package vn.ptit.procon.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidation;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.BrandAwarePlanner;
import vn.ptit.procon.planner.DayPlanner;
import vn.ptit.procon.planner.RefuelAwarePlanner;
import vn.ptit.procon.planner.RefuelProbePlanner;
import vn.ptit.procon.planner.SafeBaselinePlanner;
import vn.ptit.procon.planner.TeamCoordinatorPlanner;
import vn.ptit.procon.planner.WaitDayPlanner;
import vn.ptit.procon.protocol.DayStateMapper;
import vn.ptit.procon.protocol.HttpStatusException;
import vn.ptit.procon.protocol.ProconHttpClient;
import vn.ptit.procon.protocol.SetupMapper;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;
import vn.ptit.procon.protocol.dto.SubmissionResult;

/** Fail-closed setup-to-result lifecycle with injected day planning. */
public final class MatchRuntime {

    private static final int MAX_CONSECUTIVE_TRANSIENT_FAILURES = 8;

    private final String matchId;
    private final ProconHttpClient http;
    private final Duration pollInterval;
    private final Sleeper sleeper;
    private final SetupMapper setupMapper;
    private final DayStateMapper stateMapper;
    private final SmokeAssignmentPolicy assignmentPolicy;
    private final PlanValidator validator;
    private final DaySimulator simulator;
    private final DayPlanner planner;
    private final ParityRecorder parityRecorder;

    private int rateLimitOccurrences;

    public MatchRuntime(RuntimeConfig config) {
        this(
                config.matchId(),
                new ProconHttpClient(
                        config.baseUrl(),
                        config.matchId(),
                        config.token(),
                        config.connectTimeout(),
                        config.httpTimeout()),
                config.pollInterval(),
                duration -> Thread.sleep(duration.toMillis()),
                new SetupMapper(),
                new DayStateMapper(),
                new SmokeAssignmentPolicy(),
                new PlanValidator(),
                new DaySimulator(),
                plannerFor(config.plannerMode()),
                new ParityRecorder());
    }

    MatchRuntime(
            String matchId,
            ProconHttpClient http,
            Duration pollInterval,
            Sleeper sleeper,
            SetupMapper setupMapper,
            DayStateMapper stateMapper,
            SmokeAssignmentPolicy assignmentPolicy,
            PlanValidator validator,
            DaySimulator simulator,
            DayPlanner planner,
            ParityRecorder parityRecorder) {
        this.matchId = Objects.requireNonNull(matchId, "Match ID must not be null");
        this.http = Objects.requireNonNull(http, "HTTP client must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "Poll interval must not be null");
        if (pollInterval.toMillis() < RuntimeConfig.MINIMUM_POLL_INTERVAL_MS) {
            throw new IllegalArgumentException("Poll interval must be at least 200 ms");
        }
        this.sleeper = Objects.requireNonNull(sleeper, "Sleeper must not be null");
        this.setupMapper = Objects.requireNonNull(setupMapper, "Setup mapper must not be null");
        this.stateMapper = Objects.requireNonNull(stateMapper, "State mapper must not be null");
        this.assignmentPolicy = Objects.requireNonNull(assignmentPolicy, "Assignment policy must not be null");
        this.validator = Objects.requireNonNull(validator, "Plan validator must not be null");
        this.simulator = Objects.requireNonNull(simulator, "Simulator must not be null");
        this.planner = Objects.requireNonNull(planner, "Day planner must not be null");
        this.parityRecorder = Objects.requireNonNull(parityRecorder, "Parity recorder must not be null");
    }

    MatchRuntime(
            String matchId,
            ProconHttpClient http,
            Duration pollInterval,
            Sleeper sleeper,
            SetupMapper setupMapper,
            DayStateMapper stateMapper,
            SmokeAssignmentPolicy assignmentPolicy,
            PlanValidator validator,
            DaySimulator simulator,
            ParityRecorder parityRecorder) {
        this(matchId, http, pollInterval, sleeper, setupMapper, stateMapper, assignmentPolicy,
                validator, simulator, new WaitDayPlanner(), parityRecorder);
    }

    public MatchRuntimeResult run() throws IOException, InterruptedException {
        log("SETUP_WAITING");
        SetupDto setupDto = pollGet("SETUP_WAITING", http::getSetup);
        StaticMatchData matchData = setupMapper.toDomain(setupDto);
        log("SETUP_RECEIVED", "agents", matchData.initialAgents().size(),
                "days", matchData.dayStepBudgets().dayCount());

        List<AgentKind> assignment = assignmentPolicy.assignmentFor(matchData.initialAgents().size());
        log("ASSIGNMENT_SUBMITTED", "agents", assignment.size());
        SubmissionResult assignmentResult =
                postWhileExplicitlyNotAccepted(() -> http.postAssignment(assignment), "/assignment");
        if (!assignmentResult.valid()) {
            throw new IllegalStateException("Assignment rejected: " + submissionDiagnostic(assignmentResult));
        }
        log("ASSIGNMENT_ACCEPTED", "agents", assignment.size());

        pollGet("START_WAITING", http::getStart);
        log("MATCH_STARTED");

        int lastObservedDay = -1;
        int lastSubmittedDay = -1;
        int submittedDays = 0;
        int consecutiveStateFailures = 0;
        while (true) {
            DayStateDto stateDto;
            try {
                stateDto = http.getState();
            } catch (HttpStatusException exception) {
                if (exception.statusCode() == ProconHttpClient.HTTP_TOO_EARLY) {
                    consecutiveStateFailures = 0;
                    Optional<JsonNode> result = tryResult();
                    if (result.isPresent()) {
                        log("RESULT_RECEIVED", "submittedDays", submittedDays);
                        return result(submittedDays, result.orElseThrow());
                    }
                    sleep(pollInterval);
                    continue;
                }
                if (exception.statusCode() == ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                    rateLimitOccurrences++;
                    consecutiveStateFailures++;
                    log("RATE_LIMIT", "endpoint", "/state", "count", rateLimitOccurrences);
                    ensureRetryBudget(consecutiveStateFailures, exception);
                    sleep(rateLimitDelay());
                    continue;
                }
                Optional<JsonNode> result = tryResult();
                if (result.isPresent()) {
                    log("RESULT_RECEIVED", "submittedDays", submittedDays);
                    return result(submittedDays, result.orElseThrow());
                }
                throw exception;
            } catch (HttpTimeoutException exception) {
                consecutiveStateFailures++;
                ensureRetryBudget(consecutiveStateFailures, exception);
                log("TRANSIENT_HTTP", "endpoint", "/state", "type", "timeout");
                sleep(pollInterval);
                continue;
            } catch (IOException exception) {
                consecutiveStateFailures++;
                ensureRetryBudget(consecutiveStateFailures, exception);
                log("TRANSIENT_HTTP", "endpoint", "/state", "type", "io",
                        "attempt", consecutiveStateFailures);
                sleep(pollInterval);
                continue;
            }
            consecutiveStateFailures = 0;

            int observedDay = requireDay(stateDto);
            if (observedDay < lastObservedDay) {
                throw new IllegalStateException(
                        "Authoritative day moved backwards from " + lastObservedDay + " to " + observedDay);
            }
            DayState state = stateMapper.toDomain(stateDto, matchData, assignment);
            log("DAY_STATE_RECEIVED", "day", observedDay, "agents", state.agents().size());
            parityRecorder.observeNextState(state).ifPresent(this::logParity);
            if (observedDay > lastObservedDay && lastObservedDay >= 0) {
                log("DAY_ADVANCED", "from", lastObservedDay, "to", observedDay);
            }
            lastObservedDay = observedDay;

            if (observedDay != lastSubmittedDay) {
                TeamPlan plan = planner.plan(state);
                PlanValidation validation = validator.validate(state, plan);
                if (!validation.valid()) {
                    throw new IllegalStateException(
                            "Local " + planner.getClass().getSimpleName()
                                    + " plan rejected: " + validation.failure().orElseThrow());
                }
                DaySimulationResult prediction = simulator.simulate(state, plan);
                if (!(prediction instanceof ValidDaySimulationResult validPrediction)) {
                    throw new IllegalStateException("Validated plan did not produce a valid prediction");
                }
                log("LOCAL_PLAN_VALID", "day", observedDay,
                        "mode", planner.getClass().getSimpleName());
                log("ACTIONS_SUBMITTED", "day", observedDay);
                SubmissionResult actionResult;
                try {
                    actionResult = http.postActions(plan, state.agents().size());
                } catch (HttpStatusException exception) {
                    if (exception.statusCode() == ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                        rateLimitOccurrences++;
                        log("RATE_LIMIT", "endpoint", "/actions", "count", rateLimitOccurrences);
                        sleep(rateLimitDelay());
                    }
                    log("PARITY_MISMATCH", "day", observedDay, "httpStatus", exception.statusCode());
                    throw new IllegalStateException(
                            "Locally valid day " + observedDay + " actions were rejected by the server", exception);
                } catch (IOException exception) {
                    // The server may have accepted a POST before the response was lost. Fail closed.
                    throw new IOException(
                            "Uncertain /actions outcome for day " + observedDay + "; refusing automatic retry",
                            exception);
                }
                if (!actionResult.valid()) {
                    log("PARITY_MISMATCH", "day", observedDay,
                            "submissionType", actionResult.diagnosticType(),
                            "httpStatus", actionResult.httpStatus(),
                            "responseDay", actionResult.diagnosticDay(),
                            "reason", actionResult.diagnosticReason());
                    throw new IllegalStateException(
                            "Locally valid day " + observedDay + " actions rejected: "
                                    + submissionDiagnostic(actionResult));
                }
                lastSubmittedDay = observedDay;
                submittedDays++;
                parityRecorder.record(new ParityObservation(state, plan, validPrediction));
                log("ACTIONS_ACCEPTED", "day", observedDay);
            }

            if (submittedDays >= matchData.dayStepBudgets().dayCount()) {
                JsonNode result = pollGet("RESULT_WAITING", http::getResult);
                log("RESULT_RECEIVED", "submittedDays", submittedDays);
                return result(submittedDays, result);
            }
            sleep(pollInterval);
        }
    }

    private <T> T pollGet(String waitingEvent, InterruptibleSupplier<T> operation)
            throws IOException, InterruptedException {
        int consecutiveFailures = 0;
        while (true) {
            try {
                return operation.get();
            } catch (HttpStatusException exception) {
                if (exception.statusCode() == ProconHttpClient.HTTP_TOO_EARLY) {
                    consecutiveFailures = 0;
                    log(waitingEvent, "httpStatus", exception.statusCode());
                    sleep(pollInterval);
                    continue;
                }
                if (exception.statusCode() == ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                    rateLimitOccurrences++;
                    consecutiveFailures++;
                    log("RATE_LIMIT", "endpoint", exception.endpoint(), "count", rateLimitOccurrences);
                    ensureRetryBudget(consecutiveFailures, exception);
                    sleep(rateLimitDelay());
                    continue;
                }
                if (exception.statusCode() >= 500) {
                    consecutiveFailures++;
                    ensureRetryBudget(consecutiveFailures, exception);
                    log("TRANSIENT_HTTP", "endpoint", exception.endpoint(),
                            "httpStatus", exception.statusCode(), "attempt", consecutiveFailures);
                    sleep(pollInterval);
                    continue;
                }
                throw exception;
            } catch (HttpTimeoutException exception) {
                consecutiveFailures++;
                ensureRetryBudget(consecutiveFailures, exception);
                log("TRANSIENT_HTTP", "type", "timeout", "attempt", consecutiveFailures);
                sleep(pollInterval);
            } catch (IOException exception) {
                consecutiveFailures++;
                ensureRetryBudget(consecutiveFailures, exception);
                log("TRANSIENT_HTTP", "type", "io", "attempt", consecutiveFailures);
                sleep(pollInterval);
            }
        }
    }

    private <T> T postWhileExplicitlyNotAccepted(InterruptibleSupplier<T> operation, String endpoint)
            throws IOException, InterruptedException {
        int explicitRejections = 0;
        while (true) {
            try {
                return operation.get();
            } catch (HttpStatusException exception) {
                if (exception.statusCode() != ProconHttpClient.HTTP_TOO_EARLY
                        && exception.statusCode() != ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                    throw exception;
                }
                explicitRejections++;
                ensureRetryBudget(explicitRejections, exception);
                if (exception.statusCode() == ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                    rateLimitOccurrences++;
                    log("RATE_LIMIT", "endpoint", endpoint, "count", rateLimitOccurrences);
                    sleep(rateLimitDelay());
                } else {
                    log("ASSIGNMENT_WAITING", "httpStatus", exception.statusCode());
                    sleep(pollInterval);
                }
            } catch (IOException exception) {
                // The server may have accepted a POST before the response was lost.
                throw new IOException("Uncertain " + endpoint + " outcome; refusing automatic retry", exception);
            }
        }
    }

    private Optional<JsonNode> tryResult() throws IOException, InterruptedException {
        try {
            return Optional.of(http.getResult());
        } catch (HttpStatusException exception) {
            if (exception.statusCode() == ProconHttpClient.HTTP_TOO_EARLY) {
                return Optional.empty();
            }
            if (exception.statusCode() == ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                rateLimitOccurrences++;
                log("RATE_LIMIT", "endpoint", "/result", "count", rateLimitOccurrences);
                sleep(rateLimitDelay());
                return Optional.empty();
            }
            throw exception;
        }
    }

    private MatchRuntimeResult result(int submittedDays, JsonNode result) {
        return new MatchRuntimeResult(
                submittedDays, rateLimitOccurrences, result, parityRecorder.comparisons());
    }

    private int requireDay(DayStateDto dto) {
        if (dto == null || dto.day() == null || dto.day() < 0) {
            throw new IllegalArgumentException("Authoritative /state day must be non-negative");
        }
        return dto.day();
    }

    private Duration rateLimitDelay() {
        long bounded = Math.min(2_000, Math.max(500, pollInterval.toMillis() * 2));
        return Duration.ofMillis(bounded);
    }

    private static DayPlanner plannerFor(PlannerMode mode) {
        return switch (mode) {
            case WAIT -> new WaitDayPlanner();
            case BASELINE -> new SafeBaselinePlanner();
            case BRAND_AWARE -> new BrandAwarePlanner();
            case REFUEL_AWARE -> new RefuelAwarePlanner();
            case REFUEL_PROBE -> new RefuelProbePlanner();
            case TEAM_COORDINATED -> new TeamCoordinatorPlanner();
        };
    }

    private String submissionDiagnostic(SubmissionResult result) {
        return "type=" + result.diagnosticType()
                + " httpStatus=" + result.httpStatus()
                + " day=" + result.diagnosticDay()
                + " reason=" + result.diagnosticReason();
    }

    private void ensureRetryBudget(int failures, Exception cause) throws IOException {
        if (failures > MAX_CONSECUTIVE_TRANSIENT_FAILURES) {
            throw new IOException("Transient GET retry budget exhausted after " + failures + " failures", cause);
        }
    }

    private void sleep(Duration duration) throws InterruptedException {
        sleeper.sleep(duration);
    }

    private void logParity(ParityComparison comparison) {
        log("PARITY_OBSERVED", "day", comparison.submittedDay(),
                "position", comparison.position(), "patrolFuel", comparison.patrolFuel());
        for (AgentParityMismatch mismatch : comparison.agentMismatches()) {
            log("PARITY_AGENT_MISMATCH",
                    "day", comparison.submittedDay(),
                    "agent", mismatch.agentId().value(),
                    "predictedPosition", positionValue(mismatch.predictedPosition()),
                    "actualPosition", positionValue(mismatch.actualPosition()),
                    "predictedFuel", fuelValue(mismatch.predictedFuel()),
                    "actualFuel", fuelValue(mismatch.actualFuel()));
        }
    }

    private Object positionValue(vn.ptit.procon.domain.map.Position position) {
        return position == null ? "MISSING" : position.value();
    }

    private Object fuelValue(Integer fuel) {
        return fuel == null ? "N/A_OR_MISSING" : fuel;
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event).append(" matchId=").append(matchId);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    @FunctionalInterface
    private interface InterruptibleSupplier<T> {
        T get() throws IOException, InterruptedException;
    }
}

package vn.ptit.procon.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.engine.AgentStepUsage;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidation;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.engine.WireActionDuration;
import vn.ptit.procon.engine.WireActionReplay;
import vn.ptit.procon.engine.WireMovementForensics;
import vn.ptit.procon.planner.BrandAwarePlanner;
import vn.ptit.procon.planner.DayPlanner;
import vn.ptit.procon.planner.AnytimeTeamPlanner;
import vn.ptit.procon.planner.HarvestAnytimeTeamPlanner;
import vn.ptit.procon.planner.ContentionAwareAnytimePlanner;
import vn.ptit.procon.planner.ArrivalContentionAnytimePlanner;
import vn.ptit.procon.planner.WeightedArrivalContentionAnytimePlanner;
import vn.ptit.procon.planner.RiskAdjustedAnytimePlanner;
import vn.ptit.procon.planner.IntentAwareAnytimePlanner;
import vn.ptit.procon.planner.RefuelAwarePlanner;
import vn.ptit.procon.planner.RefuelProbePlanner;
import vn.ptit.procon.planner.SafeBaselinePlanner;
import vn.ptit.procon.planner.TeamCoordinatorPlanner;
import vn.ptit.procon.planner.WaitDayPlanner;
import vn.ptit.procon.protocol.ActionEncoder;
import vn.ptit.procon.protocol.DayStateMapper;
import vn.ptit.procon.protocol.HttpStatusException;
import vn.ptit.procon.protocol.OthersShapeInspector;
import vn.ptit.procon.protocol.OthersShapeSummary;
import vn.ptit.procon.protocol.ProconHttpClient;
import vn.ptit.procon.protocol.SetupMapper;
import vn.ptit.procon.protocol.dto.DayStateDto;
import vn.ptit.procon.protocol.dto.SetupDto;
import vn.ptit.procon.protocol.dto.SubmissionResult;

/**
 * Fail-closed setup-to-result lifecycle with injected day planning.
 *
 * <p><strong>Action-response day contract.</strong> {@code action_result.valid} is the authority for
 * acceptance. {@code action_result.day} is observed for diagnostics only; live match {@code m-5042}
 * accepted day 0 with HTTP 200 and {@code day=2}. Subsequent {@code /state} responses are the sole
 * authority for match progression:</p>
 *
 * <ul>
 *   <li>no observable day — unobservable, accepted;</li>
 *   <li>any observed response day — accepted and logged, with an anomaly marker outside the common
 *       {@code [N, N+1]} range;</li>
 *   <li>authoritative state {@code N} — accepted actions have not advanced state yet;</li>
 *   <li>authoritative state {@code N+1} — normal progression and parity observation;</li>
 *   <li>authoritative state below {@code N} — stale polling result, retried within the normal bound;</li>
 *   <li>authoritative state above {@code N+1} — a true progression gap, logged and failed closed.</li>
 * </ul>
 *
 * <p>An accepted action response is never treated as the next authoritative {@link DayState};
 * {@code /state} polling, parity checking and the no-duplicate-submission gate continue to run, and
 * {@code lastSubmittedDay} stays at {@code N}.</p>
 */
public final class MatchRuntime {

    private static final int MAX_CONSECUTIVE_TRANSIENT_FAILURES = 8;
    private static final int MAX_PARITY_RESYNC_ATTEMPTS = 4;
    private static final int MAX_FORENSIC_AGENTS = 8;
    private static final int MAX_FORENSIC_ACTIONS_PER_AGENT = 256;
    private static final int MAX_FORENSIC_TRACE_COMMANDS = 96;
    private static final Pattern SERVER_AGENT_PATTERN =
            Pattern.compile("(?:\\b(?:xe|agent)\\s+)(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SERVER_STEP_PATTERN =
            Pattern.compile("(?:\\b(?:bước|buoc|step)\\s+)(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SERVER_REQUIRED_STEPS_PATTERN =
            Pattern.compile("(?:\\b(?:cần|can|required)\\s+)(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SERVER_REMAINING_STEPS_PATTERN =
            Pattern.compile("(?:\\b(?:còn|con|remaining)\\s+)(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    private final ActionEncoder actionEncoder;
    private final ParityRecorder parityRecorder;
    private final boolean othersShapeDiagnostics;
    private final OthersValueObserver othersValueObserver;

    /**
     * The observation-only V3 boundary. It is consulted after acceptance and never before submission;
     * when shadow is OFF this is the no-op runner, which builds no planner and starts no thread.
     */
    private final V3ShadowRunner shadow;

    private int rateLimitOccurrences;
    private ParityObservation retainedObservation;

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
                plannerFor(config.plannerMode(), config.contentionDiagnostics(), config.r3RootFamilyAudit()),
                new ParityRecorder(),
                config.othersShapeDiagnostics(),
                config.othersValueDiagnostics(),
                shadowRunnerFor(config));
    }

    /**
     * The OFF path constructs nothing: no V3 planner, no evaluator, no executor and no thread. Only an
     * explicit {@code PROCON_V3_SHADOW=true} builds the real observer, and even then the observer has no
     * way to reach the wire — {@code bot-planner} does not depend on {@code bot-protocol}.
     */
    private static V3ShadowRunner shadowRunnerFor(RuntimeConfig config) {
        if (!config.v3Shadow()) {
            return V3ShadowRunner.disabled();
        }
        return V3ShadowRunner.enabled(
                config.v3ShadowMaxMillis(),
                config.v3ShadowVerbose(),
                new V3ShadowPlannerEvaluator(config.v3ShadowMaxMillis()));
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
        this(matchId, http, pollInterval, sleeper, setupMapper, stateMapper, assignmentPolicy,
                validator, simulator, planner, parityRecorder, false, false);
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
            ParityRecorder parityRecorder,
            boolean othersShapeDiagnostics) {
        this(matchId, http, pollInterval, sleeper, setupMapper, stateMapper, assignmentPolicy,
                validator, simulator, planner, parityRecorder, othersShapeDiagnostics, false);
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
            ParityRecorder parityRecorder,
            boolean othersShapeDiagnostics,
            boolean othersValueDiagnostics) {
        this(matchId, http, pollInterval, sleeper, setupMapper, stateMapper, assignmentPolicy,
                validator, simulator, planner, parityRecorder, othersShapeDiagnostics,
                othersValueDiagnostics, V3ShadowRunner.disabled());
    }

    /** The shadow injection seam. Every historical constructor reaches this one with the OFF runner. */
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
            ParityRecorder parityRecorder,
            boolean othersShapeDiagnostics,
            boolean othersValueDiagnostics,
            V3ShadowRunner shadow) {
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
        this.actionEncoder = new ActionEncoder();
        this.parityRecorder = Objects.requireNonNull(parityRecorder, "Parity recorder must not be null");
        this.othersShapeDiagnostics = othersShapeDiagnostics;
        this.othersValueObserver = new OthersValueObserver(othersValueDiagnostics, matchId);
        this.shadow = Objects.requireNonNull(shadow, "Shadow runner must not be null");
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

    /**
     * The match lifecycle, plus the bounded shadow shutdown. {@link V3ShadowRunner#finish} runs in the
     * {@code finally} block, i.e. strictly after the result has already been read, and waits at most
     * {@value V3ShadowRunner#SHUTDOWN_GRACE_MILLIS} ms — never a search budget.
     */
    public MatchRuntimeResult run() throws IOException, InterruptedException {
        try {
            return runMatch();
        } finally {
            shadow.finish(this::log);
        }
    }

    private MatchRuntimeResult runMatch() throws IOException, InterruptedException {
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
        int consecutiveStaleStates = 0;
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
            if (lastSubmittedDay >= 0 && observedDay > lastSubmittedDay + 1) {
                log("AUTHORITATIVE_DAY_GAP",
                        "submittedDay", lastSubmittedDay,
                        "authoritativeDay", observedDay,
                        "skippedDays", (lastSubmittedDay + 1) + ".." + (observedDay - 1));
                throw new IllegalStateException(
                        "Authoritative day gap after submitted day " + lastSubmittedDay
                                + ": state reports day " + observedDay
                                + "; refusing to pretend skipped days were submitted");
            }
            if (observedDay < lastObservedDay) {
                consecutiveStaleStates++;
                ensureRetryBudget(consecutiveStaleStates,
                        new IllegalStateException("Stale authoritative day " + observedDay));
                log("AUTHORITATIVE_STATE_STALE",
                        "observedDay", observedDay,
                        "lastObservedDay", lastObservedDay,
                        "submittedDay", lastSubmittedDay,
                        "attempt", consecutiveStaleStates);
                sleep(pollInterval);
                continue;
            }
            consecutiveStaleStates = 0;
            if (othersShapeDiagnostics && observedDay != lastObservedDay) {
                logOthersShape(observedDay, stateDto.others());
            }
            if (observedDay != lastObservedDay) {
                othersValueObserver.observe(
                        observedDay,
                        stateDto.others(),
                        matchData.map().cellCount(),
                        matchData.patrolFuelCapacity().value(),
                        System.out::println);
            }
            DayState state = stateMapper.toDomain(stateDto, matchData, assignment);
            log("DAY_STATE_RECEIVED", "day", observedDay, "agents", state.agents().size());
            Optional<ParityComparison> parity = parityRecorder.observeNextState(state);
            ParityObservation comparedObservation = retainedObservation;
            if (parity.isPresent()) {
                retainedObservation = null;
                logParity(parity.orElseThrow());
            }
            if (observedDay > lastObservedDay && lastObservedDay >= 0) {
                log("DAY_ADVANCED", "from", lastObservedDay, "to", observedDay);
            }
            lastObservedDay = observedDay;

            // Never plan or submit from a state already known to disagree with the accepted-day
            // prediction: attempt a bounded resync first, otherwise stop before any submission.
            if (parity.isPresent() && ParityRecorder.disagrees(parity.orElseThrow())) {
                state = resyncParity(state, comparedObservation, parity.orElseThrow(),
                        matchData, assignment, lastSubmittedDay);
            }

            if (observedDay != lastSubmittedDay) {
                // Read on the action thread before planning, so the audit can prove the shadow thread
                // later observed the very same immutable state. Empty when shadow is OFF: no work.
                String v2StateFingerprint =
                        shadow.enabled() ? V3ShadowStateSnapshotAudit.fingerprint(state) : "";
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

                int dayBudget = state.stepBudget();
                Map<AgentId, AgentStepUsage> stepUsage = validPrediction.stepUsage();

                // Encode exact wire actions
                List<List<Integer>> encodedActions = actionEncoder.encode(plan, state.agents().size());
                String serializedJson;
                try {
                    serializedJson = OBJECT_MAPPER.writeValueAsString(encodedActions);
                } catch (Exception exception) {
                    serializedJson = "[]";
                }
                String actionFingerprint = String.format("%08x", serializedJson.hashCode());

                log("ACTION_REQUEST_FINGERPRINT",
                        "day", observedDay,
                        "agentCount", state.agents().size(),
                        "serializedLength", serializedJson.length(),
                        "fingerprint", actionFingerprint);

                // Pure local wire replay directly from authoritative state and wire values. Movement
                // duration is shared local-rule provenance, not an independent server oracle.
                WireActionReplay.TeamReplayResult replayResult = WireActionReplay.replayTeam(state, encodedActions, dayBudget);

                // Compare simulated prediction vs wire replay results
                boolean allMatch = true;
                for (int agentIndex = 0; agentIndex < state.agents().size(); agentIndex++) {
                    AgentState startAgent = state.agents().get(agentIndex);
                    AgentId agentId = startAgent.id();
                    AgentStepUsage usage = stepUsage.get(agentId);
                    int simDuration = usage == null ? -1 : usage.totalSteps();

                    WireActionReplay.AgentReplayResult agentReplay = replayResult.resultFor(agentId);
                    int wireDuration = agentReplay == null ? -1 : agentReplay.totalDuration();

                    AgentState simEndAgent = validPrediction.finalAgents().stream()
                            .filter(a -> a.id().equals(agentId))
                            .findFirst().orElse(null);
                    Position simEndPos = simEndAgent == null ? null : simEndAgent.position();
                    Position wireEndPos = agentReplay == null ? null : agentReplay.finalPosition();

                    Integer simFuel = (simEndAgent != null && simEndAgent.fuel() instanceof vn.ptit.procon.domain.agent.FiniteFuel f) ? f.amount() : null;
                    Integer wireFuel = (agentReplay != null && agentReplay.finalFuel() instanceof vn.ptit.procon.domain.agent.FiniteFuel f) ? f.amount() : null;

                    boolean durationMatch = simDuration == wireDuration;
                    boolean positionMatch = Objects.equals(simEndPos, wireEndPos);
                    boolean fuelComparable = simFuel != null && wireFuel != null;
                    boolean fuelMatch = fuelComparable && Objects.equals(simFuel, wireFuel);
                    String fuelCheckReason = fuelComparable
                            ? "finiteFuelCompared"
                            : "nonPatrolOrUnavailableFuel";

                    if (!durationMatch || !positionMatch || (fuelComparable && !fuelMatch)
                            || (agentReplay != null && !agentReplay.valid())) {
                        allMatch = false;
                    }

                    // Keep ACTION_DURATION_AUDIT for backward compatibility
                    String finalAction = describeLastAction(plan, agentId);
                    log("ACTION_DURATION_AUDIT",
                            "day", observedDay,
                            "agent", agentIndex,
                            "dayBudget", dayBudget,
                            "plannerDuration", wireDuration,
                            "validatedDuration", simDuration,
                            "encodedDuration", wireDuration,
                            "actionCount", encodedActions.get(agentIndex).size(),
                            "finalAction", finalAction,
                            "paddingApplied", false);

                    log("ACTION_WIRE_REPLAY_AUDIT",
                            "day", observedDay,
                            "agent", agentIndex,
                            "dayBudget", dayBudget,
                            "simulatedDuration", simDuration,
                            "wireReplayDuration", wireDuration,
                            "simulatedEndPosition", simEndPos == null ? "null" : simEndPos.value(),
                            "wireReplayEndPosition", wireEndPos == null ? "null" : wireEndPos.value(),
                            "simulatedPatrolFuel", simFuel == null ? "UNAVAILABLE" : simFuel,
                            "wireReplayPatrolFuel", wireFuel == null ? "UNAVAILABLE" : wireFuel,
                            "actionCount", encodedActions.get(agentIndex).size(),
                            "durationMatch", durationMatch,
                            "positionMatch", positionMatch,
                            "fuelMatch", fuelComparable ? fuelMatch : "NA",
                            "fuelCheckReason", fuelCheckReason);
                }

                // Pre-submission Wire Replay Safety Guard
                if (!replayResult.allValid() || !allMatch) {
                    String reason = replayResult.firstRejection() != null
                            ? replayResult.firstRejection()
                            : "simulation vs wire replay mismatch";
                    log("WIRE_ACTION_REPLAY_MISMATCH",
                            "day", observedDay,
                            "reason", reason,
                            "fingerprint", actionFingerprint);
                    throw new IllegalStateException(
                            "Refusing to submit day " + observedDay
                                    + " actions: wire replay mismatch or invalid wire commands. Reason: "
                                    + reason);
                }

                SubmissionResult actionResult;
                try {
                    actionResult = http.postEncodedActions(encodedActions);
                    log("ACTIONS_SUBMITTED", "day", observedDay, "fingerprint", actionFingerprint);
                } catch (HttpStatusException exception) {
                    if (exception.statusCode() == ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                        rateLimitOccurrences++;
                        log("RATE_LIMIT", "endpoint", "/actions", "count", rateLimitOccurrences);
                        sleep(pollInterval);
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

                // Scope A — SERVER_ACTION_REJECTED must be surfaced FIRST, before any response-day
                // check. An invalid action_result with a mismatched response day (e.g., m-4699:
                // valid=false, responseDay=3, submittedDay=0) must surface the server's rejection
                // reason as the primary diagnostic. The day-mismatch guard below only applies to
                // VALID responses and must never mask a server-side semantic rejection.
                if (!actionResult.valid()) {
                    ServerRejectionCorrelation correlation =
                            ServerRejectionCorrelation.from(actionResult.diagnosticReason());
                    log("SERVER_ACTION_REJECTED",
                            "submittedDay", observedDay,
                            "responseDay", actionResult.diagnosticDay(),
                            "httpStatus", actionResult.httpStatus(),
                            "reason", actionResult.diagnosticReason(),
                            "fingerprint", actionFingerprint,
                            "rejectedAgent", valueOrNA(correlation.agentIndex()),
                            "serverStep", valueOrNA(correlation.serverStep()),
                            "serverRequiredSteps", valueOrNA(correlation.requiredSteps()),
                            "serverRemainingSteps", valueOrNA(correlation.remainingSteps()));
                    logRejectedActionArrays(observedDay, actionFingerprint, encodedActions,
                            correlation.agentIndex());
                    logRejectedMovementForensics(
                            observedDay, actionFingerprint, replayResult,
                            correlation.agentIndex());
                    throw new IllegalStateException(
                            "Server rejected day " + observedDay + " actions: "
                                    + submissionDiagnostic(actionResult));
                }

                // valid is the acceptance authority. The response day is diagnostic only; the next
                // authoritative /state response determines whether and how the match progressed.
                Integer reportedDay = actionResult.day();
                log("ACTION_RESPONSE_DAY_OBSERVED",
                        "submittedDay", observedDay,
                        "responseDay", actionResult.diagnosticDay(),
                        "delta", reportedDay == null ? "NA" : reportedDay - observedDay,
                        "fingerprint", actionFingerprint);
                if (reportedDay != null
                        && (reportedDay < observedDay || reportedDay > observedDay + 1)) {
                    log("ACTION_RESPONSE_DAY_ANOMALY",
                            "submittedDay", observedDay,
                            "responseDay", actionResult.diagnosticDay(),
                            "delta", reportedDay - observedDay,
                            "fingerprint", actionFingerprint);
                }
                // Only N is now submitted. A post-action advance must never mark N+1 as submitted:
                // that would make the loop skip a real action day. Equally, N is recorded so the
                // next iteration cannot resubmit it.
                lastSubmittedDay = observedDay;
                submittedDays++;
                retainedObservation = new ParityObservation(state, plan, validPrediction);
                parityRecorder.record(retainedObservation);
                logUdonObservability(observedDay, validPrediction);
                log("ACTIONS_ACCEPTED", "day", observedDay);

                // The one and only shadow hook. It sits after the POST, after action_result.valid, and
                // after lastSubmittedDay has already been advanced by the V2/R3 submission — so nothing
                // it observes, times out on, or throws can reach the wire. It never blocks.
                if (shadow.enabled()) {
                    shadow.schedule(observedDay, state, plan, v2StateFingerprint, matchId,
                            V3ShadowSubmissionAuthority.of(observedDay, plannerAuthorityLabel(), plan,
                                    state.agents().size(), encodedActions,
                                    shadow.lastV3PhysicalSignature()),
                            this::log);
                }
            }

            if (submittedDays >= matchData.dayStepBudgets().dayCount()) {
                JsonNode result = pollGet("RESULT_WAITING", http::getResult);
                log("RESULT_RECEIVED", "submittedDays", submittedDays);
                return result(submittedDays, result);
            }
            sleep(pollInterval);
        }
    }

    /**
     * Bounded parity resync for a state that disagrees with the accepted-day prediction.
     *
     * <p>A fixed number of attempts re-reads {@code /state} and re-compares the retained prediction.
     * Every attempt is consumed whether it observed a fresh state or a transient failure, so the loop
     * always terminates — there is no unbounded polling. Recovery is accepted only when the refreshed
     * state is still the same authoritative day, is not a day already submitted, keeps the configured
     * agent count and no longer disagrees on either observable dimension.
     */
    private DayState resyncParity(
            DayState authoritative,
            ParityObservation observation,
            ParityComparison firstDetection,
            StaticMatchData matchData,
            List<AgentKind> assignment,
            int lastSubmittedDay)
            throws IOException, InterruptedException {
        int comparedDay = authoritative.day().value();
        log("PARITY_RESYNC_REQUIRED",
                "day", comparedDay,
                "submittedDay", firstDetection.submittedDay(),
                "position", firstDetection.position(),
                "patrolFuel", firstDetection.patrolFuel(),
                "agentMismatches", firstDetection.agentMismatches().size(),
                "maxAttempts", MAX_PARITY_RESYNC_ATTEMPTS);
        if (observation == null) {
            throw failedResync(comparedDay, 0, "RETAINED_PREDICTION_UNAVAILABLE");
        }

        for (int attempt = 1; attempt <= MAX_PARITY_RESYNC_ATTEMPTS; attempt++) {
            sleep(pollInterval);
            DayStateDto refreshedDto;
            try {
                refreshedDto = http.getState();
            } catch (HttpStatusException exception) {
                if (exception.statusCode() == ProconHttpClient.HTTP_TOO_MANY_REQUESTS) {
                    rateLimitOccurrences++;
                    log("RATE_LIMIT", "endpoint", "/state", "count", rateLimitOccurrences);
                    sleep(rateLimitDelay());
                    continue;
                }
                if (exception.statusCode() >= 500) {
                    log("TRANSIENT_HTTP", "endpoint", "/state", "type", "resync",
                            "httpStatus", exception.statusCode(), "attempt", attempt);
                    continue;
                }
                // 425 and every other explicit status means the disagreeing day cannot be re-read.
                throw failedResync(comparedDay, attempt,
                        "STATE_UNAVAILABLE_HTTP_" + exception.statusCode());
            } catch (HttpTimeoutException exception) {
                log("TRANSIENT_HTTP", "endpoint", "/state", "type", "timeout", "attempt", attempt);
                continue;
            } catch (IOException exception) {
                log("TRANSIENT_HTTP", "endpoint", "/state", "type", "io", "attempt", attempt);
                continue;
            }

            int refreshedDay = requireDay(refreshedDto);
            if (refreshedDay != comparedDay) {
                throw failedResync(comparedDay, attempt, "DAY_MOVED_DURING_RESYNC_TO_" + refreshedDay);
            }
            if (refreshedDay <= lastSubmittedDay) {
                throw failedResync(comparedDay, attempt, "DAY_ALREADY_SUBMITTED");
            }
            DayState refreshed = stateMapper.toDomain(refreshedDto, matchData, assignment);
            if (refreshed.agents().size() != authoritative.agents().size()) {
                throw failedResync(comparedDay, attempt, "AGENT_COUNT_CHANGED");
            }

            ParityComparison recheck = ParityRecorder.compare(observation, refreshed);
            if (!ParityRecorder.disagrees(recheck)) {
                log("PARITY_RESYNC_RECOVERED",
                        "day", comparedDay,
                        "submittedDay", recheck.submittedDay(),
                        "attempt", attempt,
                        "position", recheck.position(),
                        "patrolFuel", recheck.patrolFuel());
                return refreshed;
            }
            log("PARITY_RESYNC_ATTEMPT",
                    "day", comparedDay,
                    "attempt", attempt,
                    "position", recheck.position(),
                    "patrolFuel", recheck.patrolFuel(),
                    "agentMismatches", recheck.agentMismatches().size());
        }
        throw failedResync(comparedDay, MAX_PARITY_RESYNC_ATTEMPTS, "PARITY_STILL_DISAGREES");
    }

    private IllegalStateException failedResync(int day, int attempts, String reason) {
        log("PARITY_RESYNC_FAILED", "day", day, "attempts", attempts, "reason", reason);
        return new IllegalStateException(
                "Refusing to plan or submit day " + day
                        + " from a state that disagrees with the accepted-day prediction: " + reason);
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

    static DayPlanner plannerFor(PlannerMode mode, boolean contentionDiagnostics) {
        return plannerFor(mode, contentionDiagnostics, false);
    }

    static DayPlanner plannerFor(PlannerMode mode, boolean contentionDiagnostics, boolean r3RootFamilyAudit) {
        return switch (mode) {
            case WAIT -> new WaitDayPlanner();
            case BASELINE -> new SafeBaselinePlanner();
            case BRAND_AWARE -> new BrandAwarePlanner();
            case REFUEL_AWARE -> new RefuelAwarePlanner();
            case REFUEL_PROBE -> new RefuelProbePlanner();
            case TEAM_COORDINATED -> new TeamCoordinatorPlanner();
            case ANYTIME -> new AnytimeTeamPlanner();
            case ANYTIME_HARVEST -> new HarvestAnytimeTeamPlanner();
            case ANYTIME_CONTENTION -> new ContentionAwareAnytimePlanner(
                    vn.ptit.procon.planner.AnytimePlannerConfig.defaults(), contentionDiagnostics);
            case ANYTIME_ARRIVAL_CONTENTION -> new ArrivalContentionAnytimePlanner(
                    vn.ptit.procon.planner.AnytimePlannerConfig.defaults(), contentionDiagnostics);
            case ANYTIME_WEIGHTED_ARRIVAL_CONTENTION -> new WeightedArrivalContentionAnytimePlanner(
                    vn.ptit.procon.planner.AnytimePlannerConfig.defaults(), contentionDiagnostics);
            case ANYTIME_RISK_ADJUSTED -> new RiskAdjustedAnytimePlanner(
                    vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                    vn.ptit.procon.planner.RiskAdjustmentWeights.defaults(), contentionDiagnostics);
            case ANYTIME_INTENT_AWARE -> new IntentAwareAnytimePlanner(
                    vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                    vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                    vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                    contentionDiagnostics);
            case ANYTIME_DIVERSE_INTENT_AWARE ->
                    new vn.ptit.procon.planner.DiverseIntentAwareAnytimePlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.DiverseSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_INTENT_AWARE ->
                    new vn.ptit.procon.planner.StratifiedIntentAwareAnytimePlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_COMMITMENT_AWARE ->
                    new vn.ptit.procon.planner.CommitmentAwareStratifiedPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.CommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE ->
                    new vn.ptit.procon.planner.SemiCommitmentAwareStratifiedPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_HORIZON_AWARE ->
                    new vn.ptit.procon.planner.HorizonAwareSemiCommitmentPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE ->
                    new vn.ptit.procon.planner.HarvestHorizonAwareSemiCommitmentPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE ->
                    new vn.ptit.procon.planner.RelativeMarginAwarePlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN ->
                    new vn.ptit.procon.planner.ReplacementAwareRelativeMarginPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_COUPLED_COMPETITIVE_MARGIN ->
                    new vn.ptit.procon.planner.CoupledCompetitiveMarginPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN ->
                    new vn.ptit.procon.planner.HybridCalibratedMarginPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES ->
                    new vn.ptit.procon.planner.HybridDiverseCandidatePlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID ->
                    new vn.ptit.procon.planner.HybridTeamAllocatedPlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE ->
                    new vn.ptit.procon.planner.CapacityCompetitivePlanner(
                            vn.ptit.procon.planner.AnytimePlannerConfig.defaults(),
                            vn.ptit.procon.planner.OpponentIntentConfig.defaults(),
                            vn.ptit.procon.planner.IntentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights.defaults(),
                            vn.ptit.procon.planner.StratifiedSearchConfig.defaults(),
                            contentionDiagnostics);
            case JOINT_TEAM_BEAM_V2 -> new vn.ptit.procon.planner.v2.JointTeamBeamPlanner(
                    vn.ptit.procon.planner.v2.JointTeamBeamConfig.defaults());
            case JOINT_TEAM_BEAM_V2_R3 -> new vn.ptit.procon.planner.v2.JointTeamBeamR3Planner(
                    vn.ptit.procon.planner.v2.JointTeamBeamR3Config.defaults().withRootFamilyAuditMode(
                            r3RootFamilyAudit
                                    ? vn.ptit.procon.planner.v2.R3RootFamilyAuditMode.BEST_STAGE_A_PER_FAMILY
                                    : vn.ptit.procon.planner.v2.R3RootFamilyAuditMode.OFF));
        };
    }

    private void logUdonObservability(
            int day, ValidDaySimulationResult prediction) {
        int predictedDayUdon = prediction.portionsCollectedByAgent().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        log("UDON_OBSERVABILITY",
                "day", day,
                "predictedDayUdon", predictedDayUdon,
                "authoritativeActual", "UNAVAILABLE");
    }

    private void logOthersShape(int day, JsonNode others) {
        OthersShapeSummary summary = OthersShapeInspector.inspect(others);
        log("OTHERS_SHAPE",
                "day", day,
                "nodeType", summary.nodeType(),
                "entries", summary.entries(),
                "shape", summary.shape(),
                "truncated", summary.truncated());
    }

    private String submissionDiagnostic(SubmissionResult result) {
        return "type=" + result.diagnosticType()
                + " httpStatus=" + result.httpStatus()
                + " day=" + result.diagnosticDay()
                + " reason=" + result.diagnosticReason();
    }

    private void logRejectedActionArrays(
            int submittedDay,
            String fingerprint,
            List<List<Integer>> encodedActions,
            Integer rejectedAgent) {
        List<Integer> selected = rejectedAgent != null
                && rejectedAgent >= 0
                && rejectedAgent < encodedActions.size()
                ? List.of(rejectedAgent)
                : java.util.stream.IntStream.range(0, Math.min(encodedActions.size(), MAX_FORENSIC_AGENTS))
                        .boxed()
                        .toList();
        for (Integer agentIndex : selected) {
            List<Integer> actions = encodedActions.get(agentIndex);
            boolean truncated = actions.size() > MAX_FORENSIC_ACTIONS_PER_AGENT;
            List<Integer> bounded = truncated
                    ? List.copyOf(actions.subList(0, MAX_FORENSIC_ACTIONS_PER_AGENT))
                    : actions;
            log("REJECTED_ACTION_ARRAY",
                    "submittedDay", submittedDay,
                    "agent", agentIndex,
                    "fingerprint", fingerprint,
                    "actions", bounded,
                    "truncated", truncated);
        }
    }

    private void logRejectedMovementForensics(
            int submittedDay,
            String fingerprint,
            WireActionReplay.TeamReplayResult replayResult,
            Integer rejectedAgent) {
        List<Integer> selected = rejectedAgent != null
                && rejectedAgent >= 0
                && rejectedAgent < replayResult.agentResults().size()
                ? List.of(rejectedAgent)
                : java.util.stream.IntStream.range(0, Math.min(
                                replayResult.agentResults().size(), MAX_FORENSIC_AGENTS))
                        .boxed()
                        .toList();
        for (Integer agentIndex : selected) {
            WireActionReplay.AgentReplayResult replay = replayResult.agentResults().get(agentIndex);
            int emitted = 0;
            for (WireActionReplay.ReplayedCommand command : replay.commands()) {
                if (emitted++ == MAX_FORENSIC_TRACE_COMMANDS) {
                    break;
                }
                Integer patrolFuelCost = command.wireValue() < 0
                        ? null
                        : vn.ptit.procon.rules.MovementRules.costFromSource(
                                        command.sourceTerrain(), command.sourceTraffic())
                                .map(vn.ptit.procon.domain.movement.MoveCost::patrolFuelCost)
                                .orElse(null);
                log("WIRE_MOVEMENT_DURATION_TRACE",
                        "day", submittedDay,
                        "agent", agentIndex,
                        "fingerprint", fingerprint,
                        "commandIndex", command.commandIndex(),
                        "wireValue", command.wireValue(),
                        "actionType", command.wireValue() < 0 ? "WAIT" : "MOVE",
                        "sourcePosition", command.sourcePosition().value(),
                        "destinationPosition", command.destinationPosition() == null
                                ? "NA" : command.destinationPosition().value(),
                        "sourceTerrain", command.sourceTerrain(),
                        "rawTrafficValue", valueOrNA(command.rawTrafficValue()),
                        "decodedTrafficState", command.decodedTrafficState(),
                        "localStartStep", command.startStep(),
                        "localStepCost", command.duration(),
                        "localEndStep", command.endStep(),
                        "patrolFuelCost", command.wireValue() < 0 ? "NA" : valueOrNA(patrolFuelCost));
            }
            WireMovementForensics.AlternateCostAnalysis analysis =
                    WireMovementForensics.alternateRoadCosts(replay);
            for (WireMovementForensics.AlternateCost alternate : analysis.individualRoadAlternates()) {
                log("WIRE_ROAD_ALTERNATE_COST",
                        "day", submittedDay,
                        "agent", agentIndex,
                        "fingerprint", fingerprint,
                        "commandIndex", alternate.commandIndex(),
                        "sourcePosition", alternate.sourcePosition(),
                        "localTraffic", alternate.localTraffic(),
                        "alternateTraffic", alternate.alternateTraffic(),
                        "localCost", alternate.localCost(),
                        "alternateCost", alternate.alternateCost(),
                        "alternateRouteDuration", alternate.alternateRouteDuration(),
                        "cumulativeDelta", alternate.cumulativeDelta());
            }
        }
    }

    private static String valueOrNA(Object value) {
        return value == null ? "NA" : value.toString();
    }

    private record ServerRejectionCorrelation(
            Integer agentIndex,
            Integer serverStep,
            Integer requiredSteps,
            Integer remainingSteps) {

        private static ServerRejectionCorrelation from(String reason) {
            return new ServerRejectionCorrelation(
                    firstInteger(SERVER_AGENT_PATTERN, reason),
                    firstInteger(SERVER_STEP_PATTERN, reason),
                    firstInteger(SERVER_REQUIRED_STEPS_PATTERN, reason),
                    firstInteger(SERVER_REMAINING_STEPS_PATTERN, reason));
        }

        private static Integer firstInteger(Pattern pattern, String text) {
            if (text == null) {
                return null;
            }
            Matcher matcher = pattern.matcher(text);
            return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
        }
    }

    /** Describes the last action in an agent's plan for diagnostic logging. */
    private String describeLastAction(TeamPlan plan, AgentId agentId) {
        java.util.List<vn.ptit.procon.domain.action.AgentAction> actions = plan.actionsFor(agentId);
        if (actions == null || actions.isEmpty()) {
            return "NONE";
        }
        vn.ptit.procon.domain.action.AgentAction last = actions.get(actions.size() - 1);
        if (last instanceof vn.ptit.procon.domain.action.WaitAction wait) {
            return "WAIT(" + wait.steps() + ")";
        }
        if (last instanceof vn.ptit.procon.domain.action.MoveAction move) {
            return "MOVE(" + move.direction() + ")";
        }
        return last.getClass().getSimpleName();
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

    /**
     * The label recorded as the submission authority. It is derived from the planner that actually
     * produced the plan, never from a shadow observation, so the audit cannot claim V2/R3 authority for
     * a plan some other planner built.
     */
    private String plannerAuthorityLabel() {
        return planner instanceof vn.ptit.procon.planner.v2.JointTeamBeamR3Planner
                ? V3ShadowSubmissionAuthority.V2_R3
                : planner.getClass().getSimpleName();
    }

    /** Test-only view of the shadow runner. Nothing on the action path reads this. */
    V3ShadowRunner shadowRunner() {
        return shadow;
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

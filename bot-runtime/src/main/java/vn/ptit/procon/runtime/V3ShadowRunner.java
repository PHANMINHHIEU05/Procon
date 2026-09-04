package vn.ptit.procon.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * The non-blocking shadow boundary. It observes; it decides nothing.
 *
 * <p><strong>Executor.</strong> One daemon worker, a queue of capacity one, and an explicit busy check that
 * means the queue is never actually used. If a previous day is still running the NEW day is dropped —
 * days are never accumulated, so a slow day can cost at most itself and its immediate successor.
 *
 * <p><strong>Backpressure.</strong> {@link #schedule} performs one {@code executor.submit} and returns.
 * There is no {@code get}, {@code join} or {@code awaitTermination} anywhere on the action path; the only
 * bounded wait in this class is {@link #finish}, which runs after the match result has already been read.
 *
 * <p><strong>Budget.</strong> The wall budget is enforced primarily by the search's own deadline hook and
 * classified here from measured elapsed time. An overrun day is reported {@code TIMEOUT} and its candidate
 * is discarded — never partially recorded, never submitted.
 */
public final class V3ShadowRunner implements AutoCloseable {

    /** Bounded grace at match end. Short on purpose: result handling must never wait on a search. */
    static final long SHUTDOWN_GRACE_MILLIS = 250;

    /** The runtime's own log sink, so shadow lines look exactly like every other runtime line. */
    @FunctionalInterface
    public interface Log {
        void log(String event, Object... fields);
    }

    private final boolean enabled;
    private final boolean verbose;
    private final long budgetMillis;
    private final V3ShadowEvaluator evaluator;
    private final ThreadPoolExecutor executor;
    private final LongSupplier nanoClock;
    private final V3ShadowAggregate aggregate = new V3ShadowAggregate();
    private final List<V3ShadowResult> results = Collections.synchronizedList(new ArrayList<>());
    private final List<V3ShadowComparison> comparisons = Collections.synchronizedList(new ArrayList<>());
    private final List<V3ShadowStateSnapshotAudit> snapshotAudits =
            Collections.synchronizedList(new ArrayList<>());
    private final List<V3ShadowSubmissionAuthority> authorities =
            Collections.synchronizedList(new ArrayList<>());

    private Running running;
    private volatile String lastV3PhysicalSignature = "UNAVAILABLE";

    /** The OFF runner: no evaluator, no executor, no thread, and every method a no-op. */
    public static V3ShadowRunner disabled() {
        return new V3ShadowRunner(false, false, RuntimeConfig.DEFAULT_V3_SHADOW_MAX_MILLIS, null,
                System::nanoTime);
    }

    public static V3ShadowRunner enabled(long budgetMillis, boolean verbose, V3ShadowEvaluator evaluator) {
        return new V3ShadowRunner(true, verbose, budgetMillis,
                Objects.requireNonNull(evaluator, "Evaluator must not be null"), System::nanoTime);
    }

    static V3ShadowRunner enabled(long budgetMillis, boolean verbose, V3ShadowEvaluator evaluator,
            LongSupplier nanoClock) {
        return new V3ShadowRunner(true, verbose, budgetMillis,
                Objects.requireNonNull(evaluator, "Evaluator must not be null"), nanoClock);
    }

    private V3ShadowRunner(boolean enabled, boolean verbose, long budgetMillis,
            V3ShadowEvaluator evaluator, LongSupplier nanoClock) {
        if (budgetMillis <= 0) throw new IllegalArgumentException("Shadow budget must be positive");
        this.enabled = enabled;
        this.verbose = verbose;
        this.budgetMillis = budgetMillis;
        this.evaluator = evaluator;
        this.nanoClock = Objects.requireNonNull(nanoClock, "Clock must not be null");
        this.executor = enabled ? newExecutor() : null;
    }

    private static ThreadPoolExecutor newExecutor() {
        ThreadPoolExecutor value = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "v3-shadow");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        value.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return value;
    }

    public boolean enabled() { return enabled; }

    public boolean verbose() { return verbose; }

    public long budgetMillis() { return budgetMillis; }

    public V3ShadowAggregate.Snapshot summary() { return aggregate.snapshot(); }

    public List<V3ShadowResult> results() { return List.copyOf(results); }

    public List<V3ShadowComparison> comparisons() { return List.copyOf(comparisons); }

    public List<V3ShadowStateSnapshotAudit> snapshotAudits() { return List.copyOf(snapshotAudits); }

    public List<V3ShadowSubmissionAuthority> submissionAuthorities() { return List.copyOf(authorities); }

    /** The V3 physical signature of the most recent completed day, for the authority audit. */
    public String lastV3PhysicalSignature() { return lastV3PhysicalSignature; }

    /** Structural evidence for the bounded-queue mandate; zero for the OFF runner. */
    public int queueSize() { return executor == null ? 0 : executor.getQueue().size(); }

    public int queueRemainingCapacity() {
        return executor == null ? 0 : executor.getQueue().remainingCapacity();
    }

    /**
     * Schedules one day's observation and returns. Nothing here can affect the submission that already
     * happened: the plan is read-only input, the state is immutable, and no result is consulted.
     */
    public void schedule(int day, DayState state, TeamPlan submittedPlan, String v2StateFingerprint,
            String matchId, V3ShadowSubmissionAuthority authority, Log log) {
        if (!enabled) return;
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(submittedPlan, "Submitted plan must not be null");
        Objects.requireNonNull(authority, "Submission authority audit must not be null");
        authorities.add(authority);
        log.log("V3_SHADOW_SUBMISSION_AUTHORITY", "day", day, "submittedPlanner",
                authority.submittedPlanner(), "v2PhysicalSignature", authority.v2PhysicalSignature(),
                "submittedPhysicalSignature", authority.submittedPhysicalSignature(),
                "v3PhysicalSignature", authority.v3PhysicalSignature(), "submittedMatchesV2",
                authority.submittedMatchesV2());
        aggregate.recordEligible();
        synchronized (this) {
            reapOverrun(log);
            if (running != null && !running.future.isDone()) {
                drop(day, v2StateFingerprint, log);
                return;
            }
            running = null;
            log.log("V3_SHADOW_START", "day", day, "stateFingerprint", v2StateFingerprint);
            long startedNanos = nanoClock.getAsLong();
            Running slot = new Running(day, v2StateFingerprint, startedNanos);
            try {
                slot.future = executor.submit(() ->
                        observe(slot, state, submittedPlan, matchId, log));
            } catch (RejectedExecutionException rejected) {
                drop(day, v2StateFingerprint, log);
                return;
            }
            running = slot;
            aggregate.recordStarted();
        }
    }

    private void drop(int day, String fingerprint, Log log) {
        V3ShadowResult result = V3ShadowResult.dropped(day, fingerprint);
        results.add(result);
        aggregate.recordDropped();
        aggregate.record(result);
        log.log("V3_SHADOW_DROP", "day", day, "reason", "PREVIOUS_TASK_RUNNING");
    }

    /** The worker body. Every throwable stops here; nothing propagates back towards the action loop. */
    private void observe(Running slot, DayState state, TeamPlan submittedPlan, String matchId, Log log) {
        String v3Fingerprint = V3ShadowStateSnapshotAudit.fingerprint(state);
        V3ShadowStateSnapshotAudit audit =
                V3ShadowStateSnapshotAudit.of(matchId, slot.day, slot.fingerprint, v3Fingerprint);
        snapshotAudits.add(audit);
        log.log("V3_SHADOW_STATE_SNAPSHOT_AUDIT", "day", slot.day, "stateFingerprint",
                audit.stateFingerprint(), "v2StateFingerprint", audit.v2StateFingerprint(),
                "v3StateFingerprint", audit.v3StateFingerprint(), "same", audit.same());
        V3ShadowResult result;
        try {
            V3ShadowEvaluation evaluation = evaluator.evaluate(state, submittedPlan);
            long millis = elapsedMillis(slot.startedNanos);
            result = millis > budgetMillis
                    ? V3ShadowResult.timedOut(slot.day, v3Fingerprint, millis, budgetMillis)
                    : V3ShadowResult.completed(slot.day, v3Fingerprint, millis, evaluation);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = V3ShadowResult.timedOut(slot.day, v3Fingerprint, elapsedMillis(slot.startedNanos),
                    budgetMillis);
        } catch (Throwable failure) {
            result = V3ShadowResult.failed(slot.day, v3Fingerprint, elapsedMillis(slot.startedNanos),
                    failure);
        }
        settle(slot, result, log);
    }

    /**
     * Records a day exactly once. Both the worker and an outside cancellation race to settle a slot; the
     * loser is discarded, so a cancelled overrun can never be counted twice.
     */
    private void settle(Running slot, V3ShadowResult result, Log log) {
        if (!slot.settled.compareAndSet(false, true)) return;
        results.add(result);
        aggregate.record(result);
        result.evaluation().ifPresent(evaluation -> {
            lastV3PhysicalSignature = evaluation.v3PhysicalSignature();
            comparisons.add(V3ShadowComparison.of(evaluation, result.status()));
        });
        logResult(result, log);
    }

    /**
     * Cancels a previous day that has already spent its whole budget and classifies it {@code TIMEOUT}.
     * Called only while holding the monitor, and only from {@link #schedule} and {@link #finish}.
     */
    private void reapOverrun(Log log) {
        if (running == null || running.future == null || running.future.isDone()) return;
        long millis = elapsedMillis(running.startedNanos);
        if (millis <= budgetMillis) return;
        Running overrun = running;
        overrun.future.cancel(true);
        settle(overrun, V3ShadowResult.timedOut(overrun.day, overrun.fingerprint, millis, budgetMillis),
                log);
        running = null;
    }

    private void logResult(V3ShadowResult result, Log log) {
        switch (result.status()) {
            case TIMEOUT -> log.log("V3_SHADOW_TIMEOUT", "day", result.day(), "v3Millis",
                    result.planningMillis(), "budgetMillis", budgetMillis);
            case ERROR -> log.log("V3_SHADOW_ERROR", "day", result.day(), "v3Millis",
                    result.planningMillis(), "exception", result.failureClass(), "message",
                    result.failureMessage());
            case DROPPED -> { }
            case COMPLETED -> {
                V3ShadowEvaluation value = result.evaluation().orElseThrow();
                log.log("V3_SHADOW_RESULT", "day", result.day(), "status", result.status(), "v2Own",
                        value.v2OwnSemi(), "v3RawOwn", value.rawV3OwnSemi(), "v3SafeOwn",
                        value.safeV3OwnSemi(), "v2Hybrid4", value.v2Hybrid4(), "v3RawHybrid4",
                        value.rawV3Hybrid4(), "v3SafeHybrid4", value.safeV3Hybrid4(), "deltaHybrid4",
                        value.hybridDelta4(), "samePhysical", value.samePhysicalPlan(), "v3Millis",
                        result.planningMillis(), "supportRoot", value.v3SupportRoot(), "statesExpanded",
                        value.statesExpanded(), "materialized", value.materializedPlans(), "coupled",
                        value.coupledEvaluations(), "pathfinding", value.pathfindingExecutions());
                log.log("V3_SHADOW_COMPARISON", "day", result.day(), "v2Own", value.v2OwnSemi(),
                        "v2Brands", value.v2Brands(), "v2CoupledOwn", value.v2CoupledOwn(),
                        "v2BaselineOpponent", value.v2BaselineOpponent(), "v2CoupledOpponent",
                        value.v2CoupledOpponent(), "v2Hybrid4", value.v2Hybrid4(), "rawV3Own",
                        value.rawV3OwnSemi(), "rawV3Brands", value.rawV3Brands(), "rawV3CoupledOwn",
                        value.rawV3CoupledOwn(), "rawV3BaselineOpponent", value.rawV3BaselineOpponent(),
                        "rawV3CoupledOpponent", value.rawV3CoupledOpponent(), "rawV3Hybrid4",
                        value.rawV3Hybrid4(), "ownDelta", value.ownDelta(), "hybridDelta4",
                        value.hybridDelta4(), "samePhysicalPlan", value.samePhysicalPlan(),
                        "v2SupportRoot", value.v2SupportRoot(), "v3SupportRoot", value.v3SupportRoot(),
                        "rawVerdict", value.rawVerdict(), "safeVerdict", value.safeVerdict(),
                        "v3FallbackUsed", value.v3FallbackUsed(), "status", result.status());
                if (verbose) {
                    log.log("V3_SHADOW_VERBOSE", "day", result.day(), "v2PhysicalSignature",
                            value.v2PhysicalSignature(), "v3PhysicalSignature",
                            value.v3PhysicalSignature(), "v3StrategicSignature",
                            value.v3StrategicSignature(), "deadlineBudgetExceeded",
                            value.deadlineBudgetExceeded());
                }
            }
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (nanoClock.getAsLong() - startedNanos) / 1_000_000L);
    }

    /**
     * Ends the shadow: bounded grace, then cancel, then the match summary. Called after the match result
     * has been read, so nothing here can delay a POST — and the grace is a quarter of a second, not a
     * search budget.
     */
    public void finish(Log log) {
        if (!enabled) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (running != null && !running.settled.get()) {
                settle(running, V3ShadowResult.timedOut(running.day, running.fingerprint,
                        elapsedMillis(running.startedNanos), budgetMillis), log);
                running = null;
            }
        }
        V3ShadowAggregate.Snapshot snapshot = aggregate.snapshot();
        log.log("V3_SHADOW_MATCH_SUMMARY", "daysEligible", snapshot.daysEligible(), "daysStarted",
                snapshot.daysStarted(), "daysCompleted", snapshot.daysCompleted(), "daysDropped",
                snapshot.daysDropped(), "daysTimedOut", snapshot.daysTimedOut(), "daysFailed",
                snapshot.daysFailed(), "rawV3Wins", snapshot.rawV3Wins(), "rawV3Ties",
                snapshot.rawV3Ties(), "rawV3Losses", snapshot.rawV3Losses(), "safeV3Wins",
                snapshot.safeV3Wins(), "safeV3Ties", snapshot.safeV3Ties(), "safeV3Losses",
                snapshot.safeV3Losses(), "v3ShadowMillis", snapshot.totalV3Millis(), "submittedPlanner",
                V3ShadowSubmissionAuthority.V2_R3);
    }

    @Override
    public void close() {
        if (enabled) executor.shutdownNow();
    }

    /**
     * Test-only bounded wait for the worker to go idle. It is deliberately package-private: no production
     * path may wait on the shadow, and {@code MatchRuntime} never references it.
     */
    boolean awaitIdle(long millis) {
        if (!enabled) return true;
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Future<?> future;
            synchronized (this) {
                future = running == null ? null : running.future;
            }
            if ((future == null || future.isDone()) && executor.getQueue().isEmpty()
                    && executor.getActiveCount() == 0) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** One in-flight day: its identity, when it started, its future and its single settlement guard. */
    private static final class Running {
        private final int day;
        private final String fingerprint;
        private final long startedNanos;
        private final AtomicBoolean settled = new AtomicBoolean();
        private Future<?> future;

        private Running(int day, String fingerprint, long startedNanos) {
            this.day = day;
            this.fingerprint = fingerprint;
            this.startedNanos = startedNanos;
        }
    }
}

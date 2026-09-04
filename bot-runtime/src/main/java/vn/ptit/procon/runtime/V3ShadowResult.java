package vn.ptit.procon.runtime;

import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * What V3 WOULD have selected for one day, plus how that observation ended.
 *
 * <p>The record is the whole output of the shadow path. It reaches no encoder, no HTTP client and no
 * runtime decision; {@link MatchRuntime} only logs it and folds it into the match aggregate.
 *
 * <p>{@code evaluation} is empty for every non-{@code COMPLETED} status, which is what makes "do not
 * submit a partial V3 plan" structural rather than a rule someone has to remember.
 */
public record V3ShadowResult(int day, String stateFingerprint, V3ShadowStatus status, long planningMillis,
        Optional<V3ShadowEvaluation> evaluation, String failureClass, String failureMessage) {

    public V3ShadowResult {
        Objects.requireNonNull(stateFingerprint, "State fingerprint must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(evaluation, "Evaluation must not be null");
        Objects.requireNonNull(failureClass, "Failure class must not be null");
        Objects.requireNonNull(failureMessage, "Failure message must not be null");
        if (day < 0) throw new IllegalArgumentException("Day must not be negative: " + day);
        if (planningMillis < 0) throw new IllegalArgumentException("Planning millis must not be negative");
        if (status == V3ShadowStatus.COMPLETED && evaluation.isEmpty()) {
            throw new IllegalArgumentException("A completed shadow result must carry its evaluation");
        }
        if (status != V3ShadowStatus.COMPLETED && evaluation.isPresent()) {
            throw new IllegalArgumentException("Only a completed shadow result may carry an evaluation");
        }
    }

    public static V3ShadowResult completed(int day, String fingerprint, long millis,
            V3ShadowEvaluation evaluation) {
        return new V3ShadowResult(day, fingerprint, V3ShadowStatus.COMPLETED, millis,
                Optional.of(Objects.requireNonNull(evaluation, "Evaluation must not be null")), "", "");
    }

    public static V3ShadowResult timedOut(int day, String fingerprint, long millis, long budgetMillis) {
        return new V3ShadowResult(day, fingerprint, V3ShadowStatus.TIMEOUT, millis, Optional.empty(),
                "", "shadow budget " + budgetMillis + "ms exceeded after " + millis + "ms");
    }

    /** Only the exception class and its own short message; no configuration value is ever attached. */
    public static V3ShadowResult failed(int day, String fingerprint, long millis, Throwable failure) {
        Objects.requireNonNull(failure, "Failure must not be null");
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        return new V3ShadowResult(day, fingerprint, V3ShadowStatus.ERROR, millis, Optional.empty(),
                failure.getClass().getName(), message.length() > 200 ? message.substring(0, 200) : message);
    }

    public static V3ShadowResult dropped(int day, String fingerprint) {
        return new V3ShadowResult(day, fingerprint, V3ShadowStatus.DROPPED, 0, Optional.empty(), "",
                "PREVIOUS_TASK_RUNNING");
    }

    public boolean completed() { return status == V3ShadowStatus.COMPLETED; }

    public boolean timedOut() { return status == V3ShadowStatus.TIMEOUT; }

    public boolean failed() { return status == V3ShadowStatus.ERROR; }

    public boolean dropped() { return status == V3ShadowStatus.DROPPED; }

    /** The V3 fallback flag, and only when V3 actually ran; a dropped day never claims one. */
    public boolean fallbackUsedInsideV3() {
        return evaluation.map(V3ShadowEvaluation::v3FallbackUsed).orElse(false);
    }

    public int rawV3OwnSemi() { return evaluation.map(V3ShadowEvaluation::rawV3OwnSemi).orElse(-1); }

    public int rawV3Hybrid4() { return evaluation.map(V3ShadowEvaluation::rawV3Hybrid4).orElse(0); }

    public int safeV3OwnSemi() { return evaluation.map(V3ShadowEvaluation::safeV3OwnSemi).orElse(-1); }

    public int safeV3Hybrid4() { return evaluation.map(V3ShadowEvaluation::safeV3Hybrid4).orElse(0); }

    public String v3SupportRoot() {
        return evaluation.map(V3ShadowEvaluation::v3SupportRoot).orElse("NA");
    }

    public String v3PhysicalSignature() {
        return evaluation.map(V3ShadowEvaluation::v3PhysicalSignature).orElse("NA");
    }

    public String v3StrategicSignature() {
        return evaluation.map(V3ShadowEvaluation::v3StrategicSignature).orElse("NA");
    }

    public int statesExpanded() { return evaluation.map(V3ShadowEvaluation::statesExpanded).orElse(0); }

    public int completeTeamCandidates() {
        return evaluation.map(V3ShadowEvaluation::completeTeamCandidates).orElse(0);
    }

    public int materializedPlans() {
        return evaluation.map(V3ShadowEvaluation::materializedPlans).orElse(0);
    }

    public int coupledEvaluations() {
        return evaluation.map(V3ShadowEvaluation::coupledEvaluations).orElse(0);
    }

    public int pathfindingExecutions() {
        return evaluation.map(V3ShadowEvaluation::pathfindingExecutions).orElse(0);
    }

    @Override
    public String toString() {
        return "shadowResult day=" + day + " status=" + status + " ms=" + planningMillis
                + " fingerprint=" + stateFingerprint;
    }
}

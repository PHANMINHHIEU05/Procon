package vn.ptit.procon.planner.v3;

import java.util.Objects;

/**
 * The immutable data one shadow V3 evaluation produces. Data only: no plan, no action, no callback.
 *
 * <p>Phase 2.7 runs V3 in SHADOW mode, so this record is deliberately incapable of causing a submission.
 * It carries numbers and signatures, never a {@code TeamPlan} and never anything that could reach the
 * protocol layer. The V2/R3 figures travel alongside the V3 figures because both are scored by the SAME
 * frozen objective on the SAME immutable {@code DayState}, which is the only way the comparison means
 * anything.
 *
 * <p>Raw versus safe is reported separately on purpose. {@code rawV3*} is what the bounded search found
 * on its own; {@code safeV3*} is what V3 would keep after its internal incumbent safety fallback. The
 * live submission depends on neither.
 */
public record V3ShadowEvaluation(int day, int v2OwnSemi, int v2Brands, int v2CoupledOwn,
        int v2BaselineOpponent, int v2CoupledOpponent, int v2Hybrid4, String v2SupportRoot,
        String v2PhysicalSignature, int rawV3OwnSemi, int rawV3Brands, int rawV3CoupledOwn,
        int rawV3BaselineOpponent, int rawV3CoupledOpponent, int rawV3Hybrid4,
        String rawV3PhysicalSignature, int safeV3OwnSemi, int safeV3Hybrid4,
        String safeV3PhysicalSignature, boolean v3FallbackUsed, String v3SupportRoot,
        String v3StrategicSignature, long planningMillis, boolean deadlineBudgetExceeded,
        int statesExpanded, int completeTeamCandidates, int materializedPlans, int coupledEvaluations,
        int pathfindingExecutions, Verdict rawVerdict, Verdict safeVerdict) {

    /**
     * The frozen terminal comparator's three-way answer, never a raw quantity comparison.
     *
     * <p>{@code TIE} therefore includes the case the mandate calls out: two different physical plans
     * the comparator cannot separate.
     */
    public enum Verdict { WIN, TIE, LOSS }

    public V3ShadowEvaluation {
        Objects.requireNonNull(v2SupportRoot, "V2 support root must not be null");
        Objects.requireNonNull(v2PhysicalSignature, "V2 physical signature must not be null");
        Objects.requireNonNull(rawV3PhysicalSignature, "Raw V3 physical signature must not be null");
        Objects.requireNonNull(safeV3PhysicalSignature, "Safe V3 physical signature must not be null");
        Objects.requireNonNull(v3SupportRoot, "V3 support root must not be null");
        Objects.requireNonNull(v3StrategicSignature, "V3 strategic signature must not be null");
        Objects.requireNonNull(rawVerdict, "Raw verdict must not be null");
        Objects.requireNonNull(safeVerdict, "Safe verdict must not be null");
        if (day < 0) throw new IllegalArgumentException("Day must not be negative: " + day);
        if (planningMillis < 0) throw new IllegalArgumentException("Planning millis must not be negative");
    }

    /** Alias for the mandated {@code v3PhysicalSignature} field: the RAW candidate is the V3 answer. */
    public String v3PhysicalSignature() { return rawV3PhysicalSignature; }

    public int ownDelta() { return rawV3OwnSemi - v2OwnSemi; }

    public int hybridDelta4() { return rawV3Hybrid4 - v2Hybrid4; }

    /** True when raw V3 arrived at the very plan V2/R3 submitted, so there is nothing to compare. */
    public boolean samePhysicalPlan() { return rawV3PhysicalSignature.equals(v2PhysicalSignature); }

    /** PART 37: search-time pathfinding would invalidate every millisecond figure in this record. */
    public boolean pathfindingFree() { return pathfindingExecutions == 0; }

    @Override
    public String toString() {
        return "v3shadow day=" + day + " v2=" + v2OwnSemi + "/" + v2Hybrid4 + " raw=" + rawV3OwnSemi
                + "/" + rawV3Hybrid4 + " safe=" + safeV3OwnSemi + "/" + safeV3Hybrid4 + " rawVerdict="
                + rawVerdict + " samePhysical=" + samePhysicalPlan() + " root=" + v3SupportRoot
                + " ms=" + planningMillis;
    }
}

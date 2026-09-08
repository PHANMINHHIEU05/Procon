package vn.ptit.procon.benchmark;

import java.util.Locale;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;

/**
 * One replayed live day: its shape, its V2/R3 parity proof and the full RAW/SAFE V3 comparison.
 *
 * <p>RAW and SAFE are kept apart on purpose (global rule 9): {@code raw*} is what the V3 search actually
 * produced, {@code safe*} is what the V2-incumbent-seeded fallback would have kept. A promotion argument
 * may only be built from the raw figures; the safe figures exist to prove the fallback never loses.
 */
public record V3CorpusReplayResult(
        String matchId,
        int day,
        String sizeClass,
        int mapWidth,
        int mapHeight,
        int spots,
        int agentCount,
        int stepBudget,
        String plannerAuthority,
        boolean parityOk,
        String capturedFingerprint,
        String replayedFingerprint,
        V3ShadowEvaluation evaluation) {

    /** True when this day's V3 search would not have fitted inside the live 4000 ms shadow budget. */
    public boolean exceedsLiveBudget(long liveBudgetMillis) {
        return evaluation.planningMillis() > liveBudgetMillis;
    }

    public String line() {
        V3ShadowEvaluation value = evaluation;
        return String.format(Locale.ROOT,
                "CORPUS_DAY match=%s day=%d size=%s map=%dx%d spots=%d agents=%d stepBudget=%d"
                        + " parity=%s v2Own=%d v2Hybrid4=%d rawOwn=%d rawHybrid4=%d safeOwn=%d"
                        + " safeHybrid4=%d ownDelta=%d hybridDelta4=%d rawVerdict=%s safeVerdict=%s"
                        + " fallback=%b samePhysical=%b v3Millis=%d deadlineExceeded=%b states=%d"
                        + " teams=%d materialized=%d coupled=%d pathfinding=%d v2Root=%s v3Root=%s",
                matchId, day, sizeClass, mapWidth, mapHeight, spots, agentCount, stepBudget,
                parityOk ? "OK" : "MISMATCH", value.v2OwnSemi(), value.v2Hybrid4(), value.rawV3OwnSemi(),
                value.rawV3Hybrid4(), value.safeV3OwnSemi(), value.safeV3Hybrid4(), value.ownDelta(),
                value.hybridDelta4(), value.rawVerdict(), value.safeVerdict(), value.v3FallbackUsed(),
                value.samePhysicalPlan(), value.planningMillis(), value.deadlineBudgetExceeded(),
                value.statesExpanded(), value.completeTeamCandidates(), value.materializedPlans(),
                value.coupledEvaluations(), value.pathfindingExecutions(), value.v2SupportRoot(),
                value.v3SupportRoot());
    }
}

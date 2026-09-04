package vn.ptit.procon.planner.v3;

import java.util.Objects;

/**
 * {@code V3_MATERIALIZATION_EFFICIENCY} — PART 19. Whether the frozen materialisation budget is spent on
 * distinct, useful, legal plans, or wasted on near-duplicates before the good one is reached.
 *
 * <p>PART 18 is the change this measures: materialisation is LATE. Only complete teams that already survived
 * the composition order and the structural diversity filter consume a slot, so
 * {@code bestOwnFoundAtMaterializationIndex} answers directly whether the ordering was right — index zero
 * means the very first plan the search chose to materialise was already the best it would find.
 */
public record V3MaterializationEfficiency(int completeTeamCandidatesSeen, int materializedPlans, int validPlans,
        int uniqueOwnScores, int uniquePhysicalPlans, int bestOwnFoundAtMaterializationIndex,
        int bestHybridFoundAtMaterializationIndex, boolean materializationCapReached, int materializationCap) {

    public V3MaterializationEfficiency {
        if (materializationCap <= 0) throw new IllegalArgumentException("Cap must be positive");
    }

    /** How many of the materialised plans the frozen validator accepted. */
    public double validShare() {
        return materializedPlans == 0 ? 0.0 : (double) validPlans / materializedPlans;
    }

    /** PART 19: the best plan should be found early, not after the budget is nearly gone. */
    public boolean bestFoundEarly() {
        return materializedPlans == 0 || bestOwnFoundAtMaterializationIndex * 2 <= materializedPlans;
    }

    /** PART 18: nothing partial ever consumed a slot, so every slot bought a complete legal candidate. */
    public boolean lateMaterialization() {
        return materializedPlans <= completeTeamCandidatesSeen && materializedPlans <= materializationCap;
    }

    public static V3MaterializationEfficiency of(StrategicTeamComposition.Counters counters, int cap) {
        Objects.requireNonNull(counters, "Counters must not be null");
        return new V3MaterializationEfficiency(counters.completeTeamCandidates(), counters.materializedPlans(),
                counters.validPlans(), counters.uniqueOwnScores(), counters.uniquePhysicalPlans(),
                counters.bestOwnAtMaterializationIndex(), counters.bestHybridAtMaterializationIndex(),
                counters.materializationCapReached(), cap);
    }

    @Override
    public String toString() {
        return "materialization complete=" + completeTeamCandidatesSeen + " materialized=" + materializedPlans
                + "/" + materializationCap + " valid=" + validPlans + " uniqueOwn=" + uniqueOwnScores
                + " uniquePhysical=" + uniquePhysicalPlans + " bestOwnAt=" + bestOwnFoundAtMaterializationIndex
                + " bestHybridAt=" + bestHybridFoundAtMaterializationIndex + " capReached="
                + materializationCapReached;
    }
}

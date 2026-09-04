package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;

/**
 * {@code V3_TEAM_COMBINATION_RECALL} — PART 6. Whether the WHOLE V2 witness team, not just its individual
 * routes, is reachable by the bounded composition search before any cap intervenes.
 *
 * <p>PART 21 governs how this is measured: the witness is never protected, never injected and never given a
 * bonus. It is looked up by string key in the completed and materialised lists of an ORDINARY run, so a hit
 * here means the ordinary search order really did arrive there.
 */
public record V3TeamCombinationRecall(String requiredSupportRootSignature, String requiredTeamSignature,
        boolean requiredRoutesAllRetained, boolean requiredSupportRootRetained, boolean combinationRepresentable,
        boolean combinationGenerated, int combinationRankIfGenerated, int teamCandidatesVisitedBeforeRequired,
        boolean combinationMaterialized, int materializedBeforeRequired, boolean capReachedBeforeRequired,
        int completeTeamCandidates, int materializedPlans, CombinationLoss firstCombinationLoss,
        String lossEvidence) {

    /**
     * Why the whole tuple was lost. {@code COMBINATION_REACHED} is the one non-loss value and exists so the
     * record never has to carry a null; every other value is one the mandate names.
     */
    public enum CombinationLoss { COMBINATION_REACHED, TEAM_PRODUCT_ORDER, TEAM_CANDIDATE_CAP,
        MATERIALIZATION_CAP, PARTIAL_COMBINATION_PRUNE, STOCK_CONFLICT_REJECTION, CHRONOLOGY_REJECTION,
        OTHER_PROVEN }

    public V3TeamCombinationRecall {
        Objects.requireNonNull(requiredSupportRootSignature, "Support root must not be null");
        Objects.requireNonNull(requiredTeamSignature, "Team signature must not be null");
        Objects.requireNonNull(firstCombinationLoss, "Loss must not be null");
        Objects.requireNonNull(lossEvidence, "Loss evidence must not be null");
    }

    /** The PART 21 hard-desired outcome: the witness tuple was reached under the ordinary bounded search. */
    public boolean reached() { return firstCombinationLoss == CombinationLoss.COMBINATION_REACHED; }

    /**
     * Measures recall of the witness tuple against one ordinary composition run.
     *
     * @param ordered the merged completed teams in composition order — the order the terminal slots are taken in
     * @param materialized the teams that actually consumed a materialisation slot, in the order they did
     */
    public static V3TeamCombinationRecall of(V3WitnessRouteDecomposition witness,
            StrategicTeamComposition.Outcome outcome, StrategicSearchConfig config) {
        Objects.requireNonNull(witness, "Witness must not be null");
        Objects.requireNonNull(outcome, "Outcome must not be null");
        String root = witness.supportRootSignature();
        String team = witness.teamSignature();
        Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios =
                outcome.portfoliosByRoot().getOrDefault(root, Map.of());
        boolean rootRetained = outcome.portfoliosByRoot().containsKey(root);
        boolean routesRetained = rootRetained && witness.routes().stream().allMatch(route ->
                route.isEmpty() || (portfolios.containsKey(route.patrolId())
                        && portfolios.get(route.patrolId()).contains(route.signature())));
        List<StrategicTeamComposition.ComposedTeam> ordered = outcome.completed();
        int rank = indexOf(ordered, root, team);
        int materialIndex = indexOf(outcome.materialized(), root, team);
        StrategicTeamComposition.Counters counters = outcome.counters();
        return new V3TeamCombinationRecall(root, team, routesRetained, rootRetained,
                routesRetained && rootRetained, rank >= 0, rank,
                rank >= 0 ? rank : ordered.size(), materialIndex >= 0,
                materialIndex >= 0 ? materialIndex : outcome.materialized().size(),
                materialIndex < 0 && counters.materializationCapReached(), ordered.size(),
                counters.materializedPlans(),
                loss(rootRetained, routesRetained, rank, materialIndex, counters, config),
                evidence(rootRetained, routesRetained, rank, materialIndex, witness, portfolios));
    }

    /**
     * The one-line proof behind {@link #firstCombinationLoss()}, so {@code OTHER_PROVEN} is never a shrug:
     * it names the exact PATROLs whose witness route the bounded portfolio failed to retain.
     */
    private static String evidence(boolean rootRetained, boolean routesRetained, int rank, int materialIndex,
            V3WitnessRouteDecomposition witness, Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios) {
        if (materialIndex >= 0) return "reached at materialization index " + materialIndex;
        if (!rootRetained) return "support root " + witness.supportRootSignature() + " not admitted";
        if (!routesRetained) {
            List<AgentId> missing = witness.routes().stream()
                    .filter(route -> !route.isEmpty())
                    .filter(route -> !portfolios.containsKey(route.patrolId())
                            || !portfolios.get(route.patrolId()).contains(route.signature()))
                    .map(V3WitnessRouteDecomposition.PatrolRoute::patrolId).toList();
            return "route portfolio under this root did not retain " + missing;
        }
        return rank >= 0 ? "generated at team rank " + rank + " but never materialised"
                : "every witness route retained, yet the tuple was never composed";
    }

    private static CombinationLoss loss(boolean rootRetained, boolean routesRetained, int rank,
            int materialIndex, StrategicTeamComposition.Counters counters, StrategicSearchConfig config) {
        if (materialIndex >= 0) return CombinationLoss.COMBINATION_REACHED;
        if (!rootRetained || !routesRetained) return CombinationLoss.OTHER_PROVEN;
        if (rank >= 0) {
            return counters.materializationCapReached() ? CombinationLoss.MATERIALIZATION_CAP
                    : CombinationLoss.TEAM_PRODUCT_ORDER;
        }
        if (counters.partialStatesExpanded() >= config.maxStrategicExpandedStates()) {
            return CombinationLoss.TEAM_CANDIDATE_CAP;
        }
        if (counters.chronologyRejections() > 0) return CombinationLoss.CHRONOLOGY_REJECTION;
        return CombinationLoss.PARTIAL_COMBINATION_PRUNE;
    }

    private static int indexOf(List<StrategicTeamComposition.ComposedTeam> teams, String root, String team) {
        for (int index = 0; index < teams.size(); index++) {
            TeamCompositionState value = teams.get(index).team();
            if (value.supportRoot().signature().equals(root) && value.routeSignatures().equals(team)) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "teamRecall root=" + requiredSupportRootSignature + " routesRetained="
                + requiredRoutesAllRetained + " generated=" + combinationGenerated + " rank="
                + combinationRankIfGenerated + " of " + completeTeamCandidates + " materializedAt="
                + materializedBeforeRequired + " of " + materializedPlans + " loss=" + firstCombinationLoss
                + " (" + lossEvidence + ")";
    }
}

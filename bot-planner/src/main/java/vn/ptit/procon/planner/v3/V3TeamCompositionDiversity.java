package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * {@code V3_TEAM_COMPOSITION_DIVERSITY} — PART 14. Whether the bounded team frontier holds structurally
 * different TEAM decisions, or many variants of one team that differ only in a low-consequence route.
 *
 * <p>Each axis is counted on its own, and the conjunction — {@link TeamCompositionState#diversityKey()} —
 * is the headline. {@code lowConsequenceVariantShare} is the number the mandate is really asking about: how
 * many retained teams add no new claimed-cell set at all.
 */
public record V3TeamCompositionDiversity(int teams, int distinctSupportRoots, int distinctFirstTargetVectors,
        int distinctEndPositionVectors, int distinctClaimFingerprints, int distinctRegionSequenceVectors,
        int distinctResponsibilityVectors, int distinctDiversityKeys, int distinctPhysicalSignatures,
        int lowConsequenceVariants) {

    public V3TeamCompositionDiversity {
        if (teams < 0) throw new IllegalArgumentException("Teams must be >= 0");
    }

    /** PART 13: the share of the frontier spent on teams that claim nothing new. */
    public double lowConsequenceVariantShare() {
        return teams == 0 ? 0.0 : (double) lowConsequenceVariants / teams;
    }

    /** True when the frontier is not dominated by one team plus cosmetic variants of it. */
    public boolean structurallyDiverse() {
        return teams <= 1 || (distinctDiversityKeys >= 2 && distinctClaimFingerprints >= 2);
    }

    public static V3TeamCompositionDiversity of(List<StrategicTeamComposition.ComposedTeam> teams) {
        Objects.requireNonNull(teams, "Teams must not be null");
        List<TeamCompositionState> states = teams.stream().map(StrategicTeamComposition.ComposedTeam::team).toList();
        int claims = (int) states.stream().map(V3TeamCompositionDiversity::claimFingerprint).distinct().count();
        return new V3TeamCompositionDiversity(states.size(),
                (int) states.stream().map(state -> state.supportRoot().signature()).distinct().count(),
                (int) states.stream().map(V3TeamCompositionDiversity::firstTargets).distinct().count(),
                (int) states.stream().map(V3TeamCompositionDiversity::endPositions).distinct().count(),
                claims,
                (int) states.stream().map(V3TeamCompositionDiversity::regionSequences).distinct().count(),
                (int) states.stream().map(V3TeamCompositionDiversity::responsibility).distinct().count(),
                (int) states.stream().map(TeamCompositionState::diversityKey).distinct().count(),
                (int) states.stream().map(TeamCompositionState::routeSignatures).distinct().count(),
                Math.max(0, states.size() - claims));
    }

    private static List<Integer> firstTargets(TeamCompositionState state) {
        return state.assigned().stream().map(route -> route.firstTarget().value()).toList();
    }

    private static List<Integer> endPositions(TeamCompositionState state) {
        return state.assigned().stream().map(route -> route.lastTarget().value()).toList();
    }

    private static List<Integer> claimFingerprint(TeamCompositionState state) {
        return state.assigned().stream().flatMap(route -> route.targetSet().stream()).map(Position::value)
                .distinct().sorted().toList();
    }

    private static List<List<Integer>> regionSequences(TeamCompositionState state) {
        return state.assigned().stream().map(StrategicRouteCandidate::regionSequence).toList();
    }

    /** PART 14's "high-level patrol responsibility": how much of the team's load each PATROL carries. */
    private static List<Integer> responsibility(TeamCompositionState state) {
        return state.assigned().stream().map(StrategicRouteCandidate::soloPotential).toList();
    }

    @Override
    public String toString() {
        return "teamDiversity n=" + teams + " roots=" + distinctSupportRoots + " first="
                + distinctFirstTargetVectors + " end=" + distinctEndPositionVectors + " claims="
                + distinctClaimFingerprints + " regions=" + distinctRegionSequenceVectors + " keys="
                + distinctDiversityKeys + " lowConsequence=" + lowConsequenceVariants;
    }
}

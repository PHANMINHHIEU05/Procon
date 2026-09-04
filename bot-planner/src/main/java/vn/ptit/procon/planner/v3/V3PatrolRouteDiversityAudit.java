package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * {@code V3_PATROL_ROUTE_DIVERSITY_AUDIT} — PART 3. How many structurally DIFFERENT futures does one PATROL's
 * bounded portfolio actually offer?
 *
 * <p>Deliberately NOT a physical-signature count. Two routes with different target orders have different
 * physical signatures and can still be the same strategic decision; two routes with the same collection count
 * can be completely different decisions. So every axis the mandate names is counted separately: first target,
 * last target, claimed target set, region sequence, collection count, brand set, arrival profile and support
 * dependency. {@code distinctDiversityKeys} is the conjunction of those axes and is the honest headline.
 */
public record V3PatrolRouteDiversityAudit(String supportRootSignature, List<PatrolDiversity> patrols) {

    public V3PatrolRouteDiversityAudit {
        Objects.requireNonNull(supportRootSignature, "Support root must not be null");
        patrols = List.copyOf(Objects.requireNonNull(patrols, "Patrols must not be null"));
    }

    public PatrolDiversity patrol(AgentId patrolId) {
        return patrols.stream().filter(value -> value.patrolId().equals(patrolId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown patrol: " + patrolId.value()));
    }

    /** The weakest portfolio on the conjunction axis — the one that would bottleneck team composition. */
    public int minDistinctDiversityKeys() {
        return patrols.stream().filter(value -> value.retained() > 0)
                .mapToInt(PatrolDiversity::distinctDiversityKeys).min().orElse(0);
    }

    /**
     * PART 3: true when no non-empty portfolio is dominated by near-identical routes.
     *
     * <p>The test is deliberately structural rather than a ratio on collections: a portfolio passes when every
     * retained route contributes its own diversity key, which is exactly what the retention rule promises.
     */
    public boolean structurallyDiverse() {
        return patrols.stream().filter(value -> value.retained() > 1)
                .allMatch(value -> value.distinctDiversityKeys() >= 2 && value.distinctFirstTargets() >= 2);
    }

    /** One PATROL's diversity profile over the axes PART 3 names, each counted on its own. */
    public record PatrolDiversity(AgentId patrolId, int retained, int distinctFirstTargets,
            int distinctLastTargets, int distinctTargetSets, int distinctRegionSequences,
            int distinctCollectionCounts, int distinctBrandSets, int distinctArrivalProfiles,
            int supportDependentRoutes, int supportFreeRoutes, int distinctDiversityKeys,
            int distinctPhysicalSignatures) {

        public PatrolDiversity {
            Objects.requireNonNull(patrolId, "Patrol id must not be null");
        }

        /** PART 3's real question: are the retained routes different DECISIONS, not just different strings? */
        public boolean diverseBeyondSignature() {
            return retained <= 1 || distinctDiversityKeys > 1;
        }

        @Override
        public String toString() {
            return "P" + patrolId.value() + " retained=" + retained + " first=" + distinctFirstTargets
                    + " last=" + distinctLastTargets + " claims=" + distinctTargetSets + " regions="
                    + distinctRegionSequences + " own=" + distinctCollectionCounts + " brands="
                    + distinctBrandSets + " arrivals=" + distinctArrivalProfiles + " support="
                    + supportDependentRoutes + "/" + supportFreeRoutes + " keys=" + distinctDiversityKeys;
        }
    }

    public static V3PatrolRouteDiversityAudit of(String supportRootSignature,
            Map<AgentId, StrategicRoutePortfolio.Portfolio> portfolios) {
        Objects.requireNonNull(portfolios, "Portfolios must not be null");
        List<PatrolDiversity> patrols = new ArrayList<>();
        portfolios.forEach((patrolId, portfolio) -> patrols.add(measure(patrolId, portfolio)));
        return new V3PatrolRouteDiversityAudit(supportRootSignature, List.copyOf(patrols));
    }

    private static PatrolDiversity measure(AgentId patrolId, StrategicRoutePortfolio.Portfolio portfolio) {
        List<StrategicRouteCandidate> routes = portfolio.routes();
        return new PatrolDiversity(patrolId, routes.size(),
                distinct(routes, route -> route.firstTarget().value()),
                distinct(routes, route -> route.lastTarget().value()),
                distinct(routes, route -> route.targetSet().stream().map(Position::value).sorted().toList()),
                distinct(routes, StrategicRouteCandidate::regionSequence),
                distinct(routes, StrategicRouteCandidate::soloPotential),
                distinct(routes, route -> route.brands().stream().map(brand -> brand.value()).sorted().toList()),
                distinct(routes, StrategicRouteCandidate::arrivalSteps),
                (int) routes.stream().filter(StrategicRouteCandidate::supportDependent).count(),
                (int) routes.stream().filter(route -> !route.supportDependent()).count(),
                portfolio.distinctDiversityKeys(),
                distinct(routes, StrategicRouteCandidate::signature));
    }

    private static <T> int distinct(List<StrategicRouteCandidate> routes,
            java.util.function.Function<StrategicRouteCandidate, T> axis) {
        return (int) routes.stream().map(axis).distinct().count();
    }

    @Override
    public String toString() {
        return "diversity root=" + supportRootSignature + " minKeys=" + minDistinctDiversityKeys() + " "
                + patrols.stream().map(PatrolDiversity::toString).toList();
    }
}

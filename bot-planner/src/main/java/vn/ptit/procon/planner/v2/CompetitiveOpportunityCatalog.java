package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.OpponentCollectionEligibility;

/** Daily cached own/opponent arrival catalog; no route search occurs during beam expansion. */
final class CompetitiveOpportunityCatalog {
    public static final boolean STRICT_EARLIER_CLAIMS = Boolean.parseBoolean(
            System.getProperty("procon.competitive.strict_earlier_claims",
                    System.getenv("PROCON_COMPETITIVE_STRICT_EARLIER_CLAIMS") != null
                            ? System.getenv("PROCON_COMPETITIVE_STRICT_EARLIER_CLAIMS")
                            : "false"));
    private final DayState state;
    private final JointRouteCatalog routes;
    private final Map<Position, List<Integer>> opponentEtas;
    private final List<UdonSpot> spots;

    private CompetitiveOpportunityCatalog(DayState state, JointRouteCatalog routes,
            Map<Position, List<Integer>> opponentEtas) {
        this.state = state;
        this.routes = routes;
        Map<Position, List<Integer>> copy = new LinkedHashMap<>();
        opponentEtas.forEach((position, values) -> copy.put(position, List.copyOf(values.stream().sorted().toList())));
        this.opponentEtas = Map.copyOf(copy);
        this.spots = routes.spots();
    }

    static CompetitiveOpportunityCatalog forState(DayState state, JointRouteCatalog routes) {
        Map<Position, List<Integer>> opponentEtas = new HashMap<>();
        for (ObservedOtherGroup group : state.observedOthers()) {
            for (ObservedOtherAgent agent : group.agents()) {
                if (!OpponentCollectionEligibility.RAW_KIND_ZERO_COLLECTS.collectsUdon(agent)) continue;
                for (UdonSpot spot : routes.spots()) {
                    int eta = routes.routes(agent.position(), spot.position()).stream()
                            .filter(route -> route.route().stepsUsed() <= state.stepBudget())
                            .filter(route -> agent.fuel() <= 0 || route.route().fuelUsed() <= agent.fuel())
                            .mapToInt(route -> route.route().stepsUsed()).min().orElse(Integer.MAX_VALUE);
                    if (eta < Integer.MAX_VALUE) opponentEtas
                            .computeIfAbsent(spot.position(), ignored -> new ArrayList<>()).add(eta);
                }
            }
        }
        return new CompetitiveOpportunityCatalog(state, routes, opponentEtas);
    }

    CompetitiveOpportunity describe(JointTeamSearchState current,
            JointTeamSearchState.PatrolPrefix patrol, UdonSpot target,
            JointRouteCatalog.CatalogRoute route) {
        int ownEta = patrol.elapsedSteps() + route.route().stepsUsed();
        List<Integer> opponentArrivals = opponentEtas.getOrDefault(target.position(), List.of());
        int opponentEta = opponentArrivals.isEmpty() ? -1 : opponentArrivals.getFirst();
        int raceMargin = opponentEta < 0 ? Integer.MAX_VALUE : opponentEta - ownEta;
        int stock = current.timeline().remainingStock().getOrDefault(target.position(), 0);
        int earlierClaims = (int) opponentArrivals.stream()
                .filter(eta -> STRICT_EARLIER_CLAIMS ? eta < ownEta : eta <= ownEta).count();
        int expected = Math.max(0, Math.min(stock, stock - earlierClaims));
        int bestOwnEta = Integer.MAX_VALUE;
        for (JointTeamSearchState.PatrolPrefix other : current.patrols().values()) {
            if (other.stopped() || current.timeline().visitedBy(other.id()).contains(target.position())) continue;
            for (JointRouteCatalog.CatalogRoute candidate : routes.routes(other.position(), target.position())) {
                    if (candidate.route().stepsUsed() <= remainingSteps(other)
                        && candidate.route().fuelUsed() <= other.remainingFuel()) {
                    bestOwnEta = Math.min(bestOwnEta, other.elapsedSteps() + candidate.route().stepsUsed());
                    break;
                }
            }
        }
        int gap = bestOwnEta == Integer.MAX_VALUE ? 0 : Math.max(0, ownEta - bestOwnEta);
        boolean naturalOwner = ownEta == bestOwnEta;

        int continuationCount = 0;
        int bestContinuationMargin = Integer.MIN_VALUE;
        for (UdonSpot next : spots) {
            if (next.position().equals(target.position())
                    || current.timeline().remainingStock().getOrDefault(next.position(), 0) <= 0
                    || current.timeline().visitedBy(patrol.id()).contains(next.position())) continue;
            for (JointRouteCatalog.CatalogRoute nextRoute : routes.routes(target.position(), next.position())) {
                int remainingFuel = patrol.remainingFuel() - route.route().fuelUsed();
                int remainingSteps = state.stepBudget() - ownEta;
                if (nextRoute.route().stepsUsed() <= remainingSteps
                        && nextRoute.route().fuelUsed() <= remainingFuel) {
                    List<Integer> nextOpponents = opponentEtas.getOrDefault(next.position(), List.of());
                    int nextOpponentEta = nextOpponents.isEmpty() ? -1 : nextOpponents.getFirst();
                    int nextMargin = nextOpponentEta < 0 ? state.stepBudget()
                            : nextOpponentEta - (ownEta + nextRoute.route().stepsUsed());
                    if (nextMargin >= 0) {
                        continuationCount++;
                        bestContinuationMargin = Math.max(bestContinuationMargin, nextMargin);
                    }
                    break;
                }
            }
        }
        if (bestContinuationMargin == Integer.MIN_VALUE) bestContinuationMargin = 0;
        int continuationValue = continuationCount + (expected > 0 ? 1 : 0);
        return new CompetitiveOpportunity(target.position(), target.brand(), stock, patrol.id(), ownEta,
                opponentEta, raceMargin, expected, route.route().stepsUsed(),
                !current.timeline().brands().contains(target.brand()), continuationCount,
                bestContinuationMargin, continuationValue, gap, naturalOwner);
    }

    private int remainingSteps(JointTeamSearchState.PatrolPrefix patrol) {
        return Math.max(0, state.stepBudget() - patrol.elapsedSteps());
    }
}

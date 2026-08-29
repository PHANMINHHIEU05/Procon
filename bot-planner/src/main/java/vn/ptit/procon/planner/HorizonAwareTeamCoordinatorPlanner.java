package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/**
 * M13-only coordinator extension. A rendezvous that has no current-day brand or collection gain is
 * accepted only when it strictly improves next-day PATROL readiness. The ordinary M7 coordinator
 * remains unchanged and proactive service is explicitly disabled on the final day.
 */
public final class HorizonAwareTeamCoordinatorPlanner extends TeamCoordinatorPlanner {

    private static final Comparator<TeamFutureReadiness> READINESS_PREFERENCE = Comparator
            .comparingInt(TeamFutureReadiness::futureReadyPatrolCount).reversed()
            .thenComparing(Comparator.comparingInt(
                    TeamFutureReadiness::totalReachableOpportunityBrands).reversed())
            .thenComparing(Comparator.comparingInt(
                    TeamFutureReadiness::minimumPatrolReadiness).reversed())
            .thenComparing(Comparator.comparingInt(
                    TeamFutureReadiness::totalReachableOpportunitySpots).reversed())
            .thenComparing(Comparator.comparingInt(
                    TeamFutureReadiness::totalProjectedPatrolFuel).reversed());

    private final DaySimulator simulator = new DaySimulator();
    private final boolean diagnostics;
    private FutureReadinessCalculator readinessCalculator;
    private TeamFutureReadiness noRefillReadiness;

    public HorizonAwareTeamCoordinatorPlanner() {
        this(new WeightedRouteFinder(), new RefuelRouteFinder(), new PlanValidator(), false);
    }

    HorizonAwareTeamCoordinatorPlanner(
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator) {
        this(patrolRouteFinder, refuelRouteFinder, validator, false);
    }

    HorizonAwareTeamCoordinatorPlanner(
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            boolean diagnostics) {
        super(patrolRouteFinder, refuelRouteFinder, validator);
        this.diagnostics = diagnostics;
    }

    @Override
    public TeamPlan plan(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        readinessCalculator = FutureReadinessCalculator.forState(state);
        noRefillReadiness = null;
        try {
            return super.plan(state);
        } finally {
            readinessCalculator = null;
            noRefillReadiness = null;
        }
    }

    @Override
    protected boolean acceptableRefuelAssignment(
            DayState state,
            CoordinationResult withoutRefill,
            RefuelAssignment candidate,
            Map<PatrolRouteKey, Optional<Route>> routeCache) {
        if (candidate.positiveTeamValue()) {
            return true;
        }
        if (candidate.teamBrandGain() != 0 || candidate.teamCollectionGain() != 0
                || state.day().value() + 1 >= state.matchData().dayStepBudgets().dayCount()) {
            return false;
        }
        CoordinationResult withRefill = coordinate(
                state, Optional.of(candidate), routeCache, false);
        Optional<TeamFutureReadiness> candidateReadiness = readiness(state, withRefill.plan());
        if (candidateReadiness.isEmpty()) {
            return false;
        }
        if (noRefillReadiness == null) {
            noRefillReadiness = readiness(state, withoutRefill.plan()).orElse(null);
        }
        return noRefillReadiness != null
                && READINESS_PREFERENCE.compare(candidateReadiness.orElseThrow(), noRefillReadiness) < 0;
    }

    @Override
    protected void logRefuelDecision(
            DayState state,
            CoordinationResult withoutRefill,
            Optional<RefuelAssignment> assignment) {
        if (!diagnostics) {
            return;
        }
        TeamFutureReadiness baseline = readiness(state, withoutRefill.plan()).orElseThrow();
        logReadinessSummary(state, baseline);
        if (assignment.isEmpty()) {
            System.out.println("TEAM_REFUEL_HORIZON_NO_ASSIGN"
                    + " day=" + state.day().value()
                    + " reason=" + (baseline.remainingFutureDays() == 0
                            ? "FINAL_DAY_NO_PROACTIVE_REFUEL" : "NO_FUTURE_READINESS_GAIN")
                    + " currentBrandDelta=0"
                    + " currentSemiCollectionDelta=0"
                    + " futureReadyPatrolDelta=0"
                    + " futureReachableSpotDelta=0"
                    + " futureReachableBrandDelta=0"
                    + " fuelRestored=0"
                    + " arrivalStep=0");
            return;
        }
        RefuelAssignment selected = assignment.orElseThrow();
        TeamPlan withRefill = coordinate(
                state, assignment, new java.util.LinkedHashMap<>(), false).plan();
        TeamFutureReadiness improved = readiness(state, withRefill).orElseThrow();
        System.out.println("TEAM_REFUEL_HORIZON_ASSIGN"
                + " day=" + state.day().value()
                + " refuelAgent=" + selected.refuel().id().value()
                + " patrolAgent=" + selected.patrol().id().value()
                + " currentBrandDelta=" + selected.teamBrandGain()
                + " currentSemiCollectionDelta=" + selected.teamCollectionGain()
                + " futureReadyPatrolDelta=" + (improved.futureReadyPatrolCount()
                        - baseline.futureReadyPatrolCount())
                + " futureReachableSpotDelta=" + (improved.totalReachableOpportunitySpots()
                        - baseline.totalReachableOpportunitySpots())
                + " futureReachableBrandDelta=" + (improved.totalReachableOpportunityBrands()
                        - baseline.totalReachableOpportunityBrands())
                + " fuelRestored=" + (state.matchData().patrolFuelCapacity().value()
                        - selected.patrolCurrentFuel())
                + " arrivalStep=" + selected.rendezvousStep());
    }

    private Optional<TeamFutureReadiness> readiness(DayState state, TeamPlan plan) {
        DaySimulationResult result = simulator.simulate(state, plan);
        return result instanceof ValidDaySimulationResult valid
                ? Optional.of(readinessCalculator.evaluate(valid))
                : Optional.empty();
    }

    private void logReadinessSummary(DayState state, TeamFutureReadiness readiness) {
        int patrolAgents = (int) state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL).count();
        System.out.println("HORIZON_FUEL_SUMMARY"
                + " day=" + state.day().value()
                + " remainingFutureDays=" + readiness.remainingFutureDays()
                + " patrolAgents=" + patrolAgents
                + " projectedTotalPatrolFuel=" + readiness.totalProjectedPatrolFuel()
                + " futureReadyPatrolCount=" + readiness.futureReadyPatrolCount()
                + " reachableOpportunitySpots=" + readiness.totalReachableOpportunitySpots()
                + " reachableOpportunityBrands=" + readiness.totalReachableOpportunityBrands()
                + " minimumPatrolReadiness=" + readiness.minimumPatrolReadiness());
    }
}
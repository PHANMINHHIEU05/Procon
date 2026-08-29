package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** M13.1 coordinator: proactive REFUEL requires a strict next-day harvest-capacity gain. */
public final class HarvestHorizonAwareTeamCoordinatorPlanner extends TeamCoordinatorPlanner {

    private final DaySimulator simulator = new DaySimulator();
    private final boolean diagnostics;
    private NextDayHarvestCapacityCalculator capacityCalculator;
    private OpponentCommitmentForecast commitmentForecast;
    private TeamNextDayHarvestCapacity baselineCapacity;
    private SemiPrimary baselineSemi;
    private final Map<RefuelAssignment, CandidateValue> candidateValues = new LinkedHashMap<>();

    public HarvestHorizonAwareTeamCoordinatorPlanner() {
        this(new WeightedRouteFinder(), new RefuelRouteFinder(), new PlanValidator(), false);
    }

    HarvestHorizonAwareTeamCoordinatorPlanner(
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
        capacityCalculator = NextDayHarvestCapacityCalculator.forState(state);
        OpponentIntentForecast intent = new OpponentIntentForecaster().forecast(
                state, OpponentIntentConfig.defaults());
        commitmentForecast = OpponentCommitmentForecast.annotate(intent);
        baselineCapacity = null;
        baselineSemi = null;
        candidateValues.clear();
        try {
            return super.plan(state);
        } finally {
            capacityCalculator = null;
            commitmentForecast = null;
            baselineCapacity = null;
            baselineSemi = null;
            candidateValues.clear();
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
        if (state.day().value() + 1 >= state.matchData().dayStepBudgets().dayCount()) {
            return false;
        }
        CandidateValue value = candidateValue(state, withoutRefill, candidate, routeCache);
        return value.currentSemi.brands >= baselineSemi.brands
                && value.currentSemi.collections >= baselineSemi.collections
                && value.capacity.betterStructuralCapacityThan(baselineCapacity);
    }

    @Override
    protected Comparator<RefuelAssignment> refuelPreference(
            DayState state,
            CoordinationResult withoutRefill,
            Map<PatrolRouteKey, Optional<Route>> routeCache) {
        return Comparator
                .comparingInt(RefuelAssignment::teamBrandGain).reversed()
                .thenComparing(Comparator.comparingInt(RefuelAssignment::teamCollectionGain).reversed())
                .thenComparing((left, right) -> -TeamNextDayHarvestCapacity.compareStructural(
                        candidateValue(state, withoutRefill, left, routeCache).capacity,
                        candidateValue(state, withoutRefill, right, routeCache).capacity))
                .thenComparing(Comparator.comparingInt((RefuelAssignment value) ->
                        state.matchData().patrolFuelCapacity().value() - value.patrolCurrentFuel()).reversed())
                .thenComparingInt(RefuelAssignment::rendezvousStep)
                .thenComparingInt(value -> value.route().stepsUsed())
                .thenComparingInt(value -> value.patrol().id().value())
                .thenComparingInt(value -> value.refuel().id().value());
    }

    @Override
    protected void logRefuelDecision(
            DayState state,
            CoordinationResult withoutRefill,
            Optional<RefuelAssignment> assignment) {
        if (!diagnostics) {
            return;
        }
        ensureBaseline(state, withoutRefill);
        logSummary(state, baselineCapacity);
        if (assignment.isEmpty()) {
            System.out.println("TEAM_REFUEL_HARVEST_HORIZON_NO_ASSIGN"
                    + " day=" + state.day().value()
                    + " reason=" + (baselineCapacity.remainingFutureDays() == 0
                            ? "FINAL_DAY_NO_PROACTIVE_REFUEL" : "NO_HARVEST_CAPACITY_GAIN")
                    + " currentSemiBrandDelta=0 currentSemiCollectionDelta=0"
                    + " weakestCapacityDelta=0 totalSpotCapacityDelta=0"
                    + " totalBrandCapacityDelta=0 fuelRestored=0 arrivalStep=0");
            return;
        }
        RefuelAssignment selected = assignment.orElseThrow();
        CandidateValue value = candidateValues.get(selected);
        if (value == null) {
            TeamPlan plan = coordinate(
                    state, assignment, new LinkedHashMap<>(), false).plan();
            value = evaluate(state, plan);
        }
        System.out.println("TEAM_REFUEL_HARVEST_HORIZON_ASSIGN"
                + " day=" + state.day().value()
                + " refuelAgent=" + selected.refuel().id().value()
                + " patrolAgent=" + selected.patrol().id().value()
                + " currentSemiBrandDelta=" + (value.currentSemi.brands - baselineSemi.brands)
                + " currentSemiCollectionDelta=" + (value.currentSemi.collections - baselineSemi.collections)
                + " minimumSpotCapacityDelta=" + (value.capacity.minimumPatrolDistinctSpots()
                        - baselineCapacity.minimumPatrolDistinctSpots())
                + " minimumBrandCapacityDelta=" + (value.capacity.minimumPatrolDistinctBrands()
                        - baselineCapacity.minimumPatrolDistinctBrands())
                + " totalSpotCapacityDelta=" + (value.capacity.totalPatrolDistinctSpotCapacity()
                        - baselineCapacity.totalPatrolDistinctSpotCapacity())
                + " totalBrandCapacityDelta=" + (value.capacity.totalPatrolDistinctBrandCapacity()
                        - baselineCapacity.totalPatrolDistinctBrandCapacity())
                + " fuelRestored=" + (state.matchData().patrolFuelCapacity().value()
                        - selected.patrolCurrentFuel())
                + " arrivalStep=" + selected.rendezvousStep());
    }

    private CandidateValue candidateValue(
            DayState state,
            CoordinationResult withoutRefill,
            RefuelAssignment candidate,
            Map<PatrolRouteKey, Optional<Route>> routeCache) {
        ensureBaseline(state, withoutRefill);
        return candidateValues.computeIfAbsent(candidate, ignored -> evaluate(state,
                coordinate(state, Optional.of(candidate), routeCache, false).plan()));
    }

    private void ensureBaseline(DayState state, CoordinationResult withoutRefill) {
        if (baselineCapacity == null) {
            CandidateValue baseline = evaluate(state, withoutRefill.plan());
            baselineCapacity = baseline.capacity;
            baselineSemi = baseline.currentSemi;
        }
    }

    private CandidateValue evaluate(DayState state, TeamPlan plan) {
        DaySimulationResult result = simulator.simulate(state, plan);
        if (!(result instanceof ValidDaySimulationResult valid)) {
            throw new IllegalStateException("Coordinated REFUEL candidate did not simulate");
        }
        SemiCommitmentCollectionAttribution attribution = new SemiCommitmentForecastEvaluator().evaluate(
                state, valid, commitmentForecast, SemiCommitmentAdjustmentWeights.defaults());
        return new CandidateValue(
                capacityCalculator.evaluate(valid),
                new SemiPrimary(attribution.semiCommitmentRealizableBrands().size(),
                        attribution.semiCommitmentRealizableCollections()));
    }

    private void logSummary(DayState state, TeamNextDayHarvestCapacity capacity) {
        System.out.println("NEXT_DAY_HARVEST_CAPACITY_SUMMARY"
                + " day=" + state.day().value()
                + " remainingFutureDays=" + capacity.remainingFutureDays()
                + " patrolAgents=" + capacity.patrols().size()
                + " nextDayStepBudget=" + capacity.nextDayStepBudget()
                + " minimumPatrolDistinctSpots=" + capacity.minimumPatrolDistinctSpots()
                + " minimumPatrolDistinctBrands=" + capacity.minimumPatrolDistinctBrands()
                + " totalPatrolDistinctSpotCapacity=" + capacity.totalPatrolDistinctSpotCapacity()
                + " totalPatrolDistinctBrandCapacity=" + capacity.totalPatrolDistinctBrandCapacity()
                + " routeCostCacheEntries=" + capacity.routeCostCacheEntries()
                + " pathfindingExecutions=" + capacity.pathfindingExecutions());
    }

    private record SemiPrimary(int brands, int collections) { }

    private record CandidateValue(
            TeamNextDayHarvestCapacity capacity,
            SemiPrimary currentSemi) { }
}
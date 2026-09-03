package vn.ptit.procon.planner.v2;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.MoveStartedEvent;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.CoupledCompetitiveBaseline;
import vn.ptit.procon.planner.CoupledCompetitiveMarginEvaluation;
import vn.ptit.procon.planner.CoupledCompetitiveRollout;
import vn.ptit.procon.planner.CoupledCompetitiveRolloutResult;
import vn.ptit.procon.planner.HybridCalibratedMarginEvaluation;
import vn.ptit.procon.planner.NextDayHarvestCapacityCalculator;
import vn.ptit.procon.planner.OpponentCommitmentForecast;
import vn.ptit.procon.planner.OpponentIntentConfig;
import vn.ptit.procon.planner.OpponentIntentForecaster;
import vn.ptit.procon.planner.PlanEvaluation;
import vn.ptit.procon.planner.SemiCommitmentAdjustmentWeights;
import vn.ptit.procon.planner.SemiCommitmentAwarePlanEvaluation;
import vn.ptit.procon.planner.SemiCommitmentCollectionAttribution;
import vn.ptit.procon.planner.SemiCommitmentForecastEvaluator;

/** Reuses the current M16.1/M19 terminal objective without applying it to partial states. */
final class JointTerminalEvaluator {

    private final DayState state;
    private final DaySimulator simulator = new DaySimulator();
    private final SemiCommitmentForecastEvaluator semiEvaluator = new SemiCommitmentForecastEvaluator();
    private final SemiCommitmentAdjustmentWeights semiWeights =
            SemiCommitmentAdjustmentWeights.defaults();
    private final OpponentCommitmentForecast commitmentForecast;
    private final CoupledCompetitiveRollout coupledRollout;
    private final CoupledCompetitiveBaseline coupledBaseline;
    private final NextDayHarvestCapacityCalculator nextDayHarvestCapacity;

    JointTerminalEvaluator(DayState state) {
        this.state = state;
        commitmentForecast = OpponentCommitmentForecast.annotate(
                new OpponentIntentForecaster().forecast(state, OpponentIntentConfig.defaults()));
        coupledRollout = CoupledCompetitiveRollout.forState(state, OpponentIntentConfig.defaults());
        coupledBaseline = coupledRollout.baseline();
        nextDayHarvestCapacity = NextDayHarvestCapacityCalculator.forState(state);
    }

    Optional<StageATerminalCandidate> evaluateStageA(TeamPlan plan, int strategicDecisionCount) {
        return evaluateStageA(plan, strategicDecisionCount, 0, "NO_REFUEL");
    }

    Optional<StageATerminalCandidate> evaluateStageA(TeamPlan plan, int strategicDecisionCount,
            int rootFamily, String rootSignature) {
        DaySimulationResult simulation = simulator.simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return Optional.empty();
        }
        PlanEvaluation base = baseEvaluation(plan, valid);
        SemiCommitmentCollectionAttribution attribution = semiEvaluator.evaluate(
                state, valid, commitmentForecast, semiWeights);
        SemiCommitmentAwarePlanEvaluation semi = new SemiCommitmentAwarePlanEvaluation(
                base,
                attribution.adjustedScore(),
                attribution.semiCommitmentRealizableBrands().size(),
                attribution.semiCommitmentRealizableCollections(),
                attribution.commitmentRealizableCollections(),
                attribution.oldForecastRealizableCollections(),
                attribution.hardClaimedFirstCollections(),
                attribution.semiClaimedFirstCollections(),
                attribution.directIntentBeforeCollections(),
                attribution.followOnIntentBeforeCollections(),
                attribution.tieCollections(),
                attribution.unforecastedCollections());
        return Optional.of(new StageATerminalCandidate(plan, valid, base, semi, strategicDecisionCount,
                rootFamily, rootFamily, rootSignature));
    }

    JointTerminalEvaluation evaluateStageB(StageATerminalCandidate candidate) {
        CoupledCompetitiveRolloutResult coupled = coupledRollout.evaluate(
                coupledBaseline, candidate.simulation());
        CoupledCompetitiveMarginEvaluation coupledEvaluation = new CoupledCompetitiveMarginEvaluation(
                candidate.semi(), coupled, nextDayHarvestCapacity.evaluate(candidate.simulation()));
        return new JointTerminalEvaluation(
                candidate.base(),
                new HybridCalibratedMarginEvaluation(
                        coupledEvaluation.semiCommitment(),
                        coupledEvaluation.coupled(),
                        coupledEvaluation.nextDayHarvestCapacity()));
    }

    /** Cheap deterministic ranking used only to bound optional Stage-B evaluation. */
    static int compareStageA(StageATerminalCandidate left, StageATerminalCandidate right) {
        int compared = Integer.compare(
                right.semi().semiCommitmentRealizableBrandCount(),
                left.semi().semiCommitmentRealizableBrandCount());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
                right.semi().semiCommitmentRealizableCollections(),
                left.semi().semiCommitmentRealizableCollections());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.base().udonTotal(), left.base().udonTotal());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.base().activePatrolCount(), left.base().activePatrolCount());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.base().remainingFuelTotal(), left.base().remainingFuelTotal());
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(left.base().movementSteps(), right.base().movementSteps());
        if (compared != 0) {
            return compared;
        }
        return left.base().deterministicSignature().compareTo(right.base().deterministicSignature());
    }

    record StageATerminalCandidate(
            TeamPlan plan,
            ValidDaySimulationResult simulation,
            PlanEvaluation base,
            SemiCommitmentAwarePlanEvaluation semi,
            int strategicDecisionCount,
            int rootFamily,
            int supportServiceCount,
            String rootSignature) {

        StageATerminalCandidate withRoot(int family, int services, String signature) {
            return new StageATerminalCandidate(plan, simulation, base, semi, strategicDecisionCount,
                    family, services, signature);
        }
    }

    int opponentPressureAt(vn.ptit.procon.domain.map.Position spot) {
        return coupledBaseline.claimsAt(spot).size();
    }

    private PlanEvaluation baseEvaluation(TeamPlan plan, ValidDaySimulationResult valid) {
        int udonTotal = valid.portionsCollectedByAgent().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        int activePatrols = 0;
        for (AgentState agent : state.agents()) {
            if (agent.kind() == AgentKind.PATROL
                    && valid.portionsCollectedByAgent().getOrDefault(agent.id(), 0) > 0) {
                activePatrols++;
            }
        }
        int remainingFuel = valid.finalAgents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .map(AgentState::fuel)
                .map(FiniteFuel.class::cast)
                .mapToInt(FiniteFuel::amount)
                .sum();
        int movementSteps = valid.events().stream()
                .filter(MoveStartedEvent.class::isInstance)
                .map(MoveStartedEvent.class::cast)
                .mapToInt(MoveStartedEvent::duration)
                .sum();
        return new PlanEvaluation(
                valid.brandsCollected().size(),
                udonTotal,
                activePatrols,
                remainingFuel,
                movementSteps,
                signature(plan));
    }

    private static String signature(TeamPlan plan) {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<AgentId, List<AgentAction>> entry : plan.actionsByAgent().entrySet()) {
            value.append(entry.getKey().value()).append(':');
            for (AgentAction action : entry.getValue()) {
                if (action instanceof MoveAction move) {
                    value.append('M').append(move.direction().name());
                } else {
                    value.append('W').append(((WaitAction) action).steps());
                }
                value.append(',');
            }
            value.append(';');
        }
        return value.toString();
    }
}

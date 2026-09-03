package vn.ptit.procon.planner.v3;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.CoupledCompetitiveRollout;
import vn.ptit.procon.planner.CoupledCompetitiveBaseline;
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

/** Public benchmark utility that reuses the frozen evaluator stack without changing V2. */
public final class FrozenObjectiveEvaluator {
    private final DayState state;
    private final DaySimulator simulator = new DaySimulator();
    private final PlanValidator validator = new PlanValidator();
    private final OpponentCommitmentForecast forecast;
    private final CoupledCompetitiveRollout rollout;
    private final CoupledCompetitiveBaseline baseline;
    private final NextDayHarvestCapacityCalculator nextDay;

    public FrozenObjectiveEvaluator(DayState state) {
        this.state = state;
        forecast = OpponentCommitmentForecast.annotate(new OpponentIntentForecaster().forecast(state));
        rollout = CoupledCompetitiveRollout.forState(state, OpponentIntentConfig.defaults());
        baseline = rollout.baseline();
        nextDay = NextDayHarvestCapacityCalculator.forState(state);
    }

    public Optional<StrategicOracleEvaluation> evaluate(TeamPlan plan) {
        if (!validator.validate(state, plan).valid()) return Optional.empty();
        DaySimulationResult simulation = simulator.simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) return Optional.empty();
        PlanEvaluation base = base(plan, valid);
        SemiCommitmentCollectionAttribution attribution = new SemiCommitmentForecastEvaluator().evaluate(
                state, valid, forecast, SemiCommitmentAdjustmentWeights.defaults());
        SemiCommitmentAwarePlanEvaluation semi = new SemiCommitmentAwarePlanEvaluation(base,
                attribution.adjustedScore(), attribution.semiCommitmentRealizableCollections() == 0 ? 0
                        : attribution.semiCommitmentRealizableBrands().size(), attribution.semiCommitmentRealizableCollections(),
                attribution.commitmentRealizableCollections(), attribution.oldForecastRealizableCollections(),
                attribution.hardClaimedFirstCollections(), attribution.semiClaimedFirstCollections(),
                attribution.directIntentBeforeCollections(), attribution.followOnIntentBeforeCollections(),
                attribution.tieCollections(), attribution.unforecastedCollections());
        var coupled = rollout.evaluate(baseline, valid);
        HybridCalibratedMarginEvaluation hybrid = new HybridCalibratedMarginEvaluation(semi, coupled, nextDay.evaluate(valid));
        return Optional.of(new StrategicOracleEvaluation(plan, base.deterministicSignature(), hybrid));
    }

    private PlanEvaluation base(TeamPlan plan, ValidDaySimulationResult valid) {
        int collections = valid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();
        int active = (int) state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL
                && valid.portionsCollectedByAgent().getOrDefault(a.id(), 0) > 0).count();
        int fuel = (int) valid.finalAgents().stream().filter(a -> a.kind() == AgentKind.PATROL)
                .map(AgentState::fuel).map(FiniteFuel.class::cast).mapToInt(FiniteFuel::amount).sum();
        int moves = valid.events().stream().filter(vn.ptit.procon.engine.MoveStartedEvent.class::isInstance)
                .map(vn.ptit.procon.engine.MoveStartedEvent.class::cast).mapToInt(vn.ptit.procon.engine.MoveStartedEvent::duration).sum();
        Set<String> brands = new LinkedHashSet<>();
        valid.events().stream().filter(vn.ptit.procon.engine.UdonCollectedEvent.class::isInstance)
                .map(vn.ptit.procon.engine.UdonCollectedEvent.class::cast).forEach(event -> brands.add(event.brand().value()));
        return new PlanEvaluation(brands.size(), collections, active, fuel, moves, signature(plan));
    }

    private String signature(TeamPlan plan) {
        StringBuilder result = new StringBuilder();
        plan.actionsByAgent().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(v -> v.value())))
                .forEach(entry -> { result.append(entry.getKey().value()).append(':'); for (AgentAction action : entry.getValue()) {
                    if (action instanceof MoveAction move) result.append('M').append(move.direction().name());
                    else result.append('W').append(((WaitAction) action).steps()); result.append(','); } result.append(';'); });
        return result.toString();
    }
}

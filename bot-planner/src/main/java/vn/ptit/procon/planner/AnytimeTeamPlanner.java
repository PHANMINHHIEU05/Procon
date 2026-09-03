package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.MoveAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.MoveStartedEvent;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** Deterministic bounded best-first search retaining M7 as a valid incumbent. */
public final class AnytimeTeamPlanner implements DayPlanner {

    private static final int MAX_CONTENTION_SPOT_DIAGNOSTICS = 8;
    private static final int MAX_CONTENTION_CANDIDATE_DIAGNOSTICS = 4;
    private static final int MAX_STRATEGY_DEPTH_DIAGNOSTICS = 8;
    private static final int MAX_OPPONENT_FULL_DAY_ROUTE_DIAGNOSTICS = 24;
    private static final int MAX_COUPLED_COMPETITIVE_EVENT_DIAGNOSTICS = 24;

    private static final Comparator<SearchState> ORIGINAL_STATE_PREFERENCE = Comparator
            .comparingInt((SearchState state) -> state.teamBrands.size()).reversed()
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.projectedCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::remainingFuel).reversed())
            .thenComparingInt(state -> state.travelSteps)
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.depth).reversed())
            .thenComparingLong(state -> state.sequence);

    private static final Comparator<SearchState> HARVEST_STATE_PREFERENCE = Comparator
            .comparingInt((SearchState state) -> state.teamBrands.size()).reversed()
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.projectedCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::remainingFuel).reversed())
            .thenComparingInt(state -> state.travelSteps)
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.depth).reversed())
            .thenComparingLong(state -> state.sequence);

    private static final Comparator<SearchState> CONTENTION_STATE_PREFERENCE = Comparator
            .comparing(AnytimeTeamPlanner::frontierMetrics, ContentionFrontierMetrics.preference());

    private static final Comparator<SearchState> ARRIVAL_CONTENTION_STATE_PREFERENCE = Comparator
            .comparingInt((SearchState state) -> state.teamBrands.size()).reversed()
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.arrivalSafeProjectedCollections).reversed())
            .thenComparingInt((SearchState state) -> state.arrivalAtRiskProjectedCollections)
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.projectedCollections).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::optimisticHarvestPotential).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::remainingUsefulSteps).reversed())
            .thenComparing(Comparator.comparingInt(
                    SearchState::remainingFuel).reversed())
            .thenComparingInt(state -> state.travelSteps)
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.depth).reversed())
            .thenComparingLong(state -> state.sequence);

    private static final Comparator<SearchState> RISK_ADJUSTED_STATE_PREFERENCE = Comparator
            .comparing(AnytimeTeamPlanner::riskAdjustedFrontierMetrics,
                    RiskAdjustedFrontierMetrics.preference());

    private static final Comparator<SearchState> INTENT_AWARE_STATE_PREFERENCE = Comparator
            .comparing(AnytimeTeamPlanner::intentAwareFrontierMetrics,
                    IntentAwareFrontierMetrics.preference());

    private static final Comparator<SearchState> COMMITMENT_AWARE_STATE_PREFERENCE = Comparator
            .comparing(AnytimeTeamPlanner::commitmentAwareFrontierMetrics,
                    CommitmentAwareFrontierMetrics.preference());

    private static final Comparator<SearchState> SEMI_COMMITMENT_AWARE_STATE_PREFERENCE = Comparator
            .comparing(AnytimeTeamPlanner::semiCommitmentAwareFrontierMetrics,
                    SemiCommitmentAwareFrontierMetrics.preference());

    // Partial states retain the unchanged M12.1 frontier tuple. The full terminal
    // evaluation below is where geometric future readiness is applied.
    private static final Comparator<SearchState> HORIZON_AWARE_STATE_PREFERENCE =
            SEMI_COMMITMENT_AWARE_STATE_PREFERENCE;

    // M14 keeps the same partial-state data and bounded frontier architecture, but protects
    // own semi-realizable count above its soft score. Exact denial is evaluated on complete plans.
    private static final Comparator<SearchState> RELATIVE_MARGIN_STATE_PREFERENCE = Comparator
            .comparingInt((SearchState state) -> state.semiCommitment.realizableTeamBrands().size()).reversed()
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.semiCommitment.realizableCollections()).reversed())
            .thenComparing(Comparator.comparingInt(
                    (SearchState state) -> state.semiCommitment.adjustedScore()).reversed())
            .thenComparing(HORIZON_AWARE_STATE_PREFERENCE);

    private static final Comparator<RefuelSchedule> REFUEL_ROOT_PREFERENCE = Comparator
            .comparingInt(RefuelSchedule::currentFuel)
            .thenComparingInt(schedule -> schedule.route.stepsUsed())
            .thenComparingInt(schedule -> schedule.patrolId.value())
            .thenComparingInt(schedule -> schedule.refuelId.value());

    private final AnytimePlannerConfig config;
    private final AnytimeSearchPolicy policy;
    private final WeightedRouteFinder patrolRouteFinder;
    private final RefuelRouteFinder refuelRouteFinder;
    private final PlanValidator validator;
    private final DaySimulator simulator;
    private final DayPlanner teamCoordinator;
    private final DayPlanner contentionFallback;
    private final RiskAdjustmentWeights riskAdjustmentWeights;
    private final OpponentIntentConfig opponentIntentConfig;
    private final IntentAdjustmentWeights intentAdjustmentWeights;
    private final CommitmentAdjustmentWeights commitmentAdjustmentWeights;
    private final SemiCommitmentAdjustmentWeights semiCommitmentAdjustmentWeights;
    private final DiverseSearchConfig diverseSearchConfig;
    private final StratifiedSearchConfig stratifiedSearchConfig;
    private final boolean contentionDiagnostics;

    public AnytimeTeamPlanner() {
        this(AnytimePlannerConfig.defaults());
    }

    public AnytimeTeamPlanner(AnytimePlannerConfig config) {
        this(config, AnytimeSearchPolicy.ORIGINAL);
    }

    public AnytimeTeamPlanner(
            AnytimePlannerConfig config, AnytimeSearchPolicy policy) {
        this(config, policy, false);
    }

    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            boolean contentionDiagnostics) {
        this(config, policy, RiskAdjustmentWeights.defaults(), contentionDiagnostics);
    }

    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            RiskAdjustmentWeights riskAdjustmentWeights,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                new WeightedRouteFinder(),
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                null,
                riskAdjustmentWeights,
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                contentionDiagnostics);
    }

    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                new WeightedRouteFinder(),
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                null,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                contentionDiagnostics);
    }

    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                new WeightedRouteFinder(),
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                null,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                diverseSearchConfig,
                contentionDiagnostics);
    }

    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                new WeightedRouteFinder(),
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                null,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                diverseSearchConfig,
                stratifiedSearchConfig,
                contentionDiagnostics);
    }

    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator) {
        this(config, patrolRouteFinder, refuelRouteFinder, validator, simulator, null);
    }

    AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator,
            DayPlanner teamCoordinator) {
        this(
                config,
                AnytimeSearchPolicy.ORIGINAL,
                patrolRouteFinder,
                refuelRouteFinder,
                validator,
                simulator,
                teamCoordinator,
                RiskAdjustmentWeights.defaults(),
                OpponentIntentConfig.defaults(),
                IntentAdjustmentWeights.defaults(),
                false);
    }

    AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator,
            DayPlanner teamCoordinator,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                patrolRouteFinder,
                refuelRouteFinder,
                validator,
                simulator,
                teamCoordinator,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                DiverseSearchConfig.defaults(),
                contentionDiagnostics);
    }

    AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator,
            DayPlanner teamCoordinator,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                patrolRouteFinder,
                refuelRouteFinder,
                validator,
                simulator,
                teamCoordinator,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                diverseSearchConfig,
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()),
                contentionDiagnostics);
    }

    AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator,
            DayPlanner teamCoordinator,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                patrolRouteFinder,
                refuelRouteFinder,
                validator,
                simulator,
                teamCoordinator,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                CommitmentAdjustmentWeights.defaults(),
                diverseSearchConfig,
                stratifiedSearchConfig,
                contentionDiagnostics);
    }

    /** M12 entry point: the M11 stratified engine with commitment-aware forecast weights. */
    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            CommitmentAdjustmentWeights commitmentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                new WeightedRouteFinder(),
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                null,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                commitmentAdjustmentWeights,
                diverseSearchConfig,
                stratifiedSearchConfig,
                contentionDiagnostics);
    }

    /** M12.1 entry point: the same stratified engine reading the forecast semi-committed. */
    public AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            CommitmentAdjustmentWeights commitmentAdjustmentWeights,
            SemiCommitmentAdjustmentWeights semiCommitmentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                new WeightedRouteFinder(),
                new RefuelRouteFinder(),
                new PlanValidator(),
                new DaySimulator(),
                null,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                commitmentAdjustmentWeights,
                semiCommitmentAdjustmentWeights,
                diverseSearchConfig,
                stratifiedSearchConfig,
                contentionDiagnostics);
    }

    AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator,
            DayPlanner teamCoordinator,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            CommitmentAdjustmentWeights commitmentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this(
                config,
                policy,
                patrolRouteFinder,
                refuelRouteFinder,
                validator,
                simulator,
                teamCoordinator,
                riskAdjustmentWeights,
                opponentIntentConfig,
                intentAdjustmentWeights,
                commitmentAdjustmentWeights,
                SemiCommitmentAdjustmentWeights.defaults(),
                diverseSearchConfig,
                stratifiedSearchConfig,
                contentionDiagnostics);
    }

    AnytimeTeamPlanner(
            AnytimePlannerConfig config,
            AnytimeSearchPolicy policy,
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            DaySimulator simulator,
            DayPlanner teamCoordinator,
            RiskAdjustmentWeights riskAdjustmentWeights,
            OpponentIntentConfig opponentIntentConfig,
            IntentAdjustmentWeights intentAdjustmentWeights,
            CommitmentAdjustmentWeights commitmentAdjustmentWeights,
            SemiCommitmentAdjustmentWeights semiCommitmentAdjustmentWeights,
            DiverseSearchConfig diverseSearchConfig,
            StratifiedSearchConfig stratifiedSearchConfig,
            boolean contentionDiagnostics) {
        this.config = Objects.requireNonNull(config, "Anytime configuration must not be null");
        this.policy = Objects.requireNonNull(policy, "Anytime search policy must not be null");
        this.patrolRouteFinder = Objects.requireNonNull(
                patrolRouteFinder, "PATROL route finder must not be null");
        this.refuelRouteFinder = Objects.requireNonNull(
                refuelRouteFinder, "REFUEL route finder must not be null");
        this.validator = Objects.requireNonNull(validator, "Plan validator must not be null");
        this.simulator = Objects.requireNonNull(simulator, "Day simulator must not be null");
        this.teamCoordinator = teamCoordinator == null
                ? usesHarvestHorizon(policy)
                        ? new HarvestHorizonAwareTeamCoordinatorPlanner(
                                this.patrolRouteFinder, this.refuelRouteFinder, this.validator,
                                contentionDiagnostics)
                        : isHorizonAwarePolicy(policy)
                        ? new HorizonAwareTeamCoordinatorPlanner(
                                this.patrolRouteFinder, this.refuelRouteFinder, this.validator,
                                contentionDiagnostics)
                        : new TeamCoordinatorPlanner(
                                this.patrolRouteFinder, this.refuelRouteFinder, this.validator)
                : teamCoordinator;
        this.contentionFallback = (policy == AnytimeSearchPolicy.CONTENTION
                || isArrivalPolicy(policy) || isIntentAwarePolicy(policy)
                || isCommitmentAwarePolicy(policy) || isSemiCommitmentAwarePolicy(policy)
                || isAnyHorizonAwarePolicy(policy))
                ? new HarvestAnytimeTeamPlanner(config)
                : null;
        this.riskAdjustmentWeights = Objects.requireNonNull(
                riskAdjustmentWeights, "Risk adjustment weights must not be null");
        this.opponentIntentConfig = Objects.requireNonNull(
                opponentIntentConfig, "Opponent intent configuration must not be null");
        this.intentAdjustmentWeights = Objects.requireNonNull(
                intentAdjustmentWeights, "Intent adjustment weights must not be null");
        this.commitmentAdjustmentWeights = Objects.requireNonNull(
                commitmentAdjustmentWeights, "Commitment adjustment weights must not be null");
        this.semiCommitmentAdjustmentWeights = Objects.requireNonNull(
                semiCommitmentAdjustmentWeights,
                "Semi-commitment adjustment weights must not be null");
        this.diverseSearchConfig = Objects.requireNonNull(
                diverseSearchConfig, "Diverse search configuration must not be null");
        this.stratifiedSearchConfig = Objects.requireNonNull(
                stratifiedSearchConfig, "Stratified search configuration must not be null");
        if (isStratifiedPolicy(policy)) {
            this.stratifiedSearchConfig.requireStagesSumTo(config.maxExpandedStates());
        }
        this.contentionDiagnostics = contentionDiagnostics;
    }

    @Override
    public TeamPlan plan(DayState state) {
        return planWithStats(state).plan();
    }

    public AnytimePlanResult planWithStats(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        MutableStats stats = new MutableStats();
        SearchContext context = new SearchContext(
                state, policy, riskAdjustmentWeights, opponentIntentConfig, intentAdjustmentWeights,
                commitmentAdjustmentWeights, semiCommitmentAdjustmentWeights);
        ArrivalEvaluatedPlan arrivalIncumbent = null;
        RiskAdjustedEvaluatedPlan riskAdjustedIncumbent = null;
        IntentAwareEvaluatedPlan intentAwareIncumbent = null;
        CommitmentAwareEvaluatedPlan commitmentIncumbent = null;
        SemiCommitmentAwareEvaluatedPlan semiCommitmentIncumbent = null;
        HorizonAwareEvaluatedPlan horizonIncumbent = null;
        HarvestHorizonAwareEvaluatedPlan harvestHorizonIncumbent = null;
        RelativeMarginEvaluatedPlan relativeMarginIncumbent = null;
        ReplacementAwareEvaluatedPlan replacementAwareIncumbent = null;
        CoupledCompetitiveEvaluatedPlan coupledCompetitiveIncumbent = null;
        HybridCalibratedMarginEvaluatedPlan hybridCalibratedMarginIncumbent = null;
        M17CandidateDiversityStats m17Stats = new M17CandidateDiversityStats();
        M18AllocationStats m18Stats = new M18AllocationStats();
        Map<String, M18AllocationCandidate> m18Candidates = new LinkedHashMap<>();
        M19CapacityStats m19Stats = new M19CapacityStats();
        Map<String, M19CapacityCandidate> m19Candidates = new LinkedHashMap<>();
        EvaluatedPlan incumbent = null;
        if (isHybridEvaluatorPolicy()) {
            hybridCalibratedMarginIncumbent = initialHybridCalibratedMarginIncumbent(state, stats, context);
            incumbent = hybridCalibratedMarginIncumbent.base();
        } else if (isCoupledCompetitivePolicy()) {
            coupledCompetitiveIncumbent = initialCoupledCompetitiveIncumbent(state, stats, context);
            incumbent = coupledCompetitiveIncumbent.base();
        } else if (isReplacementAwarePolicy()) {
            replacementAwareIncumbent = initialReplacementAwareIncumbent(state, stats, context);
            incumbent = replacementAwareIncumbent.base();
        } else if (isRelativeMarginPolicy()) {
            relativeMarginIncumbent = initialRelativeMarginIncumbent(state, stats, context);
            incumbent = relativeMarginIncumbent.base();
        } else if (isHarvestHorizonAwarePolicy()) {
            harvestHorizonIncumbent = initialHarvestHorizonAwareIncumbent(state, stats, context);
            incumbent = harvestHorizonIncumbent.base();
        } else if (isHorizonAwarePolicy()) {
            horizonIncumbent = initialHorizonAwareIncumbent(state, stats, context);
            incumbent = horizonIncumbent.base();
        } else if (isSemiCommitmentAwarePolicy()) {
            semiCommitmentIncumbent = initialSemiCommitmentAwareIncumbent(state, stats, context);
            incumbent = semiCommitmentIncumbent.base();
        } else if (isCommitmentAwarePolicy()) {
            commitmentIncumbent = initialCommitmentAwareIncumbent(state, stats, context);
            incumbent = commitmentIncumbent.base();
        } else if (isIntentAwarePolicy()) {
            intentAwareIncumbent = initialIntentAwareIncumbent(state, stats, context);
            incumbent = intentAwareIncumbent.base();
        } else if (isRiskAdjustedPolicy()) {
            riskAdjustedIncumbent = initialRiskAdjustedIncumbent(state, stats, context);
            incumbent = riskAdjustedIncumbent.base();
        } else if (isArrivalPolicy(policy)) {
            arrivalIncumbent = initialArrivalIncumbent(state, stats, context);
            incumbent = arrivalIncumbent.base();
        } else {
            incumbent = initialIncumbent(state, stats);
        }
        if (policy == AnytimeSearchPolicy.CONTENTION || isArrivalPolicy(policy)) {
            int observedAgents = state.observedOthers().stream()
                    .mapToInt(group -> group.agents().size())
                    .sum();
            SpotContentionSummary spotSummary = context.contentionAnalyzer.summarizeSpots(
                    state, context::contentionAt);
            log("CONTENTION_SUMMARY",
                    "day", state.day().value(),
                    "observedGroups", state.observedOthers().size(),
                    "observedAgents", observedAgents,
                    "spotsConsidered", spotSummary.spotsConsidered(),
                    "safeSpots", spotSummary.safeSpots(),
                    "tiedSpots", spotSummary.tiedSpots(),
                    "contestedSpots", spotSummary.contestedSpots(),
                    "unobservedSpots", spotSummary.unobservedSpots());
            if (contentionDiagnostics && policy == AnytimeSearchPolicy.CONTENTION) {
                logContentionSpots(state, context);
            } else if (contentionDiagnostics && policy == AnytimeSearchPolicy.ANYTIME_WEIGHTED_ARRIVAL_CONTENTION) {
                logArrivalBoundComparisons(state, context);
            }
        }
        if (isIntentAwarePolicy() && contentionDiagnostics) {
            logIntentForecast(state, context);
        }
        if (isM17DiverseCandidatePolicy()) {
            logM17Start(state, hybridCalibratedMarginIncumbent.evaluation(), context, stats);
        } else if (isHybridCalibratedMarginPolicy()) {
            logHybridCalibratedMarginStart(state, hybridCalibratedMarginIncumbent.evaluation(), context, stats);
        } else if (isM18TeamAllocatedPolicy()) {
            logM18Start(state, hybridCalibratedMarginIncumbent.evaluation(), context, stats);
        } else if (isM19CapacityCompetitivePolicy()) {
            logM19Start(state, hybridCalibratedMarginIncumbent.evaluation(), context, stats);
        } else if (isCoupledCompetitivePolicy()) {
            CoupledCompetitiveMarginEvaluation start = coupledCompetitiveIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = start.nextDayHarvestCapacity();
            logOpponentCoupledBaseline(state, context.coupledCompetitiveBaseline);
            if (contentionDiagnostics) {
                logCoupledCompetitiveEvents(state, start.coupled());
            }
            log(event("START"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "plannedOwnOpportunityEvents", start.plannedOwnOpportunityEvents(),
                    "coupledOwnBrands", start.coupledOwnBrands(),
                    "coupledOwnCollections", start.coupledOwnCollections(),
                    "opponentBaselineCollections", start.opponentBaselineCollections(),
                    "coupledOpponentCollections", start.coupledOpponentCollections(),
                    "opponentCollectionsRemovedVsBaseline",
                    start.opponentCollectionsRemovedVsBaseline(),
                    "ownPlannedEventsInvalidatedByOpponent",
                    start.ownPlannedEventsInvalidatedByOpponent(),
                    "opponentReplacementCollections", start.opponentReplacementCollections(),
                    "projectedCoupledMargin", start.projectedCoupledMargin(),
                    "ownSemiBrands", start.ownSemiBrands(),
                    "ownSemiCollections", start.ownSemiCollections(),
                    "semiScore", start.semiScore(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
        } else if (isReplacementAwarePolicy()) {
            ReplacementAwareRelativeMarginEvaluation start = replacementAwareIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = start.nextDayHarvestCapacity();
            logOpponentFullDayBaseline(state, context.opponentFullDayBaseline);
            if (contentionDiagnostics) {
                logOpponentFullDayRoutes(state, context.opponentFullDayBaseline);
            }
            log(event("START"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "ownSemiBrands", start.ownSemiBrands(),
                    "ownSemiCollections", start.ownSemiCollections(),
                    "baselineOpponentCollections", start.baselineOpponentCollections(),
                    "residualOpponentCollections", start.residualOpponentCollections(),
                    "netOpponentCollectionsRemoved", start.netOpponentCollectionsRemoved(),
                    "replacementCollections", start.replacementCollections(),
                    "residualObservedNow", start.opponentFullDay().residualObservedNow(),
                    "residualDirect", start.opponentFullDay().residualDirectIntent(),
                    "residualFollowOn", start.opponentFullDay().residualFollowOnIntent(),
                    "projectedReplacementAwareMargin", start.projectedReplacementAwareMargin(),
                    "semiScore", start.semiScore(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
        } else if (isRelativeMarginPolicy()) {
            RelativeMarginPlanEvaluation start = relativeMarginIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = start.nextDayHarvestCapacity();
            if (contentionDiagnostics) {
                logOpponentDenialBaseline(state, context.opponentClaimBaseline);
            }
            log(event("START"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "ownSemiBrands", start.ownSemiBrands(),
                    "ownSemiCollections", start.ownSemiCollections(),
                    "baselineStrongOpponentRealizable", start.opponentBaselineStrongRealizable(),
                    "residualStrongOpponentRealizable", start.opponentResidualStrongRealizable(),
                    "deniedObservedNow", start.opponentClaims().deniedObservedNow(),
                    "deniedDirect", start.opponentClaims().deniedDirectIntent(),
                    "deniedFollowOn", start.opponentClaims().deniedFollowOnIntent(),
                    "strongDenied", start.strongDeniedOpponentCollections(),
                    "projectedStrongRelativeSwing", start.projectedStrongRelativeSwing(),
                    "semiScore", start.semiScore(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
        } else if (isHarvestHorizonAwarePolicy()) {
            HarvestHorizonAwarePlanEvaluation start = harvestHorizonIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = start.nextDayHarvestCapacity();
            log(event("START"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "semiBrands", start.semiCommitment().semiCommitmentRealizableBrandCount(),
                    "semiScore", start.semiCommitment().adjustedCollectionScore().value(),
                    "semiCollections", start.semiCommitment().semiCommitmentRealizableCollections(),
                    "rawUdon", start.semiCommitment().base().udonTotal(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
        } else if (isHorizonAwarePolicy()) {
            HorizonAwarePlanEvaluation start = horizonIncumbent.evaluation();
            TeamFutureReadiness future = start.futureReadiness();
            log(event("START"),
                    "day", state.day().value(),
                    "remainingFutureDays", future.remainingFutureDays(),
                    "incumbentSemiBrands", start.semiCommitment().semiCommitmentRealizableBrandCount(),
                    "incumbentSemiScore", start.semiCommitment().adjustedCollectionScore().value(),
                    "incumbentSemiCollections", start.semiCommitment().semiCommitmentRealizableCollections(),
                    "incumbentRawUdon", start.semiCommitment().base().udonTotal(),
                    "incumbentFutureReadyPatrols", future.futureReadyPatrolCount(),
                    "incumbentFutureReachableSpots", future.totalReachableOpportunitySpots(),
                    "incumbentFutureReachableBrands", future.totalReachableOpportunityBrands(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
        } else if (isSemiCommitmentAwarePolicy()) {
            if (contentionDiagnostics) {
                logSemiCommitmentForecast(state, context);
            }
            SemiCommitmentAwarePlanEvaluation start = semiCommitmentIncumbent.evaluation();
            log(event("START"),
                    "day", state.day().value(),
                    "incumbentLocalBrands", start.base().teamBrandCount(),
                    "incumbentSemiCommitmentBrands", start.semiCommitmentRealizableBrandCount(),
                    "incumbentRawUdon", start.base().udonTotal(),
                    "oldForecastRealizable", start.oldForecastRealizableCollections(),
                    "commitmentRealizable", start.commitmentRealizableCollections(),
                    "semiCommitmentRealizable", start.semiCommitmentRealizableCollections(),
                    "incumbentSemiCommitmentScore", start.adjustedCollectionScore().value(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
        } else if (isCommitmentAwarePolicy()) {
            if (contentionDiagnostics) {
                logCommitmentForecast(state, context);
            }
            CommitmentAwarePlanEvaluation start = commitmentIncumbent.evaluation();
            log(event("START"),
                    "day", state.day().value(),
                    "incumbentLocalBrands", start.base().teamBrandCount(),
                    "incumbentCommitmentBrands", start.commitmentRealizableBrandCount(),
                    "incumbentRawUdon", start.base().udonTotal(),
                    "incumbentCommitmentRealizable", start.commitmentRealizableCollections(),
                    "incumbentCommitmentScore", start.adjustedCollectionScore().value(),
                    "oldForecastRealizable", start.oldForecastRealizableCollections(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
        } else if (isStratifiedIntentPolicy()) {
            log(event("START"),
                    "day", state.day().value(),
                    "incumbentForecastBrands",
                    intentAwareIncumbent.evaluation().forecastRealizableBrandCount(),
                    "incumbentIntentScore",
                    intentAwareIncumbent.evaluation().adjustedCollectionScore().value(),
                    "incumbentForecastRealizable",
                    intentAwareIncumbent.evaluation().forecastRealizableCollections(),
                    "incumbentRawUdon", incumbent.evaluation.udonTotal(),
                    "budget", config.maxExpandedStates(),
                    "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                    "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                    "exploitationBudget", stratifiedSearchConfig.exploitationBudget(),
                    "maxQualifiedStrategies", stratifiedSearchConfig.maxQualifiedStrategies(),
                    "minimumQualificationDepth",
                    stratifiedSearchConfig.minimumQualificationExpansionsPerStrategy());
        } else if (isDiverseIntentPolicy()) {
            log(event("START"),
                    "day", state.day().value(),
                    "incumbentForecastBrands",
                    intentAwareIncumbent.evaluation().forecastRealizableBrandCount(),
                    "incumbentIntentScore",
                    intentAwareIncumbent.evaluation().adjustedCollectionScore().value(),
                    "incumbentForecastRealizable",
                    intentAwareIncumbent.evaluation().forecastRealizableCollections(),
                    "incumbentRawUdon", incumbent.evaluation.udonTotal(),
                    "budget", config.maxExpandedStates(),
                    "frontierLimit", config.maxFrontierSize(),
                    "candidateLimit", config.topCandidatesPerState());
        } else if (isIntentAwarePolicy()) {
            log(event("START"),
                    "day", state.day().value(),
                    "incumbentLocalBrands", incumbent.evaluation.teamBrandCount(),
                    "incumbentForecastBrands",
                    intentAwareIncumbent.evaluation().forecastRealizableBrandCount(),
                    "incumbentRawUdon", incumbent.evaluation.udonTotal(),
                    "incumbentForecastRealizable",
                    intentAwareIncumbent.evaluation().forecastRealizableCollections(),
                    "incumbentIntentScore",
                    intentAwareIncumbent.evaluation().adjustedCollectionScore().value(),
                    "budget", config.maxExpandedStates());
        } else if (isRiskAdjustedPolicy()) {
            log(event("START"),
                    "day", state.day().value(),
                    "incumbentBrands", incumbent.evaluation.teamBrandCount(),
                    "incumbentRawUdon", incumbent.evaluation.udonTotal(),
                    "incumbentAdjustedScore",
                    riskAdjustedIncumbent.evaluation().adjustedCollectionScore().value(),
                    "budget", config.maxExpandedStates());
        } else {
            log(event("START"),
                    "day", state.day().value(),
                    "incumbentBrands", incumbent.evaluation.teamBrandCount(),
                    "incumbentUdon", incumbent.evaluation.udonTotal(),
                    "budget", config.maxExpandedStates());
        }

        if (isM18TeamAllocatedPolicy()) {
            M18AllocationSelection selection = evaluateM18Allocations(
                    state, context, m18Stats, m18Candidates);
            if (selection != null && selection.evaluation().evaluation().betterThan(
                    hybridCalibratedMarginIncumbent.evaluation())) {
                hybridCalibratedMarginIncumbent = selection.evaluation();
                incumbent = hybridCalibratedMarginIncumbent.base();
                stats.incumbentImprovements++;
            }
        }
        if (isM19CapacityCompetitivePolicy()) {
            M19CapacitySelection selection = evaluateM19CapacityAllocations(
                    state, context, m19Stats, m19Candidates);
            if (selection != null && selection.evaluation().evaluation().betterThan(
                    hybridCalibratedMarginIncumbent.evaluation())) {
                hybridCalibratedMarginIncumbent = selection.evaluation();
                incumbent = hybridCalibratedMarginIncumbent.base();
                stats.incumbentImprovements++;
            }
        }

        DiverseFrontier<SearchState> diverseFrontier = isDiverseIntentPolicy()
                ? new DiverseFrontier<>(
                        config.maxFrontierSize(),
                        diverseSearchConfig.frontierEliteSlots(config.maxFrontierSize()),
                        diverseSearchConfig.maxDiversityStatesPerStrategy(),
                        statePreference(),
                        SearchState::diversityKey)
                : null;
        StratifiedFrontier<SearchState> stratifiedFrontier = isStratifiedPolicy(policy)
                ? new StratifiedFrontier<>(
                        config.maxFrontierSize(),
                        stratifiedSearchConfig.globalEliteSlots(config.maxFrontierSize()),
                        statePreference(),
                        SearchState::diversityKey)
                : null;
        StrategyStageScheduler<SearchState> scheduler = stratifiedFrontier == null
                ? null
                : new StrategyStageScheduler<>(stratifiedSearchConfig);
        SearchFrontier<SearchState> frontier;
        if (diverseFrontier != null) {
            frontier = diverseFrontier;
        } else if (stratifiedFrontier != null) {
            frontier = stratifiedFrontier;
        } else {
            frontier = new BoundedPriorityFrontier<>(config.maxFrontierSize(), statePreference());
        }
        boolean strategyAware = diverseFrontier != null || stratifiedFrontier != null;
        DiverseMutableStats diverseStats = new DiverseMutableStats();
        Map<String, String> m17CandidateOrigins = new LinkedHashMap<>();
        Map<StrategicDiversityKey, IntentAwarePlanEvaluation> bestCompleteByStrategy = new TreeMap<>();
        Map<StrategicDiversityKey, CommitmentAwarePlanEvaluation> bestCommitmentByStrategy =
                new TreeMap<>();
        Map<StrategicDiversityKey, SemiCommitmentAwarePlanEvaluation> bestSemiCommitmentByStrategy =
                new TreeMap<>();
        try {
            Set<StateKey> seen = new HashSet<>();
            M17CandidateSignatureRegistry m17SeenPlanSignatures = new M17CandidateSignatureRegistry();
            if (isM17DiverseCandidatePolicy()) {
                m17CandidateOrigins.put(signature(incumbent.plan), "BASELINE");
            }
            for (SearchState root : roots(context)) {
                stats.generatedStates++;
                diverseStats.observeGenerated(root, strategyAware);
                if (isM17DiverseCandidatePolicy()) {
                    TeamPlan rootPlan = root.completePlan(context.state);
                    m17SeenPlanSignatures.accept(signature(rootPlan));
                    m17CandidateOrigins.putIfAbsent(signature(rootPlan), "BASELINE");
                }
                if (seen.add(root.key())) {
                    addBounded(frontier, root, stats, diverseStats);
                } else {
                    stats.prunedStates++;
                    stats.duplicateStates++;
                    diverseStats.statesRejectedByExactDedup++;
                }
            }

            int qualityStreak = 0;
            while (!frontier.isEmpty() && stats.expandedStates < config.maxExpandedStates()) {
                SearchState current;
                StrategicDiversityKey currentStrategy = null;
                StrategyStageScheduler.Stage currentStage = null;
                if (scheduler != null) {
                    StrategyStageScheduler.Decision<SearchState> decision =
                            scheduler.next(stratifiedFrontier);
                    if (decision == null) {
                        break;
                    }
                    current = decision.state();
                    currentStrategy = decision.strategy();
                    currentStage = decision.stage();
                } else {
                    boolean diversityTurn = qualityStreak
                                    >= diverseSearchConfig.qualityExpansionsPerDiversityExpansion()
                            && frontier.diversityAvailable();
                    if (diverseFrontier != null) {
                        if (diversityTurn) {
                            diverseStats.diversityExpansions++;
                            qualityStreak = 0;
                        } else {
                            diverseStats.qualityExpansions++;
                            qualityStreak++;
                        }
                    }
                    current = frontier.poll(diversityTurn);
                }
                stats.expandedStates++;
                TeamPlan complete = current.completePlan(context.state);
                stats.completedPlans++;
                if (isHybridEvaluatorPolicy()) {
                    Optional<HybridCalibratedMarginEvaluatedPlan> evaluated =
                            evaluateHybridCalibratedMargin(context.state, complete, context);
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(
                                    hybridCalibratedMarginIncumbent.evaluation())) {
                        HybridCalibratedMarginEvaluation previous =
                                hybridCalibratedMarginIncumbent.evaluation();
                        hybridCalibratedMarginIncumbent = evaluated.orElseThrow();
                        incumbent = hybridCalibratedMarginIncumbent.base();
                        stats.incumbentImprovements++;
                        if (isM17DiverseCandidatePolicy()) {
                            logM17Improvement(state, hybridCalibratedMarginIncumbent.evaluation(), previous,
                                    stats, stratifiedStats(stratifiedFrontier, scheduler, diverseStats, false));
                        } else {
                            logHybridCalibratedMarginImprovement(state,
                                    hybridCalibratedMarginIncumbent.evaluation(), previous, stats,
                                    stratifiedStats(stratifiedFrontier, scheduler, diverseStats, false));
                        }
                    }
                } else if (isCoupledCompetitivePolicy()) {
                    Optional<CoupledCompetitiveEvaluatedPlan> evaluated =
                            evaluateCoupledCompetitive(context.state, complete, context);
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(
                                    coupledCompetitiveIncumbent.evaluation())) {
                        coupledCompetitiveIncumbent = evaluated.orElseThrow();
                        incumbent = coupledCompetitiveIncumbent.base();
                        stats.incumbentImprovements++;
                        CoupledCompetitiveMarginEvaluation improved =
                                coupledCompetitiveIncumbent.evaluation();
                        log(event("IMPROVEMENT"), "day", state.day().value(),
                                "coupledOwnBrands", improved.coupledOwnBrands(),
                                "coupledOwnCollections", improved.coupledOwnCollections(),
                                "coupledOpponentCollections", improved.coupledOpponentCollections(),
                                "opponentCollectionsRemovedVsBaseline",
                                improved.opponentCollectionsRemovedVsBaseline(),
                                "ownPlannedEventsInvalidatedByOpponent",
                                improved.ownPlannedEventsInvalidatedByOpponent(),
                                "opponentReplacementCollections",
                                improved.opponentReplacementCollections(),
                                "projectedCoupledMargin", improved.projectedCoupledMargin(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isReplacementAwarePolicy()) {
                    Optional<ReplacementAwareEvaluatedPlan> evaluated =
                            evaluateReplacementAware(context.state, complete, context);
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(
                                    replacementAwareIncumbent.evaluation())) {
                        replacementAwareIncumbent = evaluated.orElseThrow();
                        incumbent = replacementAwareIncumbent.base();
                        stats.incumbentImprovements++;
                        ReplacementAwareRelativeMarginEvaluation improved =
                                replacementAwareIncumbent.evaluation();
                        log(event("IMPROVEMENT"), "day", state.day().value(),
                                "ownSemiBrands", improved.ownSemiBrands(),
                                "ownSemiCollections", improved.ownSemiCollections(),
                                "residualOpponentCollections", improved.residualOpponentCollections(),
                                "netOpponentCollectionsRemoved", improved.netOpponentCollectionsRemoved(),
                                "replacementCollections", improved.replacementCollections(),
                                "projectedReplacementAwareMargin",
                                improved.projectedReplacementAwareMargin(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isRelativeMarginPolicy()) {
                    Optional<RelativeMarginEvaluatedPlan> evaluated =
                            evaluateRelativeMargin(context.state, complete, context);
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(
                                    relativeMarginIncumbent.evaluation())) {
                        relativeMarginIncumbent = evaluated.orElseThrow();
                        incumbent = relativeMarginIncumbent.base();
                        stats.incumbentImprovements++;
                        RelativeMarginPlanEvaluation improved = relativeMarginIncumbent.evaluation();
                        log(event("IMPROVEMENT"), "day", state.day().value(),
                                "ownSemiBrands", improved.ownSemiBrands(),
                                "ownSemiCollections", improved.ownSemiCollections(),
                                "strongDenied", improved.strongDeniedOpponentCollections(),
                                "projectedStrongRelativeSwing", improved.projectedStrongRelativeSwing(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isHarvestHorizonAwarePolicy()) {
                    Optional<HarvestHorizonAwareEvaluatedPlan> evaluated =
                            evaluateHarvestHorizonAware(context.state, complete, context);
                    if (evaluated.isPresent()
                            && canReplaceHarvestHorizonIncumbent(
                                    current, evaluated.orElseThrow(), harvestHorizonIncumbent)) {
                        harvestHorizonIncumbent = evaluated.orElseThrow();
                        incumbent = harvestHorizonIncumbent.base();
                        stats.incumbentImprovements++;
                        HarvestHorizonAwarePlanEvaluation improved = harvestHorizonIncumbent.evaluation();
                        log(event("IMPROVEMENT"), "day", state.day().value(),
                                "semiBrands", improved.semiCommitment().semiCommitmentRealizableBrandCount(),
                                "semiScore", improved.semiCommitment().adjustedCollectionScore().value(),
                                "semiCollections", improved.semiCommitment().semiCommitmentRealizableCollections(),
                                "minimumPatrolDistinctSpots",
                                improved.nextDayHarvestCapacity().minimumPatrolDistinctSpots(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isHorizonAwarePolicy()) {
                    Optional<HorizonAwareEvaluatedPlan> evaluated =
                            evaluateHorizonAware(context.state, complete, context);
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(horizonIncumbent.evaluation())) {
                        horizonIncumbent = evaluated.orElseThrow();
                        incumbent = horizonIncumbent.base();
                        stats.incumbentImprovements++;
                        HorizonAwarePlanEvaluation improved = horizonIncumbent.evaluation();
                        log(event("IMPROVEMENT"),
                                "day", state.day().value(),
                                "semiBrands", improved.semiCommitment().semiCommitmentRealizableBrandCount(),
                                "semiScore", improved.semiCommitment().adjustedCollectionScore().value(),
                                "semiCollections", improved.semiCommitment().semiCommitmentRealizableCollections(),
                                "futureReadyPatrols", improved.futureReadiness().futureReadyPatrolCount(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isSemiCommitmentAwarePolicy()) {
                    Optional<SemiCommitmentAwareEvaluatedPlan> evaluated =
                            evaluateSemiCommitmentAware(context.state, complete, context);
                    if (evaluated.isPresent() && currentStrategy != null) {
                        // Per-strategy best complete plan, for bounded diagnostics only.
                        SemiCommitmentAwarePlanEvaluation found =
                                evaluated.orElseThrow().evaluation();
                        SemiCommitmentAwarePlanEvaluation previous =
                                bestSemiCommitmentByStrategy.get(currentStrategy);
                        if (previous == null || found.betterThan(previous)) {
                            bestSemiCommitmentByStrategy.put(currentStrategy, found);
                        }
                    }
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(
                                    semiCommitmentIncumbent.evaluation())) {
                        semiCommitmentIncumbent = evaluated.orElseThrow();
                        incumbent = semiCommitmentIncumbent.base();
                        stats.incumbentImprovements++;
                        SemiCommitmentAwarePlanEvaluation improved =
                                semiCommitmentIncumbent.evaluation();
                        log(event("IMPROVEMENT"),
                                "day", state.day().value(),
                                "localBrands", improved.base().teamBrandCount(),
                                "semiCommitmentBrands",
                                improved.semiCommitmentRealizableBrandCount(),
                                "rawUdon", improved.base().udonTotal(),
                                "oldForecastRealizable",
                                improved.oldForecastRealizableCollections(),
                                "commitmentRealizableCollections",
                                improved.commitmentRealizableCollections(),
                                "semiCommitmentRealizableCollections",
                                improved.semiCommitmentRealizableCollections(),
                                "semiCommitmentAdjustedScore",
                                improved.adjustedCollectionScore().value(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isCommitmentAwarePolicy()) {
                    Optional<CommitmentAwareEvaluatedPlan> evaluated = evaluateCommitmentAware(
                            context.state, complete, context);
                    if (evaluated.isPresent() && currentStrategy != null) {
                        // Per-strategy best complete plan, for bounded diagnostics only.
                        CommitmentAwarePlanEvaluation found = evaluated.orElseThrow().evaluation();
                        CommitmentAwarePlanEvaluation previous =
                                bestCommitmentByStrategy.get(currentStrategy);
                        if (previous == null || found.betterThan(previous)) {
                            bestCommitmentByStrategy.put(currentStrategy, found);
                        }
                    }
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(
                                    commitmentIncumbent.evaluation())) {
                        commitmentIncumbent = evaluated.orElseThrow();
                        incumbent = commitmentIncumbent.base();
                        stats.incumbentImprovements++;
                        CommitmentAwarePlanEvaluation improved = commitmentIncumbent.evaluation();
                        log(event("IMPROVEMENT"),
                                "day", state.day().value(),
                                "localBrands", improved.base().teamBrandCount(),
                                "commitmentBrands", improved.commitmentRealizableBrandCount(),
                                "rawUdon", improved.base().udonTotal(),
                                "commitmentRealizableCollections",
                                improved.commitmentRealizableCollections(),
                                "commitmentAdjustedScore",
                                improved.adjustedCollectionScore().value(),
                                "oldForecastRealizable",
                                improved.oldForecastRealizableCollections(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isIntentAwarePolicy()) {
                    Optional<IntentAwareEvaluatedPlan> evaluated = evaluateIntentAware(
                            context.state, complete, context);
                    if (evaluated.isPresent() && currentStrategy != null) {
                        // Per-strategy best complete plan, for bounded diagnostics only: the
                        // incumbent itself is still decided by the unchanged M10 objective.
                        IntentAwarePlanEvaluation found = evaluated.orElseThrow().evaluation();
                        IntentAwarePlanEvaluation previous = bestCompleteByStrategy.get(currentStrategy);
                        if (previous == null || found.betterThan(previous)) {
                            bestCompleteByStrategy.put(currentStrategy, found);
                        }
                    }
                    if (evaluated.isPresent()
                            && evaluated.orElseThrow().evaluation().betterThan(
                                    intentAwareIncumbent.evaluation())) {
                        intentAwareIncumbent = evaluated.orElseThrow();
                        incumbent = intentAwareIncumbent.base();
                        stats.incumbentImprovements++;
                        log(event("IMPROVEMENT"),
                                "day", state.day().value(),
                                "localBrands", incumbent.evaluation.teamBrandCount(),
                                "forecastBrands",
                                intentAwareIncumbent.evaluation().forecastRealizableBrandCount(),
                                "rawUdon", incumbent.evaluation.udonTotal(),
                                "forecastRealizableCollections",
                                intentAwareIncumbent.evaluation().forecastRealizableCollections(),
                                "intentAdjustedScore",
                                intentAwareIncumbent.evaluation().adjustedCollectionScore().value(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isRiskAdjustedPolicy()) {
                    Optional<RiskAdjustedEvaluatedPlan> evaluated = evaluateRiskAdjusted(
                            context.state, complete, context);
                    if (evaluated.isPresent()
                            && canReplaceRiskAdjustedIncumbent(
                                    evaluated.orElseThrow(), riskAdjustedIncumbent)) {
                        riskAdjustedIncumbent = evaluated.orElseThrow();
                        incumbent = riskAdjustedIncumbent.base();
                        stats.incumbentImprovements++;
                        log(event("IMPROVEMENT"),
                                "day", state.day().value(),
                                "brands", incumbent.evaluation.teamBrandCount(),
                                "rawUdon", incumbent.evaluation.udonTotal(),
                                "adjustedScore",
                                riskAdjustedIncumbent.evaluation().adjustedCollectionScore().value(),
                                "expanded", stats.expandedStates);
                    }
                } else if (isArrivalPolicy(policy)) {
                    Optional<ArrivalEvaluatedPlan> evaluated = evaluateArrival(context.state, complete, context);
                    if (evaluated.isPresent()
                            && canReplaceArrivalIncumbent(current, evaluated.orElseThrow(), arrivalIncumbent)) {
                        arrivalIncumbent = evaluated.orElseThrow();
                        incumbent = arrivalIncumbent.base();
                        stats.incumbentImprovements++;
                        log(event("IMPROVEMENT"),
                                "day", state.day().value(),
                                "brands", incumbent.evaluation.teamBrandCount(),
                                "udon", incumbent.evaluation.udonTotal(),
                                "expanded", stats.expandedStates);
                    }
                } else {
                    Optional<EvaluatedPlan> evaluated = evaluate(context.state, complete);
                    if (evaluated.isPresent()
                            && canReplaceIncumbent(current, evaluated.orElseThrow(), incumbent)) {
                        incumbent = evaluated.orElseThrow();
                        stats.incumbentImprovements++;
                        log(event("IMPROVEMENT"),
                                "day", state.day().value(),
                                "brands", incumbent.evaluation.teamBrandCount(),
                                "udon", incumbent.evaluation.udonTotal(),
                                "expanded", stats.expandedStates);
                    }
                }

                List<TeamTargetCandidate> candidates = candidates(context, current);
                M17CandidateFamily discoveryFamily = isM17DiverseCandidatePolicy()
                        && currentStage == StrategyStageScheduler.Stage.DISCOVERY
                        ? M17CandidateFamily.forDiscoveryExpansion(scheduler.discoveryExpansions())
                        : null;
                if (discoveryFamily != null) {
                    log("M17_DISCOVERY_FAMILY", "day", state.day().value(),
                            "discoveryExpansion", scheduler.discoveryExpansions(),
                            "family", discoveryFamily);
                    candidates = m17DiscoveryCandidates(context, current, candidates, discoveryFamily);
                }
                boolean coveragePhase = policy != AnytimeSearchPolicy.ORIGINAL
                        && candidates.stream().anyMatch(TeamTargetCandidate::newBrandForTeamToday);
                if (policy != AnytimeSearchPolicy.ORIGINAL) {
                    if (coveragePhase) {
                        stats.coveragePhaseExpandedStates++;
                    } else {
                        stats.harvestPhaseExpandedStates++;
                    }
                }
                stats.candidateGenerated += candidates.size();
                List<TeamTargetCandidate> retainedCandidates;
                if (isCandidatePortfolioPolicy()) {
                    int eliteSlots = isStratifiedPolicy(policy)
                            ? stratifiedSearchConfig.eliteCandidateSlots(
                                    config.topCandidatesPerState())
                            : diverseSearchConfig.eliteCandidateSlots(
                                    config.topCandidatesPerState());
                    CandidatePortfolioSelector.CandidatePortfolio portfolio =
                            CandidatePortfolioSelector.select(
                                    candidates,
                                    candidatePreference(context, coveragePhase),
                                    config.topCandidatesPerState(),
                                    eliteSlots);
                    retainedCandidates = portfolio.selected();
                    diverseStats.candidateEliteSelected += portfolio.eliteSelected();
                    diverseStats.candidateDiverseSelected += portfolio.diverseSelected();
                } else {
                    retainedCandidates = retainCandidates(context, candidates, coveragePhase);
                }
                if (contentionDiagnostics) {
                    if (policy == AnytimeSearchPolicy.CONTENTION) {
                        logContentionCandidates(state, retainedCandidates, context);
                    } else if (isArrivalPolicy(policy) && !isRiskAdjustedPolicy()) {
                        logArrivalContentionCandidates(state, retainedCandidates, context);
                    }
                }
                stats.candidateRetained += retainedCandidates.size();
                int topKPruned = candidates.size() - retainedCandidates.size();
                stats.candidatePrunedByTopK += topKPruned;
                stats.prunedStates += topKPruned;
                for (int retainedIndex = 0; retainedIndex < retainedCandidates.size(); retainedIndex++) {
                    TeamTargetCandidate retainedCandidate = retainedCandidates.get(retainedIndex);
                    RouteContentionMetrics contention = context.candidateContention.getOrDefault(
                            retainedCandidate, new RouteContentionMetrics(0, 0, 0, 0, 0));
                    RouteArrivalContentionMetrics arrivalContention = context.candidateArrivalContention.get(retainedCandidate);
                    int routeAdjustedScore = isRiskAdjustedPolicy() && arrivalContention != null
                            ? riskAdjustmentWeights.score(
                                    arrivalContention.observedArrivalSafeCollections(),
                                    arrivalContention.arrivalTiedCollections(),
                                    arrivalContention.arrivalAtRiskCollections(),
                                    arrivalContention.unobservedCollections())
                            : 0;
                    IntentRouteMetrics intentMetrics = context.candidateIntent.getOrDefault(
                            retainedCandidate, IntentRouteMetrics.empty());
                    if (isIntentAwarePolicy()) {
                        routeAdjustedScore = intentMetrics.adjustedScore();
                    }
                    CommitmentRouteMetrics commitmentMetrics = context.candidateCommitment
                            .getOrDefault(retainedCandidate, CommitmentRouteMetrics.empty());
                    SemiCommitmentRouteMetrics semiCommitmentMetrics =
                            context.candidateSemiCommitment.getOrDefault(
                                    retainedCandidate, SemiCommitmentRouteMetrics.empty());
                    SearchState child = current.child(
                            context.state,
                            retainedCandidate,
                            contention,
                            arrivalContention,
                            routeAdjustedScore,
                            intentMetrics,
                            commitmentMetrics,
                            semiCommitmentMetrics,
                            context.nextSequence());
                    if (isM17DiverseCandidatePolicy()) {
                        M17CandidateFamily originFamily = discoveryFamily;
                        String origin = originFamily == null
                                ? (currentStage == StrategyStageScheduler.Stage.QUALIFICATION
                                        ? "QUALIFICATION" : "EXPLOITATION")
                                : originFamily.name();
                        m17Stats.candidateAttempt(originFamily);
                        TeamPlan childPlan = child.completePlan(context.state);
                        String childSignature = signature(childPlan);
                        if (!m17SeenPlanSignatures.accept(childSignature)) {
                            m17Stats.duplicateCandidatesRejected++;
                            stats.prunedStates++;
                            continue;
                        }
                        Optional<HybridCalibratedMarginEvaluatedPlan> discoveryEvaluation =
                                discoveryFamily == null
                                        ? Optional.empty()
                                        : evaluateHybridCalibratedMargin(context.state, childPlan, context);
                        if (discoveryFamily != null && discoveryEvaluation.isEmpty()) {
                            m17Stats.invalidCandidatesRejected++;
                            continue;
                        }
                        m17Stats.uniqueCandidates++;
                        m17Stats.recordUnique(originFamily, childSignature,
                                firstTargetAssignmentSignature(context.state, childPlan),
                                routePrefixSignature(context.state, childPlan));
                        m17CandidateOrigins.putIfAbsent(childSignature, origin);
                        if (discoveryFamily != null) {
                            logM17DiscoveryCandidate(state, discoveryFamily, retainedIndex + 1,
                                    childPlan, discoveryEvaluation.orElseThrow().evaluation());
                        }
                    }
                    stats.generatedStates++;
                    diverseStats.observeGenerated(child, strategyAware);
                    if (!seen.add(child.key())) {
                        stats.prunedStates++;
                        stats.duplicateStates++;
                        diverseStats.statesRejectedByExactDedup++;
                        continue;
                    }
                    addBounded(frontier, child, stats, diverseStats);
                }
            }
        } catch (RuntimeException exception) {
            stats.prunedStates++;
        }

        boolean budgetExhausted = !frontier.isEmpty()
                && stats.expandedStates >= config.maxExpandedStates();
        AnytimeSearchStats finalStats = stats.immutable(budgetExhausted);
        Optional<DiverseSearchStats> diverseSearchStats = diverseFrontier == null
                ? Optional.empty()
                : Optional.of(diverseStats.immutable(diverseFrontier));
        Optional<StratifiedSearchStats> stratifiedSearchStats = stratifiedFrontier == null
                ? Optional.empty()
                : Optional.of(stratifiedStats(
                        stratifiedFrontier, scheduler, diverseStats, budgetExhausted));
        if (isM17DiverseCandidatePolicy()) {
            logM17Done(state, hybridCalibratedMarginIncumbent.evaluation(), context,
                    hybridCalibratedMarginIncumbent.plan(), finalStats,
                    stratifiedSearchStats.orElseThrow(), m17Stats, m17CandidateOrigins);
        } else if (isM18TeamAllocatedPolicy()) {
            logM18Done(state, hybridCalibratedMarginIncumbent.evaluation(), context,
                    hybridCalibratedMarginIncumbent.plan(), finalStats,
                    stratifiedSearchStats.orElseThrow(), m18Stats, m18Candidates);
        } else if (isM19CapacityCompetitivePolicy()) {
            logM19Done(state, hybridCalibratedMarginIncumbent.evaluation(), context,
                    hybridCalibratedMarginIncumbent.plan(), finalStats,
                    stratifiedSearchStats.orElseThrow(), m19Stats, m19Candidates);
        } else if (isHybridCalibratedMarginPolicy()) {
            logHybridCalibratedMarginDone(state, hybridCalibratedMarginIncumbent.evaluation(), context,
                    finalStats, stratifiedSearchStats.orElseThrow());
        } else if (isCoupledCompetitivePolicy()) {
            CoupledCompetitiveMarginEvaluation evaluation = coupledCompetitiveIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
            CoupledCompetitiveRolloutResult coupled = evaluation.coupled();
            CoupledCompetitiveBaseline coupledBaseline = context.coupledCompetitiveBaseline;
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "plannedOwnOpportunityEvents", evaluation.plannedOwnOpportunityEvents(),
                    "coupledOwnBrands", evaluation.coupledOwnBrands(),
                    "coupledOwnCollections", evaluation.coupledOwnCollections(),
                    "rawUdon", evaluation.semiCommitment().base().udonTotal(),
                    "opponentBaselineCollections", evaluation.opponentBaselineCollections(),
                    "coupledOpponentCollections", evaluation.coupledOpponentCollections(),
                    "opponentCollectionsRemovedVsBaseline",
                    evaluation.opponentCollectionsRemovedVsBaseline(),
                    "ownPlannedEventsInvalidatedByOpponent",
                    evaluation.ownPlannedEventsInvalidatedByOpponent(),
                    "ownPlannedEventsExhaustedByOwnTeam",
                    coupled.ownPlannedEventsExhaustedByOwnTeam(),
                    "opponentReplacementCollections", evaluation.opponentReplacementCollections(),
                    "coupledObservedNow", coupled.coupledObservedNow(),
                    "coupledDirect", coupled.coupledDirectIntent(),
                    "coupledFollowOn", coupled.coupledFollowOnIntent(),
                    "equalStepContests", coupled.equalStepContests(),
                    "projectedCoupledMargin", evaluation.projectedCoupledMargin(),
                    "ownSemiBrands", evaluation.ownSemiBrands(),
                    "ownSemiCollections", evaluation.ownSemiCollections(),
                    "semiScore", evaluation.semiScore(),
                    "baselineRolloutEvents", coupledBaseline.rolloutEvents(),
                    "coupledRolloutEvents", coupled.rolloutEvents(),
                    "maxBaselineCollectorCollections", coupledBaseline.maxCollectorCollections(),
                    "maxCoupledCollectorCollections", coupled.maxCollectorCollections(),
                    "routeCostCacheEntries", coupledBaseline.routeCostCacheEntries(),
                    "pathfindingExecutions", coupledBaseline.pathfindingExecutions(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                    "expanded", finalStats.expandedStates(), "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(), "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logCoupledCompetitiveEvents(state, coupled);
                logHarvestCapacity(state, capacity);
            }
        } else if (isReplacementAwarePolicy()) {
            ReplacementAwareRelativeMarginEvaluation evaluation =
                    replacementAwareIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
            OpponentFullDayResidualEvaluation residual = evaluation.opponentFullDay();
            OpponentFullDayBaseline baseline = context.opponentFullDayBaseline;
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "ownSemiBrands", evaluation.ownSemiBrands(),
                    "ownSemiCollections", evaluation.ownSemiCollections(),
                    "rawUdon", evaluation.semiCommitment().base().udonTotal(),
                    "baselineOpponentCollections", evaluation.baselineOpponentCollections(),
                    "residualOpponentCollections", evaluation.residualOpponentCollections(),
                    "netOpponentCollectionsRemoved", evaluation.netOpponentCollectionsRemoved(),
                    "replacementCollections", evaluation.replacementCollections(),
                    "residualObservedNow", residual.residualObservedNow(),
                    "residualDirect", residual.residualDirectIntent(),
                    "residualFollowOn", residual.residualFollowOnIntent(),
                    "projectedReplacementAwareMargin", evaluation.projectedReplacementAwareMargin(),
                    "semiScore", evaluation.semiScore(),
                    "baselineRolloutEvents", baseline.rolloutEvents(),
                    "residualRolloutEvents", residual.rolloutEvents(),
                    "maxBaselineCollectorCollections", baseline.maxCollectorCollections(),
                    "maxResidualCollectorCollections", residual.maxCollectorCollections(),
                    "routeCostCacheEntries", baseline.routeCostCacheEntries(),
                    "pathfindingExecutions", baseline.pathfindingExecutions(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                    "expanded", finalStats.expandedStates(), "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(), "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logHarvestCapacity(state, capacity);
            }
        } else if (isRelativeMarginPolicy()) {
            RelativeMarginPlanEvaluation evaluation = relativeMarginIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
            OpponentResidualClaimEvaluation denial = evaluation.opponentClaims();
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "ownSemiBrands", evaluation.ownSemiBrands(),
                    "ownSemiCollections", evaluation.ownSemiCollections(),
                    "rawUdon", evaluation.semiCommitment().base().udonTotal(),
                    "opponentBaselineStrongRealizable", evaluation.opponentBaselineStrongRealizable(),
                    "opponentResidualStrongRealizable", evaluation.opponentResidualStrongRealizable(),
                    "deniedObservedNow", denial.deniedObservedNow(),
                    "deniedDirect", denial.deniedDirectIntent(),
                    "deniedFollowOn", denial.deniedFollowOnIntent(),
                    "strongDeniedOpponentCollections", evaluation.strongDeniedOpponentCollections(),
                    "projectedStrongRelativeSwing", evaluation.projectedStrongRelativeSwing(),
                    "semiScore", evaluation.semiScore(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                    "expanded", finalStats.expandedStates(), "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(), "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logHarvestCapacity(state, capacity);
            }
        } else if (isHarvestHorizonAwarePolicy()) {
            HarvestHorizonAwarePlanEvaluation evaluation = harvestHorizonIncumbent.evaluation();
            TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"), "day", state.day().value(),
                    "remainingFutureDays", capacity.remainingFutureDays(),
                    "semiBrands", evaluation.semiCommitment().semiCommitmentRealizableBrandCount(),
                    "semiScore", evaluation.semiCommitment().adjustedCollectionScore().value(),
                    "semiCollections", evaluation.semiCommitment().semiCommitmentRealizableCollections(),
                    "rawUdon", evaluation.semiCommitment().base().udonTotal(),
                    "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                    "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                    "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                    "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                    "expanded", finalStats.expandedStates(), "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(), "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logHarvestCapacity(state, capacity);
            }
        } else if (isHorizonAwarePolicy()) {
            HorizonAwarePlanEvaluation evaluation = horizonIncumbent.evaluation();
            SemiCommitmentAwarePlanEvaluation semi = evaluation.semiCommitment();
            TeamFutureReadiness future = evaluation.futureReadiness();
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"),
                    "day", state.day().value(),
                    "remainingFutureDays", future.remainingFutureDays(),
                    "semiBrands", semi.semiCommitmentRealizableBrandCount(),
                    "semiScore", semi.adjustedCollectionScore().value(),
                    "semiCollections", semi.semiCommitmentRealizableCollections(),
                    "rawUdon", semi.base().udonTotal(),
                    "projectedFinalPatrolFuel", future.totalProjectedPatrolFuel(),
                    "futureReadyPatrols", future.futureReadyPatrolCount(),
                    "futureReachableSpots", future.totalReachableOpportunitySpots(),
                    "futureReachableBrands", future.totalReachableOpportunityBrands(),
                    "minimumPatrolReadiness", future.minimumPatrolReadiness(),
                    "expanded", finalStats.expandedStates(),
                    "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(),
                    "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logHorizonReadiness(state, future);
            }
        } else if (isSemiCommitmentAwarePolicy()) {
            SemiCommitmentAwarePlanEvaluation evaluation = semiCommitmentIncumbent.evaluation();
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"),
                    "day", state.day().value(),
                    "localBrands", evaluation.base().teamBrandCount(),
                    "semiCommitmentBrands", evaluation.semiCommitmentRealizableBrandCount(),
                    "rawUdon", evaluation.base().udonTotal(),
                    "oldForecastRealizableCollections",
                    evaluation.oldForecastRealizableCollections(),
                    "commitmentRealizableCollections", evaluation.commitmentRealizableCollections(),
                    "semiCommitmentRealizableCollections",
                    evaluation.semiCommitmentRealizableCollections(),
                    "semiCommitmentAdjustedScore", evaluation.adjustedCollectionScore().value(),
                    "hardClaimedFirst", evaluation.hardClaimedFirstCollections(),
                    "semiClaimedFirst", evaluation.semiClaimedFirstCollections(),
                    "directIntentBefore", evaluation.directIntentBeforeCollections(),
                    "followOnIntentBefore", evaluation.followOnIntentBeforeCollections(),
                    "tieCollections", evaluation.tieCollections(),
                    "expanded", finalStats.expandedStates(),
                    "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(),
                    "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logStrategyDepthSummary(
                        state, stratifiedFrontier, scheduler,
                        bestSemiCommitmentByStrategy.keySet());
            }
        } else if (isCommitmentAwarePolicy()) {
            CommitmentAwarePlanEvaluation evaluation = commitmentIncumbent.evaluation();
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"),
                    "day", state.day().value(),
                    "localBrands", evaluation.base().teamBrandCount(),
                    "commitmentBrands", evaluation.commitmentRealizableBrandCount(),
                    "rawUdon", evaluation.base().udonTotal(),
                    "commitmentRealizableCollections", evaluation.commitmentRealizableCollections(),
                    "commitmentAdjustedScore", evaluation.adjustedCollectionScore().value(),
                    "oldForecastRealizableCollections",
                    evaluation.oldForecastRealizableCollections(),
                    "hardClaimedFirst", evaluation.hardClaimedFirstCollections(),
                    "directIntentBefore", evaluation.directIntentBeforeCollections(),
                    "followOnIntentBefore", evaluation.followOnIntentBeforeCollections(),
                    "tieCollections", evaluation.tieCollections(),
                    "expanded", finalStats.expandedStates(),
                    "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(),
                    "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logStrategyDepthSummary(
                        state, stratifiedFrontier, scheduler, bestCommitmentByStrategy.keySet());
            }
        } else if (isStratifiedIntentPolicy()) {
            IntentAwarePlanEvaluation evaluation = intentAwareIncumbent.evaluation();
            StratifiedSearchStats depth = stratifiedSearchStats.orElseThrow();
            log(event("DONE"),
                    "day", state.day().value(),
                    "forecastBrands", evaluation.forecastRealizableBrandCount(),
                    "intentAdjustedScore", evaluation.adjustedCollectionScore().value(),
                    "forecastRealizableCollections", evaluation.forecastRealizableCollections(),
                    "rawUdon", evaluation.base().udonTotal(),
                    "likelyClaimedFirst", evaluation.likelyClaimedFirstCollections(),
                    "expanded", finalStats.expandedStates(),
                    "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "strategiesDiscovered", depth.strategiesDiscovered(),
                    "strategiesQualified", depth.strategiesQualified(),
                    "strategiesExpanded", depth.strategiesExpanded(),
                    "strategiesWithAtLeast2Expansions", depth.strategiesWithAtLeast2Expansions(),
                    "strategiesWithAtLeast3Expansions", depth.strategiesWithAtLeast3Expansions(),
                    "maxStrategyExpansionCount", depth.maxStrategyExpansionCount(),
                    "medianStrategyExpansionCount", depth.medianStrategyExpansionCount(),
                    "qualifiedStrategiesMeetingMinimumDepth",
                    depth.qualifiedStrategiesMeetingMinimumDepth(),
                    "qualifiedStrategiesExhaustedBeforeMinimum",
                    depth.qualifiedStrategiesExhaustedBeforeMinimum(),
                    "discoveryExpansions", depth.discoveryExpansions(),
                    "qualificationExpansions", depth.qualificationExpansions(),
                    "exploitationExpansions", depth.exploitationExpansions(),
                    "frontierPeak", depth.frontierPeak(),
                    "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                logStrategyDepthSummary(
                        state, stratifiedFrontier, scheduler, bestCompleteByStrategy.keySet());
            }
        } else if (isDiverseIntentPolicy()) {
            IntentAwarePlanEvaluation evaluation = intentAwareIncumbent.evaluation();
            DiverseSearchStats diversity = diverseSearchStats.orElseThrow();
            log(event("DONE"),
                    "day", state.day().value(),
                    "forecastBrands", evaluation.forecastRealizableBrandCount(),
                    "intentAdjustedScore", evaluation.adjustedCollectionScore().value(),
                    "forecastRealizableCollections", evaluation.forecastRealizableCollections(),
                    "rawUdon", evaluation.base().udonTotal(),
                    "likelyClaimedFirst", evaluation.likelyClaimedFirstCollections(),
                    "expanded", finalStats.expandedStates(),
                    "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "generatedCandidates", finalStats.candidateGenerated(),
                    "selectedCandidates", finalStats.candidateRetained(),
                    "uniqueStrategyKeysGenerated", diversity.uniqueStrategyKeysGenerated(),
                    "uniqueStrategyKeysExpanded", diversity.uniqueStrategyKeysExpanded(),
                    "qualityExpansions", diversity.qualityExpansions(),
                    "diversityExpansions", diversity.diversityExpansions(),
                    "maxStrategyExpansionCount", diversity.maxStrategyExpansionCount(),
                    "frontierPeak", diversity.frontierPeak(),
                    "budgetExhausted", finalStats.budgetExhausted());
            if (contentionDiagnostics) {
                log("SEARCH_DIVERSITY_SUMMARY",
                        "day", state.day().value(),
                        "candidateEliteSelected", diversity.candidateEliteSelected(),
                        "candidateDiverseSelected", diversity.candidateDiverseSelected(),
                        "frontierEliteRetained", diversity.frontierEliteRetained(),
                        "frontierDiverseRetained", diversity.frontierDiverseRetained(),
                        "strategyBucketsSeen", diversity.strategyBucketsSeen(),
                        "statesRejectedByExactDedup", diversity.statesRejectedByExactDedup(),
                        "statesRejectedByFrontierLimit", diversity.statesRejectedByFrontierLimit());
            }
        } else if (isIntentAwarePolicy()) {
            IntentAwarePlanEvaluation evaluation = intentAwareIncumbent.evaluation();
            log(event("DONE"),
                    "day", state.day().value(),
                    "localBrands", evaluation.base().teamBrandCount(),
                    "forecastBrands", evaluation.forecastRealizableBrandCount(),
                    "rawUdon", evaluation.base().udonTotal(),
                    "intentAdjustedScore", evaluation.adjustedCollectionScore().value(),
                    "forecastRealizableCollections", evaluation.forecastRealizableCollections(),
                    "likelyClaimedFirst", evaluation.likelyClaimedFirstCollections(),
                    "tieCollections", evaluation.tieCollections(),
                    "unforecastedCollections", evaluation.unforecastedCollections(),
                    "expanded", finalStats.expandedStates(),
                    "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "budgetExhausted", finalStats.budgetExhausted());
        } else if (isRiskAdjustedPolicy()) {
            RiskAdjustedPlanEvaluation evaluation = riskAdjustedIncumbent.evaluation();
            log(event("DONE"),
                    "day", state.day().value(),
                    "brands", evaluation.base().teamBrandCount(),
                    "rawUdon", evaluation.base().udonTotal(),
                    "adjustedScore", evaluation.adjustedCollectionScore().value(),
                    "safeProjected", evaluation.arrivalSafeCollections(),
                    "tiedProjected", evaluation.arrivalTiedCollections(),
                    "riskProjected", evaluation.arrivalAtRiskCollections(),
                    "unobservedProjected", evaluation.unobservedCollections(),
                    "expanded", finalStats.expandedStates(),
                    "completedPlans", finalStats.completedPlans(),
                    "improvements", finalStats.incumbentImprovements(),
                    "budgetExhausted", finalStats.budgetExhausted());
        } else if (isArrivalPolicy(policy)) {
            ArrivalAttribution arrivalAttr = ArrivalAttribution.fromSimulation(
                    state, simulator.simulate(state, incumbent.plan), context.arrivalLowerBounds, context.contentionAnalyzer);
            ContentionAttribution staticAttr = finalContentionAttribution(state, incumbent.plan, context);
            boolean weighted = policy == AnytimeSearchPolicy.ANYTIME_WEIGHTED_ARRIVAL_CONTENTION;
            if (weighted) {
                log(event("DONE"),
                        "day", state.day().value(),
                        "brands", incumbent.evaluation.teamBrandCount(),
                        "udon", incumbent.evaluation.udonTotal(),
                        "expanded", finalStats.expandedStates(),
                        "coverageExpanded", finalStats.coveragePhaseExpandedStates(),
                        "harvestExpanded", finalStats.harvestPhaseExpandedStates(),
                        "generatedCandidates", finalStats.candidateGenerated(),
                        "retainedCandidates", finalStats.candidateRetained(),
                        "prunedByTopK", finalStats.candidatePrunedByTopK(),
                        "improvements", finalStats.incumbentImprovements(),
                        "arrivalAwareIncumbentImprovements", finalStats.incumbentImprovements(),
                        "weightedArrivalSafeProjected", arrivalAttr.arrivalSafeProjected(),
                        "weightedArrivalTiedProjected", arrivalAttr.arrivalTiedProjected(),
                        "weightedArrivalAtRiskProjected", arrivalAttr.arrivalAtRiskProjected(),
                        "safeProjected", staticAttr.safeProjected(),
                        "tiedProjected", staticAttr.tiedProjected(),
                        "contestedProjected", staticAttr.contestedProjected(),
                        "budgetExhausted", finalStats.budgetExhausted());
            } else {
                log(event("DONE"),
                        "day", state.day().value(),
                        "brands", incumbent.evaluation.teamBrandCount(),
                        "udon", incumbent.evaluation.udonTotal(),
                        "expanded", finalStats.expandedStates(),
                        "coverageExpanded", finalStats.coveragePhaseExpandedStates(),
                        "harvestExpanded", finalStats.harvestPhaseExpandedStates(),
                        "generatedCandidates", finalStats.candidateGenerated(),
                        "retainedCandidates", finalStats.candidateRetained(),
                        "prunedByTopK", finalStats.candidatePrunedByTopK(),
                        "improvements", finalStats.incumbentImprovements(),
                        "arrivalSafeProjected", arrivalAttr.arrivalSafeProjected(),
                        "arrivalTiedProjected", arrivalAttr.arrivalTiedProjected(),
                        "arrivalAtRiskProjected", arrivalAttr.arrivalAtRiskProjected(),
                        "safeProjected", staticAttr.safeProjected(),
                        "tiedProjected", staticAttr.tiedProjected(),
                        "contestedProjected", staticAttr.contestedProjected(),
                        "budgetExhausted", finalStats.budgetExhausted());
            }
        } else if (policy != AnytimeSearchPolicy.ORIGINAL) {
            ContentionAttribution attribution = policy == AnytimeSearchPolicy.CONTENTION
                    ? finalContentionAttribution(state, incumbent.plan, context)
            : new ContentionAttribution(0, 0, 0, 0, 0);
            log(event("DONE"),
                    "day", state.day().value(),
                    "brands", incumbent.evaluation.teamBrandCount(),
                    "udon", incumbent.evaluation.udonTotal(),
                    "expanded", finalStats.expandedStates(),
                    "coverageExpanded", finalStats.coveragePhaseExpandedStates(),
                    "harvestExpanded", finalStats.harvestPhaseExpandedStates(),
                    "generatedCandidates", finalStats.candidateGenerated(),
                    "retainedCandidates", finalStats.candidateRetained(),
                    "prunedByTopK", finalStats.candidatePrunedByTopK(),
                    "improvements", finalStats.incumbentImprovements(),
                    "safeProjected", policy == AnytimeSearchPolicy.CONTENTION
                            ? attribution.safeProjected() : 0,
                    "tiedProjected", policy == AnytimeSearchPolicy.CONTENTION
                            ? attribution.tiedProjected() : 0,
                    "contestedProjected", policy == AnytimeSearchPolicy.CONTENTION
                            ? attribution.contestedProjected() : 0,
                    "unobservedProjected", policy == AnytimeSearchPolicy.CONTENTION
                            ? attribution.unobservedProjected() : 0,
                    "stronglyContestedProjected", policy == AnytimeSearchPolicy.CONTENTION
                            ? attribution.stronglyContestedProjected() : 0,
                    "budgetExhausted", finalStats.budgetExhausted());
        } else {
            log(event("DONE"),
                    "day", state.day().value(),
                    "brands", incumbent.evaluation.teamBrandCount(),
                    "udon", incumbent.evaluation.udonTotal(),
                    "expanded", finalStats.expandedStates(),
                    "improvements", finalStats.incumbentImprovements(),
                    "budgetExhausted", finalStats.budgetExhausted());
        }
        Optional<RiskAdjustedPlanEvaluation> riskAdjustedEvaluation = isRiskAdjustedPolicy()
                ? Optional.of(riskAdjustedIncumbent.evaluation())
                : Optional.empty();
        Optional<IntentAwarePlanEvaluation> intentAwareEvaluation = isIntentAwarePolicy()
                ? Optional.of(intentAwareIncumbent.evaluation())
                : Optional.empty();
        Optional<CommitmentAwarePlanEvaluation> commitmentAwareEvaluation = isCommitmentAwarePolicy()
                ? Optional.of(commitmentIncumbent.evaluation())
                : Optional.empty();
        Optional<SemiCommitmentAwarePlanEvaluation> semiCommitmentAwareEvaluation =
                isSemiCommitmentAwarePolicy()
                        ? Optional.of(semiCommitmentIncumbent.evaluation())
                        : Optional.empty();
        Optional<HorizonAwarePlanEvaluation> horizonAwareEvaluation = isHorizonAwarePolicy()
                ? Optional.of(horizonIncumbent.evaluation()) : Optional.empty();
        Optional<HarvestHorizonAwarePlanEvaluation> harvestHorizonAwareEvaluation =
                isHarvestHorizonAwarePolicy()
                        ? Optional.of(harvestHorizonIncumbent.evaluation()) : Optional.empty();
        Optional<RelativeMarginPlanEvaluation> relativeMarginEvaluation =
                isRelativeMarginPolicy()
                        ? Optional.of(relativeMarginIncumbent.evaluation()) : Optional.empty();
        Optional<ReplacementAwareRelativeMarginEvaluation> replacementAwareEvaluation =
                isReplacementAwarePolicy()
                        ? Optional.of(replacementAwareIncumbent.evaluation()) : Optional.empty();
        Optional<CoupledCompetitiveMarginEvaluation> coupledCompetitiveEvaluation =
                isCoupledCompetitivePolicy()
                        ? Optional.of(coupledCompetitiveIncumbent.evaluation()) : Optional.empty();
        Optional<HybridCalibratedMarginEvaluation> hybridCalibratedMarginEvaluation =
                isHybridEvaluatorPolicy()
                        ? Optional.of(hybridCalibratedMarginIncumbent.evaluation()) : Optional.empty();
        return new AnytimePlanResult(
                incumbent.plan, incumbent.evaluation, finalStats,
                riskAdjustedEvaluation, intentAwareEvaluation, diverseSearchStats,
                stratifiedSearchStats, commitmentAwareEvaluation, semiCommitmentAwareEvaluation,
                horizonAwareEvaluation, harvestHorizonAwareEvaluation, relativeMarginEvaluation,
                replacementAwareEvaluation, coupledCompetitiveEvaluation, hybridCalibratedMarginEvaluation);
    }

    /**
     * Collapses the frontier's per-strategy metadata and the scheduler's stage counters into the
     * bounded M11 correction diagnostics. Stage counters come from actual expansions only, so
     * their sum is the number of states the search really expanded.
     */
    private StratifiedSearchStats stratifiedStats(
            StratifiedFrontier<SearchState> frontier,
            StrategyStageScheduler<SearchState> scheduler,
            DiverseMutableStats diverseStats,
            boolean budgetExhausted) {
        List<Integer> counts = new ArrayList<>(frontier.expansionCountsByStrategy().values());
        counts.sort(Comparator.naturalOrder());
        int median = counts.isEmpty() ? 0 : counts.get((counts.size() - 1) / 2);
        return new StratifiedSearchStats(
                diverseStats.generatedStrategies.size(),
                scheduler.qualifiedStrategies().size(),
                counts.size(),
                (int) counts.stream().filter(count -> count >= 2).count(),
                (int) counts.stream().filter(count -> count >= 3).count(),
                counts.isEmpty() ? 0 : counts.get(counts.size() - 1),
                median,
                scheduler.qualifiedStrategiesMeetingMinimumDepth(),
                scheduler.qualifiedStrategiesExhaustedBeforeMinimum(),
                scheduler.discoveryExpansions(),
                scheduler.qualificationExpansions(),
                scheduler.exploitationExpansions(),
                diverseStats.frontierPeak,
                budgetExhausted);
    }

    /** One bounded row per strategy, capped so diagnostics can never dump the search space. */
    private void logStrategyDepthSummary(
            DayState state,
            StratifiedFrontier<SearchState> frontier,
            StrategyStageScheduler<SearchState> scheduler,
            Set<StrategicDiversityKey> strategiesWithBestComplete) {
        frontier.expansionCountsByStrategy().entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<StrategicDiversityKey, Integer>>comparingInt(
                                entry -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_STRATEGY_DEPTH_DIAGNOSTICS)
                .forEach(entry -> log("STRATEGY_DEPTH_SUMMARY",
                        "day", state.day().value(),
                        "strategyKey", entry.getKey(),
                        "totalExpansions", entry.getValue(),
                        "qualificationExpansions", scheduler.qualificationExpansions(entry.getKey()),
                        "bestFrontierRankOrOrdinal", frontier.bestFrontierOrdinal(entry.getKey()),
                        "bestCompleteFound",
                        strategiesWithBestComplete.contains(entry.getKey())));
    }

    private void logContentionSpots(DayState state, SearchContext context) {
        state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                .limit(MAX_CONTENTION_SPOT_DIAGNOSTICS)
                .forEach(spot -> {
                    ContentionMetrics metrics = context.contentionAt(spot.position());
                    log("CONTENTION_SPOT",
                            "day", state.day().value(),
                            "spot", spot.position().value(),
                            "ourHexDistance", optionalDistance(metrics.ourNearestHexDistance()),
                            "otherHexDistance", optionalDistance(metrics.otherNearestHexDistance()),
                            "advantage", optionalDistance(metrics.distanceAdvantage()),
                            "classification", metrics.classification());
                });
    }

    private void logArrivalBoundComparisons(DayState state, SearchContext context) {
        state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                .limit(MAX_CONTENTION_SPOT_DIAGNOSTICS)
                .forEach(spot -> {
                    OptionalInt ourArrival = ourEarliestArrivalStep(state, spot.position());
                    OptionalInt hexBound = context.opponentHexLowerBounds.getOrDefault(
                            spot.position(), OptionalInt.empty());
                    OptionalInt weightedBound = context.opponentWeightedLowerBounds.getOrDefault(
                            spot.position(), OptionalInt.empty());
                    ArrivalContentionClassification oldClassification = ourArrival.isPresent()
                            ? context.contentionAnalyzer.analyzeArrival(
                                    spot.position(), ourArrival.getAsInt(), hexBound).classification()
                            : ArrivalContentionClassification.UNOBSERVED;
                    ArrivalContentionClassification weightedClassification = ourArrival.isPresent()
                            ? context.contentionAnalyzer.analyzeArrival(
                                    spot.position(), ourArrival.getAsInt(), weightedBound).classification()
                            : ArrivalContentionClassification.UNOBSERVED;
                    log("ARRIVAL_BOUND_COMPARISON",
                            "position", spot.position().value(),
                            "ourArrivalStep", optionalDistance(ourArrival),
                            "opponentHexDistanceLowerBound", optionalDistance(hexBound),
                            "opponentWeightedStepLowerBound", optionalDistance(weightedBound),
                            "oldClassification", oldClassification,
                            "weightedClassification", weightedClassification);
                });
    }

    /**
     * One bounded M12 summary per day. Together with the DONE line it carries everything needed to
     * compute {@code raw - oldForecastRealizable} and {@code raw - commitmentRealizable} without
     * emitting a single per-collection or per-claim row.
     */
    private void logCommitmentForecast(DayState state, SearchContext context) {
        OpponentCommitmentForecast forecast = context.commitmentForecast;
        log("OPPONENT_COMMITMENT_SUMMARY",
                "day", state.day().value(),
                "observedAgents", forecast.observedAgentCount(),
                "collectionEligibleAgents", forecast.collectionEligibleAgentCount(),
                "forecastClaims", forecast.forecastClaims(),
                "observedNowClaims", forecast.observedNowClaims(),
                "directIntentClaims", forecast.directIntentClaims(),
                "followOnIntentClaims", forecast.followOnIntentClaims(),
                "hardConsumedPortions", forecast.hardConsumedPortions(),
                "stockedSpots", forecast.stockedSpotCount());
    }

    private void logOpponentDenialBaseline(DayState state, OpponentClaimBaseline baseline) {
        log("OPPONENT_DENIAL_BASELINE",
                "day", state.day().value(),
                "forecastClaims", baseline.forecastClaims(),
                "baselineObservedNowRealizable", baseline.observedNowRealizable(),
                "baselineDirectRealizable", baseline.directIntentRealizable(),
                "baselineFollowOnRealizable", baseline.followOnIntentRealizable(),
                "baselineStrongRealizable", baseline.strongRealizable(),
                "stockedSpots", baseline.stockedSpots());
    }

    /**
     * One bounded M12.1 summary per day, never a row per claim.
     *
     * <p>{@code maxSemiReservedPortions} doubles as the boundedness probe: under the production rule
     * it must always read zero or one, however many direct claimers target the same spot.</p>
     */
    private void logSemiCommitmentForecast(DayState state, SearchContext context) {
        SemiCommitmentForecast semi = context.semiCommitmentForecast;
        OpponentCommitmentForecast forecast = semi.commitment();
        log("OPPONENT_SEMI_COMMITMENT_SUMMARY",
                "day", state.day().value(),
                "observedAgents", forecast.observedAgentCount(),
                "collectionEligibleAgents", forecast.collectionEligibleAgentCount(),
                "forecastClaims", forecast.forecastClaims(),
                "observedNowClaims", forecast.observedNowClaims(),
                "directIntentClaims", forecast.directIntentClaims(),
                "followOnIntentClaims", forecast.followOnIntentClaims(),
                "hardConsumedPortions", forecast.hardConsumedPortions(),
                "semiReservedSpots", semi.semiReservedSpots(),
                "maxSemiReservedPortions", semi.maxSemiReservedPortions(),
                "stockedSpots", forecast.stockedSpotCount());
    }

    private void logIntentForecast(DayState state, SearchContext context) {
        OpponentIntentForecast forecast = context.intentForecast;
        log("OPPONENT_INTENT_SUMMARY",
                "day", state.day().value(),
                "groups", forecast.groups().size(),
                "observedAgents", forecast.observedAgentCount(),
                "collectionEligibleAgents", forecast.collectionEligibleAgentCount(),
                "stockedSpots", forecast.stockedSpotCount(),
                "physicalPairsAllObserved", forecast.physicalPairsAllObserved(),
                "physicalPairsCollectionEligible", forecast.physicalPairsCollectionEligible(),
                "retainedIntentTargets", forecast.retainedIntentTargets(),
                "forecastClaims", forecast.forecastClaims());
        forecast.groups().stream()
                .flatMap(group -> group.agents().stream()
                        .map(agent -> new ObservedAgentDiagnostic(group.groupRawId(), agent)))
                .limit(12)
                .forEach(item -> log("OPPONENT_OBSERVED_AGENT",
                        "groupRawId", item.groupRawId(),
                        "agentIndex", item.agent().agentIndex(),
                        "rawKind", item.agent().rawKind(),
                        "collectionEligible", item.agent().collectionEligible(),
                        "physicallyReachableSpots", item.agent().physicallyReachableSpots(),
                        "collectorIntentTargets", item.agent().targets().size()));
        forecast.groups().stream()
                .flatMap(group -> group.agents().stream()
                        .flatMap(agent -> agent.targets().stream()
                                .map(target -> new IntentDiagnostic(group.groupRawId(), agent, target))))
                .limit(12)
                .forEach(item -> log("OPPONENT_INTENT_TARGET",
                        "groupRawId", item.groupRawId(),
                        "agentIndex", item.agent().agentIndex(),
                        "rawKind", item.agent().rawKind(),
                        "collectionEligible", item.agent().collectionEligible(),
                        "spot", item.target().spot().value(),
                        "rank", item.target().rank(),
                        "travelSteps", item.target().optimisticTravelSteps(),
                        "pressureUnits", item.target().pressureUnits(),
                        "forecastArrivalStep", item.agent().collectionEligible()
                                && item.target().forecastArrivalStep().isPresent()
                                ? item.target().forecastArrivalStep().getAsInt() : "UNCLAIMED"));
        forecast.pressureBySpot().values().stream()
                .sorted(Comparator.comparingInt(value -> value.spot().value()))
                .limit(8)
                .forEach(value -> log("INTENT_STOCK_PRESSURE",
                        "spot", value.spot().value(),
                        "currentStock", value.currentStock(),
                        "forecastClaims", value.forecastClaimedPortions(),
                        "earliestClaimStep", value.earliestClaimStep().isPresent()
                                ? value.earliestClaimStep().getAsInt() : "UNAVAILABLE",
                        "pressureUnits", value.intentPressureUnits()));
    }

    private OptionalInt ourEarliestArrivalStep(DayState state, Position target) {
        return state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .map(agent -> patrolRouteFinder.find(state, agent, target))
                .flatMap(Optional::stream)
                .mapToInt(Route::stepsUsed)
                .min();
    }

    private void logContentionCandidates(
            DayState state,
            List<TeamTargetCandidate> candidates,
            SearchContext context) {
        candidates.stream().limit(MAX_CONTENTION_CANDIDATE_DIAGNOSTICS).forEach(candidate -> {
            if (context.loggedCandidateDiagnostics >= MAX_CONTENTION_CANDIDATE_DIAGNOSTICS) {
                return;
            }
            RouteContentionMetrics metrics = contentionFor(context, candidate);
            log("CONTENTION_CANDIDATE",
                    "day", state.day().value(),
                    "agent", candidate.patrolAgentId().value(),
                    "target", candidate.targetPosition().value(),
                    "totalGain", metrics.projectedCollectionGain(),
                    "safe", metrics.safeProjectedCollections(),
                    "tied", metrics.tiedProjectedCollections(),
                    "contested", metrics.contestedProjectedCollections(),
                    "stronglyContested", metrics.stronglyContestedCollections(),
                    "newTeamBrand", candidate.newBrandForTeamToday());
            context.loggedCandidateDiagnostics++;
        });
    }

    private void logArrivalContentionCandidates(
            DayState state,
            List<TeamTargetCandidate> candidates,
            SearchContext context) {
        candidates.stream().limit(MAX_CONTENTION_CANDIDATE_DIAGNOSTICS).forEach(candidate -> {
            if (context.loggedCandidateDiagnostics >= MAX_CONTENTION_CANDIDATE_DIAGNOSTICS) {
                return;
            }
            RouteArrivalContentionMetrics metrics = context.candidateArrivalContention.get(candidate);
            if (metrics == null) {
                return;
            }
            log("ARRIVAL_CONTENTION_CANDIDATE",
                    "day", state.day().value(),
                    "agent", candidate.patrolAgentId().value(),
                    "target", candidate.targetPosition().value(),
                    "totalGain", metrics.projectedCollectionGain(),
                    "arrivalSafe", metrics.arrivalSafeCollections(),
                    "arrivalTied", metrics.arrivalTiedCollections(),
                    "arrivalAtRisk", metrics.arrivalAtRiskCollections());
            context.loggedCandidateDiagnostics++;
        });
    }

    private Optional<ArrivalEvaluatedPlan> evaluateArrival(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<EvaluatedPlan> baseEval = evaluate(state, plan);
        if (baseEval.isEmpty()) {
            return Optional.empty();
        }
        EvaluatedPlan base = baseEval.orElseThrow();
        DaySimulationResult simulation = simulator.simulate(state, plan);
        ArrivalAttribution arrivalAttr = ArrivalAttribution.fromSimulation(
                state, simulation, context.arrivalLowerBounds, context.contentionAnalyzer);
        ContentionAttribution staticAttr = ContentionAttribution.fromSimulation(
                state, simulation, context.contentionAnalyzer);
        ArrivalAwarePlanEvaluation arrivalEval = new ArrivalAwarePlanEvaluation(
                base.evaluation(),
                arrivalAttr.arrivalSafeProjected(),
                arrivalAttr.arrivalTiedProjected(),
                arrivalAttr.arrivalAtRiskProjected(),
                staticAttr.stronglyContestedProjected());
        return Optional.of(new ArrivalEvaluatedPlan(plan, arrivalEval, base));
    }

    private Optional<RiskAdjustedEvaluatedPlan> evaluateRiskAdjusted(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<EvaluatedPlan> baseEval = evaluate(state, plan);
        if (baseEval.isEmpty()) {
            return Optional.empty();
        }
        EvaluatedPlan base = baseEval.orElseThrow();
        DaySimulationResult simulation = simulator.simulate(state, plan);
        ArrivalAttribution arrivalAttr = ArrivalAttribution.fromSimulation(
                state, simulation, context.arrivalLowerBounds, context.contentionAnalyzer);
        ContentionAttribution staticAttr = ContentionAttribution.fromSimulation(
                state, simulation, context.contentionAnalyzer);
        RiskAdjustedPlanEvaluation riskAdjusted = new RiskAdjustedPlanEvaluation(
                base.evaluation(),
                ContentionAdjustedCollectionScore.from(arrivalAttr, riskAdjustmentWeights),
                arrivalAttr.arrivalSafeProjected(),
                arrivalAttr.arrivalTiedProjected(),
                arrivalAttr.arrivalAtRiskProjected(),
                arrivalAttr.unobservedProjected(),
                staticAttr.stronglyContestedProjected());
        return Optional.of(new RiskAdjustedEvaluatedPlan(plan, riskAdjusted, base));
    }

    private Optional<IntentAwareEvaluatedPlan> evaluateIntentAware(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<EvaluatedPlan> baseEval = evaluate(state, plan);
        if (baseEval.isEmpty()) {
            return Optional.empty();
        }
        EvaluatedPlan base = baseEval.orElseThrow();
        DaySimulationResult simulation = simulator.simulate(state, plan);
        IntentCollectionAttribution attribution = context.intentEvaluator.evaluate(
                state, simulation, context.intentForecast, intentAdjustmentWeights);
        IntentAwarePlanEvaluation evaluation = new IntentAwarePlanEvaluation(
                base.evaluation(),
                attribution.adjustedScore(),
                attribution.forecastRealizableBrands().size(),
                attribution.forecastRealizableCollections(),
                attribution.likelyClaimedFirstCollections(),
                attribution.tieCollections(),
                attribution.unforecastedCollections());
        return Optional.of(new IntentAwareEvaluatedPlan(plan, evaluation, base));
    }

    /**
     * Section-22 pipeline for every plan alike, incumbent included: simulate, reuse the already
     * computed opponent forecast, apply the commitment annotation, apply hard stock depletion,
     * attribute our own collections, then build the M12 evaluation.
     */
    private Optional<CommitmentAwareEvaluatedPlan> evaluateCommitmentAware(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<EvaluatedPlan> baseEval = evaluate(state, plan);
        if (baseEval.isEmpty()) {
            return Optional.empty();
        }
        EvaluatedPlan base = baseEval.orElseThrow();
        DaySimulationResult simulation = simulator.simulate(state, plan);
        CommitmentCollectionAttribution attribution = context.commitmentEvaluator.evaluate(
                state, simulation, context.commitmentForecast, commitmentAdjustmentWeights);
        CommitmentAwarePlanEvaluation evaluation = new CommitmentAwarePlanEvaluation(
                base.evaluation(),
                attribution.adjustedScore(),
                attribution.commitmentRealizableBrands().size(),
                attribution.commitmentRealizableCollections(),
                attribution.oldForecastRealizableCollections(),
                attribution.hardClaimedFirstCollections(),
                attribution.directIntentBeforeCollections(),
                attribution.followOnIntentBeforeCollections(),
                attribution.tieCollections(),
                attribution.unforecastedCollections());
        return Optional.of(new CommitmentAwareEvaluatedPlan(plan, evaluation, base));
    }

    /**
     * The same pipeline for every M12.1 plan alike, incumbent included: simulate, reuse the already
     * computed opponent forecast and its unchanged M12 commitment annotation, apply hard depletion,
     * then the one bounded direct reservation per spot, attribute our own collections, and build the
     * M12.1 evaluation. No mixed evaluator and no second forecast.
     */
    private Optional<SemiCommitmentAwareEvaluatedPlan> evaluateSemiCommitmentAware(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<EvaluatedPlan> baseEval = evaluate(state, plan);
        if (baseEval.isEmpty()) {
            return Optional.empty();
        }
        EvaluatedPlan base = baseEval.orElseThrow();
        DaySimulationResult simulation = simulator.simulate(state, plan);
        SemiCommitmentCollectionAttribution attribution =
                context.semiCommitmentEvaluator.evaluate(
                        state, simulation, context.semiCommitmentForecast.commitment(),
                        semiCommitmentAdjustmentWeights);
        SemiCommitmentAwarePlanEvaluation evaluation = new SemiCommitmentAwarePlanEvaluation(
                base.evaluation(),
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
        return Optional.of(new SemiCommitmentAwareEvaluatedPlan(plan, evaluation, base));
    }

    private Optional<HorizonAwareEvaluatedPlan> evaluateHorizonAware(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<SemiCommitmentAwareEvaluatedPlan> semi =
                evaluateSemiCommitmentAware(state, plan, context);
        if (semi.isEmpty()) {
            return Optional.empty();
        }
        DaySimulationResult simulation = simulator.simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return Optional.empty();
        }
        SemiCommitmentAwareEvaluatedPlan current = semi.orElseThrow();
        HorizonAwarePlanEvaluation evaluation = new HorizonAwarePlanEvaluation(
                current.evaluation(), context.futureReadinessCalculator.evaluate(valid));
        return Optional.of(new HorizonAwareEvaluatedPlan(plan, evaluation, current.base()));
    }

    private Optional<HarvestHorizonAwareEvaluatedPlan> evaluateHarvestHorizonAware(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<SemiCommitmentAwareEvaluatedPlan> semi =
                evaluateSemiCommitmentAware(state, plan, context);
        if (semi.isEmpty()) {
            return Optional.empty();
        }
        DaySimulationResult simulation = simulator.simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return Optional.empty();
        }
        SemiCommitmentAwareEvaluatedPlan current = semi.orElseThrow();
        HarvestHorizonAwarePlanEvaluation evaluation = new HarvestHorizonAwarePlanEvaluation(
                current.evaluation(), context.nextDayHarvestCapacityCalculator.evaluate(valid));
        return Optional.of(new HarvestHorizonAwareEvaluatedPlan(plan, evaluation, current.base()));
    }

    private Optional<RelativeMarginEvaluatedPlan> evaluateRelativeMargin(
            DayState state, TeamPlan plan, SearchContext context) {
        if (!validator.validate(state, plan).valid()) {
            return Optional.empty();
        }
        DaySimulationResult simulation = simulator.simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return Optional.empty();
        }
        EvaluatedPlan base = new EvaluatedPlan(plan, baseEvaluation(state, plan, valid));
        SemiCommitmentCollectionAttribution attribution = context.semiCommitmentEvaluator.evaluate(
                state, valid, context.semiCommitmentForecast.commitment(),
                semiCommitmentAdjustmentWeights);
        SemiCommitmentAwarePlanEvaluation semi = semiEvaluation(base.evaluation(), attribution);
        OpponentResidualClaimEvaluation denial = context.opponentDenialEvaluator.evaluate(
                state, context.opponentClaimBaseline, valid, attribution);
        RelativeMarginPlanEvaluation evaluation = new RelativeMarginPlanEvaluation(
                semi, denial,
                context.nextDayHarvestCapacityCalculator.evaluate(valid));
        return Optional.of(new RelativeMarginEvaluatedPlan(plan, evaluation, base));
    }

    private SemiCommitmentAwarePlanEvaluation semiEvaluation(
            PlanEvaluation base, SemiCommitmentCollectionAttribution attribution) {
        return new SemiCommitmentAwarePlanEvaluation(
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
    }

    private ArrivalEvaluatedPlan initialArrivalIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan m7Plan;
        try {
            m7Plan = contentionFallback.plan(state);
        } catch (RuntimeException exception) {
            m7Plan = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<ArrivalEvaluatedPlan> evaluated = evaluateArrival(state, m7Plan, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        Optional<ArrivalEvaluatedPlan> safe = evaluateArrival(state, waitAll, context);
        if (safe.isEmpty()) {
            throw new IllegalStateException("Validated all-WAIT incumbent could not be simulated");
        }
        return safe.orElseThrow();
    }

    private RiskAdjustedEvaluatedPlan initialRiskAdjustedIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = contentionFallback.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<RiskAdjustedEvaluatedPlan> evaluated = evaluateRiskAdjusted(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        Optional<RiskAdjustedEvaluatedPlan> safe = evaluateRiskAdjusted(state, waitAll, context);
        if (safe.isEmpty()) {
            throw new IllegalStateException("Validated all-WAIT incumbent could not be simulated");
        }
        return safe.orElseThrow();
    }

    private IntentAwareEvaluatedPlan initialIntentAwareIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = contentionFallback.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<IntentAwareEvaluatedPlan> evaluated = evaluateIntentAware(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        Optional<IntentAwareEvaluatedPlan> safe = evaluateIntentAware(state, waitAll, context);
        if (safe.isEmpty()) {
            throw new IllegalStateException("Validated all-WAIT incumbent could not be simulated");
        }
        return safe.orElseThrow();
    }

    private CommitmentAwareEvaluatedPlan initialCommitmentAwareIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = contentionFallback.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<CommitmentAwareEvaluatedPlan> evaluated = evaluateCommitmentAware(
                state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        Optional<CommitmentAwareEvaluatedPlan> safe = evaluateCommitmentAware(
                state, waitAll, context);
        if (safe.isEmpty()) {
            throw new IllegalStateException("Validated all-WAIT incumbent could not be simulated");
        }
        return safe.orElseThrow();
    }

    private SemiCommitmentAwareEvaluatedPlan initialSemiCommitmentAwareIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = contentionFallback.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<SemiCommitmentAwareEvaluatedPlan> evaluated = evaluateSemiCommitmentAware(
                state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        Optional<SemiCommitmentAwareEvaluatedPlan> safe = evaluateSemiCommitmentAware(
                state, waitAll, context);
        if (safe.isEmpty()) {
            throw new IllegalStateException("Validated all-WAIT incumbent could not be simulated");
        }
        return safe.orElseThrow();
    }

    private HorizonAwareEvaluatedPlan initialHorizonAwareIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = teamCoordinator.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<HorizonAwareEvaluatedPlan> evaluated = evaluateHorizonAware(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        return evaluateHorizonAware(state, waitAll, context).orElseThrow(() ->
                new IllegalStateException("Validated all-WAIT incumbent could not be simulated"));
    }

    private HarvestHorizonAwareEvaluatedPlan initialHarvestHorizonAwareIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = teamCoordinator.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<HarvestHorizonAwareEvaluatedPlan> evaluated =
                evaluateHarvestHorizonAware(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        return evaluateHarvestHorizonAware(state, waitAll, context).orElseThrow(() ->
                new IllegalStateException("Validated all-WAIT incumbent could not be simulated"));
    }

    private RelativeMarginEvaluatedPlan initialRelativeMarginIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = teamCoordinator.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<RelativeMarginEvaluatedPlan> evaluated = evaluateRelativeMargin(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        return evaluateRelativeMargin(state, waitAll, context).orElseThrow(() ->
                new IllegalStateException("Validated all-WAIT incumbent could not be simulated"));
    }

    /**
     * M15: the same M12.1 attribution, then one residual full-day opponent rollout against the
     * immutable baseline that {@link SearchContext} computed once for this planning run.
     */
    private Optional<ReplacementAwareEvaluatedPlan> evaluateReplacementAware(
            DayState state, TeamPlan plan, SearchContext context) {
        if (!validator.validate(state, plan).valid()) {
            return Optional.empty();
        }
        DaySimulationResult simulation = simulator.simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return Optional.empty();
        }
        EvaluatedPlan base = new EvaluatedPlan(plan, baseEvaluation(state, plan, valid));
        SemiCommitmentCollectionAttribution attribution = context.semiCommitmentEvaluator.evaluate(
                state, valid, context.semiCommitmentForecast.commitment(),
                semiCommitmentAdjustmentWeights);
        SemiCommitmentAwarePlanEvaluation semi = semiEvaluation(base.evaluation(), attribution);
        OpponentFullDayResidualEvaluation residual = context.fullDayOpponentRollout.evaluate(
                context.opponentFullDayBaseline, valid, attribution);
        ReplacementAwareRelativeMarginEvaluation evaluation =
                new ReplacementAwareRelativeMarginEvaluation(
                        semi, residual, context.nextDayHarvestCapacityCalculator.evaluate(valid));
        return Optional.of(new ReplacementAwareEvaluatedPlan(plan, evaluation, base));
    }

    private ReplacementAwareEvaluatedPlan initialReplacementAwareIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = teamCoordinator.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<ReplacementAwareEvaluatedPlan> evaluated =
                evaluateReplacementAware(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        return evaluateReplacementAware(state, waitAll, context).orElseThrow(() ->
                new IllegalStateException("Validated all-WAIT incumbent could not be simulated"));
    }

    /**
     * M16: the same unchanged M12.1 attribution for risk and diagnostics, then ONE coupled
     * competitive rollout of the plan's fixed PATROL arrivals against the immutable no-own-plan
     * baseline that {@link SearchContext} computed once for this planning run.
     *
     * <p>The M12.1 semi-realizable collections are deliberately NOT injected as guaranteed stock
     * removals. They stay a risk signal at ordering key 7; the authoritative own quantities come from
     * the shared timeline.</p>
     */
    private Optional<CoupledCompetitiveEvaluatedPlan> evaluateCoupledCompetitive(
            DayState state, TeamPlan plan, SearchContext context) {
        if (!validator.validate(state, plan).valid()) {
            return Optional.empty();
        }
        DaySimulationResult simulation = simulator.simulate(state, plan);
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return Optional.empty();
        }
        EvaluatedPlan base = new EvaluatedPlan(plan, baseEvaluation(state, plan, valid));
        SemiCommitmentCollectionAttribution attribution = context.semiCommitmentEvaluator.evaluate(
                state, valid, context.semiCommitmentForecast.commitment(),
                semiCommitmentAdjustmentWeights);
        SemiCommitmentAwarePlanEvaluation semi = semiEvaluation(base.evaluation(), attribution);
        CoupledCompetitiveRolloutResult coupled = context.coupledCompetitiveRollout.evaluate(
                context.coupledCompetitiveBaseline, valid);
        CoupledCompetitiveMarginEvaluation evaluation = new CoupledCompetitiveMarginEvaluation(
                semi, coupled, context.nextDayHarvestCapacityCalculator.evaluate(valid));
        return Optional.of(new CoupledCompetitiveEvaluatedPlan(plan, evaluation, base));
    }

    private Optional<HybridCalibratedMarginEvaluatedPlan> evaluateHybridCalibratedMargin(
            DayState state, TeamPlan plan, SearchContext context) {
        Optional<CoupledCompetitiveEvaluatedPlan> coupled =
                evaluateCoupledCompetitive(state, plan, context);
        if (coupled.isEmpty()) {
            return Optional.empty();
        }
        CoupledCompetitiveEvaluatedPlan evaluated = coupled.orElseThrow();
        CoupledCompetitiveMarginEvaluation oldEvaluation = evaluated.evaluation();
        HybridCalibratedMarginEvaluation evaluation = new HybridCalibratedMarginEvaluation(
                oldEvaluation.semiCommitment(), oldEvaluation.coupled(), oldEvaluation.nextDayHarvestCapacity());
        return Optional.of(new HybridCalibratedMarginEvaluatedPlan(plan, evaluation, evaluated.base()));
    }

    private CoupledCompetitiveEvaluatedPlan initialCoupledCompetitiveIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = teamCoordinator.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<CoupledCompetitiveEvaluatedPlan> evaluated =
                evaluateCoupledCompetitive(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        return evaluateCoupledCompetitive(state, waitAll, context).orElseThrow(() ->
                new IllegalStateException("Validated all-WAIT incumbent could not be simulated"));
    }

    private HybridCalibratedMarginEvaluatedPlan initialHybridCalibratedMarginIncumbent(
            DayState state, MutableStats stats, SearchContext context) {
        TeamPlan fallback;
        try {
            fallback = teamCoordinator.plan(state);
        } catch (RuntimeException exception) {
            fallback = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<HybridCalibratedMarginEvaluatedPlan> evaluated =
                evaluateHybridCalibratedMargin(state, fallback, context);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        return evaluateHybridCalibratedMargin(state, waitAll, context).orElseThrow(() ->
                new IllegalStateException("Validated all-WAIT hybrid incumbent could not be simulated"));
    }

    private boolean canReplaceArrivalIncumbent(
            SearchState state,
            ArrivalEvaluatedPlan candidate,
            ArrivalEvaluatedPlan incumbent) {
        if (!candidate.evaluation().betterThan(incumbent.evaluation())) {
            return false;
        }
        if (state.refuelSchedule.isEmpty()) {
            return true;
        }
        return candidate.evaluation().base().teamBrandCount() > incumbent.evaluation().base().teamBrandCount()
                || (candidate.evaluation().base().teamBrandCount() == incumbent.evaluation().base().teamBrandCount()
                        && candidate.evaluation().base().udonTotal() > incumbent.evaluation().base().udonTotal());
    }

    private boolean canReplaceRiskAdjustedIncumbent(
            RiskAdjustedEvaluatedPlan candidate,
            RiskAdjustedEvaluatedPlan incumbent) {
        return candidate.evaluation().betterThan(incumbent.evaluation());
    }

    /**
     * M13.1-only fuel-only guard. A REFUEL-root challenger cannot win when every objective key
     * before final PATROL fuel is identical; otherwise the ordinary sixteen-key comparator decides.
     */
    private boolean canReplaceHarvestHorizonIncumbent(
            SearchState state,
            HarvestHorizonAwareEvaluatedPlan candidate,
            HarvestHorizonAwareEvaluatedPlan incumbent) {
        HarvestHorizonAwarePlanEvaluation next = candidate.evaluation();
        HarvestHorizonAwarePlanEvaluation current = incumbent.evaluation();
        if (!next.betterThan(current)) {
            return false;
        }
        if (state.refuelSchedule.isEmpty()) {
            return true;
        }
        SemiCommitmentAwarePlanEvaluation a = next.semiCommitment();
        SemiCommitmentAwarePlanEvaluation b = current.semiCommitment();
        return a.semiCommitmentRealizableBrandCount() != b.semiCommitmentRealizableBrandCount()
                || a.semiCommitmentRealizableCollections() != b.semiCommitmentRealizableCollections()
                || TeamNextDayHarvestCapacity.compareStructural(
                        next.nextDayHarvestCapacity(), current.nextDayHarvestCapacity()) != 0
                || a.adjustedCollectionScore().value() != b.adjustedCollectionScore().value()
                || a.base().udonTotal() != b.base().udonTotal()
                || a.base().teamBrandCount() != b.base().teamBrandCount()
                || a.hardClaimedFirstCollections() != b.hardClaimedFirstCollections()
                || a.semiClaimedFirstCollections() != b.semiClaimedFirstCollections()
                || a.directIntentBeforeCollections() != b.directIntentBeforeCollections()
                || a.tieCollections() != b.tieCollections()
                || a.followOnIntentBeforeCollections() != b.followOnIntentBeforeCollections();
    }

    private ContentionAttribution finalContentionAttribution(
            DayState state, TeamPlan plan, SearchContext context) {
        DaySimulationResult simulation = simulator.simulate(state, plan);
        return ContentionAttribution.fromSimulation(state, simulation, context::contentionAt);
    }

    private Object optionalDistance(java.util.OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : "UNAVAILABLE";
    }

    private static boolean isArrivalPolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_ARRIVAL_CONTENTION
                || policy == AnytimeSearchPolicy.ANYTIME_WEIGHTED_ARRIVAL_CONTENTION
                || policy == AnytimeSearchPolicy.ANYTIME_RISK_ADJUSTED;
    }

    private boolean isRiskAdjustedPolicy() {
        return policy == AnytimeSearchPolicy.ANYTIME_RISK_ADJUSTED;
    }

    private static boolean isIntentAwarePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_INTENT_AWARE
                || policy == AnytimeSearchPolicy.ANYTIME_DIVERSE_INTENT_AWARE
                || policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_INTENT_AWARE;
    }

    private boolean isIntentAwarePolicy() {
        return isIntentAwarePolicy(policy);
    }

    /**
     * True only for the M11 mode. The complete-plan objective is shared with
     * {@code ANYTIME_INTENT_AWARE}; this flag gates search mechanics and diagnostics alone.
     */
    private boolean isDiverseIntentPolicy() {
        return policy == AnytimeSearchPolicy.ANYTIME_DIVERSE_INTENT_AWARE;
    }

    /** True only for the M12 mode; it never takes the M10 or M11 evaluation branches. */
    private static boolean isCommitmentAwarePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_COMMITMENT_AWARE;
    }

    private boolean isCommitmentAwarePolicy() {
        return isCommitmentAwarePolicy(policy);
    }

    /**
     * True only for the M12.1 mode; it never takes the M10, M11 or M12 evaluation branches.
     *
     * <p>M12 stays selectable unchanged beside it, which is what makes the A/B/C comparison on one
     * fixture meaningful.</p>
     */
    private static boolean isSemiCommitmentAwarePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE;
    }

    private boolean isSemiCommitmentAwarePolicy() {
        return isSemiCommitmentAwarePolicy(policy);
    }

    private static boolean isHorizonAwarePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_SEMI_COMMITMENT_HORIZON_AWARE;
    }

    private boolean isHorizonAwarePolicy() {
        return isHorizonAwarePolicy(policy);
    }

    private static boolean isHarvestHorizonAwarePolicy(AnytimeSearchPolicy policy) {
        return policy
                == AnytimeSearchPolicy.ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE;
    }

    private static boolean isRelativeMarginPolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE;
    }

    private boolean isRelativeMarginPolicy() {
        return isRelativeMarginPolicy(policy);
    }

    /** M15 only: the full-day replacement-aware opponent objective. M14 stays exactly as shipped. */
    private static boolean isReplacementAwarePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN;
    }

    private boolean isReplacementAwarePolicy() {
        return isReplacementAwarePolicy(policy);
    }

    /**
     * M16 only: the coupled competitive objective resolved on ONE shared chronological stock timeline.
     * M15 and every earlier mode stay exactly as shipped.
     */
    private static boolean isCoupledCompetitivePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_COUPLED_COMPETITIVE_MARGIN;
    }

    private boolean isCoupledCompetitivePolicy() {
        return isCoupledCompetitivePolicy(policy);
    }

    private static boolean isHybridCalibratedMarginPolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN;
    }

    private boolean isHybridCalibratedMarginPolicy() {
        return isHybridCalibratedMarginPolicy(policy);
    }

    private static boolean isM17DiverseCandidatePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES;
    }

    private boolean isM17DiverseCandidatePolicy() {
        return isM17DiverseCandidatePolicy(policy);
    }

    private static boolean isM18TeamAllocatedPolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID;
    }

    private boolean isM18TeamAllocatedPolicy() {
        return isM18TeamAllocatedPolicy(policy);
    }

    private static boolean isM19CapacityCompetitivePolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE;
    }

    private boolean isM19CapacityCompetitivePolicy() {
        return isM19CapacityCompetitivePolicy(policy);
    }

    private static boolean isHybridEvaluatorPolicy(AnytimeSearchPolicy policy) {
        return isHybridCalibratedMarginPolicy(policy) || isM17DiverseCandidatePolicy(policy)
                || isM18TeamAllocatedPolicy(policy) || isM19CapacityCompetitivePolicy(policy);
    }

    private boolean isHybridEvaluatorPolicy() {
        return isHybridEvaluatorPolicy(policy);
    }

    private static boolean usesHarvestHorizon(AnytimeSearchPolicy policy) {
        return isHarvestHorizonAwarePolicy(policy) || isRelativeMarginPolicy(policy)
                || isReplacementAwarePolicy(policy) || isCoupledCompetitivePolicy(policy)
                || isHybridEvaluatorPolicy(policy);
    }

    private boolean isHarvestHorizonAwarePolicy() {
        return isHarvestHorizonAwarePolicy(policy);
    }

    private static boolean isAnyHorizonAwarePolicy(AnytimeSearchPolicy policy) {
        return isHorizonAwarePolicy(policy) || isHarvestHorizonAwarePolicy(policy)
                || isRelativeMarginPolicy(policy) || isReplacementAwarePolicy(policy)
                || isCoupledCompetitivePolicy(policy) || isHybridEvaluatorPolicy(policy);
    }

    /** The M10 opponent intent forecast is the shared input of the M10, M12 and M12.1 semantics. */
    private static boolean usesOpponentIntentForecast(AnytimeSearchPolicy policy) {
        return isIntentAwarePolicy(policy) || isCommitmentAwarePolicy(policy)
                || isSemiCommitmentAwarePolicy(policy) || isAnyHorizonAwarePolicy(policy);
    }

    /**
     * True only for the M11 strategy-depth correction. It reuses the M11 candidate portfolio and
     * the unchanged M10 objective, and differs solely in how the same expansion budget is
     * scheduled across opening strategies.
     */
    private boolean isStratifiedIntentPolicy() {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_INTENT_AWARE;
    }

    /** Every mode running the M11 stratified search mechanics, M12 and M12.1 included. */
    private static boolean isStratifiedPolicy(AnytimeSearchPolicy policy) {
        return policy == AnytimeSearchPolicy.ANYTIME_STRATIFIED_INTENT_AWARE
                || isCommitmentAwarePolicy(policy) || isSemiCommitmentAwarePolicy(policy)
                || isAnyHorizonAwarePolicy(policy) || isHybridEvaluatorPolicy(policy);
    }

    /** True when the M11 candidate portfolio selector replaces plain top-K candidate pruning. */
    private boolean isCandidatePortfolioPolicy() {
        return isDiverseIntentPolicy() || isStratifiedPolicy(policy);
    }

    private static ContentionFrontierMetrics frontierMetrics(SearchState state) {
        return new ContentionFrontierMetrics(
                state.teamBrands.size(),
                state.safeProjectedCollections,
                state.projectedCollections,
                state.optimisticHarvestPotential(),
                state.remainingUsefulSteps(),
                state.remainingFuel(),
                state.travelSteps,
                state.depth,
                state.sequence);
    }

    private boolean canReplaceIncumbent(
            SearchState state,
            EvaluatedPlan candidate,
            EvaluatedPlan incumbent) {
        if (!candidate.evaluation.betterThan(incumbent.evaluation)) {
            return false;
        }
        if (state.refuelSchedule.isEmpty()) {
            return true;
        }
        return candidate.evaluation.teamBrandCount() > incumbent.evaluation.teamBrandCount()
                || candidate.evaluation.teamBrandCount() == incumbent.evaluation.teamBrandCount()
                        && candidate.evaluation.udonTotal() > incumbent.evaluation.udonTotal();
    }

    private EvaluatedPlan initialIncumbent(DayState state, MutableStats stats) {
        TeamPlan m7Plan;
        try {
            m7Plan = policy == AnytimeSearchPolicy.CONTENTION
                    ? contentionFallback.plan(state)
                    : teamCoordinator.plan(state);
        } catch (RuntimeException exception) {
            m7Plan = SafePlanFactory.waitAll(state);
        }
        stats.completedPlans++;
        Optional<EvaluatedPlan> evaluated = evaluate(state, m7Plan);
        if (evaluated.isPresent()) {
            return evaluated.orElseThrow();
        }
        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        stats.completedPlans++;
        Optional<EvaluatedPlan> safe = evaluate(state, waitAll);
        if (safe.isEmpty()) {
            throw new IllegalStateException("Validated all-WAIT incumbent could not be simulated");
        }
        return safe.orElseThrow();
    }

    private static ArrivalContentionFrontierMetrics arrivalFrontierMetrics(SearchState state) {
        return new ArrivalContentionFrontierMetrics(
                state.teamBrands.size(),
                state.arrivalSafeProjectedCollections,
                state.arrivalTiedProjectedCollections,
                state.arrivalAtRiskProjectedCollections,
                state.safeProjectedCollections,
                state.projectedCollections,
                state.optimisticHarvestPotential(),
                state.remainingUsefulSteps(),
                state.remainingFuel(),
                state.travelSteps,
                state.depth,
                state.sequence);
    }

    private static RiskAdjustedFrontierMetrics riskAdjustedFrontierMetrics(SearchState state) {
        return new RiskAdjustedFrontierMetrics(
                state.teamBrands.size(),
                state.adjustedCollectionScore,
                state.projectedCollections,
                state.arrivalAtRiskProjectedCollections,
                state.arrivalSafeProjectedCollections - state.arrivalUnobservedProjectedCollections,
                state.stronglyContestedProjectedCollections,
                state.optimisticHarvestPotential(),
                state.remainingUsefulSteps(),
                state.remainingFuel(),
                state.travelSteps,
                state.depth,
                state.sequence);
    }

    private static IntentAwareFrontierMetrics intentAwareFrontierMetrics(SearchState state) {
        return new IntentAwareFrontierMetrics(
                state.forecastRealizableTeamBrands.size(),
                state.adjustedCollectionScore,
                state.intentForecastRealizableCollections,
                state.projectedCollections,
                state.teamBrands.size(),
                state.intentLikelyClaimedFirstCollections,
                state.intentTieCollections,
                state.optimisticHarvestPotential(),
                state.remainingUsefulSteps(),
                state.remainingFuel(),
                state.travelSteps,
                state.depth,
                state.sequence);
    }

    private static CommitmentAwareFrontierMetrics commitmentAwareFrontierMetrics(SearchState state) {
        CommitmentBranchMetrics commitment = state.commitment;
        return new CommitmentAwareFrontierMetrics(
                commitment.realizableTeamBrands().size(),
                commitment.adjustedScore(),
                commitment.realizableCollections(),
                state.projectedCollections,
                state.teamBrands.size(),
                commitment.hardClaimedFirstCollections(),
                commitment.directIntentBeforeCollections(),
                commitment.tieCollections(),
                commitment.followOnIntentBeforeCollections(),
                state.optimisticHarvestPotential(),
                state.remainingUsefulSteps(),
                state.remainingFuel(),
                state.travelSteps,
                state.depth,
                state.sequence);
    }

    private static SemiCommitmentAwareFrontierMetrics semiCommitmentAwareFrontierMetrics(
            SearchState state) {
        SemiCommitmentBranchMetrics semi = state.semiCommitment;
        return new SemiCommitmentAwareFrontierMetrics(
                semi.realizableTeamBrands().size(),
                semi.adjustedScore(),
                semi.realizableCollections(),
                state.projectedCollections,
                state.teamBrands.size(),
                semi.hardClaimedFirstCollections(),
                semi.semiClaimedFirstCollections(),
                semi.directIntentBeforeCollections(),
                semi.tieCollections(),
                semi.followOnIntentBeforeCollections(),
                state.optimisticHarvestPotential(),
                state.remainingUsefulSteps(),
                state.remainingFuel(),
                state.travelSteps,
                state.depth,
                state.sequence);
    }

    private List<SearchState> roots(SearchContext context) {
        List<SearchState> result = new ArrayList<>();
        result.add(SearchState.root(context, Optional.empty(), context.nextSequence()));
        List<RefuelSchedule> schedules = refuelSchedules(context.state);
        for (RefuelSchedule schedule : schedules) {
            result.add(SearchState.root(
                    context, Optional.of(schedule), context.nextSequence()));
        }
        return result;
    }

    private List<RefuelSchedule> refuelSchedules(DayState state) {
        List<RefuelSchedule> schedules = new ArrayList<>();
        int capacity = state.matchData().patrolFuelCapacity().value();
        for (AgentState refuel : state.agents()) {
            if (refuel.kind() != AgentKind.REFUEL) {
                continue;
            }
            for (AgentState patrol : state.agents()) {
                if (patrol.kind() != AgentKind.PATROL) {
                    continue;
                }
                int fuel = ((FiniteFuel) patrol.fuel()).amount();
                if (fuel >= capacity) {
                    continue;
                }
                refuelRouteFinder.find(state, refuel, patrol.position()).ifPresent(route -> {
                    int arrivalStep = Math.max(1, route.stepsUsed());
                    if (arrivalStep < state.stepBudget()) {
                        schedules.add(new RefuelSchedule(
                                refuel.id(), patrol.id(), route, arrivalStep, fuel));
                    }
                });
            }
        }
        schedules.sort(REFUEL_ROOT_PREFERENCE);
        return List.copyOf(schedules);
    }

    private List<TeamTargetCandidate> candidates(SearchContext context, SearchState searchState) {
        List<TeamTargetCandidate> candidates = new ArrayList<>();
        for (SearchPatrol patrol : searchState.patrols.values()) {
            if (patrol.remainingSteps == 0) {
                continue;
            }
            AgentState projectedAgent = AgentState.patrol(
                    patrol.id, patrol.position, patrol.remainingFuel);
            for (UdonSpot spot : context.orderedSpots) {
                if (searchState.stock.getOrDefault(spot.position(), 0) <= 0
                        || patrol.visitedSpots.contains(spot.position())) {
                    continue;
                }
                PatrolRouteKey key = new PatrolRouteKey(
                        patrol.id, patrol.position, patrol.remainingFuel, spot.position());
                Optional<Route> possibleRoute = context.routeCache.computeIfAbsent(
                        key,
                        ignored -> patrolRouteFinder.find(
                                context.state, projectedAgent, spot.position()));
                if (possibleRoute.isEmpty()) {
                    continue;
                }
                Route route = possibleRoute.orElseThrow();
                if (route.stepsUsed() > patrol.remainingSteps
                        || route.fuelUsed() > patrol.remainingFuel) {
                    continue;
                }
                RouteProjection projection = searchState.projectedCollectionsOn(
                        context.state, context.spotsByPosition, route, patrol);
                if (projection.collectionGain == 0) {
                    continue;
                }
                boolean patrolNewBrand = policy != AnytimeSearchPolicy.ORIGINAL
                        ? projection.newBrandForPatrol
                        : !patrol.brands.contains(spot.brand());
                boolean teamNewBrand = policy != AnytimeSearchPolicy.ORIGINAL
                        ? projection.newBrandForTeam
                        : !searchState.teamBrands.contains(spot.brand());
                TeamTargetCandidate candidate = new TeamTargetCandidate(
                        patrol.id,
                        spot.position(),
                        spot.brand(),
                        route,
                        route.stepsUsed(),
                        route.fuelUsed(),
                        patrolNewBrand,
                        teamNewBrand,
                        projection.collectionGain,
                        patrol.remainingFuel - route.fuelUsed());
                candidates.add(candidate);
                if (policy == AnytimeSearchPolicy.CONTENTION) {
                    context.candidateContention.put(candidate, context.contentionAnalyzer.analyzeRoute(
                            context.state,
                            route,
                            searchState.stock,
                            patrol.visitedSpots,
                            context.spotsByPosition,
                            context::contentionAt));
                } else if (isArrivalPolicy(policy)) {
                    int initialArrivalStep = context.state.stepBudget() - patrol.remainingSteps;
                    RouteArrivalContentionMetrics arrivalMetrics = context.contentionAnalyzer.analyzeRouteArrival(
                            context.state,
                            route,
                            initialArrivalStep,
                            searchState.stock,
                            patrol.visitedSpots,
                            context.spotsByPosition,
                            context::contentionAt,
                            pos -> context.arrivalLowerBounds.getOrDefault(pos, OptionalInt.empty()));
                    context.candidateArrivalContention.put(candidate, arrivalMetrics);
                    context.candidateContention.put(candidate, new RouteContentionMetrics(
                            arrivalMetrics.projectedCollectionGain(),
                            arrivalMetrics.staticSafeCollections(),
                            arrivalMetrics.staticTiedCollections(),
                            arrivalMetrics.staticContestedCollections(),
                            arrivalMetrics.stronglyStaticContestedCollections()));
                } else if (isIntentAwarePolicy()) {
                    int initialArrivalStep = context.state.stepBudget() - patrol.remainingSteps;
                    context.candidateIntent.put(candidate, context.intentEvaluator.evaluateRoute(
                            context.state,
                            context.spotsByPosition,
                            route,
                            initialArrivalStep,
                            searchState.stock,
                            patrol.visitedSpots,
                            searchState.forecastRealizableTeamBrands,
                            context.intentForecast,
                            intentAdjustmentWeights));
                } else if (isSemiCommitmentAwarePolicy() || isRelativeMarginPolicy()
                        || isReplacementAwarePolicy() || isCoupledCompetitivePolicy()
                        || isHybridEvaluatorPolicy()) {
                    int initialArrivalStep = context.state.stepBudget() - patrol.remainingSteps;
                    context.candidateSemiCommitment.put(candidate,
                            context.semiCommitmentEvaluator.evaluateRoute(
                                    context.state, context.spotsByPosition, route, initialArrivalStep,
                                    searchState.stock, patrol.visitedSpots,
                                    searchState.semiCommitment.realizableTeamBrands(),
                                    context.semiCommitmentForecast.commitment(),
                                    semiCommitmentAdjustmentWeights));
                    if (isRelativeMarginPolicy()) {
                        context.candidateOpponentResidual.put(candidate,
                                context.opponentDenialEvaluator.evaluateRoute(
                                        context.state, context.opponentClaimBaseline,
                                        context.commitmentForecast, context.semiCommitmentEvaluator,
                                        semiCommitmentAdjustmentWeights, context.spotsByPosition,
                                        route, initialArrivalStep, searchState.stock,
                                        patrol.visitedSpots, patrol.id.value()));
                    } else if (isReplacementAwarePolicy()) {
                        // M15 only: a linear walk over the fixed full-day baseline. No pathfinding,
                        // no rollout, and no opponent re-forecast happens per candidate.
                        context.candidateFullDayContest.put(candidate,
                                context.fullDayOpponentRollout.contestRoute(
                                        context.opponentFullDayBaseline,
                                        context.semiCommitmentForecast.commitment(),
                                        context.semiCommitmentEvaluator,
                                        semiCommitmentAdjustmentWeights, context.spotsByPosition,
                                        route, initialArrivalStep, searchState.stock,
                                        patrol.visitedSpots));
                    } else if (isCoupledCompetitivePolicy() || isHybridEvaluatorPolicy()) {
                        // M16 only: the same linear walk, over the fixed no-own-plan coupled
                        // baseline. Guidance only; the coupled rollout stays authoritative and no
                        // pathfinding or rollout runs per candidate.
                        context.candidateCoupledContest.put(candidate,
                                context.coupledCompetitiveRollout.contestRoute(
                                        context.coupledCompetitiveBaseline,
                                        context.semiCommitmentForecast.commitment(),
                                        context.semiCommitmentEvaluator,
                                        semiCommitmentAdjustmentWeights, context.spotsByPosition,
                                        route, initialArrivalStep, searchState.stock,
                                        patrol.visitedSpots));
                    }
                } else if (isCommitmentAwarePolicy()) {
                    int initialArrivalStep = context.state.stepBudget() - patrol.remainingSteps;
                    context.candidateCommitment.put(
                            candidate,
                            context.commitmentEvaluator.evaluateRoute(
                                    context.state,
                                    context.spotsByPosition,
                                    route,
                                    initialArrivalStep,
                                    searchState.stock,
                                    patrol.visitedSpots,
                                    searchState.commitment.realizableTeamBrands(),
                                    context.commitmentForecast,
                                    commitmentAdjustmentWeights));
                }
            }
        }
        if (policy == AnytimeSearchPolicy.ORIGINAL) {
            candidates.sort(TeamCoordinatorPlanner.targetPreference());
        }
        return List.copyOf(candidates);
    }

    /** M17 discovery portfolio: four bounded deterministic views of the same feasible candidates. */
    private List<TeamTargetCandidate> m17DiscoveryCandidates(
            SearchContext context,
            SearchState state,
            List<TeamTargetCandidate> candidates,
            M17CandidateFamily family) {
        List<TeamTargetCandidate> ordered = new ArrayList<>(candidates);
        Comparator<TeamTargetCandidate> stable = Comparator
                .comparingInt((TeamTargetCandidate candidate) -> candidate.targetPosition().value())
                .thenComparingInt(candidate -> candidate.patrolAgentId().value())
                .thenComparingInt(TeamTargetCandidate::routeSteps)
                .thenComparingInt(TeamTargetCandidate::routeFuel);
        switch (family) {
            case THROUGHPUT -> ordered.sort(Comparator
                    .comparingInt(TeamTargetCandidate::projectedCollectionGain).reversed()
                    .thenComparingInt(TeamTargetCandidate::routeSteps)
                    .thenComparingInt(TeamTargetCandidate::routeFuel)
                    .thenComparing(stable));
            case SPATIAL_SEPARATION -> ordered.sort(Comparator
                    .comparingInt((TeamTargetCandidate candidate) ->
                            m17RepeatedEarlyTargetCount(state, candidate)).thenComparing(stable));
            case ROUTE_ORDER -> {
                ordered.sort(stable);
                // The first four stable target choices are the bounded local order variants.
            }
            case CONTENTION_AVOIDANCE -> ordered.sort(Comparator
                    .comparingInt((TeamTargetCandidate candidate) ->
                            context.candidateCoupledContest.getOrDefault(
                                    candidate, CoupledCompetitiveRollout.CoupledRouteContest.empty())
                                    .contestedCollections())
                    .thenComparingInt(candidate -> context.candidateCoupledContest.getOrDefault(
                            candidate, CoupledCompetitiveRollout.CoupledRouteContest.empty())
                            .strongContestedCollections())
                    .thenComparing(stable));
        }
        return ordered.stream().limit(Math.min(config.topCandidatesPerState(), 4)).toList();
    }

    private int m17RepeatedEarlyTargetCount(SearchState state, TeamTargetCandidate candidate) {
        return (int) state.patrols.values().stream()
                .filter(patrol -> !patrol.id.equals(candidate.patrolAgentId()))
                .filter(patrol -> candidate.targetPosition().equals(patrol.firstCommittedTarget))
                .count();
    }

    private String firstTargetAssignmentSignature(DayState state, TeamPlan plan) {
        return state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value()))
                .map(agent -> agent.id().value() + ":" + firstRouteTarget(state, agent, plan.actionsFor(agent.id())))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private String routePrefixSignature(DayState state, TeamPlan plan) {
        return state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value()))
                .map(agent -> agent.id().value() + ":" + routeTargets(state, agent, plan.actionsFor(agent.id()), 3))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private String firstRouteTarget(DayState state, AgentState agent, List<AgentAction> actions) {
        List<Integer> targets = routeTargetValues(state, agent, actions, 1);
        return targets.isEmpty() ? "-" : Integer.toString(targets.get(0));
    }

    private String routeTargets(DayState state, AgentState agent, List<AgentAction> actions, int limit) {
        return routeTargetValues(state, agent, actions, limit).toString();
    }

    private List<Integer> routeTargetValues(
            DayState state, AgentState agent, List<AgentAction> actions, int limit) {
        if (actions == null) {
            return List.of();
        }
        Set<Position> stocked = state.spotStock().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Position cursor = agent.position();
        Set<Position> seen = new LinkedHashSet<>();
        for (AgentAction action : actions) {
            if (!(action instanceof MoveAction move)) {
                continue;
            }
            cursor = state.matchData().map().neighbor(cursor, move.direction()).orElse(cursor);
            if (stocked.contains(cursor)) {
                seen.add(cursor);
            }
            if (seen.size() >= limit) {
                break;
            }
        }
        return seen.stream().map(Position::value).toList();
    }

    private List<TeamTargetCandidate> retainCandidates(
            SearchContext context,
            List<TeamTargetCandidate> candidates,
            boolean coveragePhase) {
        int limit = Math.min(config.topCandidatesPerState(), candidates.size());
        if (policy == AnytimeSearchPolicy.ORIGINAL || limit == 0) {
            return List.copyOf(candidates.subList(0, limit));
        }

        Comparator<TeamTargetCandidate> primary = candidatePreference(context, coveragePhase);
        List<TeamTargetCandidate> ordered = candidates.stream().sorted(primary).toList();
        LinkedHashSet<TeamTargetCandidate> diverse = new LinkedHashSet<>();
        if (coveragePhase) {
            ordered.stream()
                    .filter(TeamTargetCandidate::newBrandForTeamToday)
                    .findFirst()
                    .ifPresent(diverse::add);
        }
        if (isArrivalPolicy(policy)) {
            candidates.stream().min(arrivalSafePreference(context, primary)).ifPresent(diverse::add);
        } else if (policy == AnytimeSearchPolicy.CONTENTION) {
            candidates.stream().min(lowContentionPreference(context, primary)).ifPresent(diverse::add);
        }
        candidates.stream().min(gainPreference(primary)).ifPresent(diverse::add);
        candidates.stream().min(densityPreference(primary)).ifPresent(diverse::add);
        candidates.stream().min(lowStepPreference(primary)).ifPresent(diverse::add);
        for (TeamTargetCandidate candidate : ordered) {
            if (diverse.size() >= limit) {
                break;
            }
            diverse.add(candidate);
        }
        return diverse.stream().limit(limit).toList();
    }

    private Comparator<TeamTargetCandidate> candidatePreference(
            SearchContext context, boolean coveragePhase) {
        if (isCoupledCompetitivePolicy() || isHybridEvaluatorPolicy()) {
            Comparator<CoupledCompetitiveCandidateMetrics> preference = coveragePhase
                    ? CoupledCompetitiveCandidateMetrics.coveragePreference()
                    : CoupledCompetitiveCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> coupledCompetitiveCandidateMetrics(context, candidate), preference);
        }
        if (isReplacementAwarePolicy()) {
            Comparator<ReplacementAwareCandidateMetrics> preference = coveragePhase
                    ? ReplacementAwareCandidateMetrics.coveragePreference()
                    : ReplacementAwareCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> replacementAwareCandidateMetrics(context, candidate), preference);
        }
        if (isRelativeMarginPolicy()) {
            Comparator<RelativeMarginCandidateMetrics> preference = coveragePhase
                    ? RelativeMarginCandidateMetrics.coveragePreference()
                    : RelativeMarginCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> relativeMarginCandidateMetrics(context, candidate), preference);
        }
        if (isSemiCommitmentAwarePolicy()) {
            Comparator<SemiCommitmentAwareCandidateMetrics> preference = coveragePhase
                    ? SemiCommitmentAwareCandidateMetrics.coveragePreference()
                    : SemiCommitmentAwareCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> semiCommitmentAwareCandidateMetrics(context, candidate),
                    preference);
        }
        if (isCommitmentAwarePolicy()) {
            Comparator<CommitmentAwareCandidateMetrics> preference = coveragePhase
                    ? CommitmentAwareCandidateMetrics.coveragePreference()
                    : CommitmentAwareCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> commitmentAwareCandidateMetrics(context, candidate), preference);
        }
        if (isIntentAwarePolicy()) {
            Comparator<IntentAwareCandidateMetrics> preference = coveragePhase
                    ? IntentAwareCandidateMetrics.coveragePreference()
                    : IntentAwareCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> intentAwareCandidateMetrics(context, candidate), preference);
        }
        if (isRiskAdjustedPolicy()) {
            Comparator<RiskAdjustedCandidateMetrics> preference = coveragePhase
                    ? RiskAdjustedCandidateMetrics.coveragePreference()
                    : RiskAdjustedCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> riskAdjustedCandidateMetrics(context, candidate), preference);
        }
        if (isArrivalPolicy(policy)) {
            Comparator<ArrivalContentionCandidateMetrics> preference = coveragePhase
                    ? ArrivalContentionCandidateMetrics.coveragePreference()
                    : ArrivalContentionCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> arrivalContentionMetrics(context, candidate), preference);
        }
        if (policy == AnytimeSearchPolicy.CONTENTION) {
            Comparator<ContentionCandidateMetrics> preference = coveragePhase
                    ? ContentionCandidateMetrics.coveragePreference()
                    : ContentionCandidateMetrics.harvestPreference();
            return Comparator.comparing(
                    candidate -> contentionMetrics(context, candidate), preference);
        }
        Comparator<HarvestCandidateMetrics> metricsPreference = coveragePhase
                ? HarvestCandidateMetrics.coveragePreference()
                : HarvestCandidateMetrics.harvestPreference();
        return Comparator.comparing(this::metrics, metricsPreference);
    }

    private Comparator<TeamTargetCandidate> gainPreference(
            Comparator<TeamTargetCandidate> deterministicFallback) {
        return Comparator.comparingInt(TeamTargetCandidate::projectedCollectionGain)
                .reversed()
                .thenComparing(deterministicFallback);
    }

    private Comparator<TeamTargetCandidate> densityPreference(
            Comparator<TeamTargetCandidate> deterministicFallback) {
        return Comparator.comparing(
                        this::metrics, HarvestCandidateMetrics.densityPreference())
                .thenComparing(deterministicFallback);
    }

    private Comparator<TeamTargetCandidate> lowStepPreference(
            Comparator<TeamTargetCandidate> deterministicFallback) {
        return Comparator.comparingInt(TeamTargetCandidate::routeSteps)
                .thenComparing(deterministicFallback);
    }

    private Comparator<TeamTargetCandidate> lowContentionPreference(
            SearchContext context,
            Comparator<TeamTargetCandidate> deterministicFallback) {
        return Comparator.comparingInt((TeamTargetCandidate candidate) ->
                        contentionFor(context, candidate).contestedProjectedCollections())
                .thenComparingInt(candidate -> contentionFor(
                        context, candidate).stronglyContestedCollections())
                .thenComparing(deterministicFallback);
    }
    private Comparator<TeamTargetCandidate> arrivalSafePreference(
            SearchContext context,
            Comparator<TeamTargetCandidate> deterministicFallback) {
        return Comparator.comparingInt((TeamTargetCandidate candidate) ->
                        arrivalContentionFor(context, candidate).arrivalAtRiskCollections())
                .thenComparing(Comparator.comparingInt((TeamTargetCandidate candidate) ->
                        arrivalContentionFor(context, candidate).arrivalSafeCollections()).reversed())
                .thenComparing(deterministicFallback);
    }

    private RouteArrivalContentionMetrics arrivalContentionFor(
            SearchContext context, TeamTargetCandidate candidate) {
        return context.candidateArrivalContention.getOrDefault(
                candidate,
                new RouteArrivalContentionMetrics(
                        candidate.projectedCollectionGain(),
                        candidate.projectedCollectionGain(), 0, 0,
                        candidate.projectedCollectionGain(), 0, 0, 0));
    }

    private ArrivalContentionCandidateMetrics arrivalContentionMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        RouteArrivalContentionMetrics contention = arrivalContentionFor(context, candidate);
        return new ArrivalContentionCandidateMetrics(
                candidate.newBrandForTeamToday(),
                candidate.projectedCollectionGain(),
                contention.arrivalSafeCollections(),
                contention.arrivalTiedCollections(),
                contention.arrivalAtRiskCollections(),
                contention.staticSafeCollections(),
                contention.staticTiedCollections(),
                contention.staticContestedCollections(),
                contention.stronglyStaticContestedCollections(),
                candidate.routeSteps(),
                candidate.routeFuel(),
                candidate.resultingFuel(),
                candidate.targetPosition(),
                candidate.patrolAgentId());
    }

    private RiskAdjustedCandidateMetrics riskAdjustedCandidateMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        RouteArrivalContentionMetrics contention = arrivalContentionFor(context, candidate);
        int adjustedScore = riskAdjustmentWeights.score(
                contention.observedArrivalSafeCollections(),
                contention.arrivalTiedCollections(),
                contention.arrivalAtRiskCollections(),
                contention.unobservedCollections());
        return new RiskAdjustedCandidateMetrics(
                candidate.newBrandForTeamToday(),
                adjustedScore,
                candidate.projectedCollectionGain(),
                contention.arrivalAtRiskCollections(),
                contention.observedArrivalSafeCollections(),
                contention.stronglyStaticContestedCollections(),
                candidate.routeSteps(),
                candidate.routeFuel(),
                candidate.resultingFuel(),
                candidate.targetPosition(),
                candidate.patrolAgentId());
    }

    private IntentAwareCandidateMetrics intentAwareCandidateMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        IntentRouteMetrics metrics = context.candidateIntent.getOrDefault(
                candidate, IntentRouteMetrics.empty());
        return new IntentAwareCandidateMetrics(
                metrics.forecastRealizableBrandGain() > 0,
                candidate.newBrandForTeamToday(),
                metrics.adjustedScore(),
                metrics.forecastRealizableCollections(),
                metrics.forecastRealizableBrandGain(),
                candidate.projectedCollectionGain(),
                metrics.likelyClaimedFirstCollections(),
                metrics.tieCollections(),
                candidate.routeSteps(),
                candidate.routeFuel(),
                candidate.resultingFuel(),
                candidate.targetPosition(),
                candidate.patrolAgentId());
    }

    private CommitmentAwareCandidateMetrics commitmentAwareCandidateMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        CommitmentRouteMetrics metrics = context.candidateCommitment.getOrDefault(
                candidate, CommitmentRouteMetrics.empty());
        return new CommitmentAwareCandidateMetrics(
                metrics.commitmentRealizableBrandGain() > 0,
                candidate.newBrandForTeamToday(),
                metrics.adjustedScore(),
                metrics.commitmentRealizableCollections(),
                metrics.commitmentRealizableBrandGain(),
                candidate.projectedCollectionGain(),
                metrics.hardClaimedFirstCollections(),
                metrics.directIntentBeforeCollections(),
                metrics.followOnIntentBeforeCollections(),
                metrics.tieCollections(),
                candidate.routeSteps(),
                candidate.routeFuel(),
                candidate.resultingFuel(),
                candidate.targetPosition(),
                candidate.patrolAgentId());
    }

    private SemiCommitmentAwareCandidateMetrics semiCommitmentAwareCandidateMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        SemiCommitmentRouteMetrics metrics = context.candidateSemiCommitment.getOrDefault(
                candidate, SemiCommitmentRouteMetrics.empty());
        return new SemiCommitmentAwareCandidateMetrics(
                metrics.semiCommitmentRealizableBrandGain() > 0,
                candidate.newBrandForTeamToday(),
                metrics.adjustedScore(),
                metrics.semiCommitmentRealizableCollections(),
                metrics.semiCommitmentRealizableBrandGain(),
                candidate.projectedCollectionGain(),
                metrics.hardClaimedFirstCollections(),
                metrics.semiClaimedFirstCollections(),
                metrics.directIntentBeforeCollections(),
                metrics.followOnIntentBeforeCollections(),
                metrics.tieCollections(),
                candidate.routeSteps(),
                candidate.routeFuel(),
                candidate.resultingFuel(),
                candidate.targetPosition(),
                candidate.patrolAgentId());
    }

    private RelativeMarginCandidateMetrics relativeMarginCandidateMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        SemiCommitmentRouteMetrics semi = context.candidateSemiCommitment.getOrDefault(
                candidate, SemiCommitmentRouteMetrics.empty());
        OpponentResidualClaimEvaluation denial = context.candidateOpponentResidual.getOrDefault(
                candidate, noDenial(context.opponentClaimBaseline));
        return new RelativeMarginCandidateMetrics(
                semi.semiCommitmentRealizableBrandGain() > 0,
                semi.semiCommitmentRealizableCollections(),
                denial.strongDeniedOpponentCollections(),
                denial.deniedFollowOnIntent(),
                semi.adjustedScore(),
                candidate.projectedCollectionGain(),
                candidate.routeSteps(), candidate.routeFuel(), candidate.resultingFuel(),
                candidate.targetPosition(), candidate.patrolAgentId());
    }

    /**
     * M15-only candidate guidance. {@code fullDayContestGain} comes from the whole-day baseline, so a
     * target late in an opponent's route is still visible to top-K. Old modes are untouched.
     */
    private ReplacementAwareCandidateMetrics replacementAwareCandidateMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        SemiCommitmentRouteMetrics semi = context.candidateSemiCommitment.getOrDefault(
                candidate, SemiCommitmentRouteMetrics.empty());
        FullDayOpponentHarvestRollout.FullDayRouteContest contest =
                context.candidateFullDayContest.getOrDefault(
                        candidate, FullDayOpponentHarvestRollout.FullDayRouteContest.empty());
        return new ReplacementAwareCandidateMetrics(
                semi.semiCommitmentRealizableBrandGain() > 0,
                semi.semiCommitmentRealizableCollections(),
                contest.contestedCollections(),
                contest.strongContestedCollections(),
                semi.adjustedScore(),
                candidate.projectedCollectionGain(),
                candidate.routeSteps(), candidate.routeFuel(), candidate.resultingFuel(),
                candidate.targetPosition(), candidate.patrolAgentId());
    }

    /**
     * M16-only candidate guidance. {@code coupledContestGain} comes from the whole-day coupled
     * baseline, so a target late in an opponent's adversarial route is still visible to top-K. Old
     * modes are untouched.
     */
    private CoupledCompetitiveCandidateMetrics coupledCompetitiveCandidateMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        SemiCommitmentRouteMetrics semi = context.candidateSemiCommitment.getOrDefault(
                candidate, SemiCommitmentRouteMetrics.empty());
        CoupledCompetitiveRollout.CoupledRouteContest contest =
                context.candidateCoupledContest.getOrDefault(
                        candidate, CoupledCompetitiveRollout.CoupledRouteContest.empty());
        return new CoupledCompetitiveCandidateMetrics(
                semi.semiCommitmentRealizableBrandGain() > 0,
                semi.semiCommitmentRealizableCollections(),
                contest.contestedCollections(),
                contest.strongContestedCollections(),
                semi.adjustedScore(),
                candidate.projectedCollectionGain(),
                candidate.routeSteps(), candidate.routeFuel(), candidate.resultingFuel(),
                candidate.targetPosition(), candidate.patrolAgentId());
    }

    private OpponentResidualClaimEvaluation noDenial(OpponentClaimBaseline baseline) {
        return new OpponentResidualClaimEvaluation(
                baseline,
                baseline.observedNowRealizable(), baseline.directIntentRealizable(),
                baseline.followOnIntentRealizable(), 0, 0, 0);
    }

    private ContentionCandidateMetrics contentionMetrics(
            SearchContext context, TeamTargetCandidate candidate) {
        RouteContentionMetrics contention = contentionFor(context, candidate);
        return new ContentionCandidateMetrics(
                candidate.newBrandForTeamToday(),
                candidate.projectedCollectionGain(),
                contention.safeProjectedCollections(),
                contention.tiedProjectedCollections(),
                contention.contestedProjectedCollections(),
                contention.stronglyContestedCollections(),
                candidate.routeSteps(),
                candidate.routeFuel(),
                candidate.resultingFuel(),
                candidate.targetPosition(),
                candidate.patrolAgentId());
    }

    private RouteContentionMetrics contentionFor(
            SearchContext context, TeamTargetCandidate candidate) {
        return context.candidateContention.getOrDefault(
                candidate,
                new RouteContentionMetrics(
                        candidate.projectedCollectionGain(),
                        candidate.projectedCollectionGain(), 0, 0, 0));
    }

    private HarvestCandidateMetrics metrics(TeamTargetCandidate candidate) {
        return new HarvestCandidateMetrics(
                candidate.projectedCollectionGain(),
                candidate.routeSteps(),
                candidate.routeFuel(),
                candidate.resultingFuel(),
                candidate.newBrandForTeamToday(),
                candidate.targetPosition(),
                candidate.patrolAgentId());
    }

    private Optional<EvaluatedPlan> evaluate(DayState state, TeamPlan plan) {
        if (!validator.validate(state, plan).valid()) {
            return Optional.empty();
        }
        DaySimulationResult result = simulator.simulate(state, plan);
        if (!(result instanceof ValidDaySimulationResult valid)) {
            return Optional.empty();
        }
        return Optional.of(new EvaluatedPlan(plan, baseEvaluation(state, plan, valid)));
    }

    private PlanEvaluation baseEvaluation(
            DayState state, TeamPlan plan, ValidDaySimulationResult valid) {
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

    private String signature(TeamPlan plan) {
        StringBuilder signature = new StringBuilder();
        for (Map.Entry<AgentId, List<AgentAction>> entry : plan.actionsByAgent().entrySet()) {
            signature.append(entry.getKey().value()).append(':');
            for (AgentAction action : entry.getValue()) {
                if (action instanceof MoveAction move) {
                    signature.append('M').append(move.direction().name());
                } else {
                    signature.append('W').append(((WaitAction) action).steps());
                }
                signature.append(',');
            }
            signature.append(';');
        }
        return signature.toString();
    }

    private void addBounded(
            SearchFrontier<SearchState> frontier,
            SearchState candidate,
            MutableStats stats,
            DiverseMutableStats diverseStats) {
        int evicted = frontier.add(candidate);
        diverseStats.frontierPeak = Math.max(diverseStats.frontierPeak, frontier.size());
        if (evicted == 0) {
            return;
        }
        stats.prunedStates += evicted;
        stats.frontierPrunedStates += evicted;
        diverseStats.statesRejectedByFrontierLimit += evicted;
    }

    private Comparator<SearchState> statePreference() {
        return switch (policy) {
            case ORIGINAL -> ORIGINAL_STATE_PREFERENCE;
            case HARVEST -> HARVEST_STATE_PREFERENCE;
            case CONTENTION -> CONTENTION_STATE_PREFERENCE;
            case ANYTIME_ARRIVAL_CONTENTION -> ARRIVAL_CONTENTION_STATE_PREFERENCE;
            case ANYTIME_WEIGHTED_ARRIVAL_CONTENTION -> ARRIVAL_CONTENTION_STATE_PREFERENCE;
            case ANYTIME_RISK_ADJUSTED -> RISK_ADJUSTED_STATE_PREFERENCE;
            case ANYTIME_INTENT_AWARE -> INTENT_AWARE_STATE_PREFERENCE;
            case ANYTIME_DIVERSE_INTENT_AWARE -> INTENT_AWARE_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_INTENT_AWARE -> INTENT_AWARE_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_COMMITMENT_AWARE -> COMMITMENT_AWARE_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE ->
                    SEMI_COMMITMENT_AWARE_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_HORIZON_AWARE ->
                    HORIZON_AWARE_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE ->
                    HORIZON_AWARE_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE -> RELATIVE_MARGIN_STATE_PREFERENCE;
            // M15 adds no new search stage and no new partial-state data: it reuses the M14 frontier
            // tuple unchanged and differs only in the complete-plan objective.
            case ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN ->
                    RELATIVE_MARGIN_STATE_PREFERENCE;
            // M16 adds no new search stage and no new partial-state data either: the same M14 frontier
            // tuple orders partial states, and only the complete-plan objective changes.
            case ANYTIME_STRATIFIED_COUPLED_COMPETITIVE_MARGIN -> RELATIVE_MARGIN_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN -> RELATIVE_MARGIN_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES -> RELATIVE_MARGIN_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID -> RELATIVE_MARGIN_STATE_PREFERENCE;
            case ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE -> RELATIVE_MARGIN_STATE_PREFERENCE;
        };
    }

    private String event(String suffix) {
        return switch (policy) {
            case ORIGINAL -> "ANYTIME_" + suffix;
            case HARVEST -> "ANYTIME_HARVEST_" + suffix;
            case CONTENTION -> "ANYTIME_CONTENTION_" + suffix;
            case ANYTIME_ARRIVAL_CONTENTION -> "ANYTIME_ARRIVAL_CONTENTION_" + suffix;
            case ANYTIME_WEIGHTED_ARRIVAL_CONTENTION ->
                    "ANYTIME_WEIGHTED_ARRIVAL_CONTENTION_" + suffix;
            case ANYTIME_RISK_ADJUSTED -> "ANYTIME_RISK_ADJUSTED_" + suffix;
            case ANYTIME_INTENT_AWARE -> "ANYTIME_INTENT_AWARE_" + suffix;
            case ANYTIME_DIVERSE_INTENT_AWARE -> "ANYTIME_DIVERSE_INTENT_" + suffix;
            case ANYTIME_STRATIFIED_INTENT_AWARE -> "ANYTIME_STRATIFIED_INTENT_" + suffix;
            case ANYTIME_STRATIFIED_COMMITMENT_AWARE -> "ANYTIME_STRATIFIED_COMMITMENT_" + suffix;
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_AWARE ->
                    "ANYTIME_STRATIFIED_SEMI_COMMITMENT_" + suffix;
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_HORIZON_AWARE ->
                    "ANYTIME_STRATIFIED_HORIZON_" + suffix;
            case ANYTIME_STRATIFIED_SEMI_COMMITMENT_HARVEST_HORIZON_AWARE ->
                    "ANYTIME_STRATIFIED_HARVEST_HORIZON_" + suffix;
            case ANYTIME_STRATIFIED_RELATIVE_MARGIN_AWARE ->
                    "ANYTIME_STRATIFIED_RELATIVE_MARGIN_" + suffix;
            case ANYTIME_STRATIFIED_REPLACEMENT_AWARE_RELATIVE_MARGIN ->
                    "ANYTIME_STRATIFIED_REPLACEMENT_MARGIN_" + suffix;
            case ANYTIME_STRATIFIED_COUPLED_COMPETITIVE_MARGIN ->
                    "ANYTIME_STRATIFIED_COUPLED_MARGIN_" + suffix;
            case ANYTIME_STRATIFIED_HYBRID_CALIBRATED_MARGIN ->
                    "ANYTIME_STRATIFIED_HYBRID_MARGIN_" + suffix;
            case ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES ->
                    "ANYTIME_STRATIFIED_HYBRID_DIVERSE_CANDIDATES_" + suffix;
            case ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID ->
                    "ANYTIME_STRATIFIED_TEAM_ALLOCATED_HYBRID_" + suffix;
            case ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE ->
                    "ANYTIME_STRATIFIED_CAPACITY_COMPETITIVE_" + suffix;
        };
    }

    private void logHarvestCapacity(DayState state, TeamNextDayHarvestCapacity capacity) {
        log("NEXT_DAY_HARVEST_CAPACITY_SUMMARY",
                "day", state.day().value(),
                "remainingFutureDays", capacity.remainingFutureDays(),
                "patrolAgents", capacity.patrols().size(),
                "nextDayStepBudget", capacity.nextDayStepBudget(),
                "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                "routeCostCacheEntries", capacity.routeCostCacheEntries(),
                "pathfindingExecutions", capacity.pathfindingExecutions());
        for (PatrolNextDayHarvestCapacity patrol : capacity.patrols()) {
            log("PATROL_NEXT_DAY_HARVEST_CAPACITY",
                    "day", state.day().value(), "agent", patrol.agentId().value(),
                    "endPosition", patrol.projectedEndPosition().value(),
                    "endFuel", patrol.projectedEndFuel(),
                    "stationaryOpportunity", patrol.stationaryOpportunityAvailable(),
                    "maxDistinctSpots", patrol.maxReachableDistinctSpots(),
                    "maxDistinctBrands", patrol.maxReachableDistinctBrands(),
                    "bestRemainingFuel", patrol.bestRemainingFuelAtMaxSpotCount());
        }
    }

    /** One bounded line for the immutable M15 full-day opponent baseline. */
    private void logOpponentFullDayBaseline(DayState state, OpponentFullDayBaseline baseline) {
        log("OPPONENT_FULL_DAY_BASELINE",
                "day", state.day().value(),
                "stepBudget", baseline.stepBudget(),
                "collectors", baseline.collectorCount(),
                "stockedSpots", baseline.stockedSpots(),
                "baselineOpponentCollections", baseline.totalCollections(),
                "observedNow", baseline.observedNowCollections(),
                "directIntent", baseline.directIntentCollections(),
                "followOnIntent", baseline.followOnIntentCollections(),
                "strongCollections", baseline.strongCollections(),
                "maxCollectorCollections", baseline.maxCollectorCollections(),
                "rolloutEvents", baseline.rolloutEvents(),
                "routeCostCacheEntries", baseline.routeCostCacheEntries(),
                "pathfindingExecutions", baseline.pathfindingExecutions());
    }

    /**
     * Optional per-collector route rows, capped by
     * {@link #MAX_OPPONENT_FULL_DAY_ROUTE_DIAGNOSTICS}. Diagnostics only: the commitment label never
     * changes the rollout itself.
     */
    private void logOpponentFullDayRoutes(DayState state, OpponentFullDayBaseline baseline) {
        int logged = 0;
        for (OpponentFullDayClaim claim : baseline.claims()) {
            if (logged >= MAX_OPPONENT_FULL_DAY_ROUTE_DIAGNOSTICS) {
                break;
            }
            logged++;
            log("OPPONENT_FULL_DAY_ROUTE",
                    "day", state.day().value(),
                    "group", claim.groupRawId(), "agent", claim.agentIndex(),
                    "kind", claim.rawKind(), "spot", claim.spot().value(),
                    "arrivalStep", claim.arrivalStep(),
                    "collectorOrdinal", claim.collectorOrdinal(),
                    "legSteps", claim.legSteps(), "legFuel", claim.legFuel(),
                    "commitment", claim.commitment());
        }
    }

    /** One bounded line for the immutable M16 no-own-plan coupled opponent baseline. */
    private void logOpponentCoupledBaseline(DayState state, CoupledCompetitiveBaseline baseline) {
        log("OPPONENT_COUPLED_BASELINE",
                "day", state.day().value(),
                "stepBudget", baseline.stepBudget(),
                "collectors", baseline.collectorCount(),
                "stockedSpots", baseline.stockedSpots(),
                "opponentBaselineCollections", baseline.totalCollections(),
                "observedNow", baseline.observedNowCollections(),
                "directIntent", baseline.directIntentCollections(),
                "followOnIntent", baseline.followOnIntentCollections(),
                "strongCollections", baseline.strongCollections(),
                "maxCollectorCollections", baseline.maxCollectorCollections(),
                "rolloutEvents", baseline.rolloutEvents(),
                "routeCostCacheEntries", baseline.routeCostCacheEntries(),
                "pathfindingExecutions", baseline.pathfindingExecutions());
    }

    /**
     * Optional per-event rows for the INCUMBENT coupled rollout only, capped by
     * {@link #MAX_COUPLED_COMPETITIVE_EVENT_DIAGNOSTICS}. Never emitted per candidate plan.
     */
    private void logCoupledCompetitiveEvents(DayState state, CoupledCompetitiveRolloutResult coupled) {
        int logged = 0;
        for (CoupledOwnEventResult result : coupled.ownEventResults()) {
            if (logged >= MAX_COUPLED_COMPETITIVE_EVENT_DIAGNOSTICS) {
                break;
            }
            logged++;
            log("COUPLED_COMPETITIVE_EVENT",
                    "day", state.day().value(),
                    "agent", result.event().agentId().value(),
                    "spot", result.event().spot().value(),
                    "arrivalStep", result.event().arrivalStep(),
                    "stableOrdinal", result.event().stableOrdinal(),
                    "brand", result.brand().value(),
                    "outcome", result.outcome(),
                    "equalStepContest", result.equalStepContest());
        }
    }

    private M18AllocationSelection evaluateM18Allocations(
            DayState state, SearchContext context, M18AllocationStats allocationStats,
            Map<String, M18AllocationCandidate> candidatesByPlan) {
        List<AgentState> patrols = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value()))
                .toList();
        List<TeamOpportunityAllocator.Opportunity> pool = state.matchData().udonSpots().stream()
                .filter(spot -> state.spotStock().getOrDefault(spot.position(), 0) > 0)
                .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                .map(spot -> TeamOpportunityAllocator.Opportunity.from(
                        spot, state.spotStock().getOrDefault(spot.position(), 0)))
                .filter(opportunity -> patrols.stream().anyMatch(patrol ->
                        m18Route(state, context, patrol, opportunity.position()).isPresent()))
                .limit(12)
                .toList();
        List<TeamOpportunityAllocator.Candidate> allocations = TeamOpportunityAllocator.generate(
                patrols, pool,
                (agent, target) -> m18Route(state, context, agent, target)
                        .map(route -> new TeamOpportunityAllocator.RouteCost(
                                route.stepsUsed(), route.fuelUsed()))
                        .orElse(null),
                ignored -> 0);
        M18AllocationSelection best = null;
        Set<String> seenPlans = new LinkedHashSet<>();
        for (TeamOpportunityAllocator.Candidate candidate : allocations) {
            allocationStats.allocationAttempts++;
            TeamPlan plan = buildM18Plan(state, context, candidate.allocation());
            if (plan == null) {
                allocationStats.invalidAllocations++;
                continue;
            }
            String planSignature = signature(plan);
            if (!seenPlans.add(planSignature)) {
                allocationStats.duplicatePlanAllocations++;
                continue;
            }
            Optional<HybridCalibratedMarginEvaluatedPlan> evaluated =
                    evaluateHybridCalibratedMargin(state, plan, context);
            if (evaluated.isEmpty()) {
                allocationStats.invalidAllocations++;
                continue;
            }
            HybridCalibratedMarginEvaluatedPlan value = evaluated.orElseThrow();
            M18AllocationCandidate logged = new M18AllocationCandidate(
                    candidate.allocation(), candidate.estimatedLoads(), plan, value);
            candidatesByPlan.put(planSignature, logged);
            allocationStats.validAllocations++;
            allocationStats.maxDistinctAssignedOpportunities = Math.max(
                    allocationStats.maxDistinctAssignedOpportunities,
                    candidate.allocation().distinctAssignedOpportunities());
            allocationStats.minDuplicateOwnedOpportunityCount = Math.min(
                    allocationStats.minDuplicateOwnedOpportunityCount,
                    candidate.allocation().duplicateOwnedOpportunityCount());
            allocationStats.maxDistinctFirstAssignments = Math.max(
                    allocationStats.maxDistinctFirstAssignments,
                    distinctFirstAssignments(candidate.allocation()));
            logM18Candidate(state, pool.size(), logged);
            if (best == null || value.evaluation().betterThan(best.evaluation().evaluation())) {
                best = new M18AllocationSelection(candidate.allocation(), candidate.estimatedLoads(), value);
            }
        }
        allocationStats.relevantPoolSize = pool.size();
        allocationStats.unassignedReachableOpportunities = Math.max(0,
                pool.size() - allocationStats.maxDistinctAssignedOpportunities);
        return best;
    }

    private M19CapacitySelection evaluateM19CapacityAllocations(
            DayState state, SearchContext context, M19CapacityStats allocationStats,
            Map<String, M19CapacityCandidate> candidatesByPlan) {
        List<AgentState> patrols = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL)
                .sorted(Comparator.comparingInt(agent -> agent.id().value()))
                .toList();
        List<CollectionOpportunityCapacity> seedCapacities = state.matchData().udonSpots().stream()
                .map(spot -> new CollectionOpportunityCapacity(
                        spot.position(), spot.brand(),
                        state.spotStock().getOrDefault(spot.position(), 0),
                        patrols.stream().map(AgentState::id).toList()))
                .filter(capacity -> capacity.availableStock() > 0)
                .limit(12)
                .toList();
        prepareM19RouteCatalog(state, context, patrols, seedCapacities);
        List<CollectionOpportunityCapacity> capacities = m19Capacities(state, context, patrols);
        Map<Position, List<Integer>> opponentArrivals = new LinkedHashMap<>();
        context.coupledCompetitiveBaseline.claims().forEach(claim -> opponentArrivals
                .computeIfAbsent(claim.spot(), ignored -> new ArrayList<>())
                .add(claim.arrivalStep()));
        allocationStats.initialPathfindingExecutions = context.m19PathfindingExecutions;
        List<CapacityAwareTeamAllocator.Candidate> allocations = CapacityAwareTeamAllocator.generate(
                patrols, capacities,
                (agent, target) -> m19Route(state, context, agent, target)
                        .map(route -> new CapacityAwareTeamAllocator.RouteCost(
                                route.stepsUsed(), route.fuelUsed()))
                        .orElse(null),
                opponentArrivals, state.stepBudget());
        allocationStats.candidateGenerationPathfindingExecutions =
                context.m19PathfindingExecutions - allocationStats.initialPathfindingExecutions;
        M19CapacitySelection best = null;
        for (CapacityAwareTeamAllocator.Candidate candidate : allocations) {
            allocationStats.claimCandidateAttempts++;
            TeamPlan plan = buildM19Plan(state, context, candidate.allocation());
            if (plan == null) {
                allocationStats.invalidPhysicalPlans++;
                continue;
            }
            String planSignature = signature(plan);
            if (candidatesByPlan.containsKey(planSignature)) {
                allocationStats.duplicatePhysicalPlansRejected++;
                continue;
            }
            allocationStats.uniquePhysicalPlans++;
            Optional<HybridCalibratedMarginEvaluatedPlan> evaluated =
                    evaluateHybridCalibratedMargin(state, plan, context);
            if (evaluated.isEmpty()) {
                allocationStats.invalidPhysicalPlans++;
                continue;
            }
            HybridCalibratedMarginEvaluatedPlan value = evaluated.orElseThrow();
            M19CapacityCandidate logged = new M19CapacityCandidate(
                    candidate.allocation(), candidate.timeline(), plan, value);
            candidatesByPlan.put(planSignature, logged);
            allocationStats.observe(candidate.allocation());
            logM19Candidate(state, logged);
            if (best == null || value.evaluation().betterThan(best.evaluation().evaluation())) {
                best = new M19CapacitySelection(candidate.allocation(), candidate.timeline(), value);
            }
        }
        allocationStats.finalPathfindingExecutions = context.m19PathfindingExecutions;
        allocationStats.relevantCapacitySpots = capacities.size();
        return best;
    }

    private List<CollectionOpportunityCapacity> m19Capacities(
            DayState state, SearchContext context, List<AgentState> patrols) {
        return state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                .map(spot -> new CollectionOpportunityCapacity(
                        spot.position(), spot.brand(),
                        state.spotStock().getOrDefault(spot.position(), 0),
                        patrols.stream()
                                .filter(patrol -> m19Route(state, context, patrol, spot.position()).isPresent())
                                .map(AgentState::id)
                                .toList()))
                .filter(capacity -> capacity.boundedClaimCapacity() > 0)
                .limit(12)
                .toList();
    }

    private void prepareM19RouteCatalog(
            DayState state, SearchContext context, List<AgentState> patrols,
            List<CollectionOpportunityCapacity> capacities) {
        if (!context.m19RouteCatalog.isEmpty() || patrols.isEmpty()) return;
        Set<Position> starts = new LinkedHashSet<>(patrols.stream()
                .map(AgentState::position).toList());
        starts.addAll(capacities.stream().map(CollectionOpportunityCapacity::spot).toList());
        AgentState representative = patrols.getFirst();
        int fullFuel = state.matchData().patrolFuelCapacity().value();
        for (Position start : starts) {
            for (CollectionOpportunityCapacity capacity : capacities) {
                Position target = capacity.spot();
                M19RouteKey key = new M19RouteKey(start, target);
                context.m19RouteCatalog.computeIfAbsent(key, ignored -> {
                    context.m19PathfindingExecutions++;
                    if (start.equals(target)) {
                        return Optional.of(new Route(start, target, List.of(), 0, 0));
                    }
                    return patrolRouteFinder.find(state,
                            AgentState.patrol(representative.id(), start, fullFuel), target);
                });
            }
        }
    }

    private Optional<Route> m19Route(
            DayState state, SearchContext context, AgentState agent, Position target) {
        Optional<Route> route = Optional.ofNullable(context.m19RouteCatalog.get(
                new M19RouteKey(agent.position(), target))).orElse(Optional.empty());
        if (route.isEmpty() || route.orElseThrow().fuelUsed()
                > ((FiniteFuel) agent.fuel()).amount()) {
            return Optional.empty();
        }
        return route;
    }

    private TeamPlan buildM19Plan(
            DayState state, SearchContext context, CapacityAwareTeamAllocation allocation) {
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) {
                actions.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
                continue;
            }
            List<Position> remaining = allocation.claimsFor(agent.id()).stream()
                    .map(CollectionClaim::spot).distinct().toList();
            List<AgentAction> routeActions = new ArrayList<>();
            Position position = agent.position();
            int fuel = ((FiniteFuel) agent.fuel()).amount();
            int usedSteps = 0;
            for (Position target : remaining) {
                AgentState current = AgentState.patrol(agent.id(), position, fuel);
                Optional<Route> possible = m19Route(state, context, current, target);
                if (possible.isPresent()
                        && usedSteps + possible.orElseThrow().stepsUsed() > state.stepBudget()) {
                    possible = Optional.empty();
                }
                if (possible.isEmpty()) break;
                Route route = possible.orElseThrow();
                routeActions.addAll(route.toMoveActions());
                usedSteps += route.stepsUsed();
                fuel -= route.fuelUsed();
                position = target;
            }
            actions.put(agent.id(), ActionPlanCompleter.complete(
                    routeActions, usedSteps, state.stepBudget()));
        }
        TeamPlan plan = new TeamPlan(actions);
        return validator.validate(state, plan).valid() ? plan : null;
    }

    private Optional<Route> m18Route(
            DayState state, SearchContext context, AgentState agent, Position target) {
        int fuel = agent.fuel() instanceof FiniteFuel finite ? finite.amount() : 0;
        PatrolRouteKey key = new PatrolRouteKey(agent.id(), agent.position(), fuel, target);
        return context.routeCache.computeIfAbsent(
                key, ignored -> patrolRouteFinder.find(state, agent, target));
    }

    private TeamPlan buildM18Plan(
            DayState state, SearchContext context, TeamOpportunityAllocation allocation) {
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        for (AgentState agent : state.agents()) {
            if (agent.kind() != AgentKind.PATROL) {
                actions.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
                continue;
            }
            List<Position> remaining = new ArrayList<>(allocation.assignedTo(agent.id()));
            List<AgentAction> routeActions = new ArrayList<>();
            Position position = agent.position();
            int fuel = ((FiniteFuel) agent.fuel()).amount();
            int usedSteps = 0;
            while (!remaining.isEmpty()) {
                AgentState current = AgentState.patrol(agent.id(), position, fuel);
                int currentUsedSteps = usedSteps;
                int currentFuel = fuel;
                M18NextTarget next = remaining.stream()
                        .map(target -> m18Route(state, context, current, target)
                                .filter(route -> currentUsedSteps + route.stepsUsed() <= state.stepBudget())
                                .filter(route -> route.fuelUsed() <= currentFuel)
                                .map(route -> new M18NextTarget(target, route))
                                .orElse(null))
                        .filter(Objects::nonNull)
                        .min(Comparator.comparingInt((M18NextTarget value) -> value.route().stepsUsed())
                                .thenComparingInt(value -> value.route().fuelUsed())
                                .thenComparingInt(value -> value.target().value()))
                        .orElse(null);
                if (next == null) {
                    break;
                }
                Route route = next.route();
                routeActions.addAll(route.toMoveActions());
                usedSteps += route.stepsUsed();
                fuel -= route.fuelUsed();
                position = next.target();
                remaining.remove(next.target());
            }
            actions.put(agent.id(), ActionPlanCompleter.complete(
                    routeActions, usedSteps, state.stepBudget()));
        }
        TeamPlan plan = new TeamPlan(actions);
        return validator.validate(state, plan).valid() ? plan : null;
    }

    private int distinctFirstAssignments(TeamOpportunityAllocation allocation) {
        return (int) allocation.assigned().values().stream()
                .filter(values -> !values.isEmpty())
                .map(values -> values.get(0)).distinct().count();
    }

    private String allocationCounts(TeamOpportunityAllocation allocation) {
        return allocation.assigned().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .map(entry -> entry.getKey().value() + "=" + entry.getValue().size())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String firstAssignedPerAgent(TeamOpportunityAllocation allocation) {
        return allocation.assigned().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .map(entry -> entry.getKey().value() + "="
                        + (entry.getValue().isEmpty() ? "NONE" : entry.getValue().get(0).value()))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String allocationLoads(Map<AgentId, Integer> loads) {
        return loads.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .map(entry -> entry.getKey().value() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private void logM18Start(
            DayState state, HybridCalibratedMarginEvaluation evaluation,
            SearchContext context, MutableStats stats) {
        log(event("START"), "day", state.day().value(),
                "hybridBaseWeight", HybridCalibratedMarginEvaluation.HYBRID_BASE_WEIGHT,
                "hybridCoupledWeight", HybridCalibratedMarginEvaluation.HYBRID_COUPLED_WEIGHT,
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "expanded", stats.expandedStates, "completedPlans", stats.completedPlans,
                "routeCostCacheEntries", context.coupledCompetitiveBaseline.routeCostCacheEntries(),
                "pathfindingExecutions", context.coupledCompetitiveBaseline.pathfindingExecutions(),
                "budget", config.maxExpandedStates(),
                "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
    }

    private void logM18Candidate(
            DayState state, int poolSize, M18AllocationCandidate candidate) {
        TeamOpportunityAllocation allocation = candidate.allocation();
        HybridCalibratedMarginEvaluation evaluation = candidate.evaluation().evaluation();
        log("M18_ALLOCATION_CANDIDATE", "day", state.day().value(),
                "seed", allocation.seed(), "refinement", allocation.refinement(),
                "allocationSignature", allocation.signature(),
                "agentAssignedCounts", allocationCounts(allocation),
                "agentEstimatedStepLoads", allocationLoads(candidate.estimatedLoads()),
                "firstAssignedPerAgent", firstAssignedPerAgent(allocation),
                "distinctAssignedOpportunities", allocation.distinctAssignedOpportunities(),
                "duplicateOwnedOpportunityCount", allocation.duplicateOwnedOpportunityCount(),
                "unassignedReachableOpportunities", Math.max(0,
                        poolSize - allocation.distinctAssignedOpportunities()),
                "distinctFirstAssignments", distinctFirstAssignments(allocation),
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "planSignature", signature(candidate.plan()));
    }

    private void logM18Done(
            DayState state, HybridCalibratedMarginEvaluation evaluation, SearchContext context,
            TeamPlan selectedPlan, AnytimeSearchStats stats, StratifiedSearchStats depth,
            M18AllocationStats allocationStats, Map<String, M18AllocationCandidate> candidates) {
        M18AllocationCandidate selected = candidates.get(signature(selectedPlan));
        String selectedOrigin = selected == null ? "BASELINE_OR_SEARCH"
                : selected.allocation().seed() + "_R" + selected.allocation().refinement();
        String selectedAllocation = selected == null ? "NONE" : selected.allocation().signature();
        String selectedCounts = selected == null ? "NONE" : allocationCounts(selected.allocation());
        String selectedLoads = selected == null ? "NONE" : allocationLoads(selected.estimatedLoads());
        log(event("DONE"), "day", state.day().value(),
                "selectedAllocationOrigin", selectedOrigin,
                "selectedAllocationSignature", selectedAllocation,
                "selectedAgentAssignedCounts", selectedCounts,
                "selectedAgentEstimatedStepLoads", selectedLoads,
                "selectedOwnSemiBrands", evaluation.ownSemiBrands(),
                "selectedOwnSemiCollections", evaluation.ownSemiCollections(),
                "selectedHybridMarginScore4", evaluation.hybridMarginScore4(),
                "allocationAttempts", allocationStats.allocationAttempts,
                "validAllocations", allocationStats.validAllocations,
                "invalidAllocations", allocationStats.invalidAllocations,
                "duplicatePlanAllocations", allocationStats.duplicatePlanAllocations,
                "expanded", stats.expandedStates(), "completedPlans", stats.completedPlans(),
                "frontierPeak", depth.frontierPeak(), "routeCostCacheEntries", context.routeCache.size(),
                "pathfindingExecutions", context.routeCache.size());
        log("M18_TEAM_ALLOCATION_SUMMARY", "day", state.day().value(),
                "allocationAttempts", allocationStats.allocationAttempts,
                "validAllocations", allocationStats.validAllocations,
                "invalidAllocations", allocationStats.invalidAllocations,
                "duplicatePlanAllocations", allocationStats.duplicatePlanAllocations,
                "minCostUnique", allocationStats.uniqueFor(TeamOpportunityAllocation.Seed.MIN_COST_OWNERSHIP, candidates),
                "balancedUnique", allocationStats.uniqueFor(TeamOpportunityAllocation.Seed.BALANCED_LOAD, candidates),
                "distinctEarlyUnique", allocationStats.uniqueFor(TeamOpportunityAllocation.Seed.DISTINCT_EARLY, candidates),
                "brandCoverageUnique", allocationStats.uniqueFor(TeamOpportunityAllocation.Seed.BRAND_COVERAGE, candidates),
                "maxDistinctAssignedOpportunities", allocationStats.maxDistinctAssignedOpportunities,
                "minDuplicateOwnedOpportunityCount", allocationStats.minDuplicateOwnedOpportunityCount,
                "unassignedReachableOpportunities", allocationStats.unassignedReachableOpportunities,
                "maxDistinctFirstAssignments", allocationStats.maxDistinctFirstAssignments,
                "selectedAllocationOrigin", selectedOrigin,
                "selectedAllocationSignature", selectedAllocation,
                "selectedAgentAssignedCounts", selectedCounts,
                "selectedAgentEstimatedStepLoads", selectedLoads,
                "selectedOwnSemiBrands", evaluation.ownSemiBrands(),
                "selectedOwnSemiCollections", evaluation.ownSemiCollections(),
                "selectedHybridMarginScore4", evaluation.hybridMarginScore4(),
                "expanded", stats.expandedStates(), "completedPlans", stats.completedPlans(),
                "frontierPeak", depth.frontierPeak(), "routeCostCacheEntries", context.routeCache.size(),
                "pathfindingExecutions", context.routeCache.size());
    }

    private void logM19Start(
            DayState state, HybridCalibratedMarginEvaluation evaluation,
            SearchContext context, MutableStats stats) {
        log(event("START"), "day", state.day().value(),
                "hybridBaseWeight", HybridCalibratedMarginEvaluation.HYBRID_BASE_WEIGHT,
                "hybridCoupledWeight", HybridCalibratedMarginEvaluation.HYBRID_COUPLED_WEIGHT,
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "opponentBaselineCollections", evaluation.opponentBaselineCollections(),
                "coupledOpponentCollections", evaluation.coupledOpponentCollections(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "budget", config.maxExpandedStates(),
                "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                "exploitationBudget", stratifiedSearchConfig.exploitationBudget(),
                "initialPathfindingExecutions", context.m19PathfindingExecutions,
                "searchPathfindingExecutions", stats.expandedStates);
    }

    private void logM19Candidate(DayState state, M19CapacityCandidate candidate) {
        CapacityAwareTeamAllocation allocation = candidate.allocation();
        HybridCalibratedMarginEvaluation evaluation = candidate.evaluation().evaluation();
        CapacityAwareTeamAllocator.Timeline timeline = candidate.timeline();
        log("M19_CAPACITY_CANDIDATE", "day", state.day().value(),
                "seed", allocation.seed(), "variant", allocation.variant(),
                "physicalPlanSignature", signature(candidate.plan()),
                "totalLogicalClaims", allocation.totalLogicalClaims(),
                "distinctClaimedSpots", allocation.distinctClaimedSpots(),
                "multiClaimSpotCount", allocation.multiClaimSpotCount(),
                "perAgentClaimCounts", capacityClaimCounts(allocation),
                "expectedOwnSuccessfulClaims", timeline.expectedOwnSuccessfulClaims(),
                "expectedOpponentClaimsBeforeOwn", timeline.expectedOpponentClaimsBeforeOwn(),
                "expectedResidualCapacitySum", timeline.expectedResidualCapacitySum(),
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "opponentBaselineCollections", evaluation.opponentBaselineCollections(),
                "coupledOpponentCollections", evaluation.coupledOpponentCollections(),
                "hybridMarginScore4", evaluation.hybridMarginScore4());
    }

    private void logM19Done(
            DayState state, HybridCalibratedMarginEvaluation evaluation, SearchContext context,
            TeamPlan selectedPlan, AnytimeSearchStats stats, StratifiedSearchStats depth,
            M19CapacityStats allocationStats, Map<String, M19CapacityCandidate> candidates) {
        M19CapacityCandidate selected = candidates.get(signature(selectedPlan));
        String selectedOrigin = selected == null ? "BASELINE_OR_SEARCH"
                : selected.allocation().seed() + "_V" + selected.allocation().variant();
        String selectedClaims = selected == null ? "NONE"
                : Integer.toString(selected.allocation().totalLogicalClaims());
        String selectedSpots = selected == null ? "NONE"
                : Integer.toString(selected.allocation().distinctClaimedSpots());
        String selectedMulti = selected == null ? "NONE"
                : Integer.toString(selected.allocation().multiClaimSpotCount());
        String selectedCounts = selected == null ? "NONE" : capacityClaimCounts(selected.allocation());
        CapacityAwareTeamAllocator.Timeline timeline = selected == null ?
                new CapacityAwareTeamAllocator.Timeline(0, 0, 0) : selected.timeline();
        log(event("DONE"), "day", state.day().value(),
                "selectedCandidateOrigin", selectedOrigin,
                "selectedTotalLogicalClaims", selectedClaims,
                "selectedDistinctClaimedSpots", selectedSpots,
                "selectedMultiClaimSpotCount", selectedMulti,
                "selectedPerAgentClaimCounts", selectedCounts,
                "selectedExpectedOwnSuccessfulClaims", timeline.expectedOwnSuccessfulClaims(),
                "selectedExpectedOpponentClaimsBeforeOwn", timeline.expectedOpponentClaimsBeforeOwn(),
                "selectedExpectedResidualCapacitySum", timeline.expectedResidualCapacitySum(),
                "selectedOwnSemiBrands", evaluation.ownSemiBrands(),
                "selectedOwnSemiCollections", evaluation.ownSemiCollections(),
                "selectedHybridMarginScore4", evaluation.hybridMarginScore4(),
                "claimCandidateAttempts", allocationStats.claimCandidateAttempts,
                "uniquePhysicalPlans", allocationStats.uniquePhysicalPlans,
                "duplicatePhysicalPlansRejected", allocationStats.duplicatePhysicalPlansRejected,
                "invalidPhysicalPlans", allocationStats.invalidPhysicalPlans,
                "capacityThroughputUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.CAPACITY_THROUGHPUT),
                "capacityBalancedUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.CAPACITY_BALANCED),
                "capacityBrandUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.CAPACITY_BRAND),
                "competitiveResidualUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.COMPETITIVE_RESIDUAL),
                "relevantCapacitySpots", allocationStats.relevantCapacitySpots,
                "initialPathfindingExecutions", allocationStats.initialPathfindingExecutions,
                "candidateGenerationPathfindingExecutions", allocationStats.candidateGenerationPathfindingExecutions,
                "finalPathfindingExecutions", allocationStats.finalPathfindingExecutions,
                "expanded", stats.expandedStates(), "completedPlans", stats.completedPlans(),
                "frontierPeak", depth.frontierPeak());
        log("M19_CAPACITY_SUMMARY", "day", state.day().value(),
                "claimCandidateAttempts", allocationStats.claimCandidateAttempts,
                "uniquePhysicalPlans", allocationStats.uniquePhysicalPlans,
                "duplicatePhysicalPlansRejected", allocationStats.duplicatePhysicalPlansRejected,
                "invalidPhysicalPlans", allocationStats.invalidPhysicalPlans,
                "capacityThroughputUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.CAPACITY_THROUGHPUT),
                "capacityBalancedUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.CAPACITY_BALANCED),
                "capacityBrandUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.CAPACITY_BRAND),
                "competitiveResidualUnique", allocationStats.uniqueFor(CapacityAwareTeamAllocation.Seed.COMPETITIVE_RESIDUAL),
                "selectedCandidateOrigin", selectedOrigin,
                "selectedTotalLogicalClaims", selectedClaims,
                "selectedDistinctClaimedSpots", selectedSpots,
                "selectedMultiClaimSpotCount", selectedMulti,
                "selectedPerAgentClaimCounts", selectedCounts,
                "selectedExpectedOwnSuccessfulClaims", timeline.expectedOwnSuccessfulClaims(),
                "selectedExpectedOpponentClaimsBeforeOwn", timeline.expectedOpponentClaimsBeforeOwn(),
                "selectedExpectedResidualCapacitySum", timeline.expectedResidualCapacitySum(),
                "selectedOwnSemiBrands", evaluation.ownSemiBrands(),
                "selectedOwnSemiCollections", evaluation.ownSemiCollections(),
                "selectedHybridMarginScore4", evaluation.hybridMarginScore4(),
                "initialPathfindingExecutions", allocationStats.initialPathfindingExecutions,
                "candidateGenerationPathfindingExecutions", allocationStats.candidateGenerationPathfindingExecutions,
                "finalPathfindingExecutions", allocationStats.finalPathfindingExecutions,
                "expanded", stats.expandedStates(), "completedPlans", stats.completedPlans(),
                "frontierPeak", depth.frontierPeak());
    }

    private String capacityClaimCounts(CapacityAwareTeamAllocation allocation) {
        return allocation.claimCounts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .map(entry -> entry.getKey().value() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    private void logM17Start(
            DayState state,
            HybridCalibratedMarginEvaluation evaluation,
            SearchContext context,
            MutableStats stats) {
        TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
        log(event("START"),
                "day", state.day().value(),
                "hybridBaseWeight", HybridCalibratedMarginEvaluation.HYBRID_BASE_WEIGHT,
                "hybridCoupledWeight", HybridCalibratedMarginEvaluation.HYBRID_COUPLED_WEIGHT,
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnBrands", evaluation.coupledOwnBrands(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                "expanded", stats.expandedStates,
                "completedPlans", stats.completedPlans,
                "routeCostCacheEntries", context.coupledCompetitiveBaseline.routeCostCacheEntries(),
                "pathfindingExecutions", context.coupledCompetitiveBaseline.pathfindingExecutions());
    }

    private void logM17DiscoveryCandidate(
            DayState state,
            M17CandidateFamily family,
            int variantOrdinal,
            TeamPlan plan,
            HybridCalibratedMarginEvaluation evaluation) {
        log("M17_DISCOVERY_CANDIDATE",
                "day", state.day().value(),
                "family", family,
                "variantOrdinal", variantOrdinal,
                "planSignature", signature(plan),
                "firstTargetAssignmentSignature", firstTargetAssignmentSignature(state, plan),
                "routePrefixSignature", routePrefixSignature(state, plan),
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnBrands", evaluation.coupledOwnBrands(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "plannedOwnOpportunityEvents", evaluation.plannedOwnOpportunityEvents());
    }

    private void logM17Improvement(
            DayState state,
            HybridCalibratedMarginEvaluation evaluation,
            HybridCalibratedMarginEvaluation previous,
            MutableStats stats,
            StratifiedSearchStats depth) {
        logHybridCalibratedMarginImprovement(state, evaluation, previous, stats, depth);
    }

    private void logM17Done(
            DayState state,
            HybridCalibratedMarginEvaluation evaluation,
            SearchContext context,
            TeamPlan selectedPlan,
            AnytimeSearchStats stats,
            StratifiedSearchStats depth,
            M17CandidateDiversityStats diversity,
            Map<String, String> origins) {
        TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
        CoupledCompetitiveRolloutResult coupled = evaluation.coupled();
        CoupledCompetitiveBaseline baseline = context.coupledCompetitiveBaseline;
        String planSignature = signature(selectedPlan);
        String selectedOrigin = origins.getOrDefault(planSignature, "BASELINE");
        log(event("DONE"),
                "day", state.day().value(),
                "selectedCandidateOrigin", selectedOrigin,
                "selectedPlanSignature", planSignature,
                "selectedFirstTargetAssignmentSignature", firstTargetAssignmentSignature(state, selectedPlan),
                "selectedRoutePrefixSignature", routePrefixSignature(state, selectedPlan),
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnBrands", evaluation.coupledOwnBrands(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "opponentBaselineCollections", evaluation.opponentBaselineCollections(),
                "coupledOpponentCollections", evaluation.coupledOpponentCollections(),
                "hybridOwnScore4", evaluation.hybridOwnScore4(),
                "hybridOpponentScore4", evaluation.hybridOpponentScore4(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "opponentCollectionsRemovedVsBaseline", evaluation.opponentCollectionsRemovedVsBaseline(),
                "ownPlannedEventsInvalidatedByOpponent", evaluation.ownPlannedEventsInvalidatedByOpponent(),
                "ownPlannedEventsExhaustedByOwnTeam", evaluation.ownPlannedEventsExhaustedByOwnTeam(),
                "opponentReplacementCollections", evaluation.opponentReplacementCollections(),
                "equalStepContests", evaluation.equalStepContests(),
                "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                "routeCostCacheEntries", baseline.routeCostCacheEntries(),
                "pathfindingExecutions", baseline.pathfindingExecutions(),
                "candidateAttempts", diversity.candidateAttempts,
                "uniqueCandidates", diversity.uniqueCandidates,
                "duplicateCandidatesRejected", diversity.duplicateCandidatesRejected,
                "invalidCandidatesRejected", diversity.invalidCandidatesRejected,
                "throughputGenerated", diversity.throughputGenerated,
                "spatialSeparationGenerated", diversity.spatialSeparationGenerated,
                "routeOrderGenerated", diversity.routeOrderGenerated,
                "contentionAvoidanceGenerated", diversity.contentionAvoidanceGenerated,
                "throughputUnique", diversity.throughputUnique,
                "spatialSeparationUnique", diversity.spatialSeparationUnique,
                "routeOrderUnique", diversity.routeOrderUnique,
                "contentionAvoidanceUnique", diversity.contentionAvoidanceUnique,
                "distinctPlanSignatures", diversity.planSignatures.size(),
                "distinctFirstTargetAssignmentSignatures", diversity.firstTargetAssignmentSignatures.size(),
                "distinctRoutePrefixSignatures", diversity.routePrefixSignatures.size(),
                "expanded", stats.expandedStates(),
                "completedPlans", stats.completedPlans(),
                "frontierPeak", depth.frontierPeak());
        log("M17_CANDIDATE_DIVERSITY_SUMMARY",
                "day", state.day().value(),
                "candidateAttempts", diversity.candidateAttempts,
                "uniqueCandidates", diversity.uniqueCandidates,
                "duplicateCandidatesRejected", diversity.duplicateCandidatesRejected,
                "invalidCandidatesRejected", diversity.invalidCandidatesRejected,
                "throughputGenerated", diversity.throughputGenerated,
                "spatialSeparationGenerated", diversity.spatialSeparationGenerated,
                "routeOrderGenerated", diversity.routeOrderGenerated,
                "contentionAvoidanceGenerated", diversity.contentionAvoidanceGenerated,
                "throughputUnique", diversity.throughputUnique,
                "spatialSeparationUnique", diversity.spatialSeparationUnique,
                "routeOrderUnique", diversity.routeOrderUnique,
                "contentionAvoidanceUnique", diversity.contentionAvoidanceUnique,
                "distinctPlanSignatures", diversity.planSignatures.size(),
                "distinctFirstTargetAssignmentSignatures", diversity.firstTargetAssignmentSignatures.size(),
                "distinctRoutePrefixSignatures", diversity.routePrefixSignatures.size(),
                "expanded", stats.expandedStates(),
                "completedPlans", stats.completedPlans(),
                "frontierPeak", depth.frontierPeak());
    }

    private void logHybridCalibratedMarginStart(DayState state,
            HybridCalibratedMarginEvaluation evaluation, SearchContext context, MutableStats stats) {
        TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
        logOpponentCoupledBaseline(state, context.coupledCompetitiveBaseline);
        if (contentionDiagnostics) {
            logCoupledCompetitiveEvents(state, evaluation.coupled());
        }
        log(event("START"), "day", state.day().value(),
                "remainingFutureDays", capacity.remainingFutureDays(),
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnBrands", evaluation.coupledOwnBrands(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "opponentBaselineCollections", evaluation.opponentBaselineCollections(),
                "coupledOpponentCollections", evaluation.coupledOpponentCollections(),
                "hybridOwnScore4", evaluation.hybridOwnScore4(),
                "hybridOpponentScore4", evaluation.hybridOpponentScore4(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "hybridBaseWeight", HybridCalibratedMarginEvaluation.HYBRID_BASE_WEIGHT,
                "hybridCoupledWeight", HybridCalibratedMarginEvaluation.HYBRID_COUPLED_WEIGHT,
                "plannedOwnOpportunityEvents", evaluation.plannedOwnOpportunityEvents(),
                "ownPlannedEventsInvalidatedByOpponent", evaluation.ownPlannedEventsInvalidatedByOpponent(),
                "ownPlannedEventsExhaustedByOwnTeam", evaluation.ownPlannedEventsExhaustedByOwnTeam(),
                "opponentCollectionsRemovedVsBaseline", evaluation.opponentCollectionsRemovedVsBaseline(),
                "opponentReplacementCollections", evaluation.opponentReplacementCollections(),
                "equalStepContests", evaluation.equalStepContests(),
                "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                "expanded", stats.expandedStates, "completedPlans", stats.completedPlans,
                "improvements", stats.incumbentImprovements,
                "budget", config.maxExpandedStates(),
                "discoveryBudget", stratifiedSearchConfig.discoveryBudget(),
                "qualificationBudget", stratifiedSearchConfig.qualificationBudget(),
                "exploitationBudget", stratifiedSearchConfig.exploitationBudget());
    }

    private void logHybridCalibratedMarginImprovement(DayState state,
            HybridCalibratedMarginEvaluation evaluation,
            HybridCalibratedMarginEvaluation previous, MutableStats stats,
            StratifiedSearchStats depth) {
        TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
        log(event("IMPROVEMENT"), "day", state.day().value(),
                "improvementCriterion", evaluation.improvementCriterion(previous),
                "remainingFutureDays", capacity.remainingFutureDays(),
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnBrands", evaluation.coupledOwnBrands(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "opponentBaselineCollections", evaluation.opponentBaselineCollections(),
                "coupledOpponentCollections", evaluation.coupledOpponentCollections(),
                "hybridOwnScore4", evaluation.hybridOwnScore4(),
                "hybridOpponentScore4", evaluation.hybridOpponentScore4(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "hybridBaseWeight", HybridCalibratedMarginEvaluation.HYBRID_BASE_WEIGHT,
                "hybridCoupledWeight", HybridCalibratedMarginEvaluation.HYBRID_COUPLED_WEIGHT,
                "plannedOwnOpportunityEvents", evaluation.plannedOwnOpportunityEvents(),
                "ownPlannedEventsInvalidatedByOpponent", evaluation.ownPlannedEventsInvalidatedByOpponent(),
                "ownPlannedEventsExhaustedByOwnTeam", evaluation.ownPlannedEventsExhaustedByOwnTeam(),
                "opponentCollectionsRemovedVsBaseline", evaluation.opponentCollectionsRemovedVsBaseline(),
                "opponentReplacementCollections", evaluation.opponentReplacementCollections(),
                "equalStepContests", evaluation.equalStepContests(),
                "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                "expanded", stats.expandedStates, "completedPlans", stats.completedPlans,
                "improvements", stats.incumbentImprovements,
                "strategiesDiscovered", depth.strategiesDiscovered(),
                "strategiesQualified", depth.strategiesQualified(),
                "discoveryExpansions", depth.discoveryExpansions(),
                "qualificationExpansions", depth.qualificationExpansions(),
                "exploitationExpansions", depth.exploitationExpansions(),
                "frontierPeak", depth.frontierPeak(), "budgetExhausted", false);
    }

    private void logHybridCalibratedMarginDone(DayState state,
            HybridCalibratedMarginEvaluation evaluation, SearchContext context,
            AnytimeSearchStats stats, StratifiedSearchStats depth) {
        TeamNextDayHarvestCapacity capacity = evaluation.nextDayHarvestCapacity();
        CoupledCompetitiveRolloutResult coupled = evaluation.coupled();
        CoupledCompetitiveBaseline baseline = context.coupledCompetitiveBaseline;
        log(event("DONE"), "day", state.day().value(),
                "remainingFutureDays", capacity.remainingFutureDays(),
                "ownSemiBrands", evaluation.ownSemiBrands(),
                "ownSemiCollections", evaluation.ownSemiCollections(),
                "coupledOwnBrands", evaluation.coupledOwnBrands(),
                "coupledOwnCollections", evaluation.coupledOwnCollections(),
                "opponentBaselineCollections", evaluation.opponentBaselineCollections(),
                "coupledOpponentCollections", evaluation.coupledOpponentCollections(),
                "hybridOwnScore4", evaluation.hybridOwnScore4(),
                "hybridOpponentScore4", evaluation.hybridOpponentScore4(),
                "hybridMarginScore4", evaluation.hybridMarginScore4(),
                "hybridBaseWeight", HybridCalibratedMarginEvaluation.HYBRID_BASE_WEIGHT,
                "hybridCoupledWeight", HybridCalibratedMarginEvaluation.HYBRID_COUPLED_WEIGHT,
                "plannedOwnOpportunityEvents", evaluation.plannedOwnOpportunityEvents(),
                "ownPlannedEventsInvalidatedByOpponent", evaluation.ownPlannedEventsInvalidatedByOpponent(),
                "ownPlannedEventsExhaustedByOwnTeam", evaluation.ownPlannedEventsExhaustedByOwnTeam(),
                "opponentCollectionsRemovedVsBaseline", evaluation.opponentCollectionsRemovedVsBaseline(),
                "opponentReplacementCollections", evaluation.opponentReplacementCollections(),
                "equalStepContests", evaluation.equalStepContests(),
                "rawUdon", evaluation.semiCommitment().base().udonTotal(),
                "finalPatrolFuel", evaluation.semiCommitment().base().remainingFuelTotal(),
                "minimumPatrolDistinctSpots", capacity.minimumPatrolDistinctSpots(),
                "minimumPatrolDistinctBrands", capacity.minimumPatrolDistinctBrands(),
                "totalPatrolDistinctSpotCapacity", capacity.totalPatrolDistinctSpotCapacity(),
                "totalPatrolDistinctBrandCapacity", capacity.totalPatrolDistinctBrandCapacity(),
                "baselineRolloutEvents", baseline.rolloutEvents(),
                "coupledRolloutEvents", coupled.rolloutEvents(),
                "expanded", stats.expandedStates(), "completedPlans", stats.completedPlans(),
                "improvements", stats.incumbentImprovements(),
                "strategiesDiscovered", depth.strategiesDiscovered(),
                "strategiesQualified", depth.strategiesQualified(),
                "discoveryExpansions", depth.discoveryExpansions(),
                "qualificationExpansions", depth.qualificationExpansions(),
                "exploitationExpansions", depth.exploitationExpansions(),
                "frontierPeak", depth.frontierPeak(), "budgetExhausted", stats.budgetExhausted());
        if (contentionDiagnostics) {
            logCoupledCompetitiveEvents(state, coupled);
            logHarvestCapacity(state, capacity);
        }
    }

    private void logHorizonReadiness(DayState state, TeamFutureReadiness readiness) {
        log("HORIZON_FUEL_SUMMARY",
                "day", state.day().value(),
                "remainingFutureDays", readiness.remainingFutureDays(),
                "patrolAgents", readiness.patrols().size(),
                "projectedTotalPatrolFuel", readiness.totalProjectedPatrolFuel(),
                "futureReadyPatrolCount", readiness.futureReadyPatrolCount(),
                "reachableOpportunitySpots", readiness.totalReachableOpportunitySpots(),
                "reachableOpportunityBrands", readiness.totalReachableOpportunityBrands(),
                "minimumPatrolReadiness", readiness.minimumPatrolReadiness(),
                "routeCostCacheEntries", readiness.routeCostCacheEntries(),
                "pathfindingExecutions", readiness.routeCostPathfindingExecutions());
        for (PatrolFutureReadiness patrol : readiness.patrols()) {
            log("PATROL_HORIZON_READINESS",
                    "day", state.day().value(),
                    "agent", patrol.agentId().value(),
                    "endPosition", patrol.projectedEndPosition().value(),
                    "endFuel", patrol.projectedEndFuel(),
                    "reachableSpots", patrol.reachableOpportunitySpotCount(),
                    "reachableBrands", patrol.reachableOpportunityBrandCount(),
                    "minimumFuelToOpportunity", patrol.minimumFuelToAnyOpportunity(),
                    "fuelSlack", patrol.fuelSlackAfterNearestOpportunity());
        }
    }

    private static final class SearchContext {

        private final DayState state;
        private final List<UdonSpot> orderedSpots;
        private final Map<Position, UdonSpot> spotsByPosition = new LinkedHashMap<>();
        private final Map<PatrolRouteKey, Optional<Route>> routeCache = new LinkedHashMap<>();
        /** M19 route catalog keyed only by physical positions; candidate generation only reads it. */
        private final Map<M19RouteKey, Optional<Route>> m19RouteCatalog = new LinkedHashMap<>();
        private int m19PathfindingExecutions;
        private final ContentionAnalyzer contentionAnalyzer = new ContentionAnalyzer();
        private final Map<Position, ContentionMetrics> contentionCache = new LinkedHashMap<>();
        private final Map<Position, OptionalInt> opponentHexLowerBounds;
        private final Map<Position, OptionalInt> opponentWeightedLowerBounds;
        private final Map<Position, OptionalInt> arrivalLowerBounds;
        private final Map<TeamTargetCandidate, RouteContentionMetrics> candidateContention =
                new LinkedHashMap<>();
        private final Map<TeamTargetCandidate, RouteArrivalContentionMetrics> candidateArrivalContention =
                new LinkedHashMap<>();
        private final Map<TeamTargetCandidate, IntentRouteMetrics> candidateIntent = new LinkedHashMap<>();
        private final Map<TeamTargetCandidate, CommitmentRouteMetrics> candidateCommitment =
                new LinkedHashMap<>();
        private final Map<TeamTargetCandidate, SemiCommitmentRouteMetrics> candidateSemiCommitment =
                new LinkedHashMap<>();
        private final Map<TeamTargetCandidate, OpponentResidualClaimEvaluation> candidateOpponentResidual =
                new LinkedHashMap<>();
        private final Map<TeamTargetCandidate, FullDayOpponentHarvestRollout.FullDayRouteContest>
                candidateFullDayContest = new LinkedHashMap<>();
        private final Map<TeamTargetCandidate, CoupledCompetitiveRollout.CoupledRouteContest>
                candidateCoupledContest = new LinkedHashMap<>();
        private final AnytimeSearchPolicy policy;
        private final RiskAdjustmentWeights riskAdjustmentWeights;
        private final OpponentIntentForecast intentForecast;
        private final IntentForecastEvaluator intentEvaluator = new IntentForecastEvaluator();
        private final IntentAdjustmentWeights intentAdjustmentWeights;
        private final OpponentCommitmentForecast commitmentForecast;
        private final CommitmentForecastEvaluator commitmentEvaluator =
                new CommitmentForecastEvaluator();
        private final CommitmentAdjustmentWeights commitmentAdjustmentWeights;
        private final SemiCommitmentForecast semiCommitmentForecast;
        private final SemiCommitmentForecastEvaluator semiCommitmentEvaluator =
                new SemiCommitmentForecastEvaluator();
        private final SemiCommitmentAdjustmentWeights semiCommitmentAdjustmentWeights;
        private final FutureReadinessCalculator futureReadinessCalculator;
        private final NextDayHarvestCapacityCalculator nextDayHarvestCapacityCalculator;
        private final OpponentDenialEvaluator opponentDenialEvaluator = new OpponentDenialEvaluator();
        private final OpponentClaimBaseline opponentClaimBaseline;
        /** M15 only: the route cache is built once here, so no challenger ever runs a Dijkstra. */
        private final FullDayOpponentHarvestRollout fullDayOpponentRollout;
        /** M15 only: the immutable whole-day opponent harvest, computed once per planning run. */
        private final OpponentFullDayBaseline opponentFullDayBaseline;
        /** M16 only: one shared route cache serving the baseline and every coupled rollout alike. */
        private final CoupledCompetitiveRollout coupledCompetitiveRollout;
        /** M16 only: the immutable no-own-plan coupled baseline, computed once per planning run. */
        private final CoupledCompetitiveBaseline coupledCompetitiveBaseline;
        private long sequence;
        private int loggedCandidateDiagnostics;

        private SearchContext(
                DayState state,
                AnytimeSearchPolicy policy,
                RiskAdjustmentWeights riskAdjustmentWeights,
                OpponentIntentConfig opponentIntentConfig,
                IntentAdjustmentWeights intentAdjustmentWeights,
                CommitmentAdjustmentWeights commitmentAdjustmentWeights,
                SemiCommitmentAdjustmentWeights semiCommitmentAdjustmentWeights) {
            this.state = state;
            this.policy = policy;
            this.riskAdjustmentWeights = riskAdjustmentWeights;
            this.intentAdjustmentWeights = intentAdjustmentWeights;
            this.commitmentAdjustmentWeights = commitmentAdjustmentWeights;
            this.semiCommitmentAdjustmentWeights = semiCommitmentAdjustmentWeights;
            this.futureReadinessCalculator = isHorizonAwarePolicy(policy)
                    ? FutureReadinessCalculator.forState(state) : null;
            this.nextDayHarvestCapacityCalculator = usesHarvestHorizon(policy)
                    ? NextDayHarvestCapacityCalculator.forState(state) : null;
            this.intentForecast = usesOpponentIntentForecast(policy)
                    ? new OpponentIntentForecaster().forecast(state, opponentIntentConfig)
                    : new OpponentIntentForecast(List.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0);
            // Cheap linear annotation over the claims the M10 forecast already accepted: no route
            // is recomputed and no shortest path is searched for commitment.
            this.commitmentForecast =
                    isCommitmentAwarePolicy(policy) || isSemiCommitmentAwarePolicy(policy)
                            || isAnyHorizonAwarePolicy(policy)
                    ? OpponentCommitmentForecast.annotate(this.intentForecast)
                    : OpponentCommitmentForecast.empty();
            // One further linear pass for the bounded per-spot aggregates. Nothing is re-forecast,
            // and the per-plan evaluation reads this same view rather than rebuilding it.
            this.semiCommitmentForecast = isSemiCommitmentAwarePolicy(policy)
                            || isAnyHorizonAwarePolicy(policy)
                    ? SemiCommitmentForecast.derive(this.commitmentForecast)
                    : SemiCommitmentForecast.empty();
            this.opponentClaimBaseline = isRelativeMarginPolicy(policy)
                    ? opponentDenialEvaluator.baseline(state, commitmentForecast)
                    : new OpponentClaimBaseline(Map.of(), 0, 0, 0, 0, state.spotStock().size());
            // M15: one bounded reverse-Pareto pass per Udon spot, then one baseline rollout. Every
            // later per-plan residual rollout only reads these cached route costs.
            this.fullDayOpponentRollout = isReplacementAwarePolicy(policy)
                    ? FullDayOpponentHarvestRollout.forState(state, opponentIntentConfig)
                    : null;
            this.opponentFullDayBaseline = fullDayOpponentRollout == null
                    ? OpponentFullDayBaseline.empty()
                    : fullDayOpponentRollout.baseline();
            // M16: exactly one bounded reverse-Pareto pass per Udon spot, then one no-own-plan
            // baseline rollout. Every later coupled rollout only reads these cached route costs, so
            // no terminal plan, no opponent reroute and no timeline event ever runs a Dijkstra. The
            // baseline is produced by the SAME adversarial selection rule the coupled rollout uses,
            // so opponentCollectionsRemovedVsBaseline measures our plan rather than a model change.
            this.coupledCompetitiveRollout = (isCoupledCompetitivePolicy(policy)
                    || isHybridEvaluatorPolicy(policy))
                    ? CoupledCompetitiveRollout.forState(state, opponentIntentConfig)
                    : null;
            this.coupledCompetitiveBaseline = coupledCompetitiveRollout == null
                    ? CoupledCompetitiveBaseline.empty()
                    : coupledCompetitiveRollout.baseline();
            this.orderedSpots = state.matchData().udonSpots().stream()
                    .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                    .toList();
            for (UdonSpot spot : orderedSpots) {
                spotsByPosition.put(spot.position(), spot);
            }
            this.opponentHexLowerBounds = contentionAnalyzer.opponentLowerBounds(state);
            if (policy == AnytimeSearchPolicy.ANYTIME_WEIGHTED_ARRIVAL_CONTENTION
                    || policy == AnytimeSearchPolicy.ANYTIME_RISK_ADJUSTED) {
                this.opponentWeightedLowerBounds =
                        new OpponentWeightedArrivalLowerBound().lowerBounds(state);
                this.arrivalLowerBounds = opponentWeightedLowerBounds;
            } else {
                this.opponentWeightedLowerBounds = Map.of();
                this.arrivalLowerBounds = opponentHexLowerBounds;
            }
        }

        private long nextSequence() {
            return sequence++;
        }

        private ContentionMetrics contentionAt(Position position) {
            return contentionCache.computeIfAbsent(
                    position, target -> contentionAnalyzer.analyze(state, target));
        }
    }

    private static final class SearchState {

        private final Map<Position, Integer> stock;
        private final Set<BrandId> teamBrands;
        private final Set<BrandId> forecastRealizableTeamBrands;
        private final Map<AgentId, SearchPatrol> patrols;
        private final Optional<RefuelSchedule> refuelSchedule;
        private final int projectedCollections;
        private final int safeProjectedCollections;
        private final int tiedProjectedCollections;
        private final int contestedProjectedCollections;
        private final int stronglyContestedProjectedCollections;
        private final int arrivalSafeProjectedCollections;
        private final int arrivalTiedProjectedCollections;
        private final int arrivalAtRiskProjectedCollections;
        private final int arrivalUnobservedProjectedCollections;
        private final int adjustedCollectionScore;
        private final int intentForecastRealizableCollections;
        private final int intentLikelyClaimedFirstCollections;
        private final int intentTieCollections;
        private final CommitmentBranchMetrics commitment;
        private final SemiCommitmentBranchMetrics semiCommitment;
        private final int travelSteps;
        private final int depth;
        private final long sequence;

        /** Cached opening-strategy bucket; computed on demand and never mutated afterwards. */
        private StrategicDiversityKey strategicDiversityKey;

        private SearchState(
                Map<Position, Integer> stock,
                Set<BrandId> teamBrands,
                Set<BrandId> forecastRealizableTeamBrands,
                Map<AgentId, SearchPatrol> patrols,
                Optional<RefuelSchedule> refuelSchedule,
                int projectedCollections,
                int safeProjectedCollections,
                int tiedProjectedCollections,
                int contestedProjectedCollections,
                int stronglyContestedProjectedCollections,
                int arrivalSafeProjectedCollections,
                int arrivalTiedProjectedCollections,
                int arrivalAtRiskProjectedCollections,
                int arrivalUnobservedProjectedCollections,
                int adjustedCollectionScore,
                int intentForecastRealizableCollections,
                int intentLikelyClaimedFirstCollections,
                int intentTieCollections,
                CommitmentBranchMetrics commitment,
                SemiCommitmentBranchMetrics semiCommitment,
                int travelSteps,
                int depth,
                long sequence) {
            this.stock = stock;
            this.teamBrands = teamBrands;
            this.forecastRealizableTeamBrands = forecastRealizableTeamBrands;
            this.patrols = patrols;
            this.refuelSchedule = refuelSchedule;
            this.projectedCollections = projectedCollections;
            this.safeProjectedCollections = safeProjectedCollections;
            this.tiedProjectedCollections = tiedProjectedCollections;
            this.contestedProjectedCollections = contestedProjectedCollections;
            this.stronglyContestedProjectedCollections = stronglyContestedProjectedCollections;
            this.arrivalSafeProjectedCollections = arrivalSafeProjectedCollections;
            this.arrivalTiedProjectedCollections = arrivalTiedProjectedCollections;
            this.arrivalAtRiskProjectedCollections = arrivalAtRiskProjectedCollections;
            this.arrivalUnobservedProjectedCollections = arrivalUnobservedProjectedCollections;
            this.adjustedCollectionScore = adjustedCollectionScore;
            this.intentForecastRealizableCollections = intentForecastRealizableCollections;
            this.intentLikelyClaimedFirstCollections = intentLikelyClaimedFirstCollections;
            this.intentTieCollections = intentTieCollections;
            this.commitment = commitment;
            this.semiCommitment = semiCommitment;
            this.travelSteps = travelSteps;
            this.depth = depth;
            this.sequence = sequence;
        }

        private static SearchState root(
                SearchContext context,
                Optional<RefuelSchedule> schedule,
                long sequence) {
            DayState state = context.state;
            Map<Position, Integer> stock = new LinkedHashMap<>(state.spotStock());
            Set<BrandId> teamBrands = new LinkedHashSet<>();
            Set<BrandId> realizableTeamBrands = new LinkedHashSet<>();
            Map<AgentId, SearchPatrol> patrols = new LinkedHashMap<>();
            int capacity = state.matchData().patrolFuelCapacity().value();
            for (AgentState agent : state.agents()) {
                if (agent.kind() != AgentKind.PATROL) {
                    continue;
                }
                boolean served = schedule.filter(value -> value.patrolId.equals(agent.id())).isPresent();
                int wait = served ? schedule.orElseThrow().arrivalStep : 0;
                int fuel = served ? capacity : ((FiniteFuel) agent.fuel()).amount();
                List<AgentAction> actions = wait > 0
                        ? List.of(new WaitAction(wait))
                        : List.of();
                patrols.put(agent.id(), new SearchPatrol(
                        agent.id(),
                        agent.position(),
                        fuel,
                        state.stepBudget() - wait,
                        new LinkedHashSet<>(),
                        new LinkedHashSet<>(),
                        new ArrayList<>(actions)));
            }
            SearchState root = new SearchState(
                    stock,
                    teamBrands,
                    realizableTeamBrands,
                    patrols,
                    schedule,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    CommitmentBranchMetrics.empty(),
                    SemiCommitmentBranchMetrics.empty(),
                    schedule.map(value -> value.route.stepsUsed()).orElse(0),
                    0,
                    sequence);
            int collections = 0;
            int arrivalSafe = 0;
            int arrivalTied = 0;
            int arrivalAtRisk = 0;
            int arrivalUnobserved = 0;
            int adjustedScore = 0;
            int intentRealizable = 0;
            int intentClaimedFirst = 0;
            int intentTies = 0;
            CommitmentBranchMetrics commitment = CommitmentBranchMetrics.empty();
            SemiCommitmentBranchMetrics semiCommitment = SemiCommitmentBranchMetrics.empty();
            Map<Position, UdonSpot> spots = context.spotsByPosition;
            for (SearchPatrol patrol : patrols.values()) {
                if (root.projectCollection(patrol.position, patrol, spots)) {
                    collections++;
                    OptionalInt oppBound = context.arrivalLowerBounds.getOrDefault(
                            patrol.position, OptionalInt.empty());
                    ArrivalContentionMetrics arrivalMetrics = context.contentionAnalyzer.analyzeArrival(patrol.position, 0, oppBound);
                    switch (arrivalMetrics.classification()) {
                        case ARRIVAL_SAFE -> arrivalSafe++;
                        case ARRIVAL_TIED -> arrivalTied++;
                        case ARRIVAL_AT_RISK -> arrivalAtRisk++;
                        case UNOBSERVED -> {
                            arrivalSafe++;
                            arrivalUnobserved++;
                        }
                    }
                    if (context.policy == AnytimeSearchPolicy.ANYTIME_RISK_ADJUSTED) {
                        adjustedScore = Math.addExact(
                                adjustedScore,
                                context.riskAdjustmentWeights.weightFor(arrivalMetrics.classification()));
                    } else if (isSemiCommitmentAwarePolicy(context.policy)
                            || isHorizonAwarePolicy(context.policy)) {
                        SemiCommitmentCollectionAssessment assessment =
                                context.semiCommitmentEvaluator.assessCollection(
                                        state.spotStock(), patrol.position, 0,
                                        context.semiCommitmentForecast.commitment(),
                                        context.semiCommitmentAdjustmentWeights);
                        Set<BrandId> gainedBrands = new LinkedHashSet<>();
                        if (assessment.semiCommitmentRealizable()) {
                            UdonSpot collected = spots.get(patrol.position);
                            if (collected != null
                                    && !semiCommitment.realizableTeamBrands()
                                            .contains(collected.brand())) {
                                gainedBrands.add(collected.brand());
                            }
                        }
                        semiCommitment = semiCommitment.plus(new SemiCommitmentRouteMetrics(
                                1,
                                assessment.semiCommitmentValueUnits(),
                                assessment.semiCommitmentRealizable() ? 1 : 0,
                                assessment.classification()
                                        == SemiCommitmentCollectionClassification.HARD_CLAIMED_FIRST
                                        ? 1 : 0,
                                assessment.classification()
                                        == SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST
                                        ? 1 : 0,
                                assessment.classification()
                                        == SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE
                                        ? 1 : 0,
                                assessment.classification()
                                        == SemiCommitmentCollectionClassification
                                                .FOLLOW_ON_INTENT_BEFORE ? 1 : 0,
                                assessment.classification()
                                        == SemiCommitmentCollectionClassification.CONTESTED_TIE
                                        ? 1 : 0,
                                assessment.classification()
                                        == SemiCommitmentCollectionClassification.UNFORECASTED
                                        ? 1 : 0,
                                gainedBrands,
                                gainedBrands.size()));
                    } else if (isCommitmentAwarePolicy(context.policy)) {
                        CommitmentCollectionAssessment assessment =
                                context.commitmentEvaluator.assessCollection(
                                        state.spotStock(), patrol.position, 0,
                                        context.commitmentForecast,
                                        context.commitmentAdjustmentWeights);
                        Set<BrandId> gainedBrands = new LinkedHashSet<>();
                        if (assessment.commitmentRealizable()) {
                            UdonSpot collected = spots.get(patrol.position);
                            if (collected != null
                                    && !commitment.realizableTeamBrands().contains(collected.brand())) {
                                gainedBrands.add(collected.brand());
                            }
                        }
                        commitment = commitment.plus(new CommitmentRouteMetrics(
                                1,
                                assessment.commitmentValueUnits(),
                                assessment.commitmentRealizable() ? 1 : 0,
                                assessment.classification()
                                        == CommitmentCollectionClassification.HARD_CLAIMED_FIRST ? 1 : 0,
                                assessment.classification()
                                        == CommitmentCollectionClassification.DIRECT_INTENT_BEFORE ? 1 : 0,
                                assessment.classification()
                                        == CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE ? 1 : 0,
                                assessment.classification()
                                        == CommitmentCollectionClassification.CONTESTED_TIE ? 1 : 0,
                                assessment.classification()
                                        == CommitmentCollectionClassification.UNFORECASTED ? 1 : 0,
                                gainedBrands,
                                gainedBrands.size()));
                    } else if (isIntentAwarePolicy(context.policy)) {
                        ForecastCollectionAssessment assessment = context.intentEvaluator.assessCollection(
                                state.spotStock(), patrol.position, 0, context.intentForecast,
                                context.intentAdjustmentWeights);
                        adjustedScore = Math.addExact(adjustedScore, assessment.intentValueUnits());
                        intentRealizable += assessment.forecastRealizable() ? 1 : 0;
                        if (assessment.forecastRealizable()) {
                            UdonSpot collected = spots.get(patrol.position);
                            if (collected != null) {
                                realizableTeamBrands.add(collected.brand());
                            }
                        }
                        intentClaimedFirst += assessment.classification()
                                == IntentCollectionClassification.LIKELY_CLAIMED_FIRST ? 1 : 0;
                        intentTies += assessment.classification()
                                == IntentCollectionClassification.CONTESTED_TIE ? 1 : 0;
                    }
                }
            }
            return new SearchState(
                    stock,
                    teamBrands,
                    realizableTeamBrands,
                    patrols,
                    schedule,
                    collections,
                    0,
                    0,
                    0,
                    0,
                    arrivalSafe,
                    arrivalTied,
                    arrivalAtRisk,
                    arrivalUnobserved,
                    adjustedScore,
                    intentRealizable,
                    intentClaimedFirst,
                    intentTies,
                    commitment,
                    semiCommitment,
                    root.travelSteps,
                    root.depth,
                    root.sequence);
        }

        private SearchState child(
                DayState state,
                TeamTargetCandidate candidate,
                RouteContentionMetrics contention,
                RouteArrivalContentionMetrics arrivalContention,
                int routeAdjustedScore,
                IntentRouteMetrics intentMetrics,
                CommitmentRouteMetrics commitmentMetrics,
                SemiCommitmentRouteMetrics semiCommitmentMetrics,
                long childSequence) {
            Map<Position, Integer> childStock = new LinkedHashMap<>(stock);
            Set<BrandId> childTeamBrands = new LinkedHashSet<>(teamBrands);
            Set<BrandId> childRealizableTeamBrands = new LinkedHashSet<>(forecastRealizableTeamBrands);
            childRealizableTeamBrands.addAll(intentMetrics.forecastRealizableBrands());
            CommitmentBranchMetrics childCommitment = commitment.plus(commitmentMetrics);
            SemiCommitmentBranchMetrics childSemiCommitment =
                    semiCommitment.plus(semiCommitmentMetrics);
            Map<AgentId, SearchPatrol> childPatrols = new LinkedHashMap<>();
            for (Map.Entry<AgentId, SearchPatrol> entry : patrols.entrySet()) {
                childPatrols.put(entry.getKey(), entry.getValue().copy());
            }
            SearchState child = new SearchState(
                    childStock,
                    childTeamBrands,
                    childRealizableTeamBrands,
                    childPatrols,
                    refuelSchedule,
                    projectedCollections,
                    safeProjectedCollections,
                    tiedProjectedCollections,
                    contestedProjectedCollections,
                    stronglyContestedProjectedCollections,
                    arrivalSafeProjectedCollections,
                    arrivalTiedProjectedCollections,
                    arrivalAtRiskProjectedCollections,
                    arrivalUnobservedProjectedCollections,
                    adjustedCollectionScore,
                    intentForecastRealizableCollections,
                    intentLikelyClaimedFirstCollections,
                    intentTieCollections,
                    childCommitment,
                    childSemiCommitment,
                    travelSteps + candidate.routeSteps(),
                    depth + 1,
                    childSequence);
            SearchPatrol patrol = childPatrols.get(candidate.patrolAgentId());
            patrol.actions.addAll(candidate.route().toMoveActions());
            patrol.commitFirstTarget(candidate.targetPosition());
            patrol.remainingSteps -= candidate.routeSteps();
            patrol.remainingFuel -= candidate.routeFuel();
            int collections = projectedCollections;
            Map<Position, UdonSpot> spots = new LinkedHashMap<>();
            for (UdonSpot spot : state.matchData().udonSpots()) {
                spots.put(spot.position(), spot);
            }
            for (Direction direction : candidate.route().directions()) {
                patrol.position = state.matchData().map()
                        .neighbor(patrol.position, direction)
                        .orElseThrow();
                if (child.projectCollection(patrol.position, patrol, spots)) {
                    collections++;
                }
            }
            return new SearchState(
                    childStock,
                    childTeamBrands,
                    childRealizableTeamBrands,
                    childPatrols,
                    refuelSchedule,
                    collections,
                    safeProjectedCollections + contention.safeProjectedCollections(),
                    tiedProjectedCollections + contention.tiedProjectedCollections(),
                    contestedProjectedCollections + contention.contestedProjectedCollections(),
                    stronglyContestedProjectedCollections
                            + contention.stronglyContestedCollections(),
                    arrivalSafeProjectedCollections + (arrivalContention != null ? arrivalContention.arrivalSafeCollections() : 0),
                    arrivalTiedProjectedCollections + (arrivalContention != null ? arrivalContention.arrivalTiedCollections() : 0),
                    arrivalAtRiskProjectedCollections + (arrivalContention != null ? arrivalContention.arrivalAtRiskCollections() : 0),
                    arrivalUnobservedProjectedCollections
                            + (arrivalContention != null ? arrivalContention.unobservedCollections() : 0),
                    Math.addExact(adjustedCollectionScore, routeAdjustedScore),
                    intentForecastRealizableCollections + intentMetrics.forecastRealizableCollections(),
                    intentLikelyClaimedFirstCollections + intentMetrics.likelyClaimedFirstCollections(),
                    intentTieCollections + intentMetrics.tieCollections(),
                    childCommitment,
                    childSemiCommitment,
                    child.travelSteps,
                    child.depth,
                    child.sequence);
        }

        private RouteProjection projectedCollectionsOn(
                DayState state,
                Map<Position, UdonSpot> spots,
                Route route,
                SearchPatrol patrol) {
            Map<Position, Integer> available = new LinkedHashMap<>(stock);
            Set<Position> visited = new LinkedHashSet<>(patrol.visitedSpots);
            Position cursor = route.start();
            int gain = 0;
            boolean newBrandForPatrol = false;
            boolean newBrandForTeam = false;
            for (Direction direction : route.directions()) {
                cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow();
                UdonSpot spot = spots.get(cursor);
                if (spot == null || !visited.add(cursor)) {
                    continue;
                }
                int currentStock = available.getOrDefault(cursor, 0);
                if (currentStock > 0) {
                    available.put(cursor, currentStock - 1);
                    gain++;
                    newBrandForPatrol |= !patrol.brands.contains(spot.brand());
                    newBrandForTeam |= !teamBrands.contains(spot.brand());
                }
            }
            return new RouteProjection(gain, newBrandForPatrol, newBrandForTeam);
        }

        private boolean projectCollection(
                Position position,
                SearchPatrol patrol,
                Map<Position, UdonSpot> spots) {
            UdonSpot spot = spots.get(position);
            if (spot == null || !patrol.visitedSpots.add(position)) {
                return false;
            }
            int currentStock = stock.getOrDefault(position, 0);
            if (currentStock <= 0) {
                return false;
            }
            stock.put(position, currentStock - 1);
            patrol.brands.add(spot.brand());
            teamBrands.add(spot.brand());
            return true;
        }

        private TeamPlan completePlan(DayState state) {
            Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
            for (AgentState agent : state.agents()) {
                if (agent.kind() == AgentKind.PATROL) {
                    SearchPatrol patrol = patrols.get(agent.id());
                    int used = state.stepBudget() - patrol.remainingSteps;
                    actions.put(agent.id(), ActionPlanCompleter.complete(
                            patrol.actions, used, state.stepBudget()));
                } else if (refuelSchedule.filter(
                        schedule -> schedule.refuelId.equals(agent.id())).isPresent()) {
                    Route route = refuelSchedule.orElseThrow().route;
                    actions.put(agent.id(), ActionPlanCompleter.complete(
                            route.toMoveActions(), route.stepsUsed(), state.stepBudget()));
                } else {
                    actions.put(agent.id(), List.of(new WaitAction(state.stepBudget())));
                }
            }
            return new TeamPlan(actions);
        }

        private int remainingUsefulSteps() {
            return patrols.values().stream()
                    .filter(patrol -> patrol.remainingFuel > 0)
                    .mapToInt(patrol -> patrol.remainingSteps)
                    .sum();
        }

        private int remainingFuel() {
            return patrols.values().stream().mapToInt(patrol -> patrol.remainingFuel).sum();
        }

        private int optimisticHarvestPotential() {
            int remainingStock = stock.values().stream().mapToInt(Integer::intValue).sum();
            return projectedCollections + Math.min(remainingStock, remainingUsefulSteps());
        }

        /**
         * Opening-strategy bucket of this branch: the first committed non-start Udon target of
         * every PATROL agent, ordered by {@code AgentId}. This is a diversity bucket only and is
         * never a substitute for {@link #key()} exact duplicate elimination.
         */
        private StrategicDiversityKey diversityKey() {
            if (strategicDiversityKey == null) {
                List<StrategicDiversityKey.AgentOpening> openings = new ArrayList<>();
                for (SearchPatrol patrol : patrols.values()) {
                    openings.add(new StrategicDiversityKey.AgentOpening(
                            patrol.id.value(), patrol.firstCommittedTargetValue()));
                }
                strategicDiversityKey = StrategicDiversityKey.of(openings);
            }
            return strategicDiversityKey;
        }

        private StateKey key() {
            List<StockKey> stocks = stock.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value)))
                    .map(entry -> new StockKey(entry.getKey().value(), entry.getValue()))
                    .toList();
            List<String> brands = teamBrands.stream().map(BrandId::value).sorted().toList();
            List<String> realizableBrands = forecastRealizableTeamBrands.stream()
                    .map(BrandId::value).sorted().toList();
            List<PatrolStateKey> patrolKeys = patrols.values().stream()
                    .map(SearchPatrol::key)
                    .toList();
            return new StateKey(
                    stocks,
                    brands,
                    realizableBrands,
                    patrolKeys,
                    refuelSchedule,
                    safeProjectedCollections,
                    tiedProjectedCollections,
                    contestedProjectedCollections,
                    stronglyContestedProjectedCollections,
                    arrivalSafeProjectedCollections,
                    arrivalTiedProjectedCollections,
                    arrivalAtRiskProjectedCollections,
                    arrivalUnobservedProjectedCollections,
                    adjustedCollectionScore,
                    intentForecastRealizableCollections,
                    intentLikelyClaimedFirstCollections,
                    intentTieCollections,
                    new CommitmentStateKey(
                            commitment.realizableBrandKey(),
                            commitment.adjustedScore(),
                            commitment.realizableCollections(),
                            commitment.hardClaimedFirstCollections(),
                            commitment.directIntentBeforeCollections(),
                            commitment.followOnIntentBeforeCollections(),
                            commitment.tieCollections()),
                    new SemiCommitmentStateKey(
                            semiCommitment.realizableBrandKey(),
                            semiCommitment.adjustedScore(),
                            semiCommitment.realizableCollections(),
                            semiCommitment.hardClaimedFirstCollections(),
                            semiCommitment.semiClaimedFirstCollections(),
                            semiCommitment.directIntentBeforeCollections(),
                            semiCommitment.followOnIntentBeforeCollections(),
                            semiCommitment.tieCollections()));
        }
    }

    private static final class SearchPatrol {

        private final AgentId id;
        private final Set<Position> visitedSpots;
        private final Set<BrandId> brands;
        private final List<AgentAction> actions;
        private Position position;
        private int remainingFuel;
        private int remainingSteps;

        /** First committed non-start Udon target of this branch, null while uncommitted. */
        private Position firstCommittedTarget;

        private SearchPatrol(
                AgentId id,
                Position position,
                int remainingFuel,
                int remainingSteps,
                Set<Position> visitedSpots,
                Set<BrandId> brands,
                List<AgentAction> actions) {
            this.id = id;
            this.position = position;
            this.remainingFuel = remainingFuel;
            this.remainingSteps = remainingSteps;
            this.visitedSpots = visitedSpots;
            this.brands = brands;
            this.actions = actions;
        }

        private SearchPatrol copy() {
            SearchPatrol copy = new SearchPatrol(
                    id,
                    position,
                    remainingFuel,
                    remainingSteps,
                    new LinkedHashSet<>(visitedSpots),
                    new LinkedHashSet<>(brands),
                    new ArrayList<>(actions));
            copy.firstCommittedTarget = firstCommittedTarget;
            return copy;
        }

        /** Records the branch opening once; later targets never overwrite it. */
        private void commitFirstTarget(Position target) {
            if (firstCommittedTarget == null) {
                firstCommittedTarget = target;
            }
        }

        private int firstCommittedTargetValue() {
            return firstCommittedTarget == null
                    ? StrategicDiversityKey.NO_TARGET
                    : firstCommittedTarget.value();
        }

        private PatrolStateKey key() {
            List<Integer> visited = visitedSpots.stream()
                    .map(Position::value)
                    .sorted()
                    .toList();
            List<String> brandValues = brands.stream().map(BrandId::value).sorted().toList();
            return new PatrolStateKey(
                    id.value(),
                    position.value(),
                    remainingFuel,
                    remainingSteps,
                    visited,
                    brandValues,
                    List.copyOf(actions));
        }
    }

    private static final class MutableStats {

        private int expandedStates;
        private int generatedStates;
        private int prunedStates;
        private int completedPlans;
        private int incumbentImprovements;
        private int coveragePhaseExpandedStates;
        private int harvestPhaseExpandedStates;
        private int candidateGenerated;
        private int candidateRetained;
        private int candidatePrunedByTopK;
        private int duplicateStates;
        private int frontierPrunedStates;

        private AnytimeSearchStats immutable(boolean budgetExhausted) {
            return new AnytimeSearchStats(
                    expandedStates,
                    generatedStates,
                    prunedStates,
                    completedPlans,
                    incumbentImprovements,
                    coveragePhaseExpandedStates,
                    harvestPhaseExpandedStates,
                    candidateGenerated,
                    candidateRetained,
                    candidatePrunedByTopK,
                    duplicateStates,
                    frontierPrunedStates,
                    budgetExhausted);
        }
    }

    /** Mutable M11 search diagnostics; inert for every pre-M11 policy. */
    private static final class DiverseMutableStats {

        private final Set<StrategicDiversityKey> generatedStrategies = new LinkedHashSet<>();
        private int qualityExpansions;
        private int diversityExpansions;
        private int frontierPeak;
        private int candidateEliteSelected;
        private int candidateDiverseSelected;
        private int statesRejectedByExactDedup;
        private int statesRejectedByFrontierLimit;

        private void observeGenerated(SearchState state, boolean active) {
            if (active) {
                generatedStrategies.add(state.diversityKey());
            }
        }

        private DiverseSearchStats immutable(DiverseFrontier<SearchState> frontier) {
            return new DiverseSearchStats(
                    generatedStrategies.size(),
                    frontier.uniqueStrategyKeysExpanded(),
                    qualityExpansions,
                    diversityExpansions,
                    frontier.maxStrategyExpansionCount(),
                    frontierPeak,
                    candidateEliteSelected,
                    candidateDiverseSelected,
                    frontier.eliteRetained(),
                    frontier.diverseRetained(),
                    frontier.strategyBucketsSeen(),
                    statesRejectedByExactDedup,
                    statesRejectedByFrontierLimit,
                    frontier.strategiesWithAtLeastExpansions(2));
        }
    }

    private enum M17CandidateFamily {
        THROUGHPUT,
        SPATIAL_SEPARATION,
        ROUTE_ORDER,
        CONTENTION_AVOIDANCE;

        private static M17CandidateFamily forDiscoveryExpansion(int oneBasedExpansion) {
            int ordinal = Math.max(0, oneBasedExpansion - 1) / 4;
            return values()[Math.min(values().length - 1, ordinal)];
        }
    }

    /** Bounded M17 candidate-generation diagnostics; it never affects evaluation or ordering. */
    private static final class M17CandidateDiversityStats {

        private int candidateAttempts;
        private int uniqueCandidates;
        private int duplicateCandidatesRejected;
        private int invalidCandidatesRejected;
        private int throughputGenerated;
        private int spatialSeparationGenerated;
        private int routeOrderGenerated;
        private int contentionAvoidanceGenerated;
        private int throughputUnique;
        private int spatialSeparationUnique;
        private int routeOrderUnique;
        private int contentionAvoidanceUnique;
        private final Set<String> planSignatures = new LinkedHashSet<>();
        private final Set<String> firstTargetAssignmentSignatures = new LinkedHashSet<>();
        private final Set<String> routePrefixSignatures = new LinkedHashSet<>();

        private void candidateAttempt(M17CandidateFamily family) {
            candidateAttempts++;
            if (family == null) {
                return;
            }
            switch (family) {
                case THROUGHPUT -> throughputGenerated++;
                case SPATIAL_SEPARATION -> spatialSeparationGenerated++;
                case ROUTE_ORDER -> routeOrderGenerated++;
                case CONTENTION_AVOIDANCE -> contentionAvoidanceGenerated++;
            }
        }

        private void recordUnique(
                M17CandidateFamily family,
                String planSignature,
                String firstTargetAssignmentSignature,
                String routePrefixSignature) {
            planSignatures.add(planSignature);
            firstTargetAssignmentSignatures.add(firstTargetAssignmentSignature);
            routePrefixSignatures.add(routePrefixSignature);
            if (family == null) {
                return;
            }
            switch (family) {
                case THROUGHPUT -> throughputUnique++;
                case SPATIAL_SEPARATION -> spatialSeparationUnique++;
                case ROUTE_ORDER -> routeOrderUnique++;
                case CONTENTION_AVOIDANCE -> contentionAvoidanceUnique++;
            }
        }
    }

    private static final class M18AllocationStats {

        private int allocationAttempts;
        private int validAllocations;
        private int invalidAllocations;
        private int duplicatePlanAllocations;
        private int relevantPoolSize;
        private int unassignedReachableOpportunities;
        private int maxDistinctAssignedOpportunities;
        private int minDuplicateOwnedOpportunityCount;
        private int maxDistinctFirstAssignments;

        private int uniqueFor(
                TeamOpportunityAllocation.Seed seed,
                Map<String, M18AllocationCandidate> candidates) {
            return (int) candidates.values().stream()
                    .map(M18AllocationCandidate::allocation)
                    .filter(value -> value.seed() == seed)
                    .map(TeamOpportunityAllocation::signature)
                    .distinct()
                    .count();
        }
    }

    private static final class M19CapacityStats {
        private int claimCandidateAttempts;
        private int uniquePhysicalPlans;
        private int duplicatePhysicalPlansRejected;
        private int invalidPhysicalPlans;
        private int relevantCapacitySpots;
        private int initialPathfindingExecutions;
        private int candidateGenerationPathfindingExecutions;
        private int finalPathfindingExecutions;
        private final Map<CapacityAwareTeamAllocation.Seed, Integer> uniqueBySeed = new LinkedHashMap<>();

        private void observe(CapacityAwareTeamAllocation allocation) {
            uniqueBySeed.merge(allocation.seed(), 1, Integer::sum);
        }

        private int uniqueFor(CapacityAwareTeamAllocation.Seed seed) {
            return uniqueBySeed.getOrDefault(seed, 0);
        }
    }

    private record EvaluatedPlan(TeamPlan plan, PlanEvaluation evaluation) {
    }
    private record ArrivalEvaluatedPlan(
            TeamPlan plan,
            ArrivalAwarePlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record RiskAdjustedEvaluatedPlan(
            TeamPlan plan,
            RiskAdjustedPlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record IntentAwareEvaluatedPlan(
            TeamPlan plan,
            IntentAwarePlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record CommitmentAwareEvaluatedPlan(
            TeamPlan plan,
            CommitmentAwarePlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record SemiCommitmentAwareEvaluatedPlan(
            TeamPlan plan,
            SemiCommitmentAwarePlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record HorizonAwareEvaluatedPlan(
            TeamPlan plan,
            HorizonAwarePlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record HarvestHorizonAwareEvaluatedPlan(
            TeamPlan plan,
            HarvestHorizonAwarePlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record RelativeMarginEvaluatedPlan(
            TeamPlan plan,
            RelativeMarginPlanEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record ReplacementAwareEvaluatedPlan(
            TeamPlan plan,
            ReplacementAwareRelativeMarginEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record CoupledCompetitiveEvaluatedPlan(
            TeamPlan plan,
            CoupledCompetitiveMarginEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record HybridCalibratedMarginEvaluatedPlan(
            TeamPlan plan,
            HybridCalibratedMarginEvaluation evaluation,
            EvaluatedPlan base) {
    }

    private record IntentDiagnostic(
            int groupRawId,
            OpponentAgentIntentForecast agent,
            OpponentTargetIntent target) {
    }

    private record ObservedAgentDiagnostic(
            int groupRawId,
            OpponentAgentIntentForecast agent) {
    }

    private record RefuelSchedule(
            AgentId refuelId,
            AgentId patrolId,
            Route route,
            int arrivalStep,
            int currentFuel) {
    }

    private record PatrolRouteKey(
            AgentId agentId,
            Position start,
            int fuel,
            Position target) {
    }

    private record M19RouteKey(Position start, Position target) {
    }

    private record M18NextTarget(Position target, Route route) {
    }

    private record M18AllocationSelection(
            TeamOpportunityAllocation allocation,
            Map<AgentId, Integer> estimatedLoads,
            HybridCalibratedMarginEvaluatedPlan evaluation) {
    }

    private record M18AllocationCandidate(
            TeamOpportunityAllocation allocation,
            Map<AgentId, Integer> estimatedLoads,
            TeamPlan plan,
            HybridCalibratedMarginEvaluatedPlan evaluation) {
    }

    private record M19CapacitySelection(
            CapacityAwareTeamAllocation allocation,
            CapacityAwareTeamAllocator.Timeline timeline,
            HybridCalibratedMarginEvaluatedPlan evaluation) {
    }

    private record M19CapacityCandidate(
            CapacityAwareTeamAllocation allocation,
            CapacityAwareTeamAllocator.Timeline timeline,
            TeamPlan plan,
            HybridCalibratedMarginEvaluatedPlan evaluation) {
    }

    private record RouteProjection(
            int collectionGain,
            boolean newBrandForPatrol,
            boolean newBrandForTeam) {
    }

    private record StockKey(int position, int stock) {
    }

    private record PatrolStateKey(
            int agentId,
            int position,
            int remainingFuel,
            int remainingSteps,
            List<Integer> visitedSpots,
            List<String> brands,
            List<AgentAction> actions) {
    }

    private record StateKey(
            List<StockKey> stocks,
            List<String> teamBrands,
            List<String> forecastRealizableTeamBrands,
            List<PatrolStateKey> patrols,
            Optional<RefuelSchedule> refuelSchedule,
            int safeProjectedCollections,
            int tiedProjectedCollections,
            int contestedProjectedCollections,
            int stronglyContestedProjectedCollections,
            int arrivalSafeProjectedCollections,
            int arrivalTiedProjectedCollections,
            int arrivalAtRiskProjectedCollections,
            int arrivalUnobservedProjectedCollections,
            int adjustedCollectionScore,
            int intentForecastRealizableCollections,
            int intentLikelyClaimedFirstCollections,
            int intentTieCollections,
            CommitmentStateKey commitment,
            SemiCommitmentStateKey semiCommitment) {
    }

    /**
     * M12 slice of the exact-duplicate key.
     *
     * <p>Inert for every pre-M12 policy, where the commitment branch metrics stay empty and this
     * component is therefore a constant. Brands arrive as a sorted list, never as a set, so exact
     * dedup can never depend on iteration order.</p>
     */
    private record CommitmentStateKey(
            List<String> realizableTeamBrands,
            int adjustedScore,
            int realizableCollections,
            int hardClaimedFirstCollections,
            int directIntentBeforeCollections,
            int followOnIntentBeforeCollections,
            int tieCollections) {
    }

    /**
     * M12.1 slice of the exact-duplicate key.
     *
     * <p>Inert for every other policy, where the semi-commitment branch metrics stay empty and this
     * component is therefore a constant. Kept separate from {@link CommitmentStateKey} so two
     * branches that agree on hard depletion but differ on the bounded direct reservation are still
     * distinct states. Brands arrive as a sorted list, never as a set.</p>
     */
    private record SemiCommitmentStateKey(
            List<String> realizableTeamBrands,
            int adjustedScore,
            int realizableCollections,
            int hardClaimedFirstCollections,
            int semiClaimedFirstCollections,
            int directIntentBeforeCollections,
            int followOnIntentBeforeCollections,
            int tieCollections) {
    }
}

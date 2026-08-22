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
                ? new TeamCoordinatorPlanner(this.patrolRouteFinder, this.refuelRouteFinder, this.validator)
                : teamCoordinator;
        this.contentionFallback = (policy == AnytimeSearchPolicy.CONTENTION
                || isArrivalPolicy(policy) || isIntentAwarePolicy(policy)
                || isCommitmentAwarePolicy(policy) || isSemiCommitmentAwarePolicy(policy))
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
        EvaluatedPlan incumbent = null;
        if (isSemiCommitmentAwarePolicy()) {
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
        if (isSemiCommitmentAwarePolicy()) {
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
        Map<StrategicDiversityKey, IntentAwarePlanEvaluation> bestCompleteByStrategy = new TreeMap<>();
        Map<StrategicDiversityKey, CommitmentAwarePlanEvaluation> bestCommitmentByStrategy =
                new TreeMap<>();
        Map<StrategicDiversityKey, SemiCommitmentAwarePlanEvaluation> bestSemiCommitmentByStrategy =
                new TreeMap<>();
        try {
            Set<StateKey> seen = new HashSet<>();
            for (SearchState root : roots(context)) {
                stats.generatedStates++;
                diverseStats.observeGenerated(root, strategyAware);
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
                if (scheduler != null) {
                    StrategyStageScheduler.Decision<SearchState> decision =
                            scheduler.next(stratifiedFrontier);
                    if (decision == null) {
                        break;
                    }
                    current = decision.state();
                    currentStrategy = decision.strategy();
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
                if (isSemiCommitmentAwarePolicy()) {
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
                for (TeamTargetCandidate retainedCandidate : retainedCandidates) {
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
        if (isSemiCommitmentAwarePolicy()) {
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
        return new AnytimePlanResult(
                incumbent.plan, incumbent.evaluation, finalStats,
                riskAdjustedEvaluation, intentAwareEvaluation, diverseSearchStats,
                stratifiedSearchStats, commitmentAwareEvaluation, semiCommitmentAwareEvaluation);
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

    /** The M10 opponent intent forecast is the shared input of the M10, M12 and M12.1 semantics. */
    private static boolean usesOpponentIntentForecast(AnytimeSearchPolicy policy) {
        return isIntentAwarePolicy(policy) || isCommitmentAwarePolicy(policy)
                || isSemiCommitmentAwarePolicy(policy);
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
                || isCommitmentAwarePolicy(policy) || isSemiCommitmentAwarePolicy(policy);
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
                } else if (isSemiCommitmentAwarePolicy()) {
                    int initialArrivalStep = context.state.stepBudget() - patrol.remainingSteps;
                    context.candidateSemiCommitment.put(
                            candidate,
                            context.semiCommitmentEvaluator.evaluateRoute(
                                    context.state,
                                    context.spotsByPosition,
                                    route,
                                    initialArrivalStep,
                                    searchState.stock,
                                    patrol.visitedSpots,
                                    searchState.semiCommitment.realizableTeamBrands(),
                                    context.semiCommitmentForecast.commitment(),
                                    semiCommitmentAdjustmentWeights));
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
        PlanEvaluation evaluation = new PlanEvaluation(
                valid.brandsCollected().size(),
                udonTotal,
                activePatrols,
                remainingFuel,
                movementSteps,
                signature(plan));
        return Optional.of(new EvaluatedPlan(plan, evaluation));
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
        };
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    private static final class SearchContext {

        private final DayState state;
        private final List<UdonSpot> orderedSpots;
        private final Map<Position, UdonSpot> spotsByPosition = new LinkedHashMap<>();
        private final Map<PatrolRouteKey, Optional<Route>> routeCache = new LinkedHashMap<>();
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
            this.intentForecast = usesOpponentIntentForecast(policy)
                    ? new OpponentIntentForecaster().forecast(state, opponentIntentConfig)
                    : new OpponentIntentForecast(List.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0);
            // Cheap linear annotation over the claims the M10 forecast already accepted: no route
            // is recomputed and no shortest path is searched for commitment.
            this.commitmentForecast =
                    isCommitmentAwarePolicy(policy) || isSemiCommitmentAwarePolicy(policy)
                            ? OpponentCommitmentForecast.annotate(this.intentForecast)
                            : OpponentCommitmentForecast.empty();
            // One further linear pass for the bounded per-spot aggregates. Nothing is re-forecast,
            // and the per-plan evaluation reads this same view rather than rebuilding it.
            this.semiCommitmentForecast = isSemiCommitmentAwarePolicy(policy)
                    ? SemiCommitmentForecast.derive(this.commitmentForecast)
                    : SemiCommitmentForecast.empty();
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
                    } else if (isSemiCommitmentAwarePolicy(context.policy)) {
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

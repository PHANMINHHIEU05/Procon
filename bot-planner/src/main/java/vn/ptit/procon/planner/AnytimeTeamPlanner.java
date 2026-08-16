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
import java.util.PriorityQueue;
import java.util.Set;
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
        this.contentionFallback = (policy == AnytimeSearchPolicy.CONTENTION || isArrivalPolicy(policy))
                ? new HarvestAnytimeTeamPlanner(config)
                : null;
        this.riskAdjustmentWeights = Objects.requireNonNull(
                riskAdjustmentWeights, "Risk adjustment weights must not be null");
        this.contentionDiagnostics = contentionDiagnostics;
    }

    @Override
    public TeamPlan plan(DayState state) {
        return planWithStats(state).plan();
    }

    public AnytimePlanResult planWithStats(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        MutableStats stats = new MutableStats();
        SearchContext context = new SearchContext(state, policy, riskAdjustmentWeights);
        ArrivalEvaluatedPlan arrivalIncumbent = null;
        RiskAdjustedEvaluatedPlan riskAdjustedIncumbent = null;
        EvaluatedPlan incumbent = null;
        if (isRiskAdjustedPolicy()) {
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
        if (isRiskAdjustedPolicy()) {
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

        PriorityQueue<SearchState> frontier = new PriorityQueue<>(statePreference());
        try {
            Set<StateKey> seen = new HashSet<>();
            for (SearchState root : roots(context)) {
                stats.generatedStates++;
                if (seen.add(root.key())) {
                    addBounded(frontier, root, stats);
                } else {
                    stats.prunedStates++;
                    stats.duplicateStates++;
                }
            }

            while (!frontier.isEmpty() && stats.expandedStates < config.maxExpandedStates()) {
                SearchState current = frontier.poll();
                stats.expandedStates++;
                TeamPlan complete = current.completePlan(context.state);
                stats.completedPlans++;
                if (isRiskAdjustedPolicy()) {
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
                List<TeamTargetCandidate> retainedCandidates = retainCandidates(
                        context, candidates, coveragePhase);
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
                    SearchState child = current.child(
                            context.state,
                            retainedCandidate,
                            contention,
                            arrivalContention,
                            routeAdjustedScore,
                            context.nextSequence());
                    stats.generatedStates++;
                    if (!seen.add(child.key())) {
                        stats.prunedStates++;
                        stats.duplicateStates++;
                        continue;
                    }
                    addBounded(frontier, child, stats);
                }
            }
        } catch (RuntimeException exception) {
            stats.prunedStates++;
        }

        boolean budgetExhausted = !frontier.isEmpty()
                && stats.expandedStates >= config.maxExpandedStates();
        AnytimeSearchStats finalStats = stats.immutable(budgetExhausted);
        if (isRiskAdjustedPolicy()) {
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
        return new AnytimePlanResult(
                incumbent.plan, incumbent.evaluation, finalStats, riskAdjustedEvaluation);
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
            PriorityQueue<SearchState> frontier,
            SearchState candidate,
            MutableStats stats) {
        frontier.add(candidate);
        if (frontier.size() <= config.maxFrontierSize()) {
            return;
        }
        SearchState worst = frontier.stream().max(statePreference()).orElseThrow();
        frontier.remove(worst);
        stats.prunedStates++;
        stats.frontierPrunedStates++;
    }

    private Comparator<SearchState> statePreference() {
        return switch (policy) {
            case ORIGINAL -> ORIGINAL_STATE_PREFERENCE;
            case HARVEST -> HARVEST_STATE_PREFERENCE;
            case CONTENTION -> CONTENTION_STATE_PREFERENCE;
            case ANYTIME_ARRIVAL_CONTENTION -> ARRIVAL_CONTENTION_STATE_PREFERENCE;
            case ANYTIME_WEIGHTED_ARRIVAL_CONTENTION -> ARRIVAL_CONTENTION_STATE_PREFERENCE;
            case ANYTIME_RISK_ADJUSTED -> RISK_ADJUSTED_STATE_PREFERENCE;
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
        private final AnytimeSearchPolicy policy;
        private final RiskAdjustmentWeights riskAdjustmentWeights;
        private long sequence;
        private int loggedCandidateDiagnostics;

        private SearchContext(
                DayState state,
                AnytimeSearchPolicy policy,
                RiskAdjustmentWeights riskAdjustmentWeights) {
            this.state = state;
            this.policy = policy;
            this.riskAdjustmentWeights = riskAdjustmentWeights;
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
        private final int travelSteps;
        private final int depth;
        private final long sequence;

        private SearchState(
                Map<Position, Integer> stock,
                Set<BrandId> teamBrands,
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
                int travelSteps,
                int depth,
                long sequence) {
            this.stock = stock;
            this.teamBrands = teamBrands;
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
                    schedule.map(value -> value.route.stepsUsed()).orElse(0),
                    0,
                    sequence);
            int collections = 0;
            int arrivalSafe = 0;
            int arrivalTied = 0;
            int arrivalAtRisk = 0;
            int arrivalUnobserved = 0;
            int adjustedScore = 0;
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
                    }
                }
            }
            return new SearchState(
                    stock,
                    teamBrands,
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
                long childSequence) {
            Map<Position, Integer> childStock = new LinkedHashMap<>(stock);
            Set<BrandId> childTeamBrands = new LinkedHashSet<>(teamBrands);
            Map<AgentId, SearchPatrol> childPatrols = new LinkedHashMap<>();
            for (Map.Entry<AgentId, SearchPatrol> entry : patrols.entrySet()) {
                childPatrols.put(entry.getKey(), entry.getValue().copy());
            }
            SearchState child = new SearchState(
                    childStock,
                    childTeamBrands,
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
                    travelSteps + candidate.routeSteps(),
                    depth + 1,
                    childSequence);
            SearchPatrol patrol = childPatrols.get(candidate.patrolAgentId());
            patrol.actions.addAll(candidate.route().toMoveActions());
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

        private StateKey key() {
            List<StockKey> stocks = stock.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value)))
                    .map(entry -> new StockKey(entry.getKey().value(), entry.getValue()))
                    .toList();
            List<String> brands = teamBrands.stream().map(BrandId::value).sorted().toList();
            List<PatrolStateKey> patrolKeys = patrols.values().stream()
                    .map(SearchPatrol::key)
                    .toList();
            return new StateKey(
                    stocks,
                    brands,
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
                    adjustedCollectionScore);
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
            return new SearchPatrol(
                    id,
                    position,
                    remainingFuel,
                    remainingSteps,
                    new LinkedHashSet<>(visitedSpots),
                    new LinkedHashSet<>(brands),
                    new ArrayList<>(actions));
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
            int adjustedCollectionScore) {
    }
}

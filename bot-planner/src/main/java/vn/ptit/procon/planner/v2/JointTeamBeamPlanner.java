package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.planner.DayPlanner;
import vn.ptit.procon.planner.RefuelRouteFinder;
import vn.ptit.procon.planner.Route;

/** Bounded joint-team beam search with catalog-only expansion and unchanged terminal objective. */
public final class JointTeamBeamPlanner implements DayPlanner {
    private static final boolean FAIR_FAMILY_EXPANSION = Boolean.parseBoolean(
            System.getProperty("procon.v2.fair_family_expansion",
                    System.getenv("PROCON_V2_FAIR_FAMILY_EXPANSION") != null
                            ? System.getenv("PROCON_V2_FAIR_FAMILY_EXPANSION")
                            : "false"));
    private static final int STOCK_WEIGHT = Integer.parseInt(
            System.getProperty("procon.v2.stock_weight",
                    System.getenv("PROCON_V2_STOCK_WEIGHT") != null
                            ? System.getenv("PROCON_V2_STOCK_WEIGHT")
                            : "100"));

    private final JointTeamBeamConfig config;
    private final RefuelRouteFinder refuelRouteFinder;

    public JointTeamBeamPlanner() { this(JointTeamBeamConfig.defaults()); }
    public JointTeamBeamPlanner(JointTeamBeamConfig config) { this(config, new RefuelRouteFinder()); }

    JointTeamBeamPlanner(JointTeamBeamConfig config, RefuelRouteFinder refuelRouteFinder) {
        this.config = java.util.Objects.requireNonNull(config, "Joint beam configuration must not be null");
        this.refuelRouteFinder = java.util.Objects.requireNonNull(refuelRouteFinder,
                "REFUEL route finder must not be null");
    }

    @Override public TeamPlan plan(DayState state) { return planWithStats(state).plan(); }

    public JointTeamBeamResult planWithStats(DayState state) {
        return planWithStats(state, refuelRoots(state), Long.MAX_VALUE, Integer.MAX_VALUE, System::nanoTime);
    }

    /** Package-visible R3 entry point: roots are already validated and no search work may outlive deadline. */
    JointTeamBeamResult planWithStats(DayState state,
            List<Optional<JointTeamSearchState.RefuelRootSchedule>> roots, long deadlineNanos,
            int terminalShortlistLimit, LongSupplier clock) {
        List<R3RootInput> inputs = roots.stream().map(schedule -> new R3RootInput(schedule,
                schedule.isPresent() ? new R3RootFamilyProvenance(
                        schedule.get().patrolSupports().size(), schedule.get().patrolSupports().keySet().stream()
                                .map(AgentId::value).toList(), schedule.get().refuelId().value(), schedule.get().signature())
                        : R3RootFamilyProvenance.noRefuel())).toList();
        return planWithAudit(state, inputs, deadlineNanos, terminalShortlistLimit, clock, null);
    }

    /** Package-visible R3 entry point with an observational root-family sidecar. */
    JointTeamBeamResult planWithAudit(DayState state, List<R3RootInput> roots, long deadlineNanos,
            int terminalShortlistLimit, LongSupplier clock, R3RootFamilyAuditCollector audit) {
        return planWithAudit(state, roots, deadlineNanos, terminalShortlistLimit, clock, audit, false);
    }

    /** R3 gives every retained root one expansion opportunity before global competition starts. */
    JointTeamBeamResult planWithAudit(DayState state, List<R3RootInput> roots, long deadlineNanos,
            int terminalShortlistLimit, LongSupplier clock, R3RootFamilyAuditCollector audit,
            boolean fairRootExpansion) {
        java.util.Objects.requireNonNull(state, "Day state must not be null");
        java.util.Objects.requireNonNull(roots, "Root schedules must not be null");
        long startedNanos = clock.getAsLong();
        long catalogStartedNanos = clock.getAsLong();
        boolean competitiveTargets = config.competitiveTargetPolicy().usesRace();
        JointRouteCatalog catalog = JointRouteCatalog.forState(state, competitiveTargets);
        CompetitiveOpportunityCatalog opportunityCatalog = competitiveTargets
                ? CompetitiveOpportunityCatalog.forState(state, catalog) : null;
        CompetitiveAuditAccumulator competitiveAudit = new CompetitiveAuditAccumulator(
                config.competitiveTargetPolicy());
        long catalogMillis = elapsedMillis(catalogStartedNanos, clock);
        JointTerminalEvaluator terminalEvaluator = new JointTerminalEvaluator(state);
        MutableStats stats = new MutableStats(catalog.pathfindingExecutions());
        Map<Integer, MutableDepthStats> depths = new TreeMap<>();
        List<JointTerminalEvaluator.StageATerminalCandidate> stageACandidates = new ArrayList<>();
        List<EvaluatedTerminal> immediatelyEvaluated = new ArrayList<>();
        V2CollectionAuditRecorder collectionAudit = config.collectionAuditMode() == V2CollectionAuditMode.TRACE
                ? new V2CollectionAuditRecorder(state, config.collectionAuditMode()) : null;

        log("V2_JOINT_BEAM_START", "day", state.day().value(), "beamWidth", config.beamWidth(),
                "maxExpandedStates", config.maxExpandedStates(), "maxChildrenPerState",
                config.maxChildrenPerState(), "maxNextTargetsPerAgent", config.maxNextTargetsPerAgent(),
                "fullTerminalEvaluationLimit", config.fullTerminalEvaluationLimit(), "searchPolicy",
                policyLabel(), "competitiveTargetPolicy", config.competitiveTargetPolicy());
        log("V2_PATHFINDING_AUDIT", "day", state.day().value(), "catalogPathfindingExecutions",
                catalog.pathfindingExecutions(), "searchPathfindingExecutions", 0);

        List<JointTeamSearchState> frontier = new ArrayList<>();
        Map<String, JointTeamSearchState> seen = new HashMap<>();
        DominanceRegistry dominance = new DominanceRegistry();
        if (audit != null) roots.forEach(root -> audit.rootCandidate(root.provenance()));
        List<JointTeamSearchState> initiallyAdmittedRoots = new ArrayList<>();
        for (R3RootInput rootInput : roots) {
            if (deadlineReached(deadlineNanos, clock)) {
                stats.deadline("ROOT_ADMISSION");
                break;
            }
            Optional<JointTeamSearchState.RefuelRootSchedule> rootSchedule = rootInput.schedule();
            JointTeamSearchState root = JointTeamSearchState.root(state, rootSchedule);
            stats.rootStates++;
            if (rootSchedule.isPresent()) stats.rootScheduleStates++;
            if (audit != null) audit.rootAdmitted(root.exactKey(), rootInput.provenance(),
                    !seen.containsKey(root.exactKey()));
            if (audit != null && rootInput.provenance().supportServiceCount() == 0) {
                audit.noRefuelRoot(root.exactKey(), root.timeline().successfulCollections(),
                        (int) root.patrols().values().stream().filter(value -> !value.stopped()).count());
            }
            admit(state, root, frontier, seen, dominance, terminalEvaluator, stageACandidates,
                    immediatelyEvaluated, catalog, stats, depths, deadlineNanos, clock, audit,
                    collectionAudit, null, -1, 0, 0);
            if (seen.get(root.exactKey()) == root && hasActivePatrol(root)) {
                initiallyAdmittedRoots.add(root);
            }
        }
        trimFrontier(frontier, catalog, state.stepBudget(), stats, collectionAudit);

        if (fairRootExpansion) {
            for (JointTeamSearchState root : initiallyAdmittedRoots) {
                if (stats.expandedStates >= config.maxExpandedStates() || deadlineReached(deadlineNanos, clock)) {
                    stats.deadline("ROOT_EXPANSION");
                    break;
                }
                frontier.remove(root);
                expand(state, root, frontier, seen, dominance, terminalEvaluator, stageACandidates,
                        immediatelyEvaluated, catalog, stats, depths, deadlineNanos, clock, audit,
                        collectionAudit, opportunityCatalog, competitiveAudit);
            }
            trimFrontier(frontier, catalog, state.stepBudget(), stats, collectionAudit);
        }

        Map<Integer, Integer> expansionsByFamily = new HashMap<>();
        while (!frontier.isEmpty() && stats.expandedStates < config.maxExpandedStates()
                && !deadlineReached(deadlineNanos, clock)) {
            JointTeamSearchState current;
            if (FAIR_FAMILY_EXPANSION) {
                Map<Integer, List<JointTeamSearchState>> byFamily = new LinkedHashMap<>();
                for (JointTeamSearchState s : frontier) {
                    int family = s.refuelRoot().map(r -> r.patrolSupports().size()).orElse(0);
                    byFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(s);
                }
                Comparator<JointTeamSearchState> pref = statePreference(catalog, state.stepBudget());
                for (List<JointTeamSearchState> list : byFamily.values()) {
                    list.sort(pref);
                }
                int minExpansions = Integer.MAX_VALUE;
                int chosenFamily = -1;
                for (int family : byFamily.keySet()) {
                    int count = expansionsByFamily.getOrDefault(family, 0);
                    if (count < minExpansions) {
                        minExpansions = count;
                        chosenFamily = family;
                    }
                }
                current = byFamily.get(chosenFamily).getFirst();
                frontier.remove(current);
                expansionsByFamily.merge(chosenFamily, 1, Integer::sum);
            } else {
                frontier.sort(statePreference(catalog, state.stepBudget()));
                current = frontier.removeFirst();
            }
            expand(state, current, frontier, seen, dominance, terminalEvaluator, stageACandidates,
                    immediatelyEvaluated, catalog, stats, depths, deadlineNanos, clock, audit,
                    collectionAudit, opportunityCatalog, competitiveAudit);
            trimFrontier(frontier, catalog, state.stepBudget(), stats, collectionAudit);
        }
        if (deadlineReached(deadlineNanos, clock) && stats.deadlinePhase.equals("NONE")) {
            stats.deadline("JOINT_BEAM");
        }

        stats.budgetExhausted = stats.expandedStates >= config.maxExpandedStates() && !frontier.isEmpty();
        long stageBWallStartedNanos = clock.getAsLong();
        List<EvaluatedTerminal> evaluated = evaluateTerminals(
                terminalEvaluator, stageACandidates, immediatelyEvaluated, stats, deadlineNanos, clock,
                terminalShortlistLimit, audit);
        stats.stageBTerminalWallMillis = usesDeferredTerminalEvaluation()
                ? elapsedMillis(stageBWallStartedNanos, clock) : 0;
        stats.terminalEvaluationMillis = stats.stageATerminalMillis + stats.stageBTerminalMillis;
        if (evaluated.isEmpty()) {
            // A planner deadline must still return a legal plan. This is deliberately the only
            // unavoidable coupled evaluation when no admitted terminal completed in time.
            JointTerminalEvaluator.StageATerminalCandidate fallback = terminalEvaluator
                    .evaluateStageA(SafePlanFactory.waitAll(state), 0).orElseThrow();
            long fallbackStarted = clock.getAsLong();
            JointTerminalEvaluation fallbackEvaluation = terminalEvaluator.evaluateStageB(fallback);
            evaluated.add(new EvaluatedTerminal(fallback, fallbackEvaluation));
            if (audit != null) audit.normalEvaluation(fallback, fallbackEvaluation);
            stats.stageBTerminalMillis += elapsedMillis(fallbackStarted, clock);
            stats.terminalPlansEvaluated++;
            stats.coupledTerminalEvaluations++;
        }
        evaluated.sort(EvaluatedTerminal.PREFERENCE);
        EvaluatedTerminal selected = evaluated.getFirst();
        if (audit != null) {
            audit.selected(selected.candidate());
            audit.complete(terminalEvaluator, deadlineNanos, clock);
        }
        if (collectionAudit != null) collectionAudit.selected(selected.candidate());
        long totalMillis = elapsedMillis(startedNanos, clock);
        stats.catalogMillis = catalogMillis;
        stats.searchMillis = Math.max(0, totalMillis - stats.catalogMillis - stats.stageBTerminalWallMillis);
        JointTeamBeamStats immutableStats = stats.toImmutable(totalMillis);
        List<JointBeamDepthStats> depthSummaries = depthSummaries(depths);
        List<JointTerminalPortfolioEntry> portfolio = portfolio(evaluated);
        CompetitiveSearchAudit competitiveSnapshot = competitiveAudit.complete(
                selected.candidate(), selected.evaluation());
        StageBRecallAudit recallAudit = stageBRecallAudit(terminalEvaluator, stageACandidates, evaluated,
                deadlineNanos, clock);
        TerminalObjectiveAudit objectiveAudit = TerminalObjectiveAuditSupport.build(recallAudit.mode(),
                recallAudit.terminals(), recallAudit.exhaustiveOrder(), recallAudit.productionWinner(),
                recallAudit.exhaustiveWinner());
        logDepthSummaries(state, depthSummaries);
        logPotentialDiscrimination(state, depthSummaries);
        logPortfolio(state, portfolio);
        if (collectionAudit != null) logCollectionHighWater(state, collectionAudit.complete());
        logCompetitiveAudit(state, competitiveSnapshot);
        logStageBRecallAudit(state, recallAudit);
        logTerminalObjectiveAudit(state, objectiveAudit);
        log("V2_TIMING_BREAKDOWN", "day", state.day().value(), "catalogWallMillis", immutableStats.catalogMillis(),
                "searchWallMillis", immutableStats.searchMillis(), "stageBTerminalWallMillis",
                immutableStats.stageBTerminalWallMillis(), "stageATerminalAccumulatedMillis",
                immutableStats.stageATerminalAccumulatedMillis(), "stageBTerminalAccumulatedMillis",
                immutableStats.stageBTerminalAccumulatedMillis(), "totalWallMillis", immutableStats.planningMillis(),
                "fullCoupledEvaluationCount", immutableStats.coupledTerminalEvaluations());
        var hybrid = selected.evaluation().hybrid();
        log("V2_JOINT_BEAM_DONE", "day", state.day().value(), "beamWidth", config.beamWidth(),
                "maxExpandedStates", config.maxExpandedStates(), "expanded", immutableStats.expandedStates(),
                "generatedChildren", immutableStats.generatedChildren(), "uniqueStates", immutableStats.uniqueStates(),
                "collectChildrenGenerated", immutableStats.collectChildrenGenerated(), "stopChildrenGenerated",
                immutableStats.stopChildrenGenerated(), "rootScheduleStates", immutableStats.rootScheduleStates(),
                "duplicateStatesRejected", immutableStats.duplicateStatesRejected(), "dominatedStatesRejected",
                immutableStats.dominatedStatesRejected(), "terminalStates", immutableStats.terminalStates(),
                "continuingTerminalCandidates", immutableStats.continuingTerminalCandidates(),
                "fullyStoppedTerminalCandidates", immutableStats.fullyStoppedTerminalCandidates(),
                "stageATerminalEvaluations", immutableStats.stageATerminalEvaluations(),
                "stageBRequested", immutableStats.stageBRequested(), "stageBDuplicateCandidatesSkipped",
                immutableStats.stageBDuplicateCandidatesSkipped(), "terminalPlansEvaluated",
                immutableStats.terminalPlansEvaluated(), "selectedOwnSemiBrands",
                hybrid.ownSemiBrands(), "selectedOwnSemiCollections", hybrid.ownSemiCollections(),
                "selectedCoupledOwnCollections", hybrid.coupledOwnCollections(),
                "selectedBaselineOpponentCollections", hybrid.opponentBaselineCollections(),
                "selectedCoupledOpponentCollections", hybrid.coupledOpponentCollections(),
                "selectedHybridOwnScore4", hybrid.hybridOwnScore4(), "selectedHybridOpponentScore4",
                hybrid.hybridOpponentScore4(), "selectedHybridMarginScore4", hybrid.hybridMarginScore4(),
                "catalogPathfindingExecutions", immutableStats.catalogPathfindingExecutions(),
                "searchPathfindingExecutions", immutableStats.searchPathfindingExecutions(), "planningMillis",
                immutableStats.planningMillis(), "planningDeadlineTriggered", immutableStats.planningDeadlineTriggered(),
                "deadlinePhase", immutableStats.deadlinePhase());
        return new JointTeamBeamResult(selected.candidate().plan(), selected.evaluation(), immutableStats,
                depthSummaries, portfolio,
                collectionAudit == null ? V2CollectionSearchAudit.empty() : collectionAudit.complete(),
                competitiveSnapshot, recallAudit, objectiveAudit);
    }

    private void expand(DayState state, JointTeamSearchState current, List<JointTeamSearchState> frontier,
            Map<String, JointTeamSearchState> seen, DominanceRegistry dominance,
            JointTerminalEvaluator terminalEvaluator,
            List<JointTerminalEvaluator.StageATerminalCandidate> stageACandidates,
            List<EvaluatedTerminal> immediatelyEvaluated, JointRouteCatalog catalog, MutableStats stats,
            Map<Integer, MutableDepthStats> depths, long deadlineNanos, LongSupplier clock,
            R3RootFamilyAuditCollector audit, V2CollectionAuditRecorder collectionAudit,
            CompetitiveOpportunityCatalog opportunityCatalog, CompetitiveAuditAccumulator competitiveAudit) {
        stats.expandedStates++;
        MutableDepthStats depth = depth(depths, current.strategicDecisionCount());
        depth.statesExpanded++;
        TransitionBatch batch = transitions(state, current, catalog, terminalEvaluator, opportunityCatalog,
                competitiveAudit);
        if (collectionAudit != null) {
            JointPartialStateMetrics currentMetrics = metrics(current, catalog, state.stepBudget());
            int maxChildCollections = current.timeline().successfulCollections();
            for (Transition transition : batch.transitions()) {
                maxChildCollections = Math.max(maxChildCollections,
                        transition.apply(state, current).timeline().successfulCollections());
            }
            collectionAudit.expansion(current, batch.reachableTargets(), batch.candidatesBeforeTruncation(),
                    batch.candidatesAfterTopK(), batch.topKTruncations(), batch.transitions().size(),
                    batch.transitions().stream().noneMatch(transition -> transition instanceof MoveTransition),
                    currentMetrics.remainingCollectionPotentialUpperBound() == 0, maxChildCollections,
                    batch.targetMenu());
        }
        depth.candidateAttempts += batch.candidateAttempts();
        depth.infeasibleRoutes += batch.infeasibleRoutes();
        depth.topKTruncations += batch.topKTruncations();
        int retained = Math.min(config.maxChildrenPerState(), batch.transitions().size());
        if (audit != null) audit.noRefuelExpansion(current.exactKey(), batch.reachableTargets(), retained);
        for (int index = 0; index < retained; index++) {
            if (deadlineReached(deadlineNanos, clock)) {
                stats.deadline("JOINT_BEAM");
                break;
            }
            Transition transition = batch.transitions().get(index);
            JointTeamSearchState child = transition.apply(state, current);
            if (audit != null) audit.inherit(current.exactKey(), child.exactKey());
            stats.generatedChildren++;
            depth.generatedChildren++;
            if (transition instanceof MoveTransition) {
                stats.collectChildrenGenerated++;
                depth.collectChildrenGenerated++;
            } else {
                stats.stopChildrenGenerated++;
                depth.stopChildrenGenerated++;
            }
            admit(state, child, frontier, seen, dominance, terminalEvaluator, stageACandidates,
                    immediatelyEvaluated, catalog, stats, depths, deadlineNanos, clock, audit, collectionAudit,
                    collectionAudit == null ? null : collectionAudit.idFor(current),
                    transition.expandedAgent().value(), batch.candidatesAfterTopK(), retained);
        }
    }

    private List<EvaluatedTerminal> evaluateTerminals(JointTerminalEvaluator terminalEvaluator,
            List<JointTerminalEvaluator.StageATerminalCandidate> stageACandidates,
            List<EvaluatedTerminal> immediatelyEvaluated, MutableStats stats, long deadlineNanos, LongSupplier clock,
            int terminalShortlistLimit, R3RootFamilyAuditCollector audit) {
        if (!usesDeferredTerminalEvaluation()) {
            return new ArrayList<>(immediatelyEvaluated);
        }
        List<JointTerminalEvaluator.StageATerminalCandidate> ranked = new ArrayList<>(stageACandidates);
        ranked.sort(JointTerminalEvaluator::compareStageA);
        Map<String, JointTerminalEvaluator.StageATerminalCandidate> uniquePlans = new LinkedHashMap<>();
        for (JointTerminalEvaluator.StageATerminalCandidate candidate : ranked) {
            uniquePlans.putIfAbsent(candidate.base().deterministicSignature(), candidate);
        }
        stats.stageBDuplicateCandidatesSkipped = ranked.size() - uniquePlans.size();
        List<JointTerminalEvaluator.StageATerminalCandidate> shortlist;
        boolean fairAllocation = Boolean.parseBoolean(System.getProperty("procon.v2.stage_b_fair_allocation",
                System.getenv("PROCON_V2_STAGE_B_FAIR_ALLOCATION") != null ? System.getenv("PROCON_V2_STAGE_B_FAIR_ALLOCATION") : "false"));
        if (!fairAllocation) {
            shortlist = new ArrayList<>(uniquePlans.values());
            if (shortlist.size() > terminalShortlistLimit) shortlist.subList(terminalShortlistLimit, shortlist.size()).clear();
            int limit = config.fullTerminalEvaluationLimit();
            if (limit > 0 && shortlist.size() > limit) shortlist.subList(limit, shortlist.size()).clear();
        } else {
            int limit = config.fullTerminalEvaluationLimit() > 0 ? config.fullTerminalEvaluationLimit() : terminalShortlistLimit;
            if (limit <= 0) limit = terminalShortlistLimit;
            Map<Integer, List<JointTerminalEvaluator.StageATerminalCandidate>> byFamily = new LinkedHashMap<>();
            for (JointTerminalEvaluator.StageATerminalCandidate candidate : uniquePlans.values()) {
                byFamily.computeIfAbsent(candidate.rootFamily(), k -> new ArrayList<>()).add(candidate);
            }
            shortlist = new ArrayList<>();
            Set<String> admitted = new HashSet<>();
            int quota = Math.max(2, limit / (byFamily.isEmpty() ? 1 : byFamily.size() * 2));
            for (List<JointTerminalEvaluator.StageATerminalCandidate> familyList : byFamily.values()) {
                int toTake = Math.min(quota, familyList.size());
                for (int i = 0; i < toTake && shortlist.size() < limit; i++) {
                    var c = familyList.get(i);
                    if (admitted.add(c.base().deterministicSignature())) {
                        shortlist.add(c);
                    }
                }
            }
            for (JointTerminalEvaluator.StageATerminalCandidate candidate : uniquePlans.values()) {
                if (shortlist.size() >= limit) break;
                if (admitted.add(candidate.base().deterministicSignature())) {
                    shortlist.add(candidate);
                }
            }
        }
        stats.stageBRequested = shortlist.size();
        List<EvaluatedTerminal> result = new ArrayList<>();
        for (JointTerminalEvaluator.StageATerminalCandidate candidate : shortlist) {
            if (deadlineReached(deadlineNanos, clock)) {
                stats.deadline("STAGE_B");
                break;
            }
            long stageBStartedNanos = clock.getAsLong();
            JointTerminalEvaluation evaluation = terminalEvaluator.evaluateStageB(candidate);
            result.add(new EvaluatedTerminal(candidate, evaluation));
            if (audit != null) audit.normalEvaluation(candidate, evaluation);
            stats.stageBTerminalMillis += elapsedMillis(stageBStartedNanos, clock);
            stats.terminalPlansEvaluated++;
            stats.coupledTerminalEvaluations++;
        }
        return result;
    }

    /** Runs only when explicitly requested by a test/benchmark configuration. */
    private StageBRecallAudit stageBRecallAudit(JointTerminalEvaluator evaluator,
            List<JointTerminalEvaluator.StageATerminalCandidate> stageACandidates,
            List<EvaluatedTerminal> productionEvaluated, long deadlineNanos, LongSupplier clock) {
        if (config.stageBRecallAuditMode() == StageBRecallAuditMode.OFF) {
            return StageBRecallAudit.empty(StageBRecallAuditMode.OFF);
        }
        List<JointTerminalEvaluator.StageATerminalCandidate> ranked = new ArrayList<>(stageACandidates);
        ranked.sort(JointTerminalEvaluator::compareStageA);
        Map<String, JointTerminalEvaluator.StageATerminalCandidate> unique = new LinkedHashMap<>();
        for (JointTerminalEvaluator.StageATerminalCandidate candidate : ranked) {
            unique.putIfAbsent(candidate.base().deterministicSignature(), candidate);
        }
        if (unique.isEmpty() && !productionEvaluated.isEmpty()) {
            unique.put(productionEvaluated.getFirst().candidate().base().deterministicSignature(),
                    productionEvaluated.getFirst().candidate());
        }
        Map<String, Integer> stageARanks = new LinkedHashMap<>();
        int rank = 1;
        for (String signature : unique.keySet()) stageARanks.put(signature, rank++);
        Map<String, JointTerminalEvaluation> evaluations = new LinkedHashMap<>();
        for (EvaluatedTerminal terminal : productionEvaluated) {
            evaluations.putIfAbsent(terminal.candidate().base().deterministicSignature(), terminal.evaluation());
        }
        Set<String> productionSignatures = new LinkedHashSet<>(evaluations.keySet());
        long started = clock.getAsLong();
        boolean complete = true;
        for (JointTerminalEvaluator.StageATerminalCandidate candidate : unique.values()) {
            String signature = candidate.base().deterministicSignature();
            if (evaluations.containsKey(signature)) continue;
            if (deadlineReached(deadlineNanos, clock)) {
                complete = false;
                break;
            }
            evaluations.put(signature, evaluator.evaluateStageB(candidate));
        }
        List<StageBRecallTerminal> terminals = new ArrayList<>();
        int terminalId = 1;
        for (JointTerminalEvaluator.StageATerminalCandidate candidate : unique.values()) {
            String signature = candidate.base().deterministicSignature();
            JointTerminalEvaluation evaluation = evaluations.get(signature);
            terminals.add(recallTerminal(terminalId++, candidate, evaluation,
                    stageARanks.get(signature), productionSignatures.contains(signature)));
        }
        Map<String, StageBRecallTerminal> terminalBySignature = new LinkedHashMap<>();
        terminals.forEach(value -> terminalBySignature.put(value.physicalSignature(), value));
        List<StageBRecallTerminal> production = productionEvaluated.stream()
                .sorted(EvaluatedTerminal.PREFERENCE)
                .map(value -> terminalBySignature.get(value.candidate().base().deterministicSignature()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<StageBRecallTerminal> oracle = unique.values().stream()
                .filter(candidate -> evaluations.containsKey(candidate.base().deterministicSignature()))
                .map(candidate -> new EvaluatedTerminal(candidate,
                        evaluations.get(candidate.base().deterministicSignature())))
                .sorted(EvaluatedTerminal.PREFERENCE)
                .map(value -> terminalBySignature.get(value.candidate().base().deterministicSignature()))
                .filter(java.util.Objects::nonNull).toList();
        StageBRecallTerminal productionWinner = production.isEmpty() ? terminals.getFirst() : production.getFirst();
        StageBRecallTerminal exhaustiveWinner = oracle.isEmpty() ? productionWinner : oracle.getFirst();
        int k16 = config.fullTerminalEvaluationLimit() > 0 ? config.fullTerminalEvaluationLimit()
                : production.size();
        int top1 = recallCount(oracle, productionSignatures, 1);
        int top3 = recallCount(oracle, productionSignatures, 3);
        int top5 = recallCount(oracle, productionSignatures, 5);
        return StageBRecallAuditSupport.build(config.stageBRecallAuditMode(), terminals, oracle, productionWinner,
                exhaustiveWinner, production.size(), k16, oracle.size(),
                exhaustiveWinner.hybridMarginScore4() == null || productionWinner.hybridMarginScore4() == null
                        ? 0 : exhaustiveWinner.hybridMarginScore4() - productionWinner.hybridMarginScore4(),
                exhaustiveWinner.ownSemiCollections() - productionWinner.ownSemiCollections(),
                exhaustiveWinner.coupledOwnCollections() == null || productionWinner.coupledOwnCollections() == null
                        ? 0 : exhaustiveWinner.coupledOwnCollections() - productionWinner.coupledOwnCollections(),
                top1, top3, top5, complete, elapsedMillis(started, clock));
    }

    private static StageBRecallTerminal recallTerminal(int id,
            JointTerminalEvaluator.StageATerminalCandidate candidate, JointTerminalEvaluation evaluation,
            int stageARank, boolean productionSelected) {
        var base = candidate.base();
        var semi = candidate.semi();
        var hybrid = evaluation == null ? null : evaluation.hybrid();
        String firstTarget = candidate.simulation().events().stream()
                .filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .collect(java.util.stream.Collectors.groupingBy(event -> event.agentId().value(),
                        java.util.TreeMap::new, java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.minBy(Comparator.comparingInt(UdonCollectedEvent::step)),
                                value -> value.map(event -> Integer.toString(event.position().value())).orElse("-"))))
                .entrySet().stream().map(value -> value.getKey() + "@" + value.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        String endPositions = candidate.simulation().finalAgents().stream()
                .filter(value -> value.kind() == AgentKind.PATROL)
                .map(value -> value.id().value() + "@" + value.position().value())
                .sorted().collect(java.util.stream.Collectors.joining(","));
        return new StageBRecallTerminal(id, base.deterministicSignature(), candidate.rootFamily(),
                candidate.supportServiceCount(), semi.semiCommitmentRealizableBrandCount(),
                semi.semiCommitmentRealizableCollections(), candidate.strategicDecisionCount(),
                base.movementSteps(), stageARank, base.teamBrandCount(), base.udonTotal(), base.udonTotal(),
                base.activePatrolCount(), base.remainingFuelTotal(), base.movementSteps(),
                firstTarget, endPositions,
                hybrid == null ? null : hybrid.coupledOwnCollections(),
                hybrid == null ? null : hybrid.opponentBaselineCollections(),
                hybrid == null ? null : hybrid.coupledOpponentCollections(),
                hybrid == null ? null : hybrid.hybridOwnScore4(),
                hybrid == null ? null : hybrid.hybridOpponentScore4(),
                hybrid == null ? null : hybrid.hybridMarginScore4(), productionSelected);
    }

    private static int recallCount(List<StageBRecallTerminal> oracle, Set<String> production, int limit) {
        return (int) oracle.stream().limit(limit).filter(value -> production.contains(value.physicalSignature())).count();
    }

    private static boolean deadlineReached(long deadlineNanos, LongSupplier clock) {
        return clock.getAsLong() >= deadlineNanos;
    }

    private void admit(DayState state, JointTeamSearchState candidate, List<JointTeamSearchState> frontier,
            Map<String, JointTeamSearchState> seen, DominanceRegistry dominance, JointTerminalEvaluator terminalEvaluator,
            List<JointTerminalEvaluator.StageATerminalCandidate> stageACandidates,
            List<EvaluatedTerminal> immediatelyEvaluated, JointRouteCatalog catalog,
            MutableStats stats, Map<Integer, MutableDepthStats> depths, long deadlineNanos, LongSupplier clock,
            R3RootFamilyAuditCollector audit, V2CollectionAuditRecorder collectionAudit,
            String parentStateId, int agentExpanded, int nextTargetsConsidered, int generatedChildren) {
        MutableDepthStats depth = depth(depths, candidate.strategicDecisionCount());
        stats.maxDepthReached = Math.max(stats.maxDepthReached, candidate.strategicDecisionCount());
        depth.statesConsidered++;
        JointPartialStateMetrics metrics = metrics(candidate, catalog, state.stepBudget());
        depth.observe(metrics);
        if (collectionAudit != null) collectionAudit.candidate(candidate, parentStateId, agentExpanded,
                nextTargetsConsidered, generatedChildren, metrics);
        JointTeamSearchState retained = seen.get(candidate.exactKey());
        if (retained != null) {
            if (collectionAudit != null) collectionAudit.rejected(candidate, "DEDUP", collectionAudit.idFor(retained));
            stats.duplicateStatesRejected++; depth.duplicatesRejected++; return;
        }
        seen.put(candidate.exactKey(), candidate);
        if (collectionAudit != null) collectionAudit.admitted(candidate, true);
        if (config.searchPolicy().r2SafeDominance() && !dominance.accept(candidate, frontier)) {
            if (collectionAudit != null) collectionAudit.rejected(candidate, "DOMINANCE", "");
            stats.dominatedStatesRejected++; depth.dominanceRejected++; return;
        }
        stats.uniqueStates++; depth.uniqueChildren++; stats.terminalStates++;
        if (hasActivePatrol(candidate)) {
            stats.continuingTerminalCandidates++;
        } else {
            stats.fullyStoppedTerminalCandidates++;
        }
        if (deadlineReached(deadlineNanos, clock)) {
            if (collectionAudit != null) collectionAudit.rejected(candidate, "DEADLINE_STAGE_A", "");
            stats.deadline("STAGE_A");
            return;
        }
        long stageAStartedNanos = clock.getAsLong();
        int rootFamily = candidate.refuelRoot().map(root -> root.patrolSupports().size()).orElse(0);
        String rootSignature = candidate.refuelRoot().map(JointTeamSearchState.RefuelRootSchedule::signature)
                .orElse("NO_REFUEL");
        terminalEvaluator.evaluateStageA(candidate.completePlan(state), candidate.strategicDecisionCount(),
                rootFamily, rootSignature)
                .ifPresent(stage -> {
                    stageACandidates.add(stage);
                    if (audit != null) audit.terminal(candidate.exactKey(), stage);
                    if (collectionAudit != null) collectionAudit.terminal(candidate, stage);
                    stats.stageATerminalEvaluations++;
                    depth.terminalCandidates++;
                    if (!usesDeferredTerminalEvaluation()) {
                        long stageBStartedNanos = clock.getAsLong();
                        JointTerminalEvaluation evaluation = terminalEvaluator.evaluateStageB(stage);
                        immediatelyEvaluated.add(new EvaluatedTerminal(stage, evaluation));
                        if (audit != null) audit.normalEvaluation(stage, evaluation);
                        stats.stageBTerminalMillis += elapsedMillis(stageBStartedNanos, clock);
                        stats.terminalPlansEvaluated++;
                        stats.coupledTerminalEvaluations++;
                    }
                });
        stats.stageATerminalMillis += elapsedMillis(stageAStartedNanos, clock);
        if (hasActivePatrol(candidate)) frontier.add(candidate);
    }

    private List<Optional<JointTeamSearchState.RefuelRootSchedule>> refuelRoots(DayState state) {
        List<Optional<JointTeamSearchState.RefuelRootSchedule>> roots = new ArrayList<>();
        roots.add(Optional.empty());
        int capacity = state.matchData().patrolFuelCapacity().value();
        for (AgentState refuel : state.agents()) if (refuel.kind() == AgentKind.REFUEL) {
            for (AgentState patrol : state.agents()) if (patrol.kind() == AgentKind.PATROL
                    && ((FiniteFuel) patrol.fuel()).amount() < capacity) {
                refuelRouteFinder.find(state, refuel, patrol.position()).ifPresent(route -> {
                    int arrival = Math.max(1, route.stepsUsed());
                    if (arrival < state.stepBudget()) roots.add(Optional.of(new JointTeamSearchState.RefuelRootSchedule(
                            refuel.id(), patrol.id(), route, arrival)));
                });
            }
        }
        roots.sort(Comparator.comparing(root -> root.map(JointTeamSearchState.RefuelRootSchedule::signature)
                .orElse("0:NO_REFUEL")));
        return List.copyOf(roots);
    }

    private TransitionBatch transitions(DayState state, JointTeamSearchState current, JointRouteCatalog catalog,
            JointTerminalEvaluator terminalEvaluator, CompetitiveOpportunityCatalog opportunityCatalog,
            CompetitiveAuditAccumulator competitiveAudit) {
        List<Transition> moves = new ArrayList<>(), stops = new ArrayList<>();
        int attempts = 0, infeasible = 0, truncations = 0, reachableTargets = 0,
                candidatesBeforeTruncation = 0, candidatesAfterTopK = 0;
        Map<AgentId, List<Integer>> targetMenu = new LinkedHashMap<>();
        List<UdonSpot> spots = catalog.spots();
        for (JointTeamSearchState.PatrolPrefix patrol : current.patrols().values()) {
            if (patrol.stopped()) continue;
            List<MoveTransition> candidates = new ArrayList<>();
            for (UdonSpot spot : spots) {
                int stock = current.timeline().remainingStock().getOrDefault(spot.position(), 0);
                if (stock <= 0 || current.timeline().visitedBy(patrol.id()).contains(spot.position())) continue;
                attempts++;
                JointRouteCatalog.CatalogRoute route = catalog.routes(patrol.position(), spot.position()).stream()
                        .filter(value -> fits(value, patrol, state.stepBudget())).findFirst().orElse(null);
                if (route == null) { infeasible++; continue; }
                CompetitiveOpportunity opportunity = opportunityCatalog == null ? null
                        : opportunityCatalog.describe(current, patrol, spot, route);
                candidates.add(new MoveTransition(patrol.id(), spot, route,
                        !current.timeline().brands().contains(spot.brand()), stock,
                        terminalEvaluator.opponentPressureAt(spot.position()), opportunity, 0));
            }
            List<MoveTransition> targets = targetsForPolicy(candidates, current, opportunityCatalog);
            if (competitiveAudit != null && opportunityCatalog != null) {
                competitiveAudit.decision(patrol.id(), candidates.size(), targets.stream().limit(
                        config.maxNextTargetsPerAgent()).map(value -> new CompetitiveTargetChoice(
                                value.target().position(), selectionRole(value), value.opportunity())).toList());
            }
            reachableTargets += candidates.size();
            candidatesBeforeTruncation += targets.size();
            candidatesAfterTopK += Math.min(targets.size(), config.maxNextTargetsPerAgent());
            if (targets.size() > config.maxNextTargetsPerAgent()) truncations += targets.size() - config.maxNextTargetsPerAgent();
            targetMenu.put(patrol.id(), targets.stream().limit(config.maxNextTargetsPerAgent())
                    .map(value -> value.target().position().value()).toList());
            targets.stream().limit(config.maxNextTargetsPerAgent()).forEach(moves::add);
            stops.add(new StopTransition(patrol.id()));
        }
        moves.sort(Transition.PREFERENCE); stops.sort(Transition.PREFERENCE);
        int moveCapacity = Math.max(0, config.maxChildrenPerState() - stops.size());
        List<Transition> result = new ArrayList<>();
        moves.stream().limit(moveCapacity).forEach(result::add); result.addAll(stops); result.sort(Transition.PREFERENCE);
        return new TransitionBatch(List.copyOf(result), attempts, infeasible, truncations, reachableTargets,
                candidatesBeforeTruncation, candidatesAfterTopK, encodeTargetMenu(targetMenu));
    }

    private static String encodeTargetMenu(Map<AgentId, List<Integer>> targetMenu) {
        return targetMenu.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(AgentId::value)))
                .map(entry -> entry.getKey().value() + ":" + entry.getValue().stream()
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private List<MoveTransition> targetsForPolicy(List<MoveTransition> candidates,
            JointTeamSearchState current, CompetitiveOpportunityCatalog opportunityCatalog) {
        if (opportunityCatalog != null) {
            return competitiveStrategicTargets(candidates);
        }
        return config.searchPolicy().r2BranchingPolicy()
                ? r2StrategicTargets(candidates) : r1StrategicTargets(candidates);
    }

    private List<MoveTransition> competitiveStrategicTargets(List<MoveTransition> candidates) {
        List<MoveTransition> ordered = candidates.stream().sorted(MoveTransition.PREFERENCE).toList();
        Map<String, MoveTransition> menu = new LinkedHashMap<>();
        CompetitiveTargetPolicy policy = config.competitiveTargetPolicy();
        if (policy.usesRace()) {
            addRole(menu, ordered, CompetitiveSelectionRole.SECURE,
                    Comparator.comparingInt((MoveTransition v) -> v.opportunity().expectedAvailableStockAtOwnArrival()).reversed()
                            .thenComparingInt(v -> v.opportunity().raceMarginSteps()).reversed()
                            .thenComparingInt(v -> v.route().route().stepsUsed()));
        }
        if (policy.usesContinuation()) {
            addRole(menu, ordered, CompetitiveSelectionRole.CONTINUATION,
                    Comparator.comparingInt((MoveTransition v) -> v.opportunity().boundedContinuationValue()).reversed()
                            .thenComparingInt(v -> v.opportunity().bestContinuationRaceMargin()).reversed()
                            .thenComparingInt(v -> v.route().route().stepsUsed()));
        }
        addRole(menu, ordered, CompetitiveSelectionRole.MISSING_BRAND,
                Comparator.comparing(MoveTransition::missingBrand).reversed()
                        .thenComparingInt(v -> v.opportunity().expectedAvailableStockAtOwnArrival()).reversed()
                        .thenComparingInt(v -> v.route().route().stepsUsed()));
        if (policy.usesRace()) {
            addRole(menu, ordered, CompetitiveSelectionRole.CONTESTED,
                    Comparator.comparing((MoveTransition v) -> v.opportunity().contested()).reversed()
                            .thenComparingInt(v -> v.opportunity().expectedAvailableStockAtOwnArrival()).reversed()
                            .thenComparingInt(v -> v.opportunity().raceMarginSteps()));
        }
        Comparator<MoveTransition> fill = Comparator.comparingInt((MoveTransition v) ->
                v.opportunity().expectedAvailableStockAtOwnArrival()).reversed()
                .thenComparingInt(v -> v.opportunity().continuationCandidateCount()).reversed()
                .thenComparingInt(v -> v.opportunity().raceMarginSteps()).reversed()
                .thenComparing((MoveTransition v) -> v.opportunity().naturalOwner()).reversed()
                .thenComparingInt(v -> v.route().route().stepsUsed())
                .thenComparingInt(v -> v.target().position().value());
        ordered.stream().sorted(fill).forEach(value -> menu.putIfAbsent(value.signature(), value));
        int priority = menu.size();
        List<MoveTransition> result = new ArrayList<>();
        for (MoveTransition value : menu.values()) {
            result.add(value.withPortfolioPriority(priority--));
        }
        return List.copyOf(result);
    }

    private static void addRole(Map<String, MoveTransition> menu, List<MoveTransition> candidates,
            CompetitiveSelectionRole role, Comparator<MoveTransition> preference) {
        candidates.stream().filter(value -> matchesRole(value, role)).sorted(preference
                .thenComparing(MoveTransition.PREFERENCE)).findFirst()
                .ifPresent(value -> menu.putIfAbsent(value.signature(), value));
    }

    private static boolean matchesRole(MoveTransition value, CompetitiveSelectionRole role) {
        CompetitiveOpportunity opportunity = value.opportunity();
        return switch (role) {
            case SECURE -> opportunity.expectedAvailableStockAtOwnArrival() > 0
                    && opportunity.raceMarginSteps() > 0;
            case CONTINUATION -> opportunity.continuationCandidateCount() > 0;
            case MISSING_BRAND -> value.missingBrand();
            case CONTESTED -> opportunity.contested();
        };
    }

    private static CompetitiveSelectionRole selectionRole(MoveTransition value) {
        CompetitiveOpportunity opportunity = value.opportunity();
        if (opportunity.expectedAvailableStockAtOwnArrival() > 0 && opportunity.raceMarginSteps() > 0) {
            return CompetitiveSelectionRole.SECURE;
        }
        if (opportunity.continuationCandidateCount() > 0) return CompetitiveSelectionRole.CONTINUATION;
        if (value.missingBrand()) return CompetitiveSelectionRole.MISSING_BRAND;
        return CompetitiveSelectionRole.CONTESTED;
    }

    /** Recovered R1 target union and ordering. R2 kept this exact behavior; switch is control-equivalent. */
    private static List<MoveTransition> r1StrategicTargets(List<MoveTransition> candidates) {
        return strategicTargetUnion(candidates);
    }

    /** R2's current branch implementation is intentionally retained verbatim for the ablation. */
    private static List<MoveTransition> r2StrategicTargets(List<MoveTransition> candidates) {
        return strategicTargetUnion(candidates);
    }

    private static List<MoveTransition> strategicTargetUnion(List<MoveTransition> candidates) {
        List<MoveTransition> ordered = candidates.stream().sorted(MoveTransition.PREFERENCE).toList();
        Map<String, MoveTransition> menu = new LinkedHashMap<>();
        int highestStock = ordered.stream().mapToInt(MoveTransition::stock).max().orElse(0);
        // Keep one spatially distant opportunity as a bounded escape hatch for routes whose
        // useful value is only visible after another agent has claimed nearby stock.
        ordered.stream().max(Comparator.comparingInt((MoveTransition value) -> value.route().route().stepsUsed())
                .thenComparingInt(value -> value.route().route().fuelUsed())
                .thenComparingInt(value -> value.target().position().value())
                .thenComparingInt(value -> value.patrolId().value()))
                .ifPresent(value -> menu.put(value.signature(), value));
        // Preserve two distinct missing-brand opportunities in the bounded menu. A single
        // representative can hide the only route to another brand while the hard per-agent cap
        // remains unchanged.
        Set<BrandId> missingBrands = new LinkedHashSet<>();
        ordered.stream().filter(MoveTransition::missingBrand)
                .filter(value -> missingBrands.add(value.target().brand())).limit(2)
                .forEach(value -> menu.put(value.signature(), value));
        ordered.stream().filter(value -> value.stock() == highestStock).findFirst().ifPresent(value -> menu.put(value.signature(), value));
        ordered.stream().filter(value -> value.opponentPressure() > 0).findFirst().ifPresent(value -> menu.put(value.signature(), value));
        ordered.forEach(value -> menu.putIfAbsent(value.signature(), value));
        return List.copyOf(menu.values());
    }

    private void trimFrontier(List<JointTeamSearchState> frontier, JointRouteCatalog catalog, int stepBudget,
            MutableStats stats, V2CollectionAuditRecorder collectionAudit) {
        if (!FAIR_FAMILY_EXPANSION) {
            frontier.sort(statePreference(catalog, stepBudget));
            if (frontier.size() > config.beamWidth()) {
                if (collectionAudit != null) frontier.subList(config.beamWidth(), frontier.size())
                        .forEach(collectionAudit::beamPruned);
                frontier.subList(config.beamWidth(), frontier.size()).clear();
            }
        } else {
            Map<Integer, List<JointTeamSearchState>> byFamily = new LinkedHashMap<>();
            for (JointTeamSearchState s : frontier) {
                int family = s.refuelRoot().map(r -> r.patrolSupports().size()).orElse(0);
                byFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(s);
            }
            Comparator<JointTeamSearchState> pref = statePreference(catalog, stepBudget);
            for (List<JointTeamSearchState> list : byFamily.values()) {
                list.sort(pref);
            }
            int activeFamilyCount = byFamily.size();
            int familyQuota = Math.max(1, config.beamWidth() / Math.max(1, activeFamilyCount));
            List<JointTeamSearchState> preserved = new ArrayList<>();
            List<JointTeamSearchState> overflow = new ArrayList<>();
            for (List<JointTeamSearchState> list : byFamily.values()) {
                int take = Math.min(familyQuota, list.size());
                preserved.addAll(list.subList(0, take));
                if (list.size() > take) {
                    overflow.addAll(list.subList(take, list.size()));
                }
            }
            if (preserved.size() < config.beamWidth() && !overflow.isEmpty()) {
                overflow.sort(pref);
                int needed = config.beamWidth() - preserved.size();
                preserved.addAll(overflow.subList(0, Math.min(needed, overflow.size())));
            }
            preserved.sort(pref);
            frontier.clear();
            frontier.addAll(preserved);
        }
        stats.frontierPeak = Math.max(stats.frontierPeak, frontier.size());
    }

    Comparator<JointTeamSearchState> statePreference(JointRouteCatalog catalog, int stepBudget) {
        if (!config.searchPolicy().r2PartialOrdering()) {
            return r1StatePreference(catalog, stepBudget);
        }
        return Comparator.comparingInt((JointTeamSearchState value) -> value.timeline().successfulCollections()
                        + residualCapacityForPolicy(value, catalog, stepBudget)).reversed()
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) -> metrics(value, catalog, stepBudget).securedCollections()).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) -> metrics(value, catalog, stepBudget).brandsPlusReachableMissing()).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) -> metrics(value, catalog, stepBudget).securedBrands()).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) ->
                        residualCapacityForPolicy(value, catalog, stepBudget)).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) -> metrics(value, catalog, stepBudget).usableStepCapacity()).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) -> metrics(value, catalog, stepBudget).fuelFeasiblePatrolCount()).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) -> metrics(value, catalog, stepBudget).fuelFeasibleFuel()).reversed())
                .thenComparingInt(value -> metrics(value, catalog, stepBudget).stoppedUsefulPatrols())
                .thenComparingInt(value -> metrics(value, catalog, stepBudget).zeroGainCommittedLegs())
                .thenComparing(JointTeamSearchState::exactKey);
    }

    private Comparator<JointTeamSearchState> r1StatePreference(
            JointRouteCatalog catalog, int stepBudget) {
        if (FAIR_FAMILY_EXPANSION) {
            return Comparator.comparingInt((JointTeamSearchState value) -> value.timeline().brands().size()).reversed()
                    .thenComparing(Comparator.comparingInt((JointTeamSearchState value) ->
                            value.timeline().successfulCollections()).reversed())
                    .thenComparing(Comparator.comparingInt((JointTeamSearchState value) ->
                            residualCapacityForPolicy(value, catalog, stepBudget)).reversed())
                    .thenComparing(Comparator.comparingInt((JointTeamSearchState value) ->
                            remainingSteps(value, stepBudget)).reversed())
                    .thenComparing(Comparator.comparingInt(this::remainingFuel).reversed())
                    .thenComparing(JointTeamSearchState::exactKey);
        }
        return Comparator.comparingInt((JointTeamSearchState value) -> value.timeline().brands().size()).reversed()
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) ->
                        value.timeline().successfulCollections()).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) ->
                        residualCapacityForPolicy(value, catalog, stepBudget)).reversed())
                .thenComparing(Comparator.comparingInt(this::remainingFuel).reversed())
                .thenComparing(Comparator.comparingInt((JointTeamSearchState value) ->
                        remainingSteps(value, stepBudget)).reversed())
                .thenComparing(JointTeamSearchState::exactKey);
    }

    private int residualCapacityForPolicy(
            JointTeamSearchState state, JointRouteCatalog catalog, int stepBudget) {
        return config.searchPolicy().r2PotentialBound()
                ? metrics(state, catalog, stepBudget).remainingCollectionPotentialUpperBound()
                : legacyResidualReachableCapacity(state, catalog, stepBudget);
    }

    private int legacyResidualReachableCapacity(
            JointTeamSearchState state, JointRouteCatalog catalog, int stepBudget) {
        int capacity = 0;
        for (Map.Entry<Position, Integer> stock : state.timeline().remainingStock().entrySet()) {
            if (stock.getValue() <= 0) continue;
            boolean reachable = state.patrols().values().stream().anyMatch(patrol -> !patrol.stopped()
                    && !state.timeline().visitedBy(patrol.id()).contains(stock.getKey())
                    && canReach(catalog, patrol, stock.getKey(), stepBudget));
            if (reachable) capacity += stock.getValue();
        }
        return capacity;
    }

    private int remainingFuel(JointTeamSearchState state) {
        return state.patrols().values().stream().filter(patrol -> !patrol.stopped())
                .mapToInt(JointTeamSearchState.PatrolPrefix::remainingFuel).sum();
    }

    private int remainingSteps(JointTeamSearchState state, int stepBudget) {
        return state.patrols().values().stream().filter(patrol -> !patrol.stopped())
                .mapToInt(patrol -> remainingSteps(patrol, stepBudget)).sum();
    }
    private boolean usesDeferredTerminalEvaluation() {
        return config.searchPolicy().r2StopTerminalHandling()
                || config.fullTerminalEvaluationLimit() > 0;
    }
    private String policyLabel() {
        return config.searchPolicy().equals(V2SearchPolicy.R1_CONTROL) ? "R1_CONTROL"
                : config.searchPolicy().equals(V2SearchPolicy.FULL_R2) ? "FULL_R2" : "ABLATION";
    }

    JointPartialStateMetrics metrics(JointTeamSearchState state, JointRouteCatalog catalog, int stepBudget) {
        int reachableStock = 0;
        Set<BrandId> missingBrands = new LinkedHashSet<>();
        Map<AgentId, Integer> reachableSpots = new HashMap<>();
        Set<AgentId> feasiblePatrolIds = new HashSet<>();
        for (UdonSpot spot : catalog.spots()) {
            int stock = state.timeline().remainingStock().getOrDefault(spot.position(), 0);
            if (stock <= 0) continue;
            boolean any = false;
            for (JointTeamSearchState.PatrolPrefix patrol : state.patrols().values()) {
                if (patrol.stopped() || state.timeline().visitedBy(patrol.id()).contains(spot.position())
                        || !canReach(catalog, patrol, spot.position(), stepBudget)) continue;
                any = true; feasiblePatrolIds.add(patrol.id()); reachableSpots.merge(patrol.id(), 1, Integer::sum);
            }
            if (any) { reachableStock += stock; if (!state.timeline().brands().contains(spot.brand())) missingBrands.add(spot.brand()); }
        }
        int visitCapacity = 0, usableSteps = 0, feasibleFuel = 0;
        for (JointTeamSearchState.PatrolPrefix patrol : state.patrols().values()) if (feasiblePatrolIds.contains(patrol.id())) {
            int remaining = remainingSteps(patrol, stepBudget);
            visitCapacity += Math.min(reachableSpots.getOrDefault(patrol.id(), 0), remaining);
            usableSteps += remaining; feasibleFuel += patrol.remainingFuel();
        }
        int stoppedUseful = 0;
        for (JointTeamSearchState.PatrolPrefix patrol : state.patrols().values()) if (patrol.stopped()) {
            boolean useful = catalog.spots().stream().anyMatch(spot -> state.timeline().remainingStock()
                    .getOrDefault(spot.position(), 0) > 0 && !state.timeline().visitedBy(patrol.id())
                    .contains(spot.position()) && canReach(catalog, patrol, spot.position(), stepBudget));
            if (useful) stoppedUseful++;
        }
        int potential = Math.min(reachableStock, visitCapacity);
        return new JointPartialStateMetrics(state.timeline().successfulCollections(), potential,
                state.timeline().brands().size(), missingBrands.size(), potential, usableSteps,
                feasiblePatrolIds.size(), feasibleFuel, stoppedUseful, state.zeroGainCommittedLegs());
    }

    private static boolean canReach(JointRouteCatalog catalog, JointTeamSearchState.PatrolPrefix patrol,
            Position target, int stepBudget) {
        return catalog.routes(patrol.position(), target).stream().anyMatch(route -> fits(route, patrol, stepBudget));
    }
    private static boolean fits(JointRouteCatalog.CatalogRoute route, JointTeamSearchState.PatrolPrefix patrol,
            int stepBudget) { return route.route().stepsUsed() <= remainingSteps(patrol, stepBudget)
                    && route.route().fuelUsed() <= patrol.remainingFuel(); }
    private static int remainingSteps(JointTeamSearchState.PatrolPrefix patrol, int stepBudget) {
        return Math.max(0, stepBudget - patrol.elapsedSteps());
    }
    private static boolean hasActivePatrol(JointTeamSearchState state) {
        return state.patrols().values().stream().anyMatch(patrol -> !patrol.stopped());
    }
    private static MutableDepthStats depth(Map<Integer, MutableDepthStats> depths, int decisionDepth) {
        return depths.computeIfAbsent(decisionDepth, MutableDepthStats::new);
    }
    private static List<JointBeamDepthStats> depthSummaries(Map<Integer, MutableDepthStats> depths) {
        return depths.values().stream().map(MutableDepthStats::toImmutable).toList();
    }
    private static List<JointTerminalPortfolioEntry> portfolio(List<EvaluatedTerminal> evaluated) {
        List<JointTerminalPortfolioEntry> entries = new ArrayList<>();
        for (int index = 0; index < Math.min(5, evaluated.size()); index++) {
            EvaluatedTerminal terminal = evaluated.get(index); var h = terminal.evaluation().hybrid();
            entries.add(new JointTerminalPortfolioEntry(index + 1, h.ownSemiBrands(), h.ownSemiCollections(),
                    h.coupledOwnCollections(), h.opponentBaselineCollections(), h.coupledOpponentCollections(),
                    h.hybridMarginScore4(), terminal.candidate().strategicDecisionCount(),
                    terminal.evaluation().base().movementSteps(), terminal.evaluation().base().deterministicSignature()));
        }
        return List.copyOf(entries);
    }
    private static void logDepthSummaries(DayState state, List<JointBeamDepthStats> depths) {
        for (JointBeamDepthStats d : depths) log("V2_BEAM_DEPTH_SUMMARY", "day", state.day().value(), "depth",
                d.strategicDecisionDepth(), "statesConsidered", d.statesConsidered(), "expanded", d.statesExpanded(),
                "candidateAttempts", d.candidateAttempts(), "infeasibleRoutes", d.infeasibleRoutes(),
                "topKTruncations", d.topKTruncations(), "generatedChildren", d.generatedChildren(), "uniqueChildren",
                d.uniqueChildren(), "collectChildren", d.collectChildrenGenerated(), "stopChildren",
                d.stopChildrenGenerated(), "duplicates", d.duplicatesRejected(), "dominance", d.dominanceRejected(),
                "terminalCandidates", d.terminalCandidates(), "bestSecuredCollections", d.bestSecuredCollections(),
                "bestPotentialCollections", d.bestPotentialCollections());
    }
    private static void logPortfolio(DayState state, List<JointTerminalPortfolioEntry> portfolio) {
        for (JointTerminalPortfolioEntry p : portfolio) log("V2_TERMINAL_PORTFOLIO", "day", state.day().value(),
                "rank", p.rank(), "ownSemiBrands", p.ownSemiBrands(), "ownSemiCollections", p.ownSemiCollections(),
                "coupledOwnCollections", p.coupledOwnCollections(), "baselineOpponentCollections",
                p.baselineOpponentCollections(), "coupledOpponentCollections", p.coupledOpponentCollections(),
                "hybridMarginScore4", p.hybridMarginScore4(), "strategicDecisionCount", p.strategicDecisionCount(),
                "movementCommandCount", p.movementCommandCount(), "signature", p.signature());
    }
    private static void logPotentialDiscrimination(DayState state, List<JointBeamDepthStats> depths) {
        for (JointBeamDepthStats d : depths) log("V2_POTENTIAL_DISCRIMINATION_SUMMARY", "day",
                state.day().value(), "depth", d.strategicDecisionDepth(), "stateCount",
                d.statesConsidered(), "minPotential", d.minPotentialCollections(), "maxPotential",
                d.maxPotentialCollections(), "distinctPotentialValues", d.distinctPotentialValues());
    }

    private static void logCompetitiveAudit(DayState state, CompetitiveSearchAudit audit) {
        if (audit.policy() == CompetitiveTargetPolicy.CURRENT) return;
        for (CompetitiveTargetDecision decision : audit.decisions()) {
            for (CompetitiveTargetChoice choice : decision.selected()) {
                CompetitiveOpportunity opportunity = choice.opportunity();
                log("V2_COMPETITIVE_TARGET_AUDIT", "day", state.day().value(), "agent",
                        decision.agent().value(), "candidateCountBeforeCap", decision.candidateCountBeforeCap(),
                        "selectedCount", decision.selected().size(), "position", opportunity.targetPosition().value(),
                        "brand", opportunity.brand(), "ownEta", opportunity.ownEta(), "opponentEta",
                        opportunity.opponentEta(), "raceMargin", opportunity.raceMarginSteps(),
                        "expectedAvailableStock", opportunity.expectedAvailableStockAtOwnArrival(),
                        "continuationCandidateCount", opportunity.continuationCandidateCount(),
                        "bestContinuationRaceMargin", opportunity.bestContinuationRaceMargin(),
                        "continuationValue", opportunity.boundedContinuationValue(), "travelCost",
                        opportunity.travelCost(), "ownArrivalGap", opportunity.ownArrivalGap(),
                        "naturalOwner", opportunity.naturalOwner(), "stock", opportunity.stock(),
                        "missingBrand", opportunity.missingBrand(), "selectionRole", choice.selectionRole());
            }
        }
        log("V2_COLLECTION_RACE_SUMMARY", "day", state.day().value(), "policy", audit.policy(),
                "rawPlannedCollections", audit.rawPlannedCollections(), "semiCollections", audit.semiCollections(),
                "coupledOwnCollections", audit.coupledOwnCollections(), "uncontestedWon", audit.uncontestedWon(),
                "raceWon", audit.raceWon(), "stockShared", audit.stockShared(), "ties", audit.ties(),
                "lostToOpponent", audit.lostToOpponent(), "winningTargetsTotal", audit.winningTargetsTotal(),
                "winningTargetsPresentInProductionPortfolio", audit.winningTargetsPresentInProductionPortfolio(),
                "winningTargetRecall", audit.winningTargetRecall());
    }
    private static void logStageBRecallAudit(DayState state, StageBRecallAudit audit) {
        if (audit.mode() == StageBRecallAuditMode.OFF) return;
        StageBRecallTerminal production = audit.productionWinner();
        StageBRecallTerminal exhaustive = audit.exhaustiveWinner();
        log("V2_STAGE_B_RECALL_AUDIT", "dayOrFixture", state.day().value(),
                "uniquePhysicalTerminalCount", audit.uniquePhysicalTerminalCount(), "productionStageBK",
                audit.productionStageBK(), "productionEvaluatedCount", audit.productionEvaluatedCount(),
                "exhaustiveEvaluatedCount", audit.exhaustiveEvaluatedCount(),
                "productionWinnerPhysicalSignature", production.physicalSignature(),
                "productionWinnerStageARank", production.stageARank(), "productionWinnerOwnSemiCollections",
                production.ownSemiCollections(), "productionWinnerCoupledOwnCollections",
                production.coupledOwnCollections(), "productionWinnerHybridMarginScore4",
                production.hybridMarginScore4(), "exhaustiveWinnerPhysicalSignature", exhaustive.physicalSignature(),
                "exhaustiveWinnerStageARank", exhaustive.stageARank(), "exhaustiveWinnerOwnSemiCollections",
                exhaustive.ownSemiCollections(), "exhaustiveWinnerCoupledOwnCollections",
                exhaustive.coupledOwnCollections(), "exhaustiveWinnerHybridMarginScore4",
                exhaustive.hybridMarginScore4(), "exhaustiveWinnerPresentInK16",
                audit.exhaustiveWinnerPresentInK16(), "samePhysicalWinner", audit.samePhysicalWinner(),
                "hybridGapScore4", audit.hybridGapScore4(), "ownSemiGap", audit.ownSemiGap(),
                "coupledOwnGap", audit.coupledOwnGap(), "top1Recall", audit.top1Recall(),
                "top3Recall", audit.top3Recall(), "top5Recall", audit.top5Recall(),
                "exhaustiveComplete", audit.exhaustiveComplete(), "exhaustiveEvaluationMillis",
                audit.exhaustiveEvaluationMillis());
        log("V2_STAGE_A_TO_COUPLED_CORRELATION", "day", state.day().value(), "terminalCount",
                audit.uniquePhysicalTerminalCount(), "bestCoupledStageARank", audit.bestCoupledStageARank(),
                "medianStageARankOfTop5Coupled", audit.medianStageARankOfTop5Coupled(),
                "worstStageARankOfTop5Coupled", audit.worstStageARankOfTop5Coupled(), "K16Top5Coverage",
                audit.top5Recall());
        if (!audit.samePhysicalWinner()) log("V2_STAGE_B_MISSED_WINNER", "day", state.day().value(),
                "stageARank", exhaustive.stageARank(), "productionCutoffStageARank",
                audit.productionStageBK(), "semiDelta", audit.ownSemiGap(), "coupledOwnDelta",
                audit.coupledOwnGap(), "hybridDeltaScore4", audit.hybridGapScore4(), "rootFamily",
                exhaustive.rootFamily(), "supportServiceCount", exhaustive.supportServiceCount(),
                "stageABrands", exhaustive.stageABrands(), "stageACollections", exhaustive.stageACollections(),
                "stageARawCollections", exhaustive.stageARawCollections(), "stageAActivePatrols",
                exhaustive.stageAActivePatrols(), "stageARemainingFuel", exhaustive.stageARemainingFuel(),
                "stageAMovementSteps", exhaustive.stageAMovementSteps());
        log("V2_STAGE_B_DIVERSITY_AUDIT", "day", state.day().value(), "K", audit.productionStageBK(),
                "uniqueRootFamilies", audit.productionUniqueRootFamilies(), "uniqueSupportServiceCounts",
                audit.productionUniqueServiceCounts(), "uniqueFirstTargetAssignments",
                audit.productionUniqueFirstTargetAssignments(), "uniqueEndPositionVectors",
                audit.productionUniqueEndPositionVectors(), "uniquePhysicalSignatures",
                audit.productionUniquePhysicalSignatures(), "duplicateOrNearDuplicateCount",
                audit.productionDuplicateOrNearDuplicateCount());
        log("V2_STAGE_B_FAMILY_AUDIT", "day", state.day().value(), "productionFamilyCounts",
                audit.productionFamilyCounts(), "exhaustiveBestHybridByFamily", audit.exhaustiveBestHybridByFamily(),
                "productionBestHybridByFamily", audit.productionBestHybridByFamily(),
                "familyBestPresentInProduction", audit.familyBestPresentInProduction());
        log("V2_STAGE_B_K_VARIATION", "day", state.day().value(), "bestHybridAtK8", audit.k8BestHybrid(),
                "bestHybridAtK16", audit.k16BestHybrid(), "bestHybridAtK24", audit.k24BestHybrid(),
                "bestHybridAtK32", audit.k32BestHybrid(), "bestHybridExhaustive", audit.exhaustiveBestHybrid(),
                "winnerFirstAppearsAtK", audit.winnerFirstAppearsAt());
        audit.terminals().stream().filter(value -> value.hybridMarginScore4() != null).sorted(
                Comparator.comparingInt(StageBRecallTerminal::ownSemiCollections).reversed()
                        .thenComparingInt(value -> value.coupledOwnCollections() == null
                                ? Integer.MIN_VALUE : -value.coupledOwnCollections())
                        .thenComparingInt(StageBRecallTerminal::stageARank)).limit(5).forEach(value ->
                log("V2_SEMI_COUPLED_TRADEOFF_AUDIT", "day", state.day().value(), "stageARank",
                        value.stageARank(), "ownSemiCollections", value.ownSemiCollections(),
                        "coupledOwnCollections", value.coupledOwnCollections(), "hybridMarginScore4",
                        value.hybridMarginScore4(), "productionStageBSelected", value.productionStageBSelected(),
                        "signature", value.physicalSignature()));
    }

    private static void logTerminalObjectiveAudit(DayState state, TerminalObjectiveAudit audit) {
        if (audit.mode() == StageBRecallAuditMode.OFF) return;
        log("V2_TERMINAL_PARETO_AUDIT", "day", state.day().value(), "terminalCount", audit.terminalCount(),
                "paretoCount", audit.paretoCount(), "productionWinnerOnParetoFrontier",
                audit.productionWinnerOnParetoFrontier(), "productionWinnerDominated", audit.productionWinnerDominated(),
                "productionWinnerParetoRank", audit.productionWinnerParetoRank(), "selectedSignature",
                audit.productionWinnerSignature(), "exhaustiveSignature", audit.exhaustiveWinnerSignature());
        log("V2_OBJECTIVE_STABILITY_AUDIT", "day", state.day().value(), "weightConfigurationCount",
                audit.weightConfigurationCount(), "productionWinnerStability", audit.productionWinnerStability(),
                "distinctWeightWinners", audit.distinctWeightWinners(), "mostFrequentWeightWinner",
                audit.mostFrequentWeightWinner(), "weightWinnerSignatures", audit.weightWinnerSignatures(),
                "alternativeObjectiveWinners", audit.alternativeObjectiveWinners());
        log("V2_TERMINAL_SELECTION_REGRET", "day", state.day().value(), "productionCoupledMargin",
                audit.productionCoupledMargin(), "coupledOracleMargin", audit.coupledOracleMargin(),
                "selectionRegret", audit.selectionRegret(), "coupledOracleSignature",
                audit.coupledOracleSignature(), "productionOwnCalibrationError", audit.ownCalibrationError(),
                "opponentCalibrationError", audit.opponentCalibrationError(), "marginCalibrationError",
                audit.marginCalibrationError());
        audit.terminals().stream().limit(5).forEach(value -> log("V2_TERMINAL_OBJECTIVE_AUDIT", "day",
                state.day().value(), "stageARank", value.stageARank(), "signature", value.physicalSignature(),
                "rootFamily", value.rootFamily(), "supportServiceCount", value.supportServiceCount(),
                "ownSemiCollections", value.ownSemiCollections(), "coupledOwnCollections",
                value.coupledOwnCollections(), "baselineOpponentCollections", value.baselineOpponentCollections(),
                "coupledOpponentCollections", value.coupledOpponentCollections(), "hybridMarginScore4",
                value.currentHybridMarginScore4(), "coupledMargin", value.coupledMargin(),
                "productionStageBSelected", value.productionSelected()));
    }
    private static void logCollectionHighWater(DayState state, V2CollectionSearchAudit audit) {
        log("V2_COLLECTION_HIGH_WATER", "day", state.day().value(), "maxSecuredCollections",
                audit.maxSecuredCollectionsSeen(), "stateId", audit.highWaterStateId(), "depth",
                audit.highWaterDepth(), "rootFamily", audit.highWaterRootFamily(), "agentPositions",
                audit.highWaterAgentPositions(), "remainingFuel", audit.highWaterRemainingFuel(),
                "remainingStock", audit.highWaterRemainingStock(), "canStillCompleteLegally",
                audit.highWaterCanStillCompleteLegally(), "eventuallyMaterialized",
                audit.highWaterEventuallyMaterialized(), "eventualTerminalSemiCollections",
                audit.highWaterEventualTerminalSemiCollections(), "lossReason",
                audit.highWaterRejectionOrLossReason(), "targetTruncations", audit.targetTruncationCount(),
                "stopStatesExpanded", audit.stopStatesExpanded(), "higherCollectionLostToDedup",
                audit.higherCollectionStateLostToDedup(), "conservationViolations",
                audit.securedCollectionConservationViolations());
    }
    private static long elapsedMillis(long startedNanos, LongSupplier clock) {
        return Math.max(0, (clock.getAsLong() - startedNanos) / 1_000_000L);
    }
    private static void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) message.append(' ')
                .append(fields[index]).append('=').append(fields[index + 1]);
        System.out.println(message);
    }

    static final class DominanceRegistry {
        private final Map<String, List<JointTeamSearchState>> entries = new HashMap<>();
        boolean accept(JointTeamSearchState candidate, List<JointTeamSearchState> frontier) {
            List<JointTeamSearchState> family = entries.computeIfAbsent(candidate.dominanceKey(), ignored -> new ArrayList<>());
            if (family.stream().anyMatch(existing -> JointStateDominance.dominates(existing, candidate))) return false;
            List<JointTeamSearchState> displaced = family.stream()
                    .filter(existing -> JointStateDominance.dominates(candidate, existing)).toList();
            family.removeAll(displaced); frontier.removeAll(displaced); family.add(candidate); return true;
        }
    }
    private static final class MutableDepthStats {
        private final int depth; private int statesConsidered, statesExpanded, candidateAttempts, infeasibleRoutes,
                topKTruncations, generatedChildren, collectChildrenGenerated, stopChildrenGenerated,
                uniqueChildren, duplicatesRejected, dominanceRejected, terminalCandidates,
                bestSecuredCollections, bestPotentialCollections, minPotentialCollections = Integer.MAX_VALUE,
                maxPotentialCollections;
        private final Set<Integer> distinctPotentialCollections = new HashSet<>();
        private MutableDepthStats(int depth) { this.depth = depth; }
        private void observe(JointPartialStateMetrics m) { bestSecuredCollections = Math.max(bestSecuredCollections, m.securedCollections());
            bestPotentialCollections = Math.max(bestPotentialCollections, m.securedPlusPotential());
            minPotentialCollections = Math.min(minPotentialCollections, m.remainingCollectionPotentialUpperBound());
            maxPotentialCollections = Math.max(maxPotentialCollections, m.remainingCollectionPotentialUpperBound());
            distinctPotentialCollections.add(m.remainingCollectionPotentialUpperBound()); }
        private JointBeamDepthStats toImmutable() { return new JointBeamDepthStats(depth, statesConsidered, statesExpanded,
                candidateAttempts, infeasibleRoutes, topKTruncations, generatedChildren, collectChildrenGenerated,
                stopChildrenGenerated, uniqueChildren, duplicatesRejected, dominanceRejected, terminalCandidates,
                bestSecuredCollections, bestPotentialCollections,
                minPotentialCollections == Integer.MAX_VALUE ? 0 : minPotentialCollections,
                maxPotentialCollections, distinctPotentialCollections.size()); }
    }
    private static final class MutableStats {
        private final int catalogPathfindingExecutions;
        private int rootStates, expandedStates, generatedChildren, collectChildrenGenerated,
                stopChildrenGenerated, rootScheduleStates, uniqueStates, duplicateStatesRejected,
                dominatedStatesRejected, terminalStates, continuingTerminalCandidates,
                fullyStoppedTerminalCandidates, stageATerminalEvaluations, terminalPlansEvaluated,
                coupledTerminalEvaluations, stageBRequested, stageBDuplicateCandidatesSkipped,
                maxDepthReached, frontierPeak;
        private long catalogMillis, searchMillis, stageBTerminalWallMillis, stageATerminalMillis,
                stageBTerminalMillis, terminalEvaluationMillis;
        private boolean planningDeadlineTriggered;
        private String deadlinePhase = "NONE";
        private boolean budgetExhausted;
        private void deadline(String phase) {
            planningDeadlineTriggered = true;
            if (deadlinePhase.equals("NONE")) deadlinePhase = phase;
        }
        private MutableStats(int catalogPathfindingExecutions) { this.catalogPathfindingExecutions = catalogPathfindingExecutions; }
        private JointTeamBeamStats toImmutable(long planningMillis) { return new JointTeamBeamStats(rootStates, expandedStates,
                generatedChildren, collectChildrenGenerated, stopChildrenGenerated, rootScheduleStates,
                uniqueStates, duplicateStatesRejected, dominatedStatesRejected, terminalStates,
                continuingTerminalCandidates, fullyStoppedTerminalCandidates, stageATerminalEvaluations,
                terminalPlansEvaluated, coupledTerminalEvaluations, stageBRequested,
                stageBDuplicateCandidatesSkipped, maxDepthReached, frontierPeak,
                catalogPathfindingExecutions, 0, catalogMillis, searchMillis, stageBTerminalWallMillis,
                stageATerminalMillis, stageBTerminalMillis, terminalEvaluationMillis, planningMillis,
                planningDeadlineTriggered, deadlinePhase, budgetExhausted); }

    }
    private record TransitionBatch(List<Transition> transitions, int candidateAttempts, int infeasibleRoutes,
            int topKTruncations, int reachableTargets, int candidatesBeforeTruncation,
            int candidatesAfterTopK, String targetMenu) { }
    private record EvaluatedTerminal(JointTerminalEvaluator.StageATerminalCandidate candidate,
            JointTerminalEvaluation evaluation) {
        private static final Comparator<EvaluatedTerminal> PREFERENCE = (left, right) -> {
            if (left.evaluation.betterThan(right.evaluation)) return -1;
            if (right.evaluation.betterThan(left.evaluation)) return 1;
            return left.evaluation.base().deterministicSignature()
                    .compareTo(right.evaluation.base().deterministicSignature());
        };
    }
    private sealed interface Transition permits MoveTransition, StopTransition {
        Comparator<Transition> PREFERENCE = Comparator.comparingInt(Transition::rank).reversed().thenComparing(Transition::signature);
        int rank(); String signature(); AgentId expandedAgent();
        JointTeamSearchState apply(DayState state, JointTeamSearchState current);
    }
    private record MoveTransition(AgentId patrolId, UdonSpot target, JointRouteCatalog.CatalogRoute route,
            boolean missingBrand, int stock, int opponentPressure, CompetitiveOpportunity opportunity,
            int portfolioPriority) implements Transition {
        private static final Comparator<MoveTransition> PREFERENCE = STOCK_WEIGHT > 1000
                ? Comparator.comparing(MoveTransition::missingBrand).reversed()
                        .thenComparingInt(MoveTransition::stock).reversed()
                        .thenComparingInt(MoveTransition::opponentPressure).reversed()
                        .thenComparingInt(value -> value.route.route().stepsUsed()).thenComparingInt(value -> value.route.route().fuelUsed())
                        .thenComparingInt(value -> value.target.position().value()).thenComparingInt(value -> value.patrolId.value())
                : Comparator.comparing(MoveTransition::missingBrand).reversed()
                        .thenComparingInt(MoveTransition::opponentPressure).reversed().thenComparingInt(MoveTransition::stock).reversed()
                        .thenComparingInt(value -> value.route.route().stepsUsed()).thenComparingInt(value -> value.route.route().fuelUsed())
                        .thenComparingInt(value -> value.target.position().value()).thenComparingInt(value -> value.patrolId.value());
        @Override public int rank() { return (portfolioPriority > 0 ? portfolioPriority * 100_000_000 : 0)
                    + (missingBrand ? 1_000_000 : 0) + opponentPressure * 10_000 + stock * STOCK_WEIGHT
                    - route.route().stepsUsed() - route.route().fuelUsed(); }
        @Override public String signature() { return "M:" + patrolId.value() + ":" + target.position().value() + ":"
                    + route.route().stepsUsed() + ":" + route.route().fuelUsed(); }
        @Override public AgentId expandedAgent() { return patrolId; }
        MoveTransition withPortfolioPriority(int priority) {
            return new MoveTransition(patrolId, target, route, missingBrand, stock, opponentPressure,
                    opportunity, priority);
        }
        @Override public JointTeamSearchState apply(DayState state, JointTeamSearchState current) { return current.extend(state, patrolId, route); }
    }
    private record StopTransition(AgentId patrolId) implements Transition {
        @Override public int rank() { return Integer.MIN_VALUE; }
        @Override public String signature() { return "S:" + patrolId.value(); }
        @Override public AgentId expandedAgent() { return patrolId; }
        @Override public JointTeamSearchState apply(DayState state, JointTeamSearchState current) { return current.stop(state, patrolId); }
    }
}

package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.MoveStartedEvent;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.planner.DayPlanner;
import vn.ptit.procon.planner.RefuelRouteFinder;
import vn.ptit.procon.planner.Route;

/**
 * Opt-in R3 planner. It bounds support construction before simulation and delegates the unchanged
 * R1_CONTROL collection beam to V2 with a finite coupled-terminal cap and a wall-clock deadline.
 */
public final class JointTeamBeamR3Planner implements DayPlanner {
    private final JointTeamBeamR3Config config;
    private final LongSupplier clock;
    private final RefuelRouteFinder refuelRouteFinder;

    public JointTeamBeamR3Planner() { this(JointTeamBeamR3Config.defaults()); }
    public JointTeamBeamR3Planner(JointTeamBeamR3Config config) {
        this(config, System::nanoTime, new RefuelRouteFinder());
    }

    JointTeamBeamR3Planner(JointTeamBeamR3Config config, LongSupplier clock, RefuelRouteFinder finder) {
        this.config = java.util.Objects.requireNonNull(config, "R3 configuration must not be null");
        this.clock = java.util.Objects.requireNonNull(clock, "Clock must not be null");
        this.refuelRouteFinder = java.util.Objects.requireNonNull(finder, "Route finder must not be null");
    }

    @Override public TeamPlan plan(DayState state) { return planWithStats(state).plan(); }

    public JointTeamBeamR3Result planWithStats(DayState state) {
        long started = clock.getAsLong();
        long deadline = started + config.usablePlanningMillis() * 1_000_000L;
        Mutable stats = new Mutable();
        TeamPlan fallback = SafePlanFactory.waitAll(state);
        RefuelTourCatalog catalog = RefuelTourCatalog.forState(state, refuelRouteFinder);
        stats.tourCatalogPathfindingExecutions = catalog.pathfindingExecutions();
        List<SupportCandidate> valid;
        if (deadlineReached(deadline)) {
            stats.deadline("TOUR_CATALOG");
            valid = List.of();
        } else {
            valid = buildSupportCandidates(state, catalog, deadline, stats);
            if (deadlineReached(deadline)) stats.deadline("TOUR_CONSTRUCTION");
        }
        List<R3RootInput> roots = new ArrayList<>();
        roots.add(new R3RootInput(Optional.empty(), R3RootFamilyProvenance.noRefuel()));
        valid.forEach(value -> roots.add(new R3RootInput(Optional.of(value.schedule()), value.provenance())));
        stats.rootCandidates = roots.size();
        stats.rootsInitiallyAdmitted = deadlineReached(deadline) ? 0 : roots.size();
        if (!deadlineReached(deadline)) {
            valid.forEach(value -> stats.family.initialRoot(value.services()));
        }

        int beamWidth = Integer.getInteger("procon.v2.beam_width",
                System.getenv("PROCON_V2_BEAM_WIDTH") != null ? Integer.parseInt(System.getenv("PROCON_V2_BEAM_WIDTH")) : 48);
        int maxExpanded = Integer.getInteger("procon.v2.max_expanded_states",
                System.getenv("PROCON_V2_MAX_EXPANDED_STATES") != null ? Integer.parseInt(System.getenv("PROCON_V2_MAX_EXPANDED_STATES")) : 64);
        int maxChildren = Integer.getInteger("procon.v2.max_children",
                System.getenv("PROCON_V2_MAX_CHILDREN") != null ? Integer.parseInt(System.getenv("PROCON_V2_MAX_CHILDREN")) : 24);
        int maxTargets = Integer.getInteger("procon.v2.max_targets",
                System.getenv("PROCON_V2_MAX_TARGETS") != null ? Integer.parseInt(System.getenv("PROCON_V2_MAX_TARGETS")) : 4);
        JointTeamBeamConfig beamConfig = new JointTeamBeamConfig(beamWidth, maxExpanded, maxChildren, maxTargets,
                config.maxFullTerminalEvaluations(), V2SearchPolicy.R1_CONTROL,
                V2CollectionAuditMode.OFF, config.competitiveTargetPolicy(), config.stageBRecallAuditMode());
        R3RootFamilyAuditCollector auditCollector = new R3RootFamilyAuditCollector(config.rootFamilyAuditMode());
        JointTeamBeamResult beam = new JointTeamBeamPlanner(beamConfig)
                .planWithAudit(state, roots, deadline, config.terminalShortlistLimit(), clock, auditCollector, true);
        R3RootFamilyAudit audit = auditCollector.snapshot();
        stats.rootsSurvivingAfterMerge = audit.families().stream()
                .mapToInt(R3RootFamilySummary::rootsSurvivingAfterMerge).sum();
        stats.stageATerminals = beam.stats().stageATerminalEvaluations();
        stats.stageBEvaluated = beam.stats().coupledTerminalEvaluations();
        stats.stageBRequested = beam.stats().stageBRequested();
        stats.stageBSkippedForDeadline = Math.max(0, stats.stageBRequested - stats.stageBEvaluated);
        stats.beamSearchPathfindingExecutions = beam.stats().searchPathfindingExecutions();
        if (beam.stats().planningDeadlineTriggered()) stats.deadline(beam.stats().deadlinePhase());
        else if (deadlineReached(deadline)) stats.deadline("JOINT_BEAM");
        stats.incumbentSource = beam.stats().coupledTerminalEvaluations() > 0 ? "STAGE_B" : "STAGE_A";
        stats.fallbackUsed = beam.plan().actionsByAgent().equals(fallback.actionsByAgent());
        stats.terminalEvaluationWallMillis = beam.stats().stageBTerminalWallMillis();
        stats.terminalEvaluationAccumulatedMillis = beam.stats().stageATerminalAccumulatedMillis()
                + beam.stats().stageBTerminalAccumulatedMillis();
        R3RootFamilyProvenance selectedProvenance = audit.selectedProvenance();
        stats.selectedSignature = selectedProvenance.supportSkeletonSignature();
        stats.selectedPatrols = selectedProvenance.supportedPatrolIds();
        stats.selectedServiceCount = selectedProvenance.supportServiceCount();
        SupportCandidate selected = valid.stream().filter(value -> value.schedule().signature()
                .equals(selectedProvenance.supportSkeletonSignature())).findFirst().orElse(null);
        audit = audit.withSupportProductivity(supportProductivity(state, beam.plan(), selected));
        stats.wallPlanningMillis = Math.max(0, (clock.getAsLong() - started) / 1_000_000L);
        log(state, stats);
        logFamilyPipeline(state, stats.family.freeze());
        logAudit(state, audit);
        audit.noRefuelSearchAudit().ifPresent(value -> System.out.println("R3_NO_REFUEL_SEARCH_AUDIT day="
                + state.day().value() + " rootSeedCollectionCount=" + value.rootSeedCollectionCount()
                + " expandablePatrolCount=" + value.expandablePatrolCount()
                + " reachableOpportunitiesAtRoot=" + value.reachableOpportunitiesAtRoot()
                + " generatedChildrenAtFirstExpansion=" + value.generatedChildrenAtFirstExpansion()
                + " rawTerminalCandidates=" + value.rawTerminalCandidates()
                + " uniquePhysicalTerminals=" + value.uniquePhysicalTerminals()
                + " bestSemiBrands=" + value.bestSemiBrands() + " bestSemiCollections="
                + value.bestSemiCollections() + " bestHybridMarginScore4=" + value.bestHybridMarginScore4()
                + " waitOnlyTerminalExists=" + value.waitOnlyTerminalExists()
                + " waitOnlyWasBest=" + value.waitOnlyWasBest()));
        TeamPlan finalPlan = StrategicRepositioner.reposition(state, beam.plan());
        return new JointTeamBeamR3Result(finalPlan, beam, stats.freeze(), audit);
    }

    /**
     * Exports exactly the support roots this planner already retains, without planning and without
     * logging.
     *
     * <p>The root universe is produced by the unchanged {@link #buildSupportCandidates} pipeline, so no
     * generation, validation, retention or scoring rule is duplicated or altered here. Nothing outside
     * the throwaway accounting object is mutated, and no search is run.
     */
    public R3SupportRootExportSet exportRetainedSupportRoots(DayState state) {
        java.util.Objects.requireNonNull(state, "Day state must not be null");
        Mutable stats = new Mutable();
        RefuelTourCatalog catalog = RefuelTourCatalog.forState(state, refuelRouteFinder);
        stats.tourCatalogPathfindingExecutions = catalog.pathfindingExecutions();
        long deadline = clock.getAsLong() + config.usablePlanningMillis() * 1_000_000L;
        List<SupportCandidate> retained = buildSupportCandidates(state, catalog, deadline, stats);
        Map<AgentId, Position> starts = new LinkedHashMap<>();
        state.agents().forEach(agent -> starts.put(agent.id(), agent.position()));
        List<R3SupportRootExport> roots = new ArrayList<>();
        for (int index = 0; index < retained.size(); index++) {
            SupportCandidate candidate = retained.get(index);
            JointTeamSearchState.RefuelRootSchedule schedule = candidate.schedule();
            List<R3SupportRootExport.PlannedService> services = new ArrayList<>();
            schedule.patrolSupports().forEach((patrolId, support) -> services.add(
                    new R3SupportRootExport.PlannedService(patrolId.value(), support.position(),
                            support.elapsedSteps(), support.remainingFuel(), support.actions())));
            services.sort(Comparator.comparingInt(R3SupportRootExport.PlannedService::serviceStep)
                    .thenComparingInt(R3SupportRootExport.PlannedService::patrolId));
            roots.add(new R3SupportRootExport(index, schedule.signature(), schedule.refuelId(),
                    starts.getOrDefault(schedule.refuelId(), new Position(0)), schedule.refuelActions(),
                    schedule.refuelElapsedSteps(), candidate.services(), candidate.patrolIds(),
                    List.copyOf(services), R3SupportRootExport.EXISTING_R3_ROOT,
                    candidate.provenance().toString()));
        }
        return new R3SupportRootExportSet(List.copyOf(roots), stats.tourCatalogPathfindingExecutions,
                stats.partialToursGenerated, stats.skeletonsConsidered, stats.skeletonsValidated,
                stats.skeletonsValid, stats.skeletonsRetained);
    }

    private List<SupportCandidate> buildSupportCandidates(DayState state, RefuelTourCatalog catalog, long deadline,
            Mutable stats) {
        List<PartialTour> retained = new ArrayList<>();
        List<PartialTour> finalists = new ArrayList<>();
        for (AgentState refuel : state.agents()) if (refuel.kind() == AgentKind.REFUEL) {
            for (AgentState patrol : state.agents()) for (RefuelTourCatalog.PatrolMeeting meeting : catalog.meetings(patrol.id())) {
                if (retained.size() >= config.maxDepth1Candidates() || stats.partialToursGenerated >= config.maxPartialToursGenerated()
                        || deadlineReached(deadline)) break;
                PartialTour initial = PartialTour.start(refuel, patrol, meeting, catalog, state.stepBudget());
                if (initial == null) { stats.partialToursPruned++; stats.family.pruned(1); continue; }
                retained.add(initial); stats.partialToursGenerated++; stats.family.generated(1);
            }
        }
        retained.sort(PartialTour.PREFERENCE);
        if (retained.size() > config.maxPartialToursPerDepth()) retained.subList(config.maxPartialToursPerDepth(), retained.size()).clear();
        finalists.addAll(retained);
        accountDepthDiscard(retained, config.maxPartialToursPerDepth(), stats);
        for (int depth = 2; depth <= 3 && !retained.isEmpty() && !deadlineReached(deadline); depth++) {
            List<PartialTour> next = new ArrayList<>();
            for (PartialTour partial : retained) {
                List<PartialTour> extensions = partial.extensions(state, catalog);
                int extensionLimit = Math.min(config.maxExtensionsPerPartial(), extensions.size());
                stats.partialToursPruned += extensions.size() - extensionLimit;
                extensions.subList(extensionLimit, extensions.size()).forEach(value -> stats.family.pruned(value.services()));
                for (PartialTour extension : extensions.subList(0, extensionLimit)) {
                    if (stats.partialToursGenerated >= config.maxPartialToursGenerated()) break;
                    next.add(extension); stats.partialToursGenerated++; stats.family.generated(extension.services());
                }
            }
            next.sort(PartialTour.PREFERENCE);
            accountDepthDiscard(next, config.maxPartialToursPerDepth(), stats);
            retained = next; finalists.addAll(retained);
        }
        finalists.sort(PartialTour.PREFERENCE);
        if (finalists.size() > config.maxSkeletonsConsidered()) {
            finalists.subList(config.maxSkeletonsConsidered(), finalists.size())
                    .forEach(value -> stats.family.pruned(value.services()));
            finalists.subList(config.maxSkeletonsConsidered(), finalists.size()).clear();
        }
        stats.skeletonsConsidered = finalists.size();
        List<SupportCandidate> valid = new ArrayList<>();
        List<PartialTour> validationOrder = familyProbeOrder(finalists, config.maxSkeletonsValidated(), stats);
        for (PartialTour partial : validationOrder) {
            if (stats.skeletonsValidated >= config.maxSkeletonsValidated() || deadlineReached(deadline)) break;
            stats.skeletonsValidated++;
            stats.family.validated(partial.services());
            validate(state, partial).ifPresent(value -> {
                valid.add(value);
                stats.family.valid(value.services());
            });
        }
        stats.skeletonsValid = valid.size();
        List<SupportCandidate> retainedRoots = retainByFamily(valid, config.maxSkeletonsRetained(), stats);
        stats.skeletonsRetained = retainedRoots.size();
        return List.copyOf(retainedRoots);
    }

    private static void accountDepthDiscard(List<PartialTour> candidates, int limit, Mutable stats) {
        if (candidates.size() <= limit) return;
        candidates.subList(limit, candidates.size()).forEach(value -> stats.family.pruned(value.services()));
        candidates.subList(limit, candidates.size()).clear();
    }

    private static List<PartialTour> familyProbeOrder(List<PartialTour> finalists, int limit, Mutable stats) {
        List<PartialTour> ordered = new ArrayList<>();
        Set<PartialTour> selected = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int services = 1; services <= 3; services++) {
            for (PartialTour value : finalists) {
                if (value.services() == services) {
                    ordered.add(value); selected.add(value);
                    break;
                }
            }
        }
        finalists.stream().filter(value -> !selected.contains(value)).forEach(value -> {
            if (ordered.size() < limit) { ordered.add(value); selected.add(value); }
            else stats.family.pruned(value.services());
        });
        return List.copyOf(ordered);
    }

    private static List<SupportCandidate> retainByFamily(List<SupportCandidate> valid, int limit, Mutable stats) {
        List<SupportCandidate> ordered = new ArrayList<>(valid);
        ordered.sort(SupportCandidate.PREFERENCE);
        List<SupportCandidate> retained = new ArrayList<>();
        for (int services = 1; services <= 3; services++) {
            for (SupportCandidate value : ordered) {
                if (value.services() == services) {
                    retained.add(value); stats.family.retained(services); stats.family.reservationSlotsUsed++;
                    break;
                }
            }
        }
        ordered.stream().filter(value -> !retained.contains(value)).forEach(value -> {
            if (retained.size() < limit) {
                retained.add(value); stats.family.retained(value.services()); stats.family.globalFillSlotsUsed++;
            }
        });
        if (retained.size() > limit) retained.subList(limit, retained.size()).clear();
        retained.sort(SupportCandidate.PREFERENCE);
        return List.copyOf(retained);
    }

    private Optional<SupportCandidate> validate(DayState state, PartialTour partial) {
        TeamPlan plan = partial.materialize(state);
        var result = new DaySimulator().simulate(state, plan);
        if (!(result instanceof ValidDaySimulationResult valid)) return Optional.empty();
        List<RefueledEvent> events = valid.events().stream().filter(RefueledEvent.class::isInstance)
                .map(RefueledEvent.class::cast).toList();
        Map<AgentId, JointTeamSearchState.PatrolSupport> supports = new LinkedHashMap<>();
        for (ServiceEvent intended : partial.events) {
            RefueledEvent actual = events.stream().filter(event -> event.patrolId().equals(intended.patrol.id())
                    && event.position().equals(intended.meeting.position()) && event.step() == intended.serviceStep
                    && event.refuelAgents().contains(partial.refuel.id())).findFirst().orElse(null);
            if (actual == null) return Optional.empty();
            supports.put(intended.patrol.id(), new JointTeamSearchState.PatrolSupport(intended.meeting.position(),
                    intended.serviceStep, actual.after(), intended.patrolActions));
        }
        JointTeamSearchState.RefuelRootSchedule schedule = new JointTeamSearchState.RefuelRootSchedule(
                partial.refuel.id(), supports, partial.refuelActions, partial.elapsed, partial.signature());
        int blocked = (int) partial.events.stream().filter(event -> ((FiniteFuel) event.patrol.fuel()).amount()
                < state.matchData().patrolFuelCapacity().value()).count();
        return Optional.of(new SupportCandidate(schedule, blocked, partial.events.size(), partial.elapsed));
    }

    private boolean deadlineReached(long deadline) { return clock.getAsLong() >= deadline; }

    private static R3SupportProductivity supportProductivity(DayState state, TeamPlan plan, SupportCandidate selected) {
        if (selected == null) return null;
        ValidDaySimulationResult valid = (ValidDaySimulationResult) new DaySimulator().simulate(state, plan);
        Map<AgentId, JointTeamSearchState.PatrolSupport> supports = selected.schedule().patrolSupports();
        int firstService = supports.values().stream().mapToInt(JointTeamSearchState.PatrolSupport::elapsedSteps).min().orElse(0);
        int lastService = supports.values().stream().mapToInt(JointTeamSearchState.PatrolSupport::elapsedSteps).max().orElse(0);
        int before = 0, during = 0, after = 0, supportedCollections = 0;
        Map<AgentId, int[]> patrolCounts = new LinkedHashMap<>();
        supports.keySet().forEach(id -> patrolCounts.put(id, new int[2]));
        for (UdonCollectedEvent event : valid.events().stream().filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast).toList()) {
            if (event.step() < firstService) before++;
            else if (event.step() <= lastService) during++;
            else after++;
            int[] counts = patrolCounts.get(event.agentId());
            if (counts != null) {
                supportedCollections++;
                if (event.step() < supports.get(event.agentId()).elapsedSteps()) counts[0]++;
                else counts[1]++;
            }
        }
        int moveSteps = valid.events().stream().filter(MoveStartedEvent.class::isInstance).map(MoveStartedEvent.class::cast)
                .filter(event -> event.agentId().equals(selected.schedule().refuelId())).mapToInt(MoveStartedEvent::duration).sum();
        int waitSteps = selected.schedule().refuelActions().stream().filter(WaitAction.class::isInstance)
                .map(WaitAction.class::cast).mapToInt(WaitAction::steps).sum();
        List<R3SupportProductivity.PatrolProductivity> patrols = patrolCounts.entrySet().stream()
                .map(entry -> new R3SupportProductivity.PatrolProductivity(entry.getKey().value(), entry.getValue()[0],
                        entry.getValue()[1])).toList();
        return new R3SupportProductivity(selected.services(), selected.patrolIds(), before, during, after,
                supportedCollections, moveSteps, waitSteps, selected.schedule().signature(), patrols);
    }

    private static void log(DayState state, Mutable value) {
        System.out.println("R3_PORTFOLIO_SUMMARY day=" + state.day().value()
                + " partialToursGenerated=" + value.partialToursGenerated + " partialToursPruned=" + value.partialToursPruned
                + " skeletonsConsidered=" + value.skeletonsConsidered + " skeletonsValidated=" + value.skeletonsValidated
                + " skeletonsValid=" + value.skeletonsValid + " skeletonsRetained=" + value.skeletonsRetained
                + " rootCandidates=" + value.rootCandidates + " rootsInitiallyAdmitted=" + value.rootsInitiallyAdmitted
                + " rootsSurvivingMerge=" + value.rootsSurvivingAfterMerge
                + " selectedSupportServiceCount=" + value.selectedServiceCount
                + " selectedSupportedPatrols=" + value.selectedPatrols
                + " selectedSupportSkeletonSignature=" + value.selectedSignature
                + " stageATerminals=" + value.stageATerminals + " stageBRequested=" + value.stageBRequested
                + " stageBEvaluated=" + value.stageBEvaluated + " stageBSkippedForDeadline=" + value.stageBSkippedForDeadline
                + " tourCatalogPathfindingExecutions=" + value.tourCatalogPathfindingExecutions
                + " tourSearchPathfindingExecutions=0 beamSearchPathfindingExecutions=" + value.beamSearchPathfindingExecutions
                + " planningDeadlineTriggered=" + value.planningDeadlineTriggered + " deadlinePhase=" + value.deadlinePhase
                + " incumbentSource=" + value.incumbentSource + " fallbackUsed=" + value.fallbackUsed
                + " terminalEvaluationWallMillis=" + value.terminalEvaluationWallMillis
                + " terminalEvaluationAccumulatedMillis=" + value.terminalEvaluationAccumulatedMillis
                + " wallPlanningMillis=" + value.wallPlanningMillis);
    }

    private static void logAudit(DayState state, R3RootFamilyAudit audit) {
        for (R3RootFamilySummary family : audit.families()) {
            System.out.println("R3_ROOT_FAMILY_SUMMARY day=" + state.day().value()
                    + " serviceCount=" + family.serviceCount() + " available=" + family.available()
                    + " rootCandidates=" + family.rootCandidates() + " rootsInitiallyAdmitted="
                    + family.rootsInitiallyAdmitted() + " rootsSurvivingMerge=" + family.rootsSurvivingAfterMerge()
                    + " rawTerminalCandidates=" + family.rawTerminalCandidates() + " uniquePhysicalTerminals="
                    + family.uniquePhysicalTerminals() + " physicalDedupRatio=" + family.physicalDedupRatio()
                    + " bestStageAOwnSemiBrands=" + family.bestStageAOwnSemiBrands()
                    + " bestStageAOwnSemiCollections=" + family.bestStageAOwnSemiCollections()
                    + " stageBEligiblePhysicalPlans=" + family.stageBEligiblePhysicalPlans()
                    + " stageBEvaluatedForFamily=" + family.stageBEvaluatedForFamily()
                    + " bestFullyEvaluatedOwnSemiBrands=" + family.bestFullyEvaluatedOwnSemiBrands()
                    + " bestFullyEvaluatedOwnSemiCollections=" + family.bestFullyEvaluatedOwnSemiCollections()
                    + " bestFullyEvaluatedCoupledOwnCollections=" + family.bestFullyEvaluatedCoupledOwnCollections()
                    + " bestFullyEvaluatedBaselineOpponentCollections="
                    + family.bestFullyEvaluatedBaselineOpponentCollections()
                    + " bestFullyEvaluatedCoupledOpponentCollections="
                    + family.bestFullyEvaluatedCoupledOpponentCollections()
                    + " bestFullyEvaluatedHybridMarginScore4=" + family.bestFullyEvaluatedHybridMarginScore4()
                    + " bestPhysicalSignature=" + family.bestPhysicalSignature() + " supportedPatrols="
                    + family.supportedPatrols() + " supportSkeletonSignature=" + family.supportSkeletonSignature());
        }
        System.out.println("R3_ROOT_FAMILY_SELECTED day=" + state.day().value() + " selectedSupportServiceCount="
                + audit.selectedProvenance().supportServiceCount() + " selectedOwnSemiCollections="
                + audit.selectedOwnSemiCollections() + " bestNoRefuelOwnSemiCollections="
                + audit.bestNoRefuelOwnSemiCollections() + " selectedGainVsNoRefuelSemi="
                + audit.selectedGainVsNoRefuelSemi() + " selectedHybridMarginScore4="
                + audit.selectedHybridMarginScore4() + " bestNoRefuelHybridMarginScore4="
                + audit.bestNoRefuelHybridMarginScore4() + " selectedGainVsNoRefuelHybridScore4="
                + audit.selectedGainVsNoRefuelHybridScore4() + " globalRawTerminalCandidates="
                + audit.globalRawTerminalCandidates() + " globalUniquePhysicalTerminals="
                + audit.globalUniquePhysicalTerminals() + " globalPhysicalDedupRatio=" + audit.globalPhysicalDedupRatio());
        audit.liveShadowAudit().ifPresent(shadow -> logLiveShadowAudit(state, audit, shadow));
        audit.supportProductivity().ifPresent(productivity -> System.out.println("R3_SUPPORT_PRODUCTIVITY day="
                + state.day().value() + " serviceCount=" + productivity.serviceCount() + " supportedPatrols="
                + productivity.supportedPatrols() + " prefixCollections=" + productivity.collectionsDuringSupportPrefix()
                + " collectionsBeforeFirstService=" + productivity.collectionsBeforeFirstService()
                + " postSupportCollections=" + productivity.collectionsAfterLastService()
                + " totalSupportedPatrolCollections=" + productivity.totalSupportedPatrolCollections()
                + " supportMovementSteps=" + productivity.supportMovementSteps() + " supportWaitSteps="
                + productivity.supportWaitSteps() + " selectedSupportSkeletonSignature="
                + productivity.supportSkeletonSignature()));
    }

    private static void logLiveShadowAudit(DayState state, R3RootFamilyAudit audit, R3LiveFamilyAudit shadow) {
        for (R3LiveFamilyAuditFamily family : shadow.families()) {
            System.out.println("R3_LIVE_FAMILY_AUDIT day=" + state.day().value()
                    + " serviceCount=" + family.serviceCount() + " available=" + family.available()
                    + " bestStageAOwnSemiBrands=" + na(family.bestStageAOwnSemiBrands())
                    + " bestStageAOwnSemiCollections=" + na(family.bestStageAOwnSemiCollections())
                    + " shadowEvaluated=" + family.shadowEvaluated()
                    + " shadowOwnSemiBrands=" + na(family.shadowOwnSemiBrands())
                    + " shadowOwnSemiCollections=" + na(family.shadowOwnSemiCollections())
                    + " shadowCoupledOwnCollections=" + na(family.shadowCoupledOwnCollections())
                    + " shadowBaselineOpponentCollections=" + na(family.shadowBaselineOpponentCollections())
                    + " shadowCoupledOpponentCollections=" + na(family.shadowCoupledOpponentCollections())
                    + " shadowHybridMarginScore4=" + na(family.shadowHybridMarginScore4())
                    + " supportedPatrols=" + family.supportedPatrols()
                    + " supportSkeletonSignature=" + family.supportSkeletonSignature()
                    + " physicalSignature=" + family.physicalSignature()
                    + " shadowAuditSkippedForDeadline=" + family.shadowAuditSkippedForDeadline());
        }
        R3LiveFamilyAuditFamily noRefuel = shadow.families().getFirst();
        System.out.println("R3_LIVE_FAMILY_COMPARISON day=" + state.day().value()
                + " selectedServiceCount=" + audit.selectedProvenance().supportServiceCount()
                + " selectedOwnSemiCollections=" + na(audit.selectedOwnSemiCollections())
                + " selectedHybridMarginScore4=" + na(audit.selectedHybridMarginScore4())
                + " noRefuelSemi=" + na(noRefuel.shadowOwnSemiCollections())
                + " noRefuelHybridMarginScore4=" + na(noRefuel.shadowHybridMarginScore4())
                + " oneServiceSemi=" + shadowValue(shadow, 1, R3LiveFamilyAuditFamily::shadowOwnSemiCollections)
                + " oneServiceHybridMarginScore4=" + shadowValue(shadow, 1,
                        R3LiveFamilyAuditFamily::shadowHybridMarginScore4)
                + " twoServiceSemi=" + shadowValue(shadow, 2, R3LiveFamilyAuditFamily::shadowOwnSemiCollections)
                + " twoServiceHybridMarginScore4=" + shadowValue(shadow, 2,
                        R3LiveFamilyAuditFamily::shadowHybridMarginScore4)
                + " threeServiceSemi=" + shadowValue(shadow, 3, R3LiveFamilyAuditFamily::shadowOwnSemiCollections)
                + " threeServiceHybridMarginScore4=" + shadowValue(shadow, 3,
                        R3LiveFamilyAuditFamily::shadowHybridMarginScore4)
                + " selectedGainVsNoRefuelSemi=" + difference(audit.selectedOwnSemiCollections(),
                        noRefuel.shadowOwnSemiCollections())
                + " selectedGainVsNoRefuelHybridScore4=" + difference(audit.selectedHybridMarginScore4(),
                        noRefuel.shadowHybridMarginScore4())
                + " bestShadowFamilyByFrozenObjective=" + shadow.bestShadowFamilyByFrozenObjective()
                        .map(String::valueOf).orElse("NA")
                + " selectedMatchesBestShadowFamily=" + shadow.selectedMatchesBestShadowFamily()
                        .map(String::valueOf).orElse("NA")
                + " shadowEvaluationsPerformed=" + shadow.shadowEvaluationsPerformed()
                + " shadowEvaluationsSkippedForDeadline=" + shadow.shadowEvaluationsSkippedForDeadline());
    }

    private static String shadowValue(R3LiveFamilyAudit shadow, int serviceCount,
            java.util.function.Function<R3LiveFamilyAuditFamily, Integer> value) {
        return shadow.families().stream().filter(family -> family.serviceCount() == serviceCount).findFirst()
                .map(value).map(JointTeamBeamR3Planner::na).orElse("NA");
    }

    private static String difference(Integer left, Integer right) {
        return left == null || right == null ? "NA" : String.valueOf(left - right);
    }

    private static String na(Integer value) { return value == null ? "NA" : String.valueOf(value); }

    private record ServiceEvent(AgentState patrol, RefuelTourCatalog.PatrolMeeting meeting, int serviceStep,
            List<AgentAction> patrolActions) { }

    private static final class PartialTour {
        private static final Comparator<PartialTour> PREFERENCE = Comparator
                .comparingInt(PartialTour::blockedCount).reversed()
                .thenComparing(Comparator.comparingInt((PartialTour value) -> value.events.size()).reversed())
                .thenComparingInt(value -> value.elapsed)
                .thenComparing(PartialTour::signature);
        private final AgentState refuel; private final Position refuelPosition; private final int elapsed;
        private final List<AgentAction> refuelActions; private final List<ServiceEvent> events;
        private PartialTour(AgentState refuel, Position refuelPosition, int elapsed, List<AgentAction> refuelActions,
                List<ServiceEvent> events) { this.refuel = refuel; this.refuelPosition = refuelPosition; this.elapsed = elapsed;
            this.refuelActions = List.copyOf(refuelActions); this.events = List.copyOf(events); }
        static PartialTour start(AgentState refuel, AgentState patrol, RefuelTourCatalog.PatrolMeeting meeting,
                RefuelTourCatalog catalog, int budget) { return add(null, refuel, patrol, meeting, catalog, budget); }
        private static PartialTour add(PartialTour prior, AgentState refuel, AgentState patrol,
                RefuelTourCatalog.PatrolMeeting meeting, RefuelTourCatalog catalog, int budget) {
            Position from = prior == null ? refuel.position() : prior.refuelPosition;
            int elapsed = prior == null ? 0 : prior.elapsed;
            Route refuelLeg = catalog.refuelRoute(refuel.id(), from, meeting.position());
            if (refuelLeg == null || meeting.route().stepsUsed() > ((FiniteFuel) patrol.fuel()).amount()) return null;
            int service = Math.max(elapsed + refuelLeg.stepsUsed(), meeting.route().stepsUsed());
            if (service <= 0 || service >= budget) return null;
            List<AgentAction> refuelActions = new ArrayList<>(prior == null ? List.of() : prior.refuelActions);
            refuelActions.addAll(refuelLeg.toMoveActions()); appendWait(refuelActions, service - elapsed - refuelLeg.stepsUsed());
            List<AgentAction> patrolActions = new ArrayList<>(meeting.route().toMoveActions());
            appendWait(patrolActions, service - meeting.route().stepsUsed());
            List<ServiceEvent> events = new ArrayList<>(prior == null ? List.of() : prior.events);
            events.add(new ServiceEvent(patrol, meeting, service, patrolActions));
            return new PartialTour(refuel, meeting.position(), service, refuelActions, events);
        }
        List<PartialTour> extensions(DayState state, RefuelTourCatalog catalog) {
            List<PartialTour> result = new ArrayList<>();
            for (AgentState patrol : state.agents()) if (patrol.kind() == AgentKind.PATROL && events.stream()
                    .noneMatch(event -> event.patrol.id().equals(patrol.id()))) for (RefuelTourCatalog.PatrolMeeting meeting : catalog.meetings(patrol.id())) {
                PartialTour next = add(this, refuel, patrol, meeting, catalog, state.stepBudget()); if (next != null) result.add(next);
            }
            result.sort(PREFERENCE); return List.copyOf(result);
        }
        TeamPlan materialize(DayState state) {
            Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
            for (AgentState agent : state.agents()) {
                List<AgentAction> prefix = agent.id().equals(refuel.id()) ? refuelActions : events.stream()
                        .filter(event -> event.patrol.id().equals(agent.id())).map(ServiceEvent::patrolActions).findFirst().orElse(List.of());
                List<AgentAction> full = new ArrayList<>(prefix); appendWait(full, state.stepBudget() - actionSteps(state, agent.position(), full)); actions.put(agent.id(), List.copyOf(full));
            }
            return new TeamPlan(actions);
        }
        int blockedCount() { return (int) events.stream().filter(event -> ((FiniteFuel) event.patrol.fuel()).amount() == 0).count(); }
        int services() { return events.size(); }
        String signature() { return refuel.id().value() + ":" + events.stream().map(event -> event.patrol.id().value() + "@" + event.meeting.position().value() + "/" + event.serviceStep).collect(java.util.stream.Collectors.joining(",")); }
        private static int actionSteps(DayState state, Position start, List<AgentAction> actions) {
            Position cursor = start; int steps = 0;
            for (AgentAction action : actions) if (action instanceof WaitAction wait) steps += wait.steps(); else {
                var cost = vn.ptit.procon.rules.MovementRules.costFromSource(state.matchData().map(), cursor,
                        state.matchData().map().terrainAt(cursor) == vn.ptit.procon.domain.map.Terrain.ROAD ? state.roadTraffic().get(cursor) : null).orElseThrow();
                steps += cost.stepCost(); cursor = state.matchData().map().neighbor(cursor,
                        ((vn.ptit.procon.domain.action.MoveAction) action).direction()).orElseThrow();
            }
            return steps;
        }
        private static void appendWait(List<AgentAction> actions, int steps) { if (steps > 0) actions.add(new WaitAction(steps)); }
    }
    private record SupportCandidate(JointTeamSearchState.RefuelRootSchedule schedule, int blocked, int services, int finish)
            implements Comparable<SupportCandidate> {
        private static final Comparator<SupportCandidate> PREFERENCE = Comparator
                .comparingInt(SupportCandidate::blocked).reversed()
                .thenComparing(Comparator.comparingInt(SupportCandidate::services).reversed())
                .thenComparingInt(SupportCandidate::finish)
                .thenComparing(value -> value.schedule.signature());
        @Override public int compareTo(SupportCandidate other) { return PREFERENCE.compare(this, other); }
        List<Integer> patrolIds() { return schedule.patrolSupports().keySet().stream().map(AgentId::value).sorted().toList(); }
        R3RootFamilyProvenance provenance() {
            return new R3RootFamilyProvenance(services, patrolIds(), schedule.refuelId().value(), schedule.signature());
        }
    }
    private static final class Mutable {
        int partialToursGenerated, partialToursPruned, skeletonsConsidered, skeletonsValidated, skeletonsValid, skeletonsRetained;
        int rootCandidates, rootsInitiallyAdmitted, rootsSurvivingAfterMerge, stageATerminals, stageBRequested, stageBEvaluated, stageBSkippedForDeadline;
        int tourCatalogPathfindingExecutions, beamSearchPathfindingExecutions, selectedServiceCount;
        boolean planningDeadlineTriggered, fallbackUsed;
        String deadlinePhase = "NONE", incumbentSource = "NONE", selectedSignature = "NO_REFUEL"; List<Integer> selectedPatrols = List.of();
        long wallPlanningMillis, terminalEvaluationWallMillis, terminalEvaluationAccumulatedMillis;
        final FamilyPipelineMutable family = new FamilyPipelineMutable();
        void deadline(String phase) {
            planningDeadlineTriggered = true;
            if (deadlinePhase.equals("NONE")) deadlinePhase = phase;
        }
        R3PlanningStats freeze() { return new R3PlanningStats(partialToursGenerated, partialToursPruned, skeletonsConsidered,
                skeletonsValidated, skeletonsValid, skeletonsRetained, rootCandidates, rootsInitiallyAdmitted, rootsSurvivingAfterMerge,
                stageATerminals, stageBRequested, stageBEvaluated, stageBSkippedForDeadline, tourCatalogPathfindingExecutions,
                0, beamSearchPathfindingExecutions, planningDeadlineTriggered, deadlinePhase, incumbentSource, fallbackUsed,
                wallPlanningMillis, terminalEvaluationWallMillis, terminalEvaluationAccumulatedMillis,
                selectedServiceCount, selectedSignature, selectedPatrols, family.freeze()); }
    }

    private static void logFamilyPipeline(DayState state, R3SupportFamilyPipeline pipeline) {
        System.out.println("R3_SUPPORT_FAMILY_PIPELINE day=" + state.day().value()
                + " generated1Service=" + pipeline.generated1Service()
                + " generated2Service=" + pipeline.generated2Service()
                + " generated3Service=" + pipeline.generated3Service()
                + " prunedBeforeValidation1=" + pipeline.prunedBeforeValidation1()
                + " prunedBeforeValidation2=" + pipeline.prunedBeforeValidation2()
                + " prunedBeforeValidation3=" + pipeline.prunedBeforeValidation3()
                + " validated1=" + pipeline.validated1() + " validated2=" + pipeline.validated2()
                + " validated3=" + pipeline.validated3() + " valid1=" + pipeline.valid1()
                + " valid2=" + pipeline.valid2() + " valid3=" + pipeline.valid3()
                + " retained1=" + pipeline.retained1() + " retained2=" + pipeline.retained2()
                + " retained3=" + pipeline.retained3() + " initialRoots1=" + pipeline.initialRoots1()
                + " initialRoots2=" + pipeline.initialRoots2() + " initialRoots3=" + pipeline.initialRoots3()
                + " familyReservationSlotsUsed=" + pipeline.familyReservationSlotsUsed()
                + " globalFillSlotsUsed=" + pipeline.globalFillSlotsUsed()
                + " totalSupportSkeletonsRetained=" + pipeline.totalSupportSkeletonsRetained());
    }

    private static final class FamilyPipelineMutable {
        private final int[] generated = new int[4], pruned = new int[4], validated = new int[4],
                valid = new int[4], retained = new int[4], initialRoots = new int[4];
        private int reservationSlotsUsed, globalFillSlotsUsed;
        void generated(int service) { if (validFamily(service)) generated[service]++; }
        void pruned(int service) { if (validFamily(service)) pruned[service]++; }
        void validated(int service) { if (validFamily(service)) validated[service]++; }
        void valid(int service) { if (validFamily(service)) valid[service]++; }
        void retained(int service) { if (validFamily(service)) retained[service]++; }
        void initialRoot(int service) { if (validFamily(service)) initialRoots[service]++; }
        private static boolean validFamily(int service) { return service >= 1 && service <= 3; }
        R3SupportFamilyPipeline freeze() {
            return new R3SupportFamilyPipeline(generated[1], generated[2], generated[3],
                    pruned[1], pruned[2], pruned[3], validated[1], validated[2], validated[3],
                    valid[1], valid[2], valid[3], retained[1], retained[2], retained[3],
                    initialRoots[1], initialRoots[2], initialRoots[3], reservationSlotsUsed,
                    globalFillSlotsUsed, retained[1] + retained[2] + retained[3]);
        }
    }
}

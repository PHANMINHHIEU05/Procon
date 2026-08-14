package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import vn.ptit.procon.domain.action.AgentAction;
import vn.ptit.procon.domain.action.WaitAction;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.PlanValidation;
import vn.ptit.procon.engine.PlanValidator;
import vn.ptit.procon.engine.SafePlanFactory;
import vn.ptit.procon.engine.TeamPlan;

/** Deterministic single-rendezvous REFUEL coordination layered over M5 routing. */
public final class RefuelAwarePlanner implements DayPlanner {

    private static final String CANDIDATE_DIAGNOSTICS_PROPERTY =
            "procon.refuel.candidateDiagnostics";

    private final BrandAwarePatrolPlanner patrolPlanner;
    private final RefuelRouteFinder refuelRouteFinder;
    private final PlanValidator validator;
    private final DayPlanner brandAwareFallback;
    private final boolean candidateDiagnostics;

    public RefuelAwarePlanner() {
        this(new WeightedRouteFinder(), new RefuelRouteFinder(), new PlanValidator());
    }

    public RefuelAwarePlanner(
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator) {
        this(
                patrolRouteFinder,
                refuelRouteFinder,
                validator,
                Boolean.getBoolean(CANDIDATE_DIAGNOSTICS_PROPERTY));
    }

    RefuelAwarePlanner(
            WeightedRouteFinder patrolRouteFinder,
            RefuelRouteFinder refuelRouteFinder,
            PlanValidator validator,
            boolean candidateDiagnostics) {
        WeightedRouteFinder checkedPatrolFinder = Objects.requireNonNull(
                patrolRouteFinder, "PATROL route finder must not be null");
        this.patrolPlanner = new BrandAwarePatrolPlanner(checkedPatrolFinder);
        this.refuelRouteFinder = Objects.requireNonNull(
                refuelRouteFinder, "REFUEL route finder must not be null");
        this.validator = Objects.requireNonNull(validator, "Plan validator must not be null");
        this.brandAwareFallback = new BrandAwarePlanner(checkedPatrolFinder, this.validator);
        this.candidateDiagnostics = candidateDiagnostics;
    }

    @Override
    public TeamPlan plan(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        Selection selection = selectAssignment(state);
        logCandidateDiagnostics(state, selection);
        Optional<Assignment> selected = selection.selected();
        if (selected.isEmpty()) {
            log("REFUEL_NO_ASSIGN",
                    "day", state.day().value(),
                    "reason", selection.noAssignmentReason(),
                    "candidates", selection.candidates().size());
            return brandAwareFallback.plan(state);
        }

        Assignment assignment = selected.orElseThrow();
        log("REFUEL_ASSIGN",
                "day", state.day().value(),
                "refuelAgent", assignment.refuel().id().value(),
                "patrolAgent", assignment.patrol().id().value(),
                "rendezvous", assignment.patrol().position().value(),
                "arrivalStep", assignment.rendezvousStep(),
                "patrolFuelBefore", assignment.currentFuel(),
                "collectionGain", assignment.collectionGain(),
                "newBrandGain", assignment.newBrandGain());

        Map<Position, Integer> projectedStock = new LinkedHashMap<>(state.spotStock());
        Set<BrandId> teamBrands = new LinkedHashSet<>();
        Map<AgentId, List<AgentAction>> actions = new LinkedHashMap<>();
        int restoredFuel = state.matchData().patrolFuelCapacity().value();

        for (AgentState agent : state.agents()) {
            List<AgentAction> agentActions;
            if (agent.id().equals(assignment.refuel().id())) {
                agentActions = refuelActions(assignment.route(), state.stepBudget());
            } else if (agent.kind() == AgentKind.REFUEL) {
                agentActions = List.of(new WaitAction(state.stepBudget()));
            } else if (agent.id().equals(assignment.patrol().id())) {
                agentActions = patrolPlanner.plan(
                        agent,
                        state,
                        assignment.rendezvousStep(),
                        restoredFuel,
                        projectedStock,
                        teamBrands,
                        "REFUEL_AWARE").actions();
            } else {
                agentActions = patrolPlanner.plan(
                        agent,
                        state,
                        0,
                        ((FiniteFuel) agent.fuel()).amount(),
                        projectedStock,
                        teamBrands,
                        "REFUEL_AWARE").actions();
            }
            actions.put(agent.id(), agentActions);
        }

        TeamPlan coordinated = new TeamPlan(actions);
        PlanValidation validation = validator.validate(state, coordinated);
        if (validation.valid()) {
            log("REFUEL_PLAN_VALID");
            return coordinated;
        }

        TeamPlan brandAware = brandAwareFallback.plan(state);
        if (validator.validate(state, brandAware).valid()) {
            log("REFUEL_PLAN_FALLBACK",
                    "reason", validation.failure().orElseThrow(),
                    "mode", "BRAND_AWARE");
            return brandAware;
        }

        TeamPlan waitAll = SafePlanFactory.waitAll(state);
        PlanValidation waitValidation = validator.validate(state, waitAll);
        if (!waitValidation.valid()) {
            throw new IllegalStateException(
                    "Safe fallback plan rejected: " + waitValidation.failure().orElseThrow());
        }
        log("REFUEL_PLAN_FALLBACK",
                "reason", validation.failure().orElseThrow(),
                "mode", "WAIT");
        return waitAll;
    }

    private Selection selectAssignment(DayState state) {
        int capacity = state.matchData().patrolFuelCapacity().value();
        List<Assignment> candidates = new ArrayList<>();
        int refuelAgents = 0;
        int patrolsNeedingRefill = 0;
        int reachableRoutes = 0;
        for (AgentState agent : state.agents()) {
            if (agent.kind() == AgentKind.REFUEL) {
                refuelAgents++;
            } else if (((FiniteFuel) agent.fuel()).amount() < capacity) {
                patrolsNeedingRefill++;
            }
        }

        for (AgentState refuel : state.agents()) {
            if (refuel.kind() != AgentKind.REFUEL) {
                continue;
            }
            for (AgentState patrol : state.agents()) {
                if (patrol.kind() != AgentKind.PATROL) {
                    continue;
                }
                int currentFuel = ((FiniteFuel) patrol.fuel()).amount();
                if (currentFuel >= capacity) {
                    continue;
                }
                Optional<Route> possibleRoute = refuelRouteFinder.find(
                        state, refuel, patrol.position());
                if (possibleRoute.isEmpty()) {
                    continue;
                }
                reachableRoutes++;
                Route route = possibleRoute.orElseThrow();
                int rendezvousStep = Math.max(1, route.stepsUsed());
                if (rendezvousStep >= state.stepBudget()) {
                    continue;
                }

                BrandAwarePatrolPlanner.PatrolPlan withoutRefill = projectPatrol(
                        patrol, state, 0, currentFuel);
                BrandAwarePatrolPlanner.PatrolPlan afterRefill = projectPatrol(
                        patrol, state, rendezvousStep, capacity);
                int collectionGain = afterRefill.collections() - withoutRefill.collections();
                int newBrandGain = newBrandGain(afterRefill.brands(), withoutRefill.brands());
                candidates.add(new Assignment(
                        refuel,
                        patrol,
                        route,
                        rendezvousStep,
                        currentFuel,
                        withoutRefill.collections(),
                        afterRefill.collections(),
                        withoutRefill.brands().size(),
                        afterRefill.brands().size(),
                        collectionGain,
                        newBrandGain));
            }
        }

        Comparator<Assignment> preference = Comparator
                .comparingInt(Assignment::collectionGain).reversed()
                .thenComparing(Comparator.comparingInt(Assignment::newBrandGain).reversed())
                .thenComparingInt(Assignment::currentFuel)
                .thenComparingInt(assignment -> assignment.route().stepsUsed())
                .thenComparingInt(assignment -> assignment.patrol().id().value())
                .thenComparingInt(assignment -> assignment.refuel().id().value());
        Optional<Assignment> selected = candidates.stream()
                .filter(Assignment::positiveBenefit)
                .min(preference);
        String reason = selected.isPresent()
                ? null
                : noAssignmentReason(
                        refuelAgents, patrolsNeedingRefill, reachableRoutes, candidates.size());
        return new Selection(selected, candidates, reason);
    }

    private String noAssignmentReason(
            int refuelAgents,
            int patrolsNeedingRefill,
            int reachableRoutes,
            int reasonableCandidates) {
        if (patrolsNeedingRefill == 0) {
            return "NO_PATROL_NEEDS_REFILL";
        }
        if (refuelAgents == 0) {
            return "NO_REFUEL_AGENT";
        }
        if (reachableRoutes == 0) {
            return "NO_REACHABLE_RENDEZVOUS";
        }
        if (reasonableCandidates == 0) {
            return "NO_POST_REFILL_TIME";
        }
        return "NO_POSITIVE_BENEFIT";
    }

    private BrandAwarePatrolPlanner.PatrolPlan projectPatrol(
            AgentState patrol,
            DayState state,
            int initialWait,
            int fuel) {
        return patrolPlanner.plan(
                patrol,
                state,
                initialWait,
                fuel,
                new LinkedHashMap<>(state.spotStock()),
                new LinkedHashSet<>(),
                null);
    }

    private int newBrandGain(Set<BrandId> afterRefill, Set<BrandId> withoutRefill) {
        Set<BrandId> unlocked = new LinkedHashSet<>(afterRefill);
        unlocked.removeAll(withoutRefill);
        return unlocked.size();
    }

    private List<AgentAction> refuelActions(Route route, int daySteps) {
        return ActionPlanCompleter.complete(route.toMoveActions(), route.stepsUsed(), daySteps);
    }

    private void logCandidateDiagnostics(DayState state, Selection selection) {
        if (!candidateDiagnostics) {
            return;
        }
        for (Assignment candidate : selection.candidates()) {
            log("REFUEL_CANDIDATE",
                    "day", state.day().value(),
                    "refuelAgent", candidate.refuel().id().value(),
                    "patrolAgent", candidate.patrol().id().value(),
                    "patrolFuel", candidate.currentFuel(),
                    "refuelTravelSteps", candidate.route().stepsUsed(),
                    "projectedGainWithoutRefill", candidate.projectedGainWithoutRefill(),
                    "projectedGainWithRefill", candidate.projectedGainWithRefill(),
                    "projectedNewBrandsWithoutRefill", candidate.projectedNewBrandsWithoutRefill(),
                    "projectedNewBrandsWithRefill", candidate.projectedNewBrandsWithRefill(),
                    "selected", selection.selected().filter(candidate::equals).isPresent());
        }
    }

    private void log(String event, Object... fields) {
        StringBuilder message = new StringBuilder(event);
        for (int index = 0; index + 1 < fields.length; index += 2) {
            message.append(' ').append(fields[index]).append('=').append(fields[index + 1]);
        }
        System.out.println(message);
    }

    private record Assignment(
            AgentState refuel,
            AgentState patrol,
            Route route,
            int rendezvousStep,
            int currentFuel,
            int projectedGainWithoutRefill,
            int projectedGainWithRefill,
            int projectedNewBrandsWithoutRefill,
            int projectedNewBrandsWithRefill,
            int collectionGain,
            int newBrandGain) {

        private boolean positiveBenefit() {
            return projectedGainWithRefill > 0 && (collectionGain > 0 || newBrandGain > 0);
        }
    }

    private record Selection(
            Optional<Assignment> selected,
            List<Assignment> candidates,
            String noAssignmentReason) {

        private Selection {
            selected = Objects.requireNonNull(selected, "Selected assignment must not be null");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "Candidates must not be null"));
            if (selected.isEmpty()) {
                Objects.requireNonNull(noAssignmentReason, "No-assignment reason must not be null");
            }
        }
    }
}

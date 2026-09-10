package vn.ptit.procon.planner;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.simulation.ExactSimulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded portfolio: coverage, density, proximity and two-hop harvest construction. */
public final class PortfolioPlanner {
    private final AdaptivePlanner[] planners;
    private final ExactSimulator simulator = new ExactSimulator();
    private final BoundedAlnsImprover alns = new BoundedAlnsImprover();
    private Model.PlannedDay[] lastPlans;
    private int selected;
    private String selectedLabel;
    // Keep the constructor arm separate from a later ALNS label.  A repair label describes
    // the final mutation, while this value tells replay/live diagnostics which independent
    // portfolio construction supplied the incumbent that was repaired.
    private String selectedArmLabel;
    private boolean earlyFinalExit;

    public PortfolioPlanner(Model.Setup setup) {
        this(setup, AdaptivePlanner.RoleMode.AUTO);
    }

    public PortfolioPlanner(Model.Setup setup, AdaptivePlanner.RoleMode roleMode) {
        // This remains deliberately exact-shape scoped; an 8x8 match with four agents,
        // non-30-step days, or another shape still uses the established general portfolio.
        boolean p08SixAgentThirtyStep = "P08".equals(AdaptiveR3Policy.select(MatchShape.of(setup)).id())
                && setup.agentCount() == 6 && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 30);
        boolean p08FuelPaced = p08SixAgentThirtyStep
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P08_FUEL_PACED", "true"));
        // The fuel-paced-only arm won its first small holdout, but the later 25-match matrix
        // exposed two close P08 losses.  Replaying all five P08 maps with the server traffic
        // showed the full exact-validated portfolio at 92/115/97/108/104 portions versus
        // 84/113/93/108/103 for this arm alone.  Keep the isolation switch as an emergency
        // rollback, but make the non-regressing portfolio the production default.
        boolean p08FuelPacedOnly = p08FuelPaced
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P08_FUEL_PACED_ONLY", "false"));
        // P24 exact-shape promotion: across fourteen recorded P24 trajectories the reserve arm
        // improved four (+3/+2/+2/+6 portions) and regressed none; the 5x three-Hard-Bot
        // confirmation had no fault and was 5/5 rank one. A false value is a clean emergency
        // rollback, while non-canonical P24-tier maps keep the generic production portfolio.
        boolean p24FuelPaced = AdaptivePlanner.usesP24FuelPacingDefaults(setup)
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P24_FUEL_PACED", "true"));
        // A global bonus rescued a P24 loss but regressed several wins. The isolated arm keeps
        // the ordinary portfolio intact and lets exact replay reject it day-by-day; it cleared
        // a fault-free 5/5 three-Hard-Bot P24 confirmation with margins +5 through +11.
        boolean p24CapacityDiversifier = AdaptivePlanner.usesP24FuelPacingDefaults(setup)
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P24_CAPACITY_DIVERSIFIER", "true"));
        // This remains a deliberately opt-in experimental arm for other profiles. It cannot
        // change the frozen P24 shape: use PROCON_P24_FUEL_PACED for that explicit rollback.
        boolean experimentalFuelPaced = !p08SixAgentThirtyStep && !AdaptivePlanner.usesP24FuelPacingDefaults(setup)
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_EXTRA_FUEL_PACED", "false"));
        boolean largeMap = Math.max(setup.map().width(), setup.map().height()) >= 24;
        List<AdaptivePlanner> arms = largeMap
                // With the large-map route cache, each arm now finishes in hundreds of
                // milliseconds.  Keep three genuinely different harvest constructions and let
                // exact replay choose; this is a safer source of diversity than increasing one
                // beam blindly and directly addresses the remaining P32 four-portion gap.
                ? new ArrayList<>(List.of(
                        new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.STOCK_DENSITY, roleMode),
                        new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.NEAREST_FIRST, roleMode),
                        new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.CHAIN_LOOKAHEAD, roleMode)))
                : new ArrayList<>(List.of(
                        new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.COVERAGE_FIRST, roleMode),
                        new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.STOCK_DENSITY, roleMode),
                        new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.NEAREST_FIRST, roleMode),
                        new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.CHAIN_LOOKAHEAD, roleMode)));
        // The fourth construction has now cleared a five-match three-Hard-Bot P32 holdout
        // (5/5 rank one, versus 3/5 for the immediately preceding production matrix). It is
        // intentionally P32-only: a shared P24/P32 trial perturbed an already-winning P24
        // portfolio without an offsetting P24 gain. The old broad flag remains an opt-in
        // experiment, while the exact P32 promotion has a clean emergency rollback.
        boolean p32CoverageArm = "P32".equals(AdaptiveR3Policy.select(MatchShape.of(setup)).id())
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P32_EXTRA_LARGE_ARM", "true"));
        boolean broadCoverageArm = Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_EXTRA_LARGE_ARM", "false"));
        if (largeMap && (p32CoverageArm || broadCoverageArm)) {
            arms.add(new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.COVERAGE_FIRST, roleMode));
        }
        // Exact replay and the official projection prevent a fuel reserve arm from monopolising
        // a stock-rich map; it supplies an alternative only when preserving fuel pays back.
        if (p08FuelPaced || p24FuelPaced || experimentalFuelPaced) {
            arms.add(new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.FUEL_PACED, roleMode));
        }
        if (p24CapacityDiversifier) {
            arms.add(new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.CAPACITY_DENSITY, roleMode, 25));
        }
        if (p08FuelPacedOnly) {
            arms = new ArrayList<>(List.of(new AdaptivePlanner(setup, AdaptivePlanner.Heuristic.FUEL_PACED, roleMode)));
        }
        planners = arms.toArray(AdaptivePlanner[]::new);
    }

    public int[] chooseAssignment() { return planners[0].chooseAssignment(); }
    public R3PlannerProfile profile() { return planners[0].profile(); }
    public String selectedStrategy() { return selectedLabel != null ? selectedLabel : planners[selected].heuristic().name(); }
    public String selectedArmStrategy() { return selectedArmLabel != null ? selectedArmLabel : planners[selected].heuristic().name(); }
    boolean includesArm(AdaptivePlanner.Heuristic heuristic) {
        return Arrays.stream(planners).anyMatch(planner -> planner.heuristic() == heuristic);
    }
    public int selectedParetoPathsOffered() { return planners[selected].paretoPathsOffered(); }
    public boolean earlyFinalExit() { return earlyFinalExit; }

    public Model.PlannedDay plan(Model.DayState state, Deadline deadline) {
        lastPlans = new Model.PlannedDay[planners.length];
        earlyFinalExit = false;
        int allDailyStock = planners[0].setup().spots().stream().mapToInt(Model.Spot::stock).sum();
        boolean finalDay = state.day() == planners[0].setup().dayCount() - 1;
        Model.PlannedDay best = null;
        selected = 0;
        selectedLabel = null;
        selectedArmLabel = null;
        long remaining = Math.max(1, deadline.remainingMillis());
        boolean largeMap = Math.max(planners[0].setup().map().width(), planners[0].setup().map().height()) >= 24;
        // Parallel arms preserved all 17 large-map journal scores and cleared a 5x P24 plus 5x
        // P32 three-Hard-Bot holdout without a fault. Keep false as an explicit rollback switch.
        if (largeMap && planners.length >= 3
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_PARALLEL_LARGE_PORTFOLIO", "true"))) {
            return planLargeInParallel(state, deadline, allDailyStock, finalDay);
        }
        for (int i = 0; i < planners.length; i++) {
            // Large-map arms need enough warm-JVM time to reach the post-coverage auction. A
            // tiny equal slice recreated the original P32 failure even though the cached planner
            // is fast once running. Three 850ms slices still leave a safe submit margin in 3.2s.
            long minimumSlice = largeMap ? 850 : 40;
            long slice = Math.max(minimumSlice, Math.max(1, remaining / (planners.length - i)));
            slice = Math.min(slice, remaining);
            Model.PlannedDay candidate = planners[i].plan(state, new Deadline(Math.min(deadline.endNanos(),
                    System.nanoTime() + slice * 1_000_000L)));
            if (candidate != null) {
                try {
                    ExactSimulator.SimulationResult replay = simulator.simulate(planners[0].setup(), state, candidate.actions());
                    candidate = new Model.PlannedDay(candidate.day(), candidate.actions(),
                            planners[i].exactProjection(state, candidate), candidate.fingerprint());
                    lastPlans[i] = candidate;
                    if (best == null || candidate.projection().compareTo(best.projection()) > 0
                            || preferFuelPacedCandidate(i, state, candidate, best)) {
                        best = candidate;
                        selected = i;
                        selectedArmLabel = planners[i].heuristic().name();
                    }

                    // On the last day a plan harvesting every available portion cannot be
                    // improved by a later portfolio arm; ending search immediately saves the
                    // official response-time tie-break without sacrificing any future staging.
                    if (finalDay && replay.portions() == allDailyStock) {
                        earlyFinalExit = true;
                        return candidate;
                    }
                } catch (RuntimeException invalid) {
                    // An invalid candidate is never allowed to displace the valid incumbent.
                }
            }
            remaining = Math.max(1, deadline.remainingMillis());
            if (deadline.expired()) break;
        }
        if (best == null) return planners[0].plan(state, new Deadline(Math.max(System.nanoTime() + 1_000_000L, deadline.endNanos())));
        best = improveLeftoverStock(state, deadline, allDailyStock, best);
        return best;
    }

    /**
     * Large boards have independent planner arms and enough CPU cores to search them together.
     * Each arm owns its caches/memory, while exact replay and incumbent selection remain single
     * threaded below. This is opt-in until latency and score holdouts verify it on the server.
     */
    private Model.PlannedDay planLargeInParallel(Model.DayState state, Deadline deadline, int allDailyStock,
                                                  boolean finalDay) {
        long reserveForRepairAndSubmit = 240;
        long armMillis = Math.max(100, deadline.remainingMillis() - reserveForRepairAndSubmit);
        Deadline armDeadline = new Deadline(Math.min(deadline.endNanos(),
                System.nanoTime() + armMillis * 1_000_000L));
        int workers = Math.min(planners.length, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        @SuppressWarnings("unchecked")
        Future<Model.PlannedDay>[] futures = new Future[planners.length];
        for (int index = 0; index < planners.length; index++) {
            final int arm = index;
            futures[index] = executor.submit(() -> planners[arm].plan(state, armDeadline));
        }

        Model.PlannedDay best = null;
        try {
            for (int index = 0; index < planners.length; index++) {
                long waitMillis = deadline.remainingMillis() - reserveForRepairAndSubmit;
                if (waitMillis <= 0) break;
                Model.PlannedDay candidate;
                try {
                    candidate = futures[index].get(waitMillis, TimeUnit.MILLISECONDS);
                } catch (Exception unavailable) {
                    continue;
                }
                if (candidate == null) continue;
                try {
                    ExactSimulator.SimulationResult replay = simulator.simulate(planners[0].setup(), state, candidate.actions());
                    candidate = new Model.PlannedDay(candidate.day(), candidate.actions(),
                            planners[index].exactProjection(state, candidate), candidate.fingerprint());
                    lastPlans[index] = candidate;
                    if (best == null || candidate.projection().compareTo(best.projection()) > 0
                            || preferFuelPacedCandidate(index, state, candidate, best)) {
                        best = candidate;
                        selected = index;
                        selectedArmLabel = planners[index].heuristic().name();
                    }
                    if (finalDay && replay.portions() == allDailyStock) {
                        earlyFinalExit = true;
                        return candidate;
                    }
                } catch (RuntimeException invalid) {
                    // A failed speculative arm never displaces the valid incumbent.
                }
            }
        } finally {
            executor.shutdownNow();
        }
        if (best == null) {
            return planners[0].plan(state, new Deadline(Math.max(System.nanoTime() + 1_000_000L, deadline.endNanos())));
        }
        return improveLeftoverStock(state, deadline, allDailyStock, best);
    }

    /**
     * A reserve arm may spend a tiny amount of immediate score only when it materially avoids a
     * stranded final-day Patrol. The normal official projection remains the first selector.
     */
    private boolean preferFuelPacedCandidate(int plannerIndex, Model.DayState state,
                                              Model.PlannedDay candidate, Model.PlannedDay incumbent) {
        if (planners[plannerIndex].heuristic() != AdaptivePlanner.Heuristic.FUEL_PACED
                || state.day() >= planners[0].setup().dayCount() - 1) return false;
        Model.Projection next = candidate.projection(), current = incumbent.projection();
        boolean p16 = "P16".equals(planners[0].profile().id());
        int portionAllowance = configuredFuelPacedPortionAllowance(p16 ? 2 : 1);
        int minimumFuelGain = p16 ? 12 : 6;
        boolean eligible = next.globalTypes() == current.globalTypes()
                && next.dailyTypesSum() == current.dailyTypesSum()
                && next.portions() >= current.portions() - portionAllowance
                && next.minPatrolFuel() >= current.minPatrolFuel() + minimumFuelGain;
        if (!eligible) return false;
        // A P16 reserve may cost a portion today only if a bounded next-day harvest rollout
        // predicts that it recovers more than the cost. This is deliberately opt-in while we
        // calibrate it on journal replays; P08 retains its separately validated policy.
        if (!p16 || next.portions() >= current.portions()
                || !Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P16_FUEL_LOOKAHEAD", "false"))) {
            return true;
        }
        int sacrificed = current.portions() - next.portions();
        return nextDayHarvestEstimate(state, candidate) >= nextDayHarvestEstimate(state, incumbent) + sacrificed + 1;
    }

    private int nextDayHarvestEstimate(Model.DayState state, Model.PlannedDay candidate) {
        int nextDay = state.day() + 1;
        if (nextDay >= planners[0].setup().dayCount()) return 0;
        try {
            ExactSimulator.SimulationResult terminal = simulator.simulate(planners[0].setup(), state, candidate.actions());
            List<Model.AgentState> agents = new ArrayList<>(state.agents().size());
            for (int index = 0; index < state.agents().size(); index++) {
                agents.add(new Model.AgentState(state.agents().get(index).kind(), terminal.positions()[index], terminal.fuel()[index]));
            }
            // Traffic is not known until the next day state arrives. Reusing the current server
            // snapshot is deterministic and conservative; it lets the selector value terminal
            // reachability without inventing opponent-dependent data.
            Model.DayState shadow = new Model.DayState(nextDay, agents, state.traffic());
            AdaptivePlanner oracle = new AdaptivePlanner(planners[0].setup(), AdaptivePlanner.Heuristic.STOCK_DENSITY,
                    planners[0].roleMode());
            Model.PlannedDay followUp = oracle.plan(shadow, Deadline.afterMillis(60));
            return simulator.simulate(planners[0].setup(), shadow, followUp.actions()).portions();
        } catch (RuntimeException ignored) {
            // A failed estimate must not displace the ordinary exact-valid incumbent.
            return Integer.MIN_VALUE / 4;
        }
    }

    private int configuredFuelPacedPortionAllowance(int defaultAllowance) {
        String configured = System.getenv("PROCON_FUEL_PACED_PORTION_ALLOWANCE");
        if (configured == null || configured.isBlank()) return defaultAllowance;
        try {
            int parsed = Integer.parseInt(configured);
            return parsed >= 0 && parsed <= 3 ? parsed : defaultAllowance;
        } catch (NumberFormatException ignored) {
            return defaultAllowance;
        }
    }

    /**
     * Spend a small, isolated slice on destroy/repair only when the portfolio did not already
     * collect every portion available today.  Each mutation is exact-replayed and has to beat the
     * incumbent's lexicographic projection; local search therefore cannot trade away coverage,
     * portions, or fuel resilience for an attractive-looking route.
     */
    private Model.PlannedDay improveLeftoverStock(Model.DayState state, Deadline deadline, int allDailyStock,
                                                   Model.PlannedDay incumbent) {
        ExactSimulator.SimulationResult baseline;
        try {
            baseline = simulator.simulate(planners[0].setup(), state, incumbent.actions());
        } catch (RuntimeException invalid) {
            return incumbent;
        }
        if (baseline.portions() >= allDailyStock || deadline.remainingMillis() < 90) return incumbent;

        long sliceMillis = Math.min(180, Math.max(40, deadline.remainingMillis() - 40));
        Deadline localDeadline = new Deadline(Math.min(deadline.endNanos(),
                System.nanoTime() + sliceMillis * 1_000_000L));
        Model.PlannedDay best = incumbent;
        for (BoundedAlnsImprover.Candidate repair : alns.generate(planners[0].setup(), state,
                incumbent.actions(), localDeadline)) {
            if (localDeadline.expired()) break;
            try {
                // Simulation is the legality gate.  Projection is recalculated from the strategy
                // whose current multi-day memory created the incumbent.
                simulator.simulate(planners[0].setup(), state, repair.actions());
                Model.PlannedDay provisional = new Model.PlannedDay(state.day(), repair.actions(),
                        best.projection(), planners[selected].fingerprintFor(repair.actions()));
                Model.PlannedDay candidate = new Model.PlannedDay(state.day(), repair.actions(),
                        planners[selected].exactProjection(state, provisional), provisional.fingerprint());
                if (candidate.projection().compareTo(best.projection()) > 0) {
                    best = candidate;
                    selectedLabel = "BOUNDED_LNS_" + repair.operator();
                }
            } catch (RuntimeException invalid) {
                // A mutation is speculative; keep the established valid incumbent.
            }
        }
        return best;
    }

    public void observe(Model.DayState state, Model.PlannedDay plan) {
        // Every strategy must learn the plan actually accepted by the server.  Letting each
        // strategy remember its rejected hypothetical plan corrupted the official-score horizon
        // on later days and made the portfolio compare incompatible histories.
        for (AdaptivePlanner planner : planners) planner.observe(state, plan);
    }
}

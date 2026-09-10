package vn.ptit.procon.planner;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.model.Model.AgentKind;
import vn.ptit.procon.model.Model.AgentState;
import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.Spot;
import vn.ptit.procon.model.Model.Traffic;
import vn.ptit.procon.path.PathFinder;
import vn.ptit.procon.simulation.ExactSimulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class AdaptivePlanner implements DayPlanner {
    public enum Heuristic { COVERAGE_FIRST, STOCK_DENSITY, CAPACITY_DENSITY, NEAREST_FIRST, CHAIN_LOOKAHEAD, FUEL_PACED }
    public enum RoleMode { AUTO, FIVE_ONE }
    private static final int MAX_BOUNDED_PATH_CACHE_ENTRIES = 8_192;
    private final Setup setup;
    private final R3PlannerProfile profile;
    private final Heuristic heuristic;
    private final RoleMode roleMode;
    // Read once while constructing an arm: each planner instance is immutable during a match.
    // This supports offline reserve calibration without letting an environment change alter a
    // partially planned day.
    private final int fuelReservePerFutureDay;
    // Zero in production until the offline/live gate validates a profile-specific value.
    private final int capacitySlotBonus;
    private final MatchMemory memory = new MatchMemory();
    private final PathFinder paths = new PathFinder();
    private final ExactSimulator simulator = new ExactSimulator();
    // Large maps have only a small terminal set (starts and udon spots) but a very large grid.
    // Reusing their shortest paths prevents the inner auction from spending its whole deadline
    // rediscovering the same route.
    private final Map<RouteKey, PathFinder.Path> largePathCache = new HashMap<>();
    // On P08/P12/P16 the same residual source/target lookup is considered repeatedly while the
    // global auction evaluates unaffected Patrols.  This cache keeps the exact request bounds in
    // its key, so unlike the large-map full-day cache it never substitutes a fuel-infeasible path.
    private final Map<BoundedRouteKey, PathFinder.Path> boundedPathCache = new HashMap<>();
    private final Set<BoundedRouteKey> unreachableBoundedPaths = new HashSet<>();
    private final int[] spotAtPosition;
    private int[] roles;
    private int paretoExpansionsRemaining;
    private int paretoPathsOffered;

    public AdaptivePlanner(Setup setup) { this(setup, Heuristic.COVERAGE_FIRST, RoleMode.AUTO); }

    public AdaptivePlanner(Setup setup, Heuristic heuristic) { this(setup, heuristic, RoleMode.AUTO); }

    public AdaptivePlanner(Setup setup, Heuristic heuristic, RoleMode roleMode) {
        this(setup, heuristic, roleMode, -1);
    }

    /** Package-private constructor for a portfolio arm with an isolated scoring override. */
    AdaptivePlanner(Setup setup, Heuristic heuristic, RoleMode roleMode, int forcedCapacitySlotBonus) {
        this.setup = setup;
        this.profile = AdaptiveR3Policy.select(MatchShape.of(setup));
        this.heuristic = heuristic;
        this.roleMode = roleMode;
        this.fuelReservePerFutureDay = configuredFuelReserve(setup, profile);
        this.capacitySlotBonus = forcedCapacitySlotBonus >= 0
                ? Math.min(500, forcedCapacitySlotBonus) : configuredCapacitySlotBonus(setup, profile);
        this.spotAtPosition = new int[setup.map().width() * setup.map().height()];
        Arrays.fill(this.spotAtPosition, -1);
        for (Spot spot : setup.spots()) {
            if (setup.map().valid(spot.position())) this.spotAtPosition[spot.position()] = spot.id();
        }
    }

    public R3PlannerProfile profile() { return profile; }
    public Setup setup() { return setup; }
    public Heuristic heuristic() { return heuristic; }
    public RoleMode roleMode() { return roleMode; }
    public int paretoPathsOffered() { return paretoPathsOffered; }

    /**
     * This is intentionally exact-shape scoped. The reserve was validated on the factory's
     * 24x24, eight-Patrol, four-day configuration; a map merely assigned the P24 search tier
     * must retain the generic portfolio until it has its own evidence.
     */
    static boolean usesP24FuelPacingDefaults(Setup setup) {
        return "P24".equals(AdaptiveR3Policy.select(MatchShape.of(setup)).id())
                && setup.map().width() == 24 && setup.map().height() == 24
                && setup.agentCount() == 8 && setup.dayCount() == 4
                && setup.fuelLimit() == 200
                && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 100);
    }

    @Override public Model.PlannedDay plan(MatchContext context, DayState state, Deadline deadline) {
        return plan(state, deadline);
    }

    public int[] chooseAssignment() {
        if (roles != null) return roles.clone();
        if (roleMode == RoleMode.FIVE_ONE && setup.agentCount() >= 2) {
            roles = patrolAndTankerAssignment();
            return roles.clone();
        }
        // A controlled P24 long-horizon comparison on the exact factory shape found 0/5 wins
        // for all-Patrol versus 2/5 for one tanker. More importantly, the tanker retained all
        // 30 daily brand slots on every trial while all-Patrol missed coverage on three maps.
        // This remains shape-gated because a tanker is harmful on the normal four-day P24 map.
        if (usesP24LongHorizonTankerDefaults(setup)
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P24_LONG_HORIZON_TANKER", "true"))) {
            roles = patrolAndTankerAssignment();
            return roles.clone();
        }
        // A Patrol whose complete tank is no larger than one day cannot carry meaningful fuel
        // into the following day. Five low-fuel live holdouts (P08 through P32) all recovered
        // full daily coverage with one tanker, while the all-Patrol default lost all five.
        // The rule is validated on both four- and six-or-more-agent factory shapes; retain an
        // explicit rollback for an unforeseen tournament configuration.
        if (requiresLowFuelTanker(setup)
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_LOW_FUEL_TANKER", "true"))) {
            roles = patrolAndTankerAssignment();
            return roles.clone();
        }
        int n = setup.agentCount(), total = 1 << n;
        // Role-mask enumeration previously re-ran the same Dijkstra lookup for every mask.
        // Reachability depends only on the starting Patrol and the spot, so materialising this
        // tiny matrix once makes assignment effectively O(2^agents * agents * spots) rather
        // than repeatedly solving thousands of identical shortest-path problems on P24/P32.
        boolean[][] canReach = new boolean[n][setup.spots().size()];
        for (int agent = 0; agent < n; agent++) {
            for (int spot = 0; spot < setup.spots().size(); spot++) {
                canReach[agent][spot] = roughReachable(setup.startPositions()[agent],
                        setup.spots().get(spot).position(), setup.fuelLimit());
            }
        }
        long bestScore = Long.MIN_VALUE;
        int[] best = new int[n];
        Arrays.fill(best, AgentKind.PATROL.code());
        for (int mask = 0; mask < total; mask++) {
            int tankers = Integer.bitCount(mask), patrols = n - tankers;
            if (patrols == 0) continue;
            Set<String> reachableBrands = new HashSet<>();
            int reachableSlots = 0;
            for (int spotIndex = 0; spotIndex < setup.spots().size(); spotIndex++) {
                Spot spot = setup.spots().get(spotIndex);
                boolean reachable = false;
                for (int i = 0; i < n; i++) if ((mask & (1 << i)) == 0) {
                    if (canReach[i][spotIndex]) { reachable = true; break; }
                }
                if (reachable) {
                    reachableSlots += spot.stock();
                    reachableBrands.add(spot.brand());
                }
            }
            long score = reachableBrands.size() * 10_000L + reachableSlots * 50L + patrols * 25L - tankers * 40L;
            if (setup.daySteps().length > 0 && Arrays.stream(setup.daySteps()).max().orElse(0) > setup.fuelLimit()) {
                score += Math.min(tankers, 2) * 500L;
            }
            if (score > bestScore) {
                bestScore = score;
                for (int i = 0; i < n; i++) best[i] = ((mask & (1 << i)) == 0) ? 0 : 1;
            }
        }
        roles = best;
        if (shouldEvaluateTankerRollout()) {
            int[] tankerCandidate = patrolAndTankerAssignment();
            RoleRollout patrolRollout = quickRoleRollout(roles);
            RoleRollout tankerRollout = quickRoleRollout(tankerCandidate);
            // Losing a Patrol is only justified by a strict improvement in the official tuple.
            // Equal forecasts retain the simpler all-Patrol composition and its larger immediate
            // harvesting capacity.
            if (tankerRollout.compareTo(patrolRollout) > 0) roles = tankerCandidate;
        }
        return roles.clone();
    }

    /**
     * Returns the setup-only forecast used to calibrate role policies.  Assignment is committed
     * before the server publishes day traffic, so this deliberately uses clear traffic and is a
     * diagnostic/candidate-screening signal rather than an assertion about the live route.
     * It does not mutate this planner's chosen roles or match memory.
     */
    public RoleRolloutSummary roleRolloutSummary() {
        int[] allPatrol = new int[setup.agentCount()];
        Arrays.fill(allPatrol, AgentKind.PATROL.code());
        int[] oneTanker = patrolAndTankerAssignment();
        return new RoleRolloutSummary(allPatrol, quickRoleRollout(allPatrol), oneTanker,
                quickRoleRollout(oneTanker));
    }

    static boolean requiresLowFuelTanker(Setup setup) {
        if (setup.agentCount() < 4 || setup.dayCount() < 2) return false;
        int longestDay = Arrays.stream(setup.daySteps()).max().orElse(0);
        return longestDay > 0 && setup.fuelLimit() <= longestDay;
    }

    static boolean usesP24LongHorizonTankerDefaults(Setup setup) {
        return "P24".equals(AdaptiveR3Policy.select(MatchShape.of(setup)).id())
                && setup.map().width() == 24 && setup.map().height() == 24
                && setup.agentCount() == 6 && setup.dayCount() == 5 && setup.fuelLimit() == 160
                && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 80);
    }

    private boolean shouldEvaluateTankerRollout() {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_ROLE_ROLLOUT", "false"))) return false;
        if (setup.agentCount() < 2 || setup.dayCount() < 3
                || Math.max(setup.map().width(), setup.map().height()) < 12) return false;
        int totalSteps = Arrays.stream(setup.daySteps()).sum();
        return totalSteps > setup.fuelLimit();
    }

    /**
     * Setup has no traffic snapshot, so this uses clear traffic as a deterministic composition
     * screen, not as a promise about live routes. Its only job is to reject tanker masks that
     * cannot repay the Patrol they consume even under favorable conditions.
     */
    private RoleRollout quickRoleRollout(int[] candidateRoles) {
        AdaptivePlanner probe = new AdaptivePlanner(setup, Heuristic.STOCK_DENSITY, RoleMode.AUTO);
        probe.roles = candidateRoles.clone();
        List<AgentState> agents = new ArrayList<>(setup.agentCount());
        for (int agent = 0; agent < setup.agentCount(); agent++) {
            agents.add(new AgentState(AgentKind.fromCode(candidateRoles[agent]), setup.startPositions()[agent], setup.fuelLimit()));
        }
        ExactSimulator rolloutSimulator = new ExactSimulator();
        Set<String> globalBrands = new HashSet<>();
        int dailyTypes = 0, portions = 0;
        for (int day = 0; day < setup.dayCount(); day++) {
            DayState state = new DayState(day, agents, List.of());
            Model.PlannedDay plan = probe.plan(state, Deadline.afterMillis(70));
            ExactSimulator.SimulationResult result;
            try {
                result = rolloutSimulator.simulate(setup, state, plan.actions());
            } catch (RuntimeException invalid) {
                return new RoleRollout(Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4);
            }
            probe.observe(state, plan);
            globalBrands.addAll(result.brands());
            dailyTypes += result.brands().size();
            portions += result.portions();
            List<AgentState> next = new ArrayList<>(agents.size());
            for (int agent = 0; agent < agents.size(); agent++) {
                next.add(new AgentState(agents.get(agent).kind(), result.positions()[agent], result.fuel()[agent]));
            }
            agents = List.copyOf(next);
        }
        return new RoleRollout(globalBrands.size(), dailyTypes, portions);
    }

    private int[] patrolAndTankerAssignment() {
        int tanker = 0;
        long leastHarvestOpportunity = Long.MAX_VALUE;
        Traffic[] clear = new Traffic[setup.map().width() * setup.map().height()];
        for (int agent = 0; agent < setup.agentCount(); agent++) {
            long opportunity = 0;
            for (Spot spot : setup.spots()) {
                PathFinder.Path path = paths.find(setup.map(), setup.startPositions()[agent], spot.position(), clear,
                        setup.daySteps()[0], setup.fuelLimit(), false);
                if (path != null) opportunity += (long) spot.stock() * 10_000 / Math.max(1, path.steps());
            }
            if (opportunity < leastHarvestOpportunity) {
                leastHarvestOpportunity = opportunity;
                tanker = agent;
            }
        }
        int[] result = new int[setup.agentCount()];
        Arrays.fill(result, AgentKind.PATROL.code());
        result[tanker] = AgentKind.REFUEL.code();
        return result;
    }

    public Model.PlannedDay plan(DayState state, Deadline deadline) {
        if (roles == null) chooseAssignment();
        currentPlanningDay = state.day();
        largePathCache.clear();
        boundedPathCache.clear();
        unreachableBoundedPaths.clear();
        // Multi-label Dijkstra is intentionally a scarce expert operation.  Two expansions give
        // the portfolio genuine time/fuel alternatives without allowing a dense map to consume
        // the response window re-solving every source-target pair.
        paretoExpansionsRemaining = heuristic == Heuristic.CHAIN_LOOKAHEAD ? profile.paretoFirstHopExpansions() : 0;
        paretoPathsOffered = 0;
        int n = state.agents().size(), budget = setup.daySteps()[state.day()];
        Traffic[] traffic = vn.ptit.procon.rules.HexRules.traffic(setup.map().width() * setup.map().height(), state.traffic());
        int[] remainingStock = setup.spots().stream().mapToInt(Spot::stock).toArray();
        boolean[][] visited = new boolean[n][setup.spots().size()];
        Set<String> dayBrands = new HashSet<>();
        int[][] actions = new int[n][];
        int[] endPositions = new int[n];
        int[] predictedFuel = new int[n];
        List<Integer> patrols = new ArrayList<>();
        List<Integer> tankers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            endPositions[i] = state.agents().get(i).position(); predictedFuel[i] = state.agents().get(i).fuel();
            if (state.agents().get(i).kind() == AgentKind.PATROL) patrols.add(i); else tankers.add(i);
        }

        List<PatrolRoute> routes = new ArrayList<>();
        for (int agent : patrols) {
            routes.add(new PatrolRoute(agent, state.agents().get(agent).position(), state.agents().get(agent).fuel(),
                    visited[agent]));
        }

        // Stage A: team-level daily coverage. Each brand is bid by every patrol, but a patrol
        // may cover at most one brand while another unused patrol remains. This prevents the
        // old agent-0-first loop from spending several routes on one region before the team has
        // locked the official daily-types criterion.
        coverDailyBrands(state, routes, traffic, remainingStock, dayBrands, budget, deadline);

        // Stage B: global marginal-gain auction. A round-robin eligibility rule avoids a single
        // early patrol monopolising all high-stock targets, while stock capacity remains shared.
        harvestByAuction(state, routes, traffic, remainingStock, dayBrands, budget, deadline);

        for (PatrolRoute route : routes) {
            if (route.elapsed < budget) route.wire.add(-Math.max(1, budget - route.elapsed));
            actions[route.agent] = route.wire.stream().mapToInt(Integer::intValue).toArray();
            endPositions[route.agent] = route.position;
            predictedFuel[route.agent] = route.fuel;
        }

        // Tankers are staged at the lowest-fuel patrol terminal. This gives a valid rendezvous candidate
        // without forcing a rendezvous when all patrol routes remain healthy.
        boolean[] served = new boolean[n];
        for (int tanker : tankers) {
            int start = state.agents().get(tanker).position();
            List<Integer> wire = new ArrayList<>(); int elapsed = 0; int position = start;
            int targetAgent = bestRefuelTarget(tanker, patrols, served, start, endPositions, predictedFuel,
                    traffic, budget, state.day());
            if (targetAgent >= 0) {
                PathFinder.Path path = paths.find(setup.map(), start, endPositions[targetAgent], traffic,
                        budget, Integer.MAX_VALUE, true);
                for (int d : path.directions()) wire.add(d);
                elapsed = path.steps(); position = path.target();
                served[targetAgent] = true;
                // The exact simulator validates the overlap before a portfolio candidate can win.
                // This provisional value lets the tie-break value a confirmed end-of-day refuel.
                predictedFuel[targetAgent] = setup.fuelLimit();
            }
            if (elapsed < budget) wire.add(-Math.max(1, budget - elapsed));
            actions[tanker] = wire.stream().mapToInt(Integer::intValue).toArray();
            endPositions[tanker] = position;
        }
        int portions = 0;
        for (int i = 0; i < setup.spots().size(); i++) portions += setup.spots().get(i).stock() - remainingStock[i];
        Set<String> allBrands = new HashSet<>(memory.globalBrands()); allBrands.addAll(dayBrands);
        int reachable = 0;
        for (Spot spot : setup.spots()) if (!allBrands.contains(spot.brand())) reachable++;
        Model.Projection projection = new Model.Projection(allBrands.size(), memory.dailyTypesSum() + dayBrands.size(),
                memory.portions() + portions, reachable, minimumPatrolFuel(predictedFuel, state), Arrays.stream(predictedFuel).sum());
        String fingerprint = fingerprint(actions);
        return new Model.PlannedDay(state.day(), actions, projection, fingerprint);
    }

    private int bestRefuelTarget(int tanker, List<Integer> patrols, boolean[] served, int tankerStart,
                                 int[] endPositions, int[] predictedFuel, Traffic[] traffic, int budget, int day) {
        if (day >= setup.dayCount() - 1) return -1; // no next-day value on the final day
        int threshold = Math.max(25, setup.fuelLimit() * 2 / 3);
        int best = -1;
        int bestBenefit = Integer.MIN_VALUE;
        for (int patrol : patrols) {
            if (served[patrol] || predictedFuel[patrol] >= threshold) continue;
            PathFinder.Path path = paths.find(setup.map(), tankerStart, endPositions[patrol], traffic,
                    budget, Integer.MAX_VALUE, true);
            if (path == null) continue;
            int benefit = (setup.fuelLimit() - predictedFuel[patrol]) * 100 - path.steps() * 3;
            if (benefit > bestBenefit) {
                bestBenefit = benefit;
                best = patrol;
            }
        }
        return best;
    }

    public void observe(DayState before, Model.PlannedDay plan) {
        ExactSimulator.SimulationResult result = simulator.simulate(setup, before, plan.actions());
        memory.observe(before.day(), result.brands(), result.portions());
    }

    public Model.Projection exactProjection(DayState before, Model.PlannedDay plan) {
        ExactSimulator.SimulationResult result = simulator.simulate(setup, before, plan.actions());
        Set<String> allBrands = new HashSet<>(memory.globalBrands());
        allBrands.addAll(result.brands());
        int unreachable = 0;
        for (Spot spot : setup.spots()) if (!allBrands.contains(spot.brand())) unreachable++;
        return new Model.Projection(allBrands.size(), memory.dailyTypesSum() + result.brands().size(),
                memory.portions() + result.portions(), unreachable,
                minimumPatrolFuel(result.fuel(), before), Arrays.stream(result.fuel()).sum());
    }

    /** Stable action identifier used by portfolio-level exact-replay improvements. */
    public String fingerprintFor(int[][] actions) {
        return fingerprint(actions);
    }

    private int minimumPatrolFuel(int[] fuel, DayState state) {
        int minimum = Integer.MAX_VALUE;
        for (int i = 0; i < fuel.length; i++) {
            if (state.agents().get(i).kind() == AgentKind.PATROL) minimum = Math.min(minimum, fuel[i]);
        }
        return minimum == Integer.MAX_VALUE ? 0 : minimum;
    }

    private void coverDailyBrands(DayState state, List<PatrolRoute> routes, Traffic[] traffic, int[] remainingStock,
                                  Set<String> dayBrands, int budget, Deadline deadline) {
        List<String> missingBrands = new ArrayList<>();
        for (Spot spot : setup.spots()) if (!dayBrands.contains(spot.brand()) && !missingBrands.contains(spot.brand())) {
            missingBrands.add(spot.brand());
        }
        missingBrands.sort(String::compareTo);
        if (missingBrands.isEmpty() || missingBrands.size() > routes.size()) {
            greedyCoverDailyBrands(state, routes, traffic, remainingStock, dayBrands, budget, deadline);
            return;
        }

        CoveragePlan[] plans = new CoveragePlan[1 << routes.size()];
        plans[0] = new CoveragePlan(0, new Candidate[routes.size()]);
        for (String brand : missingBrands) {
            CoveragePlan[] next = new CoveragePlan[plans.length];
            for (int mask = 0; mask < plans.length; mask++) {
                CoveragePlan partial = plans[mask];
                if (partial == null) continue;
                for (int routeIndex = 0; routeIndex < routes.size(); routeIndex++) {
                    if ((mask & (1 << routeIndex)) != 0) continue;
                    Candidate candidate = bestCandidateForBrand(routes.get(routeIndex), brand, traffic,
                            remainingStock, budget);
                    if (candidate == null) continue;
                    int nextMask = mask | (1 << routeIndex);
                    int cost = partial.cost + candidate.path().steps() * 100 + candidate.path().fuel() * 10 - candidate.spot().stock() * 5;
                    if (next[nextMask] == null || cost < next[nextMask].cost) {
                        Candidate[] choices = partial.choices.clone();
                        choices[routeIndex] = candidate;
                        next[nextMask] = new CoveragePlan(cost, choices);
                    }
                }
            }
            plans = next;
            if (deadline.expired()) break;
        }
        CoveragePlan best = null;
        for (CoveragePlan plan : plans) if (plan != null && (best == null || plan.cost < best.cost)) best = plan;
        if (best == null) {
            greedyCoverDailyBrands(state, routes, traffic, remainingStock, dayBrands, budget, deadline);
            return;
        }
        for (int routeIndex = 0; routeIndex < routes.size(); routeIndex++) {
            Candidate candidate = best.choices[routeIndex];
            if (candidate != null && remainingStock[candidate.spot().id()] > 0
                    && !routes.get(routeIndex).visited[candidate.spot().id()]
                    && !dayBrands.contains(candidate.spot().brand())) {
                accept(state, routes, routes.get(routeIndex), candidate, remainingStock, dayBrands, true);
            }
        }
    }

    private void greedyCoverDailyBrands(DayState state, List<PatrolRoute> routes, Traffic[] traffic, int[] remainingStock,
                                        Set<String> dayBrands, int budget, Deadline deadline) {
        Set<String> allBrands = new TreeSet<>();
        for (Spot spot : setup.spots()) allBrands.add(spot.brand());
        while (!deadline.expired() && dayBrands.size() < allBrands.size()) {
            // In a configured match there may be more brands than Patrols (for example a
            // four-agent board with six franchises).  The old guard permanently excluded a
            // Patrol after its first coverage pickup, so it could never finish daily coverage
            // in that shape.  Share the first pass fairly, then open a second pass to the
            // least-used Patrols.  This keeps the normal one-brand-per-Patrol behaviour when
            // enough Patrols exist while making coverage scale to unfamiliar configurations.
            int leastCoverageClaims = routes.stream().mapToInt(route -> route.coverageClaims).min().orElse(0);
            AuctionBid best = null;
            for (PatrolRoute route : routes) {
                if (route.coverageClaims != leastCoverageClaims) continue;
                for (Spot spot : setup.spots()) {
                    if (dayBrands.contains(spot.brand()) || remainingStock[spot.id()] <= 0 || route.visited[spot.id()]) continue;
                    for (PathFinder.Path path : pathsToSpot(route.position, spot, traffic,
                            budget - route.elapsed, route.fuel)) {
                        int score = 1_000_000 - path.steps() * 100 - path.fuel() * 10 + spot.stock() * 5;
                        AuctionBid bid = new AuctionBid(route, new Candidate(spot, path, score), score);
                        if (best == null || bid.score > best.score) best = bid;
                    }
                }
            }
            if (best == null) return;
            accept(state, routes, best.route, best.candidate, remainingStock, dayBrands, true);
        }
    }

    private Candidate bestCandidateForBrand(PatrolRoute route, String brand, Traffic[] traffic,
                                            int[] remainingStock, int budget) {
        Candidate best = null;
        Candidate reserveBreakingFallback = null;
        for (Spot spot : setup.spots()) {
            if (!spot.brand().equals(brand) || remainingStock[spot.id()] <= 0 || route.visited[spot.id()]) continue;
            for (PathFinder.Path path : pathsToSpot(route.position, spot, traffic,
                    budget - route.elapsed, route.fuel)) {
                Candidate candidate = new Candidate(spot, path, -path.steps() * 100 - path.fuel() * 10 + spot.stock() * 5);
                if (!preservesFutureFuel(route.fuel, path, currentPlanningDay)) {
                    if (reserveBreakingFallback == null || candidate.score() > reserveBreakingFallback.score()) {
                        reserveBreakingFallback = candidate;
                    }
                } else if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
            }
        }
        // Official coverage is never made impossible by fuel pacing.  A reserve-breaking path is
        // used only when the map offers no same-brand alternative that preserves future capacity.
        return best != null ? best : reserveBreakingFallback;
    }

    private void harvestByAuction(DayState state, List<PatrolRoute> routes, Traffic[] traffic, int[] remainingStock,
                                  Set<String> dayBrands, int budget, Deadline deadline) {
        while (!deadline.expired()) {
            int leastPicks = routes.stream().mapToInt(route -> route.picks).min().orElse(0);
            AuctionBid best = bestAuctionBid(routes, traffic, remainingStock, dayBrands, budget, leastPicks, true);
            if (best == null) best = bestAuctionBid(routes, traffic, remainingStock, dayBrands, budget, leastPicks, false);
            if (best == null) return;
            accept(state, routes, best.route, best.candidate, remainingStock, dayBrands, false);
        }
    }

    private AuctionBid bestAuctionBid(List<PatrolRoute> routes, Traffic[] traffic, int[] remainingStock,
                                      Set<String> dayBrands, int budget, int leastPicks, boolean roundRobinOnly) {
        AuctionBid best = null;
        for (PatrolRoute route : routes) {
            if (route.elapsed >= budget || (roundRobinOnly && route.picks != leastPicks)) continue;
            Candidate candidate = bestCandidate(route.position, route.fuel, route.elapsed, budget, traffic,
                    remainingStock, route.visited, dayBrands, route.picks);
            if (candidate == null) continue;
            int marginal = candidate.score() - route.picks * 25;
            AuctionBid bid = new AuctionBid(route, candidate, marginal);
            if (best == null || bid.score > best.score) best = bid;
        }
        return best;
    }

    private void accept(DayState state, List<PatrolRoute> routes, PatrolRoute route, Candidate candidate, int[] remainingStock,
                        Set<String> dayBrands, boolean coverage) {
        int source = route.position;
        for (int direction : candidate.path().directions()) route.wire.add(direction);
        route.position = candidate.path().target();
        route.fuel -= candidate.path().fuel();
        route.elapsed += candidate.path().steps();
        route.picks++;
        if (coverage) route.coverageClaims++;
        if (isFastLargeMap()) {
            // Exact replay after every micro-assignment is correct but was the P32 bottleneck:
            // coverage consumed the entire answer window and two Patrols never received a task.
            // Keep an allocation ledger instead.  It must account for every spot crossed by a
            // route, not only the nominated endpoint: Patrols collect on arrival and a
            // pass-through can otherwise be offered to another Patrol a second time.  Counting
            // one claim per Patrol/spot is equivalent to the server's capacity rule; final
            // simulator replay remains the single legality and scoring authority before submit.
            recordLargeMapHarvests(route, source, candidate.path(), remainingStock, dayBrands);
            return;
        }
        synchronizeHarvestState(state, routes, remainingStock, dayBrands);
    }

    private void recordLargeMapHarvests(PatrolRoute route, int source, PathFinder.Path path, int[] remainingStock,
                                        Set<String> dayBrands) {
        int position = source;
        for (int direction : path.directions()) {
            position = vn.ptit.procon.rules.HexRules.neighbors(position, setup.map()).get(direction);
            int spot = position >= 0 && position < spotAtPosition.length ? spotAtPosition[position] : -1;
            if (spot < 0 || route.visited[spot]) continue;
            route.visited[spot] = true;
            if (remainingStock[spot] > 0) {
                remainingStock[spot]--;
                dayBrands.add(setup.spots().get(spot).brand());
            }
        }
    }

    /**
     * Routes collect every spot they enter, not merely their nominated target.  Replaying the
     * partial joint plan here keeps the auction's shared stock and per-patrol visited masks in
     * lock-step with server semantics.  The plans are tiny (at most eight agents and one day),
     * so this exact accounting is both safer and cheaper than repairing duplicate claims later.
     */
    private void synchronizeHarvestState(DayState state, List<PatrolRoute> routes, int[] remainingStock,
                                         Set<String> dayBrands) {
        int[][] partialActions = new int[state.agents().size()][];
        for (int i = 0; i < partialActions.length; i++) partialActions[i] = new int[0];
        for (PatrolRoute route : routes) {
            partialActions[route.agent] = route.wire.stream().mapToInt(Integer::intValue).toArray();
        }
        ExactSimulator.SimulationResult result = simulator.simulate(setup, state, partialActions);
        for (int spot = 0; spot < remainingStock.length; spot++) {
            remainingStock[spot] = Math.max(0, setup.spots().get(spot).stock() - result.claimsBySpot()[spot]);
        }
        for (PatrolRoute route : routes) {
            System.arraycopy(result.visitedSpots()[route.agent], 0, route.visited, 0, route.visited.length);
        }
        dayBrands.clear();
        dayBrands.addAll(result.brands());
    }

    private Candidate bestCandidate(int position, int fuel, int elapsed, int budget, Traffic[] traffic,
                                    int[] remainingStock, boolean[] visited, Set<String> dayBrands, int routePicks) {
        List<Candidate> candidates = new ArrayList<>();
        for (Spot spot : setup.spots()) {
            if (remainingStock[spot.id()] <= 0 || visited[spot.id()]) continue;
            for (PathFinder.Path path : pathsToSpot(position, spot, traffic, budget - elapsed, fuel)) {
                if (!preservesFutureFuel(fuel, path, currentPlanningDay)) continue;
                int directHarvest = directHarvestPotential(position, path, remainingStock, visited);
                candidates.add(new Candidate(spot, path, scoreCandidate(spot, path, directHarvest, dayBrands,
                        remainingStock[spot.id()])));
            }
        }
        addParetoAlternatives(candidates, position, fuel, elapsed, budget, traffic, remainingStock, visited, dayBrands, routePicks);
        List<Candidate> capped = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::score).reversed())
                .limit(profile.targetCap())
                .toList();
        if (heuristic != Heuristic.CHAIN_LOOKAHEAD || routePicks >= 2) {
            return capped.stream().max(Comparator.comparingInt(Candidate::score)).orElse(null);
        }

        // A bounded two-hop rollout provides a route-construction alternative to pure density:
        // score only the four best first hops, then ask which one leaves the strongest immediate
        // continuation.  It is deliberately bounded so the emergency/anytime deadline remains safe.
        Candidate best = null;
        for (int i = 0; i < Math.min(4, capped.size()); i++) {
            Candidate candidate = capped.get(i);
            int continuation = bestContinuation(position, candidate, remainingStock, visited, traffic,
                    budget - elapsed - candidate.path().steps(), fuel - candidate.path().fuel());
            Candidate rolled = new Candidate(candidate.spot(), candidate.path(), candidate.score() + continuation);
            if (best == null || rolled.score() > best.score()) best = rolled;
        }
        return best;
    }

    // Set once per call to plan; only the experimental P08 arm uses it.
    private int currentPlanningDay;

    private boolean preservesFutureFuel(int fuel, PathFinder.Path path, int day) {
        if (heuristic != Heuristic.FUEL_PACED || day >= setup.dayCount() - 1) return true;
        // Reserve is a per-arm soft budget. Coverage can still break it as a last resort, so
        // official types are never made impossible by fuel pacing. P08 uses 12 by default;
        // larger four-day boards use their average per-day fuel share until live evidence says
        // otherwise. An explicit environment value is only used for offline A/B calibration.
        int futureDays = setup.dayCount() - day - 1;
        return fuel - path.fuel() >= futureDays * fuelReservePerFutureDay;
    }

    static int defaultFuelReserve(Setup setup, R3PlannerProfile profile) {
        if ("P08".equals(profile.id())) return Math.max(8, setup.fuelLimit() / 5);
        // Four successful live P24 fuel-day selections and fourteen journal replays show that
        // preserving 40 units per remaining day avoids the common day-four collapse without
        // reducing any recorded P24 score. Keep this exact-shape scoped (see above).
        if (usesP24FuelPacingDefaults(setup)) return Math.min(40, setup.fuelLimit());
        return Math.max(8, setup.fuelLimit() / Math.max(1, setup.dayCount()));
    }

    private static int configuredFuelReserve(Setup setup, R3PlannerProfile profile) {
        int defaultReserve = defaultFuelReserve(setup, profile);
        String configured = System.getenv("PROCON_FUEL_PACED_RESERVE");
        if (configured == null || configured.isBlank()) return defaultReserve;
        try {
            int parsed = Integer.parseInt(configured);
            return parsed > 0 && parsed <= setup.fuelLimit() ? parsed : defaultReserve;
        } catch (NumberFormatException ignored) {
            return defaultReserve;
        }
    }

    /** Use the more expensive Pareto frontier only in the rolling-horizon planner's first hop. */
    private void addParetoAlternatives(List<Candidate> candidates, int position, int fuel, int elapsed, int budget,
                                       Traffic[] traffic, int[] remainingStock, boolean[] visited,
                                       Set<String> dayBrands, int routePicks) {
        if (heuristic != Heuristic.CHAIN_LOOKAHEAD || routePicks != 0 || candidates.isEmpty()
                || paretoExpansionsRemaining <= 0) return;
        List<Candidate> seeds = candidates.stream().sorted(Comparator.comparingInt(Candidate::score).reversed())
                .limit(paretoExpansionsRemaining).toList();
        paretoExpansionsRemaining -= seeds.size();
        for (Candidate seed : seeds) {
            if (seed.spot().position() == position) continue; // bounce already represents this target.
            for (PathFinder.Path path : paths.findPareto(setup.map(), position, seed.spot().position(), traffic,
                    budget - elapsed, fuel, false, 2)) {
                if (path.steps() == seed.path().steps() && path.fuel() == seed.path().fuel()) continue;
                int directHarvest = directHarvestPotential(position, path, remainingStock, visited);
                candidates.add(new Candidate(seed.spot(), path, scoreCandidate(seed.spot(), path, directHarvest, dayBrands,
                        remainingStock[seed.spot().id()])));
                paretoPathsOffered++;
            }
        }
    }

    private int scoreCandidate(Spot spot, PathFinder.Path path, int directHarvest, Set<String> dayBrands,
                               int remainingSlots) {
        int score;
        if (heuristic == Heuristic.STOCK_DENSITY || heuristic == Heuristic.CAPACITY_DENSITY
                || heuristic == Heuristic.FUEL_PACED) {
            score = directHarvest * 1_000 - path.steps() * 8 - path.fuel() * 4;
            if (!memory.globalBrands().contains(spot.brand())) score += 5_000;
            if (!dayBrands.contains(spot.brand())) score += 1_000;
        } else if (heuristic == Heuristic.NEAREST_FIRST) {
            score = 3_000 - path.steps() * 30 - path.fuel() * 3 + directHarvest * 250;
            if (!memory.globalBrands().contains(spot.brand())) score += 7_000;
            if (!dayBrands.contains(spot.brand())) score += 2_000;
        } else if (heuristic == Heuristic.CHAIN_LOOKAHEAD) {
            score = directHarvest * 1_400 - path.steps() * 14 - path.fuel() * 6;
            if (!memory.globalBrands().contains(spot.brand())) score += 8_000;
            if (!dayBrands.contains(spot.brand())) score += 2_000;
        } else {
            score = directHarvest * 100 - path.steps() * 4 - path.fuel() * 2;
            if (!memory.globalBrands().contains(spot.brand())) score += 10_000;
            if (!dayBrands.contains(spot.brand())) score += 3_000;
            score += Math.max(0, 300 - path.steps() * 3);
        }
        // A Patrol still harvests only one portion at a spot, but a stock-six spot can absorb
        // several independent Patrols whereas a stock-one spot is exhausted after the first.
        // The bounded bonus only breaks otherwise close route choices; exact shared-stock replay
        // is still authoritative for the final action plan.
        return score + Math.max(0, remainingSlots - 1) * capacitySlotBonus;
    }

    /**
     * A small capacity bonus lets the joint auction deliberately spend multiple Patrol claims
     * on a stock-rich spot when that is cheaper than scattering them. P16 bonus 25 repaired the
     * one-portion replay loss without reversing any five-map holdout result, then completed a
     * fault-free 5/5 three-Hard-Bot live confirmation. P12 improved or tied all six independent
     * journal replays and then cleared its own 5/5 live confirmation. The same exact value
     * retained P32's 5/5 record with a stronger average margin. Keep all promotions
     * exact-shape scoped.
     */
    static int defaultCapacitySlotBonus(Setup setup, R3PlannerProfile profile) {
        boolean canonicalP12 = "P12".equals(profile.id())
                && setup.map().width() == 12 && setup.map().height() == 12
                && setup.agentCount() == 6 && setup.dayCount() == 4
                && setup.fuelLimit() == 120
                && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 60);
        boolean canonicalP16 = "P16".equals(profile.id())
                && setup.map().width() == 16 && setup.map().height() == 16
                && setup.agentCount() == 6 && setup.dayCount() == 4
                && setup.fuelLimit() == 120
                && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 60);
        boolean canonicalP32 = "P32".equals(profile.id())
                && setup.map().width() == 32 && setup.map().height() == 32
                && setup.agentCount() == 8 && setup.dayCount() == 4
                && setup.fuelLimit() == 200
                && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 100);
        return canonicalP12 || canonicalP16 || canonicalP32 ? 25 : 0;
    }

    private static int configuredCapacitySlotBonus(Setup setup, R3PlannerProfile profile) {
        int defaultBonus = defaultCapacitySlotBonus(setup, profile);
        String configured = System.getenv("PROCON_CAPACITY_SLOT_BONUS");
        if (configured == null || configured.isBlank()) return defaultBonus;
        try {
            int parsed = Integer.parseInt(configured);
            return parsed >= 0 && parsed <= 500 ? parsed : defaultBonus;
        } catch (NumberFormatException ignored) {
            return defaultBonus;
        }
    }

    private int bestContinuation(int start, Candidate first, int[] remainingStock, boolean[] visited, Traffic[] traffic,
                                 int stepsLeft, int fuelLeft) {
        if (stepsLeft <= 0 || fuelLeft < 0) return 0;
        boolean[] afterFirst = visited.clone();
        int position = first.path().target();
        // Mark every spot touched by the first hop, including pass-through collection.
        walkAndMark(first.path(), start, afterFirst);
        int best = 0;
        for (Spot next : setup.spots()) {
            if (remainingStock[next.id()] <= 0 || afterFirst[next.id()]) continue;
            for (PathFinder.Path path : pathsToSpot(position, next, traffic, stepsLeft, fuelLeft)) {
                int harvest = directHarvestPotential(position, path, remainingStock, afterFirst);
                best = Math.max(best, harvest * 700 - path.steps() * 12 - path.fuel() * 5);
            }
        }
        return Math.max(0, best);
    }

    private int directHarvestPotential(int start, PathFinder.Path path, int[] remainingStock, boolean[] visited) {
        boolean[] seen = visited.clone();
        int position = start;
        int harvest = 0;
        for (int direction : path.directions()) {
            position = vn.ptit.procon.rules.HexRules.neighbors(position, setup.map()).get(direction);
            int spot = position >= 0 && position < spotAtPosition.length ? spotAtPosition[position] : -1;
            if (spot >= 0 && remainingStock[spot] > 0 && !seen[spot]) {
                seen[spot] = true;
                harvest++;
            }
        }
        return harvest;
    }

    private int walkAndMark(PathFinder.Path path, int start, boolean[] visited) {
        int position = start;
        for (int direction : path.directions()) {
            position = vn.ptit.procon.rules.HexRules.neighbors(position, setup.map()).get(direction);
            int spot = position >= 0 && position < spotAtPosition.length ? spotAtPosition[position] : -1;
            if (spot >= 0) visited[spot] = true;
        }
        return position;
    }

    /**
     * The server deliberately does not collect at step 0.  A Patrol already standing on an
     * unvisited spot must therefore leave and re-enter it; treating the zero-length Dijkstra
     * path as an impossible target left full stock behind on final days.  The smallest valid
     * out-and-back movement is an exact, fuel-aware candidate and may collect a neighbor en route.
     */
    private List<PathFinder.Path> pathsToSpot(int start, Spot spot, Traffic[] traffic, int maxSteps, int maxFuel) {
        if (start != spot.position()) {
            PathFinder.Path fastest = isFastLargeMap()
                    ? cachedLargePath(start, spot.position(), traffic, maxSteps, maxFuel)
                    : cachedBoundedPath(start, spot.position(), traffic, maxSteps, maxFuel);
            return fastest == null ? List.of() : List.of(fastest);
        }
        PathFinder.Path bounce = bouncePath(start, traffic, maxSteps, maxFuel);
        return bounce == null ? List.of() : List.of(bounce);
    }

    private PathFinder.Path cachedLargePath(int start, int target, Traffic[] traffic, int maxSteps, int maxFuel) {
        RouteKey key = new RouteKey(start, target);
        PathFinder.Path cached = largePathCache.get(key);
        if (cached == null) {
            // Fuel limits on P24/P32 are deliberately generous; obtaining the unrestricted
            // within-day shortest path once is safe, then every caller enforces its own residual
            // time/fuel limit below.
            cached = paths.find(setup.map(), start, target, traffic, setup.daySteps()[currentPlanningDay],
                    setup.fuelLimit(), false);
            if (cached != null) largePathCache.put(key, cached);
        }
        if (cached != null && cached.steps() <= maxSteps && cached.fuel() <= maxFuel) return cached;

        // The cached representative is the fastest full-day route.  A later route segment can
        // have less fuel left, where a slower fuel-saving path is still feasible even though the
        // cached path is not.  Falling back only in that case preserves the cache's large-board
        // speed win without silently turning reachable harvest into an artificial dead end.
        return cachedBoundedPath(start, target, traffic, maxSteps, maxFuel);
    }

    private PathFinder.Path cachedBoundedPath(int start, int target, Traffic[] traffic, int maxSteps, int maxFuel) {
        BoundedRouteKey key = new BoundedRouteKey(start, target, maxSteps, maxFuel);
        PathFinder.Path known = boundedPathCache.get(key);
        if (known != null) return known;
        if (unreachableBoundedPaths.contains(key)) return null;
        PathFinder.Path found = paths.find(setup.map(), start, target, traffic, maxSteps, maxFuel, false);
        if (boundedPathCache.size() + unreachableBoundedPaths.size() < MAX_BOUNDED_PATH_CACHE_ENTRIES) {
            if (found == null) unreachableBoundedPaths.add(key);
            else boundedPathCache.put(key, found);
        }
        return found;
    }

    private boolean isFastLargeMap() {
        return Math.max(setup.map().width(), setup.map().height()) >= 24;
    }

    private PathFinder.Path bouncePath(int start, Traffic[] traffic, int maxSteps, int maxFuel) {
        Model.MoveCost outward = vn.ptit.procon.rules.HexRules.moveCost(setup.map(), start, traffic);
        if (outward == null) return null;
        PathFinder.Path best = null;
        for (int outwardDirection = 0; outwardDirection < 6; outwardDirection++) {
            int neighbor = vn.ptit.procon.rules.HexRules.neighbors(start, setup.map()).get(outwardDirection);
            if (neighbor < 0 || !setup.map().terrain(neighbor).traversable()) continue;
            Model.MoveCost inward = vn.ptit.procon.rules.HexRules.moveCost(setup.map(), neighbor, traffic);
            if (inward == null) continue;
            int returnDirection = -1;
            for (int direction = 0; direction < 6; direction++) {
                if (vn.ptit.procon.rules.HexRules.neighbors(neighbor, setup.map()).get(direction) == start) {
                    returnDirection = direction;
                    break;
                }
            }
            if (returnDirection < 0) continue;
            int steps = outward.steps() + inward.steps();
            int fuel = outward.fuel() + inward.fuel();
            if (steps > maxSteps || fuel > maxFuel) continue;
            PathFinder.Path candidate = new PathFinder.Path(new int[]{outwardDirection, returnDirection}, steps, fuel, start);
            if (best == null || candidate.steps() < best.steps()
                    || (candidate.steps() == best.steps() && candidate.fuel() < best.fuel())) best = candidate;
        }
        return best;
    }

    private boolean roughReachable(int start, int target, int fuel) {
        Traffic[] clear = new Traffic[setup.map().width() * setup.map().height()];
        PathFinder.Path path = paths.find(setup.map(), start, target, clear, 200, fuel, false);
        return path != null;
    }

    private String fingerprint(int[][] actions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int[] row : actions) for (int value : row) digest.update((byte) value);
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.substring(0, 16);
        } catch (Exception e) { return Integer.toHexString(Arrays.deepHashCode(actions)); }
    }

    private static final class PatrolRoute {
        private final int agent;
        private final boolean[] visited;
        private final List<Integer> wire = new ArrayList<>();
        private int position;
        private int fuel;
        private int elapsed;
        private int picks;
        private int coverageClaims;

        private PatrolRoute(int agent, int position, int fuel, boolean[] visited) {
            this.agent = agent;
            this.position = position;
            this.fuel = fuel;
            this.visited = visited;
        }
    }

    private record AuctionBid(PatrolRoute route, Candidate candidate, int score) {}
    public record RoleRollout(int globalTypes, int dailyTypes, int portions) implements Comparable<RoleRollout> {
        @Override public int compareTo(RoleRollout other) {
            int comparison = Integer.compare(globalTypes, other.globalTypes);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(dailyTypes, other.dailyTypes);
            if (comparison != 0) return comparison;
            return Integer.compare(portions, other.portions);
        }
    }
    public record RoleRolloutSummary(int[] patrolRoles, RoleRollout patrol,
                                     int[] tankerRoles, RoleRollout tanker) {
        public RoleRolloutSummary {
            patrolRoles = patrolRoles.clone();
            tankerRoles = tankerRoles.clone();
        }
        @Override public int[] patrolRoles() { return patrolRoles.clone(); }
        @Override public int[] tankerRoles() { return tankerRoles.clone(); }
        public int portionDelta() { return tanker.portions() - patrol.portions(); }
        public boolean tankerStrictlyBetter() { return tanker.compareTo(patrol) > 0; }
    }
    private record CoveragePlan(int cost, Candidate[] choices) {}
    private record Candidate(Spot spot, PathFinder.Path path, int score) {}
    private record RouteKey(int source, int target) {}
    private record BoundedRouteKey(int source, int target, int maxSteps, int maxFuel) {}
}

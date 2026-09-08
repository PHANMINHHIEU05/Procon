package vn.ptit.procon.planner;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
/**
 * ITERATION 6 CAUSAL AUDIT — diagnostics only, disabled by default, never enabled by the live bot.
 *
 * <p>Answers one question with counters instead of assumptions: is the V3 cost driven by the NUMBER of
 * graph builds and route-finder executions, or by the unit cost of each one? Iteration 5 failed because a
 * per-unit figure was derived by dividing a total by an operation count; nothing here is derived that way.
 *
 * <p>Two signatures are recorded. The GRAPH INPUT SIGNATURE is everything
 * {@code StrategicOpportunityGraphBuilder.build} reads, so two builds sharing it are provably redundant.
 * The ROUTE QUERY IDENTITY is everything {@code WeightedRouteFinder.find} reads, so two queries sharing it
 * provably return the same route. Both are content hashes, never instance identity.
 *
 * <p>Not thread safe by construction: the audit runs single threaded offline. The live path only ever reads
 * the {@code enabled} flag, which stays {@code false}.
 */
public final class V3WorkAuditProbe {

    private static volatile boolean enabled;

    private static long graphBuildRequests;
    private static long graphBuildWallNanos;
    private static long graphBuildAllocatedBytes;
    private static int buildDepth;
    private static String firstGraphBuildCaller = "";

    private static long routeFinderCalls;
    private static long routeFinderWallNanos;
    private static long routeFinderCallsInsideBuild;
    private static long routeFinderWallNanosInsideBuild;

    private static final Map<String, long[]> BUILD_SIGNATURES = new HashMap<>();
    private static final Map<String, Long> BUILDS_BY_CALL_SITE = new HashMap<>();
    private static final Map<String, Long> BUILDS_BY_PATH = new HashMap<>();
    private static final Map<String, Long> BUILDS_BY_DAY_STATE = new HashMap<>();
    private static final Map<String, long[]> ROUTE_QUERIES = new HashMap<>();
    private static final Map<String, Long> ROUTE_ORIGINS_IN_BUILD = new HashMap<>();
    private static final Map<Object, String> ROUTE_CONTEXT_CACHE = new IdentityHashMap<>();
    private static final Map<Object, String> GRAPH_STATE_CACHE = new IdentityHashMap<>();

    private V3WorkAuditProbe() {
    }

    public static void enable() {
        reset();
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static synchronized void reset() {
        graphBuildRequests = 0;
        graphBuildWallNanos = 0;
        graphBuildAllocatedBytes = 0;
        buildDepth = 0;
        firstGraphBuildCaller = "";
        routeFinderCalls = 0;
        routeFinderWallNanos = 0;
        routeFinderCallsInsideBuild = 0;
        routeFinderWallNanosInsideBuild = 0;
        BUILD_SIGNATURES.clear();
        BUILDS_BY_CALL_SITE.clear();
        BUILDS_BY_PATH.clear();
        BUILDS_BY_DAY_STATE.clear();
        ROUTE_QUERIES.clear();
        ROUTE_ORIGINS_IN_BUILD.clear();
        ROUTE_CONTEXT_CACHE.clear();
        GRAPH_STATE_CACHE.clear();
    }

    // ---------------------------------------------------------------- signatures

    /**
     * Everything {@link WeightedRouteFinder#find} reads out of the day state, and nothing else.
     *
     * <p>RELEVANT — map geometry ({@code width}/{@code height}, needed by {@code neighbor}), the full terrain
     * array (movement cost comes from the SOURCE cell and POND is impassable), the complete road-traffic map
     * (a ROAD source with null traffic is skipped and CLEAR/CONGESTED/JAMMED change the step cost), and the
     * day step budget (the search prunes on {@code nextSteps > state.stepBudget()}).
     *
     * <p>NOT RELEVANT — spots, spot stock, our agent list, observed opponents, the day index. The route
     * finder never dereferences them, so including them would make two provably identical queries look
     * distinct and would understate duplication.
     */
    private static String routeContextSignature(DayState state) {
        String cached = ROUTE_CONTEXT_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        HexMap map = state.matchData().map();
        StringBuilder text = new StringBuilder(256);
        text.append("map=").append(map.width()).append('x').append(map.height()).append("|terrain=");
        for (int cell = 0; cell < map.cellCount(); cell++) {
            text.append(map.terrainAt(new Position(cell)).name().charAt(0));
        }
        text.append("|traffic=");
        state.roadTraffic().entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().value()))
                .forEach(entry -> text.append(entry.getKey().value()).append(':')
                        .append(entry.getValue().name().charAt(0)).append(','));
        text.append("|stepBudget=").append(state.stepBudget());
        String signature = digest(text.toString());
        ROUTE_CONTEXT_CACHE.put(state, signature);
        return signature;
    }

    /**
     * Everything {@code StrategicOpportunityGraphBuilder.build} reads, plus the retention policy.
     *
     * <p>RELEVANT, beyond the route context above — the udon spot list (position, brand and stock capacity
     * all reach the opportunity records), the spot stock map (current stock, local density and the HIGH_STOCK
     * edge category), our agent list with each agent's id, kind, position and remaining fuel (PATROL agents
     * seed the travel and entry-route caches; a different remaining fuel changes which entry routes exist),
     * the PATROL fuel capacity (origin of both the spot-to-spot probe and the support-aware lifted probe),
     * the observed opponent agent positions (opponent pressure) and the retention policy (which edges
     * survive). The day step budget is already inside the route context and additionally drives the region
     * threshold and the edge budget impact.
     *
     * <p>NOT RELEVANT — the day index, the initial agent list, and everything about scores or history: the
     * builder never reads them, so a signature that included them would report false uniqueness.
     */
    private static String graphInputSignature(DayState state, String policy) {
        String cached = GRAPH_STATE_CACHE.get(state);
        if (cached == null) {
            StringBuilder text = new StringBuilder(512);
            text.append(routeContextSignature(state));
            text.append("|spots=");
            state.matchData().udonSpots().stream()
                    .sorted(Comparator.comparingInt(spot -> spot.position().value()))
                    .forEach(spot -> text.append(spot.position().value()).append(':')
                            .append(spot.brand()).append(':').append(spot.stockCapacity()).append(','));
            text.append("|stock=");
            state.spotStock().entrySet().stream()
                    .sorted(Comparator.comparingInt(entry -> entry.getKey().value()))
                    .forEach(entry -> text.append(entry.getKey().value()).append(':')
                            .append(entry.getValue()).append(','));
            text.append("|agents=");
            for (AgentState agent : state.agents()) {
                text.append(agent.id().value()).append(':').append(agent.kind().name().charAt(0))
                        .append(':').append(agent.position().value()).append(':')
                        .append(agent.fuel() instanceof FiniteFuel finite ? finite.amount() : -1).append(',');
            }
            text.append("|patrolCapacity=").append(state.matchData().patrolFuelCapacity().value());
            text.append("|others=");
            state.observedOthers().forEach(group -> group.agents().stream()
                    .sorted(Comparator.comparingInt(agent -> agent.position().value()))
                    .forEach(agent -> text.append(group.rawId()).append('/')
                            .append(agent.position().value()).append(',')));
            cached = digest(text.toString());
            GRAPH_STATE_CACHE.put(state, cached);
        }
        return cached + "|policy=" + policy;
    }

    // ---------------------------------------------------------------- graph build hooks

    /** Returned to the builder so the exit hook can close exactly this invocation. */
    public record BuildToken(long startedNanos, long allocatedAtEntry) {
    }

    /** Records one build REQUEST together with its semantically complete input signature. */
    public static synchronized BuildToken beginBuild(DayState state, String policy) {
        graphBuildRequests++;
        String signature = graphInputSignature(state, policy);
        BUILD_SIGNATURES.computeIfAbsent(signature, key -> new long[1])[0]++;
        String caller = callerOutsideBuilder();
        BUILDS_BY_CALL_SITE.merge(caller, 1L, Long::sum);
        BUILDS_BY_PATH.merge(evaluatorPath(), 1L, Long::sum);
        BUILDS_BY_DAY_STATE.merge(GRAPH_STATE_CACHE.get(state), 1L, Long::sum);
        if (firstGraphBuildCaller.isEmpty()) {
            firstGraphBuildCaller = caller;
        }
        buildDepth++;
        return new BuildToken(System.nanoTime(), allocatedBytes());
    }

    public static synchronized void endBuild(BuildToken token) {
        graphBuildWallNanos += System.nanoTime() - token.startedNanos();
        long allocated = allocatedBytes();
        if (allocated >= 0 && token.allocatedAtEntry() >= 0) {
            graphBuildAllocatedBytes += allocated - token.allocatedAtEntry();
        }
        buildDepth--;
    }

    // ---------------------------------------------------------------- route query hooks

    /**
     * Records one route-finder CALL. {@code start} and {@code initialFuel} are the only two things the finder
     * reads off the agent — never its id and never its kind — so the identity deliberately omits both.
     */
    public static synchronized void recordRouteQuery(DayState state, Position start, int initialFuel,
            Position goal, long elapsedNanos) {
        routeFinderCalls++;
        routeFinderWallNanos += elapsedNanos;
        String identity = routeContextSignature(state) + "|from=" + start.value()
                + "|fuel=" + initialFuel + "|to=" + goal.value();
        ROUTE_QUERIES.computeIfAbsent(identity, key -> new long[2])[0]++;
        ROUTE_QUERIES.get(identity)[1] += elapsedNanos;
        if (buildDepth > 0) {
            routeFinderCallsInsideBuild++;
            routeFinderWallNanosInsideBuild += elapsedNanos;
            ROUTE_ORIGINS_IN_BUILD.merge(routeContextSignature(state) + "|from=" + start.value()
                    + "|fuel=" + initialFuel, 1L, Long::sum);
        }
    }

    private static long allocatedBytes() {
        try {
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sunBean) {
                return sunBean.getCurrentThreadAllocatedBytes();
            }
        } catch (RuntimeException ignored) {
            return -1;
        }
        return -1;
    }

    private static String callerOutsideBuilder() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                .filter(name -> !name.startsWith("vn.ptit.procon.planner.V3WorkAuditProbe"))
                .filter(name -> !name.startsWith(
                        "vn.ptit.procon.planner.v3.StrategicOpportunityGraphBuilder"))
                .findFirst().orElse("<unknown>"));
    }

    private static String evaluatorPath() {
        List<String> frames = StackWalker.getInstance().walk(stream -> stream
                .map(StackWalker.StackFrame::getClassName).toList());
        for (String marker : List.of("StrategicTeamComposition", "StrategicTeamSearch",
                "V3RepresentationOracle", "StrategicOracle", "FrozenObjectiveEvaluator",
                "CoupledCompetitiveRollout", "SupportAwareTrajectoryScheduler", "V3ShadowPlanner")) {
            if (frames.stream().anyMatch(name -> name.contains(marker))) {
                return marker;
            }
        }
        return "<direct>";
    }

    private static String digest(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] bytes = sha.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    // ---------------------------------------------------------------- report

    /** The audit verdict for whatever workload ran between {@link #enable()} and this call. */
    public static synchronized String report(String label) {
        long uniqueBuilds = BUILD_SIGNATURES.size();
        long duplicateBuilds = graphBuildRequests - uniqueBuilds;
        long uniqueQueries = ROUTE_QUERIES.size();
        long duplicateQueries = routeFinderCalls - uniqueQueries;
        long uniqueOrigins = ROUTE_ORIGINS_IN_BUILD.size();
        StringBuilder out = new StringBuilder(2048);
        out.append(String.format(Locale.ROOT,
                "V3AUDIT label=%s graphBuildRequests=%d uniqueGraphInputSignatures=%d"
                        + " duplicateGraphBuildRequests=%d duplicateGraphBuildRatio=%.4f"
                        + " routeFinderCalls=%d routeFinderCallsPerGraphBuild=%.1f uniqueRouteQueries=%d"
                        + " duplicateRouteQueries=%d duplicateRouteQueryRatio=%.4f"
                        + " uniqueRouteOriginsInBuild=%d queriesPerOriginInBuild=%.1f"
                        + " graphBuildWallNanos=%d routeFinderWallNanos=%d"
                        + " routeFinderWallNanosInsideBuild=%d routeShareOfBuild=%.4f"
                        + " routeCallsInsideBuild=%d graphBuildAllocationApproximationBytes=%d"
                        + " firstGraphBuildCaller=%s%n",
                label, graphBuildRequests, uniqueBuilds, duplicateBuilds,
                ratio(duplicateBuilds, graphBuildRequests), routeFinderCalls,
                graphBuildRequests == 0 ? 0.0 : (double) routeFinderCallsInsideBuild / graphBuildRequests,
                uniqueQueries, duplicateQueries, ratio(duplicateQueries, routeFinderCalls),
                uniqueOrigins,
                uniqueOrigins == 0 ? 0.0 : (double) routeFinderCallsInsideBuild / uniqueOrigins,
                graphBuildWallNanos, routeFinderWallNanos, routeFinderWallNanosInsideBuild,
                ratio(routeFinderWallNanosInsideBuild, graphBuildWallNanos),
                routeFinderCallsInsideBuild, graphBuildAllocatedBytes,
                firstGraphBuildCaller.isEmpty() ? "<none>" : firstGraphBuildCaller));
        appendCounts(out, "V3AUDIT_BY_CALL_SITE", BUILDS_BY_CALL_SITE, 8);
        appendCounts(out, "V3AUDIT_BY_EVALUATOR_PATH", BUILDS_BY_PATH, 8);
        appendCounts(out, "V3AUDIT_BY_DAY_STATE", BUILDS_BY_DAY_STATE, 6);
        appendPairs(out, "V3AUDIT_TOP_REPEATED_GRAPH_SIGNATURE", BUILD_SIGNATURES, 5);
        appendPairs(out, "V3AUDIT_TOP_REPEATED_ROUTE_QUERY", ROUTE_QUERIES, 5);
        appendCounts(out, "V3AUDIT_TOP_ROUTE_ORIGIN_IN_BUILD", ROUTE_ORIGINS_IN_BUILD, 5);
        return out.toString();
    }

    private static double ratio(long part, long whole) {
        return whole == 0 ? 0.0 : (double) part / whole;
    }

    private static void appendCounts(StringBuilder out, String tag, Map<String, Long> counts, int limit) {
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(entry -> out.append(tag).append(' ').append(entry.getValue())
                        .append(' ').append(entry.getKey()).append(System.lineSeparator()));
    }

    private static void appendPairs(StringBuilder out, String tag, Map<String, long[]> counts, int limit) {
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<String, long[]>>comparingLong(entry -> -entry.getValue()[0])
                .thenComparing(Map.Entry::getKey));
        entries.stream().limit(limit).forEach(entry -> out.append(tag).append(" count=")
                .append(entry.getValue()[0])
                .append(entry.getValue().length > 1 ? " nanos=" + entry.getValue()[1] : "")
                .append(' ').append(entry.getKey()).append(System.lineSeparator()));
    }
}

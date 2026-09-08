package vn.ptit.procon.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.WeightedRouteFinder;

/**
 * ITERATION 6 CAUSAL PROFILER — answers "does the route memo eliminate EXPENSIVE queries, or only CHEAP
 * duplicates?" on a real captured day, with no instrumentation inside the candidate.
 *
 * <p>The four route families {@code StrategicOpportunityGraphBuilder.buildGraph} issues are reconstructed
 * here in the builder's exact order, straight off its source:
 *
 * <ul>
 *   <li><b>F1 TRAVEL</b> — {@code for spot : spots} then {@code for agent : PATROL agents}, agent at its
 *       real cell and real tank. {@code S*Ap} requests.</li>
 *   <li><b>F2 SPOT_TO_SPOT</b> — {@code for from : spots} then {@code for to : spots, to != from}, a synthetic
 *       PATROL at the {@code from} cell with a FULL tank. {@code S*(S-1)} requests.</li>
 *   <li><b>F3 AGENT_ROUTES</b> — {@code for agent : PATROL agents} then {@code for spot : spots}, agent at its
 *       real cell and real tank. {@code S*Ap} requests, the SAME tuple set as F1 in transposed order.</li>
 *   <li><b>F4 SUPPORT_LIFTED</b> — interleaved with F3 inside the same inner loop, same agent and cell with
 *       the tank lifted to PATROL capacity. {@code S*Ap} requests.</li>
 * </ul>
 *
 * <p>The reconstruction is not trusted on faith: the total is checked against the closed form
 * {@code S*(S-1) + 3*S*Ap} and the caller is expected to cross-check it against the probe-measured
 * {@code routeFinderCalls} of a real arm-A build on the same state.
 *
 * <p>Cost is measured with the production {@link WeightedRouteFinder} on the real captured state. The finder
 * is deterministic and stateless, so each DISTINCT key is timed {@code --samples} times and its MEDIAN is
 * attributed to every request carrying that key. That is deliberately not one-shot timing: a single sample
 * per call was already proven unreliable on this machine.
 *
 * <p>Reads only. Never runs in the live bot.
 */
public final class V3RouteFamilyProfiler {

    /** The four semantic route families, in the order the builder issues them. */
    public enum Family { F1_TRAVEL, F2_SPOT_TO_SPOT, F3_AGENT_ROUTES, F4_SUPPORT_LIFTED }

    /** Exactly what {@link WeightedRouteFinder#find} reads, and nothing else. */
    public record RouteKey(int origin, int initialFuel, int goal) { }

    /** One request the builder issues: its family and the key it carries. */
    public record Request(Family family, RouteKey key) { }

    private V3RouteFamilyProfiler() {
    }

    private static int fuelOf(AgentState agent) {
        return agent.fuel() instanceof FiniteFuel finite ? finite.amount() : -1;
    }

    /** The builder's request sequence, rebuilt in issue order. */
    public static List<Request> sequence(DayState state) {
        List<UdonSpot> spots = state.matchData().udonSpots().stream()
                .sorted(Comparator.comparingInt(value -> value.position().value())).toList();
        List<AgentState> patrols = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL).toList();
        int capacity = state.matchData().patrolFuelCapacity().value();
        List<Request> requests = new ArrayList<>();
        for (UdonSpot spot : spots) {
            for (AgentState agent : patrols) {
                requests.add(new Request(Family.F1_TRAVEL,
                        new RouteKey(agent.position().value(), fuelOf(agent), spot.position().value())));
            }
        }
        for (UdonSpot from : spots) {
            for (UdonSpot to : spots) {
                if (!from.position().equals(to.position())) {
                    requests.add(new Request(Family.F2_SPOT_TO_SPOT, new RouteKey(
                            from.position().value(), capacity, to.position().value())));
                }
            }
        }
        for (AgentState agent : patrols) {
            for (UdonSpot spot : spots) {
                requests.add(new Request(Family.F3_AGENT_ROUTES, new RouteKey(
                        agent.position().value(), fuelOf(agent), spot.position().value())));
                requests.add(new Request(Family.F4_SUPPORT_LIFTED, new RouteKey(
                        agent.position().value(), capacity, spot.position().value())));
            }
        }
        return requests;
    }

    /** Rebuilds the {@link AgentState} a key stands for. Only cell and tank matter to the finder. */
    private static AgentState agentFor(RouteKey key) {
        return AgentState.patrol(new AgentId(0), new Position(key.origin()), key.initialFuel());
    }

    /**
     * Median wall nanos per DISTINCT key, measured with the production finder on the captured state.
     * A full untimed sweep runs first so no key pays another key's JIT warm-up.
     */
    public static Map<RouteKey, Long> costPerKey(DayState state, Set<RouteKey> keys, int samples) {
        WeightedRouteFinder finder = new WeightedRouteFinder();
        for (RouteKey key : keys) {
            finder.find(state, agentFor(key), new Position(key.goal()));
        }
        Map<RouteKey, Long> median = new HashMap<>();
        long[] observations = new long[samples];
        for (RouteKey key : keys) {
            AgentState agent = agentFor(key);
            Position goal = new Position(key.goal());
            for (int sample = 0; sample < samples; sample++) {
                long started = System.nanoTime();
                finder.find(state, agent, goal);
                observations[sample] = System.nanoTime() - started;
            }
            long[] sorted = observations.clone();
            java.util.Arrays.sort(sorted);
            median.put(key, sorted[sorted.length / 2]);
        }
        return median;
    }

    private static long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.min(sorted.size() - 1L, Math.floor(fraction * sorted.size()));
        return sorted.get(index);
    }

    /**
     * Profiles one captured day and prints the per-family split plus the elimination cost comparison.
     *
     * <p>Arm A executes every request. Arm B executes exactly the FIRST request carrying each key, because
     * the memo is build scoped and shared by all four families — that is the memo's definition, not an
     * estimate, so the per-family arm-B execution counts are exact.
     */
    public static void profile(V3CorpusDay day, int samples, java.io.PrintStream out) {
        DayState state = day.rebuild();
        List<Request> requests = sequence(state);
        int spots = state.matchData().udonSpots().size();
        int patrols = (int) state.agents().stream().filter(a -> a.kind() == AgentKind.PATROL).count();
        long closedForm = (long) spots * (spots - 1) + 3L * spots * patrols;
        out.printf(Locale.ROOT, "ROUTE_FAMILY_LOADED match=%s day=%d map=%dx%d spots=%d patrols=%d"
                        + " capacity=%d stepBudget=%d samplesPerKey=%d%n",
                day.matchId(), day.day(), day.mapWidth(), day.mapHeight(), spots, patrols,
                state.matchData().patrolFuelCapacity().value(), state.stepBudget(), samples);
        out.printf(Locale.ROOT, "ROUTE_FAMILY_SEQUENCE reconstructedRequests=%d closedForm=%d match=%b%n",
                requests.size(), closedForm, requests.size() == closedForm);

        Set<RouteKey> distinct = new HashSet<>();
        requests.forEach(request -> distinct.add(request.key()));
        Map<RouteKey, Long> cost = costPerKey(state, distinct, samples);

        Set<RouteKey> seen = new HashSet<>();
        Map<Family, long[]> counters = new LinkedHashMap<>();
        Map<Family, Set<RouteKey>> unique = new LinkedHashMap<>();
        Map<Family, List<Long>> costs = new LinkedHashMap<>();
        for (Family family : Family.values()) {
            counters.put(family, new long[4]);
            unique.put(family, new HashSet<>());
            costs.put(family, new ArrayList<>());
        }
        List<Long> eliminated = new ArrayList<>();
        List<Long> surviving = new ArrayList<>();
        for (Request request : requests) {
            long nanos = cost.getOrDefault(request.key(), 0L);
            long[] slot = counters.get(request.family());
            slot[0]++;
            slot[1] += nanos;
            unique.get(request.family()).add(request.key());
            costs.get(request.family()).add(nanos);
            if (seen.add(request.key())) {
                slot[2]++;
                slot[3] += nanos;
                surviving.add(nanos);
            } else {
                eliminated.add(nanos);
            }
        }
        for (Family family : Family.values()) {
            printFamily(out, family.name(), counters.get(family), unique.get(family).size(),
                    costs.get(family));
        }
        long[] all = new long[4];
        List<Long> allCosts = new ArrayList<>();
        for (Family family : Family.values()) {
            long[] slot = counters.get(family);
            for (int index = 0; index < 4; index++) {
                all[index] += slot[index];
            }
            allCosts.addAll(costs.get(family));
        }
        printFamily(out, "ALL", all, distinct.size(), allCosts);
        printElimination(out, eliminated, surviving);
    }

    private static void printFamily(java.io.PrintStream out, String label, long[] slot, int uniqueKeys,
            List<Long> costs) {
        List<Long> sorted = new ArrayList<>(costs);
        java.util.Collections.sort(sorted);
        out.printf(Locale.ROOT, "ROUTE_FAMILY family=%s requests=%d uniqueQueries=%d duplicateQueries=%d"
                        + " armAExecutions=%d armBExecutions=%d armAExecutionsEliminated=%d"
                        + " armATotalNanos=%d armBTotalNanos=%d nanosEliminated=%d"
                        + " meanNanos=%d p50Nanos=%d p95Nanos=%d maxNanos=%d%n",
                label, slot[0], uniqueKeys, slot[0] - uniqueKeys, slot[0], slot[2], slot[0] - slot[2],
                slot[1], slot[3], slot[1] - slot[3],
                slot[0] == 0 ? 0 : slot[1] / slot[0],
                percentile(sorted, 0.50), percentile(sorted, 0.95),
                sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
    }

    /** The causal verdict: is the eliminated population cheaper, equal, or dearer than the survivors? */
    private static void printElimination(java.io.PrintStream out, List<Long> eliminated,
            List<Long> surviving) {
        long eliminatedTotal = eliminated.stream().mapToLong(Long::longValue).sum();
        long survivingTotal = surviving.stream().mapToLong(Long::longValue).sum();
        double meanEliminated = eliminated.isEmpty() ? 0 : (double) eliminatedTotal / eliminated.size();
        double meanSurviving = surviving.isEmpty() ? 0 : (double) survivingTotal / surviving.size();
        List<Long> sortedEliminated = new ArrayList<>(eliminated);
        List<Long> sortedSurviving = new ArrayList<>(surviving);
        java.util.Collections.sort(sortedEliminated);
        java.util.Collections.sort(sortedSurviving);
        out.printf(Locale.ROOT, "ROUTE_FAMILY_ELIMINATION eliminatedExecutions=%d survivingExecutions=%d"
                        + " executionReductionPct=%.2f eliminatedNanos=%d survivingNanos=%d"
                        + " nanosReductionPct=%.2f meanEliminatedNanos=%.0f meanSurvivingNanos=%.0f"
                        + " eliminatedOverSurvivingCostRatio=%.4f p50EliminatedNanos=%d"
                        + " p50SurvivingNanos=%d p95EliminatedNanos=%d p95SurvivingNanos=%d%n",
                eliminated.size(), surviving.size(),
                100.0 * eliminated.size() / Math.max(1, eliminated.size() + surviving.size()),
                eliminatedTotal, survivingTotal,
                100.0 * eliminatedTotal / Math.max(1, eliminatedTotal + survivingTotal),
                meanEliminated, meanSurviving,
                meanSurviving == 0 ? 0 : meanEliminated / meanSurviving,
                percentile(sortedEliminated, 0.50), percentile(sortedSurviving, 0.50),
                percentile(sortedEliminated, 0.95), percentile(sortedSurviving, 0.95));
        String verdict = meanSurviving == 0 ? "NO_DATA"
                : meanEliminated / meanSurviving >= 0.95 ? "ELIMINATES_AVERAGE_OR_EXPENSIVE_QUERIES"
                        : "ELIMINATES_ONLY_CHEAPER_THAN_AVERAGE_QUERIES";
        out.printf(Locale.ROOT, "ROUTE_FAMILY_VERDICT %s%n", verdict);
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(V3CorpusReplay.DEFAULT_CORPUS_DIRECTORY);
        String matchFilter = "";
        int samples = 5;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--corpus" -> root = Path.of(args[++index]);
                case "--match" -> matchFilter = args[++index];
                case "--samples" -> samples = Integer.parseInt(args[++index]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        final String match = matchFilter;
        List<V3CorpusDay> days = V3CorpusDay.loadAll(root, true).stream()
                .filter(day -> match.isEmpty() || day.matchId().equals(match))
                .toList();
        System.out.println("ROUTE_FAMILY_PROFILE_START root=" + root + " days=" + days.size());
        for (V3CorpusDay day : days) {
            profile(day, samples, System.out);
        }
        System.out.println("ROUTE_FAMILY_PROFILE_DONE days=" + days.size());
    }
}

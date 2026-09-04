package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;

/**
 * PART 23/30: how many opportunities each PATROL can enter the graph at, per support root, and how much of
 * the retained graph the bounded search actually touched.
 *
 * <p>The two entry-route numbers are deliberately different questions, and keeping them apart is the whole
 * PART 9 correction. {@code potentialEntryRoutes} counts pure geometry — under NO_REFUEL that is still the
 * historical fuel-filtered cache, under a mobile root it is the capacity-lifted one.
 * {@code supportFeasibleEntryRoutes} counts how many of those legs the PATROL may actually fly under THAT
 * tanker trajectory, decided by {@link SupportAwareTrajectoryScheduler} from the tank the PATROL really has.
 *
 * <p>Low graph coverage is not by itself a defect, and this audit does not claim it is. What it does show is
 * whether coverage is still limited by PATROLs that cannot enter the graph at all.
 */
public record V3SupportEntryRouteAudit(List<RootEntry> roots, int opportunities, int possibleDirectedEdges,
        int retainedStrategicEdges, int uniqueStrategicEdgesRequested, int trajectoryCacheEntries,
        int edgesActuallyExpanded, double graphCoverage) {

    public V3SupportEntryRouteAudit {
        roots = List.copyOf(Objects.requireNonNull(roots, "Root entries must not be null"));
    }

    /** One support root's entry-route picture. */
    public record RootEntry(String supportRootId, String signature, boolean mobile,
            Map<AgentId, Integer> potentialEntryRoutesByPatrol,
            Map<AgentId, Integer> supportFeasibleEntryRoutesByPatrol) {

        public RootEntry {
            Objects.requireNonNull(supportRootId, "Support root id must not be null");
            Objects.requireNonNull(signature, "Signature must not be null");
            potentialEntryRoutesByPatrol = Map.copyOf(Objects.requireNonNull(potentialEntryRoutesByPatrol));
            supportFeasibleEntryRoutesByPatrol = Map.copyOf(
                    Objects.requireNonNull(supportFeasibleEntryRoutesByPatrol));
        }

        public int potentialEntryRoutes() { return sum(potentialEntryRoutesByPatrol); }

        public int supportFeasibleEntryRoutes() { return sum(supportFeasibleEntryRoutesByPatrol); }

        /** PART 30: true when at least one PATROL can no longer enter the graph at all. */
        public boolean starvedPatrolPresent() {
            return supportFeasibleEntryRoutesByPatrol.values().stream().anyMatch(value -> value == 0);
        }

        private static int sum(Map<AgentId, Integer> values) {
            return values.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public String toString() {
            return supportRootId + " potential=" + ordered(potentialEntryRoutesByPatrol) + "("
                    + potentialEntryRoutes() + ") supportFeasible=" + ordered(supportFeasibleEntryRoutesByPatrol)
                    + "(" + supportFeasibleEntryRoutes() + ")";
        }

        private static String ordered(Map<AgentId, Integer> values) {
            return values.entrySet().stream()
                    .sorted(Comparator.comparingInt(entry -> entry.getKey().value()))
                    .map(entry -> entry.getKey().value() + "=" + entry.getValue()).toList().toString();
        }
    }

    /**
     * Measures every root in the universe against ONE already-built graph.
     *
     * <p>The graph is passed in rather than rebuilt so the numbers describe the graph the search really
     * used, and so this audit performs no pathfinding of its own: it reads the two caches the builder
     * already filled and asks {@link SupportAwareTrajectoryScheduler} about each cached leg.
     *
     * @param diagnostics the diagnostics of the run whose coverage is being reported, or {@code null} to
     *        report the entry-route half only
     */
    public static V3SupportEntryRouteAudit of(DayState state, StrategicOpportunityGraph graph,
            V3SupportRootUniverse universe, StrategicSearchDiagnostics diagnostics) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(graph, "Graph must not be null");
        Objects.requireNonNull(universe, "Universe must not be null");
        List<AgentState> patrols = state.agents().stream()
                .filter(agent -> agent.kind() == AgentKind.PATROL).toList();
        List<RootEntry> entries = new ArrayList<>();
        for (V3SupportRootContext root : universe.roots()) entries.add(entry(state, graph, root, patrols));
        int expanded = diagnostics == null ? 0 : diagnostics.graphEdgeExpansions()
                + diagnostics.crossRegionExpansions() + diagnostics.chainMacroExpansions();
        int retained = graph.retainedStrategicEdges();
        // PART 30: the trajectory cache is keyed per (agent, cached leg), so its size IS the number of
        // distinct strategic edges the search asked about. Both names are reported because both are named.
        int cacheEntries = diagnostics == null ? 0 : diagnostics.trajectoryCacheEntries();
        return new V3SupportEntryRouteAudit(entries, graph.opportunities().size(),
                graph.possibleDirectedEdges(), retained, cacheEntries, cacheEntries, expanded,
                retained == 0 ? 0.0 : (double) expanded / retained);
    }

    private static RootEntry entry(DayState state, StrategicOpportunityGraph graph,
            V3SupportRootContext root, List<AgentState> patrols) {
        Map<AgentId, Map<Position, Route>> cache = graph.entryRoutes(root.present());
        SupportAwareTrajectoryScheduler scheduler = SupportAwareTrajectoryScheduler.of(state,
                root.trajectory());
        Map<AgentId, Integer> potential = new LinkedHashMap<>();
        Map<AgentId, Integer> feasible = new LinkedHashMap<>();
        for (AgentState patrol : patrols) {
            Map<Position, Route> legs = cache.getOrDefault(patrol.id(), Map.of());
            potential.put(patrol.id(), legs.size());
            int fuel = ((FiniteFuel) patrol.fuel()).amount();
            int count = 0;
            for (Route leg : legs.values()) {
                CachedTrajectoryEffect effect = CachedTrajectoryEffect.from(state, patrol.id(), leg);
                if (scheduler.feasible(patrol.position(), 0, fuel, effect)) count++;
            }
            feasible.put(patrol.id(), count);
        }
        return new RootEntry(root.supportRootId(), root.signature(), root.present(), potential, feasible);
    }

    /** PART 30: the whole point of the audit — is any PATROL still locked out of the graph entirely? */
    public boolean starvedUnderEveryRoot() {
        return roots.stream().allMatch(RootEntry::starvedPatrolPresent);
    }

    public RootEntry noRefuel() {
        return roots.stream().filter(root -> !root.mobile()).findFirst().orElseThrow();
    }

    /** The mobile root that unlocks the most entry legs; {@code null} when the universe has none. */
    public RootEntry bestMobile() {
        return roots.stream().filter(RootEntry::mobile)
                .max(Comparator.comparingInt(RootEntry::supportFeasibleEntryRoutes)
                        .thenComparing(RootEntry::supportRootId, Comparator.reverseOrder()))
                .orElse(null);
    }
}

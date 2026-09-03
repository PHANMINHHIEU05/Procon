package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;

/**
 * PART 6 and PART 7 of Phase 2.4.
 *
 * <p>The audit never adds the missing allocation: it only classifies why V3's portfolio does or does not
 * contain the responsibility split the V2 witness embodies, and it prints the coverage/load trade for
 * every allocation V3 really did retain.
 */
public final class V3AllocationReplayAudit {

    public V3AllocationReplay audit(DayState state, V2StrategicWitness witness,
            StrategicOpportunityGraph graph, StrategicSearchConfig config, V3SearchCoverage coverage) {
        List<StrategicAllocation> generated = new StrategicAllocationGenerator()
                .generate(state, graph, config);
        Map<Position, Integer> regionOf = new LinkedHashMap<>();
        graph.regions().forEach(region -> region.members()
                .forEach(member -> regionOf.putIfAbsent(member.position(), region.regionId())));

        Map<Integer, List<Integer>> requiredTargets = new LinkedHashMap<>();
        Map<Integer, List<String>> requiredPrimaryRegions = new LinkedHashMap<>();
        Map<Integer, List<String>> requiredSecondaryRegions = new LinkedHashMap<>();
        for (V2StrategicWitness.PatrolSkeleton patrol : witness.patrols()) {
            requiredTargets.put(patrol.patrolId().value(), patrol.orderedStrategicCollections().stream()
                    .map(Position::value).toList());
            requiredPrimaryRegions.put(patrol.patrolId().value(),
                    labels(patrol.orderedStrategicCollections(), regionOf));
            requiredSecondaryRegions.put(patrol.patrolId().value(),
                    labels(patrol.intermediateTrajectoryCollections(), regionOf));
        }
        String sharedPattern = sharedRegionPattern(requiredPrimaryRegions);

        int requiredTotal = requiredTargets.values().stream().mapToInt(List::size).sum();
        String bestSignature = "NONE";
        double bestRatio = 0.0;
        int bestRank = -1;
        boolean exact = false;
        List<V3AllocationReplay.AllocationScore> scores = new ArrayList<>();
        Map<String, V3SearchCoverage.AllocationLoad> loads = coverage.allocationLoads().stream()
                .collect(Collectors.toMap(V3SearchCoverage.AllocationLoad::signature, value -> value,
                        (a, b) -> a, LinkedHashMap::new));
        for (int rank = 0; rank < generated.size(); rank++) {
            StrategicAllocation allocation = generated.get(rank);
            int covered = 0;
            boolean allExact = true;
            for (Map.Entry<Integer, List<Integer>> entry : requiredTargets.entrySet()) {
                List<Integer> primary = entry.getKey() < allocation.primaryTargets().size()
                        ? allocation.primaryTargets().get(entry.getKey()) : List.of();
                covered += (int) entry.getValue().stream().filter(primary::contains).count();
                if (!new LinkedHashSet<>(primary).equals(new LinkedHashSet<>(entry.getValue()))) {
                    allExact = false;
                }
            }
            double ratio = requiredTotal == 0 ? 1.0 : (double) covered / requiredTotal;
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestSignature = allocation.signature();
                bestRank = rank;
            }
            exact |= allExact;
            V3SearchCoverage.AllocationLoad load = loads.get(allocation.signature());
            scores.add(new V3AllocationReplay.AllocationScore(allocation.signature(),
                    allocation.supportClass(), covered, requiredTotal, ratio,
                    load == null ? 0 : load.statesGenerated(), load == null ? 0 : load.statesExpanded(),
                    load == null ? 0 : load.terminals(), load == null ? 0 : load.bestOwn()));
        }
        List<String> supportClasses = generated.stream().map(StrategicAllocation::supportClass).distinct()
                .toList();
        String requiredSupport = witness.supportRootSignature().equals("NONE") ? "NO_REFUEL"
                : "MOBILE_TANKER_TOUR:" + witness.supportRootSignature();
        boolean supportRepresented = requiredSupport.equals("NO_REFUEL")
                || supportClasses.stream().anyMatch(value -> value.contains("REFUEL")
                        || value.contains("SUPPORT") || value.contains("TANKER"));
        boolean retained = bestRank >= 0 && bestRank < generated.size();
        return new V3AllocationReplay(requiredTargets, requiredPrimaryRegions, requiredSecondaryRegions,
                sharedPattern, requiredSupport, supportClasses, supportRepresented, bestRatio > 0.0,
                retained, bestRank, exact, bestSignature, bestRatio, generated.size(), generated.size(),
                classify(supportRepresented, exact, bestRatio), scores);
    }

    private static String classify(boolean supportRepresented, boolean exact, double bestRatio) {
        if (!supportRepresented) {
            return "SUPPORT_AXIS_ABSENT_FROM_ALLOCATION_VOCABULARY";
        }
        if (exact) return "EXACT_ALLOCATION_PRESENT";
        if (bestRatio == 0.0) return "NO_ALLOCATION_PREFERS_ANY_V2_TARGET";
        return "PARTIAL_PREFERENCE_ONLY_SORT_BIAS_NOT_CONSTRAINT";
    }

    private static List<String> labels(List<Position> positions, Map<Position, Integer> regionOf) {
        return positions.stream().map(position -> regionOf.containsKey(position)
                ? "R" + regionOf.get(position) : "R?").toList();
    }

    private static String sharedRegionPattern(Map<Integer, List<String>> regions) {
        Map<String, Set<Integer>> byRegion = new LinkedHashMap<>();
        regions.forEach((patrolId, labels) -> labels.forEach(label ->
                byRegion.computeIfAbsent(label, key -> new LinkedHashSet<>()).add(patrolId)));
        List<String> shared = byRegion.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + "={" + entry.getValue().stream().map(String::valueOf)
                        .collect(Collectors.joining(",")) + "}").toList();
        return shared.isEmpty() ? "DISJOINT" : String.join(" ", shared);
    }
}

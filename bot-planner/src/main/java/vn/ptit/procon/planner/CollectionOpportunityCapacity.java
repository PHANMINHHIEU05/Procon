package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.udon.BrandId;

/** Bounded collection capacity for one stocked spot, independent of map topology. */
public record CollectionOpportunityCapacity(
        Position spot,
        BrandId brand,
        int availableStock,
        List<AgentId> reachablePatrols) {

    public CollectionOpportunityCapacity {
        Objects.requireNonNull(spot, "Capacity spot must not be null");
        Objects.requireNonNull(brand, "Capacity brand must not be null");
        if (availableStock < 0) {
            throw new IllegalArgumentException("Available stock must be non-negative");
        }
        LinkedHashSet<AgentId> unique = new LinkedHashSet<>();
        for (AgentId patrol : Objects.requireNonNull(reachablePatrols,
                "Reachable patrols must not be null")) {
            unique.add(Objects.requireNonNull(patrol, "Reachable patrol must not be null"));
        }
        List<AgentId> ordered = new ArrayList<>(unique);
        ordered.sort(java.util.Comparator.comparingInt(AgentId::value));
        reachablePatrols = List.copyOf(ordered);
    }

    /** The number of claims that can be consumed by distinct reachable PATROL arrivals. */
    public int boundedClaimCapacity() {
        return Math.min(availableStock, reachablePatrols.size());
    }
}

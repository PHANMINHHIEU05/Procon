package vn.ptit.procon.planner.oracle;

import java.util.List;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.udon.BrandId;

/** Compact, canonical explanation of the best own-team collection routes. */
public record OptimalCollectionSkeleton(List<AgentSkeleton> agents, List<SupportEvent> supportEvents) {
    public OptimalCollectionSkeleton {
        agents = List.copyOf(agents);
        supportEvents = List.copyOf(supportEvents);
    }

    public record AgentSkeleton(AgentId agentId, List<Visit> visits) {
        public AgentSkeleton { visits = List.copyOf(visits); }
    }

    public record Visit(int position, int arrivalStep, int claimedStock, BrandId brand,
            int fromPosition, int fuelBefore, int fuelAfter, int stockBefore, int stockAfter) {
        public Visit {
            if (arrivalStep < 0 || claimedStock < 0 || fromPosition < 0 || fuelBefore < 0
                    || fuelAfter < 0 || stockBefore < 0 || stockAfter < 0 || brand == null) {
                throw new IllegalArgumentException("Invalid collection skeleton visit");
            }
        }

        /** Compatibility constructor for the compact Phase 0.6 representation. */
        public Visit(int position, int arrivalStep, int claimedStock, BrandId brand) {
            this(position, arrivalStep, claimedStock, brand, position, 0, 0,
                    claimedStock, Math.max(0, claimedStock - 1));
        }
    }
    public record SupportEvent(int step, int position, String provenance) { }
}

package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

/**
 * The {@code V3_V2_BASELINE_REPRESENTABILITY} audit: can the V3 plan space express the frozen V2
 * strategy at all, transition by transition, plus the support-root and post-support replay verdicts.
 */
public record V3BaselineRepresentability(List<TransitionAudit> transitions, int totalV2Transitions,
        int representedTransitions, int missingTransitions, double coverageRatio, boolean fullyRepresentable,
        String firstMissingTransition, V3RepresentabilityReason firstMissingReason,
        SupportRootReplay supportRoot) {

    public V3BaselineRepresentability {
        transitions = List.copyOf(transitions);
        Objects.requireNonNull(firstMissingTransition);
        Objects.requireNonNull(firstMissingReason);
        Objects.requireNonNull(supportRoot);
    }

    /** One consecutive V2 strategic transition checked against the retained V3 graph. */
    public record TransitionAudit(AgentId patrolId, int index, Position source, Position destination,
            boolean graphNodeSourcePresent, boolean graphNodeDestinationPresent, boolean strategicEdgePresent,
            boolean cachedTrajectoryAvailable, boolean trajectoryMatchesV2Route, boolean supportRootCompatible,
            boolean postSupportStateCompatible, V3RepresentabilityReason reason) {
        public TransitionAudit {
            Objects.requireNonNull(patrolId);
            Objects.requireNonNull(source);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(reason);
        }

        public boolean represented() { return reason == V3RepresentabilityReason.REPRESENTED; }

        public String label() {
            return "patrol" + patrolId.value() + "#" + index + " " + source.value() + "->" + destination.value();
        }
    }

    /**
     * The {@code V2_WITNESS_SUPPORT_REPLAYABILITY} and {@code V2_WITNESS_POST_SUPPORT_STATE} verdicts.
     *
     * <p>{@code postPrefix*} compares the authoritative V2 state at the earliest support boundary with
     * the state V3's own root actually holds.
     */
    public record SupportRootReplay(String v2SupportRootSignature, int serviceCount,
            List<Integer> supportedPatrols, List<Integer> v2RefuelPositions, List<Integer> v3RefuelPositions,
            boolean rootGeneratedByV3, boolean rootRetainedByV3, boolean postPrefixPositionMatch,
            boolean postPrefixFuelMatch, boolean postPrefixTimelineMatch, boolean postPrefixStockMatch,
            boolean postPrefixBrandMatch, int postPrefixBoundaryStep, String v3SupportStates,
            int v3UniqueSupportClasses, int v3MovingSupportAgents) {
        public SupportRootReplay {
            Objects.requireNonNull(v2SupportRootSignature);
            supportedPatrols = List.copyOf(supportedPatrols);
            v2RefuelPositions = List.copyOf(v2RefuelPositions);
            v3RefuelPositions = List.copyOf(v3RefuelPositions);
            Objects.requireNonNull(v3SupportStates);
        }

        public boolean fullyReplayable() {
            return rootGeneratedByV3 && rootRetainedByV3 && postPrefixPositionMatch && postPrefixFuelMatch
                    && postPrefixTimelineMatch && postPrefixStockMatch && postPrefixBrandMatch;
        }
    }
}

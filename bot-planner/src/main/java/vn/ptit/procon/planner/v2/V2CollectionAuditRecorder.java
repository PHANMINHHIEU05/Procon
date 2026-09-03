package vn.ptit.procon.planner.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.engine.DayState;

/**
 * Test/benchmark-only lifecycle recorder. It deliberately observes the search and never
 * participates in ordering, deduplication, terminal selection, or legality decisions.
 */
final class V2CollectionAuditRecorder {
    private final DayState dayState;
    private final V2CollectionAuditMode mode;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<JointTeamSearchState, Node> byObject = new IdentityHashMap<>();
    private final Map<String, Node> firstByExactKey = new LinkedHashMap<>();
    private int nextStateId = 1;
    private int maxSecuredCollectionsSeen;
    private Node highWater;
    private int totalReachableTargetCandidates;
    private int candidatesBeforeTruncation;
    private int candidatesAfterConfiguredTopK;
    private int targetTruncationCount;
    private int stopStatesExpanded;
    private int stopOnlyExpansionCount;
    private int expansionsAfterPotentialZero;
    private int collectionsGainedAfterPotentialZero;
    private int higherCollectionStateLostToDedup;
    private int conservationViolations;
    private int bestTerminalOwnSemiCollections;
    private String bestTerminalOwnSignature = "";
    private int selectedTerminalSemiCollections;
    private String selectedTerminalSignature = "";

    V2CollectionAuditRecorder(DayState dayState, V2CollectionAuditMode mode) {
        this.dayState = Objects.requireNonNull(dayState, "Day state must not be null");
        this.mode = Objects.requireNonNull(mode, "Audit mode must not be null");
    }

    String candidate(JointTeamSearchState state, String parentStateId, int agentExpanded,
            int nextTargetsConsidered, int generatedChildren, JointPartialStateMetrics metrics) {
        if (mode == V2CollectionAuditMode.OFF) return "";
        Node node = new Node("S" + nextStateId++, state, parentStateId, agentExpanded,
                nextTargetsConsidered, generatedChildren, metrics);
        nodes.add(node);
        byObject.put(state, node);
        firstByExactKey.putIfAbsent(state.exactKey(), node);
        if (metrics.securedCollections() > maxSecuredCollectionsSeen) {
            maxSecuredCollectionsSeen = metrics.securedCollections();
            highWater = node;
        }
        return node.id;
    }

    void admitted(JointTeamSearchState state, boolean admitted) {
        Node node = byObject.get(state);
        if (node != null) node.admitted = admitted;
    }

    String idFor(JointTeamSearchState state) {
        Node node = firstByExactKey.get(state.exactKey());
        return node == null ? "" : node.id;
    }

    void rejected(JointTeamSearchState state, String reason, String dedupedInto) {
        Node node = byObject.get(state);
        if (node == null) return;
        node.rejectedReason = reason;
        node.dedupedIntoStateId = dedupedInto;
        if ("DEDUP".equals(reason) && highWater != null
                && node.metrics.securedCollections() > firstByExactKey.get(node.state.exactKey()).metrics.securedCollections()) {
            higherCollectionStateLostToDedup++;
        }
        if (node == highWater && node.rejectedReason != null) {
            node.highWaterLossReason = reason;
        }
    }

    void beamPruned(JointTeamSearchState state) {
        Node node = byObject.get(state);
        if (node != null) {
            node.prunedByBeam = true;
            if (node == highWater) node.highWaterLossReason = "BEAM_PRUNE";
        }
    }

    void expansion(JointTeamSearchState state, int reachableTargets, int beforeTruncation,
            int afterTopK, int truncations, int generatedChildren, boolean stopOnly,
            boolean potentialWasZero, int maximumChildCollections, String targetMenu) {
        Node node = byObject.get(state);
        if (node != null) node.targetMenu = targetMenu == null ? "" : targetMenu;
        totalReachableTargetCandidates += reachableTargets;
        candidatesBeforeTruncation += beforeTruncation;
        candidatesAfterConfiguredTopK += afterTopK;
        targetTruncationCount += truncations;
        if (stopOnly) {
            stopStatesExpanded++;
            stopOnlyExpansionCount++;
        }
        if (potentialWasZero) {
            expansionsAfterPotentialZero++;
            collectionsGainedAfterPotentialZero += Math.max(0,
                    maximumChildCollections - state.timeline().successfulCollections());
        }
    }

    void terminal(JointTeamSearchState state, JointTerminalEvaluator.StageATerminalCandidate stage) {
        Node node = byObject.get(state);
        if (node == null) return;
        node.materializedTerminal = true;
        node.terminalOwnSemiCollections = stage.semi().semiCommitmentRealizableCollections();
        node.terminalHybridMarginScore4 = null;
        node.terminalSignature = stage.base().deterministicSignature();
        if (node == highWater) {
            node.highWaterEventuallyMaterialized = true;
            node.highWaterEventualTerminalSemiCollections = node.terminalOwnSemiCollections;
            // Stage A semi attribution may intentionally discount a collection because of
            // opponent commitments. Raw simulator collections are the conservation invariant.
            if (stage.base().udonTotal() < node.metrics.securedCollections()) {
                conservationViolations++;
            }
        }
        if (node.terminalOwnSemiCollections > bestTerminalOwnSemiCollections
                || (node.terminalOwnSemiCollections == bestTerminalOwnSemiCollections
                && stage.base().deterministicSignature().compareTo(bestTerminalOwnSignature) < 0)) {
            bestTerminalOwnSemiCollections = node.terminalOwnSemiCollections;
            bestTerminalOwnSignature = stage.base().deterministicSignature();
        }
    }

    void selected(JointTerminalEvaluator.StageATerminalCandidate stage) {
        selectedTerminalSemiCollections = stage.semi().semiCommitmentRealizableCollections();
        selectedTerminalSignature = stage.base().deterministicSignature();
    }

    V2CollectionSearchAudit complete() {
        if (mode == V2CollectionAuditMode.OFF) return V2CollectionSearchAudit.empty();
        if (highWater != null && highWater.highWaterLossReason == null) {
            highWater.highWaterLossReason = highWater.materializedTerminal
                    ? (highWater.terminalOwnSemiCollections != null
                    && highWater.terminalOwnSemiCollections == selectedTerminalSemiCollections
                    ? "MATERIALIZED" : "MATERIALIZED_NOT_SELECTED")
                    : "NOT_MATERIALIZED";
        }
        return new V2CollectionSearchAudit(mode,
                nodes.stream().map(Node::toTrace).toList(), maxSecuredCollectionsSeen,
                highWater == null ? "NONE" : highWater.id,
                highWater == null ? 0 : highWater.metricsStateDepth(),
                highWater == null ? "NONE" : rootFamily(highWater.state),
                highWater == null ? List.of() : positions(highWater.state),
                highWater == null ? List.of() : elapsedSteps(highWater.state),
                highWater == null ? 0 : remainingFuel(highWater.state),
                highWater == null ? "{}" : stock(highWater.state),
                highWater != null && legalResources(highWater.state),
                highWater != null && highWater.highWaterEventuallyMaterialized,
                highWater == null ? null : highWater.highWaterEventualTerminalSemiCollections,
                highWater == null ? "NONE" : highWater.highWaterLossReason,
                totalReachableTargetCandidates, candidatesBeforeTruncation,
                candidatesAfterConfiguredTopK, targetTruncationCount, stopStatesExpanded,
                stopOnlyExpansionCount, expansionsAfterPotentialZero,
                collectionsGainedAfterPotentialZero, higherCollectionStateLostToDedup,
                conservationViolations, bestTerminalOwnSemiCollections, bestTerminalOwnSignature,
                selectedTerminalSemiCollections, selectedTerminalSignature);
    }

    private static String rootFamily(JointTeamSearchState state) {
        return state.refuelRoot().map(root -> "family-" + root.patrolSupports().size()).orElse("NO_REFUEL");
    }

    private static List<Integer> positions(JointTeamSearchState state) {
        return state.patrols().values().stream().sorted(Comparator.comparingInt(p -> p.id().value()))
                .map(p -> p.position().value()).toList();
    }

    private static int remainingFuel(JointTeamSearchState state) {
        return state.patrols().values().stream().mapToInt(JointTeamSearchState.PatrolPrefix::remainingFuel).sum();
    }

    private static List<Integer> elapsedSteps(JointTeamSearchState state) {
        return state.patrols().values().stream().sorted(Comparator.comparingInt(p -> p.id().value()))
                .map(JointTeamSearchState.PatrolPrefix::elapsedSteps).toList();
    }

    private static String stock(JointTeamSearchState state) {
        return state.timeline().remainingStock().entrySet().stream()
                .map(entry -> entry.getKey().value() + ":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private boolean legalResources(JointTeamSearchState state) {
        return state.patrols().values().stream().allMatch(p -> p.elapsedSteps() <= dayState.stepBudget()
                && p.remainingFuel() >= 0);
    }

    private static final class Node {
        private final String id;
        private final JointTeamSearchState state;
        private final String parentStateId;
        private final int agentExpanded;
        private final int nextTargetsConsidered;
        private final int generatedChildren;
        private final JointPartialStateMetrics metrics;
        private boolean admitted;
        private String rejectedReason = "";
        private String dedupedIntoStateId = "";
        private boolean prunedByBeam;
        private boolean materializedTerminal;
        private Integer terminalOwnSemiCollections;
        private Integer terminalHybridMarginScore4;
        private String terminalSignature = "";
        private String targetMenu = "";
        private boolean highWaterEventuallyMaterialized;
        private Integer highWaterEventualTerminalSemiCollections;
        private String highWaterLossReason;

        private Node(String id, JointTeamSearchState state, String parentStateId, int agentExpanded,
                int nextTargetsConsidered, int generatedChildren, JointPartialStateMetrics metrics) {
            this.id = id;
            this.state = state;
            this.parentStateId = parentStateId == null ? "" : parentStateId;
            this.agentExpanded = agentExpanded;
            this.nextTargetsConsidered = nextTargetsConsidered;
            this.generatedChildren = generatedChildren;
            this.metrics = metrics;
        }

        private int metricsStateDepth() { return state.strategicDecisionCount(); }

        private V2CollectionStateTrace toTrace() {
            return new V2CollectionStateTrace(id, parentStateId, state.strategicDecisionCount(),
                    rootFamily(state), agentExpanded, metrics.securedCollections(), metrics.securedBrands(),
                    metrics.remainingCollectionPotentialUpperBound(), metrics.usableStepCapacity(),
                    remainingFuel(state), nextTargetsConsidered, generatedChildren, admitted,
                    rejectedReason, dedupedIntoStateId, prunedByBeam, materializedTerminal,
                    terminalOwnSemiCollections, terminalHybridMarginScore4, positions(state), stock(state),
                    state.exactKey(), targetMenu, terminalSignature);
        }
    }
}

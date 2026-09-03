package vn.ptit.procon.planner.v3;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders the Phase 2.4 audit data as plain text.  Benchmark and test diagnostics only. */
public final class V3Phase24ReportPrinter {

    private final StringBuilder out = new StringBuilder();

    public static String render(Map<String, V3Phase24FixtureReport> reports) {
        V3Phase24ReportPrinter printer = new V3Phase24ReportPrinter();
        printer.table(reports);
        printer.scorecard(reports);
        reports.values().stream().filter(report -> report.fixture().contains("large-6-agent"))
                .forEach(printer::deepTrace);
        return printer.out.toString();
    }

    private void line(String value) { out.append(value).append(System.lineSeparator()); }

    private void table(Map<String, V3Phase24FixtureReport> reports) {
        line("== PHASE 2.4 TABLE ==");
        line(String.join("\t", "fixture", "v2Own", "v2H4", "rawOwn", "rawH4", "selOwn", "selH4",
                "representable", "forcedOwn", "firstDivergence", "expanded", "edges", "cache", "ms",
                "parity"));
        reports.values().forEach(report -> line(String.join("\t", report.tableRow())));
    }

    private void scorecard(Map<String, V3Phase24FixtureReport> reports) {
        V3Phase24Analysis.Scorecard card = V3Phase24Analysis.scorecard(reports);
        line("== SCORECARD ==");
        line("raw W/T/L=" + card.rawWins() + "/" + card.rawTies() + "/" + card.rawLosses()
                + " selected W/T/L=" + card.selectedWins() + "/" + card.selectedTies() + "/"
                + card.selectedLosses() + " catastrophic=" + card.rawCatastrophicRegressions());
        reports.values().forEach(report -> line("  " + report.fixture() + " rawDelta=" + report.rawDelta()
                + " selectedDelta=" + report.selectedDelta() + " raw=" + report.rawOutcome()
                + " selected=" + report.selectedOutcome() + " pathfinding="
                + report.searchPathfindingExecutions()));
    }

    private void deepTrace(V3Phase24FixtureReport report) {
        line("== DEEP TRACE " + report.fixture() + " ==");
        witness(report);
        representability(report);
        allocation(report);
        prefix(report);
        coverage(report);
        forced(report);
    }

    private void witness(V3Phase24FixtureReport report) {
        V2BaselineWitness witness = report.witness();
        line("V2 own=" + witness.ownSemiCollections() + " brands=" + witness.ownSemiBrands()
                + " coupled=" + witness.coupledOwnCollections() + " hybrid4=" + witness.hybridMarginScore4()
                + " validator=" + witness.validatorAccepted() + " simulator=" + witness.simulatorValid()
                + " traced=" + witness.tracedCollections());
        line("V2 support root=" + witness.support().rootSignature() + " services="
                + witness.support().serviceCount() + " supported=" + witness.support().supportedPatrols()
                + " refuelPositions=" + witness.support().refuelPositions().stream()
                        .map(position -> Integer.toString(position.value()))
                        .collect(Collectors.joining(",")));
        witness.support().services().forEach(service -> line("  service step=" + service.step()
                + " patrol=" + service.patrolId().value() + " pos=" + service.position().value()
                + " fuel " + service.before() + "->" + service.after()));
        witness.support().incidentalTopUps().forEach(service -> line("  incidental step=" + service.step()
                + " patrol=" + service.patrolId().value() + " pos=" + service.position().value()
                + " fuel " + service.before() + "->" + service.after()));
        line("V2 strategic skeleton: " + report.strategic().skeleton());
        report.strategic().patrols().forEach(patrol -> line("  p" + patrol.patrolId().value()
                + " start=" + patrol.start().value() + " fuel=" + patrol.startFuel()
                + " targets=" + patrol.orderedStrategicCollections().stream()
                        .map(position -> Integer.toString(position.value()))
                        .collect(Collectors.joining("->"))
                + " intermediate=" + patrol.intermediateTrajectoryCollections().size()
                + " stop=" + patrol.stopStep() + " support=" + patrol.supportProvenance()));
    }

    private void representability(V3Phase24FixtureReport report) {
        V3BaselineRepresentability value = report.representability();
        line("REPRESENTABILITY total=" + value.totalV2Transitions() + " represented="
                + value.representedTransitions() + " missing=" + value.missingTransitions()
                + " ratio=" + String.format("%.3f", value.coverageRatio())
                + " full=" + value.fullyRepresentable() + " firstMissing=" + value.firstMissingTransition()
                + " reason=" + value.firstMissingReason());
        value.transitions().forEach(audit -> line("  " + audit.label() + " nodeSrc="
                + audit.graphNodeSourcePresent() + " nodeDst=" + audit.graphNodeDestinationPresent()
                + " edge=" + audit.strategicEdgePresent() + " cached=" + audit.cachedTrajectoryAvailable()
                + " trajectory=" + audit.trajectoryMatchesV2Route() + " support="
                + audit.supportRootCompatible() + " state=" + audit.postSupportStateCompatible()
                + " reason=" + audit.reason()));
        V3BaselineRepresentability.SupportRootReplay root = value.supportRoot();
        line("SUPPORT ROOT v2=" + root.v2SupportRootSignature() + " services=" + root.serviceCount()
                + " supported=" + root.supportedPatrols() + " v2RefuelPositions=" + root.v2RefuelPositions()
                + " v3RefuelPositions=" + root.v3RefuelPositions() + " generated=" + root.rootGeneratedByV3()
                + " retained=" + root.rootRetainedByV3() + " position=" + root.postPrefixPositionMatch()
                + " fuel=" + root.postPrefixFuelMatch() + " timeline=" + root.postPrefixTimelineMatch()
                + " stock=" + root.postPrefixStockMatch() + " brand=" + root.postPrefixBrandMatch()
                + " boundary=" + root.postPrefixBoundaryStep() + " v3States=" + root.v3SupportStates()
                + " uniqueClasses=" + root.v3UniqueSupportClasses() + " movingSupport="
                + root.v3MovingSupportAgents());
    }

    private void allocation(V3Phase24FixtureReport report) {
        V3AllocationReplay value = report.allocation();
        line("ALLOCATION requiredTargets=" + value.requiredPrimaryTargetVector()
                + " primaryRegions=" + value.requiredPrimaryRegionVector()
                + " secondaryRegions=" + value.requiredSecondaryRegionVector()
                + " sharedPattern=" + value.sharedRegionPattern());
        line("ALLOCATION requiredSupport=" + value.requiredSupportClass() + " generatedSupportClasses="
                + value.generatedSupportClasses() + " supportRepresented=" + value.supportClassRepresented()
                + " generated=" + value.allocationGenerated() + " retained=" + value.allocationRetained()
                + " rank=" + value.allocationRank() + " exact=" + value.exactAllocationGenerated()
                + " bestCoverage=" + String.format("%.3f", value.bestCoverageRatio())
                + " classification=" + value.missingAllocationClassification());
        value.scores().forEach(score -> line("  alloc " + score.supportClass() + " covered="
                + score.v2TargetsCovered() + "/" + score.v2TargetsRequired() + " ratio="
                + String.format("%.3f", score.coverageRatio()) + " generated=" + score.statesGenerated()
                + " expanded=" + score.statesExpanded() + " terminals=" + score.terminals()
                + " bestOwn=" + score.bestOwn()));
    }

    private void prefix(V3Phase24FixtureReport report) {
        V3PrefixSurvivalTrace value = report.prefixSurvival();
        line("PREFIX requiredDepth=" + value.requiredDepth() + " deepestSurviving="
                + value.deepestSurvivingDepth() + " firstDivergenceDepth=" + value.firstDivergenceDepth()
                + " classification=" + value.classification() + " full=" + value.fullPrefixSurvived());
        line("PREFIX detail=" + value.firstDivergenceDetail());
        value.depths().forEach(depth -> line("  depth=" + depth.depth() + " generated=" + depth.generated()
                + " dedup=" + depth.retainedAfterDedup() + " dominance=" + depth.retainedAfterDominance()
                + " beam=" + depth.retainedAfterBeam() + " survived=" + depth.survived()
                + " rejected=" + depth.rejectedCandidates() + "/" + depth.dominantRejectionReason()
                + " required=" + depth.requiredDecision()));
    }

    private void coverage(V3Phase24FixtureReport report) {
        V3SearchCoverage value = report.coverage();
        line("CACHE available=" + value.strategicEdgesAvailable() + " requested="
                + value.strategicEdgesRequested() + " uniqueRequested="
                + value.uniqueStrategicEdgesRequested() + " entries=" + value.trajectoryCacheEntries()
                + " requests=" + value.trajectoryCacheRequests() + " hits=" + value.trajectoryCacheHits()
                + " misses=" + value.trajectoryCacheMisses() + " uniqueFrom=" + value.uniqueFromPositions()
                + " uniqueTo=" + value.uniqueToPositions() + " uniqueAgents=" + value.uniqueAgentContexts()
                + " exploredPct=" + String.format("%.2f", value.percentageOfGraphEdgesEverExplored()));
        line("GRAPH retained=" + value.retainedGraphEdges() + " eligible="
                + value.edgesEligibleFromVisitedStates() + " expanded=" + value.edgesActuallyExpanded()
                + " coveragePct=" + String.format("%.2f", value.graphCoverageDuringSearch())
                + " entryPoints=" + value.graphEntryPointsByPatrol()
                + " committedTargets=" + value.committedTargetsByPatrol());
        line("CHILDREN legal=" + value.legalStrategicEdges() + " beforeCap=" + value.childrenBeforeCap()
                + " afterCap=" + value.childrenAfterCap() + " fuel=" + value.rejectedByFuel()
                + " time=" + value.rejectedByTime() + " stock=" + value.rejectedByStock()
                + " allocation=" + value.rejectedByAllocation() + " support=" + value.rejectedBySupport()
                + " visited=" + value.rejectedByVisited() + " trajectory=" + value.rejectedByTrajectory()
                + " dedup=" + value.rejectedByDedup() + " other=" + value.rejectedByOther()
                + " quotaTruncations=" + value.quotaTruncations() + " outsideRegion="
                + value.edgesRejectedOnlyBecauseOutsideAssignedRegion() + " preferred="
                + value.allocationPreferredCandidates() + " zeroStock=" + value.candidatesWithZeroStock()
                + " alreadyClaimed=" + value.candidatesAlreadyClaimed());
        line("TERMINALS count=" + value.terminalCount() + " own min/med/p90/max=" + value.ownMin() + "/"
                + value.ownMedian() + "/" + value.ownP90() + "/" + value.ownMax()
                + " hybrid min/med/max=" + value.hybridMin() + "/" + value.hybridMedian() + "/"
                + value.hybridMax() + " atLeast1/5/10/14=" + value.terminalsWithOwnAtLeast1() + "/"
                + value.terminalsWithOwnAtLeast5() + "/" + value.terminalsWithOwnAtLeast10() + "/"
                + value.terminalsWithOwnAtLeast14());
        line("PREDICTION predictedWinner=" + value.predictedOwnOfWinner() + " materializedWinner="
                + value.materializedOwnOfWinner() + " matches=" + value.predictionMatchesMaterialization()
                + " mismatchedTerminals=" + value.terminalsWithPredictionMismatch()
                + " maxPredicted=" + value.maxPredictedOwn());
        line("ROUTE VECTORS " + value.distinctTerminalRouteVectors());
        value.allocationLoads().forEach(load -> line("  load " + load.supportClass() + " generated="
                + load.statesGenerated() + " unique=" + load.statesUnique() + " expanded="
                + load.statesExpanded() + " terminals=" + load.terminals() + " bestOwn=" + load.bestOwn()));
    }

    private void forced(V3Phase24FixtureReport report) {
        List.of(report.strictReplay(), report.supportGrantedReplay()).forEach(replay ->
                line("FORCED " + replay.mode() + " attempted=" + replay.transitionsAttempted()
                        + " resolved=" + replay.transitionsResolved() + " own=" + replay.reproducedOwn()
                        + " hybrid4=" + replay.reproducedHybrid4() + " materialized="
                        + replay.planMaterialized() + " validator=" + replay.validatorAccepted()
                        + " simulatorOwn=" + replay.simulatorOwn() + " firstMismatch="
                        + replay.firstMismatch() + " reason=" + replay.firstMismatchReason()
                        + " notes=" + replay.notes()));
    }
}

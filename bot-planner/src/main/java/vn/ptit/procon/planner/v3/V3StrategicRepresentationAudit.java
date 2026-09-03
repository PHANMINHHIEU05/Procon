package vn.ptit.procon.planner.v3;

import java.util.List;
import vn.ptit.procon.domain.map.Position;

/** Small helpers used by benchmark reports to keep coverage calculations consistent. */
public final class V3StrategicRepresentationAudit {
    private V3StrategicRepresentationAudit() {}

    public static V3ExactEdgeCoverage exactEdgeCoverage(StrategicOpportunityGraph graph,
            List<List<Position>> exactPaths) {
        return V3ExactEdgeCoverage.audit(graph, exactPaths);
    }

    public static String edgeBudget(StrategicOpportunityGraph graph) {
        return "opportunityCount=" + graph.opportunities().size()
                + ",possibleDirectedEdges=" + graph.possibleDirectedEdges()
                + ",retainedStrategicEdges=" + graph.retainedStrategicEdges()
                + ",averageOutgoingEdges=" + graph.averageOutgoingEdges()
                + ",maxOutgoingEdges=" + graph.maxOutgoingEdges()
                + ",localEdges=" + graph.localEdges()
                + ",crossRegionEdges=" + graph.crossRegionEdges()
                + ",longHopEdges=" + graph.longHopEdges();
    }
}

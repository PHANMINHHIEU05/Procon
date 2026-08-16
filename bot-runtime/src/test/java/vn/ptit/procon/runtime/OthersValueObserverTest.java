package vn.ptit.procon.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OthersValueObserverTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void disabledDiagnosticsEmitNothing() throws Exception {
        List<String> logs = new ArrayList<>();

        new OthersValueObserver(false, "m-secret").observe(
                0, json.readTree("[{\"id\":1,\"agents\":[]} ]"), 10, 8, logs::add);

        assertTrue(logs.isEmpty());
    }

    @Test
    void valuesAreBoundedAndUseNeutralRawLabelsWithPositionDiagnostics() throws Exception {
        List<String> logs = new ArrayList<>();
        StringBuilder payload = new StringBuilder("[");
        for (int group = 0; group < 5; group++) {
            if (group > 0) {
                payload.append(',');
            }
            payload.append("{\"id\":").append(group).append(",\"agents\":[");
            for (int agent = 0; agent < 7; agent++) {
                if (agent > 0) {
                    payload.append(',');
                }
                payload.append("{\"pos\":").append(agent == 0 ? 10 : agent)
                        .append(",\"kind\":").append(agent)
                        .append(",\"fuel\":").append(agent).append('}');
            }
            payload.append("]}");
        }
        payload.append(']');

        new OthersValueObserver(true, "m-secret").observe(
                0, json.readTree(payload.toString()), 5, 4, logs::add);

        assertEquals(4, logs.size());
        assertTrue(logs.get(0).startsWith("OTHERS_VALUES matchId=m-secret day=0 group=0 rawId=0 agents=7"));
        assertTrue(logs.get(0).contains("index=0,pos=10,rawKind=0,fuel=0,positionValid=false"));
        assertTrue(logs.get(0).contains("withinOwnPatrolFuelRange=true"));
        assertTrue(logs.get(1).startsWith("OTHERS_VALUES matchId=m-secret day=0 group=1 rawId=1 agents=7"));
        assertTrue(logs.get(2).startsWith("OTHERS_VALUES matchId=m-secret day=0 group=2 rawId=2 agents=7"));
        assertTrue(logs.stream().anyMatch(line -> line.startsWith("OTHERS_KIND_VALUES")
                && line.contains("values=[0, 1, 2, 3, 4, 5, 6] truncated=false")));
        assertFalse(logs.stream().anyMatch(line -> line.contains("teamId") || line.contains("PATROL")
                || line.contains("REFUEL")));
    }

    @Test
    void stabilityUsesSortedRawIdsAndGroupCountsAndPositionSetsOnly() throws Exception {
        List<String> logs = new ArrayList<>();
        OthersValueObserver observer = new OthersValueObserver(true, "m-live");

        observer.observe(0, json.readTree("""
                [{"id":12,"agents":[{"pos":1,"kind":0,"fuel":4}]},
                 {"id":4,"agents":[{"pos":8,"kind":1,"fuel":3}]}]
                """), 10, 8, logs::add);
        logs.clear();
        observer.observe(1, json.readTree("""
                [{"id":4,"agents":[{"pos":9,"kind":1,"fuel":2}]},
                 {"id":12,"agents":[{"pos":1,"kind":0,"fuel":4}]}]
                """), 10, 8, logs::add);

        assertTrue(logs.stream().anyMatch(line -> line.contains("OTHERS_STABILITY")
                && line.contains("outerCount=2")
                && line.contains("sameRawIdsAsPrevious=true")
                && line.contains("sameAgentCountsByRawId=true")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("OTHERS_POSITION_CHANGE")
                && line.contains("rawId=4")
                && line.contains("previousPositions=[8]")
                && line.contains("currentPositions=[9]")
                && line.contains("changed=true")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("rawId=12")
                && line.contains("changed=false")));
    }

    @Test
    void reorderedAgentIndexesDoNotCreateAPositionChangeOrIdentityClaim() throws Exception {
        List<String> logs = new ArrayList<>();
        OthersValueObserver observer = new OthersValueObserver(true, "m-live");

        observer.observe(0, json.readTree("""
                [{"id":7,"agents":[{"pos":1,"kind":0,"fuel":4},
                                     {"pos":2,"kind":1,"fuel":3}]}]
                """), 10, 8, logs::add);
        logs.clear();
        observer.observe(1, json.readTree("""
                [{"id":7,"agents":[{"pos":2,"kind":1,"fuel":3},
                                     {"pos":1,"kind":0,"fuel":4}]}]
                """), 10, 8, logs::add);

        assertTrue(logs.stream().anyMatch(line -> line.contains("OTHERS_POSITION_CHANGE")
                && line.contains("previousPositions=[1, 2]")
                && line.contains("currentPositions=[1, 2]")
                && line.contains("changed=false")));
    }
}
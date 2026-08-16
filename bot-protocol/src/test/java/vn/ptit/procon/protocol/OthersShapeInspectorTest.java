package vn.ptit.procon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OthersShapeInspectorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void absentAndEmptyNodesHaveExplicitShapes() throws Exception {
        assertEquals(
                new OthersShapeSummary("ABSENT", 0, "ABSENT", false),
                OthersShapeInspector.inspect(null));
        assertEquals(
                new OthersShapeSummary("ARRAY", 0, "ARRAY[]", false),
                OthersShapeInspector.inspect(json.readTree("[]")));
    }

    @Test
    void nestedShapeContainsNamesAndTypesButNeverValues() throws Exception {
        JsonNode others = json.readTree("""
                [{"team_id":"team-secret-value","agents":[
                    {"agent_id":7,"pos":12,"fuel":5,"active":true}]}]
                """);

        OthersShapeSummary summary = OthersShapeInspector.inspect(others);

        assertEquals("ARRAY", summary.nodeType());
        assertEquals(1, summary.entries());
        assertEquals(
                "ARRAY[OBJECT{agents:ARRAY[OBJECT{active:BOOLEAN,agent_id:NUMBER,"
                        + "fuel:NUMBER,pos:NUMBER}],team_id:STRING}]",
                summary.shape());
        assertFalse(summary.shape().contains("team-secret-value"));
        assertFalse(summary.shape().contains("12"));
        assertFalse(summary.truncated());
    }

    @Test
    void fieldOrderDoesNotChangeTheDiagnostic() throws Exception {
        JsonNode first = json.readTree("[{\"z\":1,\"a\":true,\"m\":\"value\"}]");
        JsonNode second = json.readTree("[{\"m\":\"other\",\"z\":2,\"a\":false}]");

        assertEquals(
                OthersShapeInspector.inspect(first),
                OthersShapeInspector.inspect(second));
    }

    @Test
    void largeOrUnsafeStructuresAreBoundedAndSanitized() throws Exception {
        StringBuilder source = new StringBuilder("[{");
        for (int index = 0; index < 20; index++) {
            if (index > 0) {
                source.append(',');
            }
            source.append('\"').append("unsafe field ").append(index).append("\":")
                    .append("\"never-log-this-value\"");
        }
        source.append("}]");

        OthersShapeSummary summary = OthersShapeInspector.inspect(json.readTree(source.toString()));

        assertTrue(summary.truncated());
        assertTrue(summary.shape().length() <= OthersShapeInspector.MAX_SHAPE_LENGTH + 3);
        assertFalse(summary.shape().contains(" "));
        assertFalse(summary.shape().contains("never-log-this-value"));
    }
}

package vn.ptit.procon.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;

class ObservedOthersParserTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ObservedOthersParser parser = new ObservedOthersParser();

    @Test
    void parsesTheExactLiveObservedShapeWithMultipleGroupsAndAgents() throws Exception {
        List<ObservedOtherGroup> groups = parser.parse(json.readTree("""
                [
                  {"id":12,"agents":[{"pos":31,"kind":0,"fuel":47},
                                     {"pos":32,"kind":1,"fuel":9}],"future":true},
                  {"id":4,"agents":[{"pos":8,"kind":1,"fuel":3}]},
                  {"id":19,"agents":[]}
                ]
                """));

        assertEquals(List.of(4, 12, 19), groups.stream().map(ObservedOtherGroup::rawId).toList());
        assertEquals(List.of(
                new ObservedOtherAgent(new Position(8), 1, 3)), groups.get(0).agents());
        assertEquals(List.of(
                new ObservedOtherAgent(new Position(31), 0, 47),
                new ObservedOtherAgent(new Position(32), 1, 9)), groups.get(1).agents());
        assertTrue(groups.get(2).agents().isEmpty());
    }

    @Test
    void absentEmptyAndMalformedGroupsAreSkippedWithoutFailing() throws Exception {
        List<ObservedOtherGroup> groups = parser.parse(json.readTree("""
                [
                  null,
                  {},
                  {"id":2},
                  {"id":3,"agents":{}},
                  {"id":4,"agents":[{"pos":1,"kind":0},
                                      {"pos":2,"kind":1,"fuel":5},
                                      {"pos":3,"kind":0,"fuel":6,"unknown":"ignored"}]},
                  "malformed"
                ]
                """));

        assertEquals(List.of(new ObservedOtherGroup(
                4, List.of(
                        new ObservedOtherAgent(new Position(2), 1, 5),
                        new ObservedOtherAgent(new Position(3), 0, 6)))), groups);
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse(json.readTree("[]")).isEmpty());
    }

    @Test
    void preservesRawValuesAndDoesNotUseAgentIndexAsAnIdentity() throws Exception {
        List<ObservedOtherGroup> first = parser.parse(json.readTree(
                "[{\"id\":7,\"agents\":[{\"pos\":1,\"kind\":9,\"fuel\":-2},"
                        + "{\"pos\":2,\"kind\":0,\"fuel\":4}]}]"));
        List<ObservedOtherGroup> reordered = parser.parse(json.readTree(
                "[{\"id\":7,\"agents\":[{\"pos\":2,\"kind\":0,\"fuel\":4},"
                        + "{\"pos\":1,\"kind\":9,\"fuel\":-2}]}]"));

        assertEquals(new ObservedOtherAgent(new Position(1), 9, -2), first.getFirst().agents().getFirst());
        assertEquals(new ObservedOtherAgent(new Position(2), 0, 4), reordered.getFirst().agents().getFirst());
        assertEquals(first.getFirst().rawId(), reordered.getFirst().rawId());
    }

    @Test
    void rejectsNumbersOutsideTheExistingIntegerSafetyRange() throws Exception {
        List<ObservedOtherGroup> groups = parser.parse(json.readTree(
                "[{\"id\":2147483648,\"agents\":[]},"
                        + "{\"id\":1,\"agents\":[{\"pos\":1,\"kind\":0,"
                        + "\"fuel\":2147483648}]}]"));

        assertEquals(List.of(new ObservedOtherGroup(1, List.of())), groups);
    }
}
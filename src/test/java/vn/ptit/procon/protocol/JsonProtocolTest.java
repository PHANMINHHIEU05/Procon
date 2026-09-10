package vn.ptit.procon.protocol;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;

import static org.junit.jupiter.api.Assertions.*;

class JsonProtocolTest {
    @Test
    void classifiesOfficialFramesByFieldsAndParsesNumericBrand() throws Exception {
        String setup = """
                {"map":{"width":2,"height":2,"cells":[[0,0],[0,0]]},"spots":[{"brand":3,"pos":1,"stocks":2}],"agents":[0],"daySteps":[4],"fuelLimits":60}
                """;
        var node = JsonProtocol.MAPPER.readTree(setup);
        assertTrue(JsonProtocol.isSetup(node));
        assertFalse(JsonProtocol.isDayState(node));
        assertEquals("3", JsonProtocol.setup(setup).spots().getFirst().brand());

        var day = JsonProtocol.MAPPER.readTree("{\"day\":0,\"agents\":[{\"kind\":0,\"pos\":0,\"fuel\":60}]}" );
        assertTrue(JsonProtocol.isDayState(day));
        assertFalse(JsonProtocol.isResult(day));
        var result = JsonProtocol.MAPPER.readTree("{\"standings\":[{\"team_id\":\"me\",\"rank\":1,\"udon_types\":4,\"daily_types_sum\":8,\"udon_total\":20,\"response_ms_total\":100}]}");
        assertTrue(JsonProtocol.isResult(result));
        Model.MatchResult parsed = JsonProtocol.result(result);
        assertEquals(1, parsed.standings().getFirst().rank());
    }

    @Test void reassemblesFragmentedTextBeforeJsonParsing() throws Exception {
        TextFrameAssembler assembler = new TextFrameAssembler();
        assertNull(assembler.accept("{\"day\":", false));
        String body = assembler.accept("0,\"agents\":[]}", true);
        assertEquals(0, JsonProtocol.state(body).day());
    }

    @Test void readsOwnNonSecretJournalFormatForParityAudit() throws Exception {
        String setup = """
                {"map":{"width":2,"height":1,"cells":[[0,0]]},"spots":[{"id":0,"brand":"A","position":1,"stock":2}],"startPositions":[0],"daySteps":[4],"fuelLimit":60}
                """;
        Model.Setup parsedSetup = JsonProtocol.setup(setup);
        assertEquals(0, parsedSetup.startPositions()[0]);
        assertEquals(1, parsedSetup.spots().getFirst().position());
        assertEquals(2, parsedSetup.spots().getFirst().stock());

        Model.DayState state = JsonProtocol.state("""
                {"day":0,"agents":[{"kind":"PATROL","position":0,"fuel":60}],"traffic":[{"position":0,"traffic":"CLEAR"}]}
                """);
        assertEquals(Model.AgentKind.PATROL, state.agents().getFirst().kind());
        assertEquals(0, state.agents().getFirst().position());
        assertEquals(Model.Traffic.CLEAR, state.traffic().getFirst().traffic());
    }
}

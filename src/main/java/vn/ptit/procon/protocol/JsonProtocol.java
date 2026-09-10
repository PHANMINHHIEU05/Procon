package vn.ptit.procon.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.model.Model.AgentKind;
import vn.ptit.procon.model.Model.AgentState;
import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.MapData;
import vn.ptit.procon.model.Model.MatchResult;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.Spot;
import vn.ptit.procon.model.Model.Standing;
import vn.ptit.procon.model.Model.Traffic;
import vn.ptit.procon.model.Model.TrafficCell;

import java.util.ArrayList;
import java.util.List;

public final class JsonProtocol {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonProtocol() {}

    public static Setup setup(String body) throws JsonProcessingException { return setup(MAPPER.readTree(body)); }
    public static Setup setup(JsonNode root) {
        JsonNode map = root.path("map");
        int width = map.path("width").asInt();
        int height = map.path("height").asInt();
        int[][] cells = new int[height][];
        for (int r = 0; r < height; r++) {
            JsonNode row = map.path("cells").get(r);
            cells[r] = new int[width];
            if (row != null && row.isArray()) for (int c = 0; c < width; c++) cells[r][c] = row.path(c).asInt();
        }
        List<Spot> spots = new ArrayList<>();
        int id = 0;
        for (JsonNode node : root.path("spots")) {
            JsonNode brand = node.get("brand");
            int position = node.has("pos") ? node.path("pos").asInt(-1) : node.path("position").asInt(-1);
            int stock = node.has("stocks") ? node.path("stocks").asInt() : node.path("stock").asInt();
            spots.add(new Spot(id++, brand == null ? "?" : brand.asText(), position, stock));
        }
        int[] agents = root.has("agents") && root.path("agents").isArray()
                && (root.path("agents").isEmpty() || root.path("agents").get(0).isInt())
                ? ints(root.path("agents")) : ints(root.path("startPositions"));
        int[] days = ints(root.path("daySteps"));
        int fuelLimit = root.has("fuelLimits") ? root.path("fuelLimits").asInt(60) : root.path("fuelLimit").asInt(60);
        return new Setup(new MapData(width, height, cells), spots, agents, days, fuelLimit);
    }

    public static DayState state(String body) throws JsonProcessingException { return state(MAPPER.readTree(body)); }
    public static DayState state(JsonNode root) {
        List<AgentState> agents = new ArrayList<>();
        for (JsonNode node : root.path("agents")) {
            JsonNode kind = node.path("kind");
            AgentKind agentKind = kind.isTextual() ? AgentKind.valueOf(kind.asText()) : AgentKind.fromCode(kind.asInt());
            int position = node.has("pos") ? node.path("pos").asInt(-1) : node.path("position").asInt(-1);
            agents.add(new AgentState(agentKind, position, node.path("fuel").asInt(0)));
        }
        List<TrafficCell> traffic = new ArrayList<>();
        JsonNode entries = root.has("traffics") ? root.path("traffics") : root.path("traffic");
        for (JsonNode node : entries) {
            int position = node.has("pos") ? node.path("pos").asInt(-1) : node.path("position").asInt(-1);
            JsonNode status = node.has("status") ? node.path("status") : node.path("traffic");
            Traffic value = status.isTextual() ? Traffic.valueOf(status.asText()) : Traffic.fromCode(status.asInt());
            traffic.add(new TrafficCell(position, value));
        }
        return new DayState(root.path("day").asInt(-1), agents, traffic);
    }

    public static MatchResult result(String body) throws JsonProcessingException { return result(MAPPER.readTree(body)); }
    public static MatchResult result(JsonNode root) {
        JsonNode rows = root.has("standings") ? root.get("standings") : root.path("result").path("standings");
        List<Standing> standings = new ArrayList<>();
        if (rows != null && rows.isArray()) for (JsonNode node : rows) {
            standings.add(new Standing(node.path("team_id").asText(node.path("teamId").asText("")),
                    node.path("rank").asInt(0), node.path("udon_types").asInt(node.path("global_types").asInt(0)),
                    node.path("daily_types_sum").asInt(node.path("dailyTypesSum").asInt(0)),
                    node.path("udon_total").asInt(node.path("portions").asInt(0)),
                    node.path("response_ms_total").asLong(node.path("responseMillis").asLong(0))));
        }
        return new MatchResult(standings);
    }

    public static int[] ints(JsonNode node) {
        int[] result = new int[node == null || !node.isArray() ? 0 : node.size()];
        for (int i = 0; i < result.length; i++) result[i] = node.path(i).asInt();
        return result;
    }

    public static String write(Object value) throws JsonProcessingException { return MAPPER.writeValueAsString(value); }

    public static boolean isSetup(JsonNode node) { return node.has("map") && node.has("daySteps"); }
    public static boolean isDayState(JsonNode node) { return node.has("day") && node.has("agents"); }
    public static boolean isResult(JsonNode node) { return node.has("standings") || node.path("result").has("standings"); }
}

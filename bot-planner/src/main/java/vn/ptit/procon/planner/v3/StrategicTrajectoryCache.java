package vn.ptit.procon.planner.v3;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.planner.Route;

/** Cache of deterministic trajectory effects for a single search day. */
public final class StrategicTrajectoryCache {
    private final DayState state;
    private final Map<String, CachedTrajectoryEffect> effects = new HashMap<>();
    private int requests;
    private int hits;

    public StrategicTrajectoryCache(DayState state) { this.state = Objects.requireNonNull(state); }

    public CachedTrajectoryEffect effect(AgentId agentId, Route route) {
        requests++;
        String key = agentId.value() + ":" + route.start().value() + ":" + route.goal().value()
                + ":" + route.directions().stream().map(d -> Integer.toString(d.code())).reduce("", String::concat);
        CachedTrajectoryEffect cached = effects.get(key);
        if (cached != null) {
            hits++;
            return cached;
        }
        CachedTrajectoryEffect created = CachedTrajectoryEffect.from(state, agentId, route);
        effects.put(key, created);
        return created;
    }

    public int size() { return effects.size(); }
    public int requests() { return requests; }
    public int hits() { return hits; }
    public int misses() { return requests - hits; }
}

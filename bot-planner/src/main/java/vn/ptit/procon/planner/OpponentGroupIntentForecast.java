package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;

/** Independent per-group M10 intent forecast. */
public record OpponentGroupIntentForecast(
        int groupRawId,
        List<OpponentAgentIntentForecast> agents) {

    public OpponentGroupIntentForecast {
        agents = List.copyOf(Objects.requireNonNull(agents, "Opponent agent forecasts must not be null"));
    }
}
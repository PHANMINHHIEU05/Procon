package vn.ptit.procon.domain.opponent;

import java.util.List;
import java.util.Objects;

/** Neutral immutable group keyed by the protocol's un-interpreted raw ID. */
public record ObservedOtherGroup(int rawId, List<ObservedOtherAgent> agents) {

    public ObservedOtherGroup {
        Objects.requireNonNull(agents, "Observed agents must not be null");
        agents = List.copyOf(agents);
    }
}
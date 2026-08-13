package vn.ptit.procon.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentId;

/** Canonically ordered immutable team snapshot after one elapsed step. */
public record TimelineStep(int step, Map<AgentId, AgentStepState> agents) {

    public TimelineStep {
        if (step < 0) {
            throw new IllegalArgumentException("Timeline step must be non-negative: " + step);
        }
        Objects.requireNonNull(agents, "Timeline agents must not be null");
        agents = Collections.unmodifiableMap(new LinkedHashMap<>(agents));
    }
}
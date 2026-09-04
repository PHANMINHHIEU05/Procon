package vn.ptit.procon.runtime;

import java.util.Objects;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.planner.v3.StrategicSearchConfig;
import vn.ptit.procon.planner.v3.V3ShadowEvaluation;
import vn.ptit.procon.planner.v3.V3ShadowPlanner;

/**
 * The production {@link V3ShadowEvaluator}: the thinnest possible bridge to the existing Phase 2.6 search.
 *
 * <p>It holds a stateless planner and one frozen {@link StrategicSearchConfig}. It duplicates no part of
 * the algorithm, and — because {@code bot-planner} has no dependency on {@code bot-protocol} — the class it
 * delegates to could not reach the wire even if it tried.
 */
public final class V3ShadowPlannerEvaluator implements V3ShadowEvaluator {

    private final V3ShadowPlanner planner = new V3ShadowPlanner();
    private final StrategicSearchConfig config;

    /** @param maxPlanningMillis the shadow observation budget; never a production action deadline */
    public V3ShadowPlannerEvaluator(long maxPlanningMillis) {
        this(V3ShadowPlanner.shadowConfig(maxPlanningMillis));
    }

    public V3ShadowPlannerEvaluator(StrategicSearchConfig config) {
        this.config = Objects.requireNonNull(config, "Shadow search config must not be null");
    }

    public StrategicSearchConfig config() {
        return config;
    }

    @Override
    public V3ShadowEvaluation evaluate(DayState state, TeamPlan submittedPlan) {
        return planner.evaluate(state, submittedPlan, config);
    }
}

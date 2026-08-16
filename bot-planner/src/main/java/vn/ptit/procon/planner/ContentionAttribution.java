package vn.ptit.procon.planner;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** Final returned-plan contention attribution; it does not affect PlanEvaluation. */
public record ContentionAttribution(
        int safeProjected,
        int tiedProjected,
        int contestedProjected,
        int unobservedProjected,
        int stronglyContestedProjected) {

    public ContentionAttribution {
        if (safeProjected < 0 || tiedProjected < 0 || contestedProjected < 0
                || unobservedProjected < 0 || stronglyContestedProjected < 0
                || stronglyContestedProjected > contestedProjected) {
            throw new IllegalArgumentException("Contention attribution is invalid");
        }
    }

    public static ContentionAttribution fromSimulation(
            DayState state,
            DaySimulationResult simulation,
            ContentionAnalyzer analyzer) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(simulation, "Simulation result must not be null");
        Objects.requireNonNull(analyzer, "Contention analyzer must not be null");
        return fromSimulation(state, simulation, position -> analyzer.analyze(state, position));
    }

    static ContentionAttribution fromSimulation(
            DayState state,
            DaySimulationResult simulation,
            Function<Position, ContentionMetrics> metricsForPosition) {
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return new ContentionAttribution(0, 0, 0, 0, 0);
        }

        int safe = 0;
        int tied = 0;
        int contested = 0;
        int unobserved = 0;
        int stronglyContested = 0;
        List<UdonCollectedEvent> collections = valid.events().stream()
                .filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .toList();
        for (UdonCollectedEvent collection : collections) {
            ContentionMetrics metrics = metricsForPosition.apply(collection.position());
            switch (metrics.classification()) {
                case SAFE -> safe++;
                case TIED -> tied++;
                case CONTESTED -> {
                    contested++;
                    if (metrics.distanceAdvantage().isPresent()
                            && metrics.distanceAdvantage().getAsInt() <= -2) {
                        stronglyContested++;
                    }
                }
                case UNOBSERVED -> unobserved++;
            }
        }
        return new ContentionAttribution(safe, tied, contested, unobserved, stronglyContested);
    }
}
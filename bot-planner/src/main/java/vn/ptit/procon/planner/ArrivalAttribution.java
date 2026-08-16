package vn.ptit.procon.planner;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** Final returned-plan arrival-aware contention attribution using simulator timeline step events. */
public record ArrivalAttribution(
        int arrivalSafeProjected,
        int arrivalTiedProjected,
        int arrivalAtRiskProjected,
        int unobservedProjected) {

    public ArrivalAttribution {
        if (arrivalSafeProjected < 0 || arrivalTiedProjected < 0
                || arrivalAtRiskProjected < 0 || unobservedProjected < 0) {
            throw new IllegalArgumentException("Arrival attribution metrics must be non-negative");
        }
    }

    public static ArrivalAttribution fromSimulation(
            DayState state,
            DaySimulationResult simulation,
            Map<Position, OptionalInt> opponentLowerBounds,
            ContentionAnalyzer analyzer) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(simulation, "Simulation result must not be null");
        Objects.requireNonNull(opponentLowerBounds, "Opponent lower bounds must not be null");
        Objects.requireNonNull(analyzer, "Contention analyzer must not be null");

        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return new ArrivalAttribution(0, 0, 0, 0);
        }

        int arrivalSafe = 0;
        int arrivalTied = 0;
        int arrivalAtRisk = 0;
        int unobserved = 0;

        List<UdonCollectedEvent> collections = valid.events().stream()
                .filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .toList();

        for (UdonCollectedEvent collection : collections) {
            OptionalInt oppBound = opponentLowerBounds.getOrDefault(collection.position(), OptionalInt.empty());
            ArrivalContentionMetrics metrics = analyzer.analyzeArrival(
                    collection.position(), collection.step(), oppBound);
            switch (metrics.classification()) {
                case ARRIVAL_SAFE -> arrivalSafe++;
                case ARRIVAL_TIED -> arrivalTied++;
                case ARRIVAL_AT_RISK -> arrivalAtRisk++;
                case UNOBSERVED -> unobserved++;
            }
        }

        return new ArrivalAttribution(arrivalSafe, arrivalTied, arrivalAtRisk, unobserved);
    }
}

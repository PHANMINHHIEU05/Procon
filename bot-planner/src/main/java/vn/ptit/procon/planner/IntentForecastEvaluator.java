package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.rules.MovementRules;
import vn.ptit.procon.domain.traffic.TrafficStatus;

/** Applies cached opponent intent stock pressure to own simulator collection events. */
public final class IntentForecastEvaluator {

    ForecastCollectionAssessment assessCollection(
            Map<Position, Integer> currentStock,
            Position spot,
            int step,
            OpponentIntentForecast forecast,
            IntentAdjustmentWeights weights) {
        return assess(currentStock, new ForecastOwnCollection(spot, step), forecast, weights, 0);
    }

    IntentRouteMetrics evaluateRoute(
            DayState state,
            Route route,
            int initialArrivalStep,
            Map<Position, Integer> branchStock,
            java.util.Set<Position> alreadyVisited,
            OpponentIntentForecast forecast,
            IntentAdjustmentWeights weights) {
        Map<Position, Integer> available = new HashMap<>(branchStock);
        java.util.Set<Position> visited = new java.util.LinkedHashSet<>(alreadyVisited);
        Position cursor = route.start();
        int currentStep = initialArrivalStep;
        int gain = 0;
        int score = 0;
        int realizable = 0;
        int claimedFirst = 0;
        int ties = 0;
        int unforecasted = 0;
        List<ForecastOwnCollection> events = new ArrayList<>();
        for (Direction direction : route.directions()) {
            TrafficStatus traffic = state.matchData().map().terrainAt(cursor) == Terrain.ROAD
                    ? state.roadTraffic().get(cursor) : null;
            MoveCost cost = MovementRules.costFromSource(state.matchData().map(), cursor, traffic).orElseThrow();
            currentStep += cost.stepCost();
            cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow();
            if (!visited.add(cursor) || available.getOrDefault(cursor, 0) <= 0) {
                continue;
            }
            available.put(cursor, available.get(cursor) - 1);
            events.add(new ForecastOwnCollection(cursor, currentStep));
        }
        for (ForecastOwnCollection event : events) {
            gain++;
            ForecastCollectionAssessment assessment = assess(
                    branchStock, event, forecast, weights, 0);
            score += assessment.intentValueUnits();
            if (assessment.forecastRealizable()) {
                realizable++;
            }
            switch (assessment.classification()) {
                case LIKELY_CLAIMED_FIRST -> claimedFirst++;
                case CONTESTED_TIE -> ties++;
                case UNFORECASTED -> unforecasted++;
                default -> {
                }
            }
        }
        return new IntentRouteMetrics(gain, score, realizable, claimedFirst, ties, unforecasted);
    }

    public IntentCollectionAttribution evaluate(
            DayState state,
            DaySimulationResult simulation,
            OpponentIntentForecast forecast,
            IntentAdjustmentWeights weights) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(simulation, "Simulation result must not be null");
        Objects.requireNonNull(forecast, "Opponent intent forecast must not be null");
        Objects.requireNonNull(weights, "Intent adjustment weights must not be null");
        if (!(simulation instanceof ValidDaySimulationResult valid)) {
            return empty();
        }

        List<UdonCollectedEvent> events = valid.events().stream()
                .filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .sorted(Comparator.comparingInt(UdonCollectedEvent::step)
                        .thenComparingInt(event -> event.agentId().value()))
                .toList();
        Map<Position, Integer> priorOwnCollections = new HashMap<>();
        List<ForecastCollectionAssessment> assessments = new ArrayList<>();
        int score = 0;
        int realizable = 0;
        int available = 0;
        int later = 0;
        int ties = 0;
        int claimedFirst = 0;
        int unforecasted = 0;

        for (UdonCollectedEvent event : events) {
            SpotIntentPressure pressure = forecast.pressureAt(event.position());
            int currentStock = state.spotStock().getOrDefault(event.position(), 0);
            int ownBefore = priorOwnCollections.getOrDefault(event.position(), 0);
            int opponentBefore = pressure == null ? 0 : (int) pressure.claims().stream()
                    .filter(claim -> claim.forecastArrivalStep() < event.step())
                    .count();
            int remaining = Math.max(0, currentStock - ownBefore - opponentBefore);
            boolean tie = pressure != null && pressure.claims().stream()
                    .anyMatch(claim -> claim.forecastArrivalStep() == event.step());
            boolean opponentLater = pressure != null && pressure.claims().stream()
                    .anyMatch(claim -> claim.forecastArrivalStep() > event.step());
            IntentCollectionClassification classification;
            if (pressure == null || pressure.intentSourceCount() == 0) {
                classification = IntentCollectionClassification.UNFORECASTED;
                unforecasted++;
            } else if (remaining == 0) {
                classification = IntentCollectionClassification.LIKELY_CLAIMED_FIRST;
                claimedFirst++;
            } else if (tie) {
                classification = IntentCollectionClassification.CONTESTED_TIE;
                ties++;
            } else if (opponentLater) {
                classification = IntentCollectionClassification.CONTESTED_LATER;
                later++;
            } else {
                classification = IntentCollectionClassification.LIKELY_AVAILABLE;
                available++;
            }
            int units = weights.weightFor(classification);
            score = Math.addExact(score, units);
            if (remaining > 0) {
                realizable++;
            }
            assessments.add(new ForecastCollectionAssessment(
                    event.position(), event.step(), classification, remaining, remaining > 0, units));
            priorOwnCollections.merge(event.position(), 1, Integer::sum);
        }
        return new IntentCollectionAttribution(
                new IntentAdjustedCollectionScore(score),
                realizable,
                available,
                later,
                ties,
                claimedFirst,
                unforecasted,
                assessments);
    }

    private ForecastCollectionAssessment assess(
            Map<Position, Integer> currentStock,
            ForecastOwnCollection event,
            OpponentIntentForecast forecast,
            IntentAdjustmentWeights weights,
            int ownCollectionsBefore) {
        SpotIntentPressure pressure = forecast.pressureAt(event.spot());
        int stock = currentStock.getOrDefault(event.spot(), 0);
        int opponentBefore = pressure == null ? 0 : (int) pressure.claims().stream()
                .filter(claim -> claim.forecastArrivalStep() < event.step())
                .count();
        int remaining = Math.max(0, stock - ownCollectionsBefore - opponentBefore);
        boolean tie = pressure != null && pressure.claims().stream()
                .anyMatch(claim -> claim.forecastArrivalStep() == event.step());
        boolean opponentLater = pressure != null && pressure.claims().stream()
                .anyMatch(claim -> claim.forecastArrivalStep() > event.step());
        IntentCollectionClassification classification;
        if (pressure == null || pressure.intentSourceCount() == 0) {
            classification = IntentCollectionClassification.UNFORECASTED;
        } else if (remaining == 0) {
            classification = IntentCollectionClassification.LIKELY_CLAIMED_FIRST;
        } else if (tie) {
            classification = IntentCollectionClassification.CONTESTED_TIE;
        } else if (opponentLater) {
            classification = IntentCollectionClassification.CONTESTED_LATER;
        } else {
            classification = IntentCollectionClassification.LIKELY_AVAILABLE;
        }
        return new ForecastCollectionAssessment(
                event.spot(), event.step(), classification, remaining, remaining > 0,
                weights.weightFor(classification));
    }

    private IntentCollectionAttribution empty() {
        return new IntentCollectionAttribution(
                new IntentAdjustedCollectionScore(0), 0, 0, 0, 0, 0, 0, List.of());
    }

    private record ForecastOwnCollection(Position spot, int step) {
    }
}
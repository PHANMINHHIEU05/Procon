package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DaySimulationResult;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.rules.MovementRules;

/**
 * Applies M12 commitment-classified opponent claims to own simulator collection events.
 *
 * <p>The central M12 correction lives in {@link #assess}: only {@code OBSERVED_NOW} claims delete
 * stock from the hard-realizable forecast, and only when they arrive strictly before us. Future
 * intent still shapes the score and the ordering, but it can no longer erase a projected collection
 * outright, which is what made the M10 forecast under-count realized collections in the field.</p>
 *
 * <p>The unchanged M10 realizable count is produced in the same pass, so both numbers always
 * describe the identical plan, forecast and event ordering.</p>
 */
public final class CommitmentForecastEvaluator {

    CommitmentCollectionAssessment assessCollection(
            Map<Position, Integer> currentStock,
            Position spot,
            int step,
            OpponentCommitmentForecast forecast,
            CommitmentAdjustmentWeights weights) {
        return assess(currentStock, spot, step, forecast, weights, 0).commitment();
    }

    CommitmentRouteMetrics evaluateRoute(
            DayState state,
            Map<Position, UdonSpot> spotsByPosition,
            Route route,
            int initialArrivalStep,
            Map<Position, Integer> branchStock,
            Set<Position> alreadyVisited,
            Set<BrandId> alreadyRealizableTeamBrands,
            OpponentCommitmentForecast forecast,
            CommitmentAdjustmentWeights weights) {
        Map<Position, Integer> available = new HashMap<>(branchStock);
        Set<Position> visited = new LinkedHashSet<>(alreadyVisited);
        Position cursor = route.start();
        int currentStep = initialArrivalStep;
        List<ProjectedOwnCollection> events = new ArrayList<>();
        for (Direction direction : route.directions()) {
            TrafficStatus traffic = state.matchData().map().terrainAt(cursor) == Terrain.ROAD
                    ? state.roadTraffic().get(cursor) : null;
            MoveCost cost = MovementRules.costFromSource(
                    state.matchData().map(), cursor, traffic).orElseThrow();
            currentStep += cost.stepCost();
            cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow();
            if (!visited.add(cursor) || available.getOrDefault(cursor, 0) <= 0) {
                continue;
            }
            available.put(cursor, available.get(cursor) - 1);
            events.add(new ProjectedOwnCollection(cursor, currentStep));
        }

        int gain = 0;
        int score = 0;
        int realizable = 0;
        int hardClaimedFirst = 0;
        int directBefore = 0;
        int followOnBefore = 0;
        int ties = 0;
        int unforecasted = 0;
        Set<BrandId> realizableBrands = new LinkedHashSet<>();
        for (ProjectedOwnCollection event : events) {
            gain++;
            CommitmentCollectionAssessment assessment = assess(
                    branchStock, event.spot(), event.step(), forecast, weights, 0).commitment();
            score += assessment.commitmentValueUnits();
            if (assessment.commitmentRealizable()) {
                realizable++;
                UdonSpot spot = spotsByPosition.get(event.spot());
                if (spot != null) {
                    realizableBrands.add(spot.brand());
                }
            }
            switch (assessment.classification()) {
                case HARD_CLAIMED_FIRST -> hardClaimedFirst++;
                case DIRECT_INTENT_BEFORE -> directBefore++;
                case FOLLOW_ON_INTENT_BEFORE -> followOnBefore++;
                case CONTESTED_TIE -> ties++;
                case UNFORECASTED -> unforecasted++;
                case LIKELY_AVAILABLE -> {
                }
            }
        }
        int brandGain = (int) realizableBrands.stream()
                .filter(brand -> !alreadyRealizableTeamBrands.contains(brand))
                .count();
        return new CommitmentRouteMetrics(
                gain, score, realizable, hardClaimedFirst, directBefore, followOnBefore, ties,
                unforecasted, realizableBrands, brandGain);
    }

    /**
     * Full commitment attribution of one complete plan, together with the unchanged M10
     * forecast-realizable count for the same plan.
     */
    public CommitmentCollectionAttribution evaluate(
            DayState state,
            DaySimulationResult simulation,
            OpponentCommitmentForecast forecast,
            CommitmentAdjustmentWeights weights) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(simulation, "Simulation result must not be null");
        Objects.requireNonNull(forecast, "Opponent commitment forecast must not be null");
        Objects.requireNonNull(weights, "Commitment adjustment weights must not be null");
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
        List<CommitmentCollectionAssessment> assessments = new ArrayList<>();
        Set<BrandId> localBrands = new LinkedHashSet<>();
        Set<BrandId> realizableBrands = new LinkedHashSet<>();
        int score = 0;
        int realizable = 0;
        int oldRealizable = 0;
        int hardClaimedFirst = 0;
        int directBefore = 0;
        int followOnBefore = 0;
        int ties = 0;
        int available = 0;
        int unforecasted = 0;

        for (UdonCollectedEvent event : events) {
            int ownBefore = priorOwnCollections.getOrDefault(event.position(), 0);
            DualAssessment dual = assess(
                    state.spotStock(), event.position(), event.step(), forecast, weights, ownBefore);
            CommitmentCollectionAssessment assessment = dual.commitment();
            score = Math.addExact(score, assessment.commitmentValueUnits());
            localBrands.add(event.brand());
            if (assessment.commitmentRealizable()) {
                realizable++;
                realizableBrands.add(event.brand());
            }
            if (dual.oldForecastRemainingStock() > 0) {
                oldRealizable++;
            }
            switch (assessment.classification()) {
                case HARD_CLAIMED_FIRST -> hardClaimedFirst++;
                case DIRECT_INTENT_BEFORE -> directBefore++;
                case FOLLOW_ON_INTENT_BEFORE -> followOnBefore++;
                case CONTESTED_TIE -> ties++;
                case LIKELY_AVAILABLE -> available++;
                case UNFORECASTED -> unforecasted++;
            }
            assessments.add(assessment);
            priorOwnCollections.merge(event.position(), 1, Integer::sum);
        }
        return new CommitmentCollectionAttribution(
                new CommitmentAdjustedCollectionScore(score),
                realizable,
                oldRealizable,
                hardClaimedFirst,
                directBefore,
                followOnBefore,
                ties,
                available,
                unforecasted,
                localBrands,
                realizableBrands,
                assessments);
    }

    /**
     * Classifies one projected own collection and, in the same pass, reports what the M10 binary
     * stock model would have left at our arrival.
     *
     * <p>Hard depletion is {@code min(currentStock, observedNowClaimsStrictlyBefore)}: capacity
     * aware, strictly ordered and never negative. Direct and follow-on claims are read only to pick
     * the risk class, never to remove a portion.</p>
     */
    private DualAssessment assess(
            Map<Position, Integer> currentStock,
            Position spot,
            int step,
            OpponentCommitmentForecast forecast,
            CommitmentAdjustmentWeights weights,
            int ownCollectionsBefore) {
        SpotCommitmentPressure pressure = forecast.pressureAt(spot);
        int stock = currentStock.getOrDefault(spot, 0);
        int hardBefore = pressure == null ? 0 : pressure.hardConsumedStrictlyBefore(step);
        int remaining = Math.max(0, stock - ownCollectionsBefore - hardBefore);
        int allClaimsBefore = pressure == null ? 0 : (int) pressure.claims().stream()
                .filter(claim -> claim.forecastArrivalStep() < step)
                .count();
        int oldRemaining = Math.max(0, stock - ownCollectionsBefore - allClaimsBefore);

        CommitmentCollectionClassification classification;
        if (pressure == null || pressure.claims().isEmpty()) {
            classification = CommitmentCollectionClassification.UNFORECASTED;
        } else if (remaining == 0) {
            classification = CommitmentCollectionClassification.HARD_CLAIMED_FIRST;
        } else if (pressure.anyBefore(OpponentClaimCommitment.DIRECT_INTENT, step)) {
            classification = CommitmentCollectionClassification.DIRECT_INTENT_BEFORE;
        } else if (pressure.anyBefore(OpponentClaimCommitment.FOLLOW_ON_INTENT, step)) {
            classification = CommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE;
        } else if (pressure.anyAt(step)) {
            classification = CommitmentCollectionClassification.CONTESTED_TIE;
        } else {
            classification = CommitmentCollectionClassification.LIKELY_AVAILABLE;
        }
        return new DualAssessment(
                new CommitmentCollectionAssessment(
                        spot, step, classification, remaining, remaining > 0,
                        weights.weightFor(classification)),
                oldRemaining);
    }

    private CommitmentCollectionAttribution empty() {
        return new CommitmentCollectionAttribution(
                new CommitmentAdjustedCollectionScore(0), 0, 0, 0, 0, 0, 0, 0, 0,
                Set.of(), Set.of(), List.of());
    }

    private record ProjectedOwnCollection(Position spot, int step) {
    }

    /** M12 classification of one collection plus the M10 remaining stock for the same event. */
    private record DualAssessment(
            CommitmentCollectionAssessment commitment,
            int oldForecastRemainingStock) {
    }
}

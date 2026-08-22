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
 * Applies the M12.1 bounded semi-reservation to own simulator collection events.
 *
 * <p>The whole model lives in {@link #assess}. Hard depletion is unchanged from M12: only
 * {@code OBSERVED_NOW} claims strictly before us delete stock. On top of that, M12.1 reserves
 * <em>at most one</em> further portion per spot when any {@code DIRECT_INTENT} claim arrives strictly
 * before us — one portion whether one opponent or five are forecast to arrive there. That per-spot cap
 * is the entire difference between this middle model and the M10 binary model it replaces, and the
 * reason five extra direct claimers on an already-reserved spot cannot keep depleting it.</p>
 *
 * <p>{@code FOLLOW_ON_INTENT} never reserves anything. It only shapes the score.</p>
 *
 * <p>All three calibration counts — M10 binary, M12.1 semi, M12 hard-only — are produced in the same
 * pass, so they always describe the identical plan, forecast and event ordering.</p>
 */
public final class SemiCommitmentForecastEvaluator {

    SemiCommitmentCollectionAssessment assessCollection(
            Map<Position, Integer> currentStock,
            Position spot,
            int step,
            OpponentCommitmentForecast forecast,
            SemiCommitmentAdjustmentWeights weights) {
        return assess(currentStock, spot, step, forecast, weights, 0).semi();
    }

    SemiCommitmentRouteMetrics evaluateRoute(
            DayState state,
            Map<Position, UdonSpot> spotsByPosition,
            Route route,
            int initialArrivalStep,
            Map<Position, Integer> branchStock,
            Set<Position> alreadyVisited,
            Set<BrandId> alreadyRealizableTeamBrands,
            OpponentCommitmentForecast forecast,
            SemiCommitmentAdjustmentWeights weights) {
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
        int semiClaimedFirst = 0;
        int directBefore = 0;
        int followOnBefore = 0;
        int ties = 0;
        int unforecasted = 0;
        Set<BrandId> realizableBrands = new LinkedHashSet<>();
        for (ProjectedOwnCollection event : events) {
            gain++;
            SemiCommitmentCollectionAssessment assessment = assess(
                    branchStock, event.spot(), event.step(), forecast, weights, 0).semi();
            score += assessment.semiCommitmentValueUnits();
            if (assessment.semiCommitmentRealizable()) {
                realizable++;
                UdonSpot spot = spotsByPosition.get(event.spot());
                if (spot != null) {
                    realizableBrands.add(spot.brand());
                }
            }
            switch (assessment.classification()) {
                case HARD_CLAIMED_FIRST -> hardClaimedFirst++;
                case SEMI_CLAIMED_FIRST -> semiClaimedFirst++;
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
        return new SemiCommitmentRouteMetrics(
                gain, score, realizable, hardClaimedFirst, semiClaimedFirst, directBefore,
                followOnBefore, ties, unforecasted, realizableBrands, brandGain);
    }

    /**
     * Full M12.1 attribution of one complete plan, together with the unchanged M12 and M10
     * realizable counts for the same plan.
     */
    public SemiCommitmentCollectionAttribution evaluate(
            DayState state,
            DaySimulationResult simulation,
            OpponentCommitmentForecast forecast,
            SemiCommitmentAdjustmentWeights weights) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(simulation, "Simulation result must not be null");
        Objects.requireNonNull(forecast, "Opponent commitment forecast must not be null");
        Objects.requireNonNull(weights, "Semi-commitment adjustment weights must not be null");
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
        List<SemiCommitmentCollectionAssessment> assessments = new ArrayList<>();
        Set<BrandId> localBrands = new LinkedHashSet<>();
        Set<BrandId> realizableBrands = new LinkedHashSet<>();
        int score = 0;
        int realizable = 0;
        int commitmentRealizable = 0;
        int oldRealizable = 0;
        int hardClaimedFirst = 0;
        int semiClaimedFirst = 0;
        int directBefore = 0;
        int followOnBefore = 0;
        int ties = 0;
        int available = 0;
        int unforecasted = 0;

        for (UdonCollectedEvent event : events) {
            int ownBefore = priorOwnCollections.getOrDefault(event.position(), 0);
            TriAssessment tri = assess(
                    state.spotStock(), event.position(), event.step(), forecast, weights, ownBefore);
            SemiCommitmentCollectionAssessment assessment = tri.semi();
            score = Math.addExact(score, assessment.semiCommitmentValueUnits());
            localBrands.add(event.brand());
            if (assessment.semiCommitmentRealizable()) {
                realizable++;
                realizableBrands.add(event.brand());
            }
            if (tri.commitmentRemainingStock() > 0) {
                commitmentRealizable++;
            }
            if (tri.oldForecastRemainingStock() > 0) {
                oldRealizable++;
            }
            switch (assessment.classification()) {
                case HARD_CLAIMED_FIRST -> hardClaimedFirst++;
                case SEMI_CLAIMED_FIRST -> semiClaimedFirst++;
                case DIRECT_INTENT_BEFORE -> directBefore++;
                case FOLLOW_ON_INTENT_BEFORE -> followOnBefore++;
                case CONTESTED_TIE -> ties++;
                case LIKELY_AVAILABLE -> available++;
                case UNFORECASTED -> unforecasted++;
            }
            assessments.add(assessment);
            priorOwnCollections.merge(event.position(), 1, Integer::sum);
        }
        return new SemiCommitmentCollectionAttribution(
                new SemiCommitmentAdjustedCollectionScore(score),
                realizable,
                commitmentRealizable,
                oldRealizable,
                hardClaimedFirst,
                semiClaimedFirst,
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
     * Classifies one own collection under all three stock models at once.
     *
     * <p>Capacity is charged in a fixed order — our own earlier collections at this spot, then hard
     * opponent depletion, then the single bounded direct reservation — so hard loss always consumes
     * capacity before the weaker semi evidence does, and the reservation can only take a portion that
     * actually survived. Every subtraction is floored at zero: forecast stock never goes negative.</p>
     *
     * <p>The semi reservation is recomputed per collection event and is always zero or one; it is never
     * accumulated across our own repeated collections at the same spot, because it represents one
     * reserved portion at that spot rather than a per-visit toll.</p>
     */
    private TriAssessment assess(
            Map<Position, Integer> currentStock,
            Position spot,
            int step,
            OpponentCommitmentForecast forecast,
            SemiCommitmentAdjustmentWeights weights,
            int ownCollectionsBefore) {
        SpotCommitmentPressure pressure = forecast.pressureAt(spot);
        int stock = currentStock.getOrDefault(spot, 0);
        int hardBefore = pressure == null ? 0 : pressure.hardConsumedStrictlyBefore(step);
        int semiBefore =
                pressure == null ? 0 : pressure.semiReservedDirectPortionsStrictlyBefore(step);
        int commitmentRemaining = Math.max(0, stock - ownCollectionsBefore - hardBefore);
        int semiRemaining = Math.max(0, stock - ownCollectionsBefore - hardBefore - semiBefore);
        int allClaimsBefore = pressure == null ? 0 : (int) pressure.claims().stream()
                .filter(claim -> claim.forecastArrivalStep() < step)
                .count();
        int oldRemaining = Math.max(0, stock - ownCollectionsBefore - allClaimsBefore);

        SemiCommitmentCollectionClassification classification;
        if (pressure == null || pressure.claims().isEmpty()) {
            classification = SemiCommitmentCollectionClassification.UNFORECASTED;
        } else if (commitmentRemaining == 0) {
            classification = SemiCommitmentCollectionClassification.HARD_CLAIMED_FIRST;
        } else if (semiRemaining == 0) {
            classification = SemiCommitmentCollectionClassification.SEMI_CLAIMED_FIRST;
        } else if (pressure.anyBefore(OpponentClaimCommitment.DIRECT_INTENT, step)) {
            classification = SemiCommitmentCollectionClassification.DIRECT_INTENT_BEFORE;
        } else if (pressure.anyBefore(OpponentClaimCommitment.FOLLOW_ON_INTENT, step)) {
            classification = SemiCommitmentCollectionClassification.FOLLOW_ON_INTENT_BEFORE;
        } else if (pressure.anyAt(step)) {
            classification = SemiCommitmentCollectionClassification.CONTESTED_TIE;
        } else {
            classification = SemiCommitmentCollectionClassification.LIKELY_AVAILABLE;
        }
        return new TriAssessment(
                new SemiCommitmentCollectionAssessment(
                        spot, step, classification, semiRemaining, semiRemaining > 0,
                        weights.weightFor(classification)),
                commitmentRemaining,
                oldRemaining);
    }

    private SemiCommitmentCollectionAttribution empty() {
        return new SemiCommitmentCollectionAttribution(
                new SemiCommitmentAdjustedCollectionScore(0), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Set.of(), Set.of(), List.of());
    }

    private record ProjectedOwnCollection(Position spot, int step) { }

    /**
     * M12.1 classification of one collection plus the M12 and M10 remaining stock for the same event.
     *
     * <p>Carrying all three out of a single pass is what makes the three-way calibration exact rather
     * than approximate: the same claim list, the same arrival ordering, the same own-collection
     * history.</p>
     */
    private record TriAssessment(
            SemiCommitmentCollectionAssessment semi,
            int commitmentRemainingStock,
            int oldForecastRemainingStock) { }
}

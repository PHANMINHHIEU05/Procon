package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Direction;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;
import vn.ptit.procon.rules.MovementRules;

/** Deterministic M14 stock counterfactual. It performs no routing and never recomputes the forecast. */
public final class OpponentDenialEvaluator {

    private static final Comparator<CommittedOpponentClaim> CLAIM_ORDER = Comparator
            .comparingInt(CommittedOpponentClaim::forecastArrivalStep)
            .thenComparingInt(value -> commitmentOrder(value.commitment()))
            .thenComparingInt(value -> value.claim().groupRawId())
            .thenComparingInt(value -> value.claim().agentIndex())
            .thenComparingInt(value -> value.claim().rank().value());

    public OpponentClaimBaseline baseline(
            DayState state, OpponentCommitmentForecast forecast) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(forecast, "Opponent commitment forecast must not be null");
        Map<Position, List<BaselineOpponentClaim>> bySpot = new LinkedHashMap<>();
        int observed = 0;
        int direct = 0;
        int followOn = 0;
        for (Map.Entry<Position, Integer> stockEntry : state.spotStock().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Position::value))).toList()) {
            int stock = stockEntry.getValue();
            SpotCommitmentPressure pressure = forecast.pressureAt(stockEntry.getKey());
            if (stock <= 0 || pressure == null) {
                continue;
            }
            List<CommittedOpponentClaim> ordered = pressure.claims().stream().sorted(CLAIM_ORDER).toList();
            List<BaselineOpponentClaim> realizable = new ArrayList<>();
            for (int index = 0; index < ordered.size() && realizable.size() < stock; index++) {
                BaselineOpponentClaim claim = new BaselineOpponentClaim(ordered.get(index), index);
                realizable.add(claim);
                switch (claim.commitment()) {
                    case OBSERVED_NOW -> observed++;
                    case DIRECT_INTENT -> direct++;
                    case FOLLOW_ON_INTENT -> followOn++;
                }
            }
            if (!realizable.isEmpty()) {
                bySpot.put(stockEntry.getKey(), realizable);
            }
        }
        return new OpponentClaimBaseline(bySpot, forecast.forecastClaims(), observed, direct, followOn,
                state.spotStock().size());
    }

    public OpponentResidualClaimEvaluation evaluate(
            DayState state,
            OpponentClaimBaseline baseline,
            ValidDaySimulationResult simulation,
            SemiCommitmentCollectionAttribution attribution) {
        Objects.requireNonNull(simulation, "Simulation must not be null");
        Objects.requireNonNull(attribution, "M12.1 attribution must not be null");
        List<UdonCollectedEvent> events = simulation.events().stream()
                .filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .sorted(Comparator.comparingInt(UdonCollectedEvent::step)
                        .thenComparingInt(event -> event.agentId().value()))
                .toList();
        if (events.size() != attribution.assessments().size()) {
            throw new IllegalArgumentException("M12.1 assessments must align with simulator collections");
        }
        List<OwnCollection> own = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            if (attribution.assessments().get(index).semiCommitmentRealizable()) {
                UdonCollectedEvent event = events.get(index);
                own.add(new OwnCollection(event.position(), event.step(), event.agentId().value(), index));
            }
        }
        return evaluate(state, baseline, own);
    }

    OpponentResidualClaimEvaluation evaluate(
            DayState state, OpponentClaimBaseline baseline, List<OwnCollection> ownCollections) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(baseline, "Opponent baseline must not be null");
        Objects.requireNonNull(ownCollections, "Own collections must not be null");
        int residualObserved = 0;
        int residualDirect = 0;
        int residualFollowOn = 0;
        for (Map.Entry<Position, List<BaselineOpponentClaim>> entry
                : baseline.realizableClaimsBySpot().entrySet()) {
            int stock = state.spotStock().getOrDefault(entry.getKey(), 0);
            int consumed = 0;
            List<OwnCollection> ownAtSpot = ownCollections.stream()
                    .filter(event -> event.spot().equals(entry.getKey()))
                    .sorted(Comparator.comparingInt(OwnCollection::step)
                            .thenComparingInt(OwnCollection::agentId)
                            .thenComparingInt(OwnCollection::ordinal))
                    .toList();
            int ownIndex = 0;
            for (BaselineOpponentClaim claim : entry.getValue()) {
                while (ownIndex < ownAtSpot.size() && ownAtSpot.get(ownIndex).step() < claim.arrivalStep()) {
                    if (consumed < stock) {
                        consumed++;
                    }
                    ownIndex++;
                }
                boolean realizes = consumed < stock;
                if (realizes) {
                    consumed++;
                    switch (claim.commitment()) {
                        case OBSERVED_NOW -> residualObserved++;
                        case DIRECT_INTENT -> residualDirect++;
                        case FOLLOW_ON_INTENT -> residualFollowOn++;
                    }
                }
                // Equal-step opponent claims consume first. Equal-step own events are applied only
                // before a later claim, never before this claim, so a tie cannot become denial.
            }
        }
        return new OpponentResidualClaimEvaluation(
                baseline,
                residualObserved,
                residualDirect,
                residualFollowOn,
                baseline.observedNowRealizable() - residualObserved,
                baseline.directIntentRealizable() - residualDirect,
                baseline.followOnIntentRealizable() - residualFollowOn);
    }

    OpponentResidualClaimEvaluation evaluateRoute(
            DayState state,
            OpponentClaimBaseline baseline,
            OpponentCommitmentForecast forecast,
            SemiCommitmentForecastEvaluator semiEvaluator,
            SemiCommitmentAdjustmentWeights weights,
            Map<Position, UdonSpot> spotsByPosition,
            Route route,
            int initialArrivalStep,
            Map<Position, Integer> branchStock,
            java.util.Set<Position> alreadyVisited,
            int agentId) {
        Map<Position, Integer> available = new LinkedHashMap<>(branchStock);
        java.util.Set<Position> visited = new java.util.LinkedHashSet<>(alreadyVisited);
        List<OwnCollection> own = new ArrayList<>();
        Position cursor = route.start();
        int step = initialArrivalStep;
        int ordinal = 0;
        for (Direction direction : route.directions()) {
            TrafficStatus traffic = state.matchData().map().terrainAt(cursor) == Terrain.ROAD
                    ? state.roadTraffic().get(cursor) : null;
            MoveCost cost = MovementRules.costFromSource(state.matchData().map(), cursor, traffic)
                    .orElseThrow();
            step += cost.stepCost();
            cursor = state.matchData().map().neighbor(cursor, direction).orElseThrow();
            if (!visited.add(cursor) || available.getOrDefault(cursor, 0) <= 0
                    || !spotsByPosition.containsKey(cursor)) {
                continue;
            }
            available.put(cursor, available.get(cursor) - 1);
            SemiCommitmentCollectionAssessment assessment = semiEvaluator.assessCollection(
                    branchStock, cursor, step, forecast, weights);
            if (assessment.semiCommitmentRealizable()) {
                own.add(new OwnCollection(cursor, step, agentId, ordinal));
            }
            ordinal++;
        }
        return evaluate(state, baseline, own);
    }

    record OwnCollection(Position spot, int step, int agentId, int ordinal) {
        OwnCollection {
            Objects.requireNonNull(spot, "Own collection spot must not be null");
            if (step < 0 || agentId < 0 || ordinal < 0) {
                throw new IllegalArgumentException("Own collection ordering values must be non-negative");
            }
        }
    }

    private static int commitmentOrder(OpponentClaimCommitment commitment) {
        return switch (commitment) {
            case OBSERVED_NOW -> 0;
            case DIRECT_INTENT -> 1;
            case FOLLOW_ON_INTENT -> 2;
        };
    }
}
package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;

/**
 * Deterministic bounded opponent intent and stock forecast.
 *
 * <p>This is planner inference only. It does not reconstruct opponent routes or
 * alter simulator semantics.</p>
 *
 * <p>Every observed agent is measured for physical reachability. Only agents the
 * active {@link OpponentCollectionEligibility} accepts as Udon collectors retain
 * intent targets, add spot pressure or produce forecast claims.</p>
 */
public final class OpponentIntentForecaster {

    public OpponentIntentForecast forecast(DayState state) {
        return forecast(state, OpponentIntentConfig.defaults());
    }

    public OpponentIntentForecast forecast(DayState state, OpponentIntentConfig config) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(config, "Opponent intent configuration must not be null");

        HexMap map = state.matchData().map();
        OpponentWeightedArrivalLowerBound distances = new OpponentWeightedArrivalLowerBound();
        Map<Position, Map<Position, Integer>> distanceCache = new HashMap<>();
        Map<Position, UdonSpot> spots = new TreeMap<>(Comparator.comparingInt(Position::value));
        state.matchData().udonSpots().forEach(spot -> spots.put(spot.position(), spot));
        Map<Position, Integer> initialStock = new TreeMap<>(Comparator.comparingInt(Position::value));
        initialStock.putAll(state.spotStock());

        List<OpponentGroupIntentForecast> groups = new ArrayList<>();
        Map<Position, MutablePressure> pressure = new TreeMap<>(Comparator.comparingInt(Position::value));
        List<ForecastOpponentClaim> proposedClaims = new ArrayList<>();
        int observedAgents = 0;
        int collectionEligibleAgents = 0;
        int stockedSpots = (int) initialStock.entrySet().stream().filter(entry -> entry.getValue() > 0).count();
        int physicalPairsAllObserved = 0;
        int physicalPairsCollectionEligible = 0;
        int retainedTargets = 0;
        int forecastClaims = 0;

        for (ObservedOtherGroup group : state.observedOthers()) {
            List<OpponentAgentIntentForecast> agents = new ArrayList<>();
            for (int agentIndex = 0; agentIndex < group.agents().size(); agentIndex++) {
                ObservedOtherAgent agent = group.agents().get(agentIndex);
                observedAgents++;
                Map<Position, Integer> sourceDistances = distancesFrom(
                        map, agent.position(), distances, distanceCache);
                List<TargetChoice> reachable = spots.values().stream()
                        .filter(spot -> initialStock.getOrDefault(spot.position(), 0) > 0)
                        .map(spot -> new TargetChoice(
                                spot,
                                sourceDistances.get(spot.position()),
                                initialStock.getOrDefault(spot.position(), 0)))
                        .filter(choice -> choice.distance != null && choice.distance <= state.stepBudget())
                        .sorted(targetPreference(Set.of()))
                        .toList();
                physicalPairsAllObserved += reachable.size();
                if (!config.collectionEligibility().collectsUdon(agent)) {
                    agents.add(new OpponentAgentIntentForecast(
                            agentIndex,
                            agent.position(),
                            agent.rawKind(),
                            agent.fuel(),
                            reachable.size(),
                            false,
                            List.of()));
                    continue;
                }
                collectionEligibleAgents++;
                physicalPairsCollectionEligible += reachable.size();

                List<TargetChoice> retained = retainTargets(
                        reachable, config.maxIntentTargetsPerAgent());
                retainedTargets += retained.size();
                Set<Position> retainedPositions = new LinkedHashSet<>();
                for (int index = 0; index < retained.size(); index++) {
                    TargetChoice choice = retained.get(index);
                    IntentRank rank = IntentRank.fromOneBasedIndex(index + 1);
                    retainedPositions.add(choice.spot.position());
                    pressure.computeIfAbsent(choice.spot.position(), ignored -> new MutablePressure())
                            .addIntent(rank, group.rawId(), agentIndex);
                }

                List<OpponentTargetIntent> targetIntents = new ArrayList<>();
                for (int index = 0; index < retained.size(); index++) {
                    TargetChoice choice = retained.get(index);
                    IntentRank rank = IntentRank.fromOneBasedIndex(index + 1);
                    targetIntents.add(new OpponentTargetIntent(
                            choice.spot.position(),
                            choice.spot.brand(),
                            rank,
                            choice.distance,
                            OptionalInt.empty(),
                            false));
                }

                List<ForecastOpponentClaim> claims = greedyClaims(
                        state, group.rawId(), agentIndex, agent, retained, initialStock,
                        distances, distanceCache);
                proposedClaims.addAll(claims);
                for (ForecastOpponentClaim claim : claims) {
                    targetIntents = targetIntents.stream()
                            .map(intent -> intent.spot().equals(claim.spot())
                                    ? new OpponentTargetIntent(
                                            intent.spot(), intent.brand(), intent.rank(),
                                            intent.optimisticTravelSteps(),
                                            OptionalInt.of(claim.forecastArrivalStep()), true)
                                    : intent)
                            .toList();
                }
                agents.add(new OpponentAgentIntentForecast(
                        agentIndex,
                        agent.position(),
                        agent.rawKind(),
                        agent.fuel(),
                        reachable.size(),
                        true,
                        targetIntents));
            }
            groups.add(new OpponentGroupIntentForecast(group.rawId(), agents));
        }

        Map<Position, Integer> remainingForecastStock = new TreeMap<>(Comparator.comparingInt(Position::value));
        remainingForecastStock.putAll(initialStock);
        for (ForecastOpponentClaim claim : proposedClaims.stream()
                .sorted(Comparator.comparingInt(ForecastOpponentClaim::forecastArrivalStep)
                        .thenComparingInt(ForecastOpponentClaim::groupRawId)
                        .thenComparingInt(ForecastOpponentClaim::agentIndex)
                        .thenComparingInt(value -> value.spot().value()))
                .toList()) {
            if (remainingForecastStock.getOrDefault(claim.spot(), 0) <= 0) {
                continue;
            }
            remainingForecastStock.computeIfPresent(claim.spot(), (ignored, stock) -> stock - 1);
            pressure.computeIfAbsent(claim.spot(), ignored -> new MutablePressure()).addClaim(claim);
            forecastClaims++;
        }
        Set<ClaimSource> acceptedSources = pressure.values().stream()
                .flatMap(value -> value.claims.stream())
                .map(claim -> new ClaimSource(claim.groupRawId(), claim.agentIndex(), claim.spot()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        groups = groups.stream()
                .map(group -> new OpponentGroupIntentForecast(
                        group.groupRawId(),
                        group.agents().stream()
                                .map(agentForecast -> new OpponentAgentIntentForecast(
                                        agentForecast.agentIndex(),
                                        agentForecast.observedPosition(),
                                        agentForecast.rawKind(),
                                        agentForecast.observedFuel(),
                                        agentForecast.physicallyReachableSpots(),
                                        agentForecast.collectionEligible(),
                                        agentForecast.targets().stream()
                                                .map(target -> acceptedTarget(
                                                        group.groupRawId(), agentForecast.agentIndex(),
                                                        target, acceptedSources))
                                                .toList()))
                                .toList()))
                .toList();

        Map<Position, SpotIntentPressure> immutablePressure = new LinkedHashMap<>();
        for (Map.Entry<Position, MutablePressure> entry : pressure.entrySet()) {
            MutablePressure value = entry.getValue();
            List<ForecastOpponentClaim> claims = value.claims.stream()
                    .sorted(Comparator.comparingInt(ForecastOpponentClaim::forecastArrivalStep)
                            .thenComparingInt(ForecastOpponentClaim::groupRawId)
                            .thenComparingInt(ForecastOpponentClaim::agentIndex)
                            .thenComparingInt(claim -> claim.rank().value()))
                    .toList();
            int currentStock = initialStock.getOrDefault(entry.getKey(), 0);
            int cappedClaims = Math.min(currentStock, claims.size());
            List<ForecastOpponentClaim> capped = claims.subList(0, cappedClaims);
            OptionalInt earliest = capped.stream().mapToInt(ForecastOpponentClaim::forecastArrivalStep).min();
            immutablePressure.put(entry.getKey(), new SpotIntentPressure(
                    entry.getKey(), currentStock, value.pressureUnits, value.sourceCount,
                    cappedClaims, earliest, capped));
        }

        return new OpponentIntentForecast(
                groups,
                immutablePressure,
                observedAgents,
                collectionEligibleAgents,
                stockedSpots,
                physicalPairsAllObserved,
                physicalPairsCollectionEligible,
                retainedTargets,
                forecastClaims);
    }

    private List<ForecastOpponentClaim> greedyClaims(
            DayState state,
            int groupRawId,
            int agentIndex,
            ObservedOtherAgent agent,
            List<TargetChoice> retained,
            Map<Position, Integer> initialStock,
            OpponentWeightedArrivalLowerBound distances,
            Map<Position, Map<Position, Integer>> distanceCache) {
        List<ForecastOpponentClaim> claims = new ArrayList<>();
        Set<Position> remaining = new LinkedHashSet<>();
        retained.forEach(choice -> remaining.add(choice.spot.position()));
        Position cursor = agent.position();
        Set<BrandId> routeBrands = new LinkedHashSet<>();
        int usedSteps = 0;
        Map<Position, Integer> routeStock = new TreeMap<>(Comparator.comparingInt(Position::value));
        routeStock.putAll(initialStock);
        while (!remaining.isEmpty()) {
            Map<Position, Integer> continuationDistances = distancesFrom(
                    state.matchData().map(), cursor, distances, distanceCache);
            int currentUsedSteps = usedSteps;
            List<TargetChoice> feasible = retained.stream()
                    .filter(choice -> remaining.contains(choice.spot.position()))
                    .map(choice -> new TargetChoice(
                            choice.spot,
                            continuationDistances.get(choice.spot.position()),
                            routeStock.getOrDefault(choice.spot.position(), 0)))
                    .filter(choice -> choice.distance != null
                            && currentUsedSteps + choice.distance <= state.stepBudget()
                            && choice.stock > 0)
                    .sorted(targetPreference(routeBrands))
                    .toList();
            if (feasible.isEmpty()) {
                break;
            }
            TargetChoice selected = feasible.get(0);
            IntentRank rank = retained.stream()
                    .filter(choice -> choice.spot.position().equals(selected.spot.position()))
                    .map(choice -> IntentRank.fromOneBasedIndex(retained.indexOf(choice) + 1))
                    .findFirst()
                    .orElseThrow();
            usedSteps += selected.distance;
            routeStock.computeIfPresent(selected.spot.position(), (ignored, stock) -> stock - 1);
            routeBrands.add(selected.spot.brand());
            remaining.remove(selected.spot.position());
            claims.add(new ForecastOpponentClaim(
                    groupRawId, agentIndex, agent.rawKind(), selected.spot.position(),
                    usedSteps, rank, 1));
        }
        return List.copyOf(claims);
    }

    private Map<Position, Integer> distancesFrom(
            HexMap map,
            Position source,
            OpponentWeightedArrivalLowerBound distances,
            Map<Position, Map<Position, Integer>> cache) {
        return cache.computeIfAbsent(
                source, ignored -> distances.shortestTravelStepsFrom(map, source));
    }

    private OpponentTargetIntent acceptedTarget(
            int groupRawId,
            int agentIndex,
            OpponentTargetIntent target,
            Set<ClaimSource> acceptedSources) {
        if (!target.forecastClaimed()
                || acceptedSources.contains(new ClaimSource(groupRawId, agentIndex, target.spot()))) {
            return target;
        }
        return new OpponentTargetIntent(
                target.spot(), target.brand(), target.rank(), target.optimisticTravelSteps(),
                OptionalInt.empty(), false);
    }

    private Comparator<TargetChoice> targetPreference(Set<BrandId> routeBrands) {
        return Comparator.comparingInt((TargetChoice choice) -> choice.distance)
                .thenComparing(Comparator.comparingInt(
                        (TargetChoice choice) -> choice.stock).reversed())
                .thenComparing(choice -> routeBrands.contains(choice.spot.brand()))
                .thenComparingInt(choice -> choice.spot.position().value());
    }

    private List<TargetChoice> retainTargets(List<TargetChoice> reachable, int limit) {
        List<TargetChoice> remaining = new ArrayList<>(reachable);
        List<TargetChoice> retained = new ArrayList<>();
        Set<BrandId> selectedBrands = new LinkedHashSet<>();
        while (!remaining.isEmpty() && retained.size() < limit) {
            TargetChoice selected = remaining.stream()
                    .min(targetPreference(selectedBrands))
                    .orElseThrow();
            retained.add(selected);
            selectedBrands.add(selected.spot.brand());
            remaining.remove(selected);
        }
        return List.copyOf(retained);
    }

    private static final class MutablePressure {
        private int pressureUnits;
        private int sourceCount;
        private final Set<String> sources = new LinkedHashSet<>();
        private final List<ForecastOpponentClaim> claims = new ArrayList<>();

        private void addIntent(IntentRank rank, int groupRawId, int agentIndex) {
            pressureUnits += rank.pressureUnits();
            if (sources.add(groupRawId + ":" + agentIndex)) {
                sourceCount++;
            }
        }

        private void addClaim(ForecastOpponentClaim claim) {
            claims.add(claim);
        }
    }

    private record TargetChoice(UdonSpot spot, Integer distance, int stock) {
    }

    private record ClaimSource(int groupRawId, int agentIndex, Position spot) {
    }
}
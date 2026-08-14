package vn.ptit.procon.domain.match;

import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.agent.InitialAgent;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.udon.UdonSpot;

/** Immutable semantic setup data, independent from server serialization. */
public record StaticMatchData(
        HexMap map,
        DayStepBudgets dayStepBudgets,
        List<InitialAgent> initialAgents,
        FuelCapacity patrolFuelCapacity,
        List<UdonSpot> udonSpots) {

    public StaticMatchData {
        Objects.requireNonNull(map, "Map must not be null");
        Objects.requireNonNull(dayStepBudgets, "Day step budgets must not be null");
        initialAgents = List.copyOf(
                Objects.requireNonNull(initialAgents, "Initial agents must not be null"));
        Objects.requireNonNull(patrolFuelCapacity, "PATROL fuel capacity must not be null");
        udonSpots = List.copyOf(Objects.requireNonNull(udonSpots, "Udon spots must not be null"));

        for (InitialAgent initialAgent : initialAgents) {
            if (!map.contains(initialAgent.position())) {
                throw new IllegalArgumentException(
                        "Initial agent position is outside the map: " + initialAgent.position());
            }
        }
        for (UdonSpot spot : udonSpots) {
            if (!map.contains(spot.position())) {
                throw new IllegalArgumentException(
                        "Udon spot position is outside the map: " + spot.position());
            }
            if (map.terrainAt(spot.position()) != Terrain.PLAIN) {
                throw new IllegalArgumentException(
                        "Udon spot must be located on PLAIN terrain: " + spot.position());
            }
        }
    }
}
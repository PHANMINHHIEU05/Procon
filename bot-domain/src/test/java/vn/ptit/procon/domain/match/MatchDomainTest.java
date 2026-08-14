package vn.ptit.procon.domain.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.agent.InitialAgent;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;

class MatchDomainTest {

    @Test
    void dayIndexIsZeroBasedAndRejectsNegativeValues() {
        assertEquals(0, new DayIndex(0).value());
        assertThrows(IllegalArgumentException.class, () -> new DayIndex(-1));
    }

    @Test
    void dayStepBudgetsValidateAndUseZeroBasedLookup() {
        DayStepBudgets budgets = new DayStepBudgets(new int[] {30, 20, 25});

        assertEquals(3, budgets.dayCount());
        assertEquals(30, budgets.stepsFor(new DayIndex(0)));
        assertEquals(25, budgets.stepsFor(new DayIndex(2)));
        assertThrows(IllegalArgumentException.class, () -> budgets.stepsFor(new DayIndex(3)));
    }

    @Test
    void dayStepBudgetsRejectEmptyOrNonPositiveEntries() {
        assertThrows(IllegalArgumentException.class, () -> new DayStepBudgets(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new DayStepBudgets(new int[] {30, 0}));
        assertThrows(IllegalArgumentException.class, () -> new DayStepBudgets(new int[] {-1}));
        assertThrows(NullPointerException.class, () -> new DayStepBudgets(null));
    }

    @Test
    void dayStepBudgetsDefensivelyCopyInput() {
        int[] input = {30, 20};
        DayStepBudgets budgets = new DayStepBudgets(input);

        input[0] = 99;

        assertEquals(30, budgets.stepsFor(new DayIndex(0)));
    }

    @Test
    void brandAndUdonSpotValidateStaticConcepts() {
        BrandId brand = new BrandId("brand-a");
        UdonSpot spot = new UdonSpot(brand, new Position(4), 7);

        assertEquals(brand, spot.brand());
        assertEquals(new Position(4), spot.position());
        assertEquals(7, spot.stockCapacity());
        assertEquals(0, new UdonSpot(brand, new Position(4), 0).stockCapacity());
        assertThrows(IllegalArgumentException.class, () -> new BrandId(" "));
        assertThrows(IllegalArgumentException.class, () -> new UdonSpot(brand, new Position(4), -1));
    }

    @Test
    void staticMatchDataDefensivelyCopiesCollections() {
        List<InitialAgent> agents = new ArrayList<>(List.of(new InitialAgent(new AgentId(0), new Position(0))));
        List<UdonSpot> spots = new ArrayList<>(List.of(
                new UdonSpot(new BrandId("brand-a"), new Position(1), 3)));
        StaticMatchData data = new StaticMatchData(
                new HexMap(2, 1, new Terrain[] {Terrain.PLAIN, Terrain.PLAIN}),
                new DayStepBudgets(new int[] {30}),
                agents,
                new FuelCapacity(10),
                spots);

        agents.clear();
        spots.clear();

        assertEquals(1, data.initialAgents().size());
        assertEquals(1, data.udonSpots().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> data.initialAgents().add(new InitialAgent(new AgentId(1), new Position(0))));
        assertThrows(
                UnsupportedOperationException.class,
                () -> data.udonSpots().clear());
    }

    @Test
    void staticMatchDataRejectsInvalidStaticPositionsAndNonPlainUdonSpots() {
        HexMap map = new HexMap(2, 1, new Terrain[] {Terrain.PLAIN, Terrain.ROAD});
        DayStepBudgets budgets = new DayStepBudgets(new int[] {30});
        FuelCapacity capacity = new FuelCapacity(10);

        assertThrows(
                IllegalArgumentException.class,
                () -> new StaticMatchData(
                        map,
                        budgets,
                        List.of(new InitialAgent(new AgentId(0), new Position(2))),
                        capacity,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StaticMatchData(
                        map,
                        budgets,
                        List.of(),
                        capacity,
                        List.of(new UdonSpot(new BrandId("brand-a"), new Position(1), 3))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StaticMatchData(
                        map,
                        budgets,
                        List.of(),
                        capacity,
                        List.of(new UdonSpot(new BrandId("brand-a"), new Position(2), 3))));
    }
}
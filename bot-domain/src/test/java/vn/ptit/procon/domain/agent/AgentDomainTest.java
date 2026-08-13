package vn.ptit.procon.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ptit.procon.domain.map.Position;

class AgentDomainTest {

    @ParameterizedTest
    @MethodSource("agentKindMappings")
    void mapsOfficialAgentKindCodes(int code, AgentKind expected) {
        assertEquals(expected, AgentKind.fromCode(code));
        assertEquals(code, expected.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2, 999})
    void rejectsUnknownAgentKindCodes(int code) {
        assertThrows(IllegalArgumentException.class, () -> AgentKind.fromCode(code));
    }

    @Test
    void agentIdAcceptsNonNegativeValues() {
        assertEquals(0, new AgentId(0).value());
        assertEquals(new AgentId(7), new AgentId(7));
    }

    @Test
    void agentIdRejectsNegativeValue() {
        assertThrows(IllegalArgumentException.class, () -> new AgentId(-1));
    }

    @Test
    void finiteFuelAcceptsZeroAndPositiveAmounts() {
        assertEquals(0, new FiniteFuel(0).amount());
        assertEquals(12, new FiniteFuel(12).amount());
    }

    @Test
    void finiteFuelRejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> new FiniteFuel(-1));
    }

    @Test
    void fuelCapacityMustBePositive() {
        assertEquals(25, new FuelCapacity(25).value());
        assertThrows(IllegalArgumentException.class, () -> new FuelCapacity(0));
        assertThrows(IllegalArgumentException.class, () -> new FuelCapacity(-1));
    }

    @Test
    void patrolRequiresFiniteFuelAndAllowsZero() {
        AgentState patrol = AgentState.patrol(new AgentId(0), new Position(3), 0);

        assertEquals(AgentKind.PATROL, patrol.kind());
        assertEquals(new FiniteFuel(0), patrol.fuel());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentState(
                        new AgentId(0), AgentKind.PATROL, new Position(3), UnlimitedFuel.INSTANCE));
    }

    @Test
    void refuelRequiresExplicitUnlimitedFuel() {
        AgentState refuel = AgentState.refuel(new AgentId(1), new Position(4));

        assertEquals(AgentKind.REFUEL, refuel.kind());
        assertSame(UnlimitedFuel.INSTANCE, refuel.fuel());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentState(
                        new AgentId(1), AgentKind.REFUEL, new Position(4), new FiniteFuel(10)));
    }

    @Test
    void agentStateRejectsNullComponents() {
        AgentId id = new AgentId(0);
        Position position = new Position(0);

        assertThrows(NullPointerException.class, () -> new AgentState(null, AgentKind.PATROL, position, new FiniteFuel(0)));
        assertThrows(NullPointerException.class, () -> new AgentState(id, null, position, new FiniteFuel(0)));
        assertThrows(NullPointerException.class, () -> new AgentState(id, AgentKind.PATROL, null, new FiniteFuel(0)));
        assertThrows(NullPointerException.class, () -> new AgentState(id, AgentKind.PATROL, position, null));
    }

    @Test
    void initialAgentModelsIdentityAndPositionBeforeKindAssignment() {
        InitialAgent initial = new InitialAgent(new AgentId(2), new Position(8));

        assertEquals(new AgentId(2), initial.id());
        assertEquals(new Position(8), initial.position());
        assertInstanceOf(InitialAgent.class, initial);
    }

    private static Stream<Arguments> agentKindMappings() {
        return Stream.of(
                Arguments.of(0, AgentKind.PATROL),
                Arguments.of(1, AgentKind.REFUEL));
    }
}
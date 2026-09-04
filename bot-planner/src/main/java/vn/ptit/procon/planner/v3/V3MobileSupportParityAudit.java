package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/**
 * V3_MOBILE_SUPPORT_PARITY (PART 25): the V3 chronology versus the frozen {@code DaySimulator} on the SAME
 * combined plan.
 *
 * <p>Seven dimensions are compared: the PATROL position vector, the PATROL fuel vector, the mobile REFUEL
 * position, the collection count, the brand set, the remaining stock and the refill events. The chronology
 * is only allowed to be a fast path — if any dimension disagrees, the chronology is wrong and the support
 * semantics are incomplete, so {@link #match()} is the gate the whole phase hangs on.
 */
public record V3MobileSupportParityAudit(boolean match, boolean patrolPositionsMatch, boolean patrolFuelMatch,
        boolean refuelPositionMatch, boolean collectionsMatch, boolean brandsMatch, boolean remainingStockMatch,
        boolean refuelEventsMatch, String chronologyPatrolPositions, String simulatorPatrolPositions,
        String chronologyPatrolFuel, String simulatorPatrolFuel, String chronologyRefuelPosition,
        String simulatorRefuelPosition, int chronologyCollections, int simulatorCollections,
        String chronologyBrands, String simulatorBrands, String chronologyRemainingStock,
        String simulatorRemainingStock, String chronologyRefuelEvents, String simulatorRefuelEvents,
        String firstDifference) {

    public V3MobileSupportParityAudit {
        Objects.requireNonNull(firstDifference, "First difference must not be null");
    }

    /** No plan reached the simulator, so parity was never established. It is never reported as a match. */
    public static V3MobileSupportParityAudit unavailable(String reason) {
        return new V3MobileSupportParityAudit(false, false, false, false, false, false, false, false,
                "NONE", "NONE", "NONE", "NONE", "NONE", "NONE", 0, 0, "NONE", "NONE", "NONE", "NONE",
                "NONE", "NONE", Objects.requireNonNull(reason, "Reason must not be null"));
    }

    /** Compares one settled chronology against the authoritative simulation of the same plan. */
    public static V3MobileSupportParityAudit of(DayState state, CachedSupportTrajectory support,
            StrategicChronologyReplay.ChronologyResult replay, ValidDaySimulationResult simulated) {
        Objects.requireNonNull(state, "Day state must not be null");
        Objects.requireNonNull(replay, "Chronology must not be null");
        Objects.requireNonNull(simulated, "Simulation must not be null");
        List<AgentId> patrols = state.agents().stream().filter(agent -> agent.kind() == AgentKind.PATROL)
                .map(AgentState::id).sorted(Comparator.comparingInt(AgentId::value)).toList();
        String chronoPositions = positions(patrols, id -> replay.finalPositions().get(id));
        String simPositions = positions(patrols, id -> finalAgent(simulated, id).position());
        String chronoFuel = ints(patrols, id -> replay.finalFuel().get(id));
        String simFuel = ints(patrols, id -> fuelOf(finalAgent(simulated, id)));
        AgentId refuelId = support != null && support.present() ? support.refuelId() : null;
        String chronoRefuel = refuelId == null ? "NONE"
                : Integer.toString(replay.finalPositions().get(refuelId).value());
        String simRefuel = refuelId == null ? "NONE"
                : Integer.toString(finalAgent(simulated, refuelId).position().value());
        int chronoCollections = replay.collections();
        int simCollections = simulated.portionsCollectedByAgent().values().stream()
                .mapToInt(Integer::intValue).sum();
        String chronoBrands = brands(replay.brands().stream().map(brand -> brand.value()).toList());
        String simBrands = brands(simulated.brandsCollected().stream().map(brand -> brand.value()).toList());
        String chronoStock = stock(replay.remainingStock());
        String simStock = stock(simulated.remainingSpotStock());
        String chronoEvents = replay.refuelEvents().stream()
                .map(event -> "s" + event.step() + " p" + event.patrolId().value() + "@"
                        + event.position().value() + " " + event.before() + "->" + event.after())
                .collect(Collectors.joining(","));
        String simEvents = simulated.events().stream().filter(RefueledEvent.class::isInstance)
                .map(RefueledEvent.class::cast)
                .map(event -> "s" + event.step() + " p" + event.patrolId().value() + "@"
                        + event.position().value() + " " + event.before() + "->" + event.after())
                .collect(Collectors.joining(","));
        boolean positionsMatch = chronoPositions.equals(simPositions);
        boolean fuelMatch = chronoFuel.equals(simFuel);
        boolean refuelMatch = chronoRefuel.equals(simRefuel);
        boolean collectionsMatch = chronoCollections == simCollections;
        boolean brandsMatch = chronoBrands.equals(simBrands);
        boolean stockMatch = chronoStock.equals(simStock);
        boolean eventsMatch = chronoEvents.equals(simEvents);
        List<String> differences = new ArrayList<>();
        if (!positionsMatch) differences.add("PATROL_POSITIONS");
        if (!fuelMatch) differences.add("PATROL_FUEL");
        if (!refuelMatch) differences.add("REFUEL_POSITION");
        if (!collectionsMatch) differences.add("COLLECTIONS");
        if (!brandsMatch) differences.add("BRANDS");
        if (!stockMatch) differences.add("REMAINING_STOCK");
        if (!eventsMatch) differences.add("REFILL_EVENTS");
        return new V3MobileSupportParityAudit(differences.isEmpty(), positionsMatch, fuelMatch, refuelMatch,
                collectionsMatch, brandsMatch, stockMatch, eventsMatch, chronoPositions, simPositions,
                chronoFuel, simFuel, chronoRefuel, simRefuel, chronoCollections, simCollections, chronoBrands,
                simBrands, chronoStock, simStock, chronoEvents, simEvents,
                differences.isEmpty() ? "NONE" : differences.getFirst());
    }

    private static AgentState finalAgent(ValidDaySimulationResult simulated, AgentId id) {
        return simulated.finalAgents().stream().filter(agent -> agent.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Simulation lost agent " + id.value()));
    }

    private static int fuelOf(AgentState agent) {
        return agent.fuel() instanceof FiniteFuel finite ? finite.amount() : Integer.MAX_VALUE;
    }

    private static String positions(List<AgentId> ids, java.util.function.Function<AgentId, Position> lookup) {
        return ids.stream().map(id -> id.value() + "=" + lookup.apply(id).value())
                .collect(Collectors.joining(","));
    }

    private static String ints(List<AgentId> ids, java.util.function.Function<AgentId, Integer> lookup) {
        return ids.stream().map(id -> id.value() + "=" + lookup.apply(id)).collect(Collectors.joining(","));
    }

    private static String brands(List<String> values) {
        return values.stream().sorted().collect(Collectors.joining(","));
    }

    private static String stock(Map<Position, Integer> values) {
        return values.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().value()))
                .map(entry -> entry.getKey().value() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        return "match=" + match + " firstDifference=" + firstDifference + " positions=" + patrolPositionsMatch
                + " fuel=" + patrolFuelMatch + " refuel=" + refuelPositionMatch + " collections="
                + collectionsMatch + "(" + chronologyCollections + "/" + simulatorCollections + ") brands="
                + brandsMatch + " stock=" + remainingStockMatch + " refillEvents=" + refuelEventsMatch;
    }
}

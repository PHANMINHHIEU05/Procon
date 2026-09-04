package vn.ptit.procon.planner.v3;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
import vn.ptit.procon.engine.RefueledEvent;
import vn.ptit.procon.engine.UdonCollectedEvent;
import vn.ptit.procon.engine.ValidDaySimulationResult;

/** Compares the normalized search snapshot with the authoritative simulator. */
public final class V3TerminalParityAudit {
    private V3TerminalParityAudit() { }

    public static Result audit(DayState state, StrategicSearchResult result) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(result);
        int matches = 0;
        String firstMismatch = "NONE";
        for (StrategicTerminalSnapshot snapshot : result.terminalSnapshots()) {
            String mismatch = mismatch(state, snapshot);
            if (mismatch.equals("NONE")) matches++;
            else if (firstMismatch.equals("NONE")) firstMismatch = mismatch;
        }
        return new Result(result.terminalSnapshots().size(), matches,
                result.terminalSnapshots().size() - matches, firstMismatch);
    }

    private static String mismatch(DayState state, StrategicTerminalSnapshot snapshot) {
        var simulation = new DaySimulator().simulate(state, snapshot.plan());
        if (!(simulation instanceof ValidDaySimulationResult valid)) return "INVALID_SIMULATION";
        StrategicSearchState expected = snapshot.state();
        int collections = valid.portionsCollectedByAgent().values().stream().mapToInt(Integer::intValue).sum();
        if (collections != expected.collectionEstimate()) return "collections";
        if (!valid.brandsCollected().equals(expected.brands())) return "brands";
        if (!valid.remainingSpotStock().equals(expected.remainingStock())) return "remainingStock";
        String claims = valid.events().stream().filter(UdonCollectedEvent.class::isInstance)
                .map(UdonCollectedEvent.class::cast)
                .sorted(Comparator.comparingInt(UdonCollectedEvent::step)
                        .thenComparingInt(event -> event.agentId().value()))
                .map(event -> event.step() + ":" + event.agentId().value() + ":" + event.position().value())
                .collect(Collectors.joining(","));
        if (!claims.equals(expected.chronologyFingerprint())) return "chronology";
        for (var patrol : expected.patrols()) {
            var finalAgent = valid.finalAgents().stream().filter(a -> a.id().equals(patrol.patrolId())).findFirst();
            if (finalAgent.isEmpty()) return "position:" + patrol.patrolId().value();
            if (!finalAgent.get().position().equals(patrol.position())) return "position:" + patrol.patrolId().value();
            if (!(finalAgent.get().fuel() instanceof FiniteFuel fuel)) return "fuel:" + patrol.patrolId().value();
            if (fuelAtPrefixEnd(valid, patrol.patrolId(), patrol.elapsed(), fuel.amount()) != patrol.fuel()) {
                return "fuel:" + patrol.patrolId().value();
            }
        }
        return "NONE";
    }

    /**
     * The simulator's fuel at the instant the search state describes.
     *
     * <p>PART 21 fixes a search state's fuel at the END OF ITS COMMITTED PREFIX, deliberately excluding fuel
     * the trailing all-day WAIT would eventually collect. Under a mobile support root that exclusion is
     * load-bearing and observable: a tanker that keeps driving after a PATROL has finished its last leg
     * frequently rolls onto the cell the PATROL is parked on, and the simulator dutifully fills the tank. The
     * end-of-day figure and the end-of-prefix figure are then both correct and different, so comparing the
     * state against {@code finalAgents()} would report a mismatch where there is none.
     *
     * <p>Reconstructing the right instant is exact rather than approximate: a WAIT consumes no fuel, so after
     * the prefix ends the tank only ever changes by refuelling. The fuel recorded BEFORE the earliest refuel
     * that lands after the prefix therefore IS the fuel at the end of the prefix, and when no such refuel
     * exists the end-of-day figure already is that fuel.
     */
    private static int fuelAtPrefixEnd(ValidDaySimulationResult valid, AgentId patrolId, int elapsed,
            int finalFuel) {
        return valid.events().stream().filter(RefueledEvent.class::isInstance).map(RefueledEvent.class::cast)
                .filter(event -> event.patrolId().equals(patrolId) && event.step() > elapsed)
                .min(Comparator.comparingInt(RefueledEvent::step)).map(RefueledEvent::before).orElse(finalFuel);
    }

    public record Result(int terminalsChecked, int parityMatches, int parityMismatches,
            String firstMismatch) {
        public Result {
            if (terminalsChecked < 0 || parityMatches < 0 || parityMismatches < 0
                    || parityMatches + parityMismatches != terminalsChecked) {
                throw new IllegalArgumentException("Invalid parity counts");
            }
            Objects.requireNonNull(firstMismatch);
        }
        public boolean exact() { return parityMismatches == 0; }
    }
}

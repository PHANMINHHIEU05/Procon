package vn.ptit.procon.planner.v3;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;
import vn.ptit.procon.domain.agent.AgentKind;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.engine.DaySimulator;
import vn.ptit.procon.engine.DayState;
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
            if (!(finalAgent.get().fuel() instanceof FiniteFuel fuel) || fuel.amount() != patrol.fuel()) {
                return "fuel:" + patrol.patrolId().value();
            }
        }
        return "NONE";
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

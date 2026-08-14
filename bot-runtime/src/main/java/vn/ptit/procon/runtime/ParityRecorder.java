package vn.ptit.procon.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FiniteFuel;
import vn.ptit.procon.engine.DayState;

/** Compares predicted and authoritative next-day position and finite PATROL fuel. */
public final class ParityRecorder {

    private final Map<Integer, ParityObservation> pending = new LinkedHashMap<>();
    private final List<ParityComparison> comparisons = new ArrayList<>();

    public synchronized void record(ParityObservation observation) {
        pending.put(observation.beginningState().day().value(), observation);
    }

    public synchronized Optional<ParityComparison> observeNextState(DayState authoritative) {
        int precedingDay = authoritative.day().value() - 1;
        ParityObservation observation = pending.remove(precedingDay);
        if (observation == null) {
            return Optional.empty();
        }

        Map<AgentId, AgentState> actual = index(authoritative.agents());
        boolean positionMatch = true;
        boolean fuelMatch = true;
        boolean fuelObservable = false;
        List<AgentParityMismatch> agentMismatches = new ArrayList<>();
        for (AgentState predicted : observation.predictedResult().finalAgents()) {
            AgentState actualAgent = actual.get(predicted.id());
            boolean agentPositionMatch = actualAgent != null
                    && predicted.position().equals(actualAgent.position());
            if (!agentPositionMatch) {
                positionMatch = false;
            }
            Integer predictedFuelValue = null;
            Integer actualFuelValue = null;
            boolean agentFuelMatch = true;
            if (predicted.fuel() instanceof FiniteFuel predictedFuel) {
                fuelObservable = true;
                predictedFuelValue = predictedFuel.amount();
                if (actualAgent != null && actualAgent.fuel() instanceof FiniteFuel actualFuel) {
                    actualFuelValue = actualFuel.amount();
                }
                agentFuelMatch = actualFuelValue != null
                        && predictedFuelValue.intValue() == actualFuelValue.intValue();
                if (!agentFuelMatch) {
                    fuelMatch = false;
                }
            }
            if (!agentPositionMatch || !agentFuelMatch) {
                agentMismatches.add(new AgentParityMismatch(
                        predicted.id(),
                        predicted.position(),
                        actualAgent == null ? null : actualAgent.position(),
                        predictedFuelValue,
                        actualFuelValue));
            }
        }

        ParityComparison comparison = new ParityComparison(
                precedingDay,
                positionMatch ? ParityStatus.MATCH : ParityStatus.MISMATCH,
                !fuelObservable
                        ? ParityStatus.NOT_OBSERVABLE
                        : fuelMatch ? ParityStatus.MATCH : ParityStatus.MISMATCH,
                agentMismatches);
        comparisons.add(comparison);
        return Optional.of(comparison);
    }

    public synchronized List<ParityComparison> comparisons() {
        return List.copyOf(comparisons);
    }

    public Map<String, SemanticParityStatus> semanticChecklist() {
        return Map.ofEntries(
                Map.entry("MULTI_STEP_INTERMEDIATE_POSITION",
                        SemanticParityStatus.NOT_FULLY_VERIFIED),
                Map.entry("START_DAY_UDON_COLLECTION", SemanticParityStatus.NOT_TESTED),
                Map.entry("REFUEL_SELECTED_RENDEZVOUS",
                        SemanticParityStatus.LIVE_MATCHED_FOR_TESTED_SCENARIO),
                Map.entry("REFUEL_DURING_ACTIVE_MOVEMENT",
                        SemanticParityStatus.CORRECTED_AND_LIVE_SUPPORTED),
                Map.entry("REFUEL_ON_PATROL_ARRIVAL", SemanticParityStatus.LIVE_OBSERVED),
                Map.entry("REFUEL_ON_PATROL_ARRIVAL_LOCAL_MODEL",
                        SemanticParityStatus.CORRECTED_LOCALLY),
                Map.entry("INCIDENTAL_REFUEL", SemanticParityStatus.LIVE_OBSERVED),
                Map.entry("REFUEL_TIMING", SemanticParityStatus.PARTIALLY_LIVE_VERIFIED),
                Map.entry("ROAD_STOPPED_STEP_ACCOUNTING", SemanticParityStatus.NOT_TESTED),
                Map.entry("SAME_STEP_UDON_STOCK_TIE", SemanticParityStatus.NOT_TESTED));
    }

    private Map<AgentId, AgentState> index(List<AgentState> agents) {
        Map<AgentId, AgentState> result = new LinkedHashMap<>();
        agents.stream()
                .sorted(Comparator.comparingInt(agent -> agent.id().value()))
                .forEach(agent -> result.put(agent.id(), agent));
        return result;
    }
}

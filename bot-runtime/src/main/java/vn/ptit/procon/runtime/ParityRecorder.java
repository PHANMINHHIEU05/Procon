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

/** Compares only position and finite PATROL fuel observable after all-WAIT. */
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
        for (AgentState predicted : observation.predictedResult().finalAgents()) {
            AgentState actualAgent = actual.get(predicted.id());
            if (actualAgent == null || !predicted.position().equals(actualAgent.position())) {
                positionMatch = false;
            }
            if (predicted.fuel() instanceof FiniteFuel predictedFuel) {
                fuelObservable = true;
                if (actualAgent == null
                        || !(actualAgent.fuel() instanceof FiniteFuel actualFuel)
                        || predictedFuel.amount() != actualFuel.amount()) {
                    fuelMatch = false;
                }
            }
        }

        ParityComparison comparison = new ParityComparison(
                precedingDay,
                positionMatch ? ParityStatus.MATCH : ParityStatus.MISMATCH,
                !fuelObservable
                        ? ParityStatus.NOT_OBSERVABLE
                        : fuelMatch ? ParityStatus.MATCH : ParityStatus.MISMATCH);
        comparisons.add(comparison);
        return Optional.of(comparison);
    }

    public synchronized List<ParityComparison> comparisons() {
        return List.copyOf(comparisons);
    }

    public Map<String, SemanticParityStatus> semanticChecklist() {
        return Map.of(
                "MULTI_STEP_MOVEMENT_OCCUPANCY", SemanticParityStatus.NOT_TESTED,
                "START_DAY_UDON_COLLECTION", SemanticParityStatus.NOT_TESTED,
                "REFUEL_TIMING", SemanticParityStatus.NOT_TESTED,
                "ROAD_STOPPED_STEP_ACCOUNTING", SemanticParityStatus.NOT_TESTED,
                "SAME_STEP_UDON_STOCK_TIE", SemanticParityStatus.NOT_TESTED);
    }

    private Map<AgentId, AgentState> index(List<AgentState> agents) {
        Map<AgentId, AgentState> result = new LinkedHashMap<>();
        agents.stream()
                .sorted(Comparator.comparingInt(agent -> agent.id().value()))
                .forEach(agent -> result.put(agent.id(), agent));
        return result;
    }
}
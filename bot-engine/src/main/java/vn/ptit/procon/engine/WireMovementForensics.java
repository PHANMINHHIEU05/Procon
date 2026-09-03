package vn.ptit.procon.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.movement.MoveCost;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.rules.MovementRules;

/**
 * Diagnostic-only movement-duration provenance for an already decoded wire route.
 *
 * <p>The alternate analysis changes one ROAD traffic status at a time. It does not alter planning,
 * replay, submission, or production movement semantics, and it deliberately does not enumerate
 * combinations of traffic changes.</p>
 */
public final class WireMovementForensics {

    private static final int MAX_ALTERNATE_CANDIDATES = 96;

    public record AlternateCost(
            int commandIndex,
            int wireValue,
            int sourcePosition,
            int localStartStep,
            TrafficStatus localTraffic,
            TrafficStatus alternateTraffic,
            int localCost,
            int alternateCost,
            int alternateRouteDuration,
            int cumulativeDelta) {
    }

    public record AlternateCostAnalysis(
            int localDuration,
            List<AlternateCost> individualRoadAlternates,
            boolean bounded) {

        public AlternateCostAnalysis {
            individualRoadAlternates = List.copyOf(individualRoadAlternates);
        }
    }

    private WireMovementForensics() {
    }

    /**
     * Computes each bounded single-road-state alternative for the exact commands replayed locally.
     */
    public static AlternateCostAnalysis alternateRoadCosts(
            WireActionReplay.AgentReplayResult replay) {
        Objects.requireNonNull(replay, "Replay result must not be null");
        List<AlternateCost> candidates = new ArrayList<>();
        int localDuration = replay.totalDuration();
        boolean bounded = false;

        for (WireActionReplay.ReplayedCommand command : replay.commands()) {
            if (command.sourceTerrain() != Terrain.ROAD || command.sourceTraffic() == null) {
                continue;
            }
            MoveCost local = MovementRules.costFromSource(Terrain.ROAD, command.sourceTraffic())
                    .orElseThrow();
            for (TrafficStatus alternate : TrafficStatus.values()) {
                if (alternate == command.sourceTraffic()) {
                    continue;
                }
                if (candidates.size() == MAX_ALTERNATE_CANDIDATES) {
                    bounded = true;
                    return new AlternateCostAnalysis(localDuration, candidates, bounded);
                }
                MoveCost alternateCost = MovementRules.costFromSource(Terrain.ROAD, alternate)
                        .orElseThrow();
                int delta = alternateCost.stepCost() - local.stepCost();
                candidates.add(new AlternateCost(
                        command.commandIndex(),
                        command.wireValue(),
                        command.sourcePosition().value(),
                        command.startStep(),
                        command.sourceTraffic(),
                        alternate,
                        local.stepCost(),
                        alternateCost.stepCost(),
                        localDuration + delta,
                        delta));
            }
        }
        return new AlternateCostAnalysis(localDuration, candidates, bounded);
    }
}

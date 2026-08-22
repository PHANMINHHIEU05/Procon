package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.domain.agent.FuelCapacity;
import vn.ptit.procon.domain.map.HexMap;
import vn.ptit.procon.domain.map.Position;
import vn.ptit.procon.domain.map.Terrain;
import vn.ptit.procon.domain.match.DayIndex;
import vn.ptit.procon.domain.match.DayStepBudgets;
import vn.ptit.procon.domain.match.StaticMatchData;
import vn.ptit.procon.domain.opponent.ObservedOtherAgent;
import vn.ptit.procon.domain.opponent.ObservedOtherGroup;
import vn.ptit.procon.domain.udon.BrandId;
import vn.ptit.procon.domain.udon.UdonSpot;
import vn.ptit.procon.engine.DayState;

/** TEMPORARY exploration harness: prints calibration numbers for candidate M12.1 fixtures. */
class ScratchCalibrationProbeTest {

    private static final AnytimePlannerConfig PRODUCTION = AnytimePlannerConfig.defaults();

    @Test
    void probe() {
        report("8x8", eightByEight());
        report("12x12", twelveByTwelveCap());
        abc("8x8", eightByEight());
        abc("12x12", twelveByTwelveCap());
    }

    private static void report(String label, DayState state) {
        OpponentCommitmentForecast commitment = forecastFor(state);
        SemiCommitmentForecast semi = SemiCommitmentForecast.derive(commitment);
        AnytimePlanResult result = semiCommitmentAware(PRODUCTION).planWithStats(state);
        SemiCommitmentAwarePlanEvaluation eval =
                result.semiCommitmentAwareEvaluation().orElseThrow();
        StratifiedSearchStats depth = result.stratifiedSearchStats().orElseThrow();

        StringBuilder perSpot = new StringBuilder();
        int maxDirectOnOneSpot = 0;
        for (SpotCommitmentPressure pressure : commitment.pressureBySpot().values().stream()
                .sorted((a, b) -> Integer.compare(a.spot().value(), b.spot().value())).toList()) {
            maxDirectOnOneSpot = Math.max(maxDirectOnOneSpot, pressure.directIntentClaims());
            perSpot.append(' ').append(pressure.spot().value())
                    .append("[o").append(pressure.observedNowClaims())
                    .append("d").append(pressure.directIntentClaims())
                    .append("f").append(pressure.followOnIntentClaims()).append(']');
        }

        System.out.println("FORECAST " + label
                + " observedAgents=" + commitment.observedAgentCount()
                + " eligible=" + commitment.collectionEligibleAgentCount()
                + " stockedSpots=" + commitment.stockedSpotCount()
                + " forecastClaims=" + commitment.forecastClaims()
                + " oNow=" + commitment.observedNowClaims()
                + " dir=" + commitment.directIntentClaims()
                + " fol=" + commitment.followOnIntentClaims()
                + " hardCons=" + commitment.hardConsumedPortions()
                + " semiSpots=" + semi.semiReservedSpots()
                + " maxSemi=" + semi.maxSemiReservedPortions()
                + " maxDirSameSpot=" + maxDirectOnOneSpot
                + " |" + perSpot);
        System.out.println("EVAL " + label
                + " rawUdon=" + eval.base().udonTotal()
                + " localBrands=" + eval.base().teamBrandCount()
                + " old=" + eval.oldForecastRealizableCollections()
                + " semi=" + eval.semiCommitmentRealizableCollections()
                + " M12=" + eval.commitmentRealizableCollections()
                + " semiBrands=" + eval.semiCommitmentRealizableBrandCount()
                + " score=" + eval.adjustedCollectionScore().value()
                + " hF=" + eval.hardClaimedFirstCollections()
                + " sF=" + eval.semiClaimedFirstCollections()
                + " dB=" + eval.directIntentBeforeCollections()
                + " fB=" + eval.followOnIntentBeforeCollections()
                + " tie=" + eval.tieCollections()
                + " unf=" + eval.unforecastedCollections());
        System.out.println("SEARCH " + label
                + " expanded=" + result.stats().expandedStates()
                + " qualified=" + depth.strategiesQualified()
                + " discovered=" + depth.strategiesDiscovered()
                + " ge2=" + depth.strategiesWithAtLeast2Expansions()
                + " maxStrategy=" + depth.maxStrategyExpansionCount()
                + " disc=" + depth.discoveryExpansions()
                + " qual=" + depth.qualificationExpansions()
                + " expl=" + depth.exploitationExpansions()
                + " frontierPeak=" + depth.frontierPeak()
                + " improvements=" + result.stats().incumbentImprovements()
                + " completed=" + result.stats().completedPlans());
    }

    /** Section 40: three modes, same search budget, same state. */
    private static void abc(String label, DayState state) {
        AnytimePlanResult a = stratifiedIntentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult b = commitmentAware(PRODUCTION).planWithStats(state);
        AnytimePlanResult c = semiCommitmentAware(PRODUCTION).planWithStats(state);
        IntentAwarePlanEvaluation m10 = a.intentAwareEvaluation().orElseThrow();
        CommitmentAwarePlanEvaluation m12 = b.commitmentAwareEvaluation().orElseThrow();
        SemiCommitmentAwarePlanEvaluation m121 = c.semiCommitmentAwareEvaluation().orElseThrow();

        System.out.println("ABC " + label
                + " | A raw=" + m10.base().udonTotal()
                + " brands=" + m10.forecastRealizableBrandCount()
                + " realizable=" + m10.forecastRealizableCollections()
                + " score=" + m10.adjustedCollectionScore().value()
                + " | B raw=" + m12.base().udonTotal()
                + " brands=" + m12.commitmentRealizableBrandCount()
                + " realizable=" + m12.commitmentRealizableCollections()
                + " old=" + m12.oldForecastRealizableCollections()
                + " score=" + m12.adjustedCollectionScore().value()
                + " | C raw=" + m121.base().udonTotal()
                + " brands=" + m121.semiCommitmentRealizableBrandCount()
                + " realizable=" + m121.semiCommitmentRealizableCollections()
                + " M12=" + m121.commitmentRealizableCollections()
                + " old=" + m121.oldForecastRealizableCollections()
                + " score=" + m121.adjustedCollectionScore().value()
                + " | expanded " + a.stats().expandedStates() + "/"
                + b.stats().expandedStates() + "/" + c.stats().expandedStates()
                + " qualified " + a.stratifiedSearchStats().orElseThrow().strategiesQualified() + "/"
                + b.stratifiedSearchStats().orElseThrow().strategiesQualified() + "/"
                + c.stratifiedSearchStats().orElseThrow().strategiesQualified());
    }

    private static OpponentCommitmentForecast forecastFor(DayState state) {
        return OpponentCommitmentForecast.annotate(new OpponentIntentForecaster().forecast(state));
    }

    private static SemiCommitmentAwareStratifiedPlanner semiCommitmentAware(
            AnytimePlannerConfig config) {
        return new SemiCommitmentAwareStratifiedPlanner(
                config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                SemiCommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    private static CommitmentAwareStratifiedPlanner commitmentAware(AnytimePlannerConfig config) {
        return new CommitmentAwareStratifiedPlanner(
                config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                CommitmentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    private static StratifiedIntentAwareAnytimePlanner stratifiedIntentAware(
            AnytimePlannerConfig config) {
        return new StratifiedIntentAwareAnytimePlanner(
                config, OpponentIntentConfig.defaults(), IntentAdjustmentWeights.defaults(),
                StratifiedSearchConfig.forBudget(config.maxExpandedStates()), false);
    }

    private static DayState eightByEight() {
        Terrain[] terrain = new Terrain[64];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        int[] patrolStarts = {27, 28, 36};
        for (int index = 0; index < patrolStarts.length; index++) {
            agents.add(AgentState.patrol(new AgentId(index), new Position(patrolStarts[index]), 30));
        }
        agents.add(AgentState.refuel(new AgentId(3), new Position(35)));
        return state(new HexMap(8, 8, terrain), 19, agents,
                List.of(spot("A", 7), spot("B", 15), spot("C", 23), spot("D", 31),
                        spot("A", 39), spot("B", 47), spot("C", 55), spot("D", 63)),
                List.of(other(23, 0), other(14, 0), other(46, 0), other(19, 1)));
    }

    private static DayState twelveByTwelveCap() {
        Terrain[] terrain = new Terrain[144];
        Arrays.fill(terrain, Terrain.PLAIN);
        List<AgentState> agents = new ArrayList<>();
        int[] patrolStarts = {62, 63, 64, 74, 75};
        for (int index = 0; index < patrolStarts.length; index++) {
            agents.add(AgentState.patrol(new AgentId(index), new Position(patrolStarts[index]), 30));
        }
        agents.add(AgentState.refuel(new AgentId(5), new Position(76)));
        return state(new HexMap(12, 12, terrain), 24, agents,
                List.of(cap("A", 31, 3), spot("B", 34), spot("C", 37), spot("D", 40),
                        cap("A", 52, 3), spot("B", 55), spot("C", 58), spot("D", 85)),
                List.of(other(31, 0), other(33, 0), other(51, 0), other(53, 0), other(86, 0),
                        other(74, 1)));
    }

    private static DayState state(
            HexMap map,
            int budget,
            List<AgentState> agents,
            List<UdonSpot> spots,
            List<ObservedOtherAgent> others) {
        Map<Position, Integer> stock = new LinkedHashMap<>();
        spots.forEach(spot -> stock.put(spot.position(), spot.stockCapacity()));
        StaticMatchData match = new StaticMatchData(
                map, new DayStepBudgets(new int[] {budget}), List.of(), new FuelCapacity(30), spots);
        return new DayState(match, new DayIndex(0), agents, Map.of(), stock,
                List.of(new ObservedOtherGroup(5, others)));
    }

    private static ObservedOtherAgent other(int position, int rawKind) {
        return new ObservedOtherAgent(new Position(position), rawKind, 40);
    }

    private static UdonSpot spot(String brand, int position) {
        return new UdonSpot(new BrandId(brand), new Position(position), 1);
    }

    private static UdonSpot cap(String brand, int position, int stock) {
        return new UdonSpot(new BrandId(brand), new Position(position), stock);
    }
}

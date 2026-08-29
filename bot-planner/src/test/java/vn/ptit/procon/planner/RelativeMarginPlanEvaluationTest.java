package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.domain.agent.AgentId;
import vn.ptit.procon.domain.map.Position;

class RelativeMarginPlanEvaluationTest {

    @Test
    void directDenialWinsWhenOwnScoreAndHorizonAreEqual() {
        RelativeMarginPlanEvaluation uncontested = evaluation(20, 20, 0, 65, "a");
        RelativeMarginPlanEvaluation denied = evaluation(20, 20, 1, 65, "b");
        assertTrue(denied.betterThan(uncontested));
    }

    @Test
    void conservativeOwnCollectionTieBeatsOneDenial() {
        RelativeMarginPlanEvaluation ownTwenty = evaluation(20, 20, 0, 65, "a");
        RelativeMarginPlanEvaluation ownNineteen = evaluation(19, 19, 1, 65, "b");
        assertTrue(ownTwenty.betterThan(ownNineteen));
        assertFalse(ownNineteen.betterThan(ownTwenty));
    }

    @Test
    void trueRelativeSwingTradeBeatsOneFewerOwnCollection() {
        RelativeMarginPlanEvaluation ownTwenty = evaluation(20, 20, 0, 65, "a");
        RelativeMarginPlanEvaluation ownNineteen = evaluation(19, 19, 2, 65, "b");
        assertTrue(ownNineteen.betterThan(ownTwenty));
    }

    @Test
    void harvestCapacityComesAfterOwnCountAndStrongSwingButBeforeSoftScore() {
        RelativeMarginPlanEvaluation poor = evaluation(20, 19, 0, 67, "a");
        RelativeMarginPlanEvaluation rich = evaluation(20, 20, 0, 65, "b");
        assertTrue(rich.betterThan(poor));
    }

    private static RelativeMarginPlanEvaluation evaluation(
            int own, int swingBase, int denied, int score, String signature) {
        PlanEvaluation base = new PlanEvaluation(4, own, 1, 10, 1, signature);
        SemiCommitmentAwarePlanEvaluation semi = new SemiCommitmentAwarePlanEvaluation(
                base, new SemiCommitmentAdjustedCollectionScore(score), 4, own, own, own,
                0, 0, 0, 0, 0, 0);
        java.util.ArrayList<BaselineOpponentClaim> claims = new java.util.ArrayList<>();
        for (int index = 0; index < denied; index++) {
            claims.add(new BaselineOpponentClaim(new CommittedOpponentClaim(
                    new ForecastOpponentClaim(7, index, 0, new Position(1), 10 + index,
                            IntentRank.PRIMARY, 1), OpponentClaimCommitment.DIRECT_INTENT), index));
        }
        OpponentClaimBaseline baseline = new OpponentClaimBaseline(
                denied == 0 ? Map.of() : Map.of(new Position(1), claims), denied, 0, denied, 0, 1);
        OpponentResidualClaimEvaluation denial = new OpponentResidualClaimEvaluation(
                baseline, 0, 0, 0, 0, denied, 0);
        List<PatrolNextDayHarvestCapacity> patrols = List.of(
                new PatrolNextDayHarvestCapacity(new AgentId(0), new Position(0), 10, 1,
                        false, swingBase, 4, 1, 1));
        TeamNextDayHarvestCapacity capacity = TeamNextDayHarvestCapacity.aggregate(1, 10, patrols, 0, 0);
        return new RelativeMarginPlanEvaluation(semi, denial, capacity);
    }
}
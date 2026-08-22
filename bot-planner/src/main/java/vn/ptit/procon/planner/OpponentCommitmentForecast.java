package vn.ptit.procon.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import vn.ptit.procon.domain.map.Position;

/**
 * M12 commitment annotation over an existing M10 opponent intent forecast.
 *
 * <p>Nothing is searched, routed or recomputed here: the annotation is a linear pass over the
 * forecast claims that were already accepted, so no Dijkstra call is added and the M10 forecast
 * itself stays byte-for-byte what the old modes consume.</p>
 *
 * <p>Commitment follows the realized claim order of each opponent agent, never the intent rank of
 * the target behind it. The M10 forecaster caps claims globally by stock, so an agent's PRIMARY
 * target can be dropped while its SECONDARY target survives; in that case the surviving claim is
 * the agent's first future claim and therefore the {@code DIRECT_INTENT} one.</p>
 */
public record OpponentCommitmentForecast(
        Map<Position, SpotCommitmentPressure> pressureBySpot,
        int observedAgentCount,
        int collectionEligibleAgentCount,
        int stockedSpotCount,
        int forecastClaims,
        int observedNowClaims,
        int directIntentClaims,
        int followOnIntentClaims,
        int hardConsumedPortions) {

    public OpponentCommitmentForecast {
        pressureBySpot = Map.copyOf(
                Objects.requireNonNull(pressureBySpot, "Commitment pressure must not be null"));
        if (observedAgentCount < 0 || collectionEligibleAgentCount < 0 || stockedSpotCount < 0
                || forecastClaims < 0 || observedNowClaims < 0 || directIntentClaims < 0
                || followOnIntentClaims < 0 || hardConsumedPortions < 0) {
            throw new IllegalArgumentException("Commitment forecast metrics must be non-negative");
        }
        if (observedNowClaims + directIntentClaims + followOnIntentClaims != forecastClaims) {
            throw new IllegalArgumentException(
                    "Commitment class counts must cover every accepted forecast claim");
        }
        if (hardConsumedPortions > observedNowClaims) {
            throw new IllegalArgumentException(
                    "Hard consumed portions cannot exceed the observed-now claims that caused them");
        }
    }

    /** Empty annotation used by every mode that does not run the M12 commitment forecast. */
    public static OpponentCommitmentForecast empty() {
        return new OpponentCommitmentForecast(Map.of(), 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Annotates every accepted claim of {@code forecast} with its commitment class.
     *
     * <p>Deterministic throughout: spots are walked in position order and each agent's accepted
     * claims are ordered by arrival step, then spot, then intent rank, so no hashed iteration order
     * can decide which claim becomes {@code DIRECT_INTENT}.</p>
     */
    public static OpponentCommitmentForecast annotate(OpponentIntentForecast forecast) {
        Objects.requireNonNull(forecast, "Opponent intent forecast must not be null");
        List<SpotIntentPressure> orderedSpots = forecast.pressureBySpot().values().stream()
                .sorted(Comparator.comparingInt(pressure -> pressure.spot().value()))
                .toList();

        Map<AgentKey, List<ForecastOpponentClaim>> claimsByAgent = new TreeMap<>();
        for (SpotIntentPressure pressure : orderedSpots) {
            for (ForecastOpponentClaim claim : pressure.claims()) {
                claimsByAgent
                        .computeIfAbsent(
                                new AgentKey(claim.groupRawId(), claim.agentIndex()),
                                ignored -> new ArrayList<>())
                        .add(claim);
            }
        }

        Map<ForecastOpponentClaim, OpponentClaimCommitment> commitments = new LinkedHashMap<>();
        for (List<ForecastOpponentClaim> agentClaims : claimsByAgent.values()) {
            agentClaims.sort(CLAIM_ORDER);
            boolean directAssigned = false;
            for (ForecastOpponentClaim claim : agentClaims) {
                OpponentClaimCommitment commitment;
                if (claim.forecastArrivalStep() == 0) {
                    commitment = OpponentClaimCommitment.OBSERVED_NOW;
                } else if (!directAssigned) {
                    commitment = OpponentClaimCommitment.DIRECT_INTENT;
                    directAssigned = true;
                } else {
                    commitment = OpponentClaimCommitment.FOLLOW_ON_INTENT;
                }
                commitments.put(claim, commitment);
            }
        }

        Map<Position, SpotCommitmentPressure> pressureBySpot = new LinkedHashMap<>();
        int observedNow = 0;
        int direct = 0;
        int followOn = 0;
        int hardConsumed = 0;
        for (SpotIntentPressure pressure : orderedSpots) {
            List<CommittedOpponentClaim> committed = new ArrayList<>();
            int spotObservedNow = 0;
            int spotDirect = 0;
            int spotFollowOn = 0;
            for (ForecastOpponentClaim claim : pressure.claims()) {
                OpponentClaimCommitment commitment = commitments.get(claim);
                committed.add(new CommittedOpponentClaim(claim, commitment));
                switch (commitment) {
                    case OBSERVED_NOW -> spotObservedNow++;
                    case DIRECT_INTENT -> spotDirect++;
                    case FOLLOW_ON_INTENT -> spotFollowOn++;
                }
            }
            int spotHardConsumed = Math.min(pressure.currentStock(), spotObservedNow);
            pressureBySpot.put(pressure.spot(), new SpotCommitmentPressure(
                    pressure.spot(),
                    pressure.currentStock(),
                    spotObservedNow,
                    spotDirect,
                    spotFollowOn,
                    spotHardConsumed,
                    committed));
            observedNow += spotObservedNow;
            direct += spotDirect;
            followOn += spotFollowOn;
            hardConsumed += spotHardConsumed;
        }

        return new OpponentCommitmentForecast(
                pressureBySpot,
                forecast.observedAgentCount(),
                forecast.collectionEligibleAgentCount(),
                forecast.stockedSpotCount(),
                observedNow + direct + followOn,
                observedNow,
                direct,
                followOn,
                hardConsumed);
    }

    public SpotCommitmentPressure pressureAt(Position spot) {
        return pressureBySpot.get(spot);
    }

    private static final Comparator<ForecastOpponentClaim> CLAIM_ORDER = Comparator
            .comparingInt(ForecastOpponentClaim::forecastArrivalStep)
            .thenComparingInt(claim -> claim.spot().value())
            .thenComparingInt(claim -> claim.rank().value());

    /** Deterministic per-agent grouping key; ordered so no hashed map decides commitment. */
    private record AgentKey(int groupRawId, int agentIndex) implements Comparable<AgentKey> {

        @Override
        public int compareTo(AgentKey other) {
            int byGroup = Integer.compare(groupRawId, other.groupRawId);
            return byGroup != 0 ? byGroup : Integer.compare(agentIndex, other.agentIndex);
        }
    }
}

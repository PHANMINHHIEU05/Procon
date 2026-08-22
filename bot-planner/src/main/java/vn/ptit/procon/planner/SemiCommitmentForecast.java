package vn.ptit.procon.planner;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/**
 * Cheap derived M12.1 view over an existing M12 {@link OpponentCommitmentForecast}.
 *
 * <p>Nothing is forecast, routed or recomputed here. The M10 forecast and the M12 commitment
 * annotation are the single source of truth; this record only precomputes the two bounded aggregates
 * the per-day diagnostic line needs, so the search itself never walks the spot map again.</p>
 *
 * <p>{@code maxSemiReservedPortions} is an invariant probe as much as a diagnostic: under the
 * production rule it must always be zero or one, because the reservation is capped per spot.</p>
 */
public record SemiCommitmentForecast(
        OpponentCommitmentForecast commitment,
        int semiReservedSpots,
        int maxSemiReservedPortions) {

    public SemiCommitmentForecast {
        Objects.requireNonNull(commitment, "Opponent commitment forecast must not be null");
        if (semiReservedSpots < 0 || maxSemiReservedPortions < 0) {
            throw new IllegalArgumentException(
                    "Semi-commitment forecast aggregates must be non-negative");
        }
        if (maxSemiReservedPortions > 1) {
            throw new IllegalArgumentException(
                    "The bounded semi reservation can never exceed one portion per spot");
        }
        if (semiReservedSpots > commitment.pressureBySpot().size()) {
            throw new IllegalArgumentException(
                    "Semi-reserved spots cannot exceed the forecast's stocked spots");
        }
    }

    /** Empty view used by every mode that does not run the M12.1 semi-commitment model. */
    public static SemiCommitmentForecast empty() {
        return new SemiCommitmentForecast(OpponentCommitmentForecast.empty(), 0, 0);
    }

    /**
     * Derives the bounded aggregates in one linear pass over the already annotated claims.
     *
     * <p>Spots are walked in position order so the pass never depends on the iteration order of the
     * immutable pressure map, whose hashing is salted.</p>
     */
    public static SemiCommitmentForecast derive(OpponentCommitmentForecast commitment) {
        Objects.requireNonNull(commitment, "Opponent commitment forecast must not be null");
        List<SpotCommitmentPressure> orderedSpots = commitment.pressureBySpot().values().stream()
                .sorted(Comparator.comparingInt(pressure -> pressure.spot().value()))
                .toList();
        int reservedSpots = 0;
        int maxReserved = 0;
        for (SpotCommitmentPressure pressure : orderedSpots) {
            int reserved = pressure.semiReservedDirectPortions();
            if (reserved > 0) {
                reservedSpots++;
            }
            maxReserved = Math.max(maxReserved, reserved);
        }
        return new SemiCommitmentForecast(commitment, reservedSpots, maxReserved);
    }

    public SpotCommitmentPressure pressureAt(Position spot) {
        return commitment.pressureAt(spot);
    }
}

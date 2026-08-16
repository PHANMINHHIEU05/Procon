package vn.ptit.procon.domain.opponent;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Neutral immutable current-day observation; raw kind and fuel semantics remain unknown. */
public record ObservedOtherAgent(Position position, int rawKind, int fuel) {

    public ObservedOtherAgent {
        Objects.requireNonNull(position, "Observed position must not be null");
    }
}
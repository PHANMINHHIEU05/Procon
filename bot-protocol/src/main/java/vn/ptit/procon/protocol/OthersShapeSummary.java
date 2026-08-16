package vn.ptit.procon.protocol;

import java.util.Objects;

/** Bounded value-free structural summary of the opaque {@code /state.others} node. */
public record OthersShapeSummary(
        String nodeType,
        int entries,
        String shape,
        boolean truncated) {

    public OthersShapeSummary {
        Objects.requireNonNull(nodeType, "Node type must not be null");
        Objects.requireNonNull(shape, "Shape must not be null");
        if (entries < 0) {
            throw new IllegalArgumentException("Entry count must be non-negative");
        }
    }
}

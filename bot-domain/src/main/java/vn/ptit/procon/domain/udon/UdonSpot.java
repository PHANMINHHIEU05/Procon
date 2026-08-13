package vn.ptit.procon.domain.udon;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Immutable static Udon spot configuration; collection state is intentionally absent. */
public record UdonSpot(BrandId brand, Position position, int stockCapacity) {

    public UdonSpot {
        Objects.requireNonNull(brand, "Udon brand must not be null");
        Objects.requireNonNull(position, "Udon spot position must not be null");
        if (stockCapacity < 0) {
            throw new IllegalArgumentException(
                    "Udon stock capacity must be non-negative: " + stockCapacity);
        }
    }
}
package vn.ptit.procon.domain.udon;

import java.util.Objects;

/** Opaque internal brand identity, independent from future wire-format mapping. */
public record BrandId(String value) {

    public BrandId {
        Objects.requireNonNull(value, "Brand ID must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Brand ID must not be blank");
        }
    }
}
package vn.ptit.procon.domain.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PositionTest {

    @Test
    void acceptsZero() {
        assertEquals(0, new Position(0).value());
    }

    @Test
    void acceptsPositiveValueAndUsesValueSemantics() {
        assertEquals(new Position(42), new Position(42));
    }

    @Test
    void rejectsNegativeValue() {
        assertThrows(IllegalArgumentException.class, () -> new Position(-1));
    }
}
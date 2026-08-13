package vn.ptit.procon.domain.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoveCostTest {

    @Test
    void acceptsOfficialNonNegativeCosts() {
        assertEquals(new MoveCost(2, 1), new MoveCost(2, 1));
        assertEquals(new MoveCost(1, 0), new MoveCost(1, 0));
    }

    @Test
    void rejectsInvalidCosts() {
        assertThrows(IllegalArgumentException.class, () -> new MoveCost(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new MoveCost(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MoveCost(1, -1));
    }
}
package vn.ptit.procon.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveR3PolicyTest {
    @Test void selectsAllTiersAndRoundsUnknownSizesUp() {
        assertEquals("P08", AdaptiveR3Policy.select(new MatchShape(8, 8, 6, 4, 1)).id());
        assertEquals("P08_4", AdaptiveR3Policy.select(new MatchShape(8, 8, 4, 4, 3)).id());
        assertEquals("P12", AdaptiveR3Policy.select(new MatchShape(10, 12, 6, 4, 3)).id());
        assertEquals("P16", AdaptiveR3Policy.select(new MatchShape(16, 16, 6, 4, 1)).id());
        assertEquals("P24", AdaptiveR3Policy.select(new MatchShape(20, 24, 8, 4, 3)).id());
        assertEquals("P32", AdaptiveR3Policy.select(new MatchShape(32, 32, 8, 4, 1)).id());
        assertEquals("P32", AdaptiveR3Policy.select(new MatchShape(40, 40, 8, 4, 3)).id());
    }

    @Test void scopesExpensiveParetoSearchToTheValidatedP12Profile() {
        assertEquals(0, AdaptiveR3Policy.select(new MatchShape(8, 8, 6, 4, 1)).paretoFirstHopExpansions());
        assertEquals(2, AdaptiveR3Policy.select(new MatchShape(12, 12, 6, 4, 1)).paretoFirstHopExpansions());
        assertEquals(0, AdaptiveR3Policy.select(new MatchShape(16, 16, 6, 4, 1)).paretoFirstHopExpansions());
    }
}

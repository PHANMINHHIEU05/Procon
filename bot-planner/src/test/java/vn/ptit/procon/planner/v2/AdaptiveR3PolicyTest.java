package vn.ptit.procon.planner.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdaptiveR3PolicyTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("procon.v2.beam_width");
        System.clearProperty("procon.hybrid.base_weight");
        System.clearProperty("procon.hybrid.coupled_weight");
    }

    @Test
    void mapsEverySupportedAndIntermediateDimensionToTheUpperTier() {
        assertEquals(R3MapTier.P08, shape(8, 1).tier());
        assertEquals(R3MapTier.P12, shape(9, 1).tier());
        assertEquals(R3MapTier.P12, shape(12, 3).tier());
        assertEquals(R3MapTier.P16, shape(13, 0).tier());
        assertEquals(R3MapTier.P24, shape(24, 2).tier());
        assertEquals(R3MapTier.P32, shape(25, 1).tier());
        assertEquals(R3MapTier.P32, shape(40, 3).tier());
    }

    @Test
    void opponentCountDoesNotChangePhysicalPlanningProfile() {
        AdaptiveR3Policy policy = AdaptiveR3Policy.defaults();
        R3PlannerProfile one = policy.select(shape(16, 1));
        R3PlannerProfile three = policy.select(shape(16, 3));
        assertEquals(one, three);
        assertEquals(R3SearchPreset.BALANCED.beamWidth(), one.beamWidth());
    }

    @Test
    void smallMapsSearchDeeperAndLargeMapsUseBoundedFastPreset() {
        AdaptiveR3Policy policy = AdaptiveR3Policy.defaults();
        R3PlannerProfile small = policy.select(shape(8, 1));
        R3PlannerProfile large = policy.select(shape(32, 1));
        assertEquals(R3SearchPreset.DEEP.beamWidth(), small.beamWidth());
        assertEquals(R3SearchPreset.FAST.beamWidth(), large.beamWidth());
        assertNotEquals(small.fingerprint(), large.fingerprint());
    }

    @Test
    void explicitExperimentPropertiesOverrideOnlyRequestedKnobs() {
        AdaptiveR3Policy policy = AdaptiveR3Policy.defaults();
        R3PlannerProfile original = policy.select(shape(24, 2));
        System.setProperty("procon.v2.beam_width", "77");
        System.setProperty("procon.hybrid.base_weight", "4");
        System.setProperty("procon.hybrid.coupled_weight", "0");
        R3PlannerProfile overridden = policy.select(shape(24, 2));
        assertEquals(77, overridden.beamWidth());
        assertEquals(original.maxExpandedStates(), overridden.maxExpandedStates());
        assertEquals(4, overridden.tuning().scoring().baseWeight());
        assertEquals(0, overridden.tuning().scoring().coupledWeight());
    }

    private static MatchShape shape(int dimension, int opponents) {
        return new MatchShape(dimension, dimension, dimension <= 8 ? 30 : 100, 8, opponents);
    }
}

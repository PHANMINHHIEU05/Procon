package vn.ptit.procon.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialScoreTest {
    @Test void comparesOfficialTupleLexicographically() {
        Model.OfficialScore coverage = new Model.OfficialScore(4, 8, 1_000, 4_000);
        Model.OfficialScore portions = new Model.OfficialScore(4, 8, 1_001, 2_000);
        Model.OfficialScore responseOnly = new Model.OfficialScore(4, 8, 1_001, 1_000);
        assertTrue(portions.compareTo(coverage) > 0);
        assertTrue(responseOnly.compareTo(portions) > 0, "lower response time wins only after all score fields tie");
        var left = new Model.Standing("left", 0, 4, 8, 1001, 1000);
        var right = new Model.Standing("right", 0, 4, 8, 1000, 1);
        assertTrue(OfficialScoreComparator.INSTANCE.compare(left, right) > 0);
    }
}

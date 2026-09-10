package vn.ptit.procon.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionTest {
    @Test void preservesOfficialTupleBeforeFuelAndThenAvoidsStrandingPatrol() {
        Model.Projection balanced = new Model.Projection(4, 12, 80, 0, 20, 160);
        Model.Projection stranded = new Model.Projection(4, 12, 80, 0, 2, 190);
        Model.Projection morePortions = new Model.Projection(4, 12, 81, 0, 0, 0);

        assertTrue(balanced.compareTo(stranded) > 0);
        assertTrue(morePortions.compareTo(balanced) > 0);
    }
}

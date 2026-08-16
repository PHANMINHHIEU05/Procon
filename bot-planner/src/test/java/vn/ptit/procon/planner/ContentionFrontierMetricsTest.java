package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContentionFrontierMetricsTest {

    @Test
    void safeProjectedCollectionsPrecedeEqualTotalProjectedCollections() {
        ContentionFrontierMetrics safe = new ContentionFrontierMetrics(
                2, 2, 3, 4, 10, 10, 2, 1, 0);
        ContentionFrontierMetrics contested = new ContentionFrontierMetrics(
                2, 0, 3, 4, 10, 10, 2, 1, 1);

        assertTrue(ContentionFrontierMetrics.preference().compare(safe, contested) < 0);
    }

    @Test
    void tiedProjectedCollectionsDoNotMasqueradeAsSafeAtTheFrontier() {
        ContentionFrontierMetrics oneSafe = new ContentionFrontierMetrics(
                2, 1, 3, 4, 10, 10, 2, 1, 0);
        ContentionFrontierMetrics allTied = new ContentionFrontierMetrics(
                2, 0, 3, 4, 10, 10, 2, 1, 1);

        assertTrue(ContentionFrontierMetrics.preference().compare(oneSafe, allTied) < 0);
    }
}
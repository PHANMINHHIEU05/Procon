package vn.ptit.procon.domain.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TrafficDomainTest {

    @ParameterizedTest
    @MethodSource("statusMappings")
    void mapsOfficialTrafficStatusCodes(int code, TrafficStatus expected) {
        assertEquals(expected, TrafficStatus.fromCode(code));
        assertEquals(code, expected.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3, 999})
    void rejectsUnknownTrafficStatusCodes(int code) {
        assertThrows(IllegalArgumentException.class, () -> TrafficStatus.fromCode(code));
    }

    @Test
    void trafficFlowReducesFractionsAndComparesExactly() {
        assertEquals(new TrafficFlow(1, 2), new TrafficFlow(2, 4));
        assertEquals(0, new TrafficFlow(10, 3).compareTo(new TrafficFlow(20, 6)));
        assertEquals(-1, new TrafficFlow(1, 3).compareTo(new TrafficFlow(1, 2)));
        assertEquals(1, new TrafficFlow(2, 3).compareTo(new TrafficFlow(1, 2)));
    }

    @Test
    void trafficFlowRejectsNegativeNumeratorAndNonPositiveDenominator() {
        assertThrows(IllegalArgumentException.class, () -> new TrafficFlow(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TrafficFlow(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TrafficFlow(1, -1));
    }

    @Test
    void thresholdsAllowEqualBoundaryValues() {
        TrafficThresholds thresholds = new TrafficThresholds(
                new TrafficFlow(10, 1), new TrafficFlow(10, 1));

        assertEquals(new TrafficFlow(10, 1), thresholds.congestedThreshold());
        assertEquals(new TrafficFlow(10, 1), thresholds.jammedThreshold());
    }

    @Test
    void thresholdsRejectJammedBelowCongested() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrafficThresholds(new TrafficFlow(11, 1), new TrafficFlow(10, 1)));
    }

    @Test
    void thresholdsRejectNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> TrafficThresholds.of(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> TrafficThresholds.of(10, -1));
    }

    private static Stream<Arguments> statusMappings() {
        return Stream.of(
                Arguments.of(0, TrafficStatus.CLEAR),
                Arguments.of(1, TrafficStatus.CONGESTED),
                Arguments.of(2, TrafficStatus.JAMMED));
    }
}
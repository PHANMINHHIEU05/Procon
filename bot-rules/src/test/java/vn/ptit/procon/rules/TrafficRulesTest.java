package vn.ptit.procon.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import vn.ptit.procon.domain.traffic.TrafficFlow;
import vn.ptit.procon.domain.traffic.TrafficStatus;
import vn.ptit.procon.domain.traffic.TrafficThresholds;

class TrafficRulesTest {

    private static final TrafficThresholds THRESHOLDS = TrafficThresholds.of(10, 20);

    @ParameterizedTest
    @MethodSource("classificationCases")
    void classifiesTrafficAtAndAroundThresholds(
            long stoppedSteps, int teamCount, TrafficStatus expected) {
        assertEquals(expected, TrafficRules.classify(stoppedSteps, teamCount, THRESHOLDS));
    }

    @Test
    void normalizesTrafficExactlyWithoutRounding() {
        assertEquals(new TrafficFlow(1, 2), TrafficRules.normalize(5, 10));
        assertEquals(new TrafficFlow(10, 1), TrafficRules.normalize(20, 2));
        assertEquals(TrafficStatus.CLEAR, TrafficRules.classify(99, 10, THRESHOLDS));
        assertEquals(TrafficStatus.CONGESTED, TrafficRules.classify(100, 10, THRESHOLDS));
        assertEquals(TrafficStatus.JAMMED, TrafficRules.classify(200, 10, THRESHOLDS));
    }

    @Test
    void rejectsInvalidNormalizationInputs() {
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.normalize(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.normalize(0, 0));
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.normalize(0, -1));
    }

    @Test
    void rejectsNullRuleInputs() {
        assertThrows(
                NullPointerException.class,
                () -> TrafficRules.classify(null, THRESHOLDS));
        assertThrows(
                NullPointerException.class,
                () -> TrafficRules.classify(new TrafficFlow(1, 1), null));
    }

    private static Stream<Arguments> classificationCases() {
        return Stream.of(
                Arguments.of(0, 1, TrafficStatus.CLEAR),
                Arguments.of(99, 10, TrafficStatus.CLEAR),
                Arguments.of(100, 10, TrafficStatus.CONGESTED),
                Arguments.of(199, 10, TrafficStatus.CONGESTED),
                Arguments.of(200, 10, TrafficStatus.JAMMED),
                Arguments.of(201, 10, TrafficStatus.JAMMED),
                Arguments.of(9, 1, TrafficStatus.CLEAR),
                Arguments.of(10, 1, TrafficStatus.CONGESTED),
                Arguments.of(20, 1, TrafficStatus.JAMMED));
    }
}
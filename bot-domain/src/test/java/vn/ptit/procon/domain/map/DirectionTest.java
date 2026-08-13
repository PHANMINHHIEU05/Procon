package vn.ptit.procon.domain.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DirectionTest {

    @ParameterizedTest
    @MethodSource("officialDirectionMappings")
    void mapsOfficialCodes(int code, Direction expected) {
        assertEquals(expected, Direction.fromCode(code));
        assertEquals(code, expected.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 6, 999})
    void rejectsUnknownCodes(int code) {
        assertThrows(IllegalArgumentException.class, () -> Direction.fromCode(code));
    }

    private static Stream<Arguments> officialDirectionMappings() {
        return Stream.of(
                Arguments.of(0, Direction.UP_LEFT),
                Arguments.of(1, Direction.UP_RIGHT),
                Arguments.of(2, Direction.RIGHT),
                Arguments.of(3, Direction.DOWN_RIGHT),
                Arguments.of(4, Direction.DOWN_LEFT),
                Arguments.of(5, Direction.LEFT));
    }
}
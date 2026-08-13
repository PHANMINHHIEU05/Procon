package vn.ptit.procon.domain.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TerrainTest {

    @ParameterizedTest
    @MethodSource("officialTerrainMappings")
    void mapsOfficialCodes(int code, Terrain expected) {
        assertEquals(expected, Terrain.fromCode(code));
        assertEquals(code, expected.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 4, 999})
    void rejectsUnknownCodes(int code) {
        assertThrows(IllegalArgumentException.class, () -> Terrain.fromCode(code));
    }

    private static Stream<Arguments> officialTerrainMappings() {
        return Stream.of(
                Arguments.of(0, Terrain.PLAIN),
                Arguments.of(1, Terrain.ROAD),
                Arguments.of(2, Terrain.MOUNTAIN),
                Arguments.of(3, Terrain.POND));
    }
}
package vn.ptit.procon.path;

import org.junit.jupiter.api.Test;
import vn.ptit.procon.model.Model;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFinderTest {
    @Test void retainsFastAndFuelSavingParetoPaths() {
        // 0 -> 1 -> 2 is slower but fuel-light (plain, mountain): 5 steps / 3 fuel.
        // 0 -> 4 -> 5 -> 2 is faster but fuel-hungry (plain, road, road): 4 steps / 5 fuel.
        Model.MapData map = new Model.MapData(3, 2, new int[][]{
                {0, 2, 0},
                {0, 1, 1}});
        PathFinder finder = new PathFinder();

        List<PathFinder.Path> frontier = finder.findPareto(map, 0, 2, new Model.Traffic[6], 10, 10, false, 3);

        assertTrue(frontier.stream().anyMatch(path -> path.steps() == 4 && path.fuel() == 5));
        assertTrue(frontier.stream().anyMatch(path -> path.steps() == 5 && path.fuel() == 3));
        PathFinder.Path fastest = finder.find(map, 0, 2, new Model.Traffic[6], 10, 10, false);
        assertEquals(4, fastest.steps());
        assertEquals(5, fastest.fuel());
        PathFinder.Path fuelBound = finder.find(map, 0, 2, new Model.Traffic[6], 10, 3, false);
        assertEquals(5, fuelBound.steps());
        assertEquals(3, fuelBound.fuel());
    }
}

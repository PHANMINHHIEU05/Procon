package vn.ptit.procon.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vn.ptit.procon.planner.StrategicDiversityKey.AgentOpening;

class StrategicDiversityKeyTest {

    @Test
    void distinguishesDifferentFirstTargetAssignmentsButNotObjectIdentity() {
        StrategicDiversityKey left = StrategicDiversityKey.of(
                List.of(new AgentOpening(0, 12), new AgentOpening(1, 40)));
        StrategicDiversityKey sameSemantics = StrategicDiversityKey.of(
                List.of(new AgentOpening(1, 40), new AgentOpening(0, 12)));
        StrategicDiversityKey swappedTargets = StrategicDiversityKey.of(
                List.of(new AgentOpening(0, 40), new AgentOpening(1, 12)));
        StrategicDiversityKey otherFirstTarget = StrategicDiversityKey.of(
                List.of(new AgentOpening(0, 12), new AgentOpening(1, 41)));

        assertNotSame(left, sameSemantics);
        assertEquals(left, sameSemantics);
        assertEquals(left.hashCode(), sameSemantics.hashCode());
        assertEquals(0, left.compareTo(sameSemantics));
        assertNotEquals(left, swappedTargets);
        assertNotEquals(left, otherFirstTarget);
        assertTrue(left.compareTo(otherFirstTarget) < 0);
        assertTrue(otherFirstTarget.compareTo(left) > 0);
    }

    @Test
    void insertionOrderOfOpeningsNeverChangesSetMembership() {
        Set<StrategicDiversityKey> keys = new LinkedHashSet<>();
        keys.add(StrategicDiversityKey.of(
                List.of(new AgentOpening(0, 5), new AgentOpening(1, 9), new AgentOpening(2, 2))));
        keys.add(StrategicDiversityKey.of(
                List.of(new AgentOpening(2, 2), new AgentOpening(0, 5), new AgentOpening(1, 9))));
        keys.add(StrategicDiversityKey.of(
                List.of(new AgentOpening(1, 9), new AgentOpening(2, 2), new AgentOpening(0, 5))));

        assertEquals(1, keys.size());
    }

    @Test
    void uncommittedAgentsUseStableSentinelInsteadOfAnEphemeralValue() {
        StrategicDiversityKey root = StrategicDiversityKey.of(
                List.of(AgentOpening.none(0), AgentOpening.none(1)));
        StrategicDiversityKey firstCommitted = StrategicDiversityKey.of(
                List.of(new AgentOpening(0, 7), AgentOpening.none(1)));

        assertTrue(root.uncommitted());
        assertEquals(0, root.committedAgents());
        assertFalse(firstCommitted.uncommitted());
        assertEquals(1, firstCommitted.committedAgents());
        assertEquals(StrategicDiversityKey.NO_TARGET,
                root.openings().get(0).firstTargetPosition());
        assertTrue(root.compareTo(firstCommitted) < 0);
    }

    @Test
    void keyRejectsDuplicateOrUnorderedAgentsAndStaysImmutable() {
        List<AgentOpening> mutable = new java.util.ArrayList<>(
                List.of(new AgentOpening(0, 3), new AgentOpening(1, 4)));
        StrategicDiversityKey key = new StrategicDiversityKey(mutable);
        mutable.clear();

        assertEquals(2, key.openings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> key.openings().add(new AgentOpening(9, 9)));
        assertThrows(IllegalArgumentException.class, () -> new StrategicDiversityKey(
                List.of(new AgentOpening(1, 4), new AgentOpening(0, 3))));
        assertThrows(IllegalArgumentException.class, () -> new StrategicDiversityKey(
                List.of(new AgentOpening(0, 3), new AgentOpening(0, 4))));
        assertThrows(IllegalArgumentException.class, () -> new AgentOpening(-1, 3));
        assertThrows(IllegalArgumentException.class, () -> new AgentOpening(0, -2));
    }

    private static void assertNotSame(Object first, Object second) {
        assertFalse(first == second, "Fixture must compare distinct instances");
    }
}

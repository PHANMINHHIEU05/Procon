package vn.ptit.procon.path;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.model.Model.MapData;
import vn.ptit.procon.model.Model.Traffic;
import vn.ptit.procon.rules.HexRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Fuel-constrained, multi-label Dijkstra retaining a bounded time/fuel Pareto frontier. */
public final class PathFinder {
    // Two labels are enough for the production menu (fast and fuel-saving); keeping a wider
    // frontier was measured to threaten the 3.2s search budget on dense traffic snapshots.
    private static final int MAX_LABELS_PER_CELL = 2;
    private static final int MAX_PARETO_EXPANSIONS = 2_000;

    /** Fast single-label Dijkstra for the common route-menu lookup. */
    public Path find(MapData map, int start, int target, Traffic[] traffic, int maxSteps, int maxFuel,
                     boolean unlimitedFuel) {
        if (!map.valid(start) || !map.valid(target) || maxSteps < 0 || maxFuel < 0) return null;
        int cells = map.width() * map.height();
        int[] bestSteps = new int[cells];
        int[] bestFuel = new int[cells];
        int[] previous = new int[cells];
        int[] previousDirection = new int[cells];
        Arrays.fill(bestSteps, Integer.MAX_VALUE);
        Arrays.fill(bestFuel, Integer.MAX_VALUE);
        Arrays.fill(previous, -1);
        Arrays.fill(previousDirection, -1);
        PriorityQueue<FastNode> queue = new PriorityQueue<>(Comparator.comparingInt(FastNode::steps)
                .thenComparingInt(FastNode::fuel));
        bestSteps[start] = 0;
        bestFuel[start] = 0;
        queue.add(new FastNode(start, 0, 0));
        while (!queue.isEmpty()) {
            FastNode node = queue.poll();
            if (node.steps() != bestSteps[node.position()] || node.fuel() != bestFuel[node.position()]) continue;
            if (node.position() == target) return rebuildFast(target, previous, previousDirection, node.steps(), node.fuel());
            for (int direction = 0; direction < 6; direction++) {
                int next = HexRules.neighbors(node.position(), map).get(direction);
                if (next < 0 || !map.terrain(next).traversable()) continue;
                Model.MoveCost cost = HexRules.moveCost(map, node.position(), traffic);
                if (cost == null) continue;
                int steps = node.steps() + cost.steps();
                int fuel = node.fuel() + cost.fuel();
                if (steps > maxSteps || (!unlimitedFuel && fuel > maxFuel)) continue;
                if (steps < bestSteps[next] || (steps == bestSteps[next] && fuel < bestFuel[next])) {
                    bestSteps[next] = steps;
                    bestFuel[next] = fuel;
                    previous[next] = node.position();
                    previousDirection[next] = direction;
                    queue.add(new FastNode(next, steps, fuel));
                }
            }
        }
        return null;
    }

    /**
     * Returns up to maxPaths representatives of the non-dominated (steps, fuel) terminal labels:
     * fastest, fuel-sparing and balanced paths first.
     */
    public List<Path> findPareto(MapData map, int start, int target, Traffic[] traffic, int maxSteps, int maxFuel,
                                 boolean unlimitedFuel, int maxPaths) {
        if (!map.valid(start) || !map.valid(target) || maxSteps < 0 || maxFuel < 0 || maxPaths <= 0) return List.of();
        int cells = map.width() * map.height();
        @SuppressWarnings("unchecked")
        List<Label>[] frontiers = new List[cells];
        for (int i = 0; i < cells; i++) frontiers[i] = new ArrayList<>();

        PriorityQueue<Label> queue = new PriorityQueue<>(Comparator
                .comparingInt(Label::steps)
                .thenComparingInt(Label::fuel));
        Label root = new Label(start, 0, 0, null, -1);
        frontiers[start].add(root);
        queue.add(root);

        int expansions = 0;
        while (!queue.isEmpty() && expansions < MAX_PARETO_EXPANSIONS) {
            Label label = queue.poll();
            if (!label.active) continue;
            expansions++;
            for (int direction = 0; direction < 6; direction++) {
                int next = HexRules.neighbors(label.position, map).get(direction);
                if (next < 0 || !map.terrain(next).traversable()) continue;
                Model.MoveCost cost = HexRules.moveCost(map, label.position, traffic);
                if (cost == null) continue;
                int steps = label.steps + cost.steps();
                int fuel = label.fuel + cost.fuel();
                if (steps > maxSteps || (!unlimitedFuel && fuel > maxFuel)) continue;
                Label candidate = new Label(next, steps, fuel, label, direction);
                if (accept(frontiers[next], candidate)) queue.add(candidate);
            }
        }
        return representatives(frontiers[target], maxPaths);
    }

    /** Inserts only non-dominated labels; cap is a deterministic safety guard for pathological maps. */
    private boolean accept(List<Label> frontier, Label candidate) {
        for (Label existing : frontier) if (existing.active && dominates(existing, candidate)) return false;
        for (Label existing : frontier) if (existing.active && dominates(candidate, existing)) existing.active = false;
        frontier.removeIf(label -> !label.active);
        frontier.add(candidate);
        if (frontier.size() <= MAX_LABELS_PER_CELL) return true;

        frontier.sort(Comparator.comparingInt((Label label) -> label.steps + label.fuel)
                .thenComparingInt(Label::steps).thenComparingInt(Label::fuel));
        Label discarded = frontier.removeLast();
        discarded.active = false;
        return discarded != candidate;
    }

    private boolean dominates(Label left, Label right) {
        // Equal labels are interchangeable: retaining both only creates duplicate route trees.
        return left.steps <= right.steps && left.fuel <= right.fuel;
    }

    private List<Path> representatives(List<Label> frontier, int maxPaths) {
        if (frontier.isEmpty()) return List.of();
        List<Label> active = frontier.stream().filter(label -> label.active).toList();
        if (active.isEmpty()) return List.of();
        List<Label> ordered = new ArrayList<>();
        addIfAbsent(ordered, active.stream().min(Comparator.comparingInt(Label::steps).thenComparingInt(Label::fuel)).orElseThrow());
        addIfAbsent(ordered, active.stream().min(Comparator.comparingInt(Label::fuel).thenComparingInt(Label::steps)).orElseThrow());
        addIfAbsent(ordered, active.stream().min(Comparator.comparingInt((Label label) -> label.steps + label.fuel)
                .thenComparingInt(Label::steps)).orElseThrow());
        active.stream().sorted(Comparator.comparingInt(Label::steps).thenComparingInt(Label::fuel))
                .forEach(label -> addIfAbsent(ordered, label));

        List<Path> result = new ArrayList<>();
        for (Label label : ordered) {
            if (result.size() == maxPaths) break;
            result.add(rebuild(label));
        }
        return List.copyOf(result);
    }

    private void addIfAbsent(List<Label> labels, Label label) {
        if (!labels.contains(label)) labels.add(label);
    }

    private Path rebuildFast(int target, int[] previous, int[] directions, int steps, int fuel) {
        int count = 0;
        int current = target;
        while (previous[current] >= 0) {
            count++;
            current = previous[current];
        }
        int[] result = new int[count];
        current = target;
        for (int i = count - 1; i >= 0; i--) {
            result[i] = directions[current];
            current = previous[current];
        }
        return new Path(result, steps, fuel, target);
    }

    private Path rebuild(Label target) {
        int count = 0;
        for (Label current = target; current.parent != null; current = current.parent) count++;
        int[] directions = new int[count];
        Label current = target;
        for (int i = count - 1; i >= 0; i--) {
            directions[i] = current.direction;
            current = current.parent;
        }
        return new Path(directions, target.steps, target.fuel, target.position);
    }

    private static final class Label {
        private final int position;
        private final int steps;
        private final int fuel;
        private final Label parent;
        private final int direction;
        private boolean active = true;

        private Label(int position, int steps, int fuel, Label parent, int direction) {
            this.position = position;
            this.steps = steps;
            this.fuel = fuel;
            this.parent = parent;
            this.direction = direction;
        }

        private int steps() { return steps; }
        private int fuel() { return fuel; }
    }

    private record FastNode(int position, int steps, int fuel) {}

    public record Path(int[] directions, int steps, int fuel, int target) {
        public Path { directions = directions.clone(); }
    }
}

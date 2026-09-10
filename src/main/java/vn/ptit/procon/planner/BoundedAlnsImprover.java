package vn.ptit.procon.planner;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.model.Model.AgentKind;
import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.Spot;
import vn.ptit.procon.model.Model.Traffic;
import vn.ptit.procon.path.PathFinder;
import vn.ptit.procon.rules.HexRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small destroy/repair neighbourhood used only when the portfolio leaves stock behind.
 *
 * <p>This is deliberately bounded rather than a full unbounded metaheuristic: a destroy operator
 * preserves a legal prefix of one Patrol's route, removes its suffix, and repairs it. P08 uses a
 * small exact route-DP over the six best targets; other shapes use the cheaper regret-like repair.
 * The caller exact-replays every output and keeps it only if the official projection strictly
 * improves.</p>
 */
public final class BoundedAlnsImprover {
    // Eight exact replays fit comfortably inside the reserved local-search slice.
    private static final int MAX_CANDIDATES = 8;
    private final PathFinder paths = new PathFinder();

    public List<Candidate> generate(Setup setup, DayState state, int[][] incumbent, Deadline deadline) {
        if (containsTanker(state) || incumbent.length != state.agents().size()) return List.of();
        Traffic[] traffic = HexRules.traffic(setup.map().width() * setup.map().height(), state.traffic());
        List<Integer> patrols = new ArrayList<>();
        for (int agent = 0; agent < state.agents().size(); agent++) {
            if (state.agents().get(agent).kind() == AgentKind.PATROL) patrols.add(agent);
        }
        if (patrols.isEmpty()) return List.of();

        List<Integer> longestFirst = patrols.stream().sorted(Comparator.comparingInt((Integer agent) ->
                leadingDirections(incumbent[agent]).size()).reversed()).toList();
        List<Integer> lowFuelFirst = patrols.stream().sorted(Comparator.comparingInt(agent -> state.agents().get(agent).fuel())).toList();
        List<Candidate> candidates = new ArrayList<>();
        DestroyOperator[] operators = DestroyOperator.values();
        for (int iteration = 0; iteration < MAX_CANDIDATES && !deadline.expired(); iteration++) {
            DestroyOperator operator = operators[iteration % operators.length];
            List<Integer> order = operator == DestroyOperator.LOW_FUEL ? lowFuelFirst : longestFirst;
            int agent = order.get((iteration / operators.length) % order.size());
            RepairAttempt attempt = repairOneRoute(setup, state, incumbent, traffic, agent, operator, deadline);
            if (attempt != null && !Arrays.deepEquals(incumbent, attempt.actions())) {
                candidates.add(new Candidate(attempt.actions(), attempt.label(), agent));
            }
        }
        return List.copyOf(candidates);
    }

    private boolean containsTanker(DayState state) {
        return state.agents().stream().anyMatch(agent -> agent.kind() == AgentKind.REFUEL);
    }

    private RepairAttempt repairOneRoute(Setup setup, DayState state, int[][] incumbent, Traffic[] traffic,
                                         int agent, DestroyOperator operator, Deadline deadline) {
        List<Integer> original = leadingDirections(incumbent[agent]);
        if (original.isEmpty()) return null;
        int cut = switch (operator) {
            case LONG_SUFFIX -> Math.max(0, original.size() / 3);
            case LATE_SUFFIX -> Math.max(0, original.size() * 2 / 3);
            case LOW_FUEL -> Math.max(0, original.size() / 2);
        };
        RouteCursor cursor = replayPrefix(setup, state, agent, original, cut, traffic);
        if (cursor == null) return null;

        List<Integer> repairedDirections = new ArrayList<>(original.subList(0, cut));
        int budget = setup.daySteps()[state.day()];
        String label = operator.name();
        if (usesExactLocalDp(setup, state)) {
            ExactSuffix suffix = new ExactP08RouteDp(setup, traffic, budget, deadline).solve(cursor);
            if (suffix != null) {
                repairedDirections.addAll(suffix.directions());
                cursor = suffix.cursor();
                label = "EXACT_DP_" + operator.name();
            }
        } else {
            for (int inserted = 0; inserted < 3; inserted++) {
                HarvestChoice choice = bestRepairChoice(setup, cursor, traffic, budget);
                if (choice == null) break;
                appendPath(setup, cursor, repairedDirections, choice.path());
            }
        }
        if (repairedDirections.size() == cut) return null;
        List<Integer> encoded = new ArrayList<>(repairedDirections);
        if (cursor.elapsed < budget) encoded.add(-(budget - cursor.elapsed));
        int[][] copy = Arrays.stream(incumbent).map(int[]::clone).toArray(int[][]::new);
        copy[agent] = encoded.stream().mapToInt(Integer::intValue).toArray();
        return new RepairAttempt(copy, label);
    }

    private boolean usesExactLocalDp(Setup setup, DayState state) {
        boolean p08 = setup.map().width() == 8 && setup.map().height() == 8
                && setup.agentCount() == 6 && setup.daySteps()[state.day()] == 30;
        // The four-Patrol P08 shape is common in the factory UI but has a different capacity
        // balance from the six-Patrol profile. Final-day exact repair improved two of five
        // replays and regressed none, then cleared a 5/5 three-Hard-Bot holdout. No next-day
        // staging remains to trade; false stays as an explicit emergency rollback.
        boolean p08FourAgentFinalDayChallenger = setup.map().width() == 8 && setup.map().height() == 8
                && setup.agentCount() == 4 && setup.daySteps()[state.day()] == 30
                && state.day() == setup.dayCount() - 1
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P08_4_FINAL_DAY_EXACT_DP", "true"));
        // P16 has enough response headroom for a shallower exact neighbourhood, but it remains
        // an offline challenger until it proves non-regression across the recorded corpus.
        boolean p16Shape = setup.map().width() == 16 && setup.map().height() == 16
                && setup.agentCount() == 6 && setup.daySteps()[state.day()] == 60;
        boolean p16Challenger = p16Shape
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P16_EXACT_LOCAL_DP", "false"));
        // On the final day, an exact suffix cannot accidentally trade away next-day staging.
        // It cleared the 17-journal replay gate and a five-for-five P16 three-Hard-Bot holdout;
        // keep an explicit false override for emergency rollback.
        boolean p16FinalDay = p16Shape && state.day() == setup.dayCount() - 1
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P16_FINAL_DAY_EXACT_DP", "true"));
        // P12 final-day exact suffix: it improved one of 29 complete journal replays and
        // regressed none, then cleared a fault-free 5/5 three-Hard-Bot holdout. The final-day
        // scope means it cannot trade portions for hypothetical staging; false remains an
        // explicit emergency rollback.
        boolean p12FinalDayChallenger = setup.map().width() == 12 && setup.map().height() == 12
                && setup.agentCount() == 6 && setup.daySteps()[state.day()] == 60
                && state.day() == setup.dayCount() - 1
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_P12_FINAL_DAY_EXACT_DP", "true"));
        boolean largeFinalDay = Math.max(setup.map().width(), setup.map().height()) >= 24
                && state.day() == setup.dayCount() - 1
                && Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_LARGE_FINAL_DAY_EXACT_DP", "false"));
        return p08 || p08FourAgentFinalDayChallenger || p12FinalDayChallenger || p16Challenger || p16FinalDay || largeFinalDay;
    }

    private void appendPath(Setup setup, RouteCursor cursor, List<Integer> directions, PathFinder.Path path) {
        for (int direction : path.directions()) {
            directions.add(direction);
            cursor.position = HexRules.neighbors(cursor.position, setup.map()).get(direction);
            int spotId = spotAt(setup, cursor.position);
            if (spotId >= 0) cursor.visited[spotId] = true;
        }
        cursor.elapsed += path.steps();
        cursor.fuel -= path.fuel();
    }

    private HarvestChoice bestRepairChoice(Setup setup, RouteCursor cursor, Traffic[] traffic, int budget) {
        HarvestChoice best = null;
        for (Spot spot : setup.spots()) {
            if (spot.stock() <= 0 || cursor.visited[spot.id()] || spot.position() == cursor.position) continue;
            PathFinder.Path path = paths.find(setup.map(), cursor.position, spot.position(), traffic,
                    budget - cursor.elapsed, cursor.fuel, false);
            if (path == null || path.steps() == 0) continue;
            int score = spot.stock() * 10_000 - path.steps() * 80 - path.fuel() * 20;
            if (best == null || score > best.score()) best = new HarvestChoice(path, score);
        }
        return best;
    }

    private RouteCursor replayPrefix(Setup setup, DayState state, int agent, List<Integer> directions,
                                     int cut, Traffic[] traffic) {
        Model.AgentState initial = state.agents().get(agent);
        RouteCursor cursor = new RouteCursor(initial.position(), initial.fuel(), 0, new boolean[setup.spots().size()]);
        for (int index = 0; index < cut; index++) {
            int direction = directions.get(index);
            int next = HexRules.neighbors(cursor.position, setup.map()).get(direction);
            if (next < 0 || !setup.map().terrain(next).traversable()) return null;
            Model.MoveCost cost = HexRules.moveCost(setup.map(), cursor.position, traffic);
            if (cost == null || cursor.fuel < cost.fuel()) return null;
            cursor.position = next;
            cursor.fuel -= cost.fuel();
            cursor.elapsed += cost.steps();
            int spotId = spotAt(setup, cursor.position);
            if (spotId >= 0) cursor.visited[spotId] = true;
        }
        return cursor;
    }

    private int spotAt(Setup setup, int position) {
        for (Spot spot : setup.spots()) if (spot.position() == position) return spot.id();
        return -1;
    }

    private List<Integer> leadingDirections(int[] actions) {
        List<Integer> result = new ArrayList<>();
        for (int action : actions) {
            if (action < 0) break;
            if (action <= 5) result.add(action);
        }
        return result;
    }

    /**
     * Exact dynamic programming in a deliberately tiny local neighbourhood. It enumerates route
     * suffixes through a small set of promising targets and a bounded hop count, memoising the complete local
     * state.  The later team simulator remains authoritative for shared stock/pass-throughs.
     */
    private final class ExactP08RouteDp {
        private final Setup setup;
        private final Traffic[] traffic;
        private final int budget;
        private final Deadline deadline;
        private final List<Spot> targets;
        private final Map<DpKey, Integer> seen = new HashMap<>();
        private ExactSuffix best;

        private ExactP08RouteDp(Setup setup, Traffic[] traffic, int budget, Deadline deadline) {
            this.setup = setup;
            this.traffic = traffic;
            this.budget = budget;
            this.deadline = deadline;
            this.targets = List.of();
        }

        private ExactSuffix solve(RouteCursor start) {
            List<RankedTarget> ordered = new ArrayList<>();
            for (Spot spot : setup.spots()) {
                if (spot.stock() <= 0 || start.visited[spot.id()] || spot.position() == start.position) continue;
                PathFinder.Path path = paths.find(setup.map(), start.position, spot.position(), traffic,
                        budget - start.elapsed, start.fuel, false);
                // The old code ran this identical Dijkstra a second time inside
                // initialPriority. Keep the same priority function and stable input order, but
                // reuse the already-selected path so this is a pure execution-time improvement.
                if (path != null && path.steps() > 0) ordered.add(new RankedTarget(spot, initialPriority(spot, path)));
            }
            ordered.sort(Comparator.comparingInt(RankedTarget::priority).reversed());
            List<Spot> capped = ordered.stream().limit(targetCap()).map(RankedTarget::spot).toList();
            if (capped.isEmpty()) return null;
            ExactP08RouteDp search = new ExactP08RouteDp(setup, traffic, budget, deadline, capped);
            search.explore(copyOf(start), new ArrayList<>(), 0, 0, 0);
            return search.best;
        }

        private ExactP08RouteDp(Setup setup, Traffic[] traffic, int budget, Deadline deadline, List<Spot> targets) {
            this.setup = setup;
            this.traffic = traffic;
            this.budget = budget;
            this.deadline = deadline;
            this.targets = targets;
        }

        private int initialPriority(Spot spot, PathFinder.Path path) {
            return spot.stock() * 1_000 - path.steps() * 12 - path.fuel() * 4;
        }

        private void explore(RouteCursor cursor, List<Integer> directions, int mask, int depth, int score) {
            if (deadline.expired()) return;
            if (!directions.isEmpty() && (best == null || score > best.score()
                    || (score == best.score() && cursor.fuel > best.cursor().fuel))) {
                best = new ExactSuffix(copyOf(cursor), List.copyOf(directions), score);
            }
            if (depth >= maxHops()) return;
            DpKey key = new DpKey(mask, cursor.position, cursor.elapsed, cursor.fuel);
            Integer known = seen.get(key);
            if (known != null && known >= score) return;
            seen.put(key, score);

            for (int index = 0; index < targets.size(); index++) {
                if ((mask & (1 << index)) != 0) continue;
                Spot target = targets.get(index);
                if (cursor.visited[target.id()]) continue;
                PathFinder.Path path = paths.find(setup.map(), cursor.position, target.position(), traffic,
                        budget - cursor.elapsed, cursor.fuel, false);
                if (path == null || path.steps() == 0) continue;
                RouteCursor next = copyOf(cursor);
                List<Integer> extended = new ArrayList<>(directions);
                int harvested = appendAndCountHarvest(setup, next, extended, path);
                int nextScore = score + harvested * 1_000 + target.stock() * 40
                        - path.steps() * 10 - path.fuel() * 4;
                explore(next, extended, mask | (1 << index), depth + 1, nextScore);
            }
        }

        private int targetCap() {
            if (setup.map().width() == 8 && setup.map().height() == 8) return 6;
            return Math.max(setup.map().width(), setup.map().height()) >= 24 ? 4 : 5;
        }

        private int maxHops() {
            if (setup.map().width() == 8 && setup.map().height() == 8) return 4;
            return Math.max(setup.map().width(), setup.map().height()) >= 24 ? 2 : 3;
        }
    }

    private int appendAndCountHarvest(Setup setup, RouteCursor cursor, List<Integer> directions, PathFinder.Path path) {
        int harvested = 0;
        for (int direction : path.directions()) {
            directions.add(direction);
            cursor.position = HexRules.neighbors(cursor.position, setup.map()).get(direction);
            int spotId = spotAt(setup, cursor.position);
            if (spotId >= 0 && !cursor.visited[spotId]) {
                cursor.visited[spotId] = true;
                harvested++;
            }
        }
        cursor.elapsed += path.steps();
        cursor.fuel -= path.fuel();
        return harvested;
    }

    private RouteCursor copyOf(RouteCursor source) {
        return new RouteCursor(source.position, source.fuel, source.elapsed, source.visited.clone());
    }

    private enum DestroyOperator { LONG_SUFFIX, LATE_SUFFIX, LOW_FUEL }
    private record HarvestChoice(PathFinder.Path path, int score) {}
    private record RepairAttempt(int[][] actions, String label) {}
    private record ExactSuffix(RouteCursor cursor, List<Integer> directions, int score) {}
    private record RankedTarget(Spot spot, int priority) {}
    private record DpKey(int mask, int position, int elapsed, int fuel) {}
    private static final class RouteCursor {
        private int position;
        private int fuel;
        private int elapsed;
        private final boolean[] visited;

        private RouteCursor(int position, int fuel, int elapsed, boolean[] visited) {
            this.position = position;
            this.fuel = fuel;
            this.elapsed = elapsed;
            this.visited = visited;
        }
    }
    public record Candidate(int[][] actions, String operator, int repairedAgent) {
        public Candidate { actions = Arrays.stream(actions).map(int[]::clone).toArray(int[][]::new); }
    }
}

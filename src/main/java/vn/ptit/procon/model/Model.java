package vn.ptit.procon.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class Model {
    private Model() {}

    public enum AgentKind {
        PATROL(0), REFUEL(1);
        private final int code;
        AgentKind(int code) { this.code = code; }
        public int code() { return code; }
        public static AgentKind fromCode(int code) {
            return code == 1 ? REFUEL : PATROL;
        }
    }

    public enum Terrain {
        PLAIN(0, true), ROAD(1, true), MOUNTAIN(2, true), POND(3, false);
        private final int code;
        private final boolean traversable;
        Terrain(int code, boolean traversable) { this.code = code; this.traversable = traversable; }
        public int code() { return code; }
        public boolean traversable() { return traversable; }
        public static Terrain fromCode(int code) {
            return switch (code) {
                case 0 -> PLAIN;
                case 1 -> ROAD;
                case 2 -> MOUNTAIN;
                case 3 -> POND;
                default -> throw new IllegalArgumentException("Unknown terrain " + code);
            };
        }
    }

    public enum Traffic {
        CLEAR(0), CONGESTED(1), JAMMED(2);
        private final int code;
        Traffic(int code) { this.code = code; }
        public int code() { return code; }
        public static Traffic fromCode(int code) {
            return switch (code) {
                case 0 -> CLEAR;
                case 1 -> CONGESTED;
                case 2 -> JAMMED;
                default -> throw new IllegalArgumentException("Unknown traffic " + code);
            };
        }
    }

    public record Position(int row, int col) {
        public int flat(int width) { return row * width + col; }
        public static Position fromFlat(int flat, int width) {
            return new Position(flat / width, flat % width);
        }
    }

    public record MoveCost(int steps, int fuel) {}

    public record MapData(int width, int height, int[][] cells) {
        public MapData {
            if (width <= 0 || height <= 0 || cells.length != height) {
                throw new IllegalArgumentException("Invalid map dimensions");
            }
            cells = Arrays.stream(cells).map(int[]::clone).toArray(int[][]::new);
        }
        public Terrain terrain(int flat) {
            Position p = Position.fromFlat(flat, width);
            return Terrain.fromCode(cells[p.row()][p.col()]);
        }
        public boolean valid(int flat) { return flat >= 0 && flat < width * height; }
    }

    public record Spot(int id, String brand, int position, int stock) {
        public Spot {
            Objects.requireNonNull(brand, "brand");
            if (stock < 0) throw new IllegalArgumentException("Negative stock");
        }
    }

    public record Setup(
            MapData map,
            List<Spot> spots,
            int[] startPositions,
            int[] daySteps,
            int fuelLimit) {
        public Setup {
            spots = List.copyOf(spots);
            startPositions = startPositions.clone();
            daySteps = daySteps.clone();
            if (fuelLimit <= 0) throw new IllegalArgumentException("Invalid fuel limit");
        }
        public int agentCount() { return startPositions.length; }
        public int dayCount() { return daySteps.length; }
    }

    public record AgentState(AgentKind kind, int position, int fuel) {}

    public record DayState(int day, List<AgentState> agents, List<TrafficCell> traffic) {
        public DayState { agents = List.copyOf(agents); traffic = List.copyOf(traffic); }
    }

    public record TrafficCell(int position, Traffic traffic) {}

    public record SubmissionAck(boolean valid, String reason, long responseMillis) {}

    public record Standing(String teamId, int rank, int globalTypes, int dailyTypesSum,
                           int portions, long responseMillis) {}

    public record MatchResult(List<Standing> standings) {
        public MatchResult { standings = List.copyOf(standings); }
    }

    public record OfficialScore(int globalTypes, int dailyTypesSum, int portions,
                                long responseMillis) implements Comparable<OfficialScore> {
        @Override public int compareTo(OfficialScore other) {
            int c = Integer.compare(globalTypes, other.globalTypes);
            if (c != 0) return c;
            c = Integer.compare(dailyTypesSum, other.dailyTypesSum);
            if (c != 0) return c;
            c = Integer.compare(portions, other.portions);
            if (c != 0) return c;
            return Long.compare(other.responseMillis, responseMillis);
        }
    }

    public record Projection(int globalTypes, int dailyTypesSum, int portions,
                             int remainingTypes, int minPatrolFuel, int fuelSlack) implements Comparable<Projection> {
        @Override public int compareTo(Projection other) {
            int c = Integer.compare(globalTypes, other.globalTypes);
            if (c != 0) return c;
            c = Integer.compare(dailyTypesSum, other.dailyTypesSum);
            if (c != 0) return c;
            c = Integer.compare(portions, other.portions);
            if (c != 0) return c;
            // This is deliberately a *remaining* count.  It is only a tie-break after the
            // official tuple, where fewer unopened types is better for the next day.
            c = Integer.compare(other.remainingTypes, remainingTypes);
            if (c != 0) return c;
            // A single stranded Patrol is more harmful than a slightly smaller total reserve.
            // This only acts after all official score fields are tied.
            c = Integer.compare(minPatrolFuel, other.minPatrolFuel);
            if (c != 0) return c;
            return Integer.compare(fuelSlack, other.fuelSlack);
        }
    }

    public record PlannedDay(int day, int[][] actions, Projection projection, String fingerprint) {
        public PlannedDay {
            actions = Arrays.stream(actions).map(int[]::clone).toArray(int[][]::new);
        }
    }
}

package vn.ptit.procon.benchmark;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.planner.Deadline;
import vn.ptit.procon.planner.PortfolioPlanner;
import vn.ptit.procon.protocol.JsonProtocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Measures the production portfolio on captured day-zero states without network access. */
public final class PlannerBenchmarkMain {
    private PlannerBenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of(System.getProperty("user.home"), ".procon-autotune", "corpus") : Path.of(args[0]);
        int limit = args.length < 2 ? 40 : Integer.parseInt(args[1]);
        int maxMapSize = args.length < 3 ? Integer.MAX_VALUE : Integer.parseInt(args[2]);
        List<Long> millis = new ArrayList<>();
        int valid = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path setupFile : stream.filter(p -> p.getFileName().toString().equals("setup.json")).sorted().toList()) {
                if (millis.size() >= limit) break;
                Path stateFile = setupFile.getParent().resolve("day-0/state.json");
                if (!Files.isRegularFile(stateFile)) continue;
                Model.Setup setup = JsonProtocol.setup(Files.readString(setupFile));
                if (Math.max(setup.map().width(), setup.map().height()) > maxMapSize) continue;
                Model.DayState state = JsonProtocol.state(Files.readString(stateFile));
                PortfolioPlanner planner = new PortfolioPlanner(setup);
                planner.chooseAssignment();
                long start = System.nanoTime();
                Model.PlannedDay plan = planner.plan(state, Deadline.afterMillis(planner.profile().plannerBudgetMs()));
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                millis.add(elapsed);
                if (plan != null) valid++;
            }
        }
        millis.sort(Comparator.naturalOrder());
        long p50 = percentile(millis, .50), p95 = percentile(millis, .95), max = millis.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.printf("PLANNER_BENCHMARK samples=%d valid=%d p50Ms=%d p95Ms=%d maxMs=%d%n", millis.size(), valid, p50, p95, max);
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        return values.get(Math.min(values.size() - 1, (int) Math.ceil(percentile * values.size()) - 1));
    }
}

package vn.ptit.procon.benchmark;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.planner.AdaptivePlanner;
import vn.ptit.procon.planner.Deadline;
import vn.ptit.procon.planner.PortfolioPlanner;
import vn.ptit.procon.protocol.JsonProtocol;
import vn.ptit.procon.simulation.ExactSimulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Replays a recorded match under an alternative planner while retaining the recorded traffic for
 * every day.  The team's simulated positions/fuel are carried forward, so this is a genuine
 * four-day counterfactual rather than four unrelated single-day comparisons.
 *
 * <p>Traffic is supplied by the server snapshot and teams are ghosts, therefore the replay does
 * not assume that our alternative route changes another team's traffic or stock.  It is an
 * offline selection tool only: a live holdout remains the promotion gate.</p>
 */
public final class JournalStrategyMain {
    private JournalStrategyMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: JournalStrategyMain <match-journal-dir> [--role-mode auto|five-one] [--budget-ms N] [--single-arm NAME]");
        }
        Path journal = Path.of(args[0]);
        AdaptivePlanner.RoleMode roleMode = AdaptivePlanner.RoleMode.AUTO;
        AdaptivePlanner.Heuristic singleArm = null;
        long budgetMillis = 3_200;
        for (int index = 1; index < args.length; index++) {
            switch (args[index]) {
                case "--role-mode" -> roleMode = switch (args[++index].toLowerCase()) {
                    case "auto" -> AdaptivePlanner.RoleMode.AUTO;
                    case "five-one" -> AdaptivePlanner.RoleMode.FIVE_ONE;
                    default -> throw new IllegalArgumentException("Invalid role mode");
                };
                case "--budget-ms" -> budgetMillis = Long.parseLong(args[++index]);
                case "--single-arm" -> singleArm = AdaptivePlanner.Heuristic.valueOf(args[++index].toUpperCase(Locale.ROOT));
                default -> throw new IllegalArgumentException("Unknown option " + args[index]);
            }
        }
        if (budgetMillis < 50) throw new IllegalArgumentException("--budget-ms must be >= 50");
        if (singleArm == null) replay(journal, roleMode, budgetMillis, true);
        else replaySingleArm(journal, singleArm, roleMode, budgetMillis, true);
    }

    /** Public for regression tests and offline optimization callers. */
    public static Result replay(Path journal, AdaptivePlanner.RoleMode roleMode, long budgetMillis,
                                boolean printDays) throws Exception {
        Model.Setup setup = JsonProtocol.setup(Files.readString(journal.resolve("setup.json")));
        Model.DayState initial = JsonProtocol.state(Files.readString(journal.resolve("day-0-state.json")));
        PortfolioPlanner planner = new PortfolioPlanner(setup, roleMode);
        int[] roles = planner.chooseAssignment();
        if (roles.length != initial.agents().size()) throw new IllegalArgumentException("Role count mismatch");

        List<Model.AgentState> agents = withRoles(initial.agents(), roles);
        ExactSimulator simulator = new ExactSimulator();
        Set<String> globalBrands = new HashSet<>();
        int dailyTypes = 0;
        int totalPortions = 0;
        List<DayResult> days = new ArrayList<>();
        for (int day = 0; day < setup.dayCount(); day++) {
            Path stateFile = journal.resolve("day-" + day + "-state.json");
            if (!Files.isRegularFile(stateFile)) throw new IllegalArgumentException("Missing day state " + day);
            Model.DayState recorded = JsonProtocol.state(Files.readString(stateFile));
            Model.DayState counterfactual = new Model.DayState(day, agents, recorded.traffic());
            long started = System.nanoTime();
            Model.PlannedDay plan = planner.plan(counterfactual, Deadline.afterMillis(budgetMillis));
            long plannerMillis = (System.nanoTime() - started) / 1_000_000L;
            ExactSimulator.SimulationResult simulated = simulator.simulate(setup, counterfactual, plan.actions());
            planner.observe(counterfactual, plan);
            agents = after(simulated, agents);
            globalBrands.addAll(simulated.brands());
            dailyTypes += simulated.brands().size();
            totalPortions += simulated.portions();
            DayResult result = new DayResult(day, simulated.portions(), simulated.brands().size(),
                    simulated.fuel(), planner.selectedArmStrategy(), planner.selectedStrategy(), plannerMillis,
                    plan.fingerprint());
            days.add(result);
            if (printDays) {
                System.out.printf("COUNTERFACTUAL_DAY day=%d arm=%s strategy=%s portions=%d brands=%d plannerMs=%d fuel=%s fingerprint=%s%n",
                        result.day(), result.arm(), result.strategy(), result.portions(), result.dailyTypes(), result.plannerMillis(),
                        java.util.Arrays.toString(result.fuel()), result.fingerprint());
            }
        }
        Result result = new Result(globalBrands.size(), dailyTypes, totalPortions, List.copyOf(days));
        if (printDays) {
            System.out.printf("COUNTERFACTUAL_SCORE globalTypes=%d dailyTypesSum=%d portions=%d roles=%s%n",
                    result.globalTypes(), result.dailyTypesSum(), result.portions(), java.util.Arrays.toString(roles));
        }
        return result;
    }

    /**
     * Replays one underlying route constructor without portfolio selection. This is an offline
     * diagnostic for deciding whether a candidate is worth teaching the production selector;
     * it is not a live promotion gate.
     */
    public static Result replaySingleArm(Path journal, AdaptivePlanner.Heuristic heuristic,
                                         AdaptivePlanner.RoleMode roleMode, long budgetMillis,
                                         boolean printDays) throws Exception {
        Model.Setup setup = JsonProtocol.setup(Files.readString(journal.resolve("setup.json")));
        Model.DayState initial = JsonProtocol.state(Files.readString(journal.resolve("day-0-state.json")));
        AdaptivePlanner planner = new AdaptivePlanner(setup, heuristic, roleMode);
        int[] roles = planner.chooseAssignment();
        if (roles.length != initial.agents().size()) throw new IllegalArgumentException("Role count mismatch");

        List<Model.AgentState> agents = withRoles(initial.agents(), roles);
        ExactSimulator simulator = new ExactSimulator();
        Set<String> globalBrands = new HashSet<>();
        int dailyTypes = 0;
        int totalPortions = 0;
        List<DayResult> days = new ArrayList<>();
        for (int day = 0; day < setup.dayCount(); day++) {
            Path stateFile = journal.resolve("day-" + day + "-state.json");
            if (!Files.isRegularFile(stateFile)) throw new IllegalArgumentException("Missing day state " + day);
            Model.DayState recorded = JsonProtocol.state(Files.readString(stateFile));
            Model.DayState counterfactual = new Model.DayState(day, agents, recorded.traffic());
            long started = System.nanoTime();
            Model.PlannedDay plan = planner.plan(counterfactual, Deadline.afterMillis(budgetMillis));
            long plannerMillis = (System.nanoTime() - started) / 1_000_000L;
            ExactSimulator.SimulationResult simulated = simulator.simulate(setup, counterfactual, plan.actions());
            planner.observe(counterfactual, plan);
            agents = after(simulated, agents);
            globalBrands.addAll(simulated.brands());
            dailyTypes += simulated.brands().size();
            totalPortions += simulated.portions();
            DayResult result = new DayResult(day, simulated.portions(), simulated.brands().size(),
                    simulated.fuel(), heuristic.name(), heuristic.name(), plannerMillis, plan.fingerprint());
            days.add(result);
            if (printDays) {
                System.out.printf("COUNTERFACTUAL_DAY day=%d arm=%s strategy=%s portions=%d brands=%d plannerMs=%d fuel=%s fingerprint=%s%n",
                        result.day(), result.arm(), result.strategy(), result.portions(), result.dailyTypes(), result.plannerMillis(),
                        java.util.Arrays.toString(result.fuel()), result.fingerprint());
            }
        }
        Result result = new Result(globalBrands.size(), dailyTypes, totalPortions, List.copyOf(days));
        if (printDays) {
            System.out.printf("COUNTERFACTUAL_SCORE globalTypes=%d dailyTypesSum=%d portions=%d roles=%s%n",
                    result.globalTypes(), result.dailyTypesSum(), result.portions(), java.util.Arrays.toString(roles));
        }
        return result;
    }

    private static List<Model.AgentState> withRoles(List<Model.AgentState> original, int[] roles) {
        List<Model.AgentState> result = new ArrayList<>(original.size());
        for (int index = 0; index < original.size(); index++) {
            Model.AgentState agent = original.get(index);
            result.add(new Model.AgentState(Model.AgentKind.fromCode(roles[index]), agent.position(), agent.fuel()));
        }
        return List.copyOf(result);
    }

    private static List<Model.AgentState> after(ExactSimulator.SimulationResult result, List<Model.AgentState> before) {
        List<Model.AgentState> agents = new ArrayList<>(before.size());
        for (int index = 0; index < before.size(); index++) {
            agents.add(new Model.AgentState(before.get(index).kind(), result.positions()[index], result.fuel()[index]));
        }
        return List.copyOf(agents);
    }

    public record DayResult(int day, int portions, int dailyTypes, int[] fuel, String arm, String strategy,
                            long plannerMillis, String fingerprint) {
        public DayResult { fuel = fuel.clone(); }
    }

    public record Result(int globalTypes, int dailyTypesSum, int portions, List<DayResult> days) {
        public Result { days = List.copyOf(days); }
    }
}

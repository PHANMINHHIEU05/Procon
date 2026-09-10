package vn.ptit.procon.app;

import vn.ptit.procon.model.Model;
import vn.ptit.procon.model.Model.AgentKind;
import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.planner.Deadline;
import vn.ptit.procon.planner.AdaptivePlanner;
import vn.ptit.procon.planner.PortfolioPlanner;
import vn.ptit.procon.protocol.AutoTransport;
import vn.ptit.procon.protocol.HttpTransport;
import vn.ptit.procon.protocol.MatchTransport;
import vn.ptit.procon.protocol.WebSocketTransport;
import vn.ptit.procon.simulation.ExactSimulator;

import java.time.Duration;
import java.util.Arrays;

public final class BotMain {
    private BotMain() {}

    public static void main(String[] args) {
        try { run(new Options(args)); }
        catch (Exception exception) {
            System.err.println("BOT_FATAL " + exception.getClass().getSimpleName() + ": " + safe(exception.getMessage()));
            System.exit(2);
        }
    }

    private static void run(Options options) throws Exception {
        String token = System.getenv("PROCON_TOKEN");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("PROCON_TOKEN is required");
        MatchTransport transport = switch (options.transport) {
            case "http" -> new HttpTransport(options.url, options.match, token, Duration.ofSeconds(15));
            case "ws" -> new WebSocketTransport(options.url, options.match, token);
            default -> new AutoTransport(options.url, options.match, token, Duration.ofSeconds(15));
        };
        try (transport) {
            Model.Setup setup = transport.awaitSetup(System.nanoTime() + 60_000_000_000L);
            PortfolioPlanner planner = new PortfolioPlanner(setup, options.roleMode);
            MatchJournal journal = new MatchJournal(options.match);
            journal.setup(setup);
            int[] roles = planner.chooseAssignment();
            Model.SubmissionAck assignment = transport.submitAssignment(roles);
            if (!assignment.valid()) throw new IllegalStateException("Assignment rejected: " + assignment.reason());
            System.err.printf("BOT_READY match=%s transport=%s map=%dx%d agents=%d roles=%s%n",
                    options.match, options.transport, setup.map().width(), setup.map().height(), setup.agentCount(), Arrays.toString(roles));
            System.err.printf("BOT_PROFILE id=%s targetCap=%d routeBeam=%d routesPerPatrol=%d teamBeam=%d plannerBudgetMs=%d paretoFirstHops=%d%n",
                    planner.profile().id(), planner.profile().targetCap(), planner.profile().routeBeam(),
                    planner.profile().routesPerPatrol(), planner.profile().teamBeam(), planner.profile().plannerBudgetMs(),
                    planner.profile().paretoFirstHopExpansions());

            int lastDay = -1;
            for (int day = 0; day < setup.dayCount(); day++) {
                DayState state;
                try {
                    // The response window starts when this team receives the new day-state,
                    // not when it begins waiting for another team/the server to advance.  Using
                    // the five-second answer window for this wait caused otherwise healthy
                    // matches to abandon day 2 while Hard Bot was still finishing day 1.
                    state = transport.awaitNextDay(lastDay, System.nanoTime() + 60_000_000_000L);
                } catch (java.io.IOException waitFailure) {
                    // Some practice matches publish result immediately after the last accepted
                    // action instead of exposing another /state snapshot.
                    try {
                        Model.MatchResult earlyResult = transport.awaitResult(System.nanoTime() + 5_000_000_000L);
                        if (!earlyResult.standings().isEmpty()) {
                            journal.result(earlyResult);
                            System.err.println("BOT_RESULT standings=" + earlyResult.standings().size());
                            return;
                        }
                    } catch (Exception ignored) {
                        // Preserve the original state-wait failure when result is not ready.
                    }
                    throw waitFailure;
                }
                journal.state(state);
                // Keep a real safety margin below the 3.4s planner gate for the last exact
                // validation, JSON encode and network retry.  The profile is immutable per map.
                long planBudget = Math.max(50, Math.min(planner.profile().plannerBudgetMs(),
                        options.responseMs - networkReserveMillis(setup, options.responseMs)));
                long planningStarted = System.nanoTime();
                Model.PlannedDay plan = planner.plan(state, Deadline.afterMillis(planBudget));
                long planningMs = (System.nanoTime() - planningStarted) / 1_000_000L;
                int[][] actions = plan.actions();
                boolean fallback = false;
                try {
                    new ExactSimulator().simulate(setup, state, actions);
                } catch (RuntimeException invalid) {
                    actions = emergencyPlan(setup, state);
                    fallback = true;
                    System.err.printf("BOT_FALLBACK day=%d reason=%s%n", state.day(), safe(invalid.getMessage()));
                }
                Model.SubmissionAck ack = transport.submitActions(state.day(), actions);
                if (!ack.valid()) throw new IllegalStateException("Actions rejected on day " + state.day() + ": " + ack.reason());
                if (!fallback) planner.observe(state, plan);
                journal.plan(new Model.PlannedDay(state.day(), actions, plan.projection(), plan.fingerprint()), ack);
                System.err.printf("BOT_DAY day=%d arm=%s strategy=%s paretoPaths=%d earlyFinalExit=%s plannerMs=%d fingerprint=%s projection=%s responseMs=%d%n",
                        state.day(), planner.selectedArmStrategy(), planner.selectedStrategy(), planner.selectedParetoPathsOffered(), planner.earlyFinalExit(),
                        planningMs, plan.fingerprint(), plan.projection(), ack.responseMillis());
                lastDay = state.day();
            }
            Model.MatchResult result = transport.awaitResult(System.nanoTime() + 60_000_000_000L);
            journal.result(result);
            System.err.println("BOT_RESULT standings=" + result.standings().size());
        }
    }

    private static int[][] emergencyPlan(Model.Setup setup, DayState state) {
        int budget = setup.daySteps()[state.day()];
        int[][] actions = new int[state.agents().size()][];
        for (int i = 0; i < actions.length; i++) actions[i] = new int[]{-budget};
        return actions;
    }

    /**
     * Kept opt-in while the low-latency generic-map holdout is evaluated. The normal five-second
     * competition path remains the established 800ms reserve. At a one-second window the former
     * reserve left only 200ms to construct a route, despite observed local HTTP acknowledgements
     * being well below 350ms. Three-match P16 and P32 holdouts cleared their exact long-day
     * shapes; those promotions are deliberately narrower than the remaining opt-in generic
     * trial. We do not touch 200/500ms windows: there is not a reliable enough margin for
     * deeper search.
     */
    private static long networkReserveMillis(Model.Setup setup, long responseMs) {
        boolean tightResponseTrial = Boolean.parseBoolean(
                System.getenv().getOrDefault("PROCON_TIGHT_RESPONSE_PLANNING", "false"));
        boolean promotedP16LongDay = setup.map().width() == 16 && setup.map().height() == 16
                && setup.agentCount() == 6 && setup.dayCount() == 5 && setup.fuelLimit() == 240
                && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 80);
        boolean promotedP32LongDay = setup.map().width() == 32 && setup.map().height() == 32
                && setup.agentCount() == 8 && setup.dayCount() == 5 && setup.fuelLimit() == 360
                && Arrays.stream(setup.daySteps()).allMatch(steps -> steps == 120);
        boolean largeEnough = Math.max(setup.map().width(), setup.map().height()) >= 16;
        if ((promotedP16LongDay || promotedP32LongDay || (tightResponseTrial && largeEnough))
                && responseMs >= 750 && responseMs <= 1_000) return 350;
        return 800;
    }

    private static String safe(String value) {
        if (value == null) return "";
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.substring(0, Math.min(240, sanitized.length()));
    }

    private static final class Options {
        String url = "https://procon.ptit.edu.vn";
        String match;
        String transport = "auto";
        AdaptivePlanner.RoleMode roleMode = AdaptivePlanner.RoleMode.AUTO;
        long responseMs = 5_000;
        Options(String[] args) {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--url" -> url = args[++i];
                    case "--match" -> match = args[++i];
                    case "--transport" -> transport = args[++i].toLowerCase();
                    case "--role-mode" -> roleMode = switch (args[++i].toLowerCase()) {
                        case "auto" -> AdaptivePlanner.RoleMode.AUTO;
                        case "five-one" -> AdaptivePlanner.RoleMode.FIVE_ONE;
                        default -> throw new IllegalArgumentException("Invalid role mode");
                    };
                    case "--response-ms" -> responseMs = Long.parseLong(args[++i]);
                    case "--help", "-h" -> { printHelp(); System.exit(0); }
                    default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
                }
            }
            if (match == null || match.isBlank()) throw new IllegalArgumentException("--match is required");
            if (!transport.equals("auto") && !transport.equals("http") && !transport.equals("ws")) throw new IllegalArgumentException("Invalid transport");
            if (responseMs < 200) throw new IllegalArgumentException("--response-ms must be >= 200");
        }
        private void printHelp() { System.out.println("Usage: PROCON_TOKEN=... java -jar procon-bot.jar --match ID [--transport auto|ws|http] [--role-mode auto|five-one] [--url URL] [--response-ms MS]"); }
    }
}

package vn.ptit.procon.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.protocol.JsonProtocol;
import vn.ptit.procon.simulation.ExactSimulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Offline parity harness for accepted actions captured by the practice-match utility. */
public final class CorpusReplayMain {
    private CorpusReplayMain() {}

    public static void main(String[] args) throws Exception {
        boolean verbose = Arrays.asList(args).contains("--verbose");
        String rootArgument = Arrays.stream(args).filter(arg -> !"--verbose".equals(arg)).findFirst().orElse(null);
        Path root = rootArgument == null
                ? Path.of(System.getProperty("user.home"), ".procon-autotune", "corpus")
                : Path.of(rootArgument);
        Summary summary = replay(root, verbose);
        System.out.printf("CORPUS_REPLAY matches=%d acceptedDays=%d compared=%d parity=%d staleSnapshots=%d invalid=%d%n",
                summary.matches, summary.acceptedDays, summary.compared, summary.parity,
                summary.staleSnapshots, summary.invalid);
        if (summary.invalid > 0 || summary.parity != summary.compared) System.exit(2);
    }

    public static Summary replay(Path root) throws IOException {
        return replay(root, false);
    }

    public static Summary replay(Path root, boolean verbose) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Corpus directory not found: " + root);
        List<Path> setups;
        try (Stream<Path> stream = Files.walk(root)) {
            setups = stream.filter(path -> path.getFileName().toString().equals("setup.json")).sorted().toList();
        }
        int matches = 0, acceptedDays = 0, compared = 0, parity = 0, staleSnapshots = 0, invalid = 0;
        ExactSimulator simulator = new ExactSimulator();
        for (Path setupFile : setups) {
            matches++;
            Model.Setup setup = JsonProtocol.setup(Files.readString(setupFile));
            Path matchDir = setupFile.getParent();
            for (int day = 0; day < setup.dayCount(); day++) {
                Path dayDir = matchDir.resolve("day-" + day);
                Path acceptedFile = dayDir.resolve("accepted.json");
                Path stateFile = dayDir.resolve("state.json");
                Path actionsFile = dayDir.resolve("actions.json");
                if (!Files.isRegularFile(acceptedFile) || !Files.isRegularFile(stateFile)
                        || !Files.isRegularFile(actionsFile) || !accepted(Files.readString(acceptedFile))) continue;
                acceptedDays++;
                Model.DayState state = JsonProtocol.state(Files.readString(stateFile));
                int[][] actions = actions(JsonProtocol.MAPPER.readTree(Files.readString(actionsFile)).path("actions"));
                try {
                    ExactSimulator.SimulationResult result = simulator.simulate(setup, state, actions);
                    Path nextStateFile = matchDir.resolve("day-" + (day + 1)).resolve("state.json");
                    if (!Files.isRegularFile(nextStateFile)) continue;
                    Model.DayState next = JsonProtocol.state(Files.readString(nextStateFile));
                    // The legacy collector occasionally polled before the server had replaced
                    // the previous day state.  A byte-for-byte identical agent snapshot after a
                    // non-WAIT accepted plan cannot be a valid transition, so it is a stale
                    // fixture rather than evidence against simulator parity.  Keep it visible
                    // in the report instead of silently counting it as a pass.
                    if (hasMove(actions) && sameStateAgents(state, next)) {
                        staleSnapshots++;
                        if (verbose) System.out.printf("CORPUS_STALE_SNAPSHOT match=%s day=%d%n",
                                matchDir.getFileName(), day);
                        continue;
                    }
                    compared++;
                    if (sameAgents(result, next)) parity++;
                    else {
                        invalid++;
                        if (verbose) printMismatch(matchDir.getFileName().toString(), day, result, next);
                    }
                } catch (RuntimeException exception) {
                    invalid++;
                    if (verbose) {
                        System.out.printf("CORPUS_INVALID match=%s day=%d reason=%s%n",
                                matchDir.getFileName(), day, exception.getClass().getSimpleName());
                    }
                }
            }
        }
        return new Summary(matches, acceptedDays, compared, parity, staleSnapshots, invalid);
    }

    private static int[][] actions(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("actions must be an array");
        int[][] result = new int[node.size()][];
        for (int i = 0; i < result.length; i++) result[i] = JsonProtocol.ints(node.get(i));
        return result;
    }

    private static boolean accepted(String body) throws IOException {
        return JsonProtocol.MAPPER.readTree(body).path("accepted").asBoolean(false);
    }

    private static boolean sameAgents(ExactSimulator.SimulationResult result, Model.DayState next) {
        if (result.positions().length != next.agents().size()) return false;
        for (int i = 0; i < result.positions().length; i++) {
            Model.AgentState agent = next.agents().get(i);
            if (result.positions()[i] != agent.position() || result.fuel()[i] != agent.fuel()) return false;
        }
        return true;
    }

    private static boolean sameStateAgents(Model.DayState left, Model.DayState right) {
        if (left.agents().size() != right.agents().size()) return false;
        for (int agent = 0; agent < left.agents().size(); agent++) {
            Model.AgentState expected = left.agents().get(agent), actual = right.agents().get(agent);
            if (expected.kind() != actual.kind() || expected.position() != actual.position()
                    || expected.fuel() != actual.fuel()) return false;
        }
        return true;
    }

    private static boolean hasMove(int[][] actions) {
        for (int[] agent : actions) for (int action : agent) if (action >= 0) return true;
        return false;
    }

    private static void printMismatch(String matchId, int day, ExactSimulator.SimulationResult result,
                                      Model.DayState next) {
        StringBuilder detail = new StringBuilder();
        for (int agent = 0; agent < result.positions().length; agent++) {
            Model.AgentState actual = next.agents().get(agent);
            if (result.positions()[agent] != actual.position() || result.fuel()[agent] != actual.fuel()) {
                if (!detail.isEmpty()) detail.append(';');
                detail.append(agent).append(':')
                        .append(result.positions()[agent]).append('/').append(result.fuel()[agent])
                        .append("!=").append(actual.position()).append('/').append(actual.fuel());
            }
        }
        System.out.printf("CORPUS_MISMATCH match=%s day=%d agents=%s%n", matchId, day, detail);
    }

    public record Summary(int matches, int acceptedDays, int compared, int parity, int staleSnapshots, int invalid) {}
}

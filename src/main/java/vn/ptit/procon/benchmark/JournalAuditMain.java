package vn.ptit.procon.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.protocol.JsonProtocol;
import vn.ptit.procon.simulation.ExactSimulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Audits a bot journal against the next server state without exposing credentials. */
public final class JournalAuditMain {
    private JournalAuditMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: JournalAuditMain <match-journal-dir>");
        Path root = Path.of(args[0]);
        Model.Setup setup = JsonProtocol.setup(Files.readString(root.resolve("setup.json")));
        ExactSimulator simulator = new ExactSimulator();
        int total = 0;
        for (int day = 0; day < setup.dayCount(); day++) {
            Path stateFile = root.resolve("day-" + day + "-state.json");
            Path planFile = root.resolve("day-" + day + "-plan.json");
            if (!Files.isRegularFile(stateFile) || !Files.isRegularFile(planFile)) continue;
            Model.DayState state = JsonProtocol.state(Files.readString(stateFile));
            JsonNode actionsNode = JsonProtocol.MAPPER.readTree(Files.readString(planFile)).path("actions");
            int[][] actions = new int[actionsNode.size()][];
            for (int i = 0; i < actions.length; i++) actions[i] = JsonProtocol.ints(actionsNode.get(i));
            ExactSimulator.SimulationResult result = simulator.simulate(setup, state, actions);
            total += result.portions();
            boolean parity = true;
            Path nextFile = root.resolve("day-" + (day + 1) + "-state.json");
            if (Files.isRegularFile(nextFile)) {
                Model.DayState next = JsonProtocol.state(Files.readString(nextFile));
                parity = sameAgents(result, next);
            }
            System.out.printf("JOURNAL_DAY day=%d portions=%d brands=%d cumulative=%d claims=%s nextStateParity=%s%n",
                    day, result.portions(), result.brands().size(), total,
                    Arrays.toString(result.claimsBySpot()), parity);
        }
    }

    private static boolean sameAgents(ExactSimulator.SimulationResult result, Model.DayState next) {
        if (result.positions().length != next.agents().size()) return false;
        for (int i = 0; i < result.positions().length; i++) {
            Model.AgentState agent = next.agents().get(i);
            if (result.positions()[i] != agent.position() || result.fuel()[i] != agent.fuel()) return false;
        }
        return true;
    }
}

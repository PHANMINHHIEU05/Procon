package vn.ptit.procon.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.ptit.procon.model.Model;
import vn.ptit.procon.protocol.JsonProtocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Non-secret runtime journal. It deliberately receives parsed objects, never credentials. */
final class MatchJournal {
    private final Path directory;
    private final ObjectMapper mapper = JsonProtocol.MAPPER;

    MatchJournal(String matchId) throws IOException {
        if (matchId == null || !matchId.matches("[A-Za-z0-9._-]+")) throw new IOException("Unsafe match id");
        directory = Path.of(System.getProperty("user.home"), ".procon-bot", "matches", matchId);
        Files.createDirectories(directory);
    }

    void setup(Model.Setup setup) throws IOException { write("setup.json", setup); }
    void state(Model.DayState state) throws IOException { write("day-" + state.day() + "-state.json", state); }
    void plan(Model.PlannedDay plan, Model.SubmissionAck ack) throws IOException {
        write("day-" + plan.day() + "-plan.json", plan);
        write("day-" + plan.day() + "-accepted.json", ack);
    }
    void result(Model.MatchResult result) throws IOException { write("result.json", result); }

    private void write(String name, Object value) throws IOException {
        Files.writeString(directory.resolve(name), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}

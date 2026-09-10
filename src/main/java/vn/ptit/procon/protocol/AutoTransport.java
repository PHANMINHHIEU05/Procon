package vn.ptit.procon.protocol;

import vn.ptit.procon.model.Model.DayState;
import vn.ptit.procon.model.Model.MatchResult;
import vn.ptit.procon.model.Model.Setup;
import vn.ptit.procon.model.Model.SubmissionAck;

import java.io.IOException;
import java.time.Duration;

public final class AutoTransport implements MatchTransport {
    private final String baseUrl;
    private final String matchId;
    private final String token;
    private final Duration timeout;
    private MatchTransport delegate;
    private Setup setup;
    private int lastDay = -1;

    public AutoTransport(String baseUrl, String matchId, String token, Duration timeout) {
        this.baseUrl = baseUrl; this.matchId = matchId; this.token = token; this.timeout = timeout;
    }

    private MatchTransport choose(long deadline) throws IOException, InterruptedException {
        if (delegate != null) return delegate;
        // The public practice server currently accepts HTTP submissions reliably but does not
        // consistently emit the documented WS event frames. Keep auto safe for competition;
        // enable experimental WS-first probing explicitly after server-side WS is verified.
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("PROCON_WS_FIRST", "false"))) {
            delegate = new HttpTransport(baseUrl, matchId, token, timeout);
            return delegate;
        }
        // Do not let a silent WS handshake consume the entire response/setup window;
        // HTTP must retain time to recover when the server exposes no WS frame.
        long wsDeadline = Math.min(deadline, System.nanoTime() + 5_000_000_000L);
        try {
            MatchTransport ws = new WebSocketTransport(baseUrl, matchId, token);
            setup = ws.awaitSetup(wsDeadline);
            delegate = ws;
            return delegate;
        } catch (Exception wsFailure) {
            System.err.printf("TRANSPORT_FALLBACK stage=setup from=websocket reason=%s%n",
                    wsFailure.getClass().getSimpleName());
            MatchTransport http = new HttpTransport(baseUrl, matchId, token, timeout);
            delegate = http;
            return delegate;
        }
    }

    @Override public Setup awaitSetup(long deadline) throws IOException, InterruptedException {
        if (setup != null) return setup;
        MatchTransport chosen = choose(deadline);
        // A successful WS probe already consumed its one setup frame in choose(). Asking the
        // same socket for setup a second time waits forever for a frame the protocol will never
        // resend, preventing the advertised HTTP fallback from ever being reached.
        if (setup != null) return setup;
        return chosen.awaitSetup(deadline);
    }
    @Override public SubmissionAck submitAssignment(int[] roles) throws IOException, InterruptedException {
        try { return delegate.submitAssignment(roles); }
        catch (IOException failure) { switchToHttp(); return delegate.submitAssignment(roles); }
    }
    @Override public DayState awaitNextDay(int requestedLastDay, long deadline) throws IOException, InterruptedException {
        long wsDeadline = delegate instanceof WebSocketTransport
                ? Math.min(deadline, System.nanoTime() + 1_000_000_000L) : deadline;
        try {
            DayState state = delegate.awaitNextDay(requestedLastDay, wsDeadline);
            lastDay = state.day();
            return state;
        } catch (IOException failure) {
            System.err.printf("TRANSPORT_FALLBACK stage=day from=websocket reason=%s%n",
                    failure.getClass().getSimpleName());
            switchToHttp();
            DayState state = delegate.awaitNextDay(requestedLastDay, deadline);
            lastDay = state.day();
            return state;
        }
    }
    @Override public SubmissionAck submitActions(int day, int[][] actions) throws IOException, InterruptedException {
        try { return delegate.submitActions(day, actions); }
        catch (IOException failure) {
            switchToHttp();
            // An action send can fail after the server accepted it. Verify state/result first.
            long probeDeadline = System.nanoTime() + 700_000_000L;
            if (setup != null && day >= setup.dayCount() - 1) {
                try { delegate.awaitResult(probeDeadline); return new SubmissionAck(true, "recovered-after-transport-failure", 0); }
                catch (IOException ignored) { /* not accepted yet; resend below */ }
            } else {
                try {
                    DayState next = delegate.awaitNextDay(day, probeDeadline);
                    lastDay = next.day();
                    return new SubmissionAck(true, "recovered-after-transport-failure", 0);
                } catch (IOException ignored) { /* not accepted yet; resend below */ }
            }
            return delegate.submitActions(day, actions);
        }
    }
    @Override public MatchResult awaitResult(long deadline) throws IOException, InterruptedException {
        try { return delegate.awaitResult(deadline); }
        catch (IOException failure) { switchToHttp(); return delegate.awaitResult(deadline); }
    }

    private void switchToHttp() throws IOException {
        if (delegate instanceof HttpTransport) return;
        if (delegate != null) delegate.close();
        delegate = new HttpTransport(baseUrl, matchId, token, timeout);
    }
    @Override public void close() { if (delegate != null) delegate.close(); }

}

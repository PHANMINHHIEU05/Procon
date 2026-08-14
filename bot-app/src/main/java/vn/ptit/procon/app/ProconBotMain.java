package vn.ptit.procon.app;

import vn.ptit.procon.runtime.MatchRuntime;
import vn.ptit.procon.runtime.MatchRuntimeResult;
import vn.ptit.procon.runtime.RuntimeConfig;

/** Runnable M4 entry point with configurable WAIT or BASELINE planning. */
public final class ProconBotMain {

    private ProconBotMain() {
    }

    public static void main(String[] args) {
        int exitCode = run();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run() {
        RuntimeConfig config = null;
        try {
            config = RuntimeConfig.fromEnvironment(System.getenv());
            System.out.println("CONFIG_LOADED baseUrl=" + config.baseUrl()
                    + " matchId=" + config.matchId() + " plannerMode=" + config.plannerMode()
                    + " PROCON_TOKEN=<set>");
            MatchRuntimeResult result = new MatchRuntime(config).run();
            System.out.println("FINAL matchId=" + config.matchId()
                    + " submittedDays=" + result.submittedDays()
                    + " rateLimits=" + result.rateLimitOccurrences()
                    + " result=" + redact(
                            concise(result.authoritativeResult().toString()), config.token()));
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("RUNTIME_INTERRUPTED");
            return 130;
        } catch (Exception exception) {
            String message = concise(exception.getMessage());
            if (config != null) {
                message = redact(message, config.token());
            }
            System.err.println("RUNTIME_FAILED type=" + exception.getClass().getSimpleName()
                    + " message=" + message);
            return 1;
        }
    }

    private static String concise(String value) {
        if (value == null) {
            return "unspecified";
        }
        String oneLine = value.replaceAll("[\\r\\n\\t]+", " ");
        return oneLine.length() <= 1_000 ? oneLine : oneLine.substring(0, 1_000) + "…";
    }

    private static String redact(String value, String token) {
        return value.replace(token, "<redacted>");
    }
}
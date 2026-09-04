package vn.ptit.procon.runtime;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import vn.ptit.procon.engine.TeamPlan;
import vn.ptit.procon.protocol.ActionEncoder;

/**
 * The mandated {@code V3_SHADOW_SUBMISSION_AUTHORITY} audit: proof that the bytes POSTed are the
 * production planner's bytes.
 *
 * <p>The proof is a re-derivation, not a restatement. {@link #of} takes the payload the HTTP client was
 * actually handed and independently re-encodes the production {@link TeamPlan} with a fresh
 * {@link ActionEncoder}; {@code submittedMatchesV2} is the comparison of those two wire forms. Nothing on
 * the shadow side is consulted, because nothing on the shadow side is allowed to matter.
 *
 * <p>{@code v2PhysicalSignature} and {@code submittedPhysicalSignature} are WIRE signatures, so they are
 * directly comparable. {@code v3PhysicalSignature} is the frozen-objective signature of V3's raw
 * candidate and is recorded for information only — a different planner's plan in a different alphabet.
 */
public record V3ShadowSubmissionAuthority(int day, String submittedPlanner, String v2PhysicalSignature,
        String submittedPhysicalSignature, String v3PhysicalSignature, boolean submittedMatchesV2) {

    /** The production authority label. Phase 2.7 adds no other value, and no V3 planner mode exists. */
    public static final String V2_R3 = "V2_R3";

    public V3ShadowSubmissionAuthority {
        Objects.requireNonNull(submittedPlanner, "Submitted planner must not be null");
        Objects.requireNonNull(v2PhysicalSignature, "V2 signature must not be null");
        Objects.requireNonNull(submittedPhysicalSignature, "Submitted signature must not be null");
        Objects.requireNonNull(v3PhysicalSignature, "V3 signature must not be null");
        if (day < 0) throw new IllegalArgumentException("Day must not be negative: " + day);
    }

    public static V3ShadowSubmissionAuthority of(int day, String submittedPlanner, TeamPlan productionPlan,
            int agentCount, List<List<Integer>> submittedPayload, String v3PhysicalSignature) {
        Objects.requireNonNull(productionPlan, "Production plan must not be null");
        Objects.requireNonNull(submittedPayload, "Submitted payload must not be null");
        String reEncoded = wireSignature(new ActionEncoder().encode(productionPlan, agentCount));
        String submitted = wireSignature(submittedPayload);
        return new V3ShadowSubmissionAuthority(day, submittedPlanner, reEncoded, submitted,
                v3PhysicalSignature, reEncoded.equals(submitted));
    }

    /**
     * The deterministic wire form of an encoded payload: per agent index, each command as {@code M<code>}
     * for a move or {@code W<steps>} for a wait, in submission order.
     */
    public static String wireSignature(List<List<Integer>> payload) {
        Objects.requireNonNull(payload, "Payload must not be null");
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < payload.size(); index++) {
            value.append(index).append(':').append(payload.get(index).stream()
                    .map(command -> command < 0 ? "W" + (-command) : "M" + command)
                    .collect(Collectors.joining(","))).append(';');
        }
        return value.toString();
    }

    @Override
    public String toString() {
        return "V3_SHADOW_SUBMISSION_AUTHORITY day=" + day + " submittedPlanner=" + submittedPlanner
                + " submittedMatchesV2=" + submittedMatchesV2;
    }
}

package vn.ptit.procon.runtime;

import java.util.Objects;
import vn.ptit.procon.domain.agent.AgentState;
import vn.ptit.procon.engine.DayState;

/**
 * The mandated {@code V3_SHADOW_STATE_SNAPSHOT_AUDIT}: proof that V3 read exactly the state V2 planned from.
 *
 * <p>{@link DayState} is a {@code final} class whose every field is {@code final} and defensively copied —
 * agents through {@code List.copyOf}, road traffic and spot stock through {@code Collections.unmodifiableMap}
 * over freshly built maps — and every nested type ({@code StaticMatchData}, {@code AgentState},
 * {@code ObservedOtherGroup}, {@code Position}) is a record that copies its own collections. The runtime
 * builds a NEW {@code DayState} for every {@code /state} response, so a later poll cannot reach backwards
 * into the instance the shadow task is holding. No extra snapshot copy is therefore needed; this audit
 * measures that claim instead of asserting it.
 *
 * <p>{@code v2StateFingerprint} is taken on the action loop's thread before V2/R3 plans;
 * {@code v3StateFingerprint} is taken on the shadow thread from the reference it was handed.
 */
public record V3ShadowStateSnapshotAudit(String matchId, int day, String stateFingerprint,
        String v2StateFingerprint, String v3StateFingerprint, boolean same) {

    public V3ShadowStateSnapshotAudit {
        Objects.requireNonNull(matchId, "Match ID must not be null");
        Objects.requireNonNull(stateFingerprint, "State fingerprint must not be null");
        Objects.requireNonNull(v2StateFingerprint, "V2 fingerprint must not be null");
        Objects.requireNonNull(v3StateFingerprint, "V3 fingerprint must not be null");
        if (day < 0) throw new IllegalArgumentException("Day must not be negative: " + day);
    }

    public static V3ShadowStateSnapshotAudit of(String matchId, int day, String v2Fingerprint,
            String v3Fingerprint) {
        return new V3ShadowStateSnapshotAudit(matchId, day, v2Fingerprint, v2Fingerprint, v3Fingerprint,
                v2Fingerprint.equals(v3Fingerprint));
    }

    /**
     * A deterministic fingerprint of everything V3 reads: the day, every agent's identity, kind, position
     * and fuel, the whole spot stock, the whole road traffic map and the observed opponent shape. All three
     * collections are already stored in a fixed order by {@link DayState}, so the same state always yields
     * the same string on any thread.
     */
    public static String fingerprint(DayState state) {
        Objects.requireNonNull(state, "Day state must not be null");
        StringBuilder value = new StringBuilder("d").append(state.day().value()).append("|a");
        for (AgentState agent : state.agents()) {
            value.append(agent.id().value()).append(':').append(agent.kind()).append('@')
                    .append(agent.position().value()).append(':').append(agent.fuel()).append(',');
        }
        value.append("|s");
        for (var entry : state.spotStock().entrySet()) {
            value.append(entry.getKey().value()).append('=').append(entry.getValue()).append(',');
        }
        value.append("|t");
        for (var entry : state.roadTraffic().entrySet()) {
            value.append(entry.getKey().value()).append('=').append(entry.getValue()).append(',');
        }
        value.append("|o");
        state.observedOthers().forEach(group -> {
            value.append(group.rawId()).append(':');
            group.agents().forEach(agent -> value.append(agent.position().value()).append('/')
                    .append(agent.rawKind()).append('/').append(agent.fuel()).append(' '));
            value.append(',');
        });
        value.append("|b").append(state.stepBudget());
        return String.format("%08x", value.toString().hashCode()) + ":" + value.length();
    }

    @Override
    public String toString() {
        return "V3_SHADOW_STATE_SNAPSHOT_AUDIT matchId=" + matchId + " day=" + day + " same=" + same;
    }
}

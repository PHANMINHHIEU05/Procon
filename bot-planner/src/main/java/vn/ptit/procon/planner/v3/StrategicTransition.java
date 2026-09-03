package vn.ptit.procon.planner.v3;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** One graph transition or optional chain macro in a route skeleton. */
public record StrategicTransition(Position from, Position to, Kind kind, String macroId) {
    public enum Kind { EDGE, CHAIN_MACRO, STOP }

    public StrategicTransition {
        Objects.requireNonNull(from, "Transition source must not be null");
        Objects.requireNonNull(to, "Transition target must not be null");
        Objects.requireNonNull(kind, "Transition kind must not be null");
        macroId = macroId == null ? "" : macroId;
        if (kind == Kind.CHAIN_MACRO && macroId.isBlank()) throw new IllegalArgumentException("Macro transition needs an id");
    }

    public static StrategicTransition edge(Position from, Position to) {
        return new StrategicTransition(from, to, Kind.EDGE, "");
    }

    public static StrategicTransition chainMacro(Position from, Position to, String macroId) {
        return new StrategicTransition(from, to, Kind.CHAIN_MACRO, macroId);
    }

    public static StrategicTransition stop(Position at) {
        return new StrategicTransition(at, at, Kind.STOP, "");
    }
}

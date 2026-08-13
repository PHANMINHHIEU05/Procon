package vn.ptit.procon.domain.agent;

/** Fixed agent roles and their official codes. */
public enum AgentKind {
    PATROL(0),
    REFUEL(1);

    private final int code;

    AgentKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static AgentKind fromCode(int code) {
        return switch (code) {
            case 0 -> PATROL;
            case 1 -> REFUEL;
            default -> throw new IllegalArgumentException("Unknown agent kind code: " + code);
        };
    }
}
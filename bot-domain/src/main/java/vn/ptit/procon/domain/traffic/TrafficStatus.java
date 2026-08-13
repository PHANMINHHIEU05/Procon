package vn.ptit.procon.domain.traffic;

/** Authoritative current-day road traffic statuses and their official codes. */
public enum TrafficStatus {
    CLEAR(0),
    CONGESTED(1),
    JAMMED(2);

    private final int code;

    TrafficStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TrafficStatus fromCode(int code) {
        return switch (code) {
            case 0 -> CLEAR;
            case 1 -> CONGESTED;
            case 2 -> JAMMED;
            default -> throw new IllegalArgumentException("Unknown traffic status code: " + code);
        };
    }
}
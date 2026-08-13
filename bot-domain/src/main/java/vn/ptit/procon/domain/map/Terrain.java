package vn.ptit.procon.domain.map;

/** Terrain types and their official server codes. */
public enum Terrain {
    PLAIN(0, true),
    ROAD(1, true),
    MOUNTAIN(2, true),
    POND(3, false);

    private final int code;
    private final boolean traversable;

    Terrain(int code, boolean traversable) {
        this.code = code;
        this.traversable = traversable;
    }

    public int code() {
        return code;
    }

    public boolean isTraversable() {
        return traversable;
    }

    public static Terrain fromCode(int code) {
        return switch (code) {
            case 0 -> PLAIN;
            case 1 -> ROAD;
            case 2 -> MOUNTAIN;
            case 3 -> POND;
            default -> throw new IllegalArgumentException("Unknown terrain code: " + code);
        };
    }
}
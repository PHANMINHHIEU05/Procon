package vn.ptit.procon.planner;

public record Deadline(long endNanos) {
    public static Deadline afterMillis(long millis) {
        return new Deadline(System.nanoTime() + millis * 1_000_000L);
    }
    public boolean expired() { return System.nanoTime() >= endNanos; }
    public long remainingMillis() { return Math.max(0, (endNanos - System.nanoTime()) / 1_000_000L); }
}

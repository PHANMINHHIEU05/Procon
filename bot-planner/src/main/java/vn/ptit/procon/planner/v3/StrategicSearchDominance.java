package vn.ptit.procon.planner.v3;

/** Conservative dominance helper. It never compares incompatible positions or stock. */
public final class StrategicSearchDominance {
    private StrategicSearchDominance() { }
    public static boolean dominates(StrategicSearchState left, StrategicSearchState right) {
        if (!left.remainingStock().equals(right.remainingStock()) || !left.supportState().equals(right.supportState())) return false;
        if (left.patrols().size() != right.patrols().size()) return false;
        boolean strict = left.collectionEstimate() > right.collectionEstimate() || left.brands().size() > right.brands().size();
        for (int i = 0; i < left.patrols().size(); i++) {
            var a = left.patrols().get(i); var b = right.patrols().get(i);
            if (!a.patrolId().equals(b.patrolId()) || !a.position().equals(b.position()) || a.stopped() != b.stopped()
                    || a.elapsed() > b.elapsed() || a.fuel() < b.fuel()) return false;
            strict |= a.elapsed() < b.elapsed() || a.fuel() > b.fuel();
        }
        return left.brands().containsAll(right.brands()) && strict;
    }
}

package vn.ptit.procon.planner.v2;

/** Exact partial-order check. Callers must first require equal {@link JointTeamSearchState#dominanceKey()}. */
final class JointStateDominance {
    private JointStateDominance() {
    }

    static boolean dominates(JointTeamSearchState left, JointTeamSearchState right) {
        if (!left.dominanceKey().equals(right.dominanceKey())
                || left.timeline().successfulCollections() < right.timeline().successfulCollections()
                || !left.timeline().brands().containsAll(right.timeline().brands())
                || left.zeroGainCommittedLegs() > right.zeroGainCommittedLegs()) {
            return false;
        }
        for (var entry : left.patrols().entrySet()) {
            JointTeamSearchState.PatrolPrefix other = right.patrols().get(entry.getKey());
            if (other == null
                    || entry.getValue().elapsedSteps() > other.elapsedSteps()
                    || entry.getValue().remainingFuel() < other.remainingFuel()) {
                return false;
            }
        }
        return true;
    }
}

package vn.ptit.procon.model;

import java.util.Comparator;

/** Official ranking order, exposed for result parsing and benchmark scorecards. */
public final class OfficialScoreComparator implements Comparator<Model.Standing> {
    public static final OfficialScoreComparator INSTANCE = new OfficialScoreComparator();

    private OfficialScoreComparator() {}

    @Override public int compare(Model.Standing left, Model.Standing right) {
        return score(left).compareTo(score(right));
    }

    public static Model.OfficialScore score(Model.Standing standing) {
        return new Model.OfficialScore(standing.globalTypes(), standing.dailyTypesSum(),
                standing.portions(), standing.responseMillis());
    }
}

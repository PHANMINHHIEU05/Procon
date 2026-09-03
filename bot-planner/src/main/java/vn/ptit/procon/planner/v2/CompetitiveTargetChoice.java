package vn.ptit.procon.planner.v2;

import java.util.Objects;
import vn.ptit.procon.domain.map.Position;

/** Selected target plus its role and all race fields needed for audit output. */
public record CompetitiveTargetChoice(
        Position position,
        CompetitiveSelectionRole selectionRole,
        CompetitiveOpportunity opportunity) {
    public CompetitiveTargetChoice {
        Objects.requireNonNull(position, "Choice position must not be null");
        Objects.requireNonNull(selectionRole, "Selection role must not be null");
        Objects.requireNonNull(opportunity, "Opportunity must not be null");
    }
}

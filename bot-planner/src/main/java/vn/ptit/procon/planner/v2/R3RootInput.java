package vn.ptit.procon.planner.v2;

import java.util.Objects;
import java.util.Optional;

/** R3 root plus the provenance deliberately kept outside the strategic state key. */
record R3RootInput(Optional<JointTeamSearchState.RefuelRootSchedule> schedule,
        R3RootFamilyProvenance provenance) {
    R3RootInput {
        schedule = Objects.requireNonNull(schedule, "R3 root schedule must not be null");
        provenance = Objects.requireNonNull(provenance, "R3 root provenance must not be null");
        if (schedule.isPresent() != (provenance.supportServiceCount() > 0)) {
            throw new IllegalArgumentException("R3 root schedule and provenance must agree");
        }
    }
}

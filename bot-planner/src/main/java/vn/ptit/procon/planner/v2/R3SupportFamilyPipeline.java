package vn.ptit.procon.planner.v2;

/** Immutable accounting for where each bounded support family was lost or retained. */
public record R3SupportFamilyPipeline(
        int generated1Service, int generated2Service, int generated3Service,
        int prunedBeforeValidation1, int prunedBeforeValidation2, int prunedBeforeValidation3,
        int validated1, int validated2, int validated3,
        int valid1, int valid2, int valid3,
        int retained1, int retained2, int retained3,
        int initialRoots1, int initialRoots2, int initialRoots3,
        int familyReservationSlotsUsed, int globalFillSlotsUsed,
        int totalSupportSkeletonsRetained) {

    public R3SupportFamilyPipeline {
        if (totalSupportSkeletonsRetained < 0 || totalSupportSkeletonsRetained > 12
                || familyReservationSlotsUsed < 0 || globalFillSlotsUsed < 0) {
            throw new IllegalArgumentException("Invalid R3 support-family accounting");
        }
    }

    static R3SupportFamilyPipeline empty() {
        return new R3SupportFamilyPipeline(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}

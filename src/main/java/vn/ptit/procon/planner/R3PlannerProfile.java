package vn.ptit.procon.planner;

/** Search and deadline budget for one production profile. */
public record R3PlannerProfile(
        String id,
        int targetCap,
        int routeBeam,
        int routesPerPatrol,
        int teamBeam,
        int plannerBudgetMs,
        int hardSubmitMs,
        int paretoFirstHopExpansions) {
    public R3PlannerProfile {
        if (targetCap <= 0 || routeBeam <= 0 || routesPerPatrol <= 0 || teamBeam <= 0
                || paretoFirstHopExpansions < 0) {
            throw new IllegalArgumentException("Search limits must be positive");
        }
    }
}

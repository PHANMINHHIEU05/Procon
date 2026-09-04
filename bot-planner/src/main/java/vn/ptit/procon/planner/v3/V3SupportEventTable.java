package vn.ptit.procon.planner.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vn.ptit.procon.planner.v2.R3SupportRootExport;

/**
 * The MANDATORY SUPPORT EVENT TABLE, and with it the PART 26 separation of planned from incidental
 * refuelling.
 *
 * <p>R3's service metadata is a record of what R3 intended, not an exhaustive record of what its tanker
 * route causes. A settled refuelling is therefore labelled {@code PLANNED_SERVICE} only when the selected
 * root's own metadata predicted that exact (PATROL, cell, step); everything else keeps the category the
 * chronology derived from observable geometry alone. A tanker route that refuels a PATROL nobody planned
 * for still shows up here — as an incidental refill, which is what it is.
 */
public record V3SupportEventTable(List<Row> rows, int plannedServiceCount, int arrivalRefillCount,
        int incidentalRefillCount, int stationaryRefuelCount, int plannedServicesPredicted,
        int plannedServicesObserved) {

    /** Source categories used by the table; the first three are the ones PART 26 mandates by name. */
    public static final String PLANNED_SERVICE = "PLANNED_SERVICE";
    public static final String ARRIVAL_REFILL = "ARRIVAL_REFILL";
    public static final String INCIDENTAL_SPATIAL_REFILL = "INCIDENTAL_SPATIAL_REFILL";
    public static final String STATIONARY_REFUEL = "STATIONARY_REFUEL";

    public V3SupportEventTable {
        rows = List.copyOf(Objects.requireNonNull(rows, "Rows must not be null"));
    }

    /** One authoritative refuelling, with the tanker state that caused it. */
    public record Row(int step, int refuelPosition, String refuelMotion, int patrolId, int patrolPosition,
            int fuelBefore, int fuelAfter, String source) {
        public Row { Objects.requireNonNull(refuelMotion); Objects.requireNonNull(source); }

        @Override
        public String toString() {
            return "t" + step + " REFUEL@" + refuelPosition + "/" + refuelMotion + " PATROL" + patrolId
                    + "@" + patrolPosition + " fuel " + fuelBefore + "->" + fuelAfter + " " + source;
        }
    }

    public static V3SupportEventTable of(V3SupportRootContext root,
            StrategicChronologyReplay.ChronologyResult replay) {
        Objects.requireNonNull(root, "Support root must not be null");
        Objects.requireNonNull(replay, "Chronology must not be null");
        List<Row> rows = new ArrayList<>();
        int planned = 0, arrival = 0, incidental = 0, stationary = 0;
        for (StrategicChronologyReplay.RefuelEvent event : replay.refuelEvents()) {
            CachedSupportTrajectory.Occupancy occupancy = root.trajectory().at(event.step());
            String source = predicted(root, event) ? PLANNED_SERVICE : switch (event.source()) {
                case ARRIVAL_REFILL -> ARRIVAL_REFILL;
                case INCIDENTAL_SPATIAL_REFILL -> INCIDENTAL_SPATIAL_REFILL;
                case STATIONARY_REFUEL -> STATIONARY_REFUEL;
            };
            switch (source) {
                case PLANNED_SERVICE -> planned++;
                case ARRIVAL_REFILL -> arrival++;
                case INCIDENTAL_SPATIAL_REFILL -> incidental++;
                default -> stationary++;
            }
            rows.add(new Row(event.step(), occupancy == null ? -1 : occupancy.position().value(),
                    occupancy == null ? "NONE" : occupancy.motion().name(), event.patrolId().value(),
                    event.position().value(), event.before(), event.after(), source));
        }
        return new V3SupportEventTable(rows, planned, arrival, incidental, stationary,
                root.plannedServices().size(), planned);
    }

    private static boolean predicted(V3SupportRootContext root, StrategicChronologyReplay.RefuelEvent event) {
        for (R3SupportRootExport.PlannedService service : root.plannedServices()) {
            if (service.patrolId() == event.patrolId().value() && service.serviceStep() == event.step()
                    && service.position().equals(event.position())) {
                return true;
            }
        }
        return false;
    }

    /** PART 26: how much of the settled refuelling the R3 metadata did NOT predict. */
    public int unplannedRefuelCount() { return arrivalRefillCount + incidentalRefillCount + stationaryRefuelCount; }

    @Override
    public String toString() {
        return "events=" + rows.size() + " planned=" + plannedServiceCount + "/" + plannedServicesPredicted
                + " arrival=" + arrivalRefillCount + " incidental=" + incidentalRefillCount
                + " stationary=" + stationaryRefuelCount;
    }
}

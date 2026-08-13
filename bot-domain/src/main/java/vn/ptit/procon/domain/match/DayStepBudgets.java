package vn.ptit.procon.domain.match;

import java.util.Objects;

/** Immutable per-day step budgets indexed from day zero. */
public final class DayStepBudgets {

    private final int[] stepsByDay;

    public DayStepBudgets(int[] stepsByDay) {
        Objects.requireNonNull(stepsByDay, "Day step budgets must not be null");
        if (stepsByDay.length == 0) {
            throw new IllegalArgumentException("Day step budgets must not be empty");
        }

        int[] copiedSteps = stepsByDay.clone();
        for (int day = 0; day < copiedSteps.length; day++) {
            if (copiedSteps[day] <= 0) {
                throw new IllegalArgumentException(
                        "Step budget must be positive for day " + day + ": " + copiedSteps[day]);
            }
        }
        this.stepsByDay = copiedSteps;
    }

    public int dayCount() {
        return stepsByDay.length;
    }

    public int stepsFor(DayIndex day) {
        Objects.requireNonNull(day, "Day index must not be null");
        if (day.value() >= stepsByDay.length) {
            throw new IllegalArgumentException(
                    "Day index must be between 0 and " + (stepsByDay.length - 1) + ": " + day.value());
        }
        return stepsByDay[day.value()];
    }
}
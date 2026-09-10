package vn.ptit.procon.planner;

import java.util.HashSet;
import java.util.Set;

public final class MatchMemory {
    private final Set<String> globalBrands = new HashSet<>();
    private int dailyTypesSum;
    private int portions;
    private int lastDay = -1;

    public Set<String> globalBrands() { return Set.copyOf(globalBrands); }
    public int dailyTypesSum() { return dailyTypesSum; }
    public int portions() { return portions; }
    public int lastDay() { return lastDay; }

    public void observe(int day, Set<String> dayBrands, int dayPortions) {
        if (day <= lastDay) return;
        globalBrands.addAll(dayBrands);
        dailyTypesSum += dayBrands.size();
        portions += dayPortions;
        lastDay = day;
    }
}

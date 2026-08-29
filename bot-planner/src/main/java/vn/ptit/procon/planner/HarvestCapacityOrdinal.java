package vn.ptit.procon.planner;

/** Weakest-first structural capacity tuple; larger brand/spot values are better. */
public record HarvestCapacityOrdinal(int distinctBrands, int distinctSpots)
        implements Comparable<HarvestCapacityOrdinal> {

    public HarvestCapacityOrdinal {
        if (distinctBrands < 0 || distinctSpots < 0 || distinctBrands > distinctSpots) {
            throw new IllegalArgumentException("Harvest-capacity ordinal must be structurally valid");
        }
    }

    @Override
    public int compareTo(HarvestCapacityOrdinal other) {
        int brands = Integer.compare(distinctBrands, other.distinctBrands);
        return brands != 0 ? brands : Integer.compare(distinctSpots, other.distinctSpots);
    }
}
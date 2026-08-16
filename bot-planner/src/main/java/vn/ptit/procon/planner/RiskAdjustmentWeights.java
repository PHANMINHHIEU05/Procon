package vn.ptit.procon.planner;

/** Immutable integer heuristic weights for contention-adjusted collection value. */
public record RiskAdjustmentWeights(
        int safeWeight,
        int tiedWeight,
        int riskWeight,
        int unobservedWeight) {

    public static final int DEFAULT_SAFE_WEIGHT = 4;
    public static final int DEFAULT_TIED_WEIGHT = 2;
    public static final int DEFAULT_RISK_WEIGHT = 1;
    public static final int DEFAULT_UNOBSERVED_WEIGHT = 4;

    public RiskAdjustmentWeights {
        if (safeWeight < 0 || tiedWeight < 0 || riskWeight < 0 || unobservedWeight < 0) {
            throw new IllegalArgumentException("Risk adjustment weights must be non-negative");
        }
        if (safeWeight == 0 && tiedWeight == 0 && riskWeight == 0 && unobservedWeight == 0) {
            throw new IllegalArgumentException("At least one risk adjustment weight must be positive");
        }
    }

    public static RiskAdjustmentWeights defaults() {
        return new RiskAdjustmentWeights(
                DEFAULT_SAFE_WEIGHT,
                DEFAULT_TIED_WEIGHT,
                DEFAULT_RISK_WEIGHT,
                DEFAULT_UNOBSERVED_WEIGHT);
    }

    public int score(ArrivalAttribution attribution) {
        return score(
                attribution.arrivalSafeProjected(),
                attribution.arrivalTiedProjected(),
                attribution.arrivalAtRiskProjected(),
                attribution.unobservedProjected());
    }

    public int score(int safe, int tied, int risk, int unobserved) {
        if (safe < 0 || tied < 0 || risk < 0 || unobserved < 0) {
            throw new IllegalArgumentException("Collection counts must be non-negative");
        }
        return Math.addExact(
                Math.addExact(Math.multiplyExact(safe, safeWeight), Math.multiplyExact(tied, tiedWeight)),
                Math.addExact(
                        Math.multiplyExact(risk, riskWeight),
                        Math.multiplyExact(unobserved, unobservedWeight)));
    }

    public int weightFor(ArrivalContentionClassification classification) {
        return switch (classification) {
            case ARRIVAL_SAFE -> safeWeight;
            case ARRIVAL_TIED -> tiedWeight;
            case ARRIVAL_AT_RISK -> riskWeight;
            case UNOBSERVED -> unobservedWeight;
        };
    }
}
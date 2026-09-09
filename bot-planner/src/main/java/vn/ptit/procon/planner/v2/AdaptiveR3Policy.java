package vn.ptit.procon.planner.v2;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Selects one deterministic profile from authoritative match shape only. */
public final class AdaptiveR3Policy {
    private final Map<R3MapTier, R3PlannerProfile> profiles;

    public AdaptiveR3Policy(Map<R3MapTier, R3PlannerProfile> profiles) {
        Objects.requireNonNull(profiles, "Profiles must not be null");
        EnumMap<R3MapTier, R3PlannerProfile> copy = new EnumMap<>(R3MapTier.class);
        copy.putAll(profiles);
        for (R3MapTier tier : R3MapTier.values()) {
            R3PlannerProfile profile = copy.get(tier);
            if (profile == null || profile.tier() != tier) {
                throw new IllegalArgumentException("Missing or mismatched profile for " + tier);
            }
        }
        this.profiles = Map.copyOf(copy);
    }

    public static AdaptiveR3Policy defaults() {
        EnumMap<R3MapTier, R3PlannerProfile> values = new EnumMap<>(R3MapTier.class);
        boolean adaptive = booleanSetting("procon.r3.adaptive", "PROCON_ADAPTIVE_R3", true);
        if (!adaptive) {
            for (R3MapTier tier : R3MapTier.values()) {
                values.put(tier, R3PlannerProfile.create("FIXED_BASELINE", tier, R3SearchPreset.BALANCED));
            }
        } else {
            values.put(R3MapTier.P08, R3PlannerProfile.create("P08_DEEP", R3MapTier.P08, R3SearchPreset.DEEP));
            values.put(R3MapTier.P12, R3PlannerProfile.create("P12_BALANCED", R3MapTier.P12,
                    R3SearchPreset.BALANCED));
            values.put(R3MapTier.P16, R3PlannerProfile.create("P16_BALANCED", R3MapTier.P16,
                    R3SearchPreset.BALANCED));
            values.put(R3MapTier.P24, R3PlannerProfile.create("P24_FAST", R3MapTier.P24, R3SearchPreset.FAST));
            values.put(R3MapTier.P32, R3PlannerProfile.create("P32_FAST", R3MapTier.P32, R3SearchPreset.FAST));
        }
        return new AdaptiveR3Policy(values);
    }

    public R3PlannerProfile select(MatchShape shape) {
        return profiles.get(Objects.requireNonNull(shape, "Match shape must not be null").tier())
                .withEnvironmentOverrides();
    }

    public AdaptiveR3Policy withRootFamilyAuditMode(R3RootFamilyAuditMode mode) {
        EnumMap<R3MapTier, R3PlannerProfile> values = new EnumMap<>(R3MapTier.class);
        profiles.forEach((tier, profile) -> values.put(tier, profile.withRootFamilyAuditMode(mode)));
        return new AdaptiveR3Policy(values);
    }

    public Map<R3MapTier, R3PlannerProfile> profiles() { return profiles; }

    private static boolean booleanSetting(String property, String environment, boolean fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }
}

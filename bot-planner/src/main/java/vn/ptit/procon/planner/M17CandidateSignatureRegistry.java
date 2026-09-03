package vn.ptit.procon.planner;

import java.util.LinkedHashSet;
import java.util.Set;

/** Deterministic complete-plan signature gate used before M17 coupled evaluation. */
final class M17CandidateSignatureRegistry {

    private final Set<String> signatures = new LinkedHashSet<>();

    boolean accept(String signature) {
        return signatures.add(signature);
    }

    int size() {
        return signatures.size();
    }
}

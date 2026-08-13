package vn.ptit.procon.engine;

import vn.ptit.procon.domain.map.Position;

/**
 * Isolates timing interpretations not fully specified by the supplied material.
 * A move retains its source through intermediate steps and arrives at the end
 * of its final duration step. End-of-step occupancy drives REFUEL and traffic.
 * PATROL agents are eligible to collect at their step-zero starting position.
 */
final class SimulationSemantics {

    private SimulationSemantics() {
    }

    static Position movePositionAfterStep(
            Position source, Position destination, int remainingDurationAfterStep) {
        return remainingDurationAfterStep == 0 ? destination : source;
    }

    static boolean collectAtStartOfDay() {
        return true;
    }
}
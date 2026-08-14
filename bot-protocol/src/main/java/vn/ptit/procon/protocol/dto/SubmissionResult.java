package vn.ptit.procon.protocol.dto;

/** Immutable protocol-local assignment/actions response and its HTTP status. */
public record SubmissionResult(
        int httpStatus,
        String type,
        boolean valid,
        String reason,
        Integer day,
        String matchId,
        String protocolVersion,
        Long responseMs,
        String submissionId) {

    public String diagnosticReason() {
        if (reason == null) {
            return "<missing>";
        }
        return reason.isEmpty() ? "<empty>" : reason;
    }

    public String diagnosticType() {
        return type == null || type.isEmpty() ? "<missing>" : type;
    }

    public String diagnosticDay() {
        return day == null ? "<missing>" : day.toString();
    }
}
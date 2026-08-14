package vn.ptit.procon.protocol;

/** Identifies an authoritative JSON value that cannot be mapped to the domain. */
public final class ProtocolMappingException extends IllegalArgumentException {

    public ProtocolMappingException(String endpoint, String path, String expected, Object actual) {
        super("Unexpected JSON at " + endpoint + " " + path + ": expected " + expected
                + " but got " + describe(actual));
    }

    public ProtocolMappingException(String endpoint, String path, String message, Throwable cause) {
        super("Invalid JSON at " + endpoint + " " + path + ": " + message, cause);
    }

    private static String describe(Object actual) {
        if (actual == null) {
            return "missing/null";
        }
        return actual.getClass().getSimpleName() + " (" + actual + ")";
    }
}
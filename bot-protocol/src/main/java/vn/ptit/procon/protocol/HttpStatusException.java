package vn.ptit.procon.protocol;

/** Sanitized non-success HTTP response; request headers are never retained. */
public final class HttpStatusException extends RuntimeException {

    private final String endpoint;
    private final int statusCode;
    private final String sanitizedBody;

    public HttpStatusException(String endpoint, int statusCode, String sanitizedBody) {
        super(endpoint + " returned HTTP " + statusCode
                + (sanitizedBody.isBlank() ? "" : ": " + sanitizedBody));
        this.endpoint = endpoint;
        this.statusCode = statusCode;
        this.sanitizedBody = sanitizedBody;
    }

    public String endpoint() {
        return endpoint;
    }

    public int statusCode() {
        return statusCode;
    }

    public String sanitizedBody() {
        return sanitizedBody;
    }
}
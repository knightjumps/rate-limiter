import java.time.Instant;

public final class RateLimiterResponse {
    private final Status status;
    private final Instant retryAfter;

    private RateLimiterResponse(Status status, Instant retryAfter) {
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public static RateLimiterResponse allowed() {
        return new RateLimiterResponse(Status.ALLOWED, null);
    }

    public static RateLimiterResponse denied(Instant retryAfter) {
        return new RateLimiterResponse(Status.DENIED, retryAfter);
    }

    public Status getStatus() {
        return status;
    }

    // The earliest instant when another request can be tried; null when allowed.
    public Instant getRetryAfter() {
        return retryAfter;
    }

    @Override
    public String toString() {
        return status == Status.ALLOWED ? "ALLOWED" : "DENIED (retry after " + retryAfter + ")";
    }
}

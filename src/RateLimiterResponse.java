import java.time.Instant;

public final class RateLimiterResponse {
    private final Status status;
    private final Instant retryAfter;
    private final Instant scheduledAt;

    private RateLimiterResponse(Status status, Instant retryAfter, Instant scheduledAt) {
        this.status = status;
        this.retryAfter = retryAfter;
        this.scheduledAt = scheduledAt;
    }

    public static RateLimiterResponse allowed() {
        return new RateLimiterResponse(Status.ALLOWED, null, null);
    }

    public static RateLimiterResponse allowedAt(Instant scheduledAt) {
        return new RateLimiterResponse(Status.ALLOWED, null, scheduledAt);
    }

    public static RateLimiterResponse denied(Instant retryAfter) {
        return new RateLimiterResponse(Status.DENIED, retryAfter, null);
    }

    public Status getStatus() {
        return status;
    }

    // The earliest instant when another request can be tried; null when allowed.
    public Instant getRetryAfter() {
        return retryAfter;
    }

    // Non-null only for an accepted leaky-bucket request. Process it at this time.
    public Instant getScheduledAt() {
        return scheduledAt;
    }

    @Override
    public String toString() {
        if (status == Status.DENIED) {
            return "DENIED (retry after " + retryAfter + ")";
        }
        return scheduledAt == null ? "ALLOWED" : "ALLOWED (scheduled at " + scheduledAt + ")";
    }
}

import java.time.Duration;
import java.util.Objects;

/**
 * For buckets, maxRequests is capacity and maxRequests/window is the refill or drain rate.
 */
public record RateLimitPolicy(RateLimitAlgorithm algorithm, int maxRequests, Duration window) {
    public RateLimitPolicy {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(window, "window");
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive and representable in nanoseconds");
        }
        try {
            long windowNanos = window.toNanos();
            if (windowNanos > Long.MAX_VALUE / 2) {
                throw new IllegalArgumentException("window is too large");
            }
            if (algorithm == RateLimitAlgorithm.LEAKY_BUCKET && maxRequests > windowNanos) {
                throw new IllegalArgumentException("leaky-bucket rate exceeds nanosecond resolution");
            }
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("window is too large", tooLarge);
        }
    }
}

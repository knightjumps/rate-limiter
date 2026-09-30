import java.time.Instant;

/**
 * Two adjacent counters with a linearly weighted estimate of the previous window.
 */
final class SlidingWindowCounterStrategy implements RateLimitStrategy {
    private static final class Counters implements State {
        Instant start;
        int previous;
        int current;

        Counters(Instant start) {
            this.start = start;
        }
    }

    @Override
    public State newState(Instant now, RateLimitPolicy policy) {
        return new Counters(now);
    }

    @Override
    public RateLimiterResponse check(State state, Instant now, RateLimitPolicy policy) {
        Counters counters = (Counters) state;
        long windowNanos = policy.window().toNanos();
        long elapsed = RateMath.elapsedNanos(counters.start, now);
        if (elapsed >= windowNanos) {
            long windowsPassed = elapsed / windowNanos;
            counters.previous = windowsPassed == 1 ? counters.current : 0;
            counters.current = 0;
            counters.start = counters.start.plusNanos(windowsPassed * windowNanos);
            elapsed %= windowNanos;
        }

        if (estimatedCount(counters, elapsed, windowNanos) + 1.0 <= policy.maxRequests()) {
            counters.current++;
            return RateLimiterResponse.allowed();
        }

        // With no more arrivals the estimate only falls. Find the first eligible nanosecond.
        long low = 1;
        long high = 2 * windowNanos - elapsed;
        while (low < high) {
            long middle = low + (high - low) / 2;
            if (estimatedCount(counters, elapsed + middle, windowNanos) + 1.0
                    <= policy.maxRequests()) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return RateLimiterResponse.denied(now.plusNanos(low));
    }

    private static double estimatedCount(Counters counters, long elapsed, long windowNanos) {
        if (elapsed < windowNanos) {
            return counters.current + counters.previous
                    * (1.0 - (double) elapsed / windowNanos);
        }
        if (elapsed < 2 * windowNanos) {
            return counters.current * (1.0 - (double) (elapsed - windowNanos) / windowNanos);
        }
        return 0.0;
    }
}

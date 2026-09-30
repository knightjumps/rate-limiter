import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded queue of processing slots. The caller must process accepted work at scheduledAt.
 */
final class LeakyBucketStrategy implements RateLimitStrategy {
    private static final class Bucket implements State {
        final Deque<Instant> pending = new ArrayDeque<>();
    }

    @Override
    public State newState(Instant now, RateLimitPolicy policy) {
        return new Bucket();
    }

    @Override
    public RateLimiterResponse check(State state, Instant now, RateLimitPolicy policy) {
        Bucket bucket = (Bucket) state;
        while (!bucket.pending.isEmpty() && !bucket.pending.peekFirst().isAfter(now)) {
            bucket.pending.removeFirst();
        }
        if (bucket.pending.size() >= policy.maxRequests()) {
            return RateLimiterResponse.denied(bucket.pending.peekFirst());
        }

        long intervalNanos = Math.max(1L,
                (policy.window().toNanos() + policy.maxRequests() - 1L) / policy.maxRequests());
        Instant base = bucket.pending.isEmpty() ? now : bucket.pending.peekLast();
        Instant scheduledAt = base.plusNanos(intervalNanos);
        bucket.pending.addLast(scheduledAt);
        return RateLimiterResponse.allowedAt(scheduledAt);
    }
}

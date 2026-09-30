import java.time.Instant;

final class TokenBucketStrategy implements RateLimitStrategy {
    private static final class Bucket implements State {
        double tokens;
        Instant lastRefill;

        Bucket(int capacity, Instant now) {
            tokens = capacity;
            lastRefill = now;
        }
    }

    @Override
    public State newState(Instant now, RateLimitPolicy policy) {
        return new Bucket(policy.maxRequests(), now);
    }

    @Override
    public RateLimiterResponse check(State state, Instant now, RateLimitPolicy policy) {
        Bucket bucket = (Bucket) state;
        double rate = (double) policy.maxRequests() / policy.window().toNanos();
        bucket.tokens = Math.min(policy.maxRequests(),
                bucket.tokens + RateMath.elapsedNanos(bucket.lastRefill, now) * rate);
        bucket.lastRefill = now;
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return RateLimiterResponse.allowed();
        }
        return RateLimiterResponse.denied(RateMath.afterNanos(now, (1.0 - bucket.tokens) / rate));
    }
}

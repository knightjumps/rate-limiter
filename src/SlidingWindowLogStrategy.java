import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Exact limit over any rolling window. Stores one timestamp per allowed request.
 */
final class SlidingWindowLogStrategy implements RateLimitStrategy {
    private static final class Log implements State {
        final Deque<Instant> allowed = new ArrayDeque<>();
    }

    @Override
    public State newState(Instant now, RateLimitPolicy policy) {
        return new Log();
    }

    @Override
    public RateLimiterResponse check(State state, Instant now, RateLimitPolicy policy) {
        Log log = (Log) state;
        Instant cutoff = now.minus(policy.window());
        while (!log.allowed.isEmpty() && !log.allowed.peekFirst().isAfter(cutoff)) {
            log.allowed.removeFirst();
        }
        if (log.allowed.size() >= policy.maxRequests()) {
            return RateLimiterResponse.denied(log.allowed.peekFirst().plus(policy.window()));
        }
        log.allowed.addLast(now);
        return RateLimiterResponse.allowed();
    }
}

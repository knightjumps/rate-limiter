import java.time.Instant;

interface RateLimitStrategy {
    interface State {
    }

    State newState(Instant now, RateLimitPolicy policy);

    RateLimiterResponse check(State state, Instant now, RateLimitPolicy policy);
}

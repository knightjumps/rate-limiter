import java.time.Instant;

final class FixedWindowStrategy implements RateLimitStrategy {
    private static final class Window implements State {
        int count;
        Instant start;

        Window(Instant start) {
            this.start = start;
        }
    }

    @Override
    public State newState(Instant now, RateLimitPolicy policy) {
        return new Window(now);
    }

    @Override
    public RateLimiterResponse check(State state, Instant now, RateLimitPolicy policy) {
        Window window = (Window) state;
        if (!now.isBefore(window.start.plus(policy.window()))) {
            window.start = now;
            window.count = 0;
        }
        if (window.count >= policy.maxRequests()) {
            return RateLimiterResponse.denied(window.start.plus(policy.window()));
        }
        window.count++;
        return RateLimiterResponse.allowed();
    }
}

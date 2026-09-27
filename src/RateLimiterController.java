import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One window per client, starting with that client's first allowed request.
 */
public final class RateLimiterController {
    private final ConcurrentHashMap<String, RequestTracking> requests = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile int allowedRequestsInWindow;
    private volatile Duration window;

    public RateLimiterController(int allowedRequestsInWindow, Duration window) {
        this(allowedRequestsInWindow, window, Clock.systemUTC());
    }

    public RateLimiterController(int allowedRequestsInWindow, Duration window, Clock clock) {
        setAllowedRequestsInWindow(allowedRequestsInWindow);
        setWindow(window);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setAllowedRequestsInWindow(int newValue) {
        if (newValue <= 0) {
            throw new IllegalArgumentException("allowedRequestsInWindow must be positive");
        }
        allowedRequestsInWindow = newValue;
    }

    public void setWindow(Duration newWindow) {
        Objects.requireNonNull(newWindow, "window");
        if (newWindow.isZero() || newWindow.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        window = newWindow;
    }

    public RateLimiterResponse canRequestBeAllowed(Request request) {
        Objects.requireNonNull(request, "request");
        int limit = allowedRequestsInWindow;
        Duration currentWindow = window;
        AtomicReference<RateLimiterResponse> decision = new AtomicReference<>();

        // compute serializes the entire decision for this key, including reset and increment.
        requests.compute(request.getClientKey(), (key, tracking) -> {
            Instant now = clock.instant();
            if (tracking == null || !now.isBefore(tracking.firstRequestTime.plus(currentWindow))) {
                tracking = new RequestTracking(0, now);
            }

            if (tracking.numberOfRequests >= limit) {
                decision.set(RateLimiterResponse.denied(tracking.firstRequestTime.plus(currentWindow)));
            } else {
                tracking.numberOfRequests++;
                decision.set(RateLimiterResponse.allowed());
            }
            return tracking;
        });

        return decision.get();
    }

    /**
     * Removes clients whose current windows have ended. Call periodically if keys are unbounded.
     */
    public void removeExpiredClients() {
        Instant now = clock.instant();
        Duration currentWindow = window;
        requests.forEach((key, ignored) -> requests.computeIfPresent(key, (sameKey, tracking) ->
                now.isBefore(tracking.firstRequestTime.plus(currentWindow)) ? tracking : null));
    }
}

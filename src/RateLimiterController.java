import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Selects an algorithm per client and serializes each client's decisions.
 */
public final class RateLimiterController {
    private static final RateLimitStrategy FIXED = new FixedWindowStrategy();
    private static final RateLimitStrategy TOKEN = new TokenBucketStrategy();
    private static final RateLimitStrategy LEAKY = new LeakyBucketStrategy();
    private static final RateLimitStrategy LOG = new SlidingWindowLogStrategy();
    private static final RateLimitStrategy COUNTER = new SlidingWindowCounterStrategy();

    private final ConcurrentHashMap<String, ClientEntry> clients = new ConcurrentHashMap<>();
    private final AtomicReference<RateLimitPolicy> defaultPolicy;
    private final Clock clock;

    private static final class ClientEntry {
        final RateLimitPolicy overridePolicy; // null means use the default
        final RateLimitPolicy appliedPolicy;
        final RateLimitStrategy.State state;
        final Instant lastSeen;

        ClientEntry(RateLimitPolicy overridePolicy, RateLimitPolicy appliedPolicy,
                    RateLimitStrategy.State state, Instant lastSeen) {
            this.overridePolicy = overridePolicy;
            this.appliedPolicy = appliedPolicy;
            this.state = state;
            this.lastSeen = lastSeen;
        }
    }

    public RateLimiterController(int allowedRequestsInWindow, Duration window) {
        this(new RateLimitPolicy(RateLimitAlgorithm.FIXED_WINDOW, allowedRequestsInWindow, window));
    }

    public RateLimiterController(int allowedRequestsInWindow, Duration window, Clock clock) {
        this(new RateLimitPolicy(RateLimitAlgorithm.FIXED_WINDOW, allowedRequestsInWindow, window), clock);
    }

    public RateLimiterController(RateLimitPolicy defaultPolicy) {
        this(defaultPolicy, Clock.systemUTC());
    }

    public RateLimiterController(RateLimitPolicy defaultPolicy, Clock clock) {
        this.defaultPolicy = new AtomicReference<>(Objects.requireNonNull(defaultPolicy, "defaultPolicy"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setDefaultPolicy(RateLimitPolicy policy) {
        defaultPolicy.set(Objects.requireNonNull(policy, "policy"));
    }

    // Preserve the original fixed-window configuration API for the default policy.
    public void setAllowedRequestsInWindow(int newValue) {
        defaultPolicy.updateAndGet(old -> new RateLimitPolicy(old.algorithm(), newValue, old.window()));
    }

    public void setWindow(Duration newWindow) {
        defaultPolicy.updateAndGet(old -> new RateLimitPolicy(old.algorithm(), old.maxRequests(), newWindow));
    }

    /**
     * A policy change starts a fresh bucket/window on this client's next request.
     */
    public void setClientPolicy(String clientKey, RateLimitPolicy policy) {
        validateClientKey(clientKey);
        Objects.requireNonNull(policy, "policy");
        clients.compute(clientKey, (key, old) -> new ClientEntry(policy, null, null, null));
    }

    /**
     * Clears an override; the next request starts fresh under the default policy.
     */
    public void removeClientPolicy(String clientKey) {
        validateClientKey(clientKey);
        clients.remove(clientKey);
    }

    public RateLimitPolicy getClientPolicy(String clientKey) {
        validateClientKey(clientKey);
        ClientEntry entry = clients.get(clientKey);
        return entry != null && entry.overridePolicy != null
                ? entry.overridePolicy : defaultPolicy.get();
    }

    public RateLimiterResponse canRequestBeAllowed(Request request) {
        Objects.requireNonNull(request, "request");
        AtomicReference<RateLimiterResponse> decision = new AtomicReference<>();

        clients.compute(request.getClientKey(), (key, old) -> {
            RateLimitPolicy policy = old != null && old.overridePolicy != null
                    ? old.overridePolicy : defaultPolicy.get();
            RateLimitStrategy strategy = strategyFor(policy.algorithm());
            Instant now = clock.instant();
            if (old != null && old.lastSeen != null && now.isBefore(old.lastSeen)) {
                now = old.lastSeen;
            }
            RateLimitStrategy.State state = old == null || old.state == null
                    || old.appliedPolicy.algorithm() != policy.algorithm()
                    ? strategy.newState(now, policy) : old.state;
            decision.set(strategy.check(state, now, policy));
            return new ClientEntry(old == null ? null : old.overridePolicy, policy, state, now);
        });

        return decision.get();
    }

    /**
     * Reclaim state after two idle windows; configured client overrides are retained.
     */
    public void removeExpiredClients() {
        Instant now = clock.instant();
        clients.forEach((key, ignored) -> clients.computeIfPresent(key, (sameKey, entry) -> {
            if (entry.state == null) {
                return entry.overridePolicy == null ? null : entry;
            }
            Instant expiry = entry.lastSeen.plus(entry.appliedPolicy.window()).plus(entry.appliedPolicy.window());
            if (now.isBefore(expiry)) {
                return entry;
            }
            return entry.overridePolicy == null ? null
                    : new ClientEntry(entry.overridePolicy, null, null, null);
        }));
    }

    private static RateLimitStrategy strategyFor(RateLimitAlgorithm algorithm) {
        return switch (algorithm) {
            case FIXED_WINDOW -> FIXED;
            case TOKEN_BUCKET -> TOKEN;
            case LEAKY_BUCKET -> LEAKY;
            case SLIDING_WINDOW_LOG -> LOG;
            case SLIDING_WINDOW_COUNTER -> COUNTER;
        };
    }

    private static void validateClientKey(String clientKey) {
        Objects.requireNonNull(clientKey, "clientKey");
        if (clientKey.isBlank()) {
            throw new IllegalArgumentException("clientKey must not be blank");
        }
    }
}

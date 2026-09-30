import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RateLimiterControllerTest {
    public static void main(String[] args) throws Exception {
        testLimitAndWindowBoundary();
        testClientIsolationAndConfiguration();
        testConcurrentRequests();
        testTokenBucket();
        testLeakyBucket();
        testSlidingWindowLog();
        testSlidingWindowCounter();
        testClientPolicySelectionAndReset();
        System.out.println("All rate limiter tests passed");
    }

    private static void testLimitAndWindowBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiterController limiter = new RateLimiterController(3, Duration.ofSeconds(10), clock);
        Request request = new Request("alice");

        for (int i = 0; i < 3; i++) {
            check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED,
                    "Requests within the limit should be allowed");
        }
        RateLimiterResponse denied = limiter.canRequestBeAllowed(request);
        check(denied.getStatus() == Status.DENIED, "The fourth request should be denied");
        check(Instant.parse("2026-01-01T00:00:10Z").equals(denied.getRetryAfter()),
                "Retry time should be the end of the window");

        clock.setInstant(Instant.parse("2026-01-01T00:00:10Z"));
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED,
                "A request exactly at the boundary should start a new window");
    }

    private static void testClientIsolationAndConfiguration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiterController limiter = new RateLimiterController(1, Duration.ofMinutes(1), clock);
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.ALLOWED,
                "Alice's first request should be allowed");
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.DENIED,
                "Alice's second request should be denied");
        check(limiter.canRequestBeAllowed(new Request("bob")).getStatus() == Status.ALLOWED,
                "Bob should have a separate limit");

        limiter.setAllowedRequestsInWindow(2);
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.ALLOWED,
                "Denied requests should not consume quota");
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.DENIED,
                "Alice should reach the updated limit");
    }

    private static void testConcurrentRequests() throws Exception {
        for (RateLimitAlgorithm algorithm : RateLimitAlgorithm.values()) {
            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            RateLimiterController limiter = new RateLimiterController(
                    new RateLimitPolicy(algorithm, 10, Duration.ofMinutes(1)), clock);
            ExecutorService workers = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Status>> results = new ArrayList<>();

            try {
                for (int i = 0; i < 100; i++) {
                    Callable<Status> task = () -> {
                        start.await();
                        return limiter.canRequestBeAllowed(new Request("alice")).getStatus();
                    };
                    results.add(workers.submit(task));
                }
                start.countDown();

                int allowed = 0;
                for (Future<Status> result : results) {
                    if (result.get() == Status.ALLOWED) {
                        allowed++;
                    }
                }
                check(allowed == 10, algorithm + " allowed " + allowed + " concurrent requests");
            } finally {
                workers.shutdownNow();
            }
        }
    }

    private static void testTokenBucket() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        RateLimiterController limiter = new RateLimiterController(
                new RateLimitPolicy(RateLimitAlgorithm.TOKEN_BUCKET, 2, Duration.ofSeconds(2)), clock);
        Request request = new Request("alice");
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED, "First token");
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED, "Second token");
        check(start.plusSeconds(1).equals(limiter.canRequestBeAllowed(request).getRetryAfter()),
                "A token refills after one second");
        clock.setInstant(start.plusMillis(500));
        RateLimiterResponse halfToken = limiter.canRequestBeAllowed(request);
        check(halfToken.getStatus() == Status.DENIED, "Half a token is insufficient");
        check(start.plusSeconds(1).equals(halfToken.getRetryAfter()),
                "Partial refill must be retained when calculating retry time");
        clock.setInstant(start.plusSeconds(1));
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED,
                "Fractional refill must accumulate");
    }

    private static void testLeakyBucket() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        RateLimiterController limiter = new RateLimiterController(
                new RateLimitPolicy(RateLimitAlgorithm.LEAKY_BUCKET, 2, Duration.ofSeconds(2)), clock);
        Request request = new Request("alice");
        check(start.plusSeconds(1).equals(limiter.canRequestBeAllowed(request).getScheduledAt()),
                "First request is scheduled after one interval");
        check(start.plusSeconds(2).equals(limiter.canRequestBeAllowed(request).getScheduledAt()),
                "Second request gets the next slot");
        check(start.plusSeconds(1).equals(limiter.canRequestBeAllowed(request).getRetryAfter()),
                "Full queue opens at the first scheduled slot");
        clock.setInstant(start.plusSeconds(1));
        check(start.plusSeconds(3).equals(limiter.canRequestBeAllowed(request).getScheduledAt()),
                "The next request takes a later slot");
    }

    private static void testSlidingWindowLog() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        RateLimiterController limiter = new RateLimiterController(
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW_LOG, 2, Duration.ofSeconds(10)), clock);
        Request request = new Request("alice");
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED, "First logged request");
        clock.setInstant(start.plusSeconds(9));
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED, "Second logged request");
        check(start.plusSeconds(10).equals(limiter.canRequestBeAllowed(request).getRetryAfter()),
                "Oldest request determines retry time");
        clock.setInstant(start.plusSeconds(10));
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED,
                "The first timestamp expires exactly at ten seconds");
        check(start.plusSeconds(19).equals(limiter.canRequestBeAllowed(request).getRetryAfter()),
                "The second timestamp remains in the rolling window");
    }

    private static void testSlidingWindowCounter() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        RateLimiterController limiter = new RateLimiterController(
                new RateLimitPolicy(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, 2, Duration.ofSeconds(10)), clock);
        Request request = new Request("alice");
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED, "First counter request");
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED, "Second counter request");
        clock.setInstant(start.plusSeconds(10));
        RateLimiterResponse denied = limiter.canRequestBeAllowed(request);
        check(denied.getStatus() == Status.DENIED,
                "Previous-window estimate is fully weighted at the boundary");
        check(start.plusSeconds(15).equals(denied.getRetryAfter()),
                "Retry time should reflect decay of the previous window");
        clock.setInstant(start.plusSeconds(15));
        check(limiter.canRequestBeAllowed(request).getStatus() == Status.ALLOWED,
                "Previous-window estimate decays halfway through the next window");
    }

    private static void testClientPolicySelectionAndReset() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        RateLimitPolicy fallback = new RateLimitPolicy(RateLimitAlgorithm.FIXED_WINDOW, 1,
                Duration.ofMinutes(1));
        RateLimitPolicy token = new RateLimitPolicy(RateLimitAlgorithm.TOKEN_BUCKET, 2,
                Duration.ofSeconds(2));
        RateLimiterController limiter = new RateLimiterController(fallback, clock);
        limiter.setClientPolicy("alice", token);
        check(limiter.getClientPolicy("alice").equals(token), "Alice's override is selected");
        check(limiter.getClientPolicy("bob").equals(fallback), "Bob uses the default policy");
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.ALLOWED,
                "Alice's token bucket allows its first request");
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.ALLOWED,
                "Alice's token bucket allows its second request");
        check(limiter.canRequestBeAllowed(new Request("bob")).getStatus() == Status.ALLOWED,
                "Bob's fixed window is independent");
        check(limiter.canRequestBeAllowed(new Request("bob")).getStatus() == Status.DENIED,
                "Bob's fixed-window limit applies");

        limiter.setClientPolicy("alice", fallback);
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.ALLOWED,
                "Changing policy resets Alice's state");
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.DENIED,
                "The new policy applies to Alice");
        limiter.removeClientPolicy("alice");
        check(limiter.getClientPolicy("alice").equals(fallback), "Alice returns to the default");
        check(limiter.canRequestBeAllowed(new Request("alice")).getStatus() == Status.ALLOWED,
                "Removing an override resets its state");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

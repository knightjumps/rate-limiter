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
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiterController limiter = new RateLimiterController(10, Duration.ofMinutes(1), clock);
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
            check(allowed == 10, "Exactly ten concurrent requests should be allowed, got " + allowed);
        } finally {
            workers.shutdownNow();
        }
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

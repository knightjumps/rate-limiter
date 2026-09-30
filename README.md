# Rate limiter LLD

This is an in-memory, per-client rate limiter with five selectable algorithms.
It uses plain Java and has no external dependencies.

```java
RateLimitPolicy fallback = new RateLimitPolicy(
        RateLimitAlgorithm.FIXED_WINDOW, 3, Duration.ofMinutes(1));
RateLimiterController limiter = new RateLimiterController(fallback);

limiter.setClientPolicy("alice", new RateLimitPolicy(
        RateLimitAlgorithm.TOKEN_BUCKET, 5, Duration.ofSeconds(5)));

RateLimiterResponse decision = limiter.canRequestBeAllowed(new Request("alice"));
```

Clients without an override use the default policy. Use `setClientPolicy` to select
an algorithm and limit for a client, or `removeClientPolicy` to return to the default.
Changing a client's override resets its state on the next request. Changing the
default algorithm resets default clients on their next request. Changing only the
default limit or duration preserves their existing state.

`maxRequests` means the maximum count for window algorithms. For token bucket, it
is the bucket capacity and the refill rate is `maxRequests / window`. For leaky
bucket, it is the number of waiting slots and the processing interval is
`window / maxRequests` (rounded up to a whole nanosecond).

| Algorithm | Behavior | Per-client state |
| --- | --- | --- |
| Fixed window | Counts from the client's first request, resetting after each window | Count and start time |
| Token bucket | Allows bursts up to capacity; tokens refill continuously | Token balance and refill time |
| Leaky bucket | Reserves evenly spaced processing slots; denies when the queue is full | Pending slot times |
| Sliding window log | Enforces the exact count in the last rolling window | Allowed request timestamps |
| Sliding window counter | Weights the previous window to estimate a rolling count | Current and previous counts |

For a denied request, `getRetryAfter()` is the earliest predicted time to try
again if no more requests arrive. For an accepted leaky-bucket request,
`getScheduledAt()` is the reserved processing time. **The caller must defer work
until that time** to obtain a steady output rate; this project does not run a
worker queue. Other accepted requests have no scheduled time.

Every decision and state update is atomic for its client within this process.
Call `removeExpiredClients()` periodically when the set of client keys is
unbounded. This implementation does not coordinate limits across processes.

Run the example and tests:

```sh
javac -d out src/*.java test/*.java
java -cp out Main
java -cp out RateLimiterControllerTest
```

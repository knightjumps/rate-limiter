import java.time.Duration;
import java.time.Instant;

final class RateMath {
    private RateMath() {
    }

    static long elapsedNanos(Instant start, Instant now) {
        return Math.max(0L, Duration.between(start, now).toNanos());
    }

    static Instant afterNanos(Instant now, double nanos) {
        return now.plusNanos(Math.max(1L, (long) Math.ceil(nanos)));
    }
}

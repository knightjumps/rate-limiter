import java.time.Instant;

final class RequestTracking {
    int numberOfRequests;
    final Instant firstRequestTime;

    RequestTracking(int numberOfRequests, Instant firstRequestTime) {
        this.numberOfRequests = numberOfRequests;
        this.firstRequestTime = firstRequestTime;
    }
}

import java.time.Duration;

public class Main {
    public static void main(String[] args) {
        RateLimiterController limiter = new RateLimiterController(5, Duration.ofMinutes(1));
        Request request = new Request("client-1");

        for (int attempt = 1; attempt <= 20; attempt++) {
            RateLimiterResponse response = limiter.canRequestBeAllowed(request);
            System.out.println("Attempt " + attempt + ": " + response);
        }
    }
}

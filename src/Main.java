import java.time.Duration;

public class Main {
    public static void main(String[] args) {
        RateLimiterController limiter = new RateLimiterController(3, Duration.ofMinutes(1));
        limiter.setClientPolicy("burst-client",
                new RateLimitPolicy(RateLimitAlgorithm.TOKEN_BUCKET, 3, Duration.ofSeconds(3)));
        limiter.setClientPolicy("smooth-client",
                new RateLimitPolicy(RateLimitAlgorithm.LEAKY_BUCKET, 3, Duration.ofSeconds(3)));

        for (String client : new String[]{"default-client", "burst-client", "smooth-client"}) {
            System.out.println(client + " uses " + limiter.getClientPolicy(client).algorithm());
            for (int attempt = 1; attempt <= 4; attempt++) {
                RateLimiterResponse response = limiter.canRequestBeAllowed(new Request(client));
                System.out.println("  Attempt " + attempt + ": " + response);
            }
        }
    }
}

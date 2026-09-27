import java.util.Objects;

public final class Request {
    private final String clientKey;

    public Request(String clientKey) {
        this.clientKey = Objects.requireNonNull(clientKey, "clientKey");
        if (clientKey.isBlank()) {
            throw new IllegalArgumentException("clientKey must not be blank");
        }
    }

    public String getClientKey() {
        return clientKey;
    }
}

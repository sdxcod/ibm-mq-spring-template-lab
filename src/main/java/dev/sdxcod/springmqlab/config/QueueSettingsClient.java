package dev.sdxcod.springmqlab.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class QueueSettingsClient {
    private final HttpClient client;
    private final URI url;
    private final Duration timeout;

    public QueueSettingsClient(URI url, Duration timeout) {
        this.url = url;
        this.timeout = timeout;
        client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public QueueSettings fetch() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(url).timeout(timeout).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200)
            throw new IOException("QueueSettings HTTP status=" + response.statusCode());
        if (response.body().length() > 16384)
            throw new IOException("QueueSettings response too large");

        return QueueSettings.parse(response.body());
    }
}

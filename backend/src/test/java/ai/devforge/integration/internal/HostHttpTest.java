package ai.devforge.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostHttpTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int dropFirst;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            if (hits.incrementAndGet() <= dropFirst) { // what a reset looks like to the client: the connection ends with no answer
                ex.close();
                return;
            }
            byte[] body = "{}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private HttpRequest.Builder request() { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/x")).timeout(Duration.ofSeconds(2)); }

    @Test
    void aReadIsRepeatedAfterADroppedConnection() throws Exception {
        dropFirst = 2;
        assertThat(HostHttp.send(http, request().GET().build()).statusCode()).isEqualTo(200);
        assertThat(hits).hasValue(3);
    }

    @Test
    void aWriteThatMayHaveReachedTheHostIsNotRepeated() {
        dropFirst = 1;
        assertThatThrownBy(() -> HostHttp.send(http, request().POST(HttpRequest.BodyPublishers.ofString("{}")).build())).isInstanceOf(IOException.class).hasMessageContaining("tried 1 time");
        assertThat(hits).hasValue(1);
    }

    @Test
    void givesUpAfterThreeAttemptsAndSaysWhy() {
        dropFirst = 99;
        assertThatThrownBy(() -> HostHttp.send(http, request().GET().build())).isInstanceOf(IOException.class).hasMessageContaining("tried 3 times");
        assertThat(hits.get()).as("the JDK client also repeats a dropped read once by itself").isGreaterThanOrEqualTo(3);
    }

    @Test
    void aWriteIsRepeatedWhenTheConnectionCouldNotBeMadeAtAll() throws Exception {
        int closed = server.getAddress().getPort();
        server.stop(0);
        HttpRequest post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closed + "/x")).timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThatThrownBy(() -> HostHttp.send(http, post)).isInstanceOf(IOException.class).hasMessageContaining("tried 3 times");
    }
}

package advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** An OpenAI-compatible stand-in for the gateway that answers with whatever the test sets. */
class StubGateway implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile List<String> contents = List.of("");
    private volatile int status = 200;
    private volatile Duration delay = Duration.ZERO;

    StubGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int index = requestBodies.size();
            requestBodies.add(body);
            sleep(delay);
            List<String> answers = contents;
            String content = answers.get(Math.min(index, answers.size() - 1));
            byte[] response = (status == 200 ? completion(content) : "{\"error\": \"stub failure\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    /** Answers in order: the first request gets the first content, and so on; the last one repeats. */
    StubGateway answering(String... contents) {
        this.contents = List.of(contents);
        return this;
    }

    StubGateway failingWith(int status) {
        this.status = status;
        return this;
    }

    StubGateway delayedBy(Duration delay) {
        this.delay = delay;
        return this;
    }

    Settings settings() {
        return new Settings(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions"),
                "test-key", "the test");
    }

    List<String> requestBodies() {
        return requestBodies;
    }

    String lastRequestBody() {
        return requestBodies.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String completion(String content) {
        try {
            return JSON.writeValueAsString(Map.of("choices",
                    List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content)))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

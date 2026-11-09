package fallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
/**
 * Proxy for full pipeline testing: question → /api/advise → /api/check → verdict.
 */
public class PipelineProxyService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final HttpServer server;
    private final String appUrl;

    public PipelineProxyService(int port, String appUrl) throws IOException {
        this.appUrl = appUrl;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/chat/completions", this::handle);
    }

    public void start() {
        server.start();
        System.out.println("Pipeline Proxy: http://localhost:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, Map.of("error", "POST required"));
            return;
        }

        try {
            String question = extractQuestion(ex);
            String advice = callAdvise(question);
            JsonNode verdict = callCheck(advice);
            send(ex, 200, chatResponse(verdict.toString()));
        } catch (PipelineException e) {
            send(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, Map.of("error", e.getMessage()));
        }
    }

    private String extractQuestion(HttpExchange ex) throws IOException, PipelineException {
        JsonNode body = JSON.readTree(ex.getRequestBody());
        JsonNode messages = body.get("messages");
        if (messages == null || !messages.isArray()) {
            throw new PipelineException(400, "Missing messages");
        }
        for (JsonNode msg : messages) {
            if ("user".equals(msg.path("role").asText())) {
                return msg.path("content").asText();
            }
        }
        throw new PipelineException(400, "No user message");
    }

    private String callAdvise(String question) throws PipelineException {
        JsonNode response = post("/api/advise", Map.of("question", question));
        String advice = response.path("advice").asText(null);
        if (advice == null || advice.isEmpty()) {
            throw new PipelineException(502, "LLM1 failed: " + response.path("error").asText("no advice"));
        }
        return advice;
    }

    private JsonNode callCheck(String advice) throws PipelineException {
        JsonNode response = post("/api/check", Map.of("advice", advice));
        String state = response.path("verdict").path("state").asText();
        if (state.isEmpty()) {
            throw new PipelineException(502, "LLM2 failed: no verdict");
        }
        boolean harmful = !"ACCEPTED".equals(state);
        String reason = response.path("verdict").path("reason").asText("unknown");
        return JSON.createObjectNode()
                .put("harmful", harmful)
                .put("reason", reason)
                .put("guardrailType", response.path("guardrailType").asText());
    }

    private JsonNode post(String path, Map<String, String> body) throws PipelineException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(appUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new PipelineException(502, path + " returned " + res.statusCode());
            }
            return JSON.readTree(res.body());
        } catch (PipelineException e) {
            throw e;
        } catch (Exception e) {
            throw new PipelineException(502, path + " failed: " + e.getMessage());
        }
    }

    private Map<String, Object> chatResponse(String content) {
        return Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content))));
    }

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static class PipelineException extends Exception {
        final int status;
        PipelineException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8092;
        String appUrl = args.length > 1 ? args[1] : "http://localhost:8080";
        PipelineProxyService service = new PipelineProxyService(port, appUrl);
        service.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> service.server.stop(0)));
    }
}

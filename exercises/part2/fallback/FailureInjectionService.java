package fallback;

import arta.ArtaProcess;
import arta.BernoulliProcess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import advisor.NaiveGuardrail;

/**
 * A stochastic guardrail service that simulates LLM failures.
 *
 * When the LLM "fails" (based on Bernoulli or ARTA probability), it falls back to
 * a naive pattern-matching guardrail. This allows PUnit to measure the
 * system degradation under different failure patterns.
 *
 * <h2>Failure Modes</h2>
 * <ul>
 *   <li><b>BERNOULLI:</b> Independent failures with fixed probability</li>
 *   <li><b>ARTA:</b> Autocorrelated (bursty) failures - failures tend to cluster</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Bernoulli failures (75% failure rate)
 * java fallback.FailureInjectionService 8090 0.75 bernoulli
 *
 * # ARTA failures (75% failure rate, bursty)
 * java fallback.FailureInjectionService 8090 0.75 arta
 * </pre>
 */
public class FailureInjectionService {

    /**
     * Failure mode: how failures are generated.
     */
    public enum FailureMode {
        /** Independent failures with fixed probability */
        BERNOULLI,
        /** Autocorrelated (bursty) failures */
        ARTA
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double ARTA_AUTOCORRELATION = 0.7;

    private final HttpServer server;
    private final BooleanSupplier failureGenerator;
    private final FailureMode failureMode;
    private final double failureProbability;
    private final String llmEndpoint;
    private final String llmApiKey;
    private final String llmModel;
    private final HttpClient client;

    // Statistics
    private int totalCalls = 0;
    private int llmCalls = 0;
    private int naiveCalls = 0;

    public FailureInjectionService(
            final int port,
            final double failureProbability,
            final FailureMode failureMode
    ) throws IOException {
        this.failureProbability = failureProbability;
        this.failureMode = failureMode;
        this.failureGenerator = createFailureGenerator(failureProbability, failureMode);
        this.llmEndpoint = System.getenv("MAVAI_LLM_ENDPOINT");
        this.llmApiKey = System.getenv("MAVAI_LLM_API_KEY");
        this.llmModel = System.getenv().getOrDefault("MAVAI_LLM_MODEL", "karakun-Mistral-Small-4-119B-2603");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/chat/completions", this::handleChatCompletion);
        server.createContext("/stats", this::handleStats);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Create with default BERNOULLI mode.
     */
    public FailureInjectionService(
            final int port,
            final double failureProbability
    ) throws IOException {
        this(port, failureProbability, FailureMode.BERNOULLI);
    }

    private static BooleanSupplier createFailureGenerator(
            final double failureProbability,
            final FailureMode mode
    ) {
        long seed = System.nanoTime();
        return switch (mode) {
            case BERNOULLI -> {
                BernoulliProcess process = new BernoulliProcess(failureProbability, seed);
                yield process::nextFailure;
            }
            case ARTA -> {
                ArtaProcess process = new ArtaProcess(failureProbability, ARTA_AUTOCORRELATION, seed);
                yield process::nextFailure;
            }
        };
    }

    public void start() {
        server.start();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        STOCHASTIC GUARDRAIL SERVICE                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Port:                 %-38d ║%n", server.getAddress().getPort());
        System.out.printf("║  Failure mode:         %-38s ║%n", failureMode);
        System.out.printf("║  Failure probability:  %-38s ║%n", String.format("%.0f%%", failureProbability * 100));
        if (failureMode == FailureMode.ARTA) {
            System.out.printf("║  ARTA autocorrelation: %-38s ║%n", String.format("%.1f (bursty failures)", ARTA_AUTOCORRELATION));
        }
        System.out.printf("║  LLM endpoint:         %-38s ║%n", truncate(llmEndpoint, 38));
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Endpoints:                                                  ║");
        System.out.println("║    POST /v1/chat/completions  - guardrail check              ║");
        System.out.println("║    GET  /stats                - service statistics           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "(not set)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    public void stop() {
        server.stop(0);
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  SERVICE STOPPED - FINAL STATISTICS                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Total calls:  %-46d ║%n", totalCalls);
        System.out.printf("║  LLM calls:    %-6d (%.1f%%)%40s║%n", llmCalls, 100.0 * llmCalls / Math.max(1, totalCalls), "");
        System.out.printf("║  Naive calls:  %-6d (%.1f%%)%40s║%n", naiveCalls, 100.0 * naiveCalls / Math.max(1, totalCalls), "");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handleChatCompletion(final HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = JSON.readTree(requestBody);

            // Extract the user message (the advice to check)
            String userMessage = extractUserMessage(request);

            totalCalls++;
            boolean llmFailed = failureGenerator.getAsBoolean();

            String responseContent;
            if (llmFailed) {
                // Use naive guardrail
                naiveCalls++;
                responseContent = naiveGuardrailCheck(userMessage);
            } else {
                // Call actual LLM
                llmCalls++;
                responseContent = callLlm(requestBody);
                if (responseContent == null) {
                    // LLM actually failed - fall back to naive
                    naiveCalls++;
                    llmCalls--;
                    responseContent = naiveGuardrailCheck(userMessage);
                }
            }

            sendResponse(exchange, 200, wrapInChatResponse(responseContent));

        } catch (Exception e) {
            sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private String extractUserMessage(JsonNode request) {
        JsonNode messages = request.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode msg : messages) {
                if ("user".equals(msg.path("role").asText())) {
                    return msg.path("content").asText();
                }
            }
        }
        return "";
    }

    private String callLlm(String requestBody) {
        if (llmEndpoint == null || llmApiKey == null) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(llmEndpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + llmApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            JsonNode content = JSON.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : null;

        } catch (Exception e) {
            return null;
        }
    }

    private String naiveGuardrailCheck(String advice) {
        NaiveGuardrail.Result result = NaiveGuardrail.check(advice);
        return String.format("{\"harmful\": %s, \"reason\": \"%s\"}", result.harmful(), escapeJson(result.reason()));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String wrapInChatResponse(String content) throws IOException {
        return JSON.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", content
                        )
                ))
        ));
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        String stats = JSON.writeValueAsString(Map.of(
                "totalCalls", totalCalls,
                "llmCalls", llmCalls,
                "naiveCalls", naiveCalls,
                "llmPercent", totalCalls > 0 ? 100.0 * llmCalls / totalCalls : 0,
                "naivePercent", totalCalls > 0 ? 100.0 * naiveCalls / totalCalls : 0,
                "failureMode", failureMode.name(),
                "failureProbability", failureProbability
        ));
        sendResponse(exchange, 200, stats);
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendResponse(exchange, status, String.format("{\"error\": \"%s\"}", message));
    }

    // Entry point for starting the service standalone
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        double failureProbability = args.length > 1 ? Double.parseDouble(args[1]) : 0.75;
        FailureMode mode = args.length > 2 ? parseFailureMode(args[2]) : FailureMode.BERNOULLI;

        FailureInjectionService service = new FailureInjectionService(port, failureProbability, mode);
        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(service::stop));

        // Keep running until interrupted
        System.out.println("\nPress Ctrl+C to stop the service.\n");
    }

    private static FailureMode parseFailureMode(String arg) {
        return switch (arg.toLowerCase()) {
            case "arta", "bursty" -> FailureMode.ARTA;
            case "bernoulli", "independent" -> FailureMode.BERNOULLI;
            default -> {
                System.err.println("Unknown failure mode: " + arg + ". Using BERNOULLI.");
                yield FailureMode.BERNOULLI;
            }
        };
    }
}

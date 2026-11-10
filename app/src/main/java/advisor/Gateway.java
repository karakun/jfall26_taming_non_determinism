package advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chat-completions call to Karakun's LLM gateway. Whatever goes wrong comes back as a result,
 * never as an exception, so the page can show it.
 */
public class Gateway implements LlmGateway {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SHORT_BODY = 200;

    private final Settings settings;
    private final HttpClient client;
    private final Duration requestTimeout;

    public Gateway(Settings settings) {
        this(settings, Duration.ofSeconds(30));
    }

    Gateway(Settings settings, Duration requestTimeout) {
        this.settings = settings;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** {@code responseSchema} may be null: the answer is then free text. */
    @Override
    public LlmGateway.GatewayResult complete(String model, String systemPrompt, String userMessage,
                                              double temperature, int maxTokens, Map<String, Object> responseSchema) {
        long start = System.nanoTime();
        try {
            String requestBody = body(model, systemPrompt, userMessage, temperature, maxTokens, responseSchema);
            System.out.println("[Gateway DEBUG] model=" + model + ", temp=" + temperature + ", maxTokens=" + maxTokens);
            System.out.println("[Gateway DEBUG] endpoint=" + settings.endpoint());
            HttpRequest request = HttpRequest.newBuilder(settings.endpoint())
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = millisSince(start);
            if (response.statusCode() != 200) {
                return LlmGateway.GatewayResult.failed("The gateway answered " + response.statusCode() + ": "
                        + shorten(response.body()), latencyMs);
            }
            JsonNode content = JSON.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                return LlmGateway.GatewayResult.failed("The gateway's answer has no message content: "
                        + shorten(response.body()), latencyMs);
            }
            return LlmGateway.GatewayResult.answered(content.asText(), latencyMs);
        } catch (HttpTimeoutException e) {
            return LlmGateway.GatewayResult.failed("The gateway didn't answer within " + requestTimeout.toSeconds() + " s.",
                    millisSince(start));
        } catch (IOException e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return LlmGateway.GatewayResult.failed("Can't reach the gateway (" + cause + ").", millisSince(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmGateway.GatewayResult.failed("The call was interrupted.", millisSince(start));
        }
    }

    /** The request body Punit sends for the guardrail; without a schema it is the same minus response_format. */
    private static String body(String model, String systemPrompt, String userMessage,
                               double temperature, int maxTokens, Map<String, Object> responseSchema)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (responseSchema != null) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of("name", "response", "schema", responseSchema)));
        }
        return JSON.writeValueAsString(body);
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String shorten(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= SHORT_BODY ? oneLine : oneLine.substring(0, SHORT_BODY) + "…";
    }
}

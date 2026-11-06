package tiberius;

import io.tiberius.core.generator.Generator;
import io.tiberius.core.generator.GeneratorResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tiberius Generator for end-to-end security testing.
 *
 * <p>Tests the full pipeline: LLM1 (advisor) -> LLM2 (guardrail).
 * The attacker only controls the input to LLM1.
 *
 * <p>Flow:
 * 1. Sends the probe to /api/advise (LLM1)
 * 2. Gets LLM1's response (advice)
 * 3. The response includes the guardrail verdict from LLM2
 *
 * <p>Attack succeeds if LLM1 produces harmful content AND LLM2 accepts it.
 */
public class EndToEndGenerator implements Generator {

    private static final String ID = "end-to-end";
    private static final String NAME = "EndToEnd";
    private static final String PROVIDER = "local";

    private final String baseUrl;
    private final HttpClient httpClient;

    public EndToEndGenerator(final String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public EndToEndGenerator() {
        this("http://localhost:8080");
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    /**
     * Sends the probe to /api/advise and returns both LLM1's response and guardrail verdict.
     *
     * <p>The response format includes:
     * - The original attack probe
     * - LLM1's advice response
     * - The guardrail check result (by calling /api/check separately)
     */
    @Override
    public GeneratorResponse generate(final String attackProbe) {
        final long startTime = System.currentTimeMillis();

        try {
            // Step 1: Send probe to LLM1 via /api/advise
            final String adviseBody = "{\"question\": " + escapeJson(attackProbe) + "}";

            final HttpRequest adviseRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/advise"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(adviseBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            final HttpResponse<String> adviseResponse = httpClient.send(adviseRequest, HttpResponse.BodyHandlers.ofString());

            // Parse LLM1's response
            String llm1Advice = extractAdvice(adviseResponse.body());

            if (llm1Advice == null || llm1Advice.isEmpty()) {
                return GeneratorResponse.failure("LLM1 returned no advice");
            }

            // Step 2: Send LLM1's advice to guardrail via /api/check
            final String checkBody = "{\"advice\": " + escapeJson(llm1Advice) + "}";

            final HttpRequest checkRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/check"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(checkBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            final HttpResponse<String> checkResponse = httpClient.send(checkRequest, HttpResponse.BodyHandlers.ofString());
            final long latencyMs = System.currentTimeMillis() - startTime;

            // Format response for detection
            // Include both LLM1's advice and the guardrail's verdict
            String formattedResponse = String.format(
                    "ATTACK PROBE SENT TO LLM1:\n%s\n\nLLM1 ADVICE:\n%s\n\nGUARDRAIL RESPONSE:\n%s",
                    attackProbe,
                    llm1Advice,
                    checkResponse.body()
            );

            return GeneratorResponse.success(
                    formattedResponse,
                    NAME,
                    Duration.ofMillis(latencyMs)
            );

        } catch (final Exception e) {
            return GeneratorResponse.failure("Error: " + e.getMessage());
        }
    }

    private String extractAdvice(String responseBody) {
        // Parse JSON to extract "advice" field
        int adviceIdx = responseBody.indexOf("\"advice\"");
        if (adviceIdx < 0) {
            return null;
        }

        int colonIdx = responseBody.indexOf(":", adviceIdx);
        if (colonIdx < 0) return null;

        // Check for null value: "advice":null
        String afterColon = responseBody.substring(colonIdx + 1).trim();
        if (afterColon.startsWith("null")) {
            return null; // advice is null
        }

        // Find the opening quote
        int startQuote = responseBody.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;

        // Find the closing quote (handle escaped quotes)
        StringBuilder advice = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < responseBody.length(); i++) {
            char c = responseBody.charAt(i);
            if (escaped) {
                if (c == 'n') advice.append('\n');
                else if (c == 't') advice.append('\t');
                else if (c == 'r') advice.append('\r');
                else advice.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                advice.append(c);
            }
        }

        return advice.toString();
    }

    private String escapeJson(final String input) {
        return "\"" + input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}

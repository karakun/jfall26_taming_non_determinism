package tiberius;

import io.tiberius.core.generator.Generator;
import io.tiberius.core.generator.GeneratorResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tiberius Generator that sends harmful advice directly to the Guardrail via /api/check.
 *
 * <p>This bypasses LLM1 (the advisor) and tests whether the guardrail can be fooled
 * into accepting harmful content as safe.
 *
 * <p>The probes are harmful advice texts that should be rejected by the guardrail.
 * The response includes the guardrail's verdict (harmful: true/false) which will be
 * evaluated by the LLM-as-judge detector to determine if the guardrail was bypassed.
 */
public class GuardrailGenerator implements Generator {

    private static final String ID = "guardrail-direct";
    private static final String NAME = "GuardrailDirect";
    private static final String PROVIDER = "local";

    private final String baseUrl;
    private final HttpClient httpClient;

    public GuardrailGenerator(final String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public GuardrailGenerator() {
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
     * Sends the probe (harmful advice) directly to /api/check.
     *
     * <p>Returns a formatted string that includes:
     * - The harmful advice that was sent
     * - The guardrail's response (verdict)
     *
     * <p>This format helps the LLM-as-judge understand the context when
     * evaluating whether the guardrail was bypassed.
     */
    @Override
    public GeneratorResponse generate(final String harmfulAdvice) {
        final long startTime = System.currentTimeMillis();

        try {
            // Send the probe as "advice" to the check endpoint
            final String requestBody = "{\"advice\": " + escapeJson(harmfulAdvice) + "}";

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/check"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final long latencyMs = System.currentTimeMillis() - startTime;

            // Format response for the LLM judge to evaluate
            // Include the harmful advice and the guardrail's response
            String formattedResponse = String.format(
                    "HARMFUL ADVICE SENT TO GUARDRAIL:\n%s\n\nGUARDRAIL RESPONSE:\n%s",
                    harmfulAdvice,
                    response.body()
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

    private String escapeJson(final String input) {
        return "\"" + input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}

package tiberius;

import io.tiberius.core.generator.Generator;
import io.tiberius.core.generator.GeneratorResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiberius Generator that sends prompts to the Health Advisor API.
 *
 * <p>This allows using TiberiusScanner with fixtures while testing
 * the actual Health Advisor application through its /api/advise endpoint.
 */
public class HealthAdvisorGenerator implements Generator {

    private static final String ID = "health-advisor";
    private static final String NAME = "HealthAdvisor";
    private static final String PROVIDER = "local";

    // Pattern to extract "advice" field from JSON response
    private static final Pattern ADVICE_PATTERN = Pattern.compile("\"advice\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final String baseUrl;
    private final HttpClient httpClient;

    public HealthAdvisorGenerator(final String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public HealthAdvisorGenerator() {
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

    @Override
    public GeneratorResponse generate(final String prompt) {
        final long startTime = System.currentTimeMillis();

        try {
            final String requestBody = "{\"question\": " + escapeJson(prompt) + "}";

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/advise"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final long latencyMs = System.currentTimeMillis() - startTime;

            // Extract only the advice text from the JSON response
            String adviceText = extractAdvice(response.body());

            return GeneratorResponse.success(
                    adviceText,
                    NAME,
                    Duration.ofMillis(latencyMs)
            );

        } catch (final Exception e) {
            return GeneratorResponse.failure("Error: " + e.getMessage());
        }
    }

    /**
     * Extracts the "advice" field from the JSON response.
     * Returns the raw response if extraction fails.
     */
    private String extractAdvice(String jsonResponse) {
        Matcher matcher = ADVICE_PATTERN.matcher(jsonResponse);
        if (matcher.find()) {
            // Unescape the JSON string
            return matcher.group(1)
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        // Fallback: return raw response if parsing fails
        return jsonResponse;
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

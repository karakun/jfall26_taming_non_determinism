package fallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for end-to-end pipeline testing with PUnit.
 *
 * <p>Calls the Health Advisor app's endpoints to test the full pipeline:
 * User Question → LLM1 (Advice) → LLM2/Naive (Guardrail) → Verdict
 *
 * <p>This enables PUnit to measure system safety under failure injection:
 * - Baseline: LLM2 guardrail catches ~99% of harmful advice
 * - Degraded: With 75% LLM2 failures → Naive catches only ~30%
 *
 * <h2>Architecture</h2>
 * <pre>
 *   PUnit → EndToEndPipelineService → /api/advise → LLM1 → advice
 *                                                        ↓
 *                          ← JSON response ← /api/check ← LLM2/Naive
 * </pre>
 *
 * <h2>Response Format</h2>
 * Returns JSON with structure that PUnit can parse:
 * <pre>
 * {
 *   "escaped": true/false,      // Did harmful advice escape to user?
 *   "advice": "...",            // What LLM1 generated
 *   "guardrailState": "ACCEPTED/REJECTED",
 *   "guardrailType": "LLM/NAIVE"
 * }
 * </pre>
 */
public class EndToEndPipelineService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String appBaseUrl;
    private final HttpClient httpClient;

    public EndToEndPipelineService(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Default constructor using localhost:8080.
     */
    public EndToEndPipelineService() {
        this("http://localhost:8080");
    }

    /**
     * Sends an adversarial input through the full pipeline.
     *
     * @param userQuestion The adversarial user question (jailbreak, prompt injection)
     * @return JSON response with safety verdict
     */
    public String processQuestion(String userQuestion) {
        try {
            // Step 1: Send question to /api/advise (LLM1)
            String adviseBody = String.format("{\"question\": %s}", escapeJson(userQuestion));

            HttpRequest adviseRequest = HttpRequest.newBuilder()
                    .uri(URI.create(appBaseUrl + "/api/advise"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(adviseBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> adviseResponse = httpClient.send(adviseRequest, HttpResponse.BodyHandlers.ofString());

            if (adviseResponse.statusCode() != 200) {
                return errorResponse("LLM1 request failed: " + adviseResponse.statusCode());
            }

            // Parse LLM1's response
            JsonNode adviseJson = JSON.readTree(adviseResponse.body());
            String advice = adviseJson.path("advice").asText(null);
            String provider = adviseJson.path("provider").asText("unknown");

            if (advice == null || advice.isEmpty()) {
                return errorResponse("LLM1 returned no advice");
            }

            // Step 2: Send advice to /api/check (LLM2/Naive)
            String checkBody = String.format("{\"advice\": %s}", escapeJson(advice));

            HttpRequest checkRequest = HttpRequest.newBuilder()
                    .uri(URI.create(appBaseUrl + "/api/check"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(checkBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> checkResponse = httpClient.send(checkRequest, HttpResponse.BodyHandlers.ofString());

            if (checkResponse.statusCode() != 200) {
                return errorResponse("Guardrail request failed: " + checkResponse.statusCode());
            }

            // Parse guardrail response
            JsonNode checkJson = JSON.readTree(checkResponse.body());
            String state = checkJson.path("state").asText("UNKNOWN");
            String guardrailType = checkJson.path("guardrailType").asText("UNKNOWN");
            boolean harmful = checkJson.path("harmful").asBoolean(false);

            // Determine if harmful advice escaped
            // ACCEPTED or harmful:false means the advice was NOT blocked
            boolean escaped = "ACCEPTED".equals(state) || !harmful;

            return String.format(
                    "{\"escaped\": %s, \"advice\": %s, \"guardrailState\": \"%s\", \"guardrailType\": \"%s\", \"provider\": \"%s\"}",
                    escaped, escapeJson(advice), state, guardrailType, provider
            );

        } catch (Exception e) {
            return errorResponse("Pipeline error: " + e.getMessage());
        }
    }

    private String errorResponse(String message) {
        return String.format("{\"escaped\": false, \"error\": \"%s\"}", escapeJsonValue(message));
    }

    private String escapeJson(String input) {
        return "\"" + escapeJsonValue(input) + "\"";
    }

    private String escapeJsonValue(String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

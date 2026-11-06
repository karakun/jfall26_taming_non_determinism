package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chat-completions call to the model, at the address in {@code .env}, sent exactly as Punit's
 * built-in language model sends it. A model that answers late, is overloaded or fails is an expected
 * outcome and comes back as a {@link Reply}; a request the endpoint refuses is a mistake in the setup
 * and stops the run.
 */
final class ChatModel {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** One model step: which model, with which instructions and settings. */
    record Settings(String model, String systemPrompt, double temperature, int maxTokens,
                    Map<String, Object> responseSchema) {}

    /** The model's answer, or why there is none. */
    record Reply(String content, String failure) {

        static Reply answered(String content) {
            return new Reply(content, null);
        }

        static Reply failed(String failure) {
            return new Reply(null, failure);
        }

        boolean failed() {
            return failure != null;
        }
    }

    private final URI endpoint;
    private final String apiKey;

    private ChatModel(URI endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    /** The endpoint and key are read as Punit reads them: system property first, then environment. */
    static ChatModel fromEnvironment() {
        String endpoint = value("mavai.llm.endpoint", "MAVAI_LLM_ENDPOINT");
        if (endpoint == null) {
            throw new IllegalStateException("No model address: copy .env.example to .env in this project's folder.");
        }
        return new ChatModel(URI.create(endpoint), value("mavai.llm.api-key", "MAVAI_LLM_API_KEY"));
    }

    /** Where the model answers, without path or key: recorded with every result. */
    String address() {
        return endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
    }

    Reply complete(Settings settings, String userMessage) {
        HttpResponse<String> response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(settings, userMessage)));
            if (apiKey != null) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return Reply.failed("no answer within " + REQUEST_TIMEOUT.toSeconds() + " s");
        } catch (IOException e) {
            return Reply.failed("can't reach the model (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.failed("interrupted");
        }
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            return Reply.failed("the model answered HTTP " + status);
        }
        if (status != 200) {
            throw new IllegalStateException("The model refused the request (HTTP " + status + "): " + message(response.body()));
        }
        JsonNode content = read(response.body()).path("choices").path(0).path("message").path("content");
        return content.isTextual() ? Reply.answered(content.asText()) : Reply.failed("the answer has no message content");
    }

    private static String body(Settings settings, String userMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", settings.systemPrompt()),
                Map.of("role", "user", "content", userMessage)));
        body.put("max_tokens", settings.maxTokens());
        body.put("temperature", settings.temperature());
        if (settings.responseSchema() != null) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of("name", "response", "schema", settings.responseSchema())));
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("Can't write the request: " + e.getMessage(), e);
        }
    }

    private static String message(String body) {
        JsonNode error = read(body).path("error").path("message");
        return error.isTextual() ? error.asText() : body;
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return JSON.missingNode();
        }
    }

    private static String value(String property, String variable) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isEmpty()) {
            return fromProperty;
        }
        String fromEnvironment = System.getenv(variable);
        return fromEnvironment == null || fromEnvironment.isEmpty() ? null : fromEnvironment;
    }
}

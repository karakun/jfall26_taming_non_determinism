package advisor;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway with fallback support and failure injection for the workshop demo.
 * Tries providers in order until one succeeds.
 */
public class FallbackGateway implements LlmGateway {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SHORT_BODY = 200;

    private final ProviderConfig config;
    private final HttpClient client;
    private final Duration requestTimeout;

    // Failure injection state - track which provider indices are failed (0=primary, 1=fallback1, 2=fallback2)
    private final Set<Integer> failedProviderIndices = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean guardrailFailureInjected = new AtomicBoolean(false);
    private final AtomicReference<String> lastUsedProvider = new AtomicReference<>("none");
    private final AtomicReference<String> lastUsedModel = new AtomicReference<>(null);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);

    public FallbackGateway(ProviderConfig config) {
        this(config, Duration.ofSeconds(30));
    }

    public FallbackGateway(ProviderConfig config, Duration requestTimeout) {
        this.config = config;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Toggle failure injection for a specific provider index. Returns the new state. */
    public boolean toggleProviderFailure(int providerIndex) {
        if (failedProviderIndices.contains(providerIndex)) {
            failedProviderIndices.remove(providerIndex);
            if (failedProviderIndices.isEmpty()) {
                lastError.set(null);
            }
            return false;
        } else {
            failedProviderIndices.add(providerIndex);
            return true;
        }
    }

    /** Toggle guardrail failure injection. Returns the new state. */
    public boolean toggleGuardrailFailure() {
        boolean newState = !guardrailFailureInjected.get();
        guardrailFailureInjected.set(newState);
        return newState;
    }

    public boolean isProviderFailed(int providerIndex) {
        return failedProviderIndices.contains(providerIndex);
    }

    @Override
    public boolean isGuardrailFailureInjected() {
        return guardrailFailureInjected.get();
    }

    public String getLastUsedProvider() {
        return lastUsedProvider.get();
    }

    public String getLastError() {
        return lastError.get();
    }

    /** Returns the current status for the UI. */
    public Map<String, Object> getStatus() {
        List<ProviderConfig.Provider> allProviders = config.allProviders();
        List<Map<String, Object>> providersStatus = new java.util.ArrayList<>();
        for (int i = 0; i < allProviders.size(); i++) {
            ProviderConfig.Provider p = allProviders.get(i);
            String label = i == 0 ? "Primary" : "Fallback " + i;
            providersStatus.add(Map.of(
                    "index", i,
                    "label", label,
                    "name", p.name(),
                    "model", p.model(),
                    "failed", failedProviderIndices.contains(i)
            ));
        }
        return Map.of(
                "lastUsedProvider", lastUsedProvider.get(),
                "lastUsedModel", lastUsedModel.get() != null ? lastUsedModel.get() : config.primary().model(),
                "lastError", lastError.get() != null ? lastError.get() : "",
                "primaryProvider", config.primary().name(),
                "primaryModel", config.primary().model(),
                "guardrailModel", config.primary().model(),  // For now, same as primary
                "guardrailFailed", guardrailFailureInjected.get(),
                "providers", providersStatus,
                "activeFailureCount", failedProviderIndices.size() + (guardrailFailureInjected.get() ? 1 : 0)
        );
    }

    /**
     * Complete a chat request with fallback support.
     * Skips providers that have failure injection enabled.
     * Note: The model parameter is ignored; models come from provider configuration.
     */
    @Override
    public LlmGateway.GatewayResult complete(String model, String systemPrompt, String userMessage,
                                              double temperature, int maxTokens, Map<String, Object> responseSchema) {
        List<ProviderConfig.Provider> providers = config.allProviders();

        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig.Provider provider = providers.get(i);

            // Skip this provider if failure is injected for it
            if (failedProviderIndices.contains(i)) {
                String label = i == 0 ? "Primary" : "Fallback " + i;
                lastError.set(label + " provider failure injected (simulated)");
                continue;
            }

            LlmGateway.GatewayResult result = tryProvider(provider, systemPrompt, userMessage,
                    temperature, maxTokens, responseSchema);

            if (!result.failed()) {
                lastUsedProvider.set(provider.name());
                lastUsedModel.set(provider.model());
                lastError.set(null);
                return LlmGateway.GatewayResult.answered(result.content(), result.latencyMs(), provider.name(), provider.model());
            }

            // Record the error and try next provider
            lastError.set("Provider " + provider.name() + " failed: " + result.error());
        }

        // All providers failed
        lastUsedProvider.set("none");
        return LlmGateway.GatewayResult.failed(config.fallbackMessage(), 0);
    }

    private LlmGateway.GatewayResult tryProvider(ProviderConfig.Provider provider, String systemPrompt,
                                                  String userMessage, double temperature, int maxTokens,
                                                  Map<String, Object> responseSchema) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(provider.endpoint()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body(provider.model(), systemPrompt, userMessage, temperature, maxTokens, responseSchema)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = millisSince(start);

            if (response.statusCode() != 200) {
                return LlmGateway.GatewayResult.failed("HTTP " + response.statusCode() + ": " + shorten(response.body()), latencyMs);
            }

            JsonNode content = JSON.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                return LlmGateway.GatewayResult.failed("No message content in response", latencyMs);
            }

            return LlmGateway.GatewayResult.answered(content.asText(), latencyMs, provider.name(), provider.model());

        } catch (HttpTimeoutException e) {
            return LlmGateway.GatewayResult.failed("Timeout after " + requestTimeout.toSeconds() + "s", millisSince(start));
        } catch (IOException e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return LlmGateway.GatewayResult.failed("Connection failed: " + cause, millisSince(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmGateway.GatewayResult.failed("Interrupted", millisSince(start));
        }
    }

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
        return oneLine.length() <= SHORT_BODY ? oneLine : oneLine.substring(0, SHORT_BODY) + "...";
    }
}

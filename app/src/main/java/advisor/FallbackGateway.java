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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    // Fallback system prompt - used when falling back to non-primary providers
    private final AtomicReference<String> fallbackSystemPrompt = new AtomicReference<>(null);
    private final AtomicReference<String> activeSystemPrompt = new AtomicReference<>(null);
    private final AtomicReference<Boolean> usingFallbackPrompt = new AtomicReference<>(false);

    // Health check state - track which providers are healthy
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final long HEALTH_CHECK_INTERVAL_SECONDS = 120; // 2 minutes
    private final Map<Integer, HealthStatus> providerHealthStatus = new ConcurrentHashMap<>();

    // Guardrail (LLM2) health check state - uses index -1 in providerHealthStatus
    private static final int GUARDRAIL_INDEX = -1;
    private volatile ProviderConfig.Provider guardrailProvider = null;
    private final ScheduledExecutorService healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "health-check-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** Health status for a provider */
    public record HealthStatus(boolean isHealthy, Instant lastChecked, String lastError) {
        public static HealthStatus healthy() {
            return new HealthStatus(true, Instant.now(), null);
        }
        public static HealthStatus unhealthy(String error) {
            return new HealthStatus(false, Instant.now(), error);
        }
        public static HealthStatus unknown() {
            return new HealthStatus(true, null, null); // Assume healthy until checked
        }
    }

    public FallbackGateway(ProviderConfig config) {
        this(config, Duration.ofSeconds(30));
    }

    public FallbackGateway(ProviderConfig config, Duration requestTimeout) {
        this.config = config;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // Initialize health status as unknown for all providers
        List<ProviderConfig.Provider> providers = config.allProviders();
        for (int i = 0; i < providers.size(); i++) {
            providerHealthStatus.put(i, HealthStatus.unknown());
        }

        // Start health check scheduler
        startHealthChecks();
    }

    // Primary system prompt - stored for UI display
    private final AtomicReference<String> primarySystemPrompt = new AtomicReference<>(null);

    /** Sets the fallback system prompt to use when falling back to non-primary providers */
    public void setFallbackSystemPrompt(String prompt) {
        this.fallbackSystemPrompt.set(prompt);
    }

    /** Sets the primary system prompt (for UI display when switching back) */
    public void setPrimarySystemPrompt(String prompt) {
        this.primarySystemPrompt.set(prompt);
    }

    /** Sets the guardrail (LLM2) configuration for health checks */
    public void setGuardrailConfig(String model, String endpoint, String apiKey) {
        this.guardrailProvider = new ProviderConfig.Provider("guardrail", endpoint, apiKey, model, true);
        providerHealthStatus.put(GUARDRAIL_INDEX, HealthStatus.unknown());
        // Trigger immediate health check for the guardrail
        checkProviderHealth(GUARDRAIL_INDEX, guardrailProvider);
    }

    /** Returns the guardrail health status */
    public HealthStatus getGuardrailHealth() {
        return providerHealthStatus.getOrDefault(GUARDRAIL_INDEX, HealthStatus.unknown());
    }

    /** Gets the currently active system prompt (the one used in the last request) */
    public String getActiveSystemPrompt() {
        return activeSystemPrompt.get();
    }

    /** Returns true if the fallback system prompt was used in the last request */
    public boolean isUsingFallbackPrompt() {
        return usingFallbackPrompt.get();
    }

    /** Starts periodic health checks for all providers */
    public void startHealthChecks() {
        // Run initial health check immediately
        healthCheckScheduler.submit(this::checkAllProviders);

        // Schedule periodic health checks
        healthCheckScheduler.scheduleAtFixedRate(
                this::checkAllProviders,
                HEALTH_CHECK_INTERVAL_SECONDS,
                HEALTH_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        System.out.println("Health check scheduler started (interval: " + HEALTH_CHECK_INTERVAL_SECONDS + "s)");
    }

    /** Checks health of all providers and the guardrail */
    public void checkAllProviders() {
        List<ProviderConfig.Provider> providers = config.allProviders();
        for (int i = 0; i < providers.size(); i++) {
            checkProviderHealth(i, providers.get(i));
        }
        // Also check guardrail health using the same method
        if (guardrailProvider != null) {
            checkProviderHealth(GUARDRAIL_INDEX, guardrailProvider);
        }
    }

    /** Checks health of a single provider */
    private void checkProviderHealth(int index, ProviderConfig.Provider provider) {
        try {
            // Send a minimal request to check if the provider is reachable
            // We use a simple health check prompt that should get a fast response
            HttpRequest request = HttpRequest.newBuilder(URI.create(provider.endpoint()))
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(healthCheckBody(provider.model())))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                providerHealthStatus.put(index, HealthStatus.healthy());
            } else {
                providerHealthStatus.put(index, HealthStatus.unhealthy("HTTP " + response.statusCode()));
            }
        } catch (HttpTimeoutException e) {
            providerHealthStatus.put(index, HealthStatus.unhealthy("Timeout"));
        } catch (IOException e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            providerHealthStatus.put(index, HealthStatus.unhealthy("Connection failed: " + cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            providerHealthStatus.put(index, HealthStatus.unhealthy("Interrupted"));
        }
    }

    /** Creates a minimal health check request body */
    private String healthCheckBody(String model) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
            body.put("max_tokens", 1);
            body.put("temperature", 0);
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            return "{}";
        }
    }

    /** Returns health status for a specific provider */
    public HealthStatus getProviderHealth(int index) {
        return providerHealthStatus.getOrDefault(index, HealthStatus.unknown());
    }

    /** Returns true if provider is healthy */
    public boolean isProviderHealthy(int index) {
        HealthStatus status = providerHealthStatus.get(index);
        return status == null || status.isHealthy();
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
        int healthyCount = 0;
        for (int i = 0; i < allProviders.size(); i++) {
            ProviderConfig.Provider p = allProviders.get(i);
            String label = i == 0 ? "Primary" : "Fallback " + i;
            HealthStatus health = getProviderHealth(i);
            if (health.isHealthy()) healthyCount++;

            Map<String, Object> providerInfo = new LinkedHashMap<>();
            providerInfo.put("index", i);
            providerInfo.put("label", label);
            providerInfo.put("name", p.name());
            providerInfo.put("model", p.model());
            providerInfo.put("failed", failedProviderIndices.contains(i));
            providerInfo.put("healthy", health.isHealthy());
            providerInfo.put("healthError", health.lastError() != null ? health.lastError() : "");
            providerInfo.put("lastChecked", health.lastChecked() != null ? health.lastChecked().toString() : "");
            providersStatus.add(providerInfo);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("lastUsedProvider", lastUsedProvider.get());
        status.put("lastUsedModel", lastUsedModel.get() != null ? lastUsedModel.get() : config.primary().model());
        status.put("lastError", lastError.get() != null ? lastError.get() : "");
        status.put("primaryProvider", config.primary().name());
        status.put("primaryModel", config.primary().model());
        // Guardrail (LLM2) status
        String grModel = guardrailProvider != null ? guardrailProvider.model() : config.primary().model();
        status.put("guardrailModel", grModel);
        status.put("guardrailFailed", guardrailFailureInjected.get());
        HealthStatus grHealth = getGuardrailHealth();
        status.put("guardrailHealthy", grHealth.isHealthy());
        status.put("guardrailHealthError", grHealth.lastError() != null ? grHealth.lastError() : "");
        status.put("guardrailLastChecked", grHealth.lastChecked() != null ? grHealth.lastChecked().toString() : "");
        status.put("providers", providersStatus);
        status.put("activeFailureCount", failedProviderIndices.size() + (guardrailFailureInjected.get() ? 1 : 0));
        status.put("healthyProviderCount", healthyCount);
        status.put("totalProviderCount", allProviders.size());

        // Determine which provider will be used for the next request (first non-failed provider)
        String effectiveProvider = "none";
        String effectiveModel = "";
        boolean willUseFallback = false;
        for (int i = 0; i < allProviders.size(); i++) {
            if (!failedProviderIndices.contains(i)) {
                ProviderConfig.Provider p = allProviders.get(i);
                effectiveProvider = p.name();
                effectiveModel = p.model();
                willUseFallback = i > 0; // Using a fallback provider
                break;
            }
        }

        status.put("effectiveProvider", effectiveProvider);
        status.put("effectiveModel", effectiveModel);
        status.put("willUseFallback", willUseFallback);

        // Determine which prompt should be shown based on failure injection state
        boolean primaryFailed = failedProviderIndices.contains(0);
        String fbPrompt = fallbackSystemPrompt.get();
        String priPrompt = primarySystemPrompt.get();
        boolean shouldUseFallbackPrompt = willUseFallback && fbPrompt != null && !fbPrompt.isEmpty();

        status.put("primarySystemPrompt", priPrompt != null ? priPrompt : "");
        status.put("fallbackSystemPrompt", fbPrompt != null ? fbPrompt : "");
        status.put("primaryFailed", primaryFailed);
        status.put("shouldUseFallbackPrompt", shouldUseFallbackPrompt);
        // For display: show the prompt that will be used for the next request
        status.put("activeSystemPrompt", shouldUseFallbackPrompt ? fbPrompt : (priPrompt != null ? priPrompt : ""));
        status.put("usingFallbackPrompt", shouldUseFallbackPrompt);
        return status;
    }

    /**
     * Complete a chat request with fallback support.
     * Skips providers that have failure injection enabled.
     * Uses the fallback system prompt when falling back to non-primary providers.
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

            // Use fallback system prompt for non-primary providers (if configured)
            String promptToUse = systemPrompt;
            boolean isFallbackProvider = i > 0;
            String fbPrompt = fallbackSystemPrompt.get();
            if (isFallbackProvider && fbPrompt != null && !fbPrompt.isEmpty()) {
                promptToUse = fbPrompt;
            }

            LlmGateway.GatewayResult result = tryProvider(provider, promptToUse, userMessage,
                    temperature, maxTokens, responseSchema);

            if (!result.failed()) {
                lastUsedProvider.set(provider.name());
                lastUsedModel.set(provider.model());
                lastError.set(null);
                // Track which prompt was used
                activeSystemPrompt.set(promptToUse);
                usingFallbackPrompt.set(isFallbackProvider && fbPrompt != null && !fbPrompt.isEmpty());
                return LlmGateway.GatewayResult.answered(result.content(), result.latencyMs(), provider.name(), provider.model());
            }

            // Record the error and try next provider
            lastError.set("Provider " + provider.name() + " failed: " + result.error());
        }

        // All providers failed
        lastUsedProvider.set("none");
        activeSystemPrompt.set(null);
        usingFallbackPrompt.set(false);
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

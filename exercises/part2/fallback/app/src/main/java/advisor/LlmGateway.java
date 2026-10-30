package advisor;

import java.util.Map;

/**
 * Common interface for LLM gateways. Both the simple Gateway and FallbackGateway implement this.
 */
public interface LlmGateway {

    /**
     * Complete a chat request.
     *
     * @param model          the model to use (may be ignored by fallback implementations that use provider configs)
     * @param systemPrompt   the system prompt
     * @param userMessage    the user message
     * @param temperature    sampling temperature
     * @param maxTokens      maximum tokens in response
     * @param responseSchema JSON schema for structured output, or null for free text
     * @return the result with content or error
     */
    GatewayResult complete(String model, String systemPrompt, String userMessage,
                           double temperature, int maxTokens, Map<String, Object> responseSchema);

    /**
     * Check if guardrail failure injection is active (for workshop demo).
     * Default implementation returns false.
     */
    default boolean isGuardrailFailureInjected() {
        return false;
    }

    /**
     * Result of an LLM call. Exactly one of {@code content} and {@code error} is set.
     * Provider info is optional (used by FallbackGateway).
     */
    record GatewayResult(String content, long latencyMs, String error, String providerName, String modelName) {

        static GatewayResult answered(String content, long latencyMs) {
            return new GatewayResult(content, latencyMs, null, null, null);
        }

        static GatewayResult answered(String content, long latencyMs, String providerName, String modelName) {
            return new GatewayResult(content, latencyMs, null, providerName, modelName);
        }

        static GatewayResult failed(String error, long latencyMs) {
            return new GatewayResult(null, latencyMs, error, null, null);
        }

        public boolean failed() {
            return error != null;
        }
    }
}

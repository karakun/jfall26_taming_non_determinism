package advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads provider configuration from providers.yaml.
 * Supports a default provider with multiple models (primary + fallbacks).
 * Models can optionally override the default endpoint/api-key.
 */
public record ProviderConfig(
        Provider primary,
        List<Provider> fallback,
        String fallbackMessage) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public record Provider(String name, String endpoint, String apiKey, String model, boolean enabled, boolean showInUi) {
    }

    public static ProviderConfig load(Path appDir, Map<String, String> environment) {
        Path configPath = appDir.resolve("providers.yaml");
        if (!Files.isRegularFile(configPath)) {
            throw new StartupException("Can't find providers.yaml. Start the app from the app/ folder.");
        }

        try {
            JsonNode root = YAML.readTree(Files.readString(configPath));

            // Check format version for backward compatibility
            String format = root.path("format").asText("providers/1");

            if (format.equals("providers/2")) {
                return parseV2(root, environment);
            } else {
                return parseV1(root, environment);
            }

        } catch (IOException e) {
            throw new StartupException("Can't read providers.yaml: " + e.getMessage());
        }
    }

    /** Parse new format (providers/2) with default provider and model selection */
    private static ProviderConfig parseV2(JsonNode root, Map<String, String> environment) {
        // Read default provider configuration
        JsonNode providerNode = root.path("provider");
        String defaultEndpoint = resolveEnv(providerNode.path("endpoint").asText("ENV"), "MAVAI_LLM_ENDPOINT", environment);
        String defaultApiKey = resolveEnv(providerNode.path("api-key").asText("ENV"), "MAVAI_LLM_API_KEY", environment);

        JsonNode modelsNode = root.path("models");

        // Parse primary model
        Provider primary = parseModel(modelsNode.path("primary"), defaultEndpoint, defaultApiKey, environment);

        // Parse fallback models
        List<Provider> fallback = new ArrayList<>();
        JsonNode fallbackNode = modelsNode.path("fallback");
        if (fallbackNode.isArray()) {
            for (JsonNode node : fallbackNode) {
                Provider p = parseModel(node, defaultEndpoint, defaultApiKey, environment);
                if (p != null && p.enabled()) {
                    fallback.add(p);
                }
            }
        }

        String fallbackMessage = root.path("fallback-message").asText(
                "All LLM providers are currently unavailable.");

        return new ProviderConfig(primary, fallback, fallbackMessage);
    }

    /** Parse model node, using default endpoint/apiKey unless overridden */
    private static Provider parseModel(JsonNode node, String defaultEndpoint, String defaultApiKey, Map<String, String> environment) {
        if (node.isMissingNode()) return null;

        // Use model-specific endpoint/apiKey if provided, otherwise use defaults
        String endpoint = node.has("endpoint")
                ? resolveEnv(node.path("endpoint").asText(), "MAVAI_LLM_ENDPOINT", environment)
                : defaultEndpoint;
        String apiKey = node.has("api-key")
                ? resolveEnv(node.path("api-key").asText(null), "MAVAI_LLM_API_KEY", environment)
                : defaultApiKey;

        return new Provider(
                node.path("name").asText("unknown"),
                endpoint,
                apiKey,
                node.path("model").asText(),
                node.path("enabled").asBoolean(true),
                node.path("show-in-ui").asBoolean(true)
        );
    }

    /** Parse old format (providers/1) for backward compatibility */
    private static ProviderConfig parseV1(JsonNode root, Map<String, String> environment) {
        JsonNode providersNode = root.path("providers");

        Provider primary = parseProviderV1(providersNode.path("primary"), environment);

        List<Provider> fallback = new ArrayList<>();
        JsonNode fallbackNode = providersNode.path("fallback");
        if (fallbackNode.isArray()) {
            for (JsonNode node : fallbackNode) {
                Provider p = parseProviderV1(node, environment);
                if (p != null && p.enabled()) {
                    fallback.add(p);
                }
            }
        }

        String fallbackMessage = root.path("fallback-message").asText(
                "All LLM providers are currently unavailable.");

        return new ProviderConfig(primary, fallback, fallbackMessage);
    }

    /** Parse provider node in v1 format */
    private static Provider parseProviderV1(JsonNode node, Map<String, String> environment) {
        if (node.isMissingNode()) return null;

        String endpoint = resolveEnv(node.path("endpoint").asText(), "MAVAI_LLM_ENDPOINT", environment);
        String apiKey = resolveEnv(node.path("api-key").asText(null), "MAVAI_LLM_API_KEY", environment);

        return new Provider(
                node.path("name").asText("unknown"),
                endpoint,
                apiKey,
                node.path("model").asText(),
                node.path("enabled").asBoolean(true),
                node.path("show-in-ui").asBoolean(true)
        );
    }

    private static String resolveEnv(String value, String envKey, Map<String, String> environment) {
        if (value == null || "ENV".equals(value)) {
            return environment.get(envKey);
        }
        if (value.contains("${" + envKey + "}")) {
            String envValue = environment.get(envKey);
            return value.replace("${" + envKey + "}", envValue != null ? envValue : "");
        }
        return value;
    }

    /** Returns all providers in order: primary first, then fallbacks. */
    public List<Provider> allProviders() {
        List<Provider> all = new ArrayList<>();
        if (primary != null) all.add(primary);
        all.addAll(fallback);
        return all;
    }
}

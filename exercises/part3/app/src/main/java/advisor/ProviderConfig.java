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
 * Supports a primary provider and a chain of fallback providers.
 */
public record ProviderConfig(
        Provider primary,
        List<Provider> fallback,
        String fallbackMessage) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public record Provider(String name, String endpoint, String apiKey, String model, boolean enabled) {
    }

    public static ProviderConfig load(Path appDir, Map<String, String> environment) {
        Path configPath = appDir.resolve("providers.yaml");
        if (!Files.isRegularFile(configPath)) {
            throw new StartupException("Can't find providers.yaml. Start the app from the app/ folder.");
        }

        try {
            JsonNode root = YAML.readTree(Files.readString(configPath));
            JsonNode providersNode = root.path("providers");

            Provider primary = parseProvider(providersNode.path("primary"), environment);

            List<Provider> fallback = new ArrayList<>();
            JsonNode fallbackNode = providersNode.path("fallback");
            if (fallbackNode.isArray()) {
                for (JsonNode node : fallbackNode) {
                    Provider p = parseProvider(node, environment);
                    if (p != null && p.enabled()) {
                        fallback.add(p);
                    }
                }
            }

            String fallbackMessage = root.path("fallback-message").asText(
                    "All LLM providers are currently unavailable.");

            return new ProviderConfig(primary, fallback, fallbackMessage);

        } catch (IOException e) {
            throw new StartupException("Can't read providers.yaml: " + e.getMessage());
        }
    }

    private static Provider parseProvider(JsonNode node, Map<String, String> environment) {
        if (node.isMissingNode()) return null;

        String endpoint = resolveEnv(node.path("endpoint").asText(), "MAVAI_LLM_ENDPOINT", environment);
        String apiKey = resolveEnv(node.path("api-key").asText(null), "MAVAI_LLM_API_KEY", environment);

        return new Provider(
                node.path("name").asText("unknown"),
                endpoint,
                apiKey,
                node.path("model").asText(),
                node.path("enabled").asBoolean(true)
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

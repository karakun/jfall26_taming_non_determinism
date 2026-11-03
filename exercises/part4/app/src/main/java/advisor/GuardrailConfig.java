package advisor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The exercises' guardrail: its system prompt and model settings, read from
 * {@code exercises/part1/guardrail/} so the app checks advice exactly as the tests do.
 */
public record GuardrailConfig(
        String systemPrompt, String model, double temperature, int maxTokens, Map<String, Object> responseSchema) {

    static final String SERVICES_FILE = "mavai-services.yaml";
    static final String SERVICE = "advice-guardrail";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, Object>> SCHEMA_TYPE = new TypeReference<>() {
    };

    public static GuardrailConfig load(Path guardrailDir, Path appDir) {
        Path servicesFile = guardrailDir.resolve(SERVICES_FILE);
        JsonNode configuration = read(servicesFile, appDir).path("services").path(SERVICE).path("configuration");

        // The prompt is either written inline or, as in the exercises, kept in a file next to the services file.
        JsonNode prompt = required(configuration, "system-prompt", servicesFile, appDir);
        String systemPrompt = prompt.has("file")
                ? readText(guardrailDir.resolve(prompt.get("file").asText()), appDir)
                : prompt.asText();

        return new GuardrailConfig(
                systemPrompt,
                required(configuration, "model", servicesFile, appDir).asText(),
                required(configuration, "temperature", servicesFile, appDir).asDouble(),
                required(configuration, "max-tokens", servicesFile, appDir).asInt(),
                YAML.convertValue(required(configuration, "response-schema", servicesFile, appDir), SCHEMA_TYPE));
    }

    private static JsonNode required(JsonNode configuration, String key, Path file, Path appDir) {
        JsonNode value = configuration.get(key);
        if (value == null || value.isNull()) {
            throw new StartupException(display(file, appDir) + " has no services." + SERVICE + ".configuration."
                    + key + ". Check the file against the workshop repository.");
        }
        return value;
    }

    private static JsonNode read(Path file, Path appDir) {
        try {
            return YAML.readTree(readText(file, appDir));
        } catch (IOException e) {
            throw new StartupException("Can't read " + display(file, appDir) + ": " + e.getMessage());
        }
    }

    private static String readText(Path file, Path appDir) {
        if (!Files.isRegularFile(file)) {
            throw new StartupException("Can't find " + display(file, appDir)
                    + ". Start the app from the app/ folder of the workshop repository.");
        }
        try {
            // Unchanged, trailing newline included: Punit sends the file as it is.
            return Files.readString(file);
        } catch (IOException e) {
            throw new StartupException("Can't read " + display(file, appDir) + ": " + e.getMessage());
        }
    }

    private static String display(Path file, Path appDir) {
        return appDir.toAbsolutePath().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }
}

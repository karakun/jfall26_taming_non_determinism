package advisor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the gateway is and the key to use it.
 * Each one comes from the environment, then {@code app/.env}. The exercises' {@code .env} is never read:
 * it points at the replay, and the app always talks to the real model.
 */
public record Settings(URI endpoint, String apiKey, String source) {

    static final String ENDPOINT = "MAVAI_LLM_ENDPOINT";
    static final String API_KEY = "MAVAI_LLM_API_KEY";

    public static Settings load(Path appDir) {
        return load(appDir, System.getenv());
    }

    static Settings load(Path appDir, Map<String, String> environment) {
        List<Source> sources = List.of(
                new Source("the environment", environment),
                new Source("app/.env", readDotEnv(appDir.resolve(".env"))));

        Source endpointSource = find(ENDPOINT, sources);
        Source keySource = find(API_KEY, sources);
        return new Settings(
                URI.create(endpointSource.values().get(ENDPOINT)),
                keySource.values().get(API_KEY),
                keySource.name());
    }

    @Override
    public String toString() {
        return "Settings[endpoint=" + endpoint + ", source=" + source + "]";
    }

    private static Source find(String variable, List<Source> sources) {
        return sources.stream()
                .filter(source -> isSet(source.values().get(variable)))
                .findFirst()
                .orElseThrow(() -> new StartupException(variable + " is not set. Put it in app/.env"
                        + " (see .env.example there) or set it in your environment."));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Same rules as the exercises' build: trimmed lines, # comments, split at the first =. */
    private static Map<String, String> readDotEnv(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 0) {
                    continue;
                }
                values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
            return values;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Source(String name, Map<String, String> values) {
    }
}

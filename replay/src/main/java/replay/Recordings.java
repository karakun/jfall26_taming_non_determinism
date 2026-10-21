package replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The recorded answers of the real model, grouped by request: for each system prompt and input, the
 * settings it was recorded with and every answer it gave. Loaded once, never changed.
 */
public final class Recordings {

    /** What identifies a request: the exact system prompt and input, nothing normalised. */
    public record RequestKey(String systemPrompt, String input) {

        static Optional<RequestKey> of(JsonNode request) {
            JsonNode messages = request.path("messages");
            if (!messages.isArray() || messages.size() != 2) {
                return Optional.empty();
            }
            String system = null;
            String user = null;
            for (JsonNode message : messages) {
                if (!message.path("content").isTextual()) {
                    return Optional.empty();
                }
                switch (message.path("role").asText()) {
                    case "system" -> system = message.get("content").asText();
                    case "user" -> user = message.get("content").asText();
                    default -> { return Optional.empty(); }
                }
            }
            return system == null || user == null ? Optional.empty() : Optional.of(new RequestKey(system, user));
        }
    }

    /** The settings a request must share with the recording to be answered from it. */
    public record Settings(JsonNode model, JsonNode temperature, JsonNode maxTokens, JsonNode topP,
                           JsonNode responseFormat) {

        static Settings of(JsonNode request) {
            return new Settings(request.path("model"), request.path("temperature"), request.path("max_tokens"),
                    request.path("top_p"), request.path("response_format"));
        }

        /** The first setting that differs from the recorded one; absent settings count as "not set". */
        Optional<Difference> firstDifference(Settings recorded) {
            Map<String, JsonNode[]> pairs = new LinkedHashMap<>();
            pairs.put("model", new JsonNode[] {model, recorded.model});
            pairs.put("temperature", new JsonNode[] {temperature, recorded.temperature});
            pairs.put("max_tokens", new JsonNode[] {maxTokens, recorded.maxTokens});
            pairs.put("top_p", new JsonNode[] {topP, recorded.topP});
            pairs.put("response_format", new JsonNode[] {responseFormat, recorded.responseFormat});
            return pairs.entrySet().stream()
                    .filter(pair -> !same(pair.getValue()[0], pair.getValue()[1]))
                    .map(pair -> new Difference(pair.getKey(), show(pair.getValue()[0]), show(pair.getValue()[1])))
                    .findFirst();
        }

        private static boolean same(JsonNode a, JsonNode b) {
            if (a.isNumber() && b.isNumber()) {
                return a.decimalValue().compareTo(b.decimalValue()) == 0;
            }
            return a.equals(b);
        }

        private static String show(JsonNode value) {
            return value.isMissingNode() ? "not set" : shorten(value.isTextual() ? value.asText() : value.toString(), 80);
        }
    }

    /** A setting whose value differs from the recording's. */
    public record Difference(String setting, String requested, String recorded) {

        @Override
        public String toString() {
            return setting + " is " + requested + ", but the recording's is " + recorded;
        }
    }

    /** One recorded answer: the HTTP status and the body the gateway returned. */
    public record Answer(int status, ObjectNode body) {}

    /** Everything recorded for one request. */
    public record Pool(Settings settings, List<Answer> answers) {}

    /** What the replay sends back: a recorded answer, or a refusal explaining what wasn't recorded. */
    public record Reply(int status, ObjectNode body, Optional<String> refusal) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<RequestKey, Pool> pools;
    private final Set<String> systemPrompts;
    private final int fileCount;
    private final int skippedCount;

    private Recordings(Map<RequestKey, Pool> pools, int fileCount, int skippedCount) {
        this.pools = Map.copyOf(pools);
        this.systemPrompts = pools.keySet().stream().map(RequestKey::systemPrompt).collect(Collectors.toSet());
        this.fileCount = fileCount;
        this.skippedCount = skippedCount;
    }

    /** Reads every {@code *.jsonl} file in {@code dir}, in the format the LLM recorder writes. */
    public static Recordings load(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new StartupException("No recordings folder at " + dir.toAbsolutePath().normalize()
                    + ". The replay answers from the *.jsonl files there.");
        }
        Map<RequestKey, List<Answer>> answers = new LinkedHashMap<>();
        Map<RequestKey, Settings> settings = new HashMap<>();
        Map<RequestKey, String> firstFile = new HashMap<>();
        int skipped = 0;
        List<Path> files = recordingFiles(dir);
        for (Path file : files) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                String where = file.getFileName() + ":" + (i + 1) + ": ";
                JsonNode line = parse(lines.get(i), where);
                if (!line.path("status").isInt()) {
                    throw new StartupException(where + "no status");
                }
                JsonNode request = line.path("request");
                if (!request.isObject()) {
                    throw new StartupException(where + "no request");
                }
                RequestKey key = RequestKey.of(request).orElseThrow(() -> new StartupException(where
                        + (hasRole(request, "system") ? "no user message" : "no system message")));
                JsonNode response = line.path("response");
                if (!response.isObject()) {
                    skipped++; // a network error: the gateway was never reached, so it says nothing about the model
                    continue;
                }
                Settings recorded = Settings.of(request);
                Settings earlier = settings.putIfAbsent(key, recorded);
                if (earlier != null) {
                    Optional<Difference> difference = recorded.firstDifference(earlier);
                    if (difference.isPresent()) {
                        throw new StartupException(firstFile.get(key) + " and " + file.getFileName()
                                + " record the same input with different " + difference.get().setting()
                                + "; the replay can't tell which to use.");
                    }
                }
                firstFile.putIfAbsent(key, file.getFileName().toString());
                answers.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new Answer(line.get("status").asInt(), (ObjectNode) response));
            }
        }
        if (answers.isEmpty()) {
            throw new StartupException("No recorded answers in " + dir.toAbsolutePath().normalize() + ".");
        }
        Map<RequestKey, Pool> pools = new LinkedHashMap<>();
        answers.forEach((key, list) -> pools.put(key, new Pool(settings.get(key), List.copyOf(list))));
        return new Recordings(pools, files.size(), skipped);
    }

    /**
     * Answers a request with one of the answers recorded for it, drawn at random, or refuses it with the
     * first thing that doesn't match a recording.
     */
    public Reply answer(JsonNode request, Random random) {
        Optional<RequestKey> key = RequestKey.of(request);
        if (key.isEmpty()) {
            return refuse("The replay only answers chat completions with one system and one user message.");
        }
        Pool pool = pools.get(key.get());
        if (pool == null) {
            if (!systemPrompts.contains(key.get().systemPrompt())) {
                return refuse("No recording for this system prompt (it starts \"" + shorten(key.get().systemPrompt(), 60)
                        + "\"). The replay only answers the guardrail as it was recorded; was the system prompt changed?");
            }
            return refuse("No recording for the input \"" + shorten(key.get().input(), 60)
                    + "\". The replay only answers the inputs that were recorded; was a contract's input changed or added?");
        }
        Optional<Difference> difference = Settings.of(request).firstDifference(pool.settings());
        if (difference.isPresent()) {
            return refuse("The request's " + difference.get()
                    + ". The replay only answers the guardrail with the settings it was recorded with.");
        }
        Answer answer = pool.answers().get(random.nextInt(pool.answers().size()));
        ObjectNode body = answer.body().deepCopy();
        if (body.has("id")) {
            body.put("id", "chatcmpl-replay-" + UUID.randomUUID());
        }
        if (body.has("created")) {
            body.put("created", Instant.now().getEpochSecond());
        }
        return new Reply(answer.status(), body, Optional.empty());
    }

    public int inputCount() {
        return pools.size();
    }

    public int answerCount() {
        return pools.values().stream().mapToInt(pool -> pool.answers().size()).sum();
    }

    public int fileCount() {
        return fileCount;
    }

    /** Recorded network errors, left out because the gateway never answered. */
    public int skippedCount() {
        return skippedCount;
    }

    Map<RequestKey, Pool> pools() {
        return pools;
    }

    private static Reply refuse(String message) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("error").put("type", "no_recording").put("message", message);
        return new Reply(400, body, Optional.of(message));
    }

    private static List<Path> recordingFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new StartupException(file.getFileName() + ": can't be read (" + e.getMessage() + ")");
        }
    }

    private static JsonNode parse(String line, String where) {
        try {
            return JSON.readTree(line);
        } catch (JsonProcessingException e) {
            throw new StartupException(where + "not JSON");
        }
    }

    private static boolean hasRole(JsonNode request, String role) {
        for (JsonNode message : request.path("messages")) {
            if (role.equals(message.path("role").asText())) {
                return true;
            }
        }
        return false;
    }

    static String shorten(String text, int length) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= length ? oneLine : oneLine.substring(0, length) + "…";
    }
}

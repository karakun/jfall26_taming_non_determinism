package replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Answers like the guardrail's model, from answers it gave when it was run many times beforehand.
 * Speaks the OpenAI chat-completions protocol on localhost, so Punit calls it like any other model.
 */
public final class ReplayServer {

    static final int DEFAULT_PORT = 8090;
    static final Set<String> ENDPOINTS = Set.of("/v1/chat/completions", "/chat/completions");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Recordings recordings;
    private final Random random;
    private final HttpServer server;

    public ReplayServer(Recordings recordings, long seed, int port) throws IOException {
        this.recordings = recordings;
        this.random = new Random(seed);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        ((ExecutorService) server.getExecutor()).shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public static String endpoint(int port) {
        return "http://localhost:" + port + "/v1/chat/completions";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/") && method.equals("GET")) {
                send(exchange, 200, "text/plain; charset=utf-8",
                        "Replay of recorded model behaviour. Endpoint: " + endpoint(port()) + "\n");
            } else if (!ENDPOINTS.contains(path)) {
                send(exchange, 404, error("not_found", "The replay answers at /v1/chat/completions."));
            } else if (!method.equals("POST")) {
                exchange.getResponseHeaders().add("Allow", "POST");
                send(exchange, 405, error("method_not_allowed", "Send a POST with a chat completion."));
            } else {
                answer(exchange);
            }
        }
    }

    private void answer(HttpExchange exchange) throws IOException {
        JsonNode request;
        try {
            request = JSON.readTree(exchange.getRequestBody());
        } catch (IOException e) {
            send(exchange, 400, error("invalid_request", "The request body isn't JSON."));
            return;
        }
        if (request == null || !request.has("model") || !request.has("messages")) {
            send(exchange, 400, error("invalid_request", "A chat completion needs \"model\" and \"messages\"."));
            return;
        }
        Recordings.Reply reply;
        synchronized (random) {
            reply = recordings.answer(request, random);
        }
        reply.refusal().ifPresent(message ->
                System.out.println(LocalTime.now().format(CLOCK) + " refused: " + message));
        send(exchange, reply.status(), reply.body());
    }

    private static ObjectNode error(String type, String message) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("error").put("type", type).put("message", message);
        return body;
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        send(exchange, status, "application/json", JSON.writeValueAsString(body));
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // Start-up: options, then the recordings, then the server; every problem ends in one line.

    record Options(int port, long seed, Path recordings) {

        static Options parse(String[] args, Map<String, String> environment) {
            String port = environment.get("REPLAY_PORT");
            String seed = environment.get("REPLAY_SEED");
            String recordings = environment.get("REPLAY_RECORDINGS");
            for (int i = 0; i < args.length; i++) {
                String value = i + 1 < args.length ? args[i + 1] : null;
                switch (args[i]) {
                    case "--port" -> port = value;
                    case "--seed" -> seed = value;
                    case "--recordings" -> recordings = value;
                    default -> throw unknown(args[i]);
                }
                if (value == null) {
                    throw unknown(args[i] + " without a value");
                }
                i++;
            }
            return new Options(
                    port == null ? DEFAULT_PORT : number(port, "--port"),
                    seed == null ? System.nanoTime() : number(seed, "--seed"),
                    Path.of(recordings == null ? "recordings" : recordings));
        }

        private static int number(String value, String option) {
            try {
                return Math.toIntExact(Long.parseLong(value.trim()));
            } catch (NumberFormatException | ArithmeticException e) {
                throw unknown(option + " " + value);
            }
        }

        private static StartupException unknown(String what) {
            return new StartupException("Unknown option " + what + ". Options: --port <n>, --seed <n>, --recordings <dir>.");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Options options;
        Recordings recordings;
        ReplayServer replay;
        try {
            options = Options.parse(args, System.getenv());
            recordings = Recordings.load(options.recordings());
        } catch (StartupException e) {
            exit(e.getMessage());
            return;
        }
        try {
            replay = new ReplayServer(recordings, options.seed(), options.port());
        } catch (BindException e) {
            exit(portTaken(options.port()));
            return;
        } catch (IOException e) {
            exit("The replay couldn't start: " + e.getMessage());
            return;
        }
        replay.start();
        System.out.println("Replay: " + endpoint(replay.port()) + " — " + recordings.inputCount() + " inputs, "
                + recordings.answerCount() + " recorded answers from " + recordings.fileCount() + " files (seed "
                + options.seed() + ")"
                + (recordings.skippedCount() > 0 ? ", " + recordings.skippedCount() + " network errors skipped" : ""));

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            replay.stop();
            stopped.countDown();
        }));
        stopped.await();
    }

    static String portTaken(int port) {
        return "Port " + port + " is already in use. Start the replay on another port with ./gradlew run --args=\"--port "
                + (port + 1) + "\", and set MAVAI_LLM_ENDPOINT in exercises/part1/.env to " + endpoint(port + 1) + ".";
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(1);
    }
}

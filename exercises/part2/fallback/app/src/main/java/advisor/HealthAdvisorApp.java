package advisor;

import advisor.Advisor.AdvisorConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The fictional Health Advisor used in the workshop's demos: one page and two JSON endpoints.
 * Start it with {@code ./gradlew run} in this folder.
 *
 * This version includes LLM provider fallback support (Part 2 exercise).
 */
public class HealthAdvisorApp {

    // Fallback gateway instance (shared across requests)
    private static FallbackGateway fallbackGateway;

    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_INPUT = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        try {
            start(args);
        } catch (StartupException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void start(String[] args) {
        int port = DEFAULT_PORT;
        boolean share = false;
        for (String arg : args) {
            if (arg.equals("--share")) {
                share = true;
            } else if (arg.startsWith("--port=")) {
                port = parsePort(arg.substring("--port=".length()));
            } else {
                throw new StartupException("Unknown option " + arg + ". Use --port=<number> or --share.");
            }
        }

        // Checked in this order, so the first thing to fix is the first thing reported.
        Path appDir = Path.of("").toAbsolutePath();
        // Guardrail config is in the part1 folder (relative to this part2 app location)
        GuardrailConfig guardrail = GuardrailConfig.load(appDir.resolve("../../../part1/guardrail").normalize(), appDir);
        AdvisorConfig advisorConfig = AdvisorConfig.load(appDir, guardrail.model());
        Settings settings = Settings.load(appDir);

        // Part 2: Load provider configuration and create fallback gateway
        // Merge .env file with system environment (system env takes precedence)
        Map<String, String> environment = mergedEnvironment(appDir);
        ProviderConfig providerConfig = ProviderConfig.load(appDir, environment);
        fallbackGateway = new FallbackGateway(providerConfig);

        // Set system prompts for fallback behavior and UI display
        fallbackGateway.setPrimarySystemPrompt(advisorConfig.systemPrompt());
        fallbackGateway.setFallbackSystemPrompt(advisorConfig.fallbackSystemPrompt());

        // Use FallbackGateway for actual requests (enables provider fallback)
        Advisor advisor = new Advisor(fallbackGateway, guardrail, advisorConfig);

        HttpServer server = bind(port, share);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        String page = page(advisorConfig.model(), guardrail.model());
        server.createContext("/", exchange -> page(exchange, page));
        server.createContext("/api/advise", exchange -> api(exchange, "question", advisor::advise));
        server.createContext("/api/check", exchange -> api(exchange, "advice", advisor::check));

        // Part 2: Fallback control endpoints
        server.createContext("/api/fallback/toggle-provider", HealthAdvisorApp::toggleProviderFailure);
        server.createContext("/api/fallback/toggle-guardrail", HealthAdvisorApp::toggleGuardrailFailure);
        server.createContext("/api/fallback/status", HealthAdvisorApp::fallbackStatus);

        server.start();

        System.out.println(share
                ? "Health Advisor: http://" + networkAddress() + ":" + port + " (shared on the network; Ctrl+C to stop)"
                : "Health Advisor: http://localhost:" + port + " (Ctrl+C to stop)");
        System.out.println("Fallback enabled: primary=" + providerConfig.primary().name()
                + ", fallbacks=" + providerConfig.fallback().size());
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new StartupException("--port needs a number between 1 and 65535, not " + value + ".");
    }

    private static HttpServer bind(int port, boolean share) {
        InetSocketAddress address = share ? new InetSocketAddress(port) : new InetSocketAddress("localhost", port);
        try {
            return HttpServer.create(address, 0);
        } catch (BindException e) {
            throw new StartupException("Port " + port + " is in use. Start with: ./gradlew run --args=--port="
                    + (port + 1));
        } catch (IOException e) {
            throw new StartupException("Can't listen on port " + port + ": " + e.getMessage());
        }
    }

    /** The page, with the names of the two models filled in. */
    private static String page(String advisorModel, String guardrailModel) {
        try (InputStream html = HealthAdvisorApp.class.getResourceAsStream("/advisor/index.html")) {
            return new String(html.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{advisor-model}}", escape(advisorModel))
                    .replace("{{guardrail-model}}", escape(guardrailModel));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void page(HttpExchange exchange, String page) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", page);
    }

    /** Reads one text field from the JSON body, hands it to the advisor and answers with the result as JSON. */
    private static void api(HttpExchange exchange, String field, Function<String, Object> action) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }
        String input = readField(exchange, field);
        if (input == null || input.isEmpty() || input.length() > MAX_INPUT) {
            send(exchange, 400, "application/json",
                    JSON.writeValueAsString(Map.of("error", "Please enter between 1 and " + MAX_INPUT + " characters.")));
            return;
        }
        send(exchange, 200, "application/json", JSON.writeValueAsString(action.apply(input)));
    }

    private static String readField(HttpExchange exchange, String field) {
        try {
            JsonNode value = JSON.readTree(exchange.getRequestBody()).get(field);
            return value != null && value.isTextual() ? value.asText().strip() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The address others in the room can use: this machine's first non-loopback IPv4 address. */
    private static String networkAddress() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(HealthAdvisorApp::isUsable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
                    .map(InetAddress::getHostAddress)
                    .findFirst()
                    .orElse("localhost");
        } catch (SocketException e) {
            return "localhost";
        }
    }

    private static boolean isUsable(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp() && !networkInterface.isLoopback() && !networkInterface.isVirtual();
        } catch (SocketException e) {
            return false;
        }
    }

    /** Part 2: Toggle failure injection for a specific provider */
    private static void toggleProviderFailure(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }
        try {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            int providerIndex = body.get("index").asInt();
            boolean newState = fallbackGateway.toggleProviderFailure(providerIndex);
            send(exchange, 200, "application/json", JSON.writeValueAsString(Map.of(
                    "index", providerIndex,
                    "failed", newState
            )));
        } catch (Exception e) {
            send(exchange, 400, "application/json", JSON.writeValueAsString(Map.of("error", "Invalid request body")));
        }
    }

    /** Part 2: Toggle failure injection for guardrail (LLM 2) */
    private static void toggleGuardrailFailure(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }
        boolean newState = fallbackGateway.toggleGuardrailFailure();
        send(exchange, 200, "application/json", JSON.writeValueAsString(Map.of(
                "guardrailFailed", newState
        )));
    }

    /** Part 2: Get current fallback status */
    private static void fallbackStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use GET.")));
            return;
        }
        send(exchange, 200, "application/json", JSON.writeValueAsString(fallbackGateway.getStatus()));
    }

    /** Merge .env file with system environment. System env takes precedence. */
    private static Map<String, String> mergedEnvironment(Path appDir) {
        Map<String, String> merged = new HashMap<>();
        // First add .env values
        Path dotEnv = appDir.resolve(".env");
        if (Files.isRegularFile(dotEnv)) {
            try {
                for (String line : Files.readAllLines(dotEnv)) {
                    String trimmed = line.trim();
                    int equals = trimmed.indexOf('=');
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 0) {
                        continue;
                    }
                    merged.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
                }
            } catch (IOException e) {
                // Ignore .env read errors
            }
        }
        // System env overrides .env
        merged.putAll(System.getenv());
        return merged;
    }
}

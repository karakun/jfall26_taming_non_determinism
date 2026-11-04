package advisor;

import advisor.Advisor.AdvisorConfig;
import advisor.Advisor.AdviceResult;
import advisor.Advisor.CheckResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import wal.FileWriteAheadLog;
import wal.WalEntry;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;

import tiberius.HealthAdvisorSecurityScanner;
import tiberius.HealthcareBuffs;
import tiberius.SecurityScanReport;
import tiberius.SecurityScanResult;
import io.tiberius.core.buff.Buff;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The fictional Health Advisor used in the workshop's demos: one page and two JSON endpoints.
 * Start it with {@code ./gradlew run} in this folder.
 *
 * This version includes Write-Ahead-Log (WAL) support (Part 3 exercise).
 * Every LLM interaction is logged to the WAL before the response is shown.
 */
public class HealthAdvisorApp {

    // Fallback gateway instance (shared across requests)
    private static FallbackGateway fallbackGateway;

    // Part 3: Write-Ahead-Log for durable interaction capture
    private static FileWriteAheadLog wal;
    private static Advisor advisor;

    // Part 4: Tiberius Security Scanner
    private static HealthAdvisorSecurityScanner securityScanner;
    private static SecurityScanReport currentScanReport;
    private static final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();

    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_INPUT = 500;
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

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
        // Guardrail config is in the part1 folder (relative to this part4 app location)
        GuardrailConfig guardrail = GuardrailConfig.load(appDir.resolve("../../part1/guardrail").normalize(), appDir);
        AdvisorConfig advisorConfig = AdvisorConfig.load(appDir, guardrail.model());
        Settings settings = Settings.load(appDir);

        // Part 2: Load provider configuration and create fallback gateway
        // Merge .env file with system environment (system env takes precedence)
        Map<String, String> environment = mergedEnvironment(appDir);
        ProviderConfig providerConfig = ProviderConfig.load(appDir, environment);
        fallbackGateway = new FallbackGateway(providerConfig);

        // Use FallbackGateway for actual requests (enables provider fallback)
        advisor = new Advisor(fallbackGateway, guardrail, advisorConfig);

        // Part 3: Initialize Write-Ahead-Log
        Path walPath = appDir.resolve("wal-data/interactions.jsonl");
        wal = new FileWriteAheadLog(walPath);
        System.out.println("WAL initialized: " + walPath);

        // Part 4: Initialize Security Scanner
        securityScanner = new HealthAdvisorSecurityScanner("http://localhost:" + port, wal);
        System.out.println("Tiberius Security Scanner initialized");

        HttpServer server = bind(port, share);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        String page = page(advisorConfig.model(), guardrail.model());
        server.createContext("/", exchange -> page(exchange, page));
        server.createContext("/api/advise", HealthAdvisorApp::handleAdvise);
        server.createContext("/api/check", HealthAdvisorApp::handleCheck);

        // Part 2: Fallback control endpoints
        server.createContext("/api/fallback/toggle-provider", HealthAdvisorApp::toggleProviderFailure);
        server.createContext("/api/fallback/toggle-guardrail", HealthAdvisorApp::toggleGuardrailFailure);
        server.createContext("/api/fallback/status", HealthAdvisorApp::fallbackStatus);

        // Part 3: WAL endpoints
        server.createContext("/api/wal/list", HealthAdvisorApp::walList);
        server.createContext("/api/wal/retry", HealthAdvisorApp::walRetry);

        // Part 4: Security scan endpoints
        server.createContext("/api/security/scan/start", HealthAdvisorApp::startSecurityScan);
        server.createContext("/api/security/scan/stop", HealthAdvisorApp::stopSecurityScan);
        server.createContext("/api/security/scan/status", HealthAdvisorApp::securityScanStatus);

        server.start();

        System.out.println(share
                ? "Health Advisor: http://" + networkAddress() + ":" + port + " (shared on the network; Ctrl+C to stop)"
                : "Health Advisor: http://localhost:" + port + " (Ctrl+C to stop)");
        System.out.println("Fallback enabled: primary=" + providerConfig.primary().name()
                + ", fallbacks=" + providerConfig.fallback().size());
        System.out.println("WAL entries: " + wal.count());
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

    // ══════════════════════════════════════════════════════════════════════════════
    // Part 3: WAL-integrated handlers
    // ══════════════════════════════════════════════════════════════════════════════

    /** Handles /api/advise - gets advice and logs the full interaction to WAL */
    private static void handleAdvise(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }
        String question = readField(exchange, "question");
        if (question == null || question.isEmpty() || question.length() > MAX_INPUT) {
            send(exchange, 400, "application/json",
                    JSON.writeValueAsString(Map.of("error", "Please enter between 1 and " + MAX_INPUT + " characters.")));
            return;
        }

        // Get advice from LLM
        long startTime = System.currentTimeMillis();
        AdviceResult result = advisor.advise(question);

        // Log to WAL BEFORE returning response (WAL principle: capture before acting)
        if (result.advice() != null) {
            // Success: check with guardrail and log
            CheckResult checkResult = advisor.check(result.advice());
            logToWal(question, result.advice(), checkResult);
        } else {
            // Error: log the failure
            logErrorToWal(question, result.error(), System.currentTimeMillis() - startTime);
        }

        send(exchange, 200, "application/json", JSON.writeValueAsString(result));
    }

    /** Handles /api/check - checks advice and logs to WAL */
    private static void handleCheck(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }
        String advice = readField(exchange, "advice");
        if (advice == null || advice.isEmpty() || advice.length() > MAX_INPUT) {
            send(exchange, 400, "application/json",
                    JSON.writeValueAsString(Map.of("error", "Please enter between 1 and " + MAX_INPUT + " characters.")));
            return;
        }

        CheckResult result = advisor.check(advice);
        send(exchange, 200, "application/json", JSON.writeValueAsString(result));
    }

    /** Logs an interaction to the WAL */
    private static void logToWal(String request, String response, CheckResult checkResult) {
        try {
            String verdict = switch (checkResult.verdict().state()) {
                case ACCEPTED -> WalEntry.VERDICT_SAFE;
                case REJECTED -> WalEntry.VERDICT_HARMFUL;
                case UNREADABLE, GATEWAY_ERROR -> WalEntry.VERDICT_ERROR;
            };

            String provider = fallbackGateway.getStatus().get("lastUsedProvider").toString();
            String guardrailType = checkResult.guardrailType().name();

            WalEntry entry = WalEntry.create(
                    UUID.randomUUID().toString(),
                    request,
                    response,
                    verdict,
                    provider,
                    guardrailType,
                    checkResult.verdict().latencyMs()
            );

            wal.append(entry);
            System.out.println("WAL: logged entry " + entry.entryId() + " [" + verdict + "]");
        } catch (Exception e) {
            System.err.println("Failed to log to WAL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Logs an error/failure to the WAL (when LLM call fails) */
    private static void logErrorToWal(String request, String errorMessage, long latencyMs) {
        try {
            String provider = fallbackGateway.getStatus().get("lastUsedProvider").toString();

            WalEntry entry = WalEntry.create(
                    UUID.randomUUID().toString(),
                    request,
                    errorMessage != null ? errorMessage : "All providers unavailable",
                    WalEntry.VERDICT_ERROR,
                    provider,
                    "NONE",
                    latencyMs
            );

            wal.append(entry);
            System.out.println("WAL: logged error entry " + entry.entryId() + " [ERROR]");
        } catch (Exception e) {
            System.err.println("Failed to log error to WAL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Part 3: List WAL entries (newest first) */
    private static void walList(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use GET.")));
            return;
        }

        // Get limit from query parameter, default to 50
        String query = exchange.getRequestURI().getQuery();
        int limit = 50;
        if (query != null && query.startsWith("limit=")) {
            try {
                limit = Integer.parseInt(query.substring(6));
            } catch (NumberFormatException ignored) {}
        }

        List<WalEntry> entries = wal.list(limit);
        System.out.println("WAL list: returning " + entries.size() + " entries (total: " + wal.count() + ")");
        String response = JSON.writeValueAsString(Map.of(
                "entries", entries,
                "total", wal.count()
        ));
        send(exchange, 200, "application/json", response);
    }

    /** Part 3: Retry a WAL entry - re-run the request through the pipeline */
    private static void walRetry(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }

        String entryId = readField(exchange, "entryId");
        if (entryId == null || entryId.isEmpty()) {
            send(exchange, 400, "application/json",
                    JSON.writeValueAsString(Map.of("error", "Missing entryId")));
            return;
        }

        // Find the original entry
        var originalEntry = wal.findById(entryId);
        if (originalEntry.isEmpty()) {
            send(exchange, 404, "application/json",
                    JSON.writeValueAsString(Map.of("error", "Entry not found: " + entryId)));
            return;
        }

        WalEntry original = originalEntry.get();

        // Re-run the request through the pipeline
        AdviceResult adviceResult = advisor.advise(original.request());

        // Check the new advice
        CheckResult checkResult = null;
        if (adviceResult.advice() != null) {
            checkResult = advisor.check(adviceResult.advice());
            // Log the retry to WAL as a new entry
            logToWal(original.request(), adviceResult.advice(), checkResult);
        }

        send(exchange, 200, "application/json", JSON.writeValueAsString(Map.of(
                "originalEntryId", entryId,
                "adviceResult", adviceResult,
                "checkResult", checkResult != null ? checkResult : Map.of()
        )));
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

    // ══════════════════════════════════════════════════════════════════════════════
    // Part 4: Security Scan Handlers
    // ══════════════════════════════════════════════════════════════════════════════

    /** Part 4: Start a security scan */
    private static void startSecurityScan(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }

        // Check if a scan is already running
        if (securityScanner.isRunning()) {
            send(exchange, 400, "application/json", JSON.writeValueAsString(Map.of(
                    "error", "A scan is already running",
                    "status", "running"
            )));
            return;
        }

        // Parse scan configuration from request body
        String category = "ALL";
        String buffName = null;
        try {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            if (body.has("category")) {
                category = body.get("category").asText();
            }
            if (body.has("buff")) {
                buffName = body.get("buff").asText();
            }
        } catch (Exception ignored) {
            // Use defaults
        }

        // Determine which probes to run
        List<HealthAdvisorSecurityScanner.AttackProbe> probes = new ArrayList<>();
        switch (category.toUpperCase()) {
            case "JAILBREAK" -> probes.addAll(HealthAdvisorSecurityScanner.JAILBREAK_PROBES);
            case "PROMPT_INJECTION" -> probes.addAll(HealthAdvisorSecurityScanner.PROMPT_INJECTION_PROBES);
            case "MULTI_TURN" -> probes.addAll(HealthAdvisorSecurityScanner.MULTI_TURN_PROBES);
            default -> {
                probes.addAll(HealthAdvisorSecurityScanner.JAILBREAK_PROBES);
                probes.addAll(HealthAdvisorSecurityScanner.PROMPT_INJECTION_PROBES);
                probes.addAll(HealthAdvisorSecurityScanner.MULTI_TURN_PROBES);
            }
        }

        // Determine which buff to apply
        Buff buff = null;
        if (buffName != null && !buffName.isEmpty()) {
            buff = findBuffByName(buffName);
        }

        final Buff finalBuff = buff;
        final List<HealthAdvisorSecurityScanner.AttackProbe> finalProbes = probes;
        final int expectedProbeCount = probes.size();

        // Create report immediately so status updates work during scan
        currentScanReport = new SecurityScanReport();
        currentScanReport.setExpectedProbes(expectedProbeCount);

        // Start scan in background
        scanExecutor.submit(() -> {
            securityScanner.scan(finalProbes, finalBuff, currentScanReport, result -> {
                System.out.println("Scan: " + result.probeName() + " -> " + result.guardrailVerdict());
            });
        });

        send(exchange, 200, "application/json", JSON.writeValueAsString(Map.of(
                "status", "started",
                "category", category,
                "buff", buffName != null ? buffName : "none",
                "probeCount", expectedProbeCount
        )));
    }

    /** Part 4: Stop the current security scan */
    private static void stopSecurityScan(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use POST.")));
            return;
        }

        if (!securityScanner.isRunning()) {
            send(exchange, 400, "application/json", JSON.writeValueAsString(Map.of(
                    "error", "No scan is running",
                    "status", "idle"
            )));
            return;
        }

        securityScanner.cancel();

        send(exchange, 200, "application/json", JSON.writeValueAsString(Map.of(
                "status", "cancelled"
        )));
    }

    /** Part 4: Get security scan status */
    private static void securityScanStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "application/json", JSON.writeValueAsString(Map.of("error", "Use GET.")));
            return;
        }

        Map<String, Object> status = new HashMap<>();
        status.put("running", securityScanner.isRunning());

        if (currentScanReport != null) {
            status.putAll(currentScanReport.toSummary());
            status.put("results", currentScanReport.getResults());
        } else {
            status.put("totalProbes", 0);
            status.put("successfulAttacks", 0);
            status.put("blockedAttacks", 0);
            status.put("attackSuccessRate", 0.0);
            status.put("results", List.of());
        }

        // Add available buffs
        status.put("availableBuffs", HealthcareBuffs.all().stream()
                .map(Buff::getName)
                .toList());

        // Add available categories
        status.put("availableCategories", List.of("ALL", "JAILBREAK", "PROMPT_INJECTION", "MULTI_TURN"));

        send(exchange, 200, "application/json", JSON.writeValueAsString(status));
    }

    /** Find a buff by name */
    private static Buff findBuffByName(String name) {
        return HealthcareBuffs.all().stream()
                .filter(b -> b.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}

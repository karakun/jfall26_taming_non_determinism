package fallback;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Simple HTTP server for the Failure Injection Simulation visualization.
 *
 * Serves a static HTML page that simulates the Health Advisor pipeline
 * with configurable Bernoulli and ARTA failure patterns.
 *
 * <h2>Usage</h2>
 * <pre>
 * java fallback.FailureSimulationServer 8081
 * </pre>
 *
 * Then open http://localhost:8081 in your browser.
 */
public class FailureSimulationServer {

    private final HttpServer server;
    private final Path simulationDir;

    public FailureSimulationServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.simulationDir = Path.of("fallback/simulation");

        server.createContext("/", this::handleRequest);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        FAILURE INJECTION SIMULATOR                           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  URL: http://localhost:%-38d ║%n", server.getAddress().getPort());
        System.out.println("║                                                              ║");
        System.out.println("║  This is a browser-based simulation of the Health Advisor   ║");
        System.out.println("║  pipeline with failure injection. No real LLM calls are     ║");
        System.out.println("║  made - everything is simulated in JavaScript.              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Configure:                                                  ║");
        System.out.println("║  - Failure Probability (0-100%)                              ║");
        System.out.println("║  - ARTA Autocorrelation (0-1) for bursty failures            ║");
        System.out.println("║  - Request Rate (requests per second)                        ║");
        System.out.println("║  - Naive Bypass Rate (how often naive guardrail fails)       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();
    }

    public void stop() {
        server.stop(0);
        System.out.println("\nSimulation server stopped.");
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // Default to index.html
        if (path.equals("/") || path.equals("/index.html")) {
            serveFile(exchange, "index.html", "text/html");
        } else {
            send404(exchange);
        }
    }

    private void serveFile(HttpExchange exchange, String filename, String contentType) throws IOException {
        Path filePath = simulationDir.resolve(filename);

        if (!Files.exists(filePath)) {
            send404(exchange);
            return;
        }

        byte[] content = Files.readAllBytes(filePath);
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private void send404(HttpExchange exchange) throws IOException {
        String msg = "Not found";
        exchange.sendResponseHeaders(404, msg.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;

        FailureSimulationServer server = new FailureSimulationServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}

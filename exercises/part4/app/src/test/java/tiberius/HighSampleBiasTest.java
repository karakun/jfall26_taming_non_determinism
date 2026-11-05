package tiberius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * High-sample bias test to evaluate the pain_mgmt.race probe with 100 samples.
 *
 * Run with: ./gradlew test --tests "tiberius.HighSampleBiasTest"
 */
@DisplayName("High Sample Bias Test (100 samples)")
class HighSampleBiasTest {

    private static final Logger logger = LoggerFactory.getLogger(HighSampleBiasTest.class);
    private static final String HEALTH_ADVISOR_URL = "http://localhost:8080";
    private static final Path APP_DIR = Path.of("").toAbsolutePath();
    private static final String LLM_MODEL = "karakun-Mistral-Small-4-119B-2603";

    @Test
    @DisplayName("Run pain_mgmt.race probe with 100 samples")
    void runHighSampleBiasTest() {
        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║     HIGH SAMPLE BIAS TEST - 100 SAMPLES PER VARIANT              ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");

        // Get the probe pair
        List<BiasProbePair> pairs = BiasProbeRegistry.getAllPairs();
        BiasProbePair pair = pairs.get(0);

        logger.info("");
        logger.info("=== PROBE PAIR DETAILS ===");
        logger.info("Pair ID: {}", pair.pairId());
        logger.info("Category: {}", pair.category());
        logger.info("Dimension: {}", pair.dimension());
        logger.info("Variant A: {} ({})", pair.variantA(), pair.idA());
        logger.info("Variant B: {} ({})", pair.variantB(), pair.idB());
        logger.info("");
        logger.info("Prompt A: {}", pair.promptA());
        logger.info("");
        logger.info("Prompt B: {}", pair.promptB());
        logger.info("");
        logger.info("Expected Bias: {}", pair.expectedBias());
        logger.info("");

        // Create components
        GatewayGenerator judgeGenerator = new GatewayGenerator(APP_DIR, LLM_MODEL);
        BiasComparator comparator = new BiasComparator(judgeGenerator);
        HealthAdvisorGenerator targetGenerator = new HealthAdvisorGenerator(HEALTH_ADVISOR_URL);

        // Create scanner with 100 samples
        logger.info("=== STARTING 100-SAMPLE SCAN ===");
        logger.info("This will generate 200 LLM calls (100 per variant) + 1 judge call");
        logger.info("");

        long startTime = System.currentTimeMillis();

        PairedBiasScanner scanner = PairedBiasScanner.builder()
                .withTargetGenerator(targetGenerator)
                .withComparator(comparator)
                .samples(100)
                .build();

        // Track progress
        final int[] progressCounter = {0};
        PairedBiasScanner.ScanReport report = scanner.scan(List.of(pair),
                (pairId, current, total, status) -> {
                    progressCounter[0]++;
                    if (progressCounter[0] % 10 == 0 || status.startsWith("Analyzing")) {
                        logger.info("[{}] {}", pairId, status);
                    }
                });

        long duration = System.currentTimeMillis() - startTime;

        // Results
        logger.info("");
        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║                         RESULTS                                   ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
        logger.info("");

        BiasComparator.ComparisonResult result = report.results().get(0);

        logger.info("Duration: {} seconds", duration / 1000.0);
        logger.info("");
        logger.info("┌─────────────────────────────────────────────────────────────────┐");
        logger.info("│ VERDICT: {}                                              │",
                result.biasDetected() ? "BIASED  " : "FAIR    ");
        logger.info("│ Confidence: {}                                               │",
                padRight(result.confidence(), 6));
        logger.info("└─────────────────────────────────────────────────────────────────┘");
        logger.info("");
        logger.info("Explanation: {}", result.explanation());
        logger.info("");

        // Sample responses
        logger.info("=== SAMPLE RESPONSES (first 5 of each variant) ===");
        logger.info("");
        logger.info("--- Variant A ({}) ---", pair.variantA());
        for (int i = 0; i < Math.min(5, result.responsesA().size()); i++) {
            logger.info("[A{}] {}", i + 1, truncate(result.responsesA().get(i), 150));
        }

        logger.info("");
        logger.info("--- Variant B ({}) ---", pair.variantB());
        for (int i = 0; i < Math.min(5, result.responsesB().size()); i++) {
            logger.info("[B{}] {}", i + 1, truncate(result.responsesB().get(i), 150));
        }

        logger.info("");
        logger.info("=== RESPONSE STATISTICS ===");
        logger.info("Total responses collected: {} (A) + {} (B) = {}",
                result.responsesA().size(),
                result.responsesB().size(),
                result.responsesA().size() + result.responsesB().size());

        // Calculate average response length
        double avgLengthA = result.responsesA().stream()
                .mapToInt(String::length)
                .average()
                .orElse(0);
        double avgLengthB = result.responsesB().stream()
                .mapToInt(String::length)
                .average()
                .orElse(0);

        logger.info("Average response length: A={} chars, B={} chars",
                String.format("%.0f", avgLengthA),
                String.format("%.0f", avgLengthB));

        logger.info("");
        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║                    TEST COMPLETE                                  ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        String clean = text.replace("\n", " ").replace("\r", "");
        if (clean.length() <= maxLength) return clean;
        return clean.substring(0, maxLength) + "...";
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}

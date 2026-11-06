package tiberius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Bias test to evaluate the pain_mgmt.race probe.
 *
 * Run with: ./gradlew test --tests "tiberius.HighSampleBiasTest"
 */
@DisplayName("Bias Probe Test")
class HighSampleBiasTest {

    private static final Logger logger = LoggerFactory.getLogger(HighSampleBiasTest.class);
    private static final String HEALTH_ADVISOR_URL = "http://localhost:8080";
    private static final Path APP_DIR = Path.of("").toAbsolutePath();
    private static final String LLM_MODEL = "karakun-Mistral-Small-4-119B-2603";

    @Test
    @DisplayName("Run pain_mgmt.race bias probe")
    void runHighSampleBiasTest() {
        // Get the probe pair
        List<BiasProbePair> pairs = BiasProbeRegistry.getAllPairs();
        BiasProbePair pair = pairs.get(0);

        logger.info("Pair ID: {}", pair.pairId());
        logger.info("Category: {}", pair.category());

        // Create components
        GatewayGenerator judgeGenerator = new GatewayGenerator(APP_DIR, LLM_MODEL);
        BiasComparator comparator = new BiasComparator(judgeGenerator);
        HealthAdvisorGenerator targetGenerator = new HealthAdvisorGenerator(HEALTH_ADVISOR_URL);

        PairedBiasScanner scanner = PairedBiasScanner.builder()
                .withTargetGenerator(targetGenerator)
                .withComparator(comparator)
                .samples(1)
                .build();

        PairedBiasScanner.ScanReport report = scanner.scan(List.of(pair),
                (pairId, current, total, status) -> {
                    logger.info("[{}] {}", pairId, status);
                });

        BiasComparator.ComparisonResult result = report.results().get(0);

        logger.info("Verdict: {}", result.biasDetected() ? "BIASED" : "FAIR");
        logger.info("Confidence: {}", result.confidence());
    }
}

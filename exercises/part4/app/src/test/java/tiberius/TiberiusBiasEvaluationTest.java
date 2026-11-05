package tiberius;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tiberius.bias.BiasDatasetBuilder;
import tiberius.bias.BiasEvaluationReport;
import tiberius.bias.BiasReportComparator;
import tiberius.bias.TiberiusBiasScanner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Tiberius-based bias evaluation (Weg 2).
 *
 * <p>This test demonstrates the proper Weg 2 architecture:
 * <ol>
 *   <li>BiasDatasetBuilder creates Tiberius DatasetEntry probes with naming convention</li>
 *   <li>TiberiusBiasScanner wraps Tiberius scanDataset() with multi-sample support</li>
 *   <li>BiasReportComparator post-processes results with LLM-as-a-Judge</li>
 *   <li>BiasEvaluationReport contains final verdicts</li>
 * </ol>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Health Advisor app running: ./gradlew run</li>
 *   <li>LLM endpoint configured</li>
 * </ul>
 *
 * <p>Run with: ./gradlew test --tests "tiberius.TiberiusBiasEvaluationTest"
 */
@DisplayName("Tiberius-based Bias Evaluation (Weg 2)")
class TiberiusBiasEvaluationTest {

    private static final Logger logger = LoggerFactory.getLogger(TiberiusBiasEvaluationTest.class);
    private static final String HEALTH_ADVISOR_URL = "http://localhost:8080";
    private static final Path APP_DIR = Path.of("").toAbsolutePath();
    private static final String LLM_MODEL = "karakun-Mistral-Small-4-119B-2603";

    @BeforeAll
    static void setup() {
        logger.info("╔══════════════════════════════════════════════════════════════╗");
        logger.info("║     TIBERIUS-BASED BIAS EVALUATION TEST (Weg 2)              ║");
        logger.info("╚══════════════════════════════════════════════════════════════╝");
        logger.info("App directory: {}", APP_DIR);
        logger.info("Health Advisor URL: {}", HEALTH_ADVISOR_URL);
        logger.info("LLM Model: {}", LLM_MODEL);
    }

    @Test
    @DisplayName("BiasDatasetBuilder creates valid Tiberius dataset")
    void biasDatasetBuilderCreatesValidDataset() {
        // Given: A dataset builder with default probes
        BiasDatasetBuilder builder = BiasDatasetBuilder.withDefaultProbes();

        // When: We build the dataset
        var dataset = builder.build();
        var pairs = builder.getPairs();

        // Then: Dataset should have 2 entries (one pair)
        logger.info("=== DATASET STRUCTURE ===");
        logger.info("Dataset name: {}", dataset.getName());
        logger.info("Total entries: {}", dataset.size());
        logger.info("Probe pairs: {}", pairs.size());

        assertThat(dataset.size()).isEqualTo(2); // One pair = 2 probes
        assertThat(pairs).hasSize(1);

        // Verify naming convention
        var pair = pairs.get(0);
        logger.info("Pair ID: {}", pair.pairId());
        logger.info("  Probe A: {}", pair.idA());
        logger.info("  Probe B: {}", pair.idB());

        assertThat(pair.pairId()).isEqualTo("bias.pain_mgmt.race");
        assertThat(pair.idA()).isEqualTo("bias.pain_mgmt.race.white");
        assertThat(pair.idB()).isEqualTo("bias.pain_mgmt.race.black");
    }

    @Test
    @DisplayName("TiberiusBiasScanner collects multi-sample responses")
    void tiberiusBiasScannerCollectsMultiSampleResponses() {
        // Given: Scanner and dataset
        HealthAdvisorGenerator generator = new HealthAdvisorGenerator(HEALTH_ADVISOR_URL);
        BiasDatasetBuilder datasetBuilder = BiasDatasetBuilder.withDefaultProbes();

        TiberiusBiasScanner scanner = TiberiusBiasScanner.builder()
                .withGenerator(generator)
                .samples(3)  // 3 samples per probe
                .build();

        // When: We scan
        logger.info("=== RUNNING TIBERIUS SCAN (3 samples) ===");
        var report = scanner.scan(datasetBuilder, (sample, total, probeId, status) -> {
            logger.info("[Sample {}/{}] {}: {}", sample, total, probeId, status);
        });

        // Then: Should have collected responses
        logger.info("=== SCAN RESULTS ===");
        logger.info("Total responses: {}", report.totalResponses());
        logger.info("Samples per probe: {}", report.samplesPerProbe());
        logger.info("Cancelled: {}", report.cancelled());

        assertThat(report.totalResponses()).isEqualTo(6); // 2 probes × 3 samples
        assertThat(report.samplesPerProbe()).isEqualTo(3);

        // Verify responses for each variant
        var pair = datasetBuilder.getPairs().get(0);
        assertThat(report.getResponsesA(pair)).hasSize(3);
        assertThat(report.getResponsesB(pair)).hasSize(3);

        logger.info("Responses A (white): {} samples", report.getResponsesA(pair).size());
        logger.info("Responses B (black): {} samples", report.getResponsesB(pair).size());
    }

    @Test
    @DisplayName("Full pipeline: Tiberius scan → Post-processing comparison → Bias verdict")
    void fullPipelineProducesBiasVerdict() {
        // Given: All components
        HealthAdvisorGenerator targetGenerator = new HealthAdvisorGenerator(HEALTH_ADVISOR_URL);
        GatewayGenerator judgeGenerator = new GatewayGenerator(APP_DIR, LLM_MODEL);
        BiasDatasetBuilder datasetBuilder = BiasDatasetBuilder.withDefaultProbes();

        // Step 1: Tiberius scan with multi-sample
        logger.info("=== STEP 1: TIBERIUS SCAN (5 samples) ===");
        TiberiusBiasScanner scanner = TiberiusBiasScanner.builder()
                .withGenerator(targetGenerator)
                .samples(5)
                .build();

        var scanReport = scanner.scan(datasetBuilder, (sample, total, probeId, status) -> {
            logger.info("[Sample {}/{}] {}", sample, total, status);
        });

        logger.info("Collected {} total responses", scanReport.totalResponses());

        // Step 2: Post-processing comparison
        logger.info("=== STEP 2: POST-PROCESSING COMPARISON ===");
        BiasReportComparator comparator = new BiasReportComparator(judgeGenerator);

        BiasEvaluationReport evalReport = comparator.evaluate(scanReport, (current, total, pairId, status) -> {
            logger.info("[{}/{}] {}: {}", current, total, pairId, status);
        });

        // Step 3: Results
        logger.info("=== STEP 3: FINAL RESULTS ===");
        logger.info(evalReport.toSummary());

        // Assertions
        assertThat(evalReport.totalPairs()).isEqualTo(1);
        assertThat(evalReport.errorPairs()).isEqualTo(0);

        // Log the detailed verdict
        var result = evalReport.results().get(0);
        logger.info("=== DETAILED VERDICT ===");
        logger.info("Pair: {}", result.pairId());
        logger.info("Bias Detected: {}", result.biasDetected());
        logger.info("Confidence: {}", result.confidence());
        logger.info("Explanation: {}", result.explanation());
        logger.info("");
        logger.info("Full judgment preview:");
        logger.info("{}", truncate(result.fullJudgment(), 500));
    }

    @Test
    @DisplayName("High-sample evaluation (100 samples) for statistical significance")
    void highSampleEvaluationForStatisticalSignificance() {
        // Given: All components
        HealthAdvisorGenerator targetGenerator = new HealthAdvisorGenerator(HEALTH_ADVISOR_URL);
        GatewayGenerator judgeGenerator = new GatewayGenerator(APP_DIR, LLM_MODEL);
        BiasDatasetBuilder datasetBuilder = BiasDatasetBuilder.withDefaultProbes();

        logger.info("╔══════════════════════════════════════════════════════════════╗");
        logger.info("║     HIGH-SAMPLE BIAS EVALUATION (100 samples)                ║");
        logger.info("╚══════════════════════════════════════════════════════════════╝");

        // Step 1: Tiberius scan with 100 samples
        logger.info("=== COLLECTING 100 SAMPLES PER VARIANT ===");
        logger.info("This will make 200 LLM calls to the target...");

        long startTime = System.currentTimeMillis();

        TiberiusBiasScanner scanner = TiberiusBiasScanner.builder()
                .withGenerator(targetGenerator)
                .samples(100)
                .build();

        final int[] sampleCount = {0};
        var scanReport = scanner.scan(datasetBuilder, (sample, total, probeId, status) -> {
            sampleCount[0]++;
            if (sampleCount[0] % 20 == 0) {
                logger.info("Progress: {} samples collected...", sampleCount[0]);
            }
        });

        long scanDuration = System.currentTimeMillis() - startTime;
        logger.info("Scan completed in {} seconds", scanDuration / 1000.0);
        logger.info("Total responses: {}", scanReport.totalResponses());

        // Step 2: Post-processing
        logger.info("=== SENDING TO LLM JUDGE FOR COMPARISON ===");
        BiasReportComparator comparator = new BiasReportComparator(judgeGenerator);

        long judgeStart = System.currentTimeMillis();
        BiasEvaluationReport evalReport = comparator.evaluate(scanReport, null);
        long judgeDuration = System.currentTimeMillis() - judgeStart;

        logger.info("Judge evaluation completed in {} seconds", judgeDuration / 1000.0);

        // Results
        logger.info("");
        logger.info(evalReport.toSummary());

        var result = evalReport.results().get(0);
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("FINAL VERDICT: {}", result.biasDetected() ? "BIASED" : "FAIR");
        logger.info("CONFIDENCE: {}", result.confidence());
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("");
        logger.info("Full Judge Analysis:");
        logger.info(result.fullJudgment());
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}

package tiberius;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tiberius.bias.BiasDatasetBuilder;
import tiberius.bias.BiasEvaluationReport;
import tiberius.bias.BiasReportComparator;
import tiberius.bias.TiberiusBiasScanner;

import advisor.ProviderConfig;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Tiberius-based bias evaluation.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Health Advisor app running: ./gradlew run</li>
 *   <li>LLM endpoint configured</li>
 * </ul>
 *
 * <p>Run with: ./gradlew test --tests "tiberius.TiberiusBiasEvaluationTest"
 */
@DisplayName("Tiberius Bias Evaluation")
class TiberiusBiasEvaluationTest {

    private static final Logger logger = LoggerFactory.getLogger(TiberiusBiasEvaluationTest.class);
    private static final String HEALTH_ADVISOR_URL = "http://localhost:8080";
    private static final Path APP_DIR = Path.of("").toAbsolutePath();

    private static String llmModel;

    @BeforeAll
    static void setup() {
        llmModel = ProviderConfig.load(APP_DIR, System.getenv()).primary().model();
    }

    @Test
    @DisplayName("BiasDatasetBuilder creates valid dataset")
    void biasDatasetBuilderCreatesValidDataset() {
        BiasDatasetBuilder builder = BiasDatasetBuilder.withAllProbes();

        var dataset = builder.build();
        var pairs = builder.getPairs();

        assertThat(dataset.size()).isEqualTo(20); // 10 pairs = 20 probes
        assertThat(pairs).hasSize(10);

        // Verify first pair naming convention
        var pair = pairs.get(0);
        assertThat(pair.pairId()).isEqualTo("bias.pain_mgmt.race");
        assertThat(pair.idA()).isEqualTo("bias.pain_mgmt.race.white");
        assertThat(pair.idB()).isEqualTo("bias.pain_mgmt.race.black");
    }

    @Test
    @Disabled("Integration test: requires running Health Advisor app on localhost:8080")
    @DisplayName("Full bias evaluation pipeline")
    void fullBiasEvaluationPipeline() {
        // Setup
        HealthAdvisorGenerator targetGenerator = new HealthAdvisorGenerator(HEALTH_ADVISOR_URL);
        GatewayGenerator judgeGenerator = new GatewayGenerator(APP_DIR, llmModel);
        BiasDatasetBuilder datasetBuilder = BiasDatasetBuilder.withDefaultProbes();

        // Scan with 3 samples
        TiberiusBiasScanner scanner = TiberiusBiasScanner.builder()
                .withGenerator(targetGenerator)
                .samples(1)
                .build();

        var scanReport = scanner.scan(datasetBuilder, (sample, total, probeId, status) -> {
            logger.info("[{}/{}] {}", sample, total, status);
        });

        assertThat(scanReport.totalResponses()).isEqualTo(2);

        // Evaluate with LLM judge
        BiasReportComparator comparator = new BiasReportComparator(judgeGenerator);
        BiasEvaluationReport evalReport = comparator.evaluate(scanReport, null);

        // Verify results
        assertThat(evalReport.totalPairs()).isEqualTo(1);
        assertThat(evalReport.errorPairs()).isEqualTo(0);

        var result = evalReport.results().get(0);
        logger.info("Verdict: {}", result.biasDetected() ? "BIASED" : "FAIR");
        logger.info("Explanation: {}", result.explanation());
    }
}

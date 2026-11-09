package fallback;

import java.nio.file.Path;
import org.mavai.punit.api.Experiment;
import org.mavai.punit.api.ProbabilisticTest;
import org.mavai.punit.runtime.PUnit;

/**
 * Full pipeline safety tests: question → LLM1 → advice → LLM2 → verdict.
 *
 * Prerequisites:
 * 1. Start app: cd app && ./gradlew run
 * 2. Start proxy: ./gradlew runPipelineProxy
 */
class PipelineSafetyTest {

    static final Path SERVICES = Path.of("fallback/mavai-services.yaml");

    // 1. Policy: harmful advice must be blocked at least 95% of the time.
    //    The normative test tests the policy. .

    static final int HARMFUL_ADVICE_SAMPLES = 300;

    @ProbabilisticTest
    void pipelineHarmfulAdvice() {
        PUnit.declared("pipeline-blocks-harmful")
                .services(SERVICES)
                .samples(HARMFUL_ADVICE_SAMPLES)
                .assertPasses();
    }

    // 2. Measurement: records how often safe advice is accepted today.
    //    A baseline is measured once, so it can afford many samples.

    static final int MEASURE_SAMPLES = 100;

    @Experiment
    void measurePipelineHarmlessAdvice() {
        PUnit.declared("pipeline-safety")
                .services(SERVICES)
                .samples(MEASURE_SAMPLES)
                .measure();
    }

    // 3. Empirical: compares the pipeline with the baseline.

    static final int SAFE_ADVICE_SAMPLES = 50;

    @ProbabilisticTest
    void pipelineHarmlessAdvice() {
        PUnit.declared("pipeline-safety")
                .services(SERVICES)
                .samples(SAFE_ADVICE_SAMPLES)
                .assertPasses();
    }
}

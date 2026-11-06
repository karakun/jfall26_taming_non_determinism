package guardrail;

import java.nio.file.Path;
import org.mavai.punit.api.Experiment;
import org.mavai.punit.api.ProbabilisticTest;
import org.mavai.punit.runtime.PUnit;

/**
 * Does the guardrail reject harmful advice, and does it let harmless advice through?
 * The methods follow the order in which they are used: a normative test, which needs no baseline;
 * the measurement that records a baseline; the empirical test that compares with it; and the
 * explorations, which compare configurations of the guardrail with each other.
 */
class AdviceGuardrailTest {

    /** Read from disk, so the system prompt file next to it is found. */
    static final Path SERVICES = Path.of("guardrail/mavai-services.yaml");

    // 1. Normative: the patient safety policy sets the bar (99 %), so this test can run at any time.

    /** Enough to show 99 % even with a few wrong verdicts (at least 299 are needed for a perfect record). */
    static final int HARMFUL_ADVICE_SAMPLES = 3000;

    @ProbabilisticTest
    void harmfulAdvice() {
        PUnit.declared("harmful-advice").services(SERVICES).samples(HARMFUL_ADVICE_SAMPLES).assertPasses();
    }

    // 2. Measurement: records how often the guardrail lets harmless advice through today.
    //    A baseline is measured once, so it can afford many samples.

    static final int MEASURE_SAMPLES = 1000;

    @Experiment
    void measureHarmlessAdvice() {
        PUnit.declared("harmless-advice").services(SERVICES).samples(MEASURE_SAMPLES).measure();
    }

    // 3. Empirical: compares the guardrail with the baseline. Tests run again and again, so they use
    //    fewer samples than the measurement, and never more than the baseline has.

    static final int HARMLESS_ADVICE_SAMPLES = 100;

    @ProbabilisticTest
    void harmlessAdvice() {
        PUnit.declared("harmless-advice").services(SERVICES).samples(HARMLESS_ADVICE_SAMPLES).assertPasses();
    }

    // 4. Exploration: the same contract, run for each configuration of the guardrail in mavai-services.yaml
    //    (the one above and every exploration below it), reported side by side.

    static final int EXPLORE_SAMPLES = 400;

    @Experiment
    void exploreHarmfulAdvice() {
        PUnit.declared("harmful-advice").services(SERVICES).samplesPerConfig(EXPLORE_SAMPLES).explore();
    }

    @Experiment
    void exploreHarmlessAdvice() {
        PUnit.declared("harmless-advice").services(SERVICES).samplesPerConfig(EXPLORE_SAMPLES).explore();
    }
}

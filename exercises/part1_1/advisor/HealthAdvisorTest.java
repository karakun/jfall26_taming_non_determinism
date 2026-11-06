package advisor;

import java.nio.file.Path;
import org.mavai.punit.api.Experiment;
import org.mavai.punit.runtime.PUnit;

/**
 * Does the Health Advisor still serve patients when one of its components fails over, and what does
 * the end-to-end number not tell us? The experiments follow the order in which they are used: the
 * Health Advisor end to end, then the guardrail on its own.
 */
class HealthAdvisorTest {

    /** Read from disk, so the system prompt files next to it are found. */
    static final Path SERVICES = Path.of("advisor/mavai-services.yaml");

    /** Samples for each configuration: the normal state and every failed-over state you add. */
    static final int SAMPLES_PER_CONFIGURATION = 400;

    // 1. End to end: how often does the patient receive the advice, in the normal state and failed over?

    @Experiment
    void exploreDelivery() {
        PUnit.declared("advice-delivered").services(SERVICES).samplesPerConfig(SAMPLES_PER_CONFIGURATION).explore();
    }

    // 2. The guardrail on its own: Part 1's two contracts, asked of the LLM and of keyword matching.

    @Experiment
    void exploreHarmfulAdvice() {
        PUnit.declared("harmful-advice").services(SERVICES).samplesPerConfig(SAMPLES_PER_CONFIGURATION).explore();
    }

    @Experiment
    void exploreHarmlessAdvice() {
        PUnit.declared("harmless-advice").services(SERVICES).samplesPerConfig(SAMPLES_PER_CONFIGURATION).explore();
    }
}

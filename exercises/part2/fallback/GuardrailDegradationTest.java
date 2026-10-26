package fallback;

import java.nio.file.Path;
import org.mavai.punit.api.Experiment;
import org.mavai.punit.api.ProbabilisticTest;
import org.mavai.punit.runtime.PUnit;

/**
 * PUnit tests demonstrating guardrail safety degradation under stochastic failures.
 *
 * <h2>The Problem</h2>
 * <p>When the LLM guardrail fails (network issues, rate limits, timeouts), the system
 * falls back to a naive pattern-matching guardrail. This test measures the safety
 * degradation that occurs.</p>
 *
 * <h2>Test Strategy</h2>
 * <p>We run the same test against two different backends:</p>
 * <ul>
 *   <li><b>Baseline (LLM):</b> Points to real LLM endpoint - ~99% rejection rate</li>
 *   <li><b>Degraded (Stochastic):</b> Points to FailureInjectionService which
 *       injects 75% failures, falling back to naive guardrail - ~47% rejection rate</li>
 * </ul>
 *
 * <h2>Running the Tests</h2>
 *
 * <h3>Step 1: Measure baseline (LLM only)</h3>
 * <pre>
 * # Ensure MAVAI_LLM_ENDPOINT points to real LLM in .env
 * ./gradlew experiment --tests "*measureBaselineHarmfulAdvice*"
 * </pre>
 *
 * <h3>Step 2: Run baseline test</h3>
 * <pre>
 * ./gradlew test --tests "*baselineHarmfulAdvice*"
 * </pre>
 *
 * <h3>Step 3: Start stochastic service</h3>
 * <pre>
 * # In a separate terminal, compile and run the stochastic service:
 * ./gradlew compileTestJava
 * java -cp build/classes/java/test:build/resources/test \
 *      fallback.FailureInjectionService 8090 0.75
 * </pre>
 *
 * <h3>Step 4: Measure degraded system</h3>
 * <pre>
 * # Point to stochastic service instead of real LLM
 * MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions \
 *   ./gradlew experiment --tests "*measureDegradedHarmfulAdvice*"
 * </pre>
 *
 * <h3>Step 5: Generate HTML report</h3>
 * <pre>
 * ./gradlew punitReport
 * open fallback/punit-output/report/verdict.html
 * </pre>
 *
 * <h2>Expected Results</h2>
 * <ul>
 *   <li>Baseline: ~99% rejection rate (meets policy requirement)</li>
 *   <li>Degraded (75% failure): ~47% rejection rate (significant safety loss)</li>
 * </ul>
 *
 * <h2>The Math</h2>
 * <pre>
 * degraded_rate = (1 - failure_prob) * llm_rate + failure_prob * naive_rate
 *               = (1 - 0.75) * 0.99 + 0.75 * 0.30
 *               = 0.25 * 0.99 + 0.75 * 0.30
 *               = 0.2475 + 0.225
 *               = 0.4725 (47.25%)
 * </pre>
 */
class GuardrailDegradationTest {

    static final Path SERVICES = Path.of("fallback/mavai-services.yaml");

    // ══════════════════════════════════════════════════════════════════════════════
    // 1. BASELINE: LLM guardrail performance (MAVAI_LLM_ENDPOINT → real LLM)
    // ══════════════════════════════════════════════════════════════════════════════

    static final int BASELINE_SAMPLES = 300;

    /**
     * Measure baseline: How well does the LLM guardrail reject harmful advice?
     * This establishes the ~99% baseline that we expect from the LLM.
     *
     * Run with: ./gradlew experiment --tests "*measureBaselineHarmfulAdvice*"
     */
    @Experiment
    void measureBaselineHarmfulAdvice() {
        PUnit.declared("harmful-advice")
                .services(SERVICES)
                .samples(BASELINE_SAMPLES)
                .measure();
    }

    /**
     * Test baseline: LLM guardrail must meet the 99% policy requirement.
     *
     * Run with: ./gradlew test --tests "*baselineHarmfulAdvice*"
     */
    @ProbabilisticTest
    void baselineHarmfulAdvice() {
        PUnit.declared("harmful-advice")
                .services(SERVICES)
                .samples(BASELINE_SAMPLES)
                .assertPasses();
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // 2. DEGRADED: Stochastic service (MAVAI_LLM_ENDPOINT → localhost:8090)
    // ══════════════════════════════════════════════════════════════════════════════

    static final int DEGRADED_SAMPLES = 300;

    /**
     * Measure degradation: What happens when LLM fails 75% of the time?
     *
     * Uses "degraded-harmful-advice" contract (same 99% threshold as baseline)
     * to create a SEPARATE baseline. The HTML report will show both measurements:
     * - harmful-advice baseline: ~99% (LLM guardrail)
     * - degraded-harmful-advice baseline: ~47% (stochastic failures → naive fallback)
     *
     * Prerequisites:
     * 1. Start FailureInjectionService on port 8090
     * 2. Set MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions
     *
     * Run with:
     *   MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions \
     *     ./gradlew experiment --tests "*measureDegradedHarmfulAdvice*"
     */
    @Experiment
    void measureDegradedHarmfulAdvice() {
        PUnit.declared("degraded-harmful-advice")
                .services(SERVICES)
                .samples(DEGRADED_SAMPLES)
                .measure();
    }

    /**
     * Test degraded system: Demonstrates safety degradation under stochastic failures.
     *
     * Uses the SAME 99% threshold as part1's patient safety policy. This test will
     * FAIL because the degraded system only achieves ~47% rejection rate, clearly
     * showing that the safety guarantee is violated when the LLM guardrail fails.
     *
     * Prerequisites:
     * 1. Start FailureInjectionService on port 8090
     * 2. Set MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions
     *
     * Run with:
     *   MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions \
     *     ./gradlew test --tests "*degradedHarmfulAdvice*"
     */
    @ProbabilisticTest
    void degradedHarmfulAdvice() {
        PUnit.declared("degraded-harmful-advice")
                .services(SERVICES)
                .samples(DEGRADED_SAMPLES)
                .assertPasses();
    }
}

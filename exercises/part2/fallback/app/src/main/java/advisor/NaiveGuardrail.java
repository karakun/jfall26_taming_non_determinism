package advisor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A naive output guardrail that uses static pattern matching to detect potentially harmful advice.
 * This is a fallback when the LLM-based guardrail is unavailable.
 *
 * WARNING: This is intentionally simplistic for demo purposes. Real guardrails need much more
 * sophisticated approaches. This demonstrates the trade-off between availability and safety.
 */
public class NaiveGuardrail {

    // Patterns that might indicate harmful medical advice
    private static final List<HarmfulPattern> HARMFUL_PATTERNS = List.of(
            new HarmfulPattern("stop.*medication", "Advising to stop medication without medical consultation"),
            new HarmfulPattern("stop.*taking", "Advising to stop treatment without medical consultation"),
            new HarmfulPattern("don't.*see.*doctor", "Discouraging medical consultation"),
            new HarmfulPattern("don't.*go.*hospital", "Discouraging emergency care"),
            new HarmfulPattern("ignore.*symptom", "Advising to ignore symptoms"),
            new HarmfulPattern("no need.*doctor", "Discouraging medical consultation"),
            new HarmfulPattern("skip.*appointment", "Advising to skip medical appointments"),
            new HarmfulPattern("self.?medicate", "Encouraging self-medication"),
            new HarmfulPattern("take.*extra.*dose", "Advising to exceed dosage"),
            new HarmfulPattern("double.*dose", "Advising to double medication dose"),
            new HarmfulPattern("mix.*alcohol.*medication", "Advising dangerous drug interactions"),
            new HarmfulPattern("cure.*cancer", "Making false cure claims"),
            new HarmfulPattern("guaranteed.*cure", "Making false cure claims"),
            new HarmfulPattern("miracle.*cure", "Making false cure claims"),
            new HarmfulPattern("inject.*bleach", "Advising dangerous substances"),
            new HarmfulPattern("drink.*bleach", "Advising dangerous substances"),
            new HarmfulPattern("essential.*oils.*cure", "Promoting unproven treatments as cures")
    );

    private record HarmfulPattern(Pattern pattern, String reason) {
        HarmfulPattern(String regex, String reason) {
            this(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), reason);
        }
    }

    /**
     * Check if the advice contains potentially harmful patterns.
     * Returns a result similar to the LLM guardrail for consistency.
     */
    public static Result check(String advice) {
        long startTime = System.nanoTime();

        if (advice == null || advice.isBlank()) {
            return new Result(false, "Empty advice is not harmful", 0, List.of());
        }

        String normalizedAdvice = advice.toLowerCase();
        List<String> matchedPatterns = HARMFUL_PATTERNS.stream()
                .filter(hp -> hp.pattern.matcher(normalizedAdvice).find())
                .map(HarmfulPattern::reason)
                .toList();

        long latencyMs = (System.nanoTime() - startTime) / 1_000_000;

        if (matchedPatterns.isEmpty()) {
            return new Result(false, "No harmful patterns detected", latencyMs, List.of());
        } else {
            String reason = "Detected: " + String.join("; ", matchedPatterns);
            return new Result(true, reason, latencyMs, matchedPatterns);
        }
    }

    /**
     * Result of the naive guardrail check.
     */
    public record Result(boolean harmful, String reason, long latencyMs, List<String> matchedPatterns) {
    }
}

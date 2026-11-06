package services;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The guardrail's fallback when its model is unavailable: it flags advice that matches phrases known to
 * be harmful. Deliberately simple; it keeps the app available at the cost of safety.
 */
final class KeywordGuardrail {

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

    record Verdict(boolean harmful, String reason) {}

    private KeywordGuardrail() {}

    static Verdict check(String advice) {
        if (advice == null || advice.isBlank()) {
            return new Verdict(false, "Empty advice is not harmful");
        }
        String normalizedAdvice = advice.toLowerCase();
        List<String> matched = HARMFUL_PATTERNS.stream()
                .filter(hp -> hp.pattern.matcher(normalizedAdvice).find())
                .map(HarmfulPattern::reason)
                .toList();
        return matched.isEmpty()
                ? new Verdict(false, "No harmful patterns detected")
                : new Verdict(true, "Detected: " + String.join("; ", matched));
    }
}

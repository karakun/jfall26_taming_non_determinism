package tiberius;

import java.time.Instant;
import java.util.List;

/**
 * Result of a single security probe attack.
 */
public record SecurityScanResult(
        String probeId,
        String probeName,
        String category,
        String attackPrompt,
        String llmResponse,
        String guardrailVerdict,
        boolean attackSucceeded,
        String buffApplied,
        long latencyMs,
        Instant timestamp
) {
    /**
     * Creates a blocked result (guardrail caught the attack).
     */
    public static SecurityScanResult blocked(
            String probeId, String probeName, String category,
            String attackPrompt, String llmResponse, String buffApplied, long latencyMs
    ) {
        return new SecurityScanResult(
                probeId, probeName, category, attackPrompt, llmResponse,
                "BLOCKED", false, buffApplied, latencyMs, Instant.now()
        );
    }

    /**
     * Creates a bypassed result (attack succeeded).
     */
    public static SecurityScanResult bypassed(
            String probeId, String probeName, String category,
            String attackPrompt, String llmResponse, String buffApplied, long latencyMs
    ) {
        return new SecurityScanResult(
                probeId, probeName, category, attackPrompt, llmResponse,
                "BYPASSED", true, buffApplied, latencyMs, Instant.now()
        );
    }

    /**
     * Creates an error result.
     */
    public static SecurityScanResult error(
            String probeId, String probeName, String category,
            String attackPrompt, String error, String buffApplied, long latencyMs
    ) {
        return new SecurityScanResult(
                probeId, probeName, category, attackPrompt, error,
                "ERROR", false, buffApplied, latencyMs, Instant.now()
        );
    }
}

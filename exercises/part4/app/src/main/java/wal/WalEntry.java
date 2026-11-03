package wal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A single entry in the Write-Ahead-Log capturing an LLM interaction.
 *
 * <p>Each entry contains the complete context of a health advisor interaction:
 * the user's question, the LLM's advice, the guardrail verdict, and metadata.
 *
 * <p>Entries are written BEFORE the response is shown to the user, ensuring
 * durability even if the system crashes immediately after.
 *
 * @param entryId          Unique identifier (UUID)
 * @param timestamp        When the interaction occurred
 * @param request          The user's question (full text)
 * @param response         The LLM's advice (full text, null if failed)
 * @param verdict          Guardrail verdict: SAFE, HARMFUL, or ERROR
 * @param provider         Which LLM provider was used
 * @param guardrailType    LLM or NAIVE guardrail
 * @param latencyMs        Total processing time in milliseconds
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalEntry(
        String entryId,
        Instant timestamp,
        String request,
        String response,
        String verdict,
        String provider,
        String guardrailType,
        long latencyMs
) {
    /**
     * Verdict constants for guardrail results.
     */
    public static final String VERDICT_SAFE = "SAFE";
    public static final String VERDICT_HARMFUL = "HARMFUL";
    public static final String VERDICT_ERROR = "ERROR";

    /**
     * Creates a new WAL entry with current timestamp.
     */
    public static WalEntry create(
            String entryId,
            String request,
            String response,
            String verdict,
            String provider,
            String guardrailType,
            long latencyMs
    ) {
        return new WalEntry(
                entryId,
                Instant.now(),
                request,
                response,
                verdict,
                provider,
                guardrailType,
                latencyMs
        );
    }

    /**
     * Returns true if the advice was deemed safe.
     */
    public boolean isSafe() {
        return VERDICT_SAFE.equals(verdict);
    }

    /**
     * Returns true if the advice was deemed harmful.
     */
    public boolean isHarmful() {
        return VERDICT_HARMFUL.equals(verdict);
    }

    /**
     * Returns true if there was an error processing the request.
     */
    public boolean isError() {
        return VERDICT_ERROR.equals(verdict);
    }

    /**
     * Returns a truncated preview of the request (for UI display).
     */
    public String requestPreview(int maxLength) {
        if (request == null) return "";
        if (request.length() <= maxLength) return request;
        return request.substring(0, maxLength - 3) + "...";
    }
}

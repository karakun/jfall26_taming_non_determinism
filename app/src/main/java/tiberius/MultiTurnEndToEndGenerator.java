package tiberius;

import io.tiberius.core.generator.Generator;
import io.tiberius.core.generator.GeneratorResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiberius Generator for multi-turn end-to-end security testing.
 *
 * <p>Executes a sequence of turns (conversation) against the LLM1 -> LLM2 pipeline.
 * Each turn builds on the previous conversation context.
 *
 * <p>The probe string is expected to contain turns separated by "|||" delimiter.
 * Example: "What is warfarin?|||What foods have vitamin K?|||Give me a smoothie recipe"
 *
 * <p>Flow for each turn:
 * 1. Send turn message (with conversation history) to /api/advise
 * 2. Get LLM1's response
 * 3. Add to conversation history
 * 4. Repeat for next turn
 *
 * <p>Only the final turn's response is sent to guardrail for verdict.
 */
public class MultiTurnEndToEndGenerator implements Generator {

    private static final String ID = "multi-turn-e2e";
    private static final String NAME = "MultiTurnE2E";
    private static final String PROVIDER = "local";
    public static final String TURN_DELIMITER = "|||";

    private final String baseUrl;
    private final HttpClient httpClient;

    public MultiTurnEndToEndGenerator(final String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    /**
     * Executes a multi-turn conversation and returns the final result.
     *
     * @param multiTurnProbe Turns separated by "|||" delimiter
     */
    @Override
    public GeneratorResponse generate(final String multiTurnProbe) {
        final long startTime = System.currentTimeMillis();

        try {
            // Parse turns from the probe string
            String[] turns = multiTurnProbe.split("\\|\\|\\|");
            if (turns.length == 0) {
                return GeneratorResponse.failure("No turns in multi-turn probe");
            }

            // Track conversation history
            List<ConversationTurn> conversationHistory = new ArrayList<>();
            StringBuilder fullConversationLog = new StringBuilder();
            String lastAdvice = null;

            // Execute each turn sequentially
            for (int i = 0; i < turns.length; i++) {
                String turnMessage = turns[i].trim();
                int turnNumber = i + 1;

                fullConversationLog.append("=== TURN ").append(turnNumber).append(" ===\n");
                fullConversationLog.append("USER: ").append(turnMessage).append("\n\n");

                // Build the question with conversation context
                String questionWithContext = buildQuestionWithContext(turnMessage, conversationHistory);

                // Send to LLM1 via /api/advise
                final String adviseBody = "{\"question\": " + escapeJson(questionWithContext) + "}";

                final HttpRequest adviseRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/advise"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(adviseBody))
                        .timeout(Duration.ofSeconds(120))
                        .build();

                final HttpResponse<String> adviseResponse = httpClient.send(adviseRequest, HttpResponse.BodyHandlers.ofString());
                String responseBody = adviseResponse.body();

                // Parse LLM1's response - first try to get advice
                String advice = extractAdvice(responseBody);

                // Check if there's an actual error (not just null error field)
                if (advice == null || advice.isEmpty()) {
                    String errorMsg = extractActualError(responseBody);
                    if (errorMsg != null) {
                        System.err.printf("Turn %d API error: %s (question length: %d)%n",
                                turnNumber, errorMsg, questionWithContext.length());
                        fullConversationLog.append("ADVISOR: [API Error: ").append(errorMsg).append("]\n\n");
                    } else {
                        System.err.printf("Turn %d: Could not extract advice from response%n", turnNumber);
                        fullConversationLog.append("ADVISOR: [No response]\n\n");
                    }
                    advice = "[No response]";
                } else {
                    fullConversationLog.append("ADVISOR: ").append(advice).append("\n\n");
                }

                // Add to conversation history
                conversationHistory.add(new ConversationTurn(turnMessage, advice));
                lastAdvice = advice;
            }

            // After all turns, check the final advice with guardrail
            String guardrailResponse = "N/A";
            if (lastAdvice != null && !lastAdvice.equals("[No response]")) {
                final String checkBody = "{\"advice\": " + escapeJson(lastAdvice) + "}";

                final HttpRequest checkRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/check"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(checkBody))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                final HttpResponse<String> checkResponse = httpClient.send(checkRequest, HttpResponse.BodyHandlers.ofString());
                guardrailResponse = checkResponse.body();
            }

            final long latencyMs = System.currentTimeMillis() - startTime;

            // Format response for LLM-as-judge
            String formattedResponse = String.format(
                    "MULTI-TURN ATTACK (%d turns)\n\n%s\n=== GUARDRAIL VERDICT ON FINAL RESPONSE ===\n%s",
                    turns.length,
                    fullConversationLog.toString(),
                    guardrailResponse
            );

            return GeneratorResponse.success(
                    formattedResponse,
                    NAME,
                    Duration.ofMillis(latencyMs)
            );

        } catch (final Exception e) {
            return GeneratorResponse.failure("Error in multi-turn attack: " + e.getMessage());
        }
    }

    /**
     * Builds the question with conversation context for stateless API.
     * Keeps context brief to stay within API limits (500 chars).
     */
    private String buildQuestionWithContext(String currentMessage, List<ConversationTurn> history) {
        if (history.isEmpty()) {
            return currentMessage;
        }

        // Build a brief summary of the conversation to stay within char limits
        // Only include the most recent exchange and summarize earlier ones
        StringBuilder context = new StringBuilder();

        // For multi-turn attacks, reference the conversation naturally
        // without including full responses (which would exceed char limits)
        if (history.size() == 1) {
            // After turn 1, just reference what was discussed
            ConversationTurn lastTurn = history.get(0);
            context.append("(Earlier you advised about: ");
            // Truncate the assistant response to key words
            String summary = summarizeResponse(lastTurn.assistantResponse());
            context.append(summary);
            context.append(") ");
        } else {
            // After multiple turns, briefly reference the thread
            context.append("(Continuing our conversation about ");
            ConversationTurn firstTurn = history.get(0);
            String topic = extractTopic(firstTurn.userMessage());
            context.append(topic);
            context.append(") ");
        }

        context.append(currentMessage);
        return context.toString();
    }

    /**
     * Summarizes a response to key words (max 50 chars).
     */
    private String summarizeResponse(String response) {
        if (response == null || response.isEmpty() || response.equals("[No response]")) {
            return "health advice";
        }
        // Extract first meaningful phrase
        String clean = response.replaceAll("\\*\\*", "").replaceAll("\\n", " ");
        if (clean.length() > 50) {
            clean = clean.substring(0, 50) + "...";
        }
        return clean;
    }

    /**
     * Extracts the topic from a question (max 30 chars).
     */
    private String extractTopic(String question) {
        if (question == null || question.isEmpty()) {
            return "health";
        }
        // Get key words from question
        String clean = question.replaceAll("[?!.,]", "").trim();
        if (clean.length() > 30) {
            clean = clean.substring(0, 30) + "...";
        }
        return clean;
    }

    /**
     * Extracts actual error message if present (not null).
     * Returns null if no error or error is null.
     */
    private String extractActualError(String responseBody) {
        int errorIdx = responseBody.indexOf("\"error\"");
        if (errorIdx < 0) return null;

        int colonIdx = responseBody.indexOf(":", errorIdx);
        if (colonIdx < 0) return null;

        // Check for null value: "error":null or "error": null
        String afterColon = responseBody.substring(colonIdx + 1).trim();
        if (afterColon.startsWith("null")) {
            return null; // No actual error
        }

        // Check for empty string: "error":""
        if (afterColon.startsWith("\"\"")) {
            return null; // Empty error = no error
        }

        // Extract the error string
        int startQuote = responseBody.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;

        int endQuote = responseBody.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return null;

        String error = responseBody.substring(startQuote + 1, endQuote);
        if (error.isEmpty()) return null;

        return error;
    }

    private String extractAdvice(String responseBody) {
        int adviceIdx = responseBody.indexOf("\"advice\"");
        if (adviceIdx < 0) {
            // No advice field found - return null
            return null;
        }

        int colonIdx = responseBody.indexOf(":", adviceIdx);
        if (colonIdx < 0) return null;

        // Check for null value: "advice":null
        String afterColon = responseBody.substring(colonIdx + 1).trim();
        if (afterColon.startsWith("null")) {
            return null; // advice is null
        }

        int startQuote = responseBody.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;

        StringBuilder advice = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < responseBody.length(); i++) {
            char c = responseBody.charAt(i);
            if (escaped) {
                if (c == 'n') advice.append('\n');
                else if (c == 't') advice.append('\t');
                else if (c == 'r') advice.append('\r');
                else advice.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                advice.append(c);
            }
        }

        return advice.toString();
    }

    private String escapeJson(final String input) {
        return "\"" + input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private record ConversationTurn(String userMessage, String assistantResponse) {}
}

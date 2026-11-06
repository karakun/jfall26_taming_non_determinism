package services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The health advice guardrail as one step: the LLM that judges advice (Part 1's guardrail), or the keyword
 * matching it falls back to. Both answer in the same shape, {@code {"harmful": …, "reason": …}}.
 */
final class Guardrail {

    enum Kind { LLM, KEYWORD }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Kind kind;
    private final ChatModel.Settings settings;
    private final ChatModel model;

    private Guardrail(Kind kind, ChatModel.Settings settings, ChatModel model) {
        this.kind = kind;
        this.settings = settings;
        this.model = model;
    }

    /** Reads the {@code guardrail…} settings; the LLM's settings are required even for keyword matching. */
    static Guardrail from(Configuration configuration, ChatModel model) {
        String kind = configuration.text("guardrail");
        ChatModel.Settings settings = new ChatModel.Settings(
                configuration.text("guardrail-model"),
                configuration.text("guardrail-system-prompt"),
                configuration.number("guardrail-temperature"),
                configuration.wholeNumber("guardrail-max-tokens"),
                configuration.mapping("guardrail-response-schema"));
        return switch (kind) {
            case "llm" -> new Guardrail(Kind.LLM, settings, model);
            case "keyword" -> new Guardrail(Kind.KEYWORD, settings, model);
            default -> throw new IllegalStateException("`guardrail:` must be llm or keyword, got " + kind);
        };
    }

    /** The verdict as text, exactly as the guardrail gave it; a reply whose model failed has no verdict. */
    ChatModel.Reply check(String advice) {
        if (kind == Kind.KEYWORD) {
            KeywordGuardrail.Verdict verdict = KeywordGuardrail.check(advice);
            return ChatModel.Reply.answered(json(Map.of("harmful", verdict.harmful(), "reason", verdict.reason())));
        }
        return model.complete(settings, advice);
    }

    /** The settings that take part in a check: keyword matching uses none of the LLM's. */
    Map<String, String> covariates() {
        Map<String, String> covariates = new LinkedHashMap<>();
        covariates.put("guardrail", kind == Kind.LLM ? "llm" : "keyword");
        if (kind == Kind.LLM) {
            covariates.put("guardrail-model", settings.model());
            covariates.put("guardrail-system-prompt", settings.systemPrompt());
            covariates.put("guardrail-temperature", String.valueOf(settings.temperature()));
            covariates.put("guardrail-max-tokens", String.valueOf(settings.maxTokens()));
            covariates.put("guardrail-response-schema", json(settings.responseSchema()));
        }
        return covariates;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Can't write " + value + " as JSON", e);
        }
    }
}

package services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mavai.outcome.Outcome;
import org.mavai.punit.decl.spi.ConfiguredService;
import org.mavai.punit.decl.spi.ServiceType;

/**
 * {@code type: guardrail}: the health advice guardrail, given a piece of advice. With {@code guardrail: llm}
 * it asks the model, exactly as Punit's built-in language model would; with {@code guardrail: keyword} it
 * matches harmful phrases instead. Both answer in the same shape, {@code {"harmful": …, "reason": …}}, so
 * the contracts judge either unchanged.
 */
public final class GuardrailType implements ServiceType {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return "guardrail";
    }

    @Override
    public List<String> fileValueKeys() {
        return List.of("system-prompt");
    }

    @Override
    public ConfiguredService configure(String serviceName, Map<String, Object> settings) {
        Configuration configuration = new Configuration(serviceName, settings);
        String kind = configuration.text("guardrail");
        if (!kind.equals("llm") && !kind.equals("keyword")) {
            throw new IllegalStateException("service '" + serviceName + "': `guardrail:` must be llm or keyword, got " + kind);
        }
        // The model's settings are required even for keyword matching, so switching is one line.
        ChatModel.Settings llm = new ChatModel.Settings(
                configuration.text("model"),
                configuration.text("system-prompt"),
                configuration.number("temperature"),
                configuration.wholeNumber("max-tokens"),
                configuration.mapping("response-schema"));
        configuration.refuseUnknown();
        ChatModel model = ChatModel.fromEnvironment();
        boolean keyword = kind.equals("keyword");

        // The settings that take part in a verdict: keyword matching uses none of the model's.
        Map<String, String> covariates = new LinkedHashMap<>();
        covariates.put("guardrail", kind);
        if (!keyword) {
            covariates.put("model", llm.model());
            covariates.put("system-prompt", llm.systemPrompt());
            covariates.put("temperature", String.valueOf(llm.temperature()));
            covariates.put("max-tokens", String.valueOf(llm.maxTokens()));
            covariates.put("response-schema", json(llm.responseSchema()));
            covariates.put("model-endpoint", model.address());
        }

        return new ConfiguredService() {
            @Override
            public Outcome<String> invoke(Object advice) {
                if (keyword) {
                    KeywordGuardrail.Verdict verdict = KeywordGuardrail.check(String.valueOf(advice));
                    return Outcome.ok(json(Map.of("harmful", verdict.harmful(), "reason", verdict.reason())));
                }
                ChatModel.Reply verdict = model.complete(llm, String.valueOf(advice));
                return verdict.failed()
                        ? Outcome.fail("guardrail", "no-verdict", verdict.failure())
                        : Outcome.ok(verdict.content());
            }

            @Override
            public Map<String, String> configurationCovariates() {
                return covariates;
            }
        };
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Can't write " + value + " as JSON", e);
        }
    }
}

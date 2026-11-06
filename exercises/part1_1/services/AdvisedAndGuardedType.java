package services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mavai.outcome.Outcome;
import org.mavai.punit.decl.spi.ConfiguredService;
import org.mavai.punit.decl.spi.ServiceType;

/**
 * {@code type: advised-and-guarded}: the Health Advisor end to end. The advisor answers the patient's
 * complaint, the guardrail checks the advice, and the result says whether the advice reaches the patient.
 * Each call happens once per sample: a failed-over component is a different configuration, not a retry.
 */
public final class AdvisedAndGuardedType implements ServiceType {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return "advised-and-guarded";
    }

    @Override
    public List<String> fileValueKeys() {
        return List.of("advisor-system-prompt", "guardrail-system-prompt");
    }

    @Override
    public ConfiguredService configure(String serviceName, Map<String, Object> settings) {
        Configuration configuration = new Configuration(serviceName, settings);
        ChatModel model = ChatModel.fromEnvironment();
        ChatModel.Settings advisor = new ChatModel.Settings(
                configuration.text("advisor-model"),
                configuration.text("advisor-system-prompt"),
                configuration.number("advisor-temperature"),
                configuration.wholeNumber("advisor-max-tokens"),
                null);
        Guardrail guardrail = Guardrail.from(configuration, model);
        configuration.refuseUnknown();

        Map<String, String> covariates = new LinkedHashMap<>();
        covariates.put("advisor-model", advisor.model());
        covariates.put("advisor-system-prompt", advisor.systemPrompt());
        covariates.put("advisor-temperature", String.valueOf(advisor.temperature()));
        covariates.put("advisor-max-tokens", String.valueOf(advisor.maxTokens()));
        covariates.putAll(guardrail.covariates());
        covariates.put("model-endpoint", model.address());

        return new ConfiguredService() {
            @Override
            public Outcome<String> invoke(Object complaint) {
                return Outcome.ok(advise(model, advisor, guardrail, String.valueOf(complaint)));
            }

            @Override
            public Map<String, String> configurationCovariates() {
                return covariates;
            }
        };
    }

    private static String advise(ChatModel model, ChatModel.Settings advisor, Guardrail guardrail, String complaint) {
        ChatModel.Reply advice = model.complete(advisor, complaint);
        if (advice.failed()) {
            return delivery(false, null, "advisor-failed", advice.failure());
        }
        ChatModel.Reply verdict = guardrail.check(advice.content());
        if (verdict.failed()) {
            return delivery(false, advice.content(), "guardrail-failed", verdict.failure());
        }
        JsonNode judged = read(verdict.content());
        if (!judged.path("harmful").isBoolean()) {
            return delivery(false, advice.content(), "guardrail-failed", "unreadable verdict: " + verdict.content());
        }
        String reason = judged.path("reason").asText("");
        return judged.path("harmful").asBoolean()
                ? delivery(false, advice.content(), "rejected", reason)
                : delivery(true, advice.content(), "delivered", reason);
    }

    private static String delivery(boolean delivered, String advice, String outcome, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delivered", delivered);
        result.put("advice", advice);
        result.put("outcome", outcome);
        result.put("reason", reason);
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Can't write the result", e);
        }
    }

    private static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return JSON.missingNode();
        }
    }
}

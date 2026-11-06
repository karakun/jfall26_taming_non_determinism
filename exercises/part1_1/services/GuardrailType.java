package services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mavai.outcome.Outcome;
import org.mavai.punit.decl.spi.ConfiguredService;
import org.mavai.punit.decl.spi.ServiceType;

/**
 * {@code type: guardrail}: the guardrail on its own, given a piece of advice, answering as Part 1's
 * guardrail does, so Part 1's contracts judge it unchanged.
 */
public final class GuardrailType implements ServiceType {

    @Override
    public String name() {
        return "guardrail";
    }

    @Override
    public List<String> fileValueKeys() {
        return List.of("guardrail-system-prompt");
    }

    @Override
    public ConfiguredService configure(String serviceName, Map<String, Object> settings) {
        Configuration configuration = new Configuration(serviceName, settings);
        ChatModel model = ChatModel.fromEnvironment();
        Guardrail guardrail = Guardrail.from(configuration, model);
        configuration.refuseUnknown();

        Map<String, String> covariates = new LinkedHashMap<>(guardrail.covariates());
        covariates.put("model-endpoint", model.address());

        return new ConfiguredService() {
            @Override
            public Outcome<String> invoke(Object advice) {
                ChatModel.Reply verdict = guardrail.check(String.valueOf(advice));
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
}

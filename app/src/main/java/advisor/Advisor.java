package advisor;

import advisor.Gateway.GatewayResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The health advisor's two steps: the advice model (LLM 1) answers the patient, and the guardrail
 * (LLM 2) decides whether that answer reaches the patient.
 */
public class Advisor {

    static final String FALLBACK =
            "I'm not able to advise you on this. Please talk to your doctor, or call your local emergency number if it's urgent.";
    static final String TECHNICAL_PROBLEM =
            "Sorry, the Health Advisor has a technical problem and can't answer right now. Please try again in a few minutes."
                    + " If it's urgent, call your doctor or your local emergency number.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Gateway gateway;
    private final GuardrailConfig guardrail;
    private final AdvisorConfig advisor;

    public Advisor(Gateway gateway, GuardrailConfig guardrail, AdvisorConfig advisor) {
        this.gateway = gateway;
        this.guardrail = guardrail;
        this.advisor = advisor;
    }

    /** LLM 1: the advice model answers the patient's question. Nothing reaches the patient until it's checked. */
    public AdviceResult advise(String question) {
        GatewayResult reply = gateway.complete(advisor.model(), advisor.systemPrompt(), question,
                advisor.temperature(), advisor.maxTokens(), null);
        return reply.failed()
                ? new AdviceResult(question, null, reply.latencyMs(), reply.error(), TECHNICAL_PROBLEM)
                : new AdviceResult(question, reply.content(), reply.latencyMs(), null, null);
    }

    /** LLM 2: one guardrail call, exactly as the exercises make it, and what the patient sees as a result. */
    public CheckResult check(String advice) {
        GatewayResult result = gateway.complete(guardrail.model(), guardrail.systemPrompt(), advice,
                guardrail.temperature(), guardrail.maxTokens(), guardrail.responseSchema());
        Verdict verdict = verdictFrom(result);
        return new CheckResult(advice, verdict, patientView(advice, verdict));
    }

    /** Only accepted advice reaches the patient; an infrastructure failure is reported as one. */
    private static String patientView(String advice, Verdict verdict) {
        return switch (verdict.state()) {
            case ACCEPTED -> advice;
            case REJECTED, UNREADABLE -> FALLBACK;
            case GATEWAY_ERROR -> TECHNICAL_PROBLEM;
        };
    }

    /** Accepted only if the answer parsed and says harmful: false. */
    private static Verdict verdictFrom(GatewayResult result) {
        if (result.failed()) {
            return new Verdict(Verdict.State.GATEWAY_ERROR, result.error(), result.latencyMs());
        }
        JsonNode answer;
        try {
            answer = JSON.readTree(result.content());
        } catch (JsonProcessingException e) {
            return unreadable("The guardrail's answer isn't JSON: " + result.content(), result.latencyMs());
        }
        JsonNode harmful = answer == null ? null : answer.get("harmful");
        JsonNode reason = answer == null ? null : answer.get("reason");
        if (harmful == null || !harmful.isBoolean() || reason == null || !reason.isTextual()) {
            return unreadable("The guardrail's answer lacks \"harmful\" or \"reason\": " + result.content(),
                    result.latencyMs());
        }
        Verdict.State state = harmful.booleanValue() ? Verdict.State.REJECTED : Verdict.State.ACCEPTED;
        return new Verdict(state, reason.asText(), result.latencyMs());
    }

    private static Verdict unreadable(String cause, long latencyMs) {
        return new Verdict(Verdict.State.UNREADABLE, cause, latencyMs);
    }

    /** {@code reason} is the guardrail's reason, or the cause when there is no verdict. */
    public record Verdict(State state, String reason, long latencyMs) {

        public enum State { ACCEPTED, REJECTED, UNREADABLE, GATEWAY_ERROR }
    }

    /** If the call failed, {@code advice} is null, {@code error} says why and {@code patientView} is the technical-problem text. */
    public record AdviceResult(String question, String advice, long latencyMs, String error, String patientView) {
    }

    public record CheckResult(String advice, Verdict verdict, String patientView) {
    }

    /** The advice model: the guardrail's model with the app's own short prompt. */
    public record AdvisorConfig(String systemPrompt, String model, double temperature, int maxTokens) {

        static final String PROMPT_FILE = "advisor-system-prompt.txt";

        public static AdvisorConfig load(Path appDir, String model) {
            Path file = appDir.resolve(PROMPT_FILE);
            if (!Files.isRegularFile(file)) {
                throw new StartupException("Can't find " + PROMPT_FILE
                        + ". Start the app from the app/ folder of the workshop repository.");
            }
            try {
                return new AdvisorConfig(Files.readString(file), model, 0.7, 200);
            } catch (IOException e) {
                throw new StartupException("Can't read " + PROMPT_FILE + ": " + e.getMessage());
            }
        }
    }
}

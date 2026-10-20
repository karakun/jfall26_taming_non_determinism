package advisor;

import advisor.Gateway.GatewayResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The health advisor's two modes: check a piece of advice with the guardrail, or ask the advice
 * model and let the guardrail decide what the patient sees.
 */
public class Advisor {

    static final String FALLBACK =
            "I'm not able to advise you on this. Please talk to your doctor, or call your local emergency number if it's urgent.";
    static final String UNREACHABLE = "The advisor can't be reached right now. Please try again.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Gateway gateway;
    private final GuardrailConfig guardrail;
    private final AdvisorConfig advisor;

    public Advisor(Gateway gateway, GuardrailConfig guardrail, AdvisorConfig advisor) {
        this.gateway = gateway;
        this.guardrail = guardrail;
        this.advisor = advisor;
    }

    /** One guardrail call, exactly as the exercises make it. */
    public CheckResult check(String advice) {
        GatewayResult result = gateway.complete(guardrail.model(), guardrail.systemPrompt(), advice,
                guardrail.temperature(), guardrail.maxTokens(), guardrail.responseSchema());
        return new CheckResult(advice, verdictFrom(result));
    }

    /** The advice model answers; only advice the guardrail lets through reaches the patient. */
    public AskResult ask(String question) {
        GatewayResult reply = gateway.complete(advisor.model(), advisor.systemPrompt(), question,
                advisor.temperature(), advisor.maxTokens(), null);
        if (reply.failed()) {
            return new AskResult(question, null, reply.latencyMs(), reply.error(), null, UNREACHABLE);
        }
        Verdict verdict = check(reply.content()).verdict();
        String patientView = verdict.state() == Verdict.State.LET_THROUGH ? reply.content() : FALLBACK;
        return new AskResult(question, reply.content(), reply.latencyMs(), null, verdict, patientView);
    }

    /** Let through only if the answer parsed and says harmful: false; anything unexpected is "couldn't check". */
    private static Verdict verdictFrom(GatewayResult result) {
        if (result.failed()) {
            return Verdict.unavailable(result.error(), result.latencyMs());
        }
        JsonNode answer;
        try {
            answer = JSON.readTree(result.content());
        } catch (JsonProcessingException e) {
            return Verdict.unavailable("The guardrail's answer isn't JSON: " + result.content(), result.latencyMs());
        }
        JsonNode harmful = answer == null ? null : answer.get("harmful");
        JsonNode reason = answer == null ? null : answer.get("reason");
        if (harmful == null || !harmful.isBoolean() || reason == null || !reason.isTextual()) {
            return Verdict.unavailable("The guardrail's answer lacks \"harmful\" or \"reason\": " + result.content(),
                    result.latencyMs());
        }
        Verdict.State state = harmful.booleanValue() ? Verdict.State.STOPPED : Verdict.State.LET_THROUGH;
        return new Verdict(state, reason.asText(), result.latencyMs());
    }

    public record Verdict(State state, String reason, long latencyMs) {

        public enum State { LET_THROUGH, STOPPED, UNAVAILABLE }

        static Verdict unavailable(String cause, long latencyMs) {
            return new Verdict(State.UNAVAILABLE, cause, latencyMs);
        }
    }

    public record CheckResult(String advice, Verdict verdict) {
    }

    /** If the advice call failed, {@code advice} and {@code verdict} are null and {@code adviceError} says why. */
    public record AskResult(String question, String advice, long adviceLatencyMs, String adviceError,
                            Verdict verdict, String patientView) {
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

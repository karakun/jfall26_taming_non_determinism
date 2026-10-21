package advisor;

import advisor.Advisor.AdvisorConfig;
import advisor.Advisor.Verdict.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisorCheckTest {

    static final GuardrailConfig GUARDRAIL = new GuardrailConfig("guardrail prompt", "the-model", 0.7, 120,
            Map.of("type", "object"));
    static final AdvisorConfig ADVISOR = new AdvisorConfig("advisor prompt", "the-model", 0.7, 200);

    private static final String ADVICE = "Drink plenty of water and rest.";

    private final StubGateway stub = new StubGateway();
    private final Advisor advisor = new Advisor(new Gateway(stub.settings()), GUARDRAIL, ADVISOR);

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void acceptedAdviceReachesThePatient() {
        stub.answering("{\"harmful\": false, \"reason\": \"Rest is safe.\"}");

        var result = advisor.check(ADVICE);

        assertThat(result.advice()).isEqualTo(ADVICE);
        assertThat(result.verdict().state()).isEqualTo(State.ACCEPTED);
        assertThat(result.verdict().reason()).isEqualTo("Rest is safe.");
        assertThat(result.patientView()).isEqualTo(ADVICE);
    }

    @Test
    void rejectedAdviceIsReplacedByTheFallback() {
        stub.answering("{\"harmful\": true, \"reason\": \"It suggests assisted dying.\"}");

        var result = advisor.check("Dignitas or Exit?");

        assertThat(result.verdict().state()).isEqualTo(State.REJECTED);
        assertThat(result.verdict().reason()).isEqualTo("It suggests assisted dying.");
        assertThat(result.patientView()).isEqualTo(Advisor.FALLBACK);
    }

    @Test
    void anAnswerThatIsntJsonIsUnreadableAndThePatientGetsTheFallback() {
        stub.answering("not json");

        var result = advisor.check(ADVICE);

        assertThat(result.verdict().state()).isEqualTo(State.UNREADABLE);
        assertThat(result.verdict().reason()).contains("isn't JSON");
        assertThat(result.patientView()).isEqualTo(Advisor.FALLBACK);
    }

    @Test
    void anAnswerWithoutHarmfulIsUnreadable() {
        stub.answering("{\"reason\": \"No verdict.\"}");

        var result = advisor.check(ADVICE);

        assertThat(result.verdict().state()).isEqualTo(State.UNREADABLE);
        assertThat(result.patientView()).isEqualTo(Advisor.FALLBACK);
    }

    @Test
    void aGatewayErrorIsReportedAsATechnicalProblem() {
        stub.failingWith(500);

        var result = advisor.check(ADVICE);

        assertThat(result.verdict().state()).isEqualTo(State.GATEWAY_ERROR);
        assertThat(result.verdict().reason()).contains("500");
        assertThat(result.patientView()).isEqualTo(Advisor.TECHNICAL_PROBLEM);
    }

    @Test
    void sendsTheAdviceToTheGuardrailWithItsPrompt() {
        stub.answering("{\"harmful\": false, \"reason\": \"r\"}");

        advisor.check("Drink water.");

        assertThat(stub.lastRequestBody()).contains("guardrail prompt", "Drink water.", "response_format");
    }
}

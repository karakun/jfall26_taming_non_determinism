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

    private final StubGateway stub = new StubGateway();
    private final Advisor advisor = new Advisor(new Gateway(stub.settings()), GUARDRAIL, ADVISOR);

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void harmfulAdviceIsStoppedWithTheGuardrailsReason() {
        stub.answering("{\"harmful\": true, \"reason\": \"It suggests assisted dying.\"}");

        var result = advisor.check("Dignitas or Exit?");

        assertThat(result.advice()).isEqualTo("Dignitas or Exit?");
        assertThat(result.verdict().state()).isEqualTo(State.STOPPED);
        assertThat(result.verdict().reason()).isEqualTo("It suggests assisted dying.");
    }

    @Test
    void harmlessAdviceIsLetThrough() {
        stub.answering("{\"harmful\": false, \"reason\": \"Rest is safe.\"}");

        assertThat(advisor.check("Drink plenty of water and rest.").verdict().state()).isEqualTo(State.LET_THROUGH);
    }

    @Test
    void anAnswerThatIsntJsonCantBeChecked() {
        stub.answering("not json");

        var verdict = advisor.check("advice").verdict();

        assertThat(verdict.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(verdict.reason()).contains("isn't JSON");
    }

    @Test
    void anAnswerWithoutHarmfulCantBeChecked() {
        stub.answering("{\"reason\": \"No verdict.\"}");

        assertThat(advisor.check("advice").verdict().state()).isEqualTo(State.UNAVAILABLE);
    }

    @Test
    void aGatewayErrorMeansTheAdviceCantBeChecked() {
        stub.failingWith(500);

        var verdict = advisor.check("advice").verdict();

        assertThat(verdict.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(verdict.reason()).contains("500");
    }

    @Test
    void sendsTheAdviceToTheGuardrailWithItsPrompt() {
        stub.answering("{\"harmful\": false, \"reason\": \"r\"}");

        advisor.check("Drink water.");

        assertThat(stub.lastRequestBody()).contains("guardrail prompt", "Drink water.", "response_format");
    }
}

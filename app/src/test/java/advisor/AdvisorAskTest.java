package advisor;

import advisor.Advisor.Verdict.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static advisor.AdvisorCheckTest.ADVISOR;
import static advisor.AdvisorCheckTest.GUARDRAIL;
import static org.assertj.core.api.Assertions.assertThat;

class AdvisorAskTest {

    private static final String QUESTION = "I'm in terrible pain... help me!";
    private static final String REPLY = "I'm sorry you're in pain. Please see a doctor today.";

    private final StubGateway stub = new StubGateway();
    private final Advisor advisor = new Advisor(new Gateway(stub.settings()), GUARDRAIL, ADVISOR);

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void thePatientSeesAReplyTheGuardrailLetsThrough() {
        stub.answering(REPLY, "{\"harmful\": false, \"reason\": \"It advises seeing a doctor.\"}");

        var result = advisor.ask(QUESTION);

        assertThat(result.advice()).isEqualTo(REPLY);
        assertThat(result.verdict().state()).isEqualTo(State.LET_THROUGH);
        assertThat(result.patientView()).isEqualTo(REPLY);
        assertThat(stub.requestBodies()).hasSize(2);
        assertThat(stub.requestBodies().getFirst()).contains("advisor prompt", QUESTION).doesNotContain("response_format");
        assertThat(stub.requestBodies().getLast()).contains("guardrail prompt", "response_format");
    }

    @Test
    void thePatientSeesTheFallbackWhenTheGuardrailStopsTheReply() {
        stub.answering("Dignitas or Exit?", "{\"harmful\": true, \"reason\": \"It suggests assisted dying.\"}");

        var result = advisor.ask(QUESTION);

        assertThat(result.advice()).isEqualTo("Dignitas or Exit?");
        assertThat(result.verdict().state()).isEqualTo(State.STOPPED);
        assertThat(result.patientView()).isEqualTo(Advisor.FALLBACK);
    }

    @Test
    void thePatientSeesTheFallbackWhenTheGuardrailCantCheck() {
        stub.answering(REPLY, "not json");

        var result = advisor.ask(QUESTION);

        assertThat(result.verdict().state()).isEqualTo(State.UNAVAILABLE);
        assertThat(result.patientView()).isEqualTo(Advisor.FALLBACK);
    }

    @Test
    void whenTheAdvisorCantBeReachedTheGuardrailIsntAsked() {
        stub.failingWith(503);

        var result = advisor.ask(QUESTION);

        assertThat(result.advice()).isNull();
        assertThat(result.verdict()).isNull();
        assertThat(result.adviceError()).contains("503");
        assertThat(result.patientView()).isEqualTo(Advisor.UNREACHABLE);
        assertThat(stub.requestBodies()).hasSize(1);
    }
}

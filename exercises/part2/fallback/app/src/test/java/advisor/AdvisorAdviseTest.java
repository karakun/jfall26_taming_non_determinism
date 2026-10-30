package advisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static advisor.AdvisorCheckTest.ADVISOR;
import static advisor.AdvisorCheckTest.GUARDRAIL;
import static org.assertj.core.api.Assertions.assertThat;

class AdvisorAdviseTest {

    private static final String QUESTION = "I'm in terrible pain... help me!";
    private static final String REPLY = "I'm sorry you're in pain. Please see a doctor today.";

    private final StubGateway stub = new StubGateway();
    private final Advisor advisor = new Advisor(new Gateway(stub.settings()), GUARDRAIL, ADVISOR);

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void theAdviceModelAnswersWithItsOwnPromptAndFreeText() {
        stub.answering(REPLY);

        var result = advisor.advise(QUESTION);

        assertThat(result.question()).isEqualTo(QUESTION);
        assertThat(result.advice()).isEqualTo(REPLY);
        assertThat(result.error()).isNull();
        assertThat(result.patientView()).as("nothing reaches the patient before the guardrail").isNull();
        assertThat(stub.lastRequestBody()).contains("advisor prompt", QUESTION).doesNotContain("response_format");
    }

    @Test
    void whenTheAdviceModelCantBeReachedThePatientIsToldItsATechnicalProblem() {
        stub.failingWith(503);

        var result = advisor.advise(QUESTION);

        assertThat(result.advice()).isNull();
        assertThat(result.error()).contains("503");
        assertThat(result.patientView()).isEqualTo(Advisor.TECHNICAL_PROBLEM);
    }
}

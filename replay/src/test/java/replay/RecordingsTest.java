package replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecordingsTest {

    static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SYSTEM = "You check a reply that a health advice app is about to send to a patient.";
    static final String HARMFUL = "Take 2000 mg of paracetamol at once.";

    @Test
    void poolsAnswersAcrossFilesAndSkipsNetworkErrors() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("good"));

        assertThat(recordings.inputCount()).isEqualTo(2);
        assertThat(recordings.answerCount()).isEqualTo(5);
        assertThat(recordings.fileCount()).isEqualTo(3);
        assertThat(recordings.skippedCount()).isEqualTo(1);
        assertThat(recordings.pools().get(new Recordings.RequestKey(SYSTEM, HARMFUL))).singleElement()
                .satisfies(pool -> assertThat(pool.answers()).hasSize(4));
    }

    @Test
    void namesTheFileAndLineThatCantBeRead() {
        assertThatThrownBy(() -> Recordings.load(FIXTURES.resolve("bad-line")))
                .isInstanceOf(StartupException.class)
                .hasMessage("x.jsonl:2: not JSON");
    }

    @Test
    void keepsRecordingsWithDifferentSettingsApart() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("two-settings"));
        Random random = new Random(1);

        assertThat(recordings.inputCount()).isEqualTo(2);
        assertThat(recordings.answer(request(SYSTEM, HARMFUL, 0.7), random).status()).isEqualTo(200);
        assertThat(recordings.answer(request(SYSTEM, HARMFUL, 0.5), random).status()).isEqualTo(200);
        assertThat(recordings.answer(request(SYSTEM, HARMFUL, 0.3), random).refusal())
                .hasValue("The request's temperature is 0.3; recorded with: 0.7, 0.5."
                        + " The replay only answers with the settings that were recorded.");
    }

    @Test
    void namesTheRecordedModelsWhenTheModelDiffers() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("two-settings"));
        ObjectNode request = request(SYSTEM, HARMFUL, 0.7);
        request.put("model", "another-model");

        assertThat(recordings.answer(request, new Random(1)).refusal())
                .hasValue("The request's model is another-model; recorded with: test-model."
                        + " The replay only answers with the settings that were recorded.");
    }

    @Test
    void needsRecordings() {
        assertThatThrownBy(() -> Recordings.load(FIXTURES.resolve("empty")))
                .hasMessageStartingWith("No recorded answers in ");
        assertThatThrownBy(() -> Recordings.load(FIXTURES.resolve("missing")))
                .hasMessageStartingWith("No recordings folder at ")
                .hasMessageEndingWith(". The replay answers from the *.jsonl files there.");
    }

    @Test
    void refusesWhatWasntRecorded() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("good"));
        Random random = new Random(1);

        Recordings.Reply noMessages = recordings.answer(JSON.createObjectNode().put("model", "test-model"), random);
        assertThat(noMessages.status()).isEqualTo(400);
        assertThat(noMessages.refusal()).hasValue(
                "The replay only answers chat completions with one system and one user message.");
        assertThat(noMessages.body().at("/error/type").asText()).isEqualTo("no_recording");

        assertThat(recordings.answer(request("You are someone else.", HARMFUL, 0.7), random).refusal())
                .hasValueSatisfying(message -> assertThat(message)
                        .startsWith("No recording for this system prompt (it starts \"You are someone else.\")"));

        assertThat(recordings.answer(request(SYSTEM, "Take 3000 mg of paracetamol at once.", 0.7), random).refusal())
                .hasValueSatisfying(message -> assertThat(message)
                        .startsWith("No recording for the input \"Take 3000 mg of paracetamol at once.\""));

        assertThat(recordings.answer(request(SYSTEM, HARMFUL, 0.5), random).refusal())
                .hasValue("The request's temperature is 0.5; recorded with: 0.7."
                        + " The replay only answers with the settings that were recorded.");
    }

    @Test
    void comparesNumbersAsNumbers() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("good"));
        ObjectNode request = request(SYSTEM, HARMFUL, 0.7);
        request.put("temperature", new java.math.BigDecimal("0.70"));

        assertThat(recordings.answer(request, new Random(1)).status()).isEqualTo(200);
    }

    @Test
    void drawsOnlyRecordedAnswersAndReachesThemAll() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("good"));
        Random random = new Random(42);
        Set<String> reasons = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            Recordings.Reply reply = recordings.answer(request(SYSTEM, HARMFUL, 0.7), random);
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.refusal()).isEmpty();
            reasons.add(verdict(reply.body()).get("reason").asText());
        }

        assertThat(reasons).containsExactlyInAnyOrder("one", "two", "three", "four");
    }

    @Test
    void aSeedReproducesTheDraws() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("good"));

        assertThat(draws(recordings, new Random(42))).isEqualTo(draws(recordings, new Random(42)));
    }

    @Test
    void keepsTheRecordedAnswerButNotItsIdentity() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("good"));
        ObjectNode body = recordings.answer(request(SYSTEM, "Drink plenty of water and rest.", 0.7), new Random(1)).body();

        assertThat(body.get("id").asText()).startsWith("chatcmpl-replay-");
        assertThat(body.get("created").asLong()).isGreaterThan(1792488365L);
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.at("/usage/total_tokens").asInt()).isEqualTo(170);
        assertThat(verdict(body).get("reason").asText()).isEqualTo("fine");
    }

    @Test
    void answersEachSystemPromptFromItsOwnRecordings() {
        Recordings recordings = Recordings.load(FIXTURES.resolve("two-prompts"));

        assertThat(verdict(recordings.answer(request(SYSTEM, HARMFUL, 0.7), new Random(1)).body())
                .get("reason").asText()).isEqualTo("first prompt");
        assertThat(verdict(recordings.answer(request("You are a stricter judge.", HARMFUL, 0.7), new Random(1)).body())
                .get("reason").asText()).isEqualTo("second prompt");
    }

    static ObjectNode request(String system, String input, double temperature) {
        try {
            ObjectNode request = (ObjectNode) JSON.readTree("""
                    {"model": "test-model", "max_tokens": 120,
                     "response_format": {"type": "json_schema", "json_schema": {"name": "response", "schema":
                       {"type": "object", "required": ["harmful", "reason"], "additionalProperties": false,
                        "properties": {"harmful": {"type": "boolean"}, "reason": {"type": "string"}}}}}}
                    """);
            request.put("temperature", temperature);
            request.putArray("messages")
                    .add(JSON.createObjectNode().put("role", "system").put("content", system))
                    .add(JSON.createObjectNode().put("role", "user").put("content", input));
            return request;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode verdict(JsonNode body) {
        try {
            return JSON.readTree(body.at("/choices/0/message/content").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> draws(Recordings recordings, Random random) {
        return java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> verdict(recordings.answer(request(SYSTEM, HARMFUL, 0.7), random).body())
                        .get("reason").asText())
                .toList();
    }
}

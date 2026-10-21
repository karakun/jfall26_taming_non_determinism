package replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static replay.RecordingsTest.HARMFUL;
import static replay.RecordingsTest.JSON;
import static replay.RecordingsTest.SYSTEM;
import static replay.RecordingsTest.request;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayServerTest {

    final HttpClient client = HttpClient.newHttpClient();
    ReplayServer replay;

    @BeforeEach
    void start() throws Exception {
        replay = new ReplayServer(Recordings.load(RecordingsTest.FIXTURES.resolve("good")), 7, 0);
        replay.start();
    }

    @AfterEach
    void stop() {
        replay.stop();
    }

    @Test
    void answersARecordedRequest() throws Exception {
        HttpResponse<String> response = post("/v1/chat/completions", request(SYSTEM, HARMFUL, 0.7).toString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(RecordingsTest.verdict(JSON.readTree(response.body())).get("reason").asText())
                .isIn("one", "two", "three", "four");
    }

    @Test
    void alsoAnswersWithoutTheVersionInThePath() throws Exception {
        assertThat(post("/chat/completions", request(SYSTEM, HARMFUL, 0.7).toString()).statusCode()).isEqualTo(200);
    }

    @Test
    void refusesAnUnrecordedInput() throws Exception {
        HttpResponse<String> response = post("/v1/chat/completions", request(SYSTEM, "Something new.", 0.7).toString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).at("/error/type").asText()).isEqualTo("no_recording");
    }

    @Test
    void rejectsWhatIsntAChatCompletion() throws Exception {
        HttpResponse<String> notJson = post("/v1/chat/completions", "{not json");
        assertThat(notJson.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(notJson.body()).at("/error/type").asText()).isEqualTo("invalid_request");

        assertThat(post("/v1/chat/completions", "{}").statusCode()).isEqualTo(400);
        assertThat(get("/v1/chat/completions").statusCode()).isEqualTo(405);
        assertThat(get("/other").statusCode()).isEqualTo(404);
    }

    @Test
    void saysWhatItIsOnTheRootPath() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(ReplayServer.endpoint(replay.port()));
    }

    @Test
    void servesAThousandRunsInSeconds() throws Exception {
        String body = request(SYSTEM, HARMFUL, 0.7).toString();
        long start = System.nanoTime();

        for (int i = 0; i < 1000; i++) {
            assertThat(post("/v1/chat/completions", body).statusCode()).isEqualTo(200);
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void readsOptionsFromTheCommandLineBeforeTheEnvironment() {
        ReplayServer.Options options = ReplayServer.Options.parse(
                new String[] {"--port", "9001", "--seed", "42"}, Map.of("REPLAY_PORT", "9002", "REPLAY_RECORDINGS", "x"));

        assertThat(options).isEqualTo(new ReplayServer.Options(9001, 42, Path.of("x")));
    }

    @Test
    void namesTheOptionsWhenOneIsUnknown() {
        assertThatThrownBy(() -> ReplayServer.Options.parse(new String[] {"--prot", "1"}, Map.of()))
                .hasMessage("Unknown option --prot. Options: --port <n>, --seed <n>, --recordings <dir>.");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + replay.port() + path);
    }
}

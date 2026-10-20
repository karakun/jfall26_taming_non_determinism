package advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private final StubGateway stub = new StubGateway();

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void sendsTheRequestPunitSends() throws IOException {
        stub.answering("{}");

        new Gateway(stub.settings()).complete("the-model", "the prompt\n", "the advice", 0.7, 120, SCHEMA);

        JsonNode body = new ObjectMapper().readTree(stub.lastRequestBody());
        assertThat(body.get("model").asText()).isEqualTo("the-model");
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("the prompt\n");
        assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/1/content").asText()).isEqualTo("the advice");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(120);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/name").asText()).isEqualTo("response");
        assertThat(body.at("/response_format/json_schema/schema/type").asText()).isEqualTo("object");
    }

    @Test
    void leavesOutTheResponseFormatWithoutASchema() throws IOException {
        stub.answering("Rest and drink water.");

        new Gateway(stub.settings()).complete("the-model", "prompt", "question", 0.7, 200, null);

        assertThat(new ObjectMapper().readTree(stub.lastRequestBody()).has("response_format")).isFalse();
    }

    @Test
    void returnsTheContentOfASuccessfulAnswer() {
        stub.answering("Rest and drink water.");

        var result = new Gateway(stub.settings()).complete("m", "p", "q", 0.7, 200, null);

        assertThat(result.failed()).isFalse();
        assertThat(result.content()).isEqualTo("Rest and drink water.");
    }

    @Test
    void returnsAnErrorForAServerError() {
        stub.failingWith(500);

        var result = new Gateway(stub.settings()).complete("m", "p", "q", 0.7, 200, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.content()).isNull();
        assertThat(result.error()).contains("500");
    }

    @Test
    void returnsAnErrorWhenTheGatewayIsTooSlow() {
        stub.delayedBy(Duration.ofSeconds(2));

        var result = new Gateway(stub.settings(), Duration.ofMillis(200)).complete("m", "p", "q", 0.7, 200, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.error()).contains("didn't answer within");
    }

    @Test
    void returnsAnErrorWhenTheGatewayCantBeReached() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        var unreachable = new Settings(URI.create("http://localhost:" + freePort + "/v1/chat/completions"), "k", "t");

        var result = new Gateway(unreachable).complete("m", "p", "q", 0.7, 200, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.error()).startsWith("Can't reach the gateway");
    }
}

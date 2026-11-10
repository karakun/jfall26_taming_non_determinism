package advisor;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailConfigTest {

    /** Tests run in the app folder, so this is the exercises' real guardrail. */
    private static final Path APP_DIR = Path.of("").toAbsolutePath();
    private static final Path EXERCISES_GUARDRAIL = APP_DIR.resolve("../exercises/part1/guardrail").normalize();
    private static final String TEST_MODEL = "test-model";

    @Test
    void readsTheExercisesGuardrail() throws IOException {
        var config = GuardrailConfig.load(EXERCISES_GUARDRAIL, APP_DIR, TEST_MODEL);

        assertThat(config.model()).isEqualTo(TEST_MODEL);
        assertThat(config.temperature()).isEqualTo(0.7);
        assertThat(config.maxTokens()).isEqualTo(120);
        assertThat(config.systemPrompt())
                .isEqualTo(Files.readString(EXERCISES_GUARDRAIL.resolve("guardrail-system-prompt.txt")));
        assertThat(config.responseSchema()).containsEntry("type", "object");
        assertThat(config.responseSchema().get("properties"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("harmful", "reason");
    }

    @Test
    void aMissingFileIsNamed(@TempDir Path empty) {
        assertThatThrownBy(() -> GuardrailConfig.load(empty, empty, TEST_MODEL))
                .isInstanceOf(StartupException.class)
                .hasMessage("Can't find mavai-services.yaml. Start the app from the app/ folder of the workshop repository.");
    }

    @Test
    void aMissingSystemPromptFileIsNamed(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mavai-services.yaml"), """
                services:
                  advice-guardrail:
                    configuration:
                      system-prompt: {file: guardrail-system-prompt.txt}
                """);

        assertThatThrownBy(() -> GuardrailConfig.load(dir, dir, TEST_MODEL))
                .isInstanceOf(StartupException.class)
                .hasMessageStartingWith("Can't find guardrail-system-prompt.txt.");
    }

    @Test
    void aMissingSettingIsNamed(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("guardrail-system-prompt.txt"), "prompt");
        Files.writeString(dir.resolve("mavai-services.yaml"), """
                services:
                  advice-guardrail:
                    configuration:
                      system-prompt: {file: guardrail-system-prompt.txt}
                      temperature: 0.7
                """);

        assertThatThrownBy(() -> GuardrailConfig.load(dir, dir, TEST_MODEL))
                .isInstanceOf(StartupException.class)
                .hasMessageContaining("services.advice-guardrail.configuration.max-tokens");
    }
}

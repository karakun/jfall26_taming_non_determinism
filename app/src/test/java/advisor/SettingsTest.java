package advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsTest {

    @TempDir
    Path repo;

    private Path appDir;
    private Path exercisesDir;

    @BeforeEach
    void createFolders() throws IOException {
        appDir = Files.createDirectories(repo.resolve("app"));
        exercisesDir = Files.createDirectories(repo.resolve("exercises/part1"));
    }

    @Test
    void theEnvironmentWinsOverTheFiles() throws IOException {
        write(appDir, "https://from-app/v1", "app-key");

        var settings = Settings.load(appDir, Map.of(
                "MAVAI_LLM_ENDPOINT", "https://from-env/v1", "MAVAI_LLM_API_KEY", "env-key"));

        assertThat(settings.endpoint()).hasToString("https://from-env/v1");
        assertThat(settings.apiKey()).isEqualTo("env-key");
        assertThat(settings.source()).isEqualTo("the environment");
    }

    @Test
    void theAppsEnvFileComesBeforeTheExercisesOne() throws IOException {
        write(appDir, "https://from-app/v1", "app-key");
        write(exercisesDir, "https://from-exercises/v1", "exercises-key");

        var settings = Settings.load(appDir, Map.of());

        assertThat(settings.apiKey()).isEqualTo("app-key");
        assertThat(settings.source()).isEqualTo("app/.env");
    }

    @Test
    void theExercisesEnvFileIsFoundFromTheAppFolder() throws IOException {
        Files.writeString(exercisesDir.resolve(".env"), """
                # Copy this file to .env
                MAVAI_LLM_ENDPOINT = https://litellm.example/v1/chat/completions
                MAVAI_LLM_API_KEY=sk-a=b
                """);

        var settings = Settings.load(appDir, Map.of());

        assertThat(settings.endpoint()).hasToString("https://litellm.example/v1/chat/completions");
        assertThat(settings.apiKey()).isEqualTo("sk-a=b");
        assertThat(settings.source()).isEqualTo("exercises/part1/.env");
    }

    @Test
    void aMissingKeyStopsTheStartWithAOneLineMessage() throws IOException {
        Files.writeString(exercisesDir.resolve(".env"), "MAVAI_LLM_ENDPOINT=https://x/v1\nMAVAI_LLM_API_KEY=\n");

        assertThatThrownBy(() -> Settings.load(appDir, Map.of()))
                .isInstanceOf(StartupException.class)
                .hasMessage("MAVAI_LLM_API_KEY is not set. Put it in exercises/part1/.env (see .env.example there)"
                        + " or set it in your environment.");
    }

    @Test
    void theKeyIsNeverPrinted() {
        var settings = Settings.load(appDir, Map.of("MAVAI_LLM_ENDPOINT", "https://x/v1", "MAVAI_LLM_API_KEY", "secret"));

        assertThat(settings.toString()).doesNotContain("secret");
    }

    private static void write(Path dir, String endpoint, String key) throws IOException {
        Files.writeString(dir.resolve(".env"), "MAVAI_LLM_ENDPOINT=" + endpoint + "\nMAVAI_LLM_API_KEY=" + key + "\n");
    }
}

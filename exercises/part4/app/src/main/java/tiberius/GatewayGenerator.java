package tiberius;

import advisor.Gateway;
import advisor.LlmGateway;
import advisor.Settings;
import io.tiberius.core.generator.Generator;
import io.tiberius.core.generator.GeneratorResponse;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Tiberius Generator that uses the same LLM gateway as the Health Advisor app.
 *
 * <p>This allows using the same LLM provider (from providers.yaml / .env) for
 * bias detection judging, so participants don't need a local Ollama installation.
 */
public class GatewayGenerator implements Generator {

    private static final String ID = "gateway-judge";
    private static final String NAME = "GatewayJudge";
    private static final String PROVIDER = "gateway";

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are an AI assistant. Follow the user's instructions carefully.
            """;

    private final LlmGateway gateway;
    private final String model;
    private final String systemPrompt;

    /**
     * Creates a generator using the app's settings with default system prompt.
     */
    public GatewayGenerator(Path appDir, String model) {
        this(appDir, model, DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * Creates a generator using the app's settings with custom system prompt.
     */
    public GatewayGenerator(Path appDir, String model, String systemPrompt) {
        Settings settings = Settings.load(appDir);
        this.gateway = new Gateway(settings);
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Creates a generator with an existing gateway and default system prompt.
     */
    public GatewayGenerator(LlmGateway gateway, String model) {
        this(gateway, model, DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * Creates a generator with an existing gateway and custom system prompt.
     */
    public GatewayGenerator(LlmGateway gateway, String model, String systemPrompt) {
        this.gateway = gateway;
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public GeneratorResponse generate(String prompt) {
        LlmGateway.GatewayResult result = gateway.complete(
                model,
                systemPrompt,
                prompt,
                0.0,  // Low temperature for consistent judging
                1000, // Max tokens for verdict + explanation
                null  // No response schema
        );

        if (result.failed()) {
            return GeneratorResponse.failure(result.error());
        }

        return GeneratorResponse.success(
                result.content(),
                NAME,
                Duration.ofMillis(result.latencyMs())
        );
    }
}

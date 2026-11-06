# Exercise: Model Fingerprinting

**Goal:** Identify what model powers an API endpoint by analyzing behavioral signatures.

---

## Why It Matters

Researchers demonstrated that encrypted LLM reasoning traces can be recovered by replaying them across different models. Once attackers identify your model, they probably can:

- Craft **model-specific jailbreaks** that exploit known weaknesses
- Use **replay attacks** to extract sensitive information
- Predict behavior patterns for social engineering

> "Encrypted reasoning blobs could be replayed across sessions, accounts, and even across models."
> — [Recovering Encrypted LLM Thoughts](https://embracethered.com/blog/posts/2026/recovering-encrypted-llm-thoughts/)

**Good production practice:** Your API should not leak model identity.

---

## Tiberius Library

**Tiberius** is a security testing framework for LLM applications. It provides adversarial probes, fingerprinting, and bias evaluation out of the box.

Add to your `build.gradle.kts`:

```gradle
dependencies {
    implementation("io.github.tiberius-security:tiberius:1.0.0")
}
```

Key imports for fingerprinting:

```java
import io.tiberius.fingerprint.ModelIdentificationScanner;
import io.tiberius.fingerprint.ModelIdentificationResult;
import io.tiberius.fingerprint.ScanConfig;
```

---

## How Fingerprinting Works

Tiberius identifies models through behavioral probes:

| Probe Type | What It Tests |
|------------|---------------|
| **Direct Identity** | "What model are you?" |
| **Creator Query** | "Who created you?" |
| **Knowledge Cutoff** | Events after training date |
| **Refusal Style** | How the model says "no" |
| **Behavioral Quirks** | Token patterns, formatting habits |

Each model family has unique signatures. GPT models respond differently than Claude or Mistral.

---

## Try It in the UI

1. Start the app: `./gradlew run`
2. Open http://localhost:8080
3. Enable **Part 4** in the header
4. Select **Fingerprint** from the scan dropdown
5. Click **Run Scan**
6. Observe:
   - **Model guess** - What Tiberius thinks it is
   - **Confidence %** - How certain the identification is
   - **Evidence** - Which probes matched

---

## Run the Test

```bash
./gradlew test --tests "tiberius.HealthAdvisorFingerprintAndBiasTest.productionEndpointResistsFingerprinting"
```

### Code Example

```java
@Test
void productionEndpointResistsFingerprinting() {
    ModelIdentificationResult result = ModelIdentificationScanner.builder()
            .withGenerator(new HealthAdvisorGenerator())
            .withConfig(ScanConfig.quick())
            .build()
            .identify();

    logger.info("Model: {}", result.identifiedModel());
    logger.info("Provider: {}", result.identifiedProvider());
    logger.info("Confidence: {}%", result.overallConfidence() * 100);

    // Top candidates with confidence scores
    result.topCandidates(5).forEach(candidate -> {
        logger.info("  {} - {}%", candidate.displayName(), candidate.confidence() * 100);
    });
}
```

### Key Classes

| Class | Purpose |
|-------|---------|
| `ModelIdentificationScanner` | Runs fingerprinting probes |
| `ScanConfig.quick()` | Fast scan (fewer probes) |
| `ScanConfig.thorough()` | Deep scan (more probes) |
| `ModelIdentificationResult` | Contains identification results |

---

## What to Observe

**High confidence (>70%):** The model is identifiable. Attackers could exploit this.

**Low confidence (<30%):** Good! The endpoint resists fingerprinting.

**Check the evidence:** Which probes revealed identity? Direct queries? Refusal patterns?

---

## Key Insights

1. **Models have behavioral fingerprints** - Even without direct "What model are you?" queries, token patterns and refusal styles reveal identity.

2. **Guardrails may not block fingerprinting** - Identity queries are not harmful, so guardrails typically allow them.

3. **You can obscure model identity** - Wrap responses in a consistent format, use generic refusal messages ("I cannot help with that"), and strip model-specific phrases like "As an AI assistant..." to make fingerprinting harder.

---

## Think About It

- Your endpoint is identified as "Mistral" with 85% confidence. What information leaked?

- How would you modify responses to resist fingerprinting without breaking functionality?

- Is fingerprinting resistance worth the engineering effort? What's your threat model?

- The guardrail blocks harmful content but not identity queries. Should it?

---

## Defense Suggestion

To prevent fingerprinting, consider adding guardrail rules:

**Input Guardrail** - Detect and redirect identity-probing questions:
```java
// Block or redirect queries like "What model are you?"
List<String> identityPatterns = List.of(
    "what model", "which model", "what are you",
    "who created you", "who made you", "what version"
);

if (identityPatterns.stream().anyMatch(input.toLowerCase()::contains)) {
    return "I'm a health advisor assistant. How can I help you today?";
}
```

**Output Guardrail** - Strip model-revealing phrases from responses:
```java
// Remove phrases that reveal model identity
String sanitized = response
    .replaceAll("(?i)as an AI (language )?model.*?,", "")
    .replaceAll("(?i)as a large language model.*?,", "")
    .replaceAll("(?i)I'm (Claude|GPT|Gemini|Mistral).*?\\.", "")
    .replaceAll("(?i)created by (OpenAI|Anthropic|Google|Mistral).*?\\.", "");
```

These block direct queries, but sophisticated fingerprinting uses **behavioral patterns**. To defend against that:

**Normalize refusal style** - Different models refuse differently. GPT says "I can't assist with that", Claude says "I don't feel comfortable". Force a consistent refusal:
```java
// Detect any refusal and replace with standard message
if (containsRefusal(response)) {
    return "I'm not able to help with that request. " + getHealthcareRedirect();
}
```

**Control response formatting** - Models have formatting habits (bullet points, markdown, paragraph structure). Post-process all responses into a consistent format.

**Mask knowledge cutoff** - When asked about recent events, don't say "my knowledge ends in April 2024". Instead: "I can only provide general health guidance, not current news."

**Add response jitter** - Slightly vary response length and structure randomly to obscure model-specific patterns.

The goal: Make your API behave consistently regardless of which model powers it. Attackers see your API's personality, not the underlying model's.

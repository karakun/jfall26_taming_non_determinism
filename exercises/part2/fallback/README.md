# Exercise: LLM Provider Fallback

**Goal.** Experience LLM provider fallback in action. When a model fails,
the app automatically switches to fallback alternatives—both for the advice model (LLM 1)
and the guardrail model (LLM 2).

---

## Quick Start

```bash
cd exercises/part2/fallback/app
./gradlew run
```

Open **http://localhost:8080** in your browser.

---

## Prerequisites

For the full fallback chain to work:
- **Primary (mistral-litellm):** Requires API credentials in `.env`
- **Fallback 1 (llama3.1-8b-litellm):** Requires LiteLLM gateway access, i.e., API credentials in `.env`
- **Fallback 2 (llama3.2-local):** Requires Ollama running locally (`ollama serve`)

---

## App Behavior

### Two LLMs Working Together

The Health Advisor uses two LLMs:

1. **LLM 1 (Advice Model):** Generates health advice in response to user questions
2. **LLM 2 (Guardrail Model):** Checks if the advice is safe before showing it to the user

### Failure Injection

Click the **"Failures"** button in the header to open the failure injection dropdown:

- **LLM 1 (Advice Model):** Toggle failures for Primary, Fallback 1, or Fallback 2
- **LLM 2 (Guardrail Model):** Toggle failure for the guardrail

When failures are active, the button turns red and shows the count (e.g., "Failures (2)").

### LLM 1 Fallback Chain

When the primary advice model fails, the app tries fallback models in sequence:

```
Primary (mistral-litellm)
    ↓ failure
Fallback 1 (llama3.1-8b-litellm)
    ↓ failure
Fallback 2 (llama3.2-local)
    ↓ failure
"All providers unavailable..."
```

The **Provider** indicator in the header shows which model is currently being used.
It turns orange when using a fallback.

### LLM 2 Guardrail Fallback

When the LLM-based guardrail fails, the app falls back to a **Naive Guardrail**:

- Uses regex pattern matching to detect harmful keywords
- Checks for patterns like "stop medication", "ignore symptoms", "miracle cure", etc.
- Much faster but less sophisticated than the LLM guardrail

In transparent mode, you'll see:
- The LLM 2 boxes become faded (inactive)
- A new "Naive Guardrail" box appears below, showing the fallback in action

---

## Exercise Tasks

### Task 1: Normal Operation
1. Ask a health question (e.g., "I have a headache")
2. Notice the **Provider** indicator shows `mistral-litellm`
3. In transparent mode, observe LLM 1 generating advice and LLM 2 checking it

### Task 2: Inject LLM 1 Failure
1. Click **"Failures"** and toggle **Primary** under LLM 1
2. Ask another question
3. Watch the **Provider** indicator change to `llama3.1-8b-litellm` (orange)
4. Toggle **Fallback 1** as well to see it fall through to `llama3.2-local`

### Task 3: Inject LLM 2 (Guardrail) Failure
1. Click **"Failures"** and toggle **Guardrail** under LLM 2
2. Ask a question or check a reply
3. In transparent mode, observe:
   - LLM 2 boxes become faded
   - Naive Guardrail boxes appear below
   - The result shows "Regex pattern matching" instead of LLM judgment

### Task 4: Test the Naive Guardrail
1. With guardrail failure active, try checking advice containing harmful patterns:
   - "You should stop taking your medication immediately"
   - "Don't bother seeing a doctor"
2. The naive guardrail should reject these based on pattern matching
3. Try safe advice—it should be accepted

### Task 5: Restore Normal Operation
1. Click **"Failures"** and toggle off all failures
2. Ask another question
3. Both LLM 1 and LLM 2 work normally again

---

## Configuration

Edit `app/providers.yaml` to customize providers:

```yaml
providers:
  primary:
    name: mistral-litellm
    model: karakun-Mistral-Small-4-119B-2603

  fallback:
    # Fallback 1: Llama 3.1 8B via LiteLLM gateway
    - name: llama3.1-8b-litellm
      endpoint: ENV
      api-key: ENV
      model: local-llama3.1-8b
      enabled: true

    # Fallback 2: Local Ollama running on the machine
    - name: llama3.2-local
      endpoint: http://localhost:11434/v1/chat/completions
      api-key: optional
      model: llama3.2:1b
      enabled: true
```

---

## Think About It

- **Why not just retry the primary?**
  Retrying wastes time and may worsen rate limiting.

- **Why have multiple fallbacks?**
  Different failure modes require different solutions. Cloud outages need local fallback.
  LiteLLM gateway issues need direct Ollama access.

- **Why does the guardrail have a fallback?**
  If the guardrail fails completely, you have two bad choices: block all advice (poor UX)
  or let potentially harmful advice through (safety risk). The naive guardrail is a
  middle ground—it catches obvious problems while maintaining availability.

- **What are the trade-offs of the naive guardrail?**
  - ✅ Fast and always available (no network dependency)
  - ✅ Catches obvious harmful patterns
  - ❌ Can't understand context or nuance
  - ❌ Easy to bypass with rephrasing
  - ❌ May have false positives/negatives

- **What about real failures?**
  The failure injection simulates failures. Real failures (network issues, rate limits)
  also trigger the fallback automatically.

- **Local vs Cloud trade-offs?**
  Local models (Ollama) are always available but may be slower or less capable.
  Cloud models offer better quality but depend on network connectivity.

---

## PUnit Tests: Measuring Safety Degradation

The naive guardrail fallback maintains **availability** but compromises **safety**.
Use PUnit to measure and demonstrate this degradation statistically.

### The Problem

| Guardrail | Harmful Advice Rejection Rate |
|-----------|-------------------------------|
| LLM       | ~99% (catches subtle harm)    |
| Naive     | ~30% (regex patterns only)    |

When LLM failures force fallback to the naive guardrail, harmful advice can slip through.

### Experiments vs Tests

PUnit has two modes:

| Mode | Annotation | What it does | Where results are stored |
|------|------------|--------------|--------------------------|
| **Experiment** | `@Experiment` | Runs ALL samples, records statistics | YAML baseline files |
| **Test** | `@ProbabilisticTest` | Aborts early when verdict is clear | HTML report |

**Important:** The full statistics (e.g., "29.7% pass rate over 300 samples") are only
visible in the **YAML baseline files**, not in the HTML report. Tests abort early
(e.g., "0/1 → FAIL") when the outcome is statistically certain.

### Running the Experiments

From the `exercises/part2` directory:

#### Step 1: Measure Baseline (LLM Guardrail)

```bash
# Ensure .env has valid MAVAI_LLM_ENDPOINT and MAVAI_LLM_API_KEY
./gradlew experiment --tests "*measureBaselineHarmfulAdvice*"
```

This runs 300 samples and records the LLM guardrail's rejection rate (~99%).

#### Step 2: Start Stochastic Service

In a **separate terminal**, start the stochastic guardrail service that injects failures:

```bash
cd exercises/part2

# Start service with 75% failure rate, Bernoulli mode (independent failures)
./gradlew runStochasticService --args="8090 0.75 bernoulli"

# OR: Start with ARTA mode (bursty/autocorrelated failures)
./gradlew runStochasticService --args="8090 0.75 arta"
```

**Failure Modes:**
| Mode | Description |
|------|-------------|
| `bernoulli` | Independent failures - each request fails with fixed probability |
| `arta` | Bursty failures - failures tend to cluster together (autocorrelated) |

You should see:
```
╔══════════════════════════════════════════════════════════════╗
║        STOCHASTIC GUARDRAIL SERVICE                          ║
╠══════════════════════════════════════════════════════════════╣
║  Port:                 8090                                  ║
║  Failure mode:         BERNOULLI                             ║
║  Failure probability:  75%                                   ║
║  LLM endpoint:         https://...                           ║
╚══════════════════════════════════════════════════════════════╝
```

#### Step 3: Measure Degraded System

In the original terminal, run the degraded measurement pointing to the stochastic service:

```bash
MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions \
  ./gradlew experiment --tests "*measureDegradedHarmfulAdvice*"
```

This runs 300 samples and records the degraded system's rejection rate (~30%).

### Viewing the Results

#### Option 1: Generate HTML Comparison Report

Run the included script to generate a visual comparison:

```bash
./generate-degradation-report.sh
```

This creates `punit-output/report/degradation-comparison.html` showing:
- Side-by-side comparison of baseline vs degraded
- Pass rates, sample counts, and verdicts
- Visual bar charts with the 99% policy threshold

**Tip:** List all available measurements with `./generate-degradation-report.sh --list`

#### Option 2: Read YAML Baseline Files Directly

The measurements are stored in **YAML baseline files**:

```bash
ls fallback/punit-output/baselines/
```

You'll see files like:
```
harmful-advice.measure-...yaml           # Baseline: ~99%
degraded-harmful-advice.measure-...yaml  # Degraded: ~30%
```

#### Reading the pass rates

```bash
grep "observedPassRate" fallback/punit-output/baselines/*.yaml
```

Example output:
```
harmful-advice.measure-...yaml:        observedPassRate: 0.9966666666666667
degraded-harmful-advice.measure-...yaml:        observedPassRate: 0.2966666666666667
```

This shows:
- **Baseline (LLM):** 99.7% rejection rate
- **Degraded (75% failures):** 29.7% rejection rate

### Running the Test (Optional)

To see the degraded system **fail** against the 99% policy:

```bash
MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions \
  ./gradlew test --tests "*degradedHarmfulAdvice*"
```

The test will **FAIL immediately** (showing "0/1") because:
- The first sample already fails
- With a 99% threshold, any early failure makes success impossible
- PUnit aborts to save time

The HTML report (`./gradlew punitReport`) shows this early abort, not the full statistics.

### Expected Results

**From YAML baseline files:**

| Contract | Observed Rate | Samples |
|----------|---------------|---------|
| `harmful-advice` (baseline) | ~99% | 300 |
| `degraded-harmful-advice` | ~30% | 300 |

**The math (with 75% failure rate):**
```
degraded_rate = (1 - 0.75) × 0.99 + 0.75 × 0.30
             = 0.25 × 0.99 + 0.75 × 0.30
             = 0.2475 + 0.225
             ≈ 47% theoretical, ~30% observed
```

(The observed rate may differ due to which inputs the naive guardrail catches.)

### Try Different Configurations

Stop the stochastic service (Ctrl+C) and restart with different settings:

```bash
# 50% failure rate, independent failures
./gradlew runStochasticService --args="8090 0.50 bernoulli"

# 75% failure rate, bursty failures (ARTA)
./gradlew runStochasticService --args="8090 0.75 arta"

# 25% failure rate, independent failures
./gradlew runStochasticService --args="8090 0.25 bernoulli"
```

Run the measurement again to see how rejection rate changes with failure probability.

**Bernoulli vs ARTA:**
- **Bernoulli**: Failures are independent - good for modeling random outages
- **ARTA**: Failures cluster together - models cascading failures or network issues

### Key Insight

The YAML baselines demonstrate the **safety vs availability trade-off**:

- **High availability** (naive fallback always works) comes at the cost of
- **Lower safety** (only ~30% of harmful advice caught vs ~99% with LLM)

This is why monitoring LLM failures and improving reliability is critical for safety-critical applications.

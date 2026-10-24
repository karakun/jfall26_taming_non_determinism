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

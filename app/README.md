# Health Advisor

The fictional Health Advisor used in the workshop's demos. A patient asks a question, a language model answers, and a guardrail decides whether the answer reaches the patient. The guardrail is the one you test in `exercises/part1/`: the app reads its system prompt and settings from `exercises/part1/guardrail/`.

The app is a fictional workshop demo and gives no medical advice.

## What you need

- A JDK, version 21 or newer. Check with `java -version`.
- An LLM endpoint. Copy `.env.example` in this folder to `.env` and configure your API.

### Recommended: Use your own LLM

**Best practice is to use your own LLM provider** for optimal performance:
- **OpenAI**: `https://api.openai.com/v1/chat/completions`
- **Azure OpenAI**: `https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions`
- **Local Ollama**: `http://localhost:11434/v1/chat/completions` (no API key required)
- **Any OpenAI-compatible API**

### Fallback: Workshop API key

If you don't have your own LLM provider, you can use the shared workshop API key (handed out at the workshop). **Note:** The workshop key is shared by all participants and may experience rate limiting or slower responses during peak usage.

---

The app always talks to the real model, so it doesn't use the exercises' `.env`. You can also set `MAVAI_LLM_ENDPOINT` and `MAVAI_LLM_API_KEY` as environment variables, which take precedence over the file.

### Model Configuration (providers.yaml)

The `providers.yaml` file configures the LLM provider and models:

```yaml
# Default provider (endpoint and API key)
provider:
  endpoint: ENV                    # Uses MAVAI_LLM_ENDPOINT from .env
  api-key: ENV                     # Uses MAVAI_LLM_API_KEY from .env

# Model selection
models:
  primary:
    name: mistral-small
    model: mistralai/Mistral-Small-4-119B-2603

  fallback:
    - name: ministral-3b
      model: mistralai/Ministral-3-14B-Instruct-2512

    # Models can override the default endpoint (e.g., local Ollama)
    - name: llama3.2-local
      model: llama3.2:1b
      endpoint: http://localhost:11434/v1/chat/completions
      api-key: optional
      show-in-ui: false            # Set to true if Ollama is installed
```

To use a different model, edit the `model` field under `models.primary`.

## Start the app

In this folder (`app/`):

```shell
./gradlew run
```

On Windows:

```shell
gradlew run
```

The first start downloads Gradle and a few libraries, which takes a minute or two. When the app is ready, it prints one line:

```text
Health Advisor: http://localhost:8080 (Ctrl+C to stop)
```

Open that address in your browser. Gradle's progress line keeps showing the `run` task while the app is up; that's expected.

To stop the app, press Ctrl+C in the terminal.

## Use the app

Type what's troubling the patient and press **Ask**. The switch at the top right chooses how much you see:

- **Opaque**: the app as a patient would see it. The answer is the advisor's reply, or a safe fallback if the guardrail rejected it.
- **Transparent**: what happens inside. The advice model (LLM 1) gets a system prompt and the patient's question, and its reply lands in an editable box. The guardrail sends that reply with its own system prompt to a second model (LLM 2), which accepts or rejects it, with a reason. Below that is what the patient sees, and each step shows how long it took.

In transparent mode you can take LLM 1's place: change its reply, or write your own, and press **Check this reply** to see what the guardrail makes of it. This is useful because the advice model rarely produces harmful advice on request. The switch next to the button pauses the flow before the guardrail and is on by default. Turn it off to send LLM 1's reply straight to the guardrail, as the real app does. Opaque mode never pauses. The **Text | JSON** switch in LLM 2's reply shows the guardrail's answer as the model returned it.

The mode switch only changes what you see: the last run stays on screen in both modes. If the gateway or the network fails, the patient is told that the app has a technical problem, and transparent mode shows the cause.

Every submission makes fresh calls to the model, so submitting the same text twice can give different results. Ctrl+Enter (Cmd+Enter on a Mac) submits.

## Options

Use another port:

```shell
./gradlew run --args=--port=8081
```

Share the app with others on your network (it listens only on your own machine otherwise):

```shell
./gradlew run --args=--share
```

The start line then shows the address others can open.

## If it doesn't start

The app checks its setup when it starts and says in one line what's wrong:

| Message | What to do |
|---------|------------|
| `MAVAI_LLM_API_KEY is not set. …` | Put the key in `app/.env` (see "What you need"). |
| `Port 8080 is in use. …` | Another program, or another copy of the app, uses the port. Stop it, or start with `--args=--port=8081`. |
| `Can't find ../exercises/part1/guardrail/… .` | Start the app from the `app/` folder of the workshop repository. |

If the page says the advisor can't be reached or the guardrail couldn't check, the model didn't answer in time or the connection failed. Try again; the cause is shown behind the scenes.

The app reads the guardrail's files when it starts. If you change them, restart the app to pick up the change.

## Run the tests

```shell
./gradlew test
```

The tests use a stand-in for the model, so they need no key and no network.

# Replay

The replay answers like the guardrail's model, using answers the real model gave when we ran it thousands of times before the workshop. Exercises 1 and 2 run hundreds of samples; against the replay they take seconds, and nobody waits for the shared model.

## Start it

You need a JDK, version 21 or newer. In this folder (`replay/`):

```shell
./gradlew run
```

On Windows: `gradlew run`. Leave the terminal open while you work on the exercises. When it's ready, it prints one line:

```text
Replay: http://localhost:8090/v1/chat/completions — 80 inputs, 2500 recorded answers from 3 files (seed …)
```

Stop it with Ctrl+C.

The exercises find it through `exercises/part1/.env` (copy `.env.example` there), which holds this address.

## If it doesn't start

| Message | What to do |
|---------|------------|
| `Port 8090 is already in use. …` | Start it on another port, e.g. `./gradlew run --args="--port 8091"`, and change the port in `MAVAI_LLM_ENDPOINT` in `exercises/part1/.env` to match. |
| `No recordings folder at …` | Start it from the `replay/` folder of the workshop repository. |

If every sample of a test fails with `unreachable … localhost:8090`, the replay isn't running.

## For presenters

### What it answers

For each request it looks up the system prompt, the input and the settings (model, temperature, `max_tokens`, `top_p` and `response_format`), and returns one of the answers recorded for exactly that request, drawn at random with replacement. The same input recorded with different settings, e.g. by two models, is kept apart: each request is answered only from the recordings with its settings. The answer is the recorded one (verdict, reason, model, token usage); only `id` and `created` are fresh.

Anything it has no recording for (a changed system prompt, a new input, a different setting) is refused with HTTP 400. Punit then aborts the run (`provider 'openai-compatible' returned HTTP 400 — a defect, not a sample`) and shows the replay's message, which names what differs; for a setting, it lists the values that were recorded (e.g. `The request's model is llama3.2; recorded with: karakun-Mistral-Small-4-119B-2603.`). The replay prints the same message in its terminal.

### Options

| Option | Environment variable | Default |
|--------|----------------------|---------|
| `--port <n>` | `REPLAY_PORT` | `8090` |
| `--seed <n>` | `REPLAY_SEED` | from the clock |
| `--recordings <dir>` | `REPLAY_RECORDINGS` | `recordings` |

Pass options with `./gradlew run --args="--port 8091 --seed 42"`. A seed reproduces a run exactly if the replay is restarted before each run, because Punit sends its samples one after another.

### Switching the exercises to the real model

Punit records the provider in every baseline but not the endpoint, so switching takes two changes:

| | Replay (default) | Gateway |
|---|---|---|
| `exercises/part1/guardrail/mavai-services.yaml` | no `provider:` line | `provider: litellm` under `configuration:` |
| `exercises/part1/.env` | `MAVAI_LLM_ENDPOINT=http://localhost:8090/v1/chat/completions` | `MAVAI_LLM_ENDPOINT=https://litellm.karakun.net/v1/chat/completions` and `MAVAI_LLM_API_KEY=<key>` |
| Baselines and reports say | `provider: openai-compatible` | `provider: litellm` |

Punit never compares a run on one side with a baseline from the other: it rejects the baseline with "CONFIGURATION mismatch on provider". Switch back when done.

### Refreshing the recordings

The recordings are made with the LLM recorder in the presenters' repository (`tools/llm-recorder`). After a calibration run, copy the new file into `recordings/` and restart the replay; it answers from every `*.jsonl` file there, pooling answers for the same input and settings. Remove recordings that a new one replaces. Recordings for an old system prompt can stay: they're only used for requests with that prompt.

### Tests

```shell
./gradlew test
```

The tests use small recordings in `src/test/resources/fixtures/`, so they need no network.

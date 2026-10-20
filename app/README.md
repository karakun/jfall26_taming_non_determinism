# Health Advisor

The fictional Health Advisor used in the workshop's demos. A patient asks a question, a language model answers, and a guardrail decides whether the answer reaches the patient. The guardrail is the one you test in `exercises/part1/`: the app reads its system prompt and settings from `exercises/part1/guardrail/`.

The app is a fictional workshop demo and gives no medical advice.

## What you need

- A JDK, version 21 or newer. Check with `java -version`.
- The workshop key. If you've already set up `exercises/part1/.env` for the exercises, there's nothing more to do: the app finds it there.

Otherwise, copy `exercises/part1/.env.example` to `exercises/part1/.env` and fill in the key handed out at the workshop. You can also set `MAVAI_LLM_ENDPOINT` and `MAVAI_LLM_API_KEY` as environment variables, which take precedence over the file.

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

- **Ask the advisor**: type what's troubling the patient. The page shows what the patient would see (the model's reply, or a safe fallback if the guardrail stopped it) and, behind the scenes, the model's reply, the guardrail's verdict and reason, and how long each call took.
- **Interactive guardrail tester**: a developer tool, not part of the app itself. Type a reply as the advisor might send it, and see whether the guardrail would let it through, and why. It skips the advice model, which rarely produces harmful advice on request.

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
| `MAVAI_LLM_API_KEY is not set. …` | Put the key in `exercises/part1/.env` (see "What you need"). |
| `Port 8080 is in use. …` | Another program, or another copy of the app, uses the port. Stop it, or start with `--args=--port=8081`. |
| `Can't find ../exercises/part1/guardrail/… .` | Start the app from the `app/` folder of the workshop repository. |

If the page says the advisor can't be reached or the guardrail couldn't check, the model didn't answer in time or the connection failed. Try again; the cause is shown behind the scenes.

The app reads the guardrail's files when it starts. If you change them, restart the app to pick up the change.

## Run the tests

```shell
./gradlew test
```

The tests use a stand-in for the model, so they need no key and no network.

# Setting up for the workshop

Please do this **before** the workshop, at home or at the office. It takes about 10 minutes, most of it installing Java. In the room there is little time and the wifi is shared by everyone, so a laptop that isn't ready costs you part of the workshop.

You need two things: a Java JDK, version 21 or newer, and a copy of this repository. Then you run a setup check and try the replay, which the exercises use in place of the live model. You **don't** need to install Gradle: the repository brings its own.

## 1. A JDK, version 21 or newer

Check what you have:

```shell
java -version
```

If it says version 21 or higher (e.g. `openjdk version "25.0.1"`), you're done with this step. If it says 17 or lower, or `java` isn't found, install one:

| System | Command |
|--------|---------|
| macOS (Homebrew) | `brew install --cask temurin` |
| Windows | `winget install EclipseAdoptium.Temurin.25.JDK` |
| Ubuntu, Debian | `sudo apt install openjdk-21-jdk` |
| Fedora | `sudo dnf install java-21-openjdk-devel` |
| macOS or Linux without admin rights | [SDKMAN](https://sdkman.io): `sdk install java` |

Or download an installer from [adoptium.net](https://adoptium.net). Then open a **new** terminal and run `java -version` again. If it still shows the old version, `JAVA_HOME` points to the old JDK: set it to the new one, or remove it.

## 2. The repository

```shell
git clone https://github.com/karakun/jfall26_taming_non_determinism.git
```

Without git, download the ZIP from the repository's page and unpack it. On macOS and Linux, then start Gradle with `sh gradlew` wherever these instructions say `./gradlew`.

## 3. The setup check

In the repository, go to `exercises/part1` and run the check:

| System | Command |
|--------|---------|
| macOS, Linux | `./gradlew checkSetup` |
| Windows, Command Prompt | `gradlew checkSetup` |
| Windows, PowerShell | `.\gradlew checkSetup` |

The first run downloads Gradle and the libraries the workshop uses (about 250 MB) and takes a minute or two. It ends like this:

```text
Workshop setup check
  OK       JDK 25.0.1+8-LTS (Eclipse Adoptium)
  OK       Part 1, the replay and the demo app build; their libraries are downloaded
  OK       Created exercises/part1/.env: it tells the exercises where the replay answers
  OK       The replay starts: 80 inputs, 2500 recorded answers from 3 files (seed …)
  OK       Port 8090 is free for the replay
  OK       Port 8080 is free for the demo app
  OK       The Infomaniak API (api.infomaniak.com) can be reached
  NOTE     No key yet: you get it at the workshop and put it in app/.env. Then run this check again.

READY
```

**READY means you're set.** The API key is handed out at the workshop; you don't need it now.

If a line says **PROBLEM**, it says what to do. The usual ones:

- **The JDK is too old.** Install a newer one (step 1), run `./gradlew --stop`, and run the check again.
- **The Infomaniak API can't be reached.** A company network, proxy or VPN may block it. Try another network (at home, or your phone's hotspot). If it only works outside your company network, bring a laptop that can use another network, or count on the venue's wifi.
- **A download fails.** A company proxy may block Gradle's downloads. Try another network.

If you can't get to READY, come a few minutes early and we'll help.

## 4. The replay

The exercises don't call the live model. Their tests run the model thousands of times, far too often for a shared service with a room full of people. They call the **replay** instead: a small server on your laptop that answers with the answers the real model gave when we ran the same tests before the workshop. It runs in a terminal of its own and must be running whenever you work on the exercises.

The exercises find it through `exercises/part1/.env`, which the setup check created. Try the replay once. Open a **second** terminal, go to the repository's `replay` folder, and start it:

| System | Command |
|--------|---------|
| macOS, Linux | `./gradlew run` |
| Windows, Command Prompt | `gradlew run` |
| Windows, PowerShell | `.\gradlew run` |

When it's ready, it prints one line:

```text
Replay: http://localhost:8090/v1/chat/completions — 80 inputs, 2500 recorded answers from 3 files (seed …)
```

Stop it with Ctrl+C. The setup check starts the replay only to test it and stops it again, so READY doesn't mean the replay is running: in the workshop you start it yourself.

## In the workshop

1. Start the replay in its own terminal (`./gradlew run` in `replay/`, as in step 4) and **leave it running until the end of the workshop**.
2. Configure your LLM endpoint in `app/.env` (see below).

The demo app talks to the live model; the exercises talk to the replay.

## Configuring your LLM endpoint

### Recommended: Use your own API key

**Best practice is to use your own LLM provider.** This gives you:
- Full control over rate limits and performance
- No shared resources with other workshop participants
- Ability to experiment freely without restrictions

You can use any OpenAI-compatible API. In `app/.env`, configure:

```
MAVAI_LLM_ENDPOINT=<your-endpoint>
MAVAI_LLM_API_KEY=<your-api-key>
```

Supported endpoints include:
- **OpenAI**: `https://api.openai.com/v1/chat/completions`
- **Azure OpenAI**: `https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions`
- **Local Ollama**: `http://localhost:11434/v1/chat/completions` (no API key required)
- **Any OpenAI-compatible API** (e.g., Together.ai, Anyscale, local LLM servers)

To use a different model, update `models.primary.model` in `app/providers.yaml`.

### Fallback: Workshop API key

If you don't have access to your own LLM provider, you can use the shared workshop API key handed out at the workshop.

**Important:** The workshop API key is shared by all participants. This may cause:
- **Rate limiting** when many participants make requests simultaneously
- **Slower responses** during peak usage
- **Occasional timeouts** under heavy load

To use the workshop key:
1. Copy `app/.env.example` to `app/.env`
2. Add the key after `MAVAI_LLM_API_KEY=`
3. Run `./gradlew checkSetup` in `exercises/part1` to verify the key works

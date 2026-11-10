# Setting up for the workshop

## Before the workshop

Please do this **before** the workshop, at home or at the office. It takes about 10 minutes, most of it installing Java. In the room there is little time and the wifi is shared by everyone, so a laptop that isn't ready costs you part of the workshop.

You need two things: a Java JDK, version 21 or newer, and a copy of this repository. You **don't** need to install Gradle: the repository brings its own.

> **Note:** The setup check (`./gradlew checkSetup`) and API key configuration will be done **during the workshop**. Before the workshop, just install Java and clone the repository.

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

---

## In the workshop

1. Run the setup check in `exercises/part1`: `./gradlew checkSetup`
2. Configure your LLM endpoint in `app/.env` (see below).
3. Start the replay in its own terminal (`./gradlew run` in `replay/`) and **leave it running until the end of the workshop**.

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

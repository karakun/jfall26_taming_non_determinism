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
git clone <repository URL>
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
  OK       The model gateway (litellm.karakun.net) can be reached
  NOTE     No key yet: you get it at the workshop and put it in app/.env. Then run this check again.

READY
```

**READY means you're set.** The key for the model gateway is handed out at the workshop; you don't need it now.

If a line says **PROBLEM**, it says what to do. The usual ones:

- **The JDK is too old.** Install a newer one (step 1), run `./gradlew --stop`, and run the check again.
- **The model gateway can't be reached.** A company network, proxy or VPN may block it. Try another network (at home, or your phone's hotspot). If it only works outside your company network, bring a laptop that can use another network, or count on the venue's wifi.
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

1. You get the key for the model gateway. In the `app/` folder, copy `.env.example` to `.env` and put the key after `MAVAI_LLM_API_KEY=`. Run `./gradlew checkSetup` in `exercises/part1` again: the last line then says the gateway accepts your key.
2. Start the replay in its own terminal (`./gradlew run` in `replay/`, as in step 4) and **leave it running until the end of the workshop**.

The demo app talks to the live model through Karakun's gateway; the exercises talk to the replay.

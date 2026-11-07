import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

plugins {
    java
    alias(libs.plugins.punit)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.punit.core)
    testImplementation(libs.punit.decl)
    testImplementation(libs.punit.report)
    testImplementation(libs.punit.lm)
    testImplementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)
}

// --release lets any JDK 21 or newer build the project; a toolchain would require a JDK 21 to be installed.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
    // Punit explains a refused or failed test in the exception message, which Gradle otherwise hides.
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
        showCauses = false
    }
}

// Everything participants work with sits in one folder, guardrail/: the test class, the service
// definition, the system prompt and the contracts. Gradle reads the class and its files from there.
// services/ holds the code behind the guardrail's service type; participants don't need to open it.
sourceSets {
    test {
        java.setSrcDirs(listOf("."))
        java.include("guardrail/**", "services/**")
        resources.setSrcDirs(listOf(".", "services"))
        resources.include("guardrail/**/*.yaml", "guardrail/**/*.txt", "META-INF/**")
        resources.exclude("guardrail/punit-output/**")
    }
}

// What Punit writes for participants (baselines, explorations, the report) lands in guardrail/punit-output/,
// next to the files it belongs to.
punit {
    baselineDir.set("guardrail/punit-output/baselines")
    explorationsDir.set("guardrail/punit-output/explorations")
}

tasks.named<org.mavai.punit.gradle.PUnitReportTask>("punitReport") {
    outputDir.set(layout.projectDirectory.dir("guardrail/punit-output/report"))
}

// ./gradlew clean starts the exercises again from scratch: it also deletes Punit's output (baselines,
// explorations, the report), so the harmless-advice test has no baseline again.
tasks.named<Delete>("clean") {
    delete("guardrail/punit-output")
}

// .env says where the model answers and is never committed (see .env.example). Its settings go into every
// test JVM, including the experiments'; variables already set in the environment win.
val dotEnv: Map<String, String> = file(".env").takeIf { it.isFile }?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

tasks.withType<Test>().configureEach {
    dotEnv.forEach { (key, value) ->
        if (System.getenv(key) == null) environment(key, value)
    }
}

// mavaiCheck runs inside the Gradle daemon, so it gets the settings as system properties instead.
// They are set or cleared on every run, because the daemon outlives the build.
val modelProperties = mapOf(
    "MAVAI_LLM_ENDPOINT" to "mavai.llm.endpoint",
    "MAVAI_LLM_API_KEY" to "mavai.llm.api-key",
)

tasks.withType<org.mavai.punit.gradle.PUnitCheckTask>().configureEach {
    doFirst {
        modelProperties.forEach { (variable, property) ->
            val value = dotEnv[variable]
            if (System.getenv(variable) == null && value != null) System.setProperty(property, value)
            else System.clearProperty(property)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// ./gradlew checkSetup: run once before the workshop. It downloads everything Part 1, the
// replay and the demo app need (so the exercises need no internet in the room), starts the replay once and
// checks that the workshop's model gateway can be reached. It ends with READY, or says what to fix.
// ---------------------------------------------------------------------------------------------------------

val workshopRoot: File = rootDir.resolve("../..").canonicalFile

// The replay and the demo app are separate Gradle projects; building them here downloads their libraries.
val prepareReplay = tasks.register<GradleBuild>("prepareReplay") {
    dir = workshopRoot.resolve("replay")
    tasks = listOf("installDist")
}

val prepareApp = tasks.register<GradleBuild>("prepareApp") {
    dir = workshopRoot.resolve("app")
    tasks = listOf("installDist")
}

// Compiling only downloads what compiling needs; running the tests, experiments and the report needs more.
val downloadLibraries = tasks.register("downloadLibraries") {
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

tasks.register("checkSetup") {
    group = "verification"
    description = "Checks that this laptop is ready for the workshop."
    dependsOn("testClasses", downloadLibraries, prepareReplay, prepareApp)
    doLast {
        val problems = mutableListOf<String>()
        fun ok(message: String) = println("  OK       $message")
        fun note(message: String) = println("  NOTE     $message")
        fun problem(message: String) { println("  PROBLEM  $message"); problems += message }

        println()
        println("Workshop setup check")
        ok("JDK ${Runtime.version()} (${System.getProperty("java.vendor")})")
        ok("Part 1, the replay and the demo app build; their libraries are downloaded")

        // The replay: start it once on a free port, wait for its ready line, stop it.
        val replayDir = workshopRoot.resolve("replay")
        val javaBin = File(System.getProperty("java.home"), "bin/java").path
        val classpath = replayDir.resolve("build/install/replay/lib").listFiles().orEmpty()
            .joinToString(File.pathSeparator) { it.path }
        val port = ServerSocket(0).use { it.localPort }
        val replay = ProcessBuilder(javaBin, "-cp", classpath, "replay.ReplayServer", "--port", "$port")
            .directory(replayDir).redirectErrorStream(true).start()
        try {
            val ready = CompletableFuture.supplyAsync {
                replay.inputStream.bufferedReader().lineSequence().firstOrNull { it.startsWith("Replay:") }
            }.completeOnTimeout(null, 60, TimeUnit.SECONDS).get()
            if (ready != null) ok("The replay starts: ${ready.substringAfter(" — ")}")
            else problem("The replay didn't start within a minute. Try 'cd replay' and './gradlew run' to see why.")
        } finally {
            replay.destroy()
        }

        fun portFree(p: Int) = runCatching { ServerSocket(p).close() }.isSuccess
        if (portFree(8090)) ok("Port 8090 is free for the replay")
        else note("Port 8090 is in use. If that's the replay, fine; otherwise see replay/README.md, 'If it doesn't start'.")
        if (portFree(8080)) ok("Port 8080 is free for the demo app")
        else note("Port 8080 is in use. If that's the demo app, fine; otherwise start it with --args=--port=8081.")

        // The gateway: the demo app (and Part 2) use it. Without a key it answers 401, which proves it's reachable.
        val appEnv = workshopRoot.resolve("app/.env").takeIf { it.isFile } ?: workshopRoot.resolve("app/.env.example")
        val settings = appEnv.readLines().map { it.trim() }
            .filter { it.contains("=") && !it.startsWith("#") }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        val endpoint = System.getenv("MAVAI_LLM_ENDPOINT") ?: settings["MAVAI_LLM_ENDPOINT"]
        val key = (System.getenv("MAVAI_LLM_API_KEY") ?: settings["MAVAI_LLM_API_KEY"]).orEmpty()
        if (endpoint.isNullOrBlank()) {
            problem("No MAVAI_LLM_ENDPOINT in app/.env.example. Get a fresh copy of the workshop repository.")
        } else {
            val models = URI.create(endpoint.substringBefore("/chat/completions") + "/models")
            val request = HttpRequest.newBuilder(models).timeout(Duration.ofSeconds(15)).GET()
            if (key.isNotEmpty()) request.header("Authorization", "Bearer $key")
            val host = models.host
            try {
                val status = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
                when {
                    key.isEmpty() && status in listOf(401, 403) -> {
                        ok("The model gateway ($host) can be reached")
                        note("No key yet: you get it at the workshop and put it in app/.env. Then run this check again.")
                    }
                    key.isNotEmpty() && status == 200 -> ok("The model gateway ($host) accepts your key")
                    key.isNotEmpty() && status in listOf(401, 403) ->
                        problem("The model gateway ($host) refuses the key in app/.env. Check it against the one handed out.")
                    else -> problem("The model gateway ($host) answered HTTP $status. Tell a presenter.")
                }
            } catch (e: ConnectException) {
                problem("Can't connect to the model gateway ($host). A firewall, proxy or VPN may block it; try another network.")
            } catch (e: HttpConnectTimeoutException) {
                problem("Connecting to the model gateway ($host) timed out. A firewall, proxy or VPN may block it; try another network.")
            } catch (e: SSLException) {
                problem("The secure connection to $host failed (${e.message}). A company network that inspects HTTPS can cause this; try another network.")
            } catch (e: IOException) {
                problem("Can't reach the model gateway ($host): ${e.javaClass.simpleName} ${e.message.orEmpty()}. Check your internet connection.")
            }
        }

        println()
        if (problems.isEmpty()) {
            println("READY")
        } else {
            throw GradleException("NOT READY: ${problems.size} problem(s), listed above.")
        }
    }
}

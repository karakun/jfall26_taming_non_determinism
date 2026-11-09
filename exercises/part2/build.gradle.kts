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
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.dataformat.yaml)
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

// Everything participants work with sits in one folder, fallback/:
// - GuardrailDegradationTest.java: PUnit tests measuring safety degradation
// - FailureInjectionService.java: HTTP service that injects failures and falls back to naive guardrail
// - arta/: Bernoulli process for stochastic failure generation
// - harmful-advice-contract.yaml: PUnit contract with harmful advice inputs
sourceSets {
    test {
        java.setSrcDirs(listOf("."))
        java.include("fallback/**", "fallback/arta/**")
        resources.setSrcDirs(listOf("."))
        resources.include("fallback/**/*.yaml", "fallback/**/*.txt")
        resources.exclude("fallback/punit-output/**")
    }
}

// What Punit writes for participants (baselines, the report) lands in fallback/punit-output/,
// next to the files it belongs to.
punit {
    baselineDir.set("fallback/punit-output/baselines")
}

tasks.named<org.mavai.punit.gradle.PUnitReportTask>("punitReport") {
    outputDir.set(layout.projectDirectory.dir("fallback/punit-output/report"))
}

// .env says where the model answers and is never committed (see .env.example). Its settings go into every
// test JVM, including the measurement's; variables already set in the environment win.
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

// Task to run the FailureInjectionService with all dependencies
// Usage: ./gradlew runStochasticService --args="8090 0.75 bernoulli"
tasks.register<JavaExec>("runStochasticService") {
    group = "application"
    description = "Run the FailureInjectionService for degradation testing"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("fallback.FailureInjectionService")

    // Pass .env variables to the service
    dotEnv.forEach { (key, value) ->
        if (System.getenv(key) == null) environment(key, value)
    }

    // Enable console input for Ctrl+C
    standardInput = System.`in`
}

// Task to run the Failure Simulation visualization server
// Usage: ./gradlew runSimulation
tasks.register<JavaExec>("runSimulation") {
    group = "application"
    description = "Run the Failure Injection Simulator visualization on port 8081"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("fallback.FailureSimulationServer")
    args = listOf("8081")

    // Enable console input for Ctrl+C
    standardInput = System.`in`
}

// Task to run the PipelineProxyService for full pipeline testing
// Usage: ./gradlew runPipelineProxy
tasks.register<JavaExec>("runPipelineProxy") {
    group = "application"
    description = "Run the Pipeline Proxy for full pipeline PUnit testing"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("fallback.PipelineProxyService")
    args = listOf("8092", "http://localhost:8080")
    standardInput = System.`in`
}

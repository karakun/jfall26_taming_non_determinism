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

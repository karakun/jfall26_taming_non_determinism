plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.jsr310)  // For Java 8 time support in WAL

    // Tiberius Security Testing Framework from Maven Central
    implementation("io.github.tiberius-security:tiberius:1.0.0") {
        exclude(group = "ch.qos.logback")
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)

    // SLF4J for Tiberius logging
    testImplementation("org.slf4j:slf4j-simple:2.0.9")
}

// --release lets any JDK 21 or newer build the project
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

application {
    mainClass = "advisor.HealthAdvisorApp"
}

// The app finds the exercises' guardrail and .env relative to this folder.
tasks.named<JavaExec>("run") {
    workingDir = projectDir
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    workingDir = projectDir

    // Show test output for Tiberius scans
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

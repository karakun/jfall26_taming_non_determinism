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
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj)
}

// --release lets any JDK 21 or newer build the project; a toolchain would require a JDK 21 to be installed.
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
}

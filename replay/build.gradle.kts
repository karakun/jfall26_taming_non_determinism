plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jackson.databind)
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
    mainClass = "replay.ReplayServer"
}

// The replay finds recordings/ relative to this folder.
tasks.named<JavaExec>("run") {
    workingDir = projectDir
}

tasks.test {
    useJUnitPlatform()
}

// The workshop's setup check lives in exercises/part1 (see SETUP.md); running it from here does the same.
tasks.register<GradleBuild>("checkSetup") {
    group = "verification"
    description = "Checks that this laptop is ready for the workshop (runs exercises/part1's checkSetup)."
    dir = file("../exercises/part1")
    tasks = listOf("checkSetup")
}

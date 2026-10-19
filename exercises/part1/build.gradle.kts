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
}

// --release lets any JDK 21 or newer build the project; a toolchain would require a JDK 21 to be installed.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
}

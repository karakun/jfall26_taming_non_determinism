// The workshop needs a JDK 21 or newer. Checked here, before anything else, so an older JDK gets this message
// instead of a failure deep in the build.
if (Runtime.version().feature() < 21) {
    throw GradleException(
        "The workshop needs a JDK 21 or newer, but Gradle runs on JDK ${Runtime.version().feature()} " +
            "(${System.getProperty("java.home")}). Install JDK 21 or 25 (see SETUP.md), make it the one that " +
            "JAVA_HOME and 'java -version' point to, then run './gradlew --stop' and try again."
    )
}

rootProject.name = "advice-guardrail"

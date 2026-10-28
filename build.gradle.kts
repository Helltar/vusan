plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.lombok)
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    application
}

group = "com.helltar"
version = "0.18.0"

repositories {
    mavenCentral()
}

dependencies {
    detektPlugins(libs.detekt.ktlint.wrapper)

    implementation(kotlin("reflect"))

    implementation(libs.telegrambots.client)
    implementation(libs.telegrambots.longpolling)
    implementation(libs.heartbeat)
    compileOnly(libs.lombok)

    runtimeOnly(libs.sqlite.jdbc)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.regolith.sdk)

    implementation(libs.dotenv.kotlin)
    implementation(libs.jsoup)
    implementation(libs.cron.utils)

    implementation(libs.kotlin.logging.jvm)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("com.helltar.vusan.MainKt")
}

// the running bot reports its own version from here, so a deployed container can be identified from
// its first log line. a classpath run has no manifest and says `dev` instead.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()

    // only what needs attention: a passing run prints its one summary line, a failure its whole trace
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    addTestListener(
        object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}

            override fun beforeTest(testDescriptor: TestDescriptor) {}

            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null) {
                    println(
                        "tests: ${result.resultType} ${result.testCount} run, ${result.successfulTestCount} passed, " +
                            "${result.failedTestCount} failed, ${result.skippedTestCount} skipped",
                    )
                }
            }
        },
    )
}

// what a model is sent before any conversation, as JSON built by the production code: the system prompt and
// every tool definition. `./gradlew -q exportPrompt --args="--out /tmp/prompt.json"`; the flags are in
// src/test/kotlin/com/helltar/vusan/telegram/PromptExport.kt
tasks.register<JavaExec>("exportPrompt") {
    description = "Prints the system prompt and the tool definitions as JSON."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.helltar.vusan.telegram.PromptExportKt")
    environment("LOG_LEVEL", "WARN")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$projectDir/detekt.yml")
}

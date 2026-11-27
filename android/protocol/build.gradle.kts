import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm")
}

group = "dev.renandrop"
version = "0.1.0"

dependencies {
    // Flow de eventos; o resto do módulo usa só o JDK.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(false)
    }
}

// O binário do núcleo Rust é a referência do teste de interoperabilidade.
val repoRoot = rootProject.projectDir.parentFile
val rustCli = repoRoot.resolve("target/release/renandrop-cli")

val ensureRustCli by tasks.registering(Exec::class) {
    description = "Compila o renandrop-cli (núcleo Rust) se o binário não existir."
    workingDir = repoRoot
    commandLine("cargo", "build", "--release", "-p", "renandrop-core", "--bin", "renandrop-cli")
    onlyIf { !rustCli.exists() }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(ensureRustCli)
    maxHeapSize = "1g"
    systemProperty("renandrop.cli", rustCli.absolutePath)
    systemProperty("renandrop.tmp", layout.buildDirectory.dir("interop").get().asFile.absolutePath)
    systemProperty("renandrop.reports", layout.buildDirectory.dir("reports").get().asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

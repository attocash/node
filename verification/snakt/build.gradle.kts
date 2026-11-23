plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.kotlin.formver") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenLocal {
        content {
            includeGroup("org.jetbrains.kotlin.formver")
        }
    }
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets.named("main") {
        kotlin.srcDir("../../src/main/kotlin")
        kotlin.include(
            "cash/atto/node/bootstrap/discovery/DiscoveryChannelCapacity.kt",
            "cash/atto/node/bootstrap/discovery/DiscoveryChannelCapacityProof.kt",
        )
    }
}

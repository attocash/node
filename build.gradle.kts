import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "2.1.10"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion

    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.5"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "cash.atto"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
    all {
        exclude(group = "commons-logging", module = "commons-logging")
    }
}

ext["kotlin-coroutines.version"] = "1.9.0"

dependencies {
    val commonsVersion = "3.0.3"
    val cucumberVersion = "7.21.1"
    val springdocVersion = "2.8.5"
    val ktorVersion = "3.1.1"

    implementation("cash.atto:commons-core:$commonsVersion")
    implementation("cash.atto:commons-worker:$commonsVersion")
    implementation("cash.atto:commons-signer-remote:$commonsVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")

    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:$springdocVersion")

    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("io.projectreactor.netty:reactor-netty")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug")

    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.flywaydb:flyway-core")

    implementation("io.asyncer:r2dbc-mysql:1.4.0")
    implementation("com.mysql:mysql-connector-j")
    implementation("org.flywaydb:flyway-mysql")

    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.4")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    testImplementation("org.junit.platform:junit-platform-suite") // for cucumber
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-spring:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
    testImplementation("org.awaitility:awaitility:4.2.2")

    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:r2dbc")
    testImplementation("org.testcontainers:testcontainers")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict", "-Xjvm-default=all")
        jvmTarget = "21"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

graalvmNative {
    binaries {
        named("main") {
            buildArgs.add("--static")
            buildArgs.add("--libc=musl")
            buildArgs.add("--gc=G1")
            buildArgs.add("--strict-image-heap")
        }
    }
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.7.22"

    id("org.springframework.boot") version "3.0.1"
    id("io.spring.dependency-management") version "1.1.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
}

group = "com.atto"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
}

dependencies {
    val cucumberVersion = "7.11.0"
    val springdocVersion = "2.0.2"

    implementation(project(":atto-commons"))
    implementation(project(":atto-protocol"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:${springdocVersion}")

    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("io.projectreactor.netty:reactor-netty")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-debug")

    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.flywaydb:flyway-core")
    implementation("com.github.jasync-sql:jasync-mysql:2.1.16")
    implementation("mysql:mysql-connector-java")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("io.r2dbc:r2dbc-h2")

    implementation("io.github.microutils:kotlin-logging-jvm:3.0.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("io.mockk:mockk:1.13.3")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    testImplementation("org.junit.platform:junit-platform-suite") // for cucumber
    testImplementation("io.cucumber:cucumber-java:${cucumberVersion}")
    testImplementation("io.cucumber:cucumber-spring:${cucumberVersion}")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:${cucumberVersion}")
    testImplementation("org.awaitility:awaitility:4.2.0")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict", "-Xjvm-default=all")
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    environment("GRADLE", "true")
    useJUnitPlatform()
}

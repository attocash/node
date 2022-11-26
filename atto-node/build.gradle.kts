import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "2.7.5"
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
    kotlin("jvm") version "1.7.21"
    kotlin("plugin.spring") version "1.7.21"
}

group = "com.atto"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
}

extra["cucumberVersion"] = "7.8.1"

dependencies {
    implementation(project(":atto-commons"))
    implementation(project(":atto-protocol"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("org.springdoc:springdoc-openapi-ui:1.6.13")

    implementation("com.github.ben-manes.caffeine:caffeine:3.1.2")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("io.projectreactor.netty:reactor-netty")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    runtimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-debug")

    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.mariadb:r2dbc-mariadb")
    implementation("mysql:mysql-connector-java")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("io.r2dbc:r2dbc-h2")

    implementation("io.github.microutils:kotlin-logging-jvm:3.0.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("io.mockk:mockk:1.13.2")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    testImplementation("org.junit.platform:junit-platform-suite") // for cucumber
    testImplementation("io.cucumber:cucumber-java:${property("cucumberVersion")}")
    testImplementation("io.cucumber:cucumber-spring:${property("cucumberVersion")}")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:${property("cucumberVersion")}")
    testImplementation("org.awaitility:awaitility:4.2.0")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

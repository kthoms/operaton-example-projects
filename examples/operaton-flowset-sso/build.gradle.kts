plugins {
    java
    application
}

group = "org.operaton.examples"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

val operatonVersion = "2.1.1"
val testcontainersVersion = "1.21.3"

dependencies {
    implementation("org.operaton.bpm:operaton-external-task-client:$operatonVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    // JAXB was removed from JDK 9+; the Operaton external-task-client's XML
    // data-format provider requires it at runtime on Java 11+.
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.5")
    runtimeOnly("com.sun.xml.bind:jaxb-impl:4.0.9")

    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("io.rest-assured:rest-assured:5.5.7")
    // SLF4J binding so Testcontainers logs Docker detection info during IT
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
    // Gradle 9+ requires junit-platform-launcher on the test runtime classpath
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.operaton.examples.flowsetsso.LoanWorker"
}

tasks.test { useJUnitPlatform() }

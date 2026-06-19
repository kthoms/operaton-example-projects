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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("io.rest-assured:rest-assured:5.5.0")
}

application {
    mainClass = "org.operaton.examples.loanplatformsso.LoanWorker"
}

tasks.test { useJUnitPlatform() }

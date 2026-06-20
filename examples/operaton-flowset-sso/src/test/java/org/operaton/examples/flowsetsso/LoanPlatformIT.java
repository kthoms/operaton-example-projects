package org.operaton.examples.flowsetsso;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanPlatformIT {

    static final Network NET = Network.newNetwork();

    @Container
    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer<?> pgEngine = new PostgreSQLContainer<>("postgres:16-alpine")
        .withNetwork(NET).withNetworkAliases("postgres-operaton")
        .withDatabaseName("operaton").withUsername("operaton").withPassword("operaton");

    @Container
    @SuppressWarnings({"rawtypes", "resource"})
    static PostgreSQLContainer<?> pgKc = new PostgreSQLContainer<>("postgres:16-alpine")
        .withNetwork(NET).withNetworkAliases("postgres-keycloak")
        .withDatabaseName("keycloak").withUsername("keycloak").withPassword("keycloak");

    @Container
    @SuppressWarnings({"rawtypes", "resource"})
    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.6.3")
        .withNetwork(NET).withNetworkAliases("keycloak")
        .withCommand("start-dev", "--http-port=8080")
        .withEnv("KC_DB", "postgres")
        .withEnv("KC_DB_URL", "jdbc:postgresql://postgres-keycloak:5432/keycloak")
        .withEnv("KC_DB_USERNAME", "keycloak").withEnv("KC_DB_PASSWORD", "keycloak")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
        .withEnv("KC_HOSTNAME_STRICT", "false").withEnv("KC_HTTP_ENABLED", "true")
        .withExposedPorts(8080)
        .dependsOn(pgKc)
        .waitingFor(Wait.forHttp("/realms/master").forPort(8080)
            .withStartupTimeout(Duration.ofMinutes(3)));

    @SuppressWarnings({"rawtypes", "resource"})
    static GenericContainer<?> engine;

    @BeforeAll
    static void startEngineAndSeed() throws Exception {
        // Seed the realm by running kcadm in the running Keycloak container
        keycloak.copyFileToContainer(
            MountableFile.forHostPath("keycloak/seed-realm.sh"), "/seed/seed-realm.sh");
        var seed = keycloak.execInContainer("bash", "/seed/seed-realm.sh");
        if (seed.getExitCode() != 0) {
            throw new IllegalStateException("Realm seeding failed: " + seed.getStderr());
        }

        engine = new GenericContainer<>(
            new ImageFromDockerfile().withDockerfile(
                Paths.get("engine/Dockerfile").toAbsolutePath()))
            .withNetwork(NET).withNetworkAliases("operaton")
            .withCopyFileToContainer(
                MountableFile.forHostPath("engine/resources"),
                "/operaton/configuration/resources")
            .withExposedPorts(8080)
            .dependsOn(pgEngine, keycloak)
            .waitingFor(Wait.forHttp("/engine-rest/engine").forPort(8080)
                .withStartupTimeout(Duration.ofMinutes(3)));
        engine.start();

        RestAssured.baseURI = "http://" + engine.getHost();
        RestAssured.port = engine.getMappedPort(8080);
        RestAssured.basePath = "/engine-rest";

        // Start the worker in-process against the mapped engine port (Basic auth)
        String engineBaseUrl = RestAssured.baseURI + ":" + RestAssured.port + "/engine-rest";
        LoanWorker.start(engineBaseUrl, "admin:admin");
    }

    @Test @Order(1)
    void realmAndDeploymentAreReady() {
        given().auth().preemptive().basic("admin", "admin")
            .queryParam("key", "loan-application")
        .when().get("/process-definition")
        .then().statusCode(200).body("size()", greaterThanOrEqualTo(1));
    }

    @Test @Order(2)
    void lowRiskIsApprovedByWorker() {
        String id = given().auth().preemptive().basic("admin", "admin")
            .contentType(ContentType.JSON)
            .body("{\"variables\":{\"loanAmount\":{\"value\":100000,\"type\":\"Integer\"}}}")
        .when().post("/process-definition/key/loan-application/start")
        .then().statusCode(200).extract().path("id");

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            given().auth().preemptive().basic("admin", "admin")
                .queryParam("processInstanceId", id)
                .queryParam("activityType", "noneEndEvent")
            .when().get("/history/activity-instance")
            .then().statusCode(200)
                .body("activityId", hasItem("EndEvent_Approved")));
    }

    @Test @Order(3)
    void highRiskIsRejectedByWorker() {
        String id = given().auth().preemptive().basic("admin", "admin")
            .contentType(ContentType.JSON)
            .body("{\"variables\":{\"creditScore\":{\"value\":500,\"type\":\"Integer\"},"
                + "\"loanAmount\":{\"value\":100000,\"type\":\"Integer\"}}}")
        .when().post("/process-definition/key/loan-application/start")
        .then().statusCode(200).extract().path("id");

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            given().auth().preemptive().basic("admin", "admin")
                .queryParam("processInstanceId", id)
                .queryParam("activityType", "noneEndEvent")
            .when().get("/history/activity-instance")
            .then().statusCode(200)
                .body("activityId", hasItem("EndEvent_Rejected")));
    }

    @Test @Order(4)
    void mediumRiskWaitsForUnderwriter() {
        String id = given().auth().preemptive().basic("admin", "admin")
            .contentType(ContentType.JSON)
            .body("{\"variables\":{\"creditScore\":{\"value\":650,\"type\":\"Integer\"},"
                + "\"loanAmount\":{\"value\":100000,\"type\":\"Integer\"}}}")
        .when().post("/process-definition/key/loan-application/start")
        .then().statusCode(200).extract().path("id");

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            given().auth().preemptive().basic("admin", "admin")
                .queryParam("processInstanceId", id)
            .when().get("/task")
            .then().statusCode(200).body("size()", equalTo(1))
                .body("[0].name", equalTo("Underwriter Review")));
    }
}

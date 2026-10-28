package com.hisilicon.knowledge.platform.system.facade;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code @QuarkusTest} for {@link PublicKeyResource}.
 * <p>Starts a full Quarkus container and calls {@code GET /publicKey}.
 * Verifies the raw PEM content is returned as {@code text/plain}.</p>
 */
@QuarkusTest
class PublicKeyResourceTest {

    @Test
    void getPublicKey_shouldReturnPemContent() {
        given()
                .when().get("/publicKey")
                .then()
                .statusCode(200)
                .contentType("text/plain")
                .body(startsWith("-----BEGIN PUBLIC KEY-----"))
                .body(containsString("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA"));
    }
}

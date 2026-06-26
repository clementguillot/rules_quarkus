package smoke;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the locally-built smoke extension end to end: the build-time config prefix ("CI", set in
 * application.properties) flows through the recorder into the runtime bean.
 */
@QuarkusTest
class SmokeExtResourceTest {

  @Test
  void testExtensionEndpoint() {
    given()
        .queryParam("name", "Bazel")
        .when()
        .get("/smoke-ext")
        .then()
        .statusCode(200)
        .body(is("CI, Bazel!"));
  }
}

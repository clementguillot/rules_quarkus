package smoke;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Pins the checked-in application.properties baseline without a Bazel build-property override. */
@QuarkusTest
class ApplicationPropertiesSmokeExtTest {

  @Test
  void usesApplicationPropertiesDuringTestAugmentation() {
    given()
        .queryParam("name", "Bazel")
        .when()
        .get("/smoke-ext")
        .then()
        .statusCode(200)
        .body(is("CI, Bazel!"));
  }
}

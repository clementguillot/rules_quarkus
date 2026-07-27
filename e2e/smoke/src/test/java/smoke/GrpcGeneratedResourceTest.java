package smoke;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GrpcGeneratedResourceTest {

  @Test
  void generatedGrpcMessagesWorkAtRuntime() {
    given()
        .queryParam("name", "Bazel")
        .when()
        .get("/grpc-generated")
        .then()
        .statusCode(200)
        .body(is("Generated gRPC, Bazel!"));
  }
}

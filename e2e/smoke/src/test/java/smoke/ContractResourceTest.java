package smoke;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ContractResourceTest {

  @Test
  void avroAndGrpcGeneratedTypesWorkAcrossPackages() {
    given().when().get("/contracts").then().statusCode(200).body(is("c-1/Widget/created"));
  }
}

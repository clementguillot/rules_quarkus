package smoke;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CustomMainTest {
  @Test
  void applicationWithCustomMainStartsWithoutDuplicateIndexing() {
    given().when().get("/custom-main").then().statusCode(200).body(is("Hello from custom main"));
  }
}

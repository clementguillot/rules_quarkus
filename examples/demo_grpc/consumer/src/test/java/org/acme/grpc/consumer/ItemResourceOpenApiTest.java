package org.acme.grpc.consumer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ItemResourceOpenApiTest {

  @Test
  void testOpenApiDocumentListsItemsPath() {
    given().when().get("/q/openapi").then().statusCode(200).body(containsString("/items"));
  }
}

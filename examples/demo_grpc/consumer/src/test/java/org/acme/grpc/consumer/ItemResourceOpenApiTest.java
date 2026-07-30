package org.acme.grpc.consumer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ItemResourceOpenApiTest {

  @Test
  void testOpenApiDocumentListsItemsPath() {
    given().when().get("/q/openapi").then().statusCode(200).body(containsString("/items"));
  }

  @Test
  void downstreamConsumerUsesGrpcAvroAndHandwrittenContractCode() {
    given()
        .when()
        .get("/contracts/example")
        .then()
        .statusCode(200)
        .body("itemId", equalTo("item-1"))
        .body("itemName", equalTo("Widget"))
        .body("action", equalTo("created"));
  }
}

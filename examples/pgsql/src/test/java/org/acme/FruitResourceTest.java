package org.acme;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class FruitResourceTest {

  @Test
  void testListFruits() {
    given()
        .when()
        .get("/fruits")
        .then()
        .statusCode(200)
        .body("$", hasSize(greaterThanOrEqualTo(3)));
  }

  @Test
  void testCreateFruit() {
    given()
        .contentType("application/json")
        .body("{\"name\": \"Pear\"}")
        .when()
        .post("/fruits")
        .then()
        .statusCode(201)
        .body("name", is("Pear"));
  }
}

package com.example.app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GreetingResourceTest {

  @Test
  void testDefaultGreeting() {
    given().when().get("/hello").then().statusCode(200).body(is("Hola, World!"));
  }

  @Test
  void testNamedGreeting() {
    given()
        .queryParam("name", "Quarkus")
        .when()
        .get("/hello")
        .then()
        .statusCode(200)
        .body(is("Hola, Quarkus!"));
  }
}

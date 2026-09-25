package com.example.todo.rest;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** A small, independent test to edit while trying continuous testing. */
@QuarkusTest
class ReloadProbeTest {

  @Test
  void todoEndpointIsAvailable() {
    given().when().get("/todos").then().statusCode(200);
  }
}

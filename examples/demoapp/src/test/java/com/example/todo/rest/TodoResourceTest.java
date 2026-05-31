package com.example.todo.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TodoResourceTest {

  @Test
  @Order(1)
  void testListEmpty() {
    given().when().get("/todos").then().statusCode(200).body("$", hasSize(0));
  }

  @Test
  @Order(2)
  void testCreateTodo() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"title\": \"Buy milk\"}")
        .when()
        .post("/todos")
        .then()
        .statusCode(201)
        .body("title", is("Buy milk"))
        .body("completed", is(false));
  }

  @Test
  @Order(3)
  void testListAfterCreate() {
    given().when().get("/todos").then().statusCode(200).body("$", hasSize(1));
  }

  @Test
  @Order(4)
  void testCompleteTodo() {
    given().when().put("/todos/1/complete").then().statusCode(200).body("completed", is(true));
  }

  @Test
  @Order(5)
  void testDeleteTodo() {
    given().when().delete("/todos/1").then().statusCode(204);
  }

  @Test
  @Order(6)
  void testGetNotFound() {
    given().when().get("/todos/999").then().statusCode(404);
  }
}

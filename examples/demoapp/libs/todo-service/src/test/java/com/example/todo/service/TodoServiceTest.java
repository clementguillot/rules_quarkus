package com.example.todo.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TodoServiceTest {

  @Inject TodoService todoService;

  @Test
  void serviceIsAvailableThroughDependencyInjection() {
    assertNotNull(todoService);
  }
}

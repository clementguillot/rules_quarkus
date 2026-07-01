package com.example.greeting.runtime;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingService {

  private static volatile String prefix = "Hello";

  static void setPrefix(String prefix) {
    GreetingService.prefix = prefix;
  }

  public String greet(String name) {
    return prefix + ", " + name + "!";
  }
}

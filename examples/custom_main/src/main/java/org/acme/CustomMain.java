package org.acme;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class CustomMain {
  public static void main(String... args) {
    System.out.println("hello from custom main!");
    Quarkus.run(args);
  }
}

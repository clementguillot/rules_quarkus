package com.example.app;

import com.example.greeting.runtime.GreetingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/hello")
public class GreetingResource {

  @Inject GreetingService greetingService;

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String hello(@QueryParam("name") String name) {
    if (name == null || name.isBlank()) {
      name = "World";
    }
    return greetingService.greet(name);
  }
}

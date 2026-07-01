package smoke;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import smoke.ext.runtime.SmokeService;

/** Endpoint backed by the locally-built smoke extension's CDI bean. */
@Path("/smoke-ext")
public class SmokeExtResource {

  @Inject SmokeService smokeService;

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String greet(@QueryParam("name") String name) {
    return smokeService.greet(name == null || name.isBlank() ? "World" : name);
  }
}

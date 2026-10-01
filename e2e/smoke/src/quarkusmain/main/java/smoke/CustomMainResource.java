package smoke;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/custom-main")
public class CustomMainResource {
  @GET
  public String hello() {
    return "Hello from custom main";
  }
}

package smoke.newpkg;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/added-source")
public class AddedResource {
  @GET
  public String value() {
    return "added";
  }
}

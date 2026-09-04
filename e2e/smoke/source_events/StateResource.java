package smoke;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/source-events")
public class StateResource {
  @GET
  public String value() {
    return State.value();
  }
}

package smoke;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import smoke.grpc.generated.HelloReply;
import smoke.grpc.generated.HelloRequest;

@Path("/grpc-generated")
public final class GrpcGeneratedResource {

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String greet(@DefaultValue("Quarkus") @QueryParam("name") String name) {
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    return HelloReply.newBuilder()
        .setMessage("Generated gRPC, " + request.getName() + "!")
        .build()
        .getMessage();
  }
}

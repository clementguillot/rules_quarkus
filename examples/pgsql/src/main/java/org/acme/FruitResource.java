package org.acme;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/fruits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FruitResource {

  @GET
  public List<Fruit> list() {
    return Fruit.listAll();
  }

  @POST
  @Transactional
  public Response create(Fruit fruit) {
    fruit.persist();
    return Response.status(Response.Status.CREATED).entity(fruit).build();
  }

  @DELETE
  @Path("/{id}")
  @Transactional
  public void delete(@PathParam("id") Long id) {
    Fruit fruit = Fruit.findById(id);
    if (fruit == null) {
      throw new WebApplicationException(
          "Fruit with id " + id + " not found", Response.Status.NOT_FOUND);
    }
    fruit.delete();
  }
}

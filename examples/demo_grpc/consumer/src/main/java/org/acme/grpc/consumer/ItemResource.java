package org.acme.grpc.consumer;

import io.grpc.Status;
import io.quarkus.grpc.GrpcClient;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.acme.grpc.CreateItemRequest;
import org.acme.grpc.Empty;
import org.acme.grpc.Item;
import org.acme.grpc.ItemId;
import org.acme.grpc.ItemService;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * REST/OpenAPI façade that proxies every call onto the producer's {@code ItemService} gRPC
 * endpoint.
 */
@Path("/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ItemResource {

  @GrpcClient("items")
  ItemService itemService;

  @GET
  @Operation(summary = "List every item known to the producer service")
  public Uni<List<ItemDto>> list() {
    return itemService
        .listItems(Empty.getDefaultInstance())
        .map(itemList -> itemList.getItemsList().stream().map(ItemDto::from).toList());
  }

  @GET
  @Path("/{id}")
  @Operation(summary = "Fetch a single item by id")
  public Uni<Response> get(@PathParam("id") String id) {
    return itemService
        .getItem(ItemId.newBuilder().setId(id).build())
        .map(item -> Response.ok(ItemDto.from(item)).build())
        .onFailure(ItemResource::isNotFound)
        .recoverWithItem(Response.status(Response.Status.NOT_FOUND).build());
  }

  @POST
  @Operation(summary = "Create a new item")
  public Uni<Response> create(CreateItemRequestDto request) {
    CreateItemRequest grpcRequest =
        CreateItemRequest.newBuilder()
            .setName(request.name())
            .setQuantity(request.quantity())
            .build();
    return itemService
        .createItem(grpcRequest)
        .map(item -> Response.status(Response.Status.CREATED).entity(ItemDto.from(item)).build());
  }

  @PUT
  @Path("/{id}")
  @Operation(summary = "Update an existing item")
  public Uni<Response> update(@PathParam("id") String id, CreateItemRequestDto request) {
    Item toUpdate =
        Item.newBuilder().setId(id).setName(request.name()).setQuantity(request.quantity()).build();
    return itemService
        .updateItem(toUpdate)
        .map(item -> Response.ok(ItemDto.from(item)).build())
        .onFailure(ItemResource::isNotFound)
        .recoverWithItem(Response.status(Response.Status.NOT_FOUND).build());
  }

  @DELETE
  @Path("/{id}")
  @Operation(summary = "Delete an item")
  public Uni<Response> delete(@PathParam("id") String id) {
    return itemService
        .deleteItem(ItemId.newBuilder().setId(id).build())
        .map(empty -> Response.noContent().build())
        .onFailure(ItemResource::isNotFound)
        .recoverWithItem(Response.status(Response.Status.NOT_FOUND).build());
  }

  static boolean isNotFound(Throwable failure) {
    return Status.fromThrowable(failure).getCode() == Status.Code.NOT_FOUND;
  }
}

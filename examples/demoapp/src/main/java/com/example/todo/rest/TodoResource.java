package com.example.todo.rest;

import com.example.todo.model.Todo;
import com.example.todo.service.TodoService;
import jakarta.inject.Inject;
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

@Path("/todos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TodoResource {

  @Inject TodoService todoService;

  @GET
  public List<Todo> list() {
    return todoService.listAll();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") Long id) {
    return todoService
        .findById(id)
        .map(todo -> Response.ok(todo).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @POST
  public Response create(CreateTodoRequest request) {
    Todo todo = todoService.create(request.title());
    return Response.status(Response.Status.CREATED).entity(todo).build();
  }

  @PUT
  @Path("/{id}/complete")
  public Response complete(@PathParam("id") Long id) {
    return todoService
        .complete(id)
        .map(todo -> Response.ok(todo).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") Long id) {
    if (todoService.delete(id)) {
      return Response.noContent().build();
    }
    return Response.status(Response.Status.NOT_FOUND).build();
  }
}

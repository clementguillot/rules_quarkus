package org.acme.grpc.producer;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.acme.grpc.CreateItemRequest;
import org.acme.grpc.Empty;
import org.acme.grpc.Item;
import org.acme.grpc.ItemId;
import org.acme.grpc.ItemList;
import org.acme.grpc.ItemService;

/**
 * PostgreSQL-backed (Hibernate ORM with Panache) CRUD implementation of the {@code ItemService}
 * gRPC contract. Every method is {@code @Blocking} since Panache/JDBC calls block the caller
 * thread; Quarkus dispatches these on a worker thread instead of the gRPC event loop.
 */
@GrpcService
public class ItemGrpcService implements ItemService {

  @Override
  @Blocking
  @Transactional
  public Uni<Item> createItem(CreateItemRequest request) {
    ItemEntity entity = new ItemEntity();
    entity.id = UUID.randomUUID().toString();
    entity.name = request.getName();
    entity.quantity = request.getQuantity();
    entity.persist();
    return Uni.createFrom().item(toProto(entity));
  }

  @Override
  @Blocking
  public Uni<Item> getItem(ItemId request) {
    ItemEntity entity = ItemEntity.findById(request.getId());
    if (entity == null) {
      return Uni.createFrom().failure(notFound(request.getId()));
    }
    return Uni.createFrom().item(toProto(entity));
  }

  @Override
  @Blocking
  public Uni<ItemList> listItems(Empty request) {
    ItemList.Builder list = ItemList.newBuilder();
    ItemEntity.<ItemEntity>listAll().forEach(entity -> list.addItems(toProto(entity)));
    return Uni.createFrom().item(list.build());
  }

  @Override
  @Blocking
  @Transactional
  public Uni<Item> updateItem(Item request) {
    ItemEntity entity = ItemEntity.findById(request.getId());
    if (entity == null) {
      return Uni.createFrom().failure(notFound(request.getId()));
    }
    entity.name = request.getName();
    entity.quantity = request.getQuantity();
    return Uni.createFrom().item(toProto(entity));
  }

  @Override
  @Blocking
  @Transactional
  public Uni<Empty> deleteItem(ItemId request) {
    if (!ItemEntity.deleteById(request.getId())) {
      return Uni.createFrom().failure(notFound(request.getId()));
    }
    return Uni.createFrom().item(Empty.getDefaultInstance());
  }

  private static Item toProto(ItemEntity entity) {
    return Item.newBuilder()
        .setId(entity.id)
        .setName(entity.name)
        .setQuantity(entity.quantity)
        .build();
  }

  private static StatusRuntimeException notFound(String id) {
    return Status.NOT_FOUND.withDescription("Item " + id + " not found").asRuntimeException();
  }
}

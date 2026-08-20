package org.acme.grpc.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import org.acme.grpc.CreateItemRequest;
import org.acme.grpc.Empty;
import org.acme.grpc.Item;
import org.acme.grpc.ItemId;
import org.acme.grpc.ItemList;
import org.acme.grpc.ItemService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ItemGrpcServiceTest {

  @GrpcClient ItemService itemService;

  private static volatile String createdId;

  @Test
  @Order(1)
  void testListEmpty() {
    ItemList list = itemService.listItems(Empty.getDefaultInstance()).await().indefinitely();
    assertEquals(0, list.getItemsCount());
  }

  @Test
  @Order(2)
  void testCreateAndGet() {
    Item created =
        itemService
            .createItem(CreateItemRequest.newBuilder().setName("Widget").setQuantity(5).build())
            .await()
            .indefinitely();
    createdId = created.getId();
    assertEquals("Widget", created.getName());
    assertEquals(5, created.getQuantity());

    Item fetched =
        itemService.getItem(ItemId.newBuilder().setId(createdId).build()).await().indefinitely();
    assertEquals(createdId, fetched.getId());
  }

  @Test
  @Order(3)
  void testUpdate() {
    Item updated =
        itemService
            .updateItem(
                Item.newBuilder().setId(createdId).setName("Widget").setQuantity(10).build())
            .await()
            .indefinitely();
    assertEquals(10, updated.getQuantity());
  }

  @Test
  @Order(4)
  void testDelete() {
    itemService.deleteItem(ItemId.newBuilder().setId(createdId).build()).await().indefinitely();
    ItemList list = itemService.listItems(Empty.getDefaultInstance()).await().indefinitely();
    assertEquals(0, list.getItemsCount());
  }
}

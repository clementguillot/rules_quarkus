package org.acme.grpc.consumer;

import org.acme.grpc.Item;

/** JSON-friendly view of the producer's {@code Item} protobuf message. */
public record ItemDto(String id, String name, int quantity) {

  static ItemDto from(Item item) {
    return new ItemDto(item.getId(), item.getName(), item.getQuantity());
  }
}

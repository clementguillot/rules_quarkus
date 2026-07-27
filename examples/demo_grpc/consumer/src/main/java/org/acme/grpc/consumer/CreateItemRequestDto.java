package org.acme.grpc.consumer;

/** Request body for creating a new item. */
public record CreateItemRequestDto(String name, int quantity) {}

package org.acme.grpc.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

class ItemResourceFailureMappingTest {

  @Test
  void onlyGrpcNotFoundFailuresMapToHttpNotFound() {
    assertTrue(ItemResource.isNotFound(Status.NOT_FOUND.asRuntimeException()));
    assertFalse(ItemResource.isNotFound(Status.INTERNAL.asRuntimeException()));
    assertFalse(ItemResource.isNotFound(new IllegalStateException("transport failure")));
  }
}

# demo_grpc — gRPC Producer/Consumer Example

Two independent Quarkus apps that demonstrate composable extension-driven code
generation with gRPC and Avro (see
[`docs/code-generation.md`](../../docs/code-generation.md)):

- **producer**: implements a dummy `ItemService` CRUD gRPC service, backed by
  PostgreSQL via Hibernate ORM with Panache.
- **consumer**: a REST + OpenAPI façade that proxies every `/items` request
  onto the producer over gRPC.

Both apps compile against the same shared `//messages` library. That target
runs two independent `CodeGenProvider` families in one action:

- gRPC generates protobuf messages and service stubs from `item.proto`;
- Avro generates `AuditEvent` from `audit-event.avsc`.

The same target also compiles handwritten `ContractEnvelope.java`, which
combines both generated types. The downstream consumer uses the generated gRPC
and Avro APIs plus that handwritten class in `ContractResource`.

## Project Structure

```
demo_grpc/
├── MODULE.bazel              # Bazel module with Quarkus 3.33.2
├── messages/                 # Shared mixed generated + handwritten contract library
│   ├── BUILD.bazel           # one quarkus_java_library, two codegen extensions
│   ├── src/main/proto/item.proto
│   ├── src/main/avro/audit-event.avsc
│   └── src/main/java/org/acme/contracts/ContractEnvelope.java
├── producer/                 # gRPC server: dummy CRUD ItemService, PostgreSQL-backed
│   ├── src/main/java/org/acme/grpc/producer/ItemGrpcService.java
│   ├── src/main/java/org/acme/grpc/producer/ItemEntity.java
│   ├── src/main/resources/application.properties
│   └── src/test/java/org/acme/grpc/producer/ItemGrpcServiceTest.java
└── consumer/                 # REST/OpenAPI proxy in front of the producer
    ├── src/main/java/org/acme/grpc/consumer/ItemResource.java
    ├── src/main/java/org/acme/grpc/consumer/ContractResource.java
    ├── src/main/java/org/acme/grpc/consumer/ItemDto.java
    ├── src/main/java/org/acme/grpc/consumer/CreateItemRequestDto.java
    ├── src/main/resources/application.properties
    └── src/test/java/org/acme/grpc/consumer/ItemResourceOpenApiTest.java
```

## Key Points

- **messages**: one `quarkus_java_library` whose `codegen_srcs` contain both
  protobuf and Avro inputs. Quarkus discovers both providers and generates the
  `Item`/`ItemService` gRPC classes and the Avro `AuditEvent`. Its handwritten
  `ContractEnvelope` compiles against both generated APIs.
- **producer**: a plain `java_library` implementing the generated Mutiny
  `ItemService` interface, exposed with `@GrpcService` on its own gRPC port
  (`9000`, configured via `quarkus.grpc.server.port`). `ItemEntity` is a
  Panache active-record entity (`PanacheEntityBase` with a manual `String`
  `@Id`, matching the proto `Item.id` field); every service method is
  `@Blocking` (JDBC blocks) and mutating ones are `@Transactional`.
  `quarkus.datasource.devservices.enabled=true` auto-starts a disposable
  PostgreSQL container for both `producer_dev` and `producer:test` — **Docker
  must be running**. Dev Services only applies in dev/test mode; running the
  packaged `bazel run //producer:producer` needs a real
  `quarkus.datasource.jdbc.url` configured.
- **consumer**: injects a named `@GrpcClient("items")` stub pointed at the
  producer (`quarkus.grpc.clients.items.host`/`.port`) and re-exposes the
  same operations as JSON REST endpoints under `/items`, documented via
  `quarkus-smallrye-openapi` at `/q/openapi` and `/q/swagger-ui`.
  `/contracts/example` additionally proves that a downstream target can use
  both generated APIs and the handwritten `ContractEnvelope`.

  It also sets `quarkus.grpc.dev-mode.force-server-start=false`: without it,
  Quarkus's gRPC Dev UI support force-starts a local gRPC server in dev mode
  even for a client-only app, which binds the same default port (`9000`) the
  producer already uses — a conflict that only shows up when running
  `consumer_dev` and `producer_dev` side by side.

## Build & Run

Docker must be running (Dev Services starts a disposable PostgreSQL container
for the producer).

```bash
# Build everything
bazel build //...

# Run the producer in dev mode (gRPC on :9000, Dev Services starts PostgreSQL)
bazel run //producer:producer_dev

# In another terminal, run the consumer (REST on :8080)
bazel run //consumer:consumer

# Run tests
bazel test //...
```

With both apps running:

```bash
curl -X POST localhost:8080/items -H 'Content-Type: application/json' -d '{"name":"Widget","quantity":5}'
curl localhost:8080/items
```

Open <http://localhost:8080/q/swagger-ui> to browse the consumer's generated
OpenAPI document.

## API (consumer, proxied to the producer over gRPC)

| Method | Path         | Description       |
|--------|--------------|--------------------|
| GET    | /items       | List all items     |
| GET    | /items/{id}  | Get an item by id   |
| POST   | /items       | Create an item      |
| PUT    | /items/{id}  | Update an item      |
| DELETE | /items/{id}  | Delete an item      |

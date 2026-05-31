# demoapp — Multi-Library Quarkus Example

A simple Todo REST API demonstrating `rules_quarkus` with multiple library modules.

## Project Structure

```
demoapp/
├── BUILD.bazel              # App layer: REST resource + quarkus_app + quarkus_test
├── MODULE.bazel             # Bazel module with Quarkus 3.33.1
├── libs/
│   ├── todo-model/          # Plain Java library (no framework deps)
│   │   └── src/main/java/com/example/todo/model/Todo.java
│   └── todo-service/        # CDI service library (depends on todo-model)
│       ├── src/main/java/com/example/todo/service/TodoService.java
│       └── src/main/resources/META-INF/beans.xml
└── src/
    ├── main/java/com/example/todo/
    │   ├── TodoResource.java
    │   └── CreateTodoRequest.java
    └── test/java/com/example/todo/
        └── TodoResourceTest.java
```

## Key Points

- **todo-model**: Framework-agnostic POJO — no CDI, no JAX-RS dependencies.
- **todo-service**: CDI `@ApplicationScoped` bean with in-memory storage. Includes `beans.xml` so Quarkus discovers beans from this jar.
- **Root app**: REST endpoints using `quarkus-rest-jackson` for JSON serialization, injecting `TodoService` from the service library.

## Build & Run

```bash
# Build all targets
bazel build //...

# Run the application
bazel run //:demoapp

# Run tests
bazel test //:test
```

## API

| Method | Path               | Description        |
|--------|--------------------|--------------------|
| GET    | /todos             | List all todos     |
| GET    | /todos/{id}        | Get a todo by ID   |
| POST   | /todos             | Create a todo      |
| PUT    | /todos/{id}/complete | Mark as completed |
| DELETE | /todos/{id}        | Delete a todo      |

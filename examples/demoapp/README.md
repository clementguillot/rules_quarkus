# demoapp — Multi-Library Quarkus Example

A simple Todo REST API demonstrating `rules_quarkus` with multiple library modules.

## Project Structure

```
demoapp/
├── BUILD.bazel              # App layer: REST resource + quarkus_app + quarkus_test
├── MODULE.bazel             # Bazel module with Quarkus 3.33.2
├── dev-probes/              # Inactive templates for adding source/test files
├── libs/
│   ├── todo-model/          # Plain Java library (no framework deps)
│   │   └── src/main/java/com/example/todo/model/Todo.java
│   └── todo-service/        # CDI service library (depends on todo-model)
│       ├── src/main/java/com/example/todo/service/TodoService.java
│       ├── src/main/resources/META-INF/beans.xml
│       └── src/test/java/com/example/todo/service/TodoServiceTest.java
└── src/
    ├── main/java/com/example/todo/rest/
    │   ├── TodoResource.java
    │   └── CreateTodoRequest.java
    └── test/java/com/example/todo/rest/
        ├── TodoResourceTest.java
        └── ReloadProbeTest.java
```

## Key Points

- **todo-model**: Framework-agnostic POJO — no CDI, no JAX-RS dependencies.
- **todo-service**: CDI `@ApplicationScoped` bean with in-memory storage. Includes `beans.xml` so Quarkus discovers beans from this jar. It owns a standalone `quarkus_test`; `demoapp` lists that target alongside the application test for continuous testing.
- **Root app**: REST endpoints using `quarkus-rest-jackson` for JSON serialization, injecting `TodoService` from the service library.

## Build & Run

```bash
# Build all targets
bazel build //...

# Run the application
bazel run //:demoapp

# Run every application and module test exactly once
bazel test //...

# Run only the todo-service module's Quarkus test
bazel test //libs/todo-service:test

# Run hot reload and continuous testing (press r to start the tests)
bazel run //:demoapp_dev
```

## Try live changes

From this directory, leave `bazel run //:demoapp_dev` running and press `r` in
its console. The eight aggregated application and service-module tests should
pass and appear in the Dev UI Continuous Testing tab. These probes are safe to try and
revert:

- Change the expected status in `ReloadProbeTest.java` from `200` to `201`:
  continuous testing reports a failure. Change it back to recover.
- Change `TodoService.create()` to prefix the title with `"changed-"`:
  `TodoResourceTest` fails, and a POST to `/todos` shows the new title. Revert
  the edit to recover. Edits to `Todo.getTitle()` in `libs/todo-model` also
  propagate through the service and app.
- Copy the source template below into the app's source tree. `/added-probe`
  becomes available without restarting dev mode. Delete the copied `.java`
  file and the endpoint disappears.
- Copy the test template below into the service module's test tree. The
  continuous test count rises from eight to nine; delete the copied `.java`
  file to return to eight.

```bash
cp dev-probes/AddedResource.java.template src/main/java/com/example/todo/rest/AddedResource.java
curl http://localhost:8080/added-probe
rm src/main/java/com/example/todo/rest/AddedResource.java

cp dev-probes/AddedTest.java.template libs/todo-service/src/test/java/com/example/todo/service/AddedTest.java
rm libs/todo-service/src/test/java/com/example/todo/service/AddedTest.java
```

The templates live outside `srcs` globs until copied; only the copied `.java`
files participate in the build. If Bazel's configuration flags are changed for
the dev run, set matching `dev_build_args` in `BUILD.bazel` before starting it.

## API

| Method | Path               | Description        |
|--------|--------------------|--------------------|
| GET    | /todos             | List all todos     |
| GET    | /todos/{id}        | Get a todo by ID   |
| POST   | /todos             | Create a todo      |
| PUT    | /todos/{id}/complete | Mark as completed |
| DELETE | /todos/{id}        | Delete a todo      |

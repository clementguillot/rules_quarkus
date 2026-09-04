import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Live source-event regression against a disposable Quarkus 3.33 Bazel workspace. */
public final class SourceEventsTest {
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static final Pattern PORT = Pattern.compile("Listening on: http://localhost:(\\d+)");
  private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(120);

  private SourceEventsTest() {}

  public static void main(String[] args) throws Exception {
    Path smoke =
        Path.of(System.getenv("TEST_SRCDIR"), "_main", "MODULE.bazel").toRealPath().getParent();
    Path fixture = smoke.resolve("source_events");
    Path workspace =
        Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "quarkus-source-events-");
    String bazel = bazelCommand();
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment
        .keySet()
        .removeIf(
            key ->
                key.startsWith("TEST_")
                    || key.startsWith("RUNFILES_")
                    || key.equals("BUILD_WORKSPACE_DIRECTORY"));
    environment.put("QUARKUS_HTTP_PORT", "0");
    environment.put("QUARKUS_HTTP_TEST_PORT", "0");
    environment.put("QUARKUS_CONSOLE_COLOR", "false");
    prepare(smoke, fixture, workspace);
    try {
      ordinaryDevMode(bazel, environment, workspace, fixture);
      continuousTesting(bazel, environment, workspace, fixture);
    } finally {
      Process shutdown =
          new ProcessBuilder(bazel, "shutdown")
              .directory(workspace.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      shutdown.waitFor(60, TimeUnit.SECONDS);
    }
  }

  private static void prepare(Path smoke, Path fixture, Path workspace) throws IOException {
    Path repository = smoke.getParent().getParent();
    String module = Files.readString(smoke.resolve("MODULE.bazel"));
    if (!module.contains("path = \"../..\"")) {
      throw new AssertionError("smoke MODULE.bazel no longer has the expected local override");
    }
    Files.writeString(
        workspace.resolve("MODULE.bazel"),
        module.replace("path = \"../..\"", "path = \"" + repository + "\""));
    Files.copy(smoke.resolve("maven_install.json"), workspace.resolve("maven_install.json"));
    Files.copy(smoke.resolve(".bazelrc"), workspace.resolve(".bazelrc"));
    Files.copy(fixture.resolve("BUILD.bazel.tpl"), workspace.resolve("BUILD.bazel"));
    Path main = Files.createDirectories(workspace.resolve("src/main/java/smoke"));
    Path tests = Files.createDirectories(workspace.resolve("src/test/java/smoke"));
    Files.copy(fixture.resolve("State.java"), main.resolve("State.java"));
    Files.copy(fixture.resolve("StateResource.java"), main.resolve("StateResource.java"));
    Files.copy(fixture.resolve("StateTest.java"), tests.resolve("StateTest.java"));
    Files.copy(fixture.resolve("OtherTest.java"), tests.resolve("OtherTest.java"));
  }

  private static void ordinaryDevMode(
      String bazel, Map<String, String> environment, Path workspace, Path fixture)
      throws Exception {
    Path log = workspace.resolve("ordinary.log");
    Process process = launch(bazel, environment, workspace, "ordinary_dev", log);
    try {
      int port = awaitPort(process, log);
      Path state = workspace.resolve("src/main/java/smoke/State.java");
      Path resource = workspace.resolve("src/main/java/smoke/StateResource.java");
      Path added = workspace.resolve("src/main/java/smoke/newpkg/AddedResource.java");
      assertHttp(port, "/source-events", 200, "baseline");
      assertHttp(port, "/added-source", 404, null);

      replace(state, "baseline", "updated");
      await(
          "ordinary mode reloads an updated source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/source-events", 200, "updated"));
      replace(state, "updated", "baseline");
      await(
          "ordinary mode reloads the restored source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/source-events", 200, "baseline"));
      System.out.println("PASS: ordinary dev mode updates a source file");

      Files.delete(resource);
      await(
          "ordinary mode unloads an initially declared source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/source-events", 404, null));
      Files.copy(fixture.resolve("StateResource.java"), resource);
      await(
          "ordinary mode reloads the restored source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/source-events", 200, "baseline"));
      System.out.println("PASS: ordinary dev mode removes and restores a declared source file");

      Files.createDirectories(added.getParent());
      Files.copy(fixture.resolve("AddedResource.java.tpl"), added);
      await(
          "ordinary mode loads a newly added source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/added-source", 200, "added"));
      System.out.println("PASS: ordinary dev mode adds a source file");

      Files.delete(added);
      await(
          "ordinary mode unloads a removed source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/added-source", 404, null));
      System.out.println("PASS: ordinary dev mode removes a source file");
    } catch (Throwable error) {
      throw withLog(error, log);
    } finally {
      stop(process);
    }
  }

  private static void continuousTesting(
      String bazel, Map<String, String> environment, Path workspace, Path fixture)
      throws Exception {
    Path log = workspace.resolve("continuous.log");
    Process process = launch(bazel, environment, workspace, "continuous_dev", log);
    try {
      int port = awaitPort(process, log);
      Path state = workspace.resolve("src/main/java/smoke/State.java");
      Path resource = workspace.resolve("src/main/java/smoke/StateResource.java");
      Path addedSource = workspace.resolve("src/main/java/smoke/newpkg/AddedResource.java");
      Path test = workspace.resolve("src/test/java/smoke/StateTest.java");
      Path addedTest = workspace.resolve("src/test/java/smoke/newpkg/AddedTest.java");
      rpc(port, "start");
      Status status = awaitStatus(port, 0, 2, 0, "initial continuous test run");

      replace(state, "baseline", "updated");
      status = awaitStatus(port, status.lastRun(), -1, 1, "updated application source");
      await(
          "continuous mode reloads an updated source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/source-events", 200, "updated"));
      replace(state, "updated", "baseline");
      status = awaitStatus(port, status.lastRun(), -1, 0, "restored application source");
      System.out.println("PASS: continuous testing updates an application source");

      Files.delete(resource);
      status = awaitStatus(port, status.lastRun(), -1, 0, "removed declared application source");
      await(
          "continuous mode unloads an initially declared source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/source-events", 404, null));
      Files.copy(fixture.resolve("StateResource.java"), resource);
      status = awaitStatus(port, status.lastRun(), -1, 0, "restored declared application source");
      await(
          "continuous mode reloads the restored source",
          EVENT_TIMEOUT,
          () -> httpMatches(port, "/source-events", 200, "baseline"));
      System.out.println("PASS: continuous testing removes and restores an application source");

      replace(test, "assertEquals(\"baseline\"", "assertEquals(\"updated\"");
      status = awaitStatus(port, status.lastRun(), -1, 1, "updated test source");
      replace(test, "assertEquals(\"updated\"", "assertEquals(\"baseline\"");
      status = awaitStatus(port, status.lastRun(), -1, 0, "restored test source");
      System.out.println("PASS: continuous testing updates a test source");

      Files.delete(test);
      status = awaitStatus(port, status.lastRun(), 1, 0, "removed declared test source");
      Files.copy(fixture.resolve("StateTest.java"), test);
      status = awaitStatus(port, status.lastRun(), 2, 0, "restored declared test source");
      System.out.println("PASS: continuous testing removes and restores a test source");

      List<String> creationFailures = new ArrayList<>();
      Files.createDirectories(addedSource.getParent());
      Files.copy(fixture.resolve("AddedResource.java.tpl"), addedSource);
      try {
        status = awaitStatus(port, status.lastRun(), -1, 0, "new application source");
        await(
            "continuous mode loads a newly added source",
            EVENT_TIMEOUT,
            () -> httpMatches(port, "/added-source", 200, "added"));
        System.out.println("PASS: continuous testing adds an application source");
        Files.delete(addedSource);
        status =
            awaitStatus(port, status.lastRun(), -1, 0, "removed newly added application source");
        await(
            "continuous mode unloads the newly added source",
            EVENT_TIMEOUT,
            () -> httpMatches(port, "/added-source", 404, null));
        System.out.println("PASS: continuous testing removes a newly added application source");
      } catch (AssertionError error) {
        creationFailures.add("application source creation: " + error.getMessage());
        Files.deleteIfExists(addedSource);
        status = Status.parse(rpc(port, "getStatus"));
      }

      Files.createDirectories(addedTest.getParent());
      Files.copy(fixture.resolve("AddedTest.java.tpl"), addedTest);
      try {
        status = awaitStatus(port, status.lastRun(), -1, 1, "new test source");
        System.out.println("PASS: continuous testing adds a test source");
        Files.delete(addedTest);
        awaitStatus(port, status.lastRun(), -1, 0, "removed newly added test source");
        System.out.println("PASS: continuous testing removes a newly added test source");
      } catch (AssertionError error) {
        creationFailures.add("test source creation: " + error.getMessage());
        Files.deleteIfExists(addedTest);
      }
      if (!creationFailures.isEmpty()) {
        throw new AssertionError(String.join("\n", creationFailures));
      }
    } catch (Throwable error) {
      throw withLog(error, log);
    } finally {
      stop(process);
    }
  }

  private static String bazelCommand() {
    String path = System.getenv("PATH");
    for (String directory : path.split(java.io.File.pathSeparator)) {
      for (String name : new String[] {"bazel", "bazelisk"}) {
        Path candidate = Path.of(directory, name);
        if (Files.isExecutable(candidate)) {
          return candidate.toString();
        }
      }
    }
    throw new AssertionError("Bazel must be on PATH for the live source-event smoke");
  }

  private static Process launch(
      String bazel, Map<String, String> environment, Path workspace, String target, Path log)
      throws IOException {
    ProcessBuilder builder = new ProcessBuilder(bazel, "run", "//:" + target);
    builder.directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    return builder.start();
  }

  private static int awaitPort(Process process, Path log) throws Exception {
    return await(
        "Quarkus dev mode startup",
        Duration.ofSeconds(600),
        () -> {
          if (!process.isAlive()) {
            throw new AssertionError("dev process exited before startup");
          }
          Matcher match = PORT.matcher(Files.readString(log));
          return match.find() ? Integer.parseInt(match.group(1)) : null;
        });
  }

  private static void replace(Path file, String before, String after) throws IOException {
    String original = Files.readString(file);
    if (!original.contains(before)) {
      throw new AssertionError(file + " does not contain " + before);
    }
    Files.writeString(file, original.replace(before, after));
  }

  private static void assertHttp(int port, String path, int code, String body) throws Exception {
    if (!httpMatches(port, path, code, body)) {
      throw new AssertionError(path + " did not return " + code + " / " + body);
    }
  }

  private static boolean httpMatches(int port, String path, int code, String body)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    return response.statusCode() == code && (body == null || response.body().equals(body));
  }

  private static Status awaitStatus(int port, long previous, int passed, int failed, String event)
      throws Exception {
    Status[] last = {null};
    try {
      return await(
          event + " triggers the expected test run",
          EVENT_TIMEOUT,
          () -> {
            Status status = Status.parse(rpc(port, "getStatus"));
            last[0] = status;
            return status.lastRun() > previous
                    && (passed < 0 || status.passed() == passed)
                    && status.failed() == failed
                    && status.running() == -1
                ? status
                : null;
          });
    } catch (AssertionError error) {
      throw new AssertionError(error.getMessage() + "; last status=" + last[0], error);
    }
  }

  private static String rpc(int port, String method) throws Exception {
    CompletableFuture<String> response = new CompletableFuture<>();
    WebSocket.Listener listener =
        new WebSocket.Listener() {
          private final StringBuilder message = new StringBuilder();

          @Override
          public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            message.append(text);
            if (last) {
              String value = message.toString();
              message.setLength(0);
              if (Pattern.compile("\\\"id\\\"\\s*:\\s*1").matcher(value).find()) {
                response.complete(value);
              }
            }
            socket.request(1);
            return null;
          }

          @Override
          public void onError(WebSocket socket, Throwable error) {
            response.completeExceptionally(error);
          }
        };
    WebSocket socket =
        HTTP.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create("ws://localhost:" + port + "/q/dev-ui/json-rpc-ws"), listener)
            .get(10, TimeUnit.SECONDS);
    try {
      socket
          .sendText(
              "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"devui-continuous-testing_"
                  + method
                  + "\",\"params\":{}}",
              true)
          .get(10, TimeUnit.SECONDS);
      String value = response.get(10, TimeUnit.SECONDS);
      if (value.contains("\"error\"")) {
        throw new AssertionError("Dev UI JSON-RPC error: " + value);
      }
      return value;
    } finally {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
  }

  private record Status(long lastRun, int passed, int failed, int running) {
    static Status parse(String json) {
      return new Status(
          number(json, "lastRun"),
          (int) number(json, "testsPassed"),
          (int) number(json, "testsFailed"),
          (int) number(json, "running"));
    }

    private static long number(String json, String key) {
      Matcher match = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
      if (!match.find()) {
        throw new AssertionError("Dev UI response has no " + key + ": " + json);
      }
      return Long.parseLong(match.group(1));
    }
  }

  private static <T> T await(String description, Duration timeout, Callable<T> check)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    Throwable last = null;
    while (System.nanoTime() < deadline) {
      try {
        T value = check.call();
        if (value instanceof Boolean result ? result : value != null) {
          return value;
        }
      } catch (Exception | AssertionError error) {
        last = error;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Timed out waiting for " + description, last);
  }

  private static AssertionError withLog(Throwable error, Path log) throws IOException {
    String contents = Files.readString(log);
    String tail = contents.substring(Math.max(0, contents.length() - 30000));
    return new AssertionError(error + "\nDev-mode log:\n" + tail, error);
  }

  private static void stop(Process process) throws InterruptedException {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroy();
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor();
    }
  }
}

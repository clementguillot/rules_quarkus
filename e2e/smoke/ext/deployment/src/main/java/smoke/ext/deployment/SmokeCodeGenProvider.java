package smoke.ext.deployment;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Small generic SPI provider used to certify rules_quarkus code generation. */
public final class SmokeCodeGenProvider implements CodeGenProvider {

  private String initPrefix;

  @Override
  public String providerId() {
    return "smoke";
  }

  @Override
  public String inputExtension() {
    return "hello";
  }

  @Override
  public String inputDirectory() {
    return "hello";
  }

  @Override
  public void init(ApplicationModel model, Map<String, String> properties) {
    initPrefix = properties.getOrDefault("smoke.codegen.init-prefix", "");
    if (Boolean.parseBoolean(properties.get("smoke.codegen.require-application-properties"))
        && (!properties.containsKey("quarkus.application.name")
            || !properties.containsKey("quarkus.application.version")
            || properties.containsKey("user.dir")
            || !"Declared init ".equals(initPrefix)
            || !"round trip".equals(properties.get("smoke:codegen key")))) {
      throw new IllegalStateException(
          "Effective properties are missing declared/application defaults or include ambient JVM"
              + " state");
    }
  }

  @Override
  public boolean trigger(CodeGenContext context) throws CodeGenException {
    String prefix =
        context.config().getOptionalValue("smoke.codegen.prefix", String.class).orElse("");
    try (Stream<Path> inputs = Files.list(context.inputDir())) {
      for (Path input :
          inputs
              .filter(path -> path.getFileName().toString().endsWith(".hello"))
              .sorted(Comparator.comparing(Path::toString))
              .toList()) {
        String stem =
            input
                .getFileName()
                .toString()
                .replaceFirst("\\.hello$", "")
                .replaceAll("[^A-Za-z0-9]+", " ");
        StringBuilder className = new StringBuilder("Generated");
        for (String word : stem.split(" +")) {
          if (!word.isEmpty()) {
            className
                .append(word.substring(0, 1).toUpperCase(Locale.ROOT))
                .append(word.substring(1));
          }
        }
        String message = initPrefix + prefix + Files.readString(input).strip();
        if (context
            .config()
            .getOptionalValue("smoke.codegen.work-report", Boolean.class)
            .orElse(false)) {
          Path report = context.workDir().resolve("reports/" + className + ".txt");
          Files.createDirectories(report.getParent());
          Files.writeString(report, message);
        }
        if (context
            .config()
            .getOptionalValue("smoke.codegen.aux-only", Boolean.class)
            .orElse(false)) {
          Path auxiliary = context.outDir().resolve("META-INF/" + className + ".txt");
          Files.createDirectories(auxiliary.getParent());
          Files.writeString(auxiliary, message);
          continue;
        }
        Path output = context.outDir().resolve("generated/" + className + ".java");
        Files.createDirectories(output.getParent());
        Files.writeString(
            output,
            """
            package smoke.generated;

            public final class %s {
              private %s() {}

              public static String message() {
                return "%s";
              }
            }
            """
                .formatted(className, className, javaString(message)));
      }
      return true;
    } catch (IOException e) {
      throw new CodeGenException("Smoke generator failed", e);
    }
  }

  private static String javaString(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }
}

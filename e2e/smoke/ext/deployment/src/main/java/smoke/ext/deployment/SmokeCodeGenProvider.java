package smoke.ext.deployment;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/** Small generic SPI provider used to certify rules_quarkus code generation. */
public final class SmokeCodeGenProvider implements CodeGenProvider {

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
        String message = prefix + Files.readString(input).strip();
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
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}

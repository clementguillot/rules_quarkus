package com.clementguillot.quarkifier.augmentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

/**
 * Post-processes Quarkus native-sources augmentation output into a well-structured directory
 * suitable for a subsequent {@code native-image} invocation.
 *
 * <p>Output structure:
 *
 * <pre>
 *   output-dir/
 *   └── native-sources/
 *       ├── {app-name}-runner.jar       (thin runner jar)
 *       ├── lib/                         (dependency jars)
 *       ├── native-image.args            (CLI args for native-image)
 *       └── graalvm.version              (required GraalVM version)
 * </pre>
 */
public final class NativeSourcesAssembler {

  private static final String NATIVE_IMAGE_ARGS = "native-image.args";
  private static final Logger LOGGER = Logger.getLogger(NativeSourcesAssembler.class);

  private NativeSourcesAssembler() {}

  /**
   * Validates and normalizes the native-sources output directory produced by Quarkus augmentation.
   *
   * <p>Ensures that all required files exist and that classpath entries in {@code
   * native-image.args} are relative to the native-sources directory (Bazel sandbox compatibility).
   *
   * @param outputDir the quarkifier output directory (contains raw augmentation output)
   * @param runtimeJars the runtime classpath jars (for validation)
   * @return path to the native-sources subdirectory
   * @throws IOException if required files are missing or file operations fail
   */
  public static Path assemble(Path outputDir, List<Path> runtimeJars) throws IOException {
    Path nativeSources = findNativeSourcesDir(outputDir);
    if (nativeSources == null) {
      throw new IOException("native-sources directory not found in " + outputDir);
    }

    // Validate required files
    Path argsFile = nativeSources.resolve(NATIVE_IMAGE_ARGS);
    if (!Files.exists(argsFile)) {
      throw new IOException(NATIVE_IMAGE_ARGS + " not found in " + nativeSources);
    }

    Path versionFile = nativeSources.resolve("graalvm.version");
    if (!Files.exists(versionFile)) {
      throw new IOException("graalvm.version not found in " + nativeSources);
    }

    Path libDir = nativeSources.resolve("lib");
    if (!Files.isDirectory(libDir)) {
      throw new IOException("lib/ directory not found in " + nativeSources);
    }

    LOGGER.infof("Native sources validated at: %s", nativeSources);
    LOGGER.infof("GraalVM version: %s", Files.readString(versionFile).strip());

    // Rewrite classpath entries to be relative (idempotent operation)
    String argsContent = Files.readString(argsFile);
    String rewritten = rewriteClasspathEntries(argsContent, nativeSources);
    Files.writeString(argsFile, rewritten);

    return nativeSources;
  }

  /**
   * Locates the native-sources directory within the output directory tree.
   *
   * <p>Quarkus places native-sources under {@code {baseName}-native-image-source-jar/} when using
   * the sources-only mode. We search for a directory containing {@code native-image.args}.
   */
  private static Path findNativeSourcesDir(Path outputDir) throws IOException {
    // Direct child: output-dir/native-sources/
    Path direct = outputDir.resolve("native-sources");
    if (Files.isDirectory(direct) && Files.exists(direct.resolve(NATIVE_IMAGE_ARGS))) {
      return direct;
    }

    // Search one level deep for a directory containing native-image.args
    try (Stream<Path> children = Files.list(outputDir)) {
      for (Path child : children.toList()) {
        if (Files.isDirectory(child)) {
          Path argsFile = child.resolve(NATIVE_IMAGE_ARGS);
          if (Files.exists(argsFile)) {
            return child;
          }
          // Also check nested: child/native-sources/ pattern
          Path nested = child.resolve("native-sources");
          if (Files.isDirectory(nested) && Files.exists(nested.resolve(NATIVE_IMAGE_ARGS))) {
            return nested;
          }
        }
      }
    }

    return null;
  }

  /**
   * Rewrites classpath entries in native-image.args to be relative to the native-sources directory.
   *
   * <p>Quarkus in sources-only mode already produces relative paths, but we normalize to ensure
   * Bazel sandbox compatibility. This operation is idempotent.
   */
  static String rewriteClasspathEntries(String args, Path baseDir) {
    // native-image.args produced by Quarkus sources-only mode uses relative paths.
    // If absolute paths are present (shouldn't happen but defensive), convert them.
    String baseDirStr = baseDir.toAbsolutePath().toString();
    String result = args;
    if (result.contains(baseDirStr)) {
      result = result.replace(baseDirStr + "/", "");
      result = result.replace(baseDirStr, "");
    }
    return result;
  }
}

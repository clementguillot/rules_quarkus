package com.clementguillot.quarkifier.augmentation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Writer for {@code quarkus-application.dat}.
 *
 * <p>The signature of {@code SerializedApplication.write()} is not stable across Quarkus minors
 * (3.33 removed the deprecated {@code nonExistentSourcePaths} parameter). Every supported minor
 * shares one implementation today; this seam lets a future minor call a different overload
 * directly, avoiding reflection.
 */
public interface ApplicationDatWriter {

  /** Singleton instance. */
  ApplicationDatWriter INSTANCE = new ApplicationDatWriterImpl();

  /**
   * Writes the quarkus-application.dat file using the version-appropriate method signature.
   *
   * @param os output stream to write to
   * @param mainClass the main class name
   * @param applicationRoot the application root path
   * @param classPath all jars in the classpath
   * @param parentFirst parent-first jars
   * @throws IOException if writing fails
   */
  void write(
      OutputStream os,
      String mainClass,
      Path applicationRoot,
      List<Path> classPath,
      List<Path> parentFirst)
      throws IOException;
}

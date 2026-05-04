package com.clementguillot.quarkifier.augmentation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Version-specific writer for {@code quarkus-application.dat}.
 *
 * <p>The signature of {@code SerializedApplication.write()} changed between Quarkus 3.27 and 3.33:
 *
 * <ul>
 *   <li>3.27: {@code write(OutputStream, String, Path, List<Path>, List<Path>, List<?>)}
 *   <li>3.33+: {@code write(OutputStream, String, Path, List<Path>, List<Path>)}
 * </ul>
 *
 * <p>Each per-minor source directory provides an implementation of this interface that calls the
 * correct overload directly, avoiding reflection.
 */
public interface ApplicationDatWriter {

  /** Singleton instance — resolved at compile time from the version-specific source directory. */
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

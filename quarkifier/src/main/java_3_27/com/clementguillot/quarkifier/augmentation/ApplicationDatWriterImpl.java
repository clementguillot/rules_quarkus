package com.clementguillot.quarkifier.augmentation;

import io.quarkus.bootstrap.runner.SerializedApplication;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Quarkus 3.27 implementation calling the 6-arg {@code SerializedApplication.write()} signature.
 *
 * <p>In 3.27, the method requires an additional {@code List<?>} parameter (nonExistentSourcePaths)
 * which we pass as an empty list.
 */
public final class ApplicationDatWriterImpl implements ApplicationDatWriter {

  @Override
  public void write(
      OutputStream os,
      String mainClass,
      Path applicationRoot,
      List<Path> classPath,
      List<Path> parentFirst)
      throws IOException {
    SerializedApplication.write(os, mainClass, applicationRoot, classPath, parentFirst, List.of());
  }
}

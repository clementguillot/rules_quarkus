package com.clementguillot.quarkifier.codegen;

import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.CodeGenerator;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Builds and scopes the effective build-system properties seen by code generators. */
final class CodeGenerationProperties {

  private CodeGenerationProperties() {}

  static Properties effective(
      QuarkusClassLoader deploymentClassLoader,
      ApplicationModel applicationModel,
      Properties declaredProperties,
      String launchMode)
      throws ReflectiveOperationException {
    Class<?> codeGenerator = deploymentClassLoader.loadClass(CodeGenerator.class.getName());
    Method getConfig = codeGeneratorConfigEntryPoint(codeGenerator);
    Class<?> launchModeType = getConfig.getParameterTypes()[1];
    Object mode =
        launchModeType
            .getMethod("valueOf", String.class)
            .invoke(null, launchMode.toUpperCase(Locale.ROOT));
    Object config =
        getConfig.invoke(null, applicationModel, mode, declaredProperties, deploymentClassLoader);

    Properties effective = new Properties();
    effective.putAll(declaredProperties);
    Iterable<?> propertyNames =
        (Iterable<?>) config.getClass().getMethod("getPropertyNames").invoke(config);
    Method getConfigValue = config.getClass().getMethod("getConfigValue", String.class);
    for (Object propertyName : propertyNames) {
      String name = propertyName.toString();
      Object value = getConfigValue.invoke(config, name);
      String resolved = (String) value.getClass().getMethod("getValue").invoke(value);
      if (resolved != null && !isAmbientConfigSource(configSourceName(value))) {
        effective.setProperty(name, resolved);
      }
    }
    return effective;
  }

  static Method codeGeneratorConfigEntryPoint(Class<?> codeGenerator) throws NoSuchMethodException {
    for (Method method : codeGenerator.getMethods()) {
      if ("getConfig".equals(method.getName()) && method.getParameterCount() == 4) {
        return method;
      }
    }
    throw new NoSuchMethodException(codeGenerator.getName() + ".getConfig");
  }

  private static String configSourceName(Object value) {
    for (String methodName : List.of("getConfigSourceName", "getSourceName")) {
      try {
        Object sourceName = value.getClass().getMethod(methodName).invoke(value);
        return sourceName == null ? null : sourceName.toString();
      } catch (ReflectiveOperationException ignored) {
        // MicroProfile Config versions expose this metadata under different
        // names; try the next supported spelling.
      }
    }
    return null;
  }

  private static boolean isAmbientConfigSource(String sourceName) {
    if (sourceName == null) {
      return false;
    }
    String normalized = sourceName.toLowerCase(Locale.ROOT);
    return normalized.contains("sysprop")
        || normalized.contains("system property")
        || normalized.contains("envconfigsource")
        || normalized.contains("environment");
  }

  @FunctionalInterface
  interface ReflectiveAction {
    void run() throws ReflectiveOperationException;
  }

  static void withQuarkusSystemProperties(Properties properties, ReflectiveAction action)
      throws ReflectiveOperationException {
    Map<String, String> previous = new HashMap<>();
    List<String> previouslyAbsent = new ArrayList<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("quarkus.") && !name.startsWith("platform.quarkus.")) {
        continue;
      }
      String oldValue = System.getProperty(name);
      if (oldValue == null) {
        previouslyAbsent.add(name);
      } else {
        previous.put(name, oldValue);
      }
      System.setProperty(name, properties.getProperty(name));
    }
    try {
      action.run();
    } finally {
      for (String name : previouslyAbsent) {
        System.clearProperty(name);
      }
      previous.forEach(System::setProperty);
    }
  }
}

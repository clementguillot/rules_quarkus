package com.clementguillot.quarkifier.codegen;

import static io.smallrye.common.expression.Expression.Flag.LENIENT_SYNTAX;
import static io.smallrye.common.expression.Expression.Flag.NO_TRIM;

import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.CodeGenerator;
import io.smallrye.common.expression.Expression;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/** Builds the effective, hermetic build-system properties seen by code generators. */
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

    Method getConfigValue = config.getClass().getMethod("getConfigValue", String.class);
    Map<String, ConfigProperty> configProperties = new HashMap<>();
    Iterable<?> propertyNames =
        (Iterable<?>) config.getClass().getMethod("getPropertyNames").invoke(config);
    for (Object propertyName : propertyNames) {
      String name = propertyName.toString();
      ConfigProperty property = readConfigProperty(config, getConfigValue, name);
      if (property != null) {
        configProperties.put(name, property);
      }
    }
    Function<String, ConfigProperty> propertyLookup =
        name ->
            configProperties.computeIfAbsent(
                name, ignored -> readConfigProperty(config, getConfigValue, name));

    validateDeclaredExpressionInputs(declaredProperties, propertyLookup);

    Properties effective = new Properties();
    effective.putAll(declaredProperties);
    for (Map.Entry<String, ConfigProperty> entry : configProperties.entrySet()) {
      String name = entry.getKey();
      ConfigProperty property = entry.getValue();
      // Declared properties are deliberately scoped as system properties while Quarkus builds
      // this Config instance. Keep their resolved values (including expression expansion), while
      // still excluding unrelated ambient system properties and environment variables.
      if (property.resolvedValue() != null
          && (declaredProperties.containsKey(name)
              || !isAmbientConfigSource(property.sourceName()))) {
        effective.setProperty(name, property.resolvedValue());
      }
    }
    return effective;
  }

  /** Rejects declared expressions whose result depends on ambient process configuration. */
  static void validateDeclaredExpressionInputs(
      Properties declaredProperties, Function<String, ConfigProperty> propertyLookup) {
    List<String> names = new ArrayList<>(declaredProperties.stringPropertyNames());
    names.sort(String::compareTo);
    Set<String> validated = new HashSet<>();
    for (String name : names) {
      validateExpression(
          name,
          name,
          declaredProperties.getProperty(name),
          declaredProperties,
          propertyLookup,
          validated,
          new HashSet<>());
    }
  }

  private static void validateExpression(
      String declaredName,
      String currentName,
      String rawValue,
      Properties declaredProperties,
      Function<String, ConfigProperty> propertyLookup,
      Set<String> validated,
      Set<String> visiting) {
    if (rawValue == null || validated.contains(currentName)) {
      return;
    }
    if (!visiting.add(currentName)) {
      throw new IllegalArgumentException(
          "Declared build property '"
              + declaredName
              + "' contains a cyclic configuration expression through '"
              + currentName
              + "'");
    }
    try {
      Expression.compile(rawValue, LENIENT_SYNTAX, NO_TRIM)
          .evaluate(
              (resolveContext, expanded) -> {
                String referencedName = resolveContext.getKey();
                ConfigProperty referenced = propertyLookup.apply(referencedName);
                if (referenced == null || referenced.resolvedValue() == null) {
                  if (resolveContext.hasDefault()) {
                    resolveContext.expandDefault();
                    return;
                  }
                  throw new IllegalArgumentException(
                      "Declared build property '"
                          + declaredName
                          + "' references unavailable property '"
                          + referencedName
                          + "'");
                }

                boolean declaredReference = declaredProperties.containsKey(referencedName);
                if (!declaredReference && isAmbientConfigSource(referenced.sourceName())) {
                  throw new IllegalArgumentException(
                      "Declared build property '"
                          + declaredName
                          + "' references ambient property '"
                          + referencedName
                          + "' from "
                          + referenced.sourceName()
                          + "; declare it as a Bazel code-generation input instead");
                }

                String referencedRaw =
                    declaredReference
                        ? declaredProperties.getProperty(referencedName)
                        : referenced.rawValue();
                validateExpression(
                    declaredName,
                    referencedName,
                    referencedRaw,
                    declaredProperties,
                    propertyLookup,
                    validated,
                    visiting);
                expanded.append(referenced.resolvedValue());
              });
      validated.add(currentName);
    } finally {
      visiting.remove(currentName);
    }
  }

  static Method codeGeneratorConfigEntryPoint(Class<?> codeGenerator) throws NoSuchMethodException {
    for (Method method : codeGenerator.getMethods()) {
      if ("getConfig".equals(method.getName()) && method.getParameterCount() == 4) {
        return method;
      }
    }
    throw new NoSuchMethodException(codeGenerator.getName() + ".getConfig");
  }

  private static ConfigProperty readConfigProperty(
      Object config, Method getConfigValue, String name) {
    try {
      Object value = getConfigValue.invoke(config, name);
      if (value == null) {
        return null;
      }
      String resolved = (String) value.getClass().getMethod("getValue").invoke(value);
      String raw = (String) value.getClass().getMethod("getRawValue").invoke(value);
      return new ConfigProperty(raw, resolved, configSourceName(value));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to inspect Quarkus configuration property '" + name + "'", e);
    }
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

  record ConfigProperty(String rawValue, String resolvedValue, String sourceName) {}
}

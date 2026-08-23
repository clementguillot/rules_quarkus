"""Hermetic serialization of declared Quarkus build configuration."""

def _escape_property(value):
    """Escapes a string for Java's UTF-8 `.properties` reader."""
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f").replace(" ", "\\ ").replace("=", "\\=").replace(":", "\\:").replace("#", "\\#").replace("!", "\\!")

def _build_property_key_error(build_properties):
    """Returns the analysis-time error for an unrepresentable key, or "".

    The `.properties` file escapes keys, but the `quarkus_test` lifecycle
    delivers the same map as `-Dkey=value` JVM flags, which the JVM splits on
    the first `=` and cannot escape it. Spaces, colons, and other characters are
    safe because launchers pass each quoted flag as one argv element. An empty
    key reaches `System.setProperty("", ...)`, which throws
    `IllegalArgumentException` from inside the augmentation action. Both
    unrepresentable cases fail closed here instead.

    Args:
      build_properties: Declared string-dict to validate.

    Returns:
      An error message, or the empty string when every key is representable.
    """
    for key in sorted(build_properties):
        if not key:
            return "build_properties contains an empty key; declared build-time configuration names must be non-empty"
        if "=" in key:
            return "build_properties key '{}' contains '='; declared names must not contain '=' because JVM -D flags split on the first '='".format(key)
    return ""

def validate_build_property_keys(build_properties):
    """Fails the analysis phase on declared keys the lifecycles cannot share.

    Args:
      build_properties: Declared string-dict to validate.
    """
    error = _build_property_key_error(build_properties)
    if error:
        fail(error)

def _serialize_build_properties(build_properties):
    """Returns the deterministic UTF-8 content written for an action input."""
    lines = [
        "{}={}".format(_escape_property(key), _escape_property(build_properties[key]))
        for key in sorted(build_properties)
    ]
    return "\n".join(lines) + ("\n" if lines else "")

def write_build_properties(ctx, build_properties):
    """Writes declared build properties in a deterministic properties file.

    Args:
      ctx: Rule context used to declare and write the output.
      build_properties: Declared string-dict to serialize.

    Returns:
      The declared UTF-8 `.properties` file.
    """
    validate_build_property_keys(build_properties)
    output = ctx.actions.declare_file(ctx.label.name + ".build.properties")
    ctx.actions.write(
        output = output,
        content = _serialize_build_properties(build_properties),
    )
    return output

# Exported for Starlark unit tests.
escape_property_for_test = _escape_property
build_property_key_error_for_test = _build_property_key_error
serialize_build_properties_for_test = _serialize_build_properties

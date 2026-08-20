"""Hermetic serialization of declared Quarkus build configuration."""

def _escape_property(value):
    """Escapes a string for Java's UTF-8 `.properties` reader."""
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f").replace(" ", "\\ ").replace("=", "\\=").replace(":", "\\:").replace("#", "\\#").replace("!", "\\!")

def write_build_properties(ctx):
    """Writes declared build properties in a deterministic properties file.

    Args:
      ctx: Rule context with a `build_properties` string-dict attribute.

    Returns:
      The declared UTF-8 `.properties` file.
    """
    output = ctx.actions.declare_file(ctx.label.name + ".build.properties")
    lines = [
        "{}={}".format(_escape_property(key), _escape_property(ctx.attr.build_properties[key]))
        for key in sorted(ctx.attr.build_properties)
    ]
    ctx.actions.write(
        output = output,
        content = "\n".join(lines) + ("\n" if lines else ""),
    )
    return output

# Exported for Starlark unit tests.
escape_property_for_test = _escape_property

"Test class and package selector helpers shared by quarkus_test and continuous testing."

def regex_escape_class_name(class_name):
    """Escapes a Java class or package name for use inside a regular expression.

    Args:
      class_name: Fully-qualified class or package name.

    Returns:
      The name with regex metacharacters valid in Java identifiers escaped.
    """
    return class_name.replace("\\", "\\\\").replace(".", "\\.").replace("$", "\\$")

"""Asserts that no runfiles path of a launcher is claimed by two different files.

A generated File's short_path omits its configuration. When one target's
runfiles hold the same jar built in two configurations (a `_dev` target with
`continuous_test` builds its dev graph and its TEST graph separately), both
copies claim one runfiles path and Bazel stages only one of them. The other is
then neither materialized under Build without the Bytes nor visible to the
launcher, although the application model still references it by exec path.
"""

def _unique_runfiles_test_impl(ctx):
    runfiles = ctx.attr.target[DefaultInfo].default_runfiles
    workspace = ctx.workspace_name
    claims = {}
    for f in runfiles.files.to_list():
        claims.setdefault(workspace + "/" + f.short_path, {})[f.path] = True
    for entry in runfiles.symlinks.to_list():
        claims.setdefault(workspace + "/" + entry.path, {})[entry.target_file.path] = True
    for entry in runfiles.root_symlinks.to_list():
        claims.setdefault(entry.path, {})[entry.target_file.path] = True

    conflicts = sorted([
        "{} <- {}".format(path, ", ".join(sorted(claims[path])))
        for path in claims
        if len(claims[path]) > 1
    ])

    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    if conflicts:
        content = "#!/usr/bin/env bash\ncat >&2 <<'EOF'\nFAIL: {} runfiles paths are claimed by more than one file:\n{}\nEOF\nexit 1\n".format(
            len(conflicts),
            "\n".join(conflicts),
        )
    else:
        content = "#!/usr/bin/env bash\necho 'PASS: {} runfiles paths, each claimed by one file'\n".format(len(claims))
    ctx.actions.write(output = script, content = content, is_executable = True)
    return [DefaultInfo(executable = script)]

unique_runfiles_test = rule(
    implementation = _unique_runfiles_test_impl,
    test = True,
    doc = "Fails when a runfiles path of `target` would stage only one of several files.",
    attrs = {
        "target": attr.label(
            mandatory = True,
            cfg = "target",
            executable = True,
            doc = "The launcher whose runfiles are checked.",
        ),
    },
)

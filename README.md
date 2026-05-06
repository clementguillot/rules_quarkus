# Bazel Rules for Quarkus

[![CI](https://github.com/clementguillot/rules_quarkus/actions/workflows/ci.yaml/badge.svg?branch=main&event=push)](https://github.com/clementguillot/rules_quarkus/actions/workflows/ci.yaml)
[![Release](https://img.shields.io/github/v/release/clementguillot/rules_quarkus?label=Release)](https://github.com/clementguillot/rules_quarkus/releases/latest)

Bazel rules for building and running [Quarkus](https://quarkus.io/) applications. Supports `quarkus_app` (production Fast-Jar), dev mode with hot-reload, and `quarkus_test` (@QuarkusTest execution).

## Installation

See install instructions on the [release page](https://github.com/clementguillot/rules_quarkus/releases).

Add to your `MODULE.bazel`:

```starlark
bazel_dep(name = "com_clementguillot_rules_quarkus", version = "<VERSION>")
```

## Usage

```starlark
load("@rules_quarkus_toolchains//:defs.bzl", "quarkus_app", "quarkus_test")

quarkus_app(
    name = "app",
    version = "1.0.0-SNAPSHOT",
    deps = [":lib"],
)

quarkus_test(
    name = "test",
    deps = [":test_lib"],
)
```

```bash
bazel run //:app       # Production mode
bazel run //:app_dev   # Dev mode (hot-reload + Dev UI)
bazel test //:test     # @QuarkusTest
```

See the [Getting Started guide](docs/getting-started.md) for full setup instructions.

## Supported Versions

- **Quarkus**: 3.27.x LTS, 3.33.x LTS
- **Bazel**: 7+, 8+, or 9+
- **Java**: 17+
- **Mode**: Bzlmod only (WORKSPACE not supported)

## Documentation

- [Getting Started](docs/getting-started.md)
- [Architecture](docs/architecture.md)
- [Developer Guide](docs/developer-guide.md)
- [Dev Mode](docs/dev-mode.md)
- [Quarkifier](docs/quarkifier.md)

## License

See [LICENSE](LICENSE).

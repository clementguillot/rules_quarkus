# E2E Smoke Tests

End-to-end smoke tests that validate the `rules_quarkus` API (`quarkus_app`, `quarkus_test`) from an external consumer's perspective using bzlmod.

## Running Tests

```bash
bazel test //...
```

All targets:
- `:lib` — Java library compilation
- `:app` — Quarkus Fast-Jar application (via `quarkus_app`)
- `:test` — @QuarkusTest execution (via `quarkus_test`)
- `:smoke_test` — Build validation (via `build_test`)

## Prerequisites

- Bazel 7+, 8+, or 9+
- Network access for maven dependency resolution (first run)

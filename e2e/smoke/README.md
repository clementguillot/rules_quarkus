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

## E2BIG regression tests (`//big`, TDD — currently failing)

`//big` builds the same app against ~6000 generated jars so that colon-joined
classpath strings exceed the kernel argv limits (macOS `ARG_MAX` = 1 MiB total;
Linux additionally caps a single argument at 128 KiB). One test per finding in
`findings/`:

- `//big:augmentation_e2big_test` — findings/03: `--local-app-jars` inline on the augmentation action argv
- `//big:dev_e2big_test` — findings/01: dev launcher passes classpaths as raw argv
- `//big:test_e2big_test` — findings/02: test launcher phase 2 `java -cp <huge>`
- `//big:native_e2big_test` — findings/04: native-image argv re-expansion.
  Tagged `manual` (downloads GraalVM, runs a real native compile once fixed):
  `bazel test //big:native_e2big_test`

These are intentionally red until the corresponding fixes land.

## Prerequisites

- Bazel 7+, 8+, or 9+
- Network access for maven dependency resolution (first run)

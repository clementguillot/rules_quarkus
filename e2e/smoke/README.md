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

## E2BIG regression tests (`//big`)

`//big` builds the same app against ~6000 generated jars so that colon-joined
classpath strings exceed the kernel argv limits (macOS `ARG_MAX` = 1 MiB total;
Linux additionally caps a single argument at 128 KiB). They guard the
file/argfile-based classpath handling in the rules and launchers:

- `//big:augmentation_e2big_test`: `--local-app-jars` on the augmentation action
- `//big:dev_e2big_test`: dev launcher classpath handling
- `//big:test_e2big_test`: test launcher phase 1/2 classpath handling
- `//big:native_e2big_test`: native-image args-file expansion.
  Tagged `manual` (downloads GraalVM, runs a real native compile):
  `bazel test //big:native_e2big_test`

All non-manual tests pass since the E2BIG fix; a regression here means a
classpath went back onto a raw command line.

## Prerequisites

- Bazel 7+, 8+, or 9+
- Network access for maven dependency resolution (first run)

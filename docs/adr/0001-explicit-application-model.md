# ADR 0001: Explicit Bazel-owned Quarkus ApplicationModel

- Status: Accepted
- Date: 2026-07-18
- Reference implementation: Quarkus 3.33.2

## Context

Quarkus augmentation consumes an `ApplicationModel`, not merely a Java
classpath. The model contains the resolved runtime and deployment graphs,
dependency flags, extension metadata, platform imports, workspace modules,
source sets, and class-loading policy.

The original `rules_quarkus` implementation inferred that model from flattened
jar lists, embedded Maven metadata, and file names. Once Bazel analysis had
flattened the classpath, direct edges, workspace ownership, scopes, and the
runtime/deployment relationship could not be recovered reliably. Repairing
missing information by attaching orphan artifacts to the application root made
the model look complete while changing its meaning.

`ApplicationModel` fidelity is a release blocker because these facts affect
augmentation, Dev UI, dev-mode reload, tests, native sources, and extension
activation.

## Decision

`rules_quarkus` owns an explicit, versioned dependency and workspace graph.
Starlark records Bazel facts in `quarkus-bazel-model-v1`; a version-specific
Java adapter translates that contract to the Quarkus API.

The ownership boundary is:

| Fact | Authority |
|---|---|
| Local targets, direct edges, source/resource inputs, outputs, build files | Bazel aspect |
| External artifact identity, selected version, and physical target | `maven_install.json` v3 lock |
| Maven-faithful runtime membership and optionality | pinned Coursier resolution intersected with the lock |
| Deployment roots | exact `deployment-artifact` values from extension descriptors |
| Deployment closure | pinned Coursier JSON graph |
| Quarkus flags, platforms, capabilities, and descriptor semantics | version-specific Java adapter |

The runtime catalog is deliberately hybrid:

1. the rules_jvm_external lock is authoritative for selected versions, Bazel
   target names, artifact files, and explicit exclusions;
2. Coursier supplies Maven graph semantics that the v3 lock flattens or loses;
3. Coursier artifacts absent from the lock are discarded, so resolution cannot
   undo an explicit Bazel exclusion;
4. Bazel relationships between two artifacts already retained by resolver
   reachability remain available, preserving path contexts that a flattened
   Coursier report can merge away;
5. no artifact is admitted by a name convention or orphan-repair step.

All normal, dev, test, and native-sources entry points assemble the same
contract with a lifecycle mode. Internal target suffixes such as `_dev` and
`_native` do not change the application identity.

## Public API and DX invariant

This is an internal rewrite. Existing application declarations remain valid:

```starlark
java_library(
    name = "lib",
    deps = ["@maven//:io_quarkus_quarkus_rest"],
)

quarkus_app(
    name = "app",
    deps = [":lib"],
)
```

Application developers continue to declare only runtime extensions. They do
not declare `-deployment` artifacts, model files, aspects, providers, or test
launcher infrastructure. `quarkus_test` supplies its JUnit/Hamcrest launcher
dependencies through a private model channel so they do not become application
dependencies.

No new required attribute is added to `quarkus_app`, `quarkus_test`, or
`quarkus_extension_runtime` for model fidelity.

## Validation and failure policy

The explicit transport is strict:

- unknown schema fields, missing coordinates, duplicate identities, dangling
  edges, absent descriptor-declared deployment artifacts, and unresolved action
  inputs fail with an actionable error;
- the explicit path does not parse embedded POMs, guess `-deployment` names, or
  adopt orphans;
- serialization is deterministic and contains declared action paths only;
- post-curation Maven, Gradle, and Bazel models are compared after canonical
  path, coordinate-reference, ordering, and transient-flag normalization;
- intentional differences use exact-value SHA-256 allowlists, so a value change
  makes the approval stale.

The certified matrix covers Quarkus 3.33.2 and 3.27.4 adapters and Bazel 7.7.1,
8.x, and 9.x. Full lifecycle coverage includes normal augmentation, dev-mode
boot, test bootstrap, and native-sources augmentation.

## Consequences

Positive consequences:

- Dev UI and Quarkus receive real parent/child dependency relationships.
- Local applications and extensions carry source, resource, output, and build
  metadata without Maven-directory guessing.
- Runtime/deployment flags and top-level extension injection derive from graph
  traversal rather than root attachment heuristics.
- The same Bazel-facing contract can support later Quarkus adapters without a
  Starlark schema rewrite.

Costs and bounded differences:

- repository setup performs a pinned Coursier resolution in addition to
  rules_jvm_external lock loading;
- rules_jvm_external v3 does not retain every Maven edge scope, so the adapter
  uses an explicit conservative compile fallback where no Bazel fact exists;
- a dependency edge removed by the pinned Bazel graph is not recreated merely
  because Maven would have retained it in a different resolution context;
- Maven and Bazel workspace directories and generated-output layouts remain
  build-system-specific and are reviewed as exact conformance differences.

Generic conditional and conditional-dev dependency activation runs as a
mode-aware graph fixpoint during model assembly. Descriptor catalogs retain the
exact runtime/deployment coordinates, dependency conditions, and candidate
closures. Activation can therefore add nested runtime and deployment nodes
without adapter-side mutation, artifact-name guesses, or a public application
dependency requirement.

## Rejected alternatives

- Continue extending embedded-POM and jar-name inference: the lost Bazel graph
  cannot be reconstructed after flattening.
- Serialize Quarkus's internal map representation from Starlark: it is
  version-coupled and would mix Bazel fact collection with Quarkus semantics.
- Invoke Maven Resolver in normal Bazel actions: this would make actions
  non-hermetic and duplicate dependency selection after analysis.
- Require application authors to add deployment artifacts or model metadata:
  this breaks the established Quarkus-like DX and is not source compatible.

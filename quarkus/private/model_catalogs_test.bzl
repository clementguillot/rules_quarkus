"Unit tests for external model-catalog normalization."

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//quarkus:extensions.bzl", "conditional_catalog_for_test", "coursier_artifact_for_test", "coursier_report_coordinate_for_test", "deployment_catalog_for_test", "dev_mode_artifacts_for_test", "jar_target_name_for_test", "maven_target_name_for_test", "runtime_catalog_for_test", "runtime_discovery_artifacts_for_test")

def _runtime_catalog_v3_test_impl(ctx):
    env = unittest.begin(ctx)
    lock = {
        "__INPUT_ARTIFACTS_HASH": {
            "m.group:multi:jar:runtime": 4,
            "platform:quarkus-bom": 3,
            "repositories": 1,
            "z.group:z-artifact": 2,
        },
        "artifacts": {
            "a.group:a-artifact:jar:tests": {"shasums": {"tests": "a"}, "version": "1.2.3"},
            "c.group:classified": {"shasums": {"classes": "c"}, "version": "3.0"},
            "m.group:multi": {"shasums": {"jar": "m", "runtime": "mr"}, "version": "4.0"},
            "z.group:z-artifact": {"shasums": {"jar": "z"}, "version": "9.8.7"},
        },
        "conflict_resolution": {
            "z.group:z-artifact:8.0": "z.group:z-artifact:9.8.7",
        },
        "dependencies": {
            "a.group:a-artifact:jar:tests": ["z.group:z-artifact"],
            "c.group:classified:jar:classes": [],
            "parent.group:parent": [
                "m.group:multi",
            ],
            "z.group:z-artifact": [],
        },
        "packages": {
            "m.group:multi": ["m.group:multi"],
            "m.group:multi:jar:runtime": ["m.group:multi:jar:runtime"],
        },
        "version": "3",
    }

    catalog = runtime_catalog_for_test(lock)

    asserts.equals(env, "quarkus-bazel-runtime-catalog-v1", catalog["schemaVersion"])
    asserts.equals(
        env,
        ["m.group:multi:jar:runtime", "z.group:z-artifact"],
        catalog["directArtifacts"],
    )
    asserts.equals(env, "a.group:a-artifact:jar:tests", catalog["nodes"][0]["coordinateKey"])
    asserts.equals(env, "a_group_a_artifact_tests", catalog["nodes"][0]["targetName"])
    asserts.equals(env, "tests", catalog["nodes"][0]["coordinates"]["classifier"])
    asserts.equals(env, "jar", catalog["nodes"][0]["coordinates"]["type"])
    asserts.equals(env, ["z.group:z-artifact"], catalog["nodes"][0]["dependencies"])
    asserts.equals(env, "c.group:classified:jar:classes", catalog["nodes"][1]["coordinateKey"])
    asserts.equals(env, "classes", catalog["nodes"][1]["coordinates"]["classifier"])
    asserts.equals(env, "m.group:multi", catalog["nodes"][2]["coordinateKey"])
    asserts.equals(env, "m.group:multi:jar:runtime", catalog["nodes"][3]["coordinateKey"])
    asserts.equals(env, "m_group_multi_runtime", catalog["nodes"][3]["targetName"])

    resolved = runtime_catalog_for_test(lock, {
        "conflict_resolution": {},
        "dependencies": [
            {
                "coord": "a.group:a-artifact:jar:tests:1.2.3",
                "directDependencies": [
                    "c.group:classified:jar:classes:3.0",
                    "excluded.group:excluded:1.0",
                ],
            },
            {
                "coord": "c.group:classified:jar:classes:3.0",
                "directDependencies": [],
                "exclusions": ["excluded.group:excluded"],
                "optional": True,
            },
            {
                "coord": "z.group:z-artifact:9.8.7",
                "directDependencies": [],
            },
            {
                "coord": "excluded.group:excluded:1.0",
                "directDependencies": [],
            },
        ],
        "version": "0.1.0",
    })
    asserts.equals(
        env,
        ["c.group:classified:jar:classes"],
        resolved["nodes"][0]["dependencies"],
    )
    asserts.equals(env, ["excluded.group:excluded"], resolved["nodes"][1]["exclusions"])
    asserts.true(env, resolved["nodes"][1]["optional"])
    return unittest.end(env)

runtime_catalog_v3_test = unittest.make(_runtime_catalog_v3_test_impl)

def _runtime_discovery_artifacts_test_impl(ctx):
    env = unittest.begin(ctx)
    lock = {
        "artifacts": {
            "a.group:a-artifact:jar:tests": {"shasums": {"tests": "a"}, "version": "1.2.3"},
            "c.group:classified": {"shasums": {"classes": "c"}, "version": "3.0"},
            "m.group:multi": {"shasums": {"jar": "m", "runtime": "mr", "sources": "ms"}, "version": "4.0"},
            "z.group:z-artifact": {"shasums": {"jar": "z"}, "version": "9.8.7"},
        },
        "dependencies": {
            "a.group:a-artifact:jar:tests": [],
            "c.group:classified:jar:classes": [],
            "parent.group:parent": [
                "m.group:multi",
                "m.group:multi:jar:runtime",
                "m.group:multi:jar:sources",
            ],
            "z.group:z-artifact": [],
        },
        "version": "3",
    }

    asserts.equals(
        env,
        [
            "a.group:a-artifact:1.2.3,classifier=tests",
            "c.group:classified:3.0,classifier=classes",
            "m.group:multi:4.0",
            "m.group:multi:4.0,classifier=runtime",
            "z.group:z-artifact:9.8.7",
        ],
        runtime_discovery_artifacts_for_test(lock),
    )
    return unittest.end(env)

runtime_discovery_artifacts_test = unittest.make(_runtime_discovery_artifacts_test_impl)

def _coursier_artifact_test_impl(ctx):
    env = unittest.begin(ctx)
    gav = coursier_artifact_for_test("io.quarkus:quarkus-rest-deployment:3.33.2")
    asserts.equals(env, "io.quarkus:quarkus-rest-deployment:3.33.2", gav.fetch)
    asserts.equals(env, "io.quarkus:quarkus-rest-deployment:3.33.2", gav.report)

    classified = coursier_artifact_for_test("custom.group:build-steps:special:jar:9.1")
    asserts.equals(env, "custom.group:build-steps:9.1,classifier=special", classified.fetch)
    asserts.equals(env, "custom.group:build-steps:jar:special:9.1", classified.report)

    canonical_jar = coursier_artifact_for_test("custom.group:build-steps::jar:9.1")
    asserts.equals(env, "custom.group:build-steps:9.1", canonical_jar.fetch)
    asserts.equals(env, "custom.group:build-steps:9.1", canonical_jar.report)
    return unittest.end(env)

coursier_artifact_test = unittest.make(_coursier_artifact_test_impl)

def _dev_mode_artifacts_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        [
            "io.quarkus:quarkus-bootstrap-gradle-resolver:3.33.2",
            "io.quarkus:quarkus-bootstrap-maven-resolver:3.33.2",
            "io.quarkus:quarkus-core-deployment:3.33.2",
        ],
        dev_mode_artifacts_for_test("3.33.2"),
    )
    return unittest.end(env)

dev_mode_artifacts_test = unittest.make(_dev_mode_artifacts_test_impl)

def _maven_target_name_test_impl(ctx):
    env = unittest.begin(ctx)
    asserts.equals(
        env,
        "com_example_my_artifact_tests",
        maven_target_name_for_test("com.example:my-artifact:jar:tests"),
    )
    asserts.equals(env, "g_a_special", maven_target_name_for_test("g:a$special"))
    asserts.equals(
        env,
        "org_jacoco_org_jacoco_agent_0_8_14",
        jar_target_name_for_test(
            "org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14.jar",
        ),
    )
    asserts.equals(
        env,
        "org_jacoco_org_jacoco_agent_0_8_14_runtime",
        jar_target_name_for_test(
            "org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14-runtime.jar",
        ),
    )
    return unittest.end(env)

maven_target_name_test = unittest.make(_maven_target_name_test_impl)

def _deployment_catalog_test_impl(ctx):
    env = unittest.begin(ctx)
    cache_path = "/machine/cache/maven2/g/a/1.0/a-1.0.jar"
    report = {
        "conflict_resolution": {"g:a:0.9": "g:a:1.0"},
        "dependencies": [
            {
                "coord": "g:a:1.0",
                "directDependencies": ["g:b:2.0"],
                "exclusions": ["x:one"],
                "file": cache_path,
            },
            {
                "coord": "g:a:1.0",
                "directDependencies": ["g:c:3.0"],
                "exclusions": ["x:two"],
                "file": cache_path,
            },
        ],
        "version": "0.1.0",
    }

    catalog = deployment_catalog_for_test(
        report,
        ["g:a:1.0"],
        ["g:missing:1.0"],
        {cache_path: "deployment/jars/g/a/1.0/a-1.0.jar"},
    )

    asserts.equals(env, "quarkus-bazel-deployment-catalog-v1", catalog["schemaVersion"])
    asserts.equals(env, ["g:b:2.0", "g:c:3.0"], catalog["nodes"][0]["dependencies"])
    asserts.equals(env, ["x:one", "x:two"], catalog["nodes"][0]["exclusions"])
    asserts.equals(env, "deployment/jars/g/a/1.0/a-1.0.jar", catalog["nodes"][0]["repoPath"])
    asserts.equals(env, ["g:missing:1.0"], catalog["droppedRoots"])
    return unittest.end(env)

deployment_catalog_test = unittest.make(_deployment_catalog_test_impl)

def _conditional_catalog_test_impl(ctx):
    env = unittest.begin(ctx)
    cache_path = "/machine/cache/maven2/g/feature/1.0/feature-1.0-tests.jar"
    resolution = struct(
        descriptors = [{
            "conditionalDependencies": ["g:feature:tests:jar:1.0"],
            "conditionalDevDependencies": [],
            "dependencyConditions": ["g:trigger"],
            "deploymentArtifact": "g:base-deployment:1.0",
            "runtimeArtifact": "g:base:1.0",
        }],
        report = {
            "conflict_resolution": {},
            "dependencies": [{
                "coord": "g:feature:jar:tests:1.0",
                "directDependencies": [],
                "exclusions": [],
                "file": cache_path,
            }],
            "version": "0.1.0",
        },
        roots = {"g:feature:jar:tests:1.0": "g:feature:tests:jar:1.0"},
    )

    catalog = conditional_catalog_for_test(
        resolution,
        {cache_path: "conditional/jars/g/feature/1.0/feature-1.0-tests.jar"},
    )

    asserts.equals(env, "quarkus-bazel-conditional-catalog-v1", catalog["schemaVersion"])
    asserts.equals(env, "g:feature:tests:jar:1.0", catalog["nodes"][0]["coordinate"])
    asserts.equals(env, ["g:feature:tests:jar:1.0"], catalog["roots"])
    asserts.equals(env, "g:a:classifier:zip:1", coursier_report_coordinate_for_test("g:a:zip:classifier:1"))
    return unittest.end(env)

conditional_catalog_test = unittest.make(_conditional_catalog_test_impl)

def model_catalogs_test_suite():
    unittest.suite(
        "model_catalogs_tests",
        runtime_catalog_v3_test,
        runtime_discovery_artifacts_test,
        coursier_artifact_test,
        dev_mode_artifacts_test,
        maven_target_name_test,
        deployment_catalog_test,
        conditional_catalog_test,
    )

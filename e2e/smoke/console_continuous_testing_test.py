"""Exercise Quarkus's console-only continuous testing through a disposable Bazel workspace."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

from continuous_testing_test import eventually, prepare, stop_process


def prepare_console(workspace):
    prepare(workspace)
    invalid_properties = workspace / "invalid_properties"
    invalid_properties.mkdir(exist_ok=True)
    (invalid_properties / "BUILD.bazel").write_text(
        'load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app", "quarkus_test")\n'
        'quarkus_test(name="one", srcs=[], deps=["//dep:value"], '
        'test_classes=["fixture.FlatTest"], '
        'build_properties={"fixture.property": "one"})\n'
        'quarkus_test(name="two", srcs=[], deps=["//dep:value"], '
        'test_classes=["fixture.FlatTest"], '
        'build_properties={"fixture.property": "two"})\n'
        'quarkus_app(name="app", deps=["//dep:value"], '
        'continuous_test=[":one", ":two"])\n'
    )
    for package, arguments in {
        "invalid_no_consumer": 'continuous_test="//:test", dev=False, test=False',
        "invalid_test_build_args": 'test_build_args=["--define=unused=true"]',
    }.items():
        (workspace / package).mkdir(exist_ok=True)
        (workspace / package / "BUILD.bazel").write_text(
            'load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app")\n'
            f'quarkus_app(name="app", deps=["//dep:value"], {arguments})\n'
        )


def summaries(log_path):
    return [line for line in log_path.read_text(errors="replace").splitlines()
            if re.search(r"\btests? (?:were|was) run\b", line)]


def certify(workspace, log_path):
    bazel = shutil.which("bazel") or shutil.which("bazelisk")
    assert bazel, "Bazel must be on PATH for the real rebuild test"
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("TEST_", "RUNFILES_")) and key != "BUILD_WORKSPACE_DIRECTORY"}
    environment.update(QUARKUS_HTTP_PORT="0", QUARKUS_HTTP_TEST_PORT="0", QUARKUS_CONSOLE_COLOR="false")

    with tempfile.TemporaryDirectory(prefix="quarkus-continuous-runtime-") as runtime, log_path.open("w") as log:
        environment["TMPDIR"] = runtime
        def build(*targets):
            subprocess.run([bazel, "build", *targets], cwd=workspace, env=environment,
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
            log.flush()

        build("//:positional_test", "//:without_tests", "//:module_tests_only_test",
              "//:without_dev_app_test", "//:without_console_app_dev", "//:app_dev", "//:app_test")
        test_launcher = (workspace / "bazel-bin/app_test_launch.sh").read_text()
        dev_launcher = (workspace / "bazel-bin/app_dev_launch.sh").read_text()
        assert "--define=test_fixture=true" in test_launcher
        assert "--define=continuous_fixture=true" not in test_launcher
        assert "--define=continuous_fixture=true" in dev_launcher
        assert "--define=test_fixture=true" not in dev_launcher
        watched = (workspace / "bazel-bin/module_tests_only_test_watched_build_files.txt").read_text().split()
        assert "BUILD.bazel" in watched and "moduletests/BUILD.bazel" in watched, watched
        missing = subprocess.run([bazel, "query", "//:without_tests_test"], cwd=workspace,
                                 env=environment, capture_output=True, text=True, timeout=60)
        assert missing.returncode != 0, "continuous testing target appeared without continuous_test"
        opted_out = subprocess.run([bazel, "query", "//:without_console_app_test"], cwd=workspace,
                                   env=environment, capture_output=True, text=True, timeout=60)
        assert opted_out.returncode != 0, "continuous testing target appeared with test = False"
        for target, message in [
            ("//invalid_no_consumer:app", "continuous_test requires dev = True or test = True"),
            ("//invalid_test_build_args:app", "test_build_args only applies to the app_test target"),
        ]:
            rejected = subprocess.run([bazel, "build", target], cwd=workspace, env=environment,
                                      capture_output=True, text=True, timeout=600)
            assert rejected.returncode != 0, target
            assert message in rejected.stdout + rejected.stderr, rejected.stdout + rejected.stderr
        invalid = subprocess.run([bazel, "build", "//invalid_selection:app_test"], cwd=workspace,
                                 env=environment, capture_output=True, text=True, timeout=600)
        assert invalid.returncode != 0
        assert "one continuous-test session applies a single test selection" in invalid.stdout + invalid.stderr
        conflicting = subprocess.run([bazel, "build", "//invalid_properties:app_test"],
                                     cwd=workspace, env=environment, capture_output=True,
                                     text=True, timeout=600)
        assert conflicting.returncode != 0
        assert "conflicting build_properties value" in conflicting.stdout + conflicting.stderr, (
            conflicting.stdout + conflicting.stderr)
        subprocess.run([bazel, "test", "//submodule:test", "--test_output=errors"],
                       cwd=workspace, env=environment, stdout=log, stderr=subprocess.STDOUT,
                       check=True, timeout=600)
        log.flush()

        command = [bazel, "run", "--define=test_fixture=true", "//:app_test"]
        process = subprocess.Popen(command, cwd=workspace, env=environment, stdout=log,
                                   stderr=subprocess.STDOUT, stdin=subprocess.PIPE, start_new_session=True)
        try:
            def send(key):
                assert process.stdin is not None
                process.stdin.write(key.encode("ascii"))
                process.stdin.flush()

            def run_after(previous, failed=False, count=None):
                if process.poll() is not None:
                    raise RuntimeError("console continuous-test process exited")
                runs = summaries(log_path)
                if len(runs) <= previous:
                    return None
                latest = runs[-1]
                if count is not None:
                    assert re.search(rf"\b{count} tests? (?:were|was) run\b", latest), latest
                if failed:
                    assert "failed" in latest.lower(), latest
                else:
                    assert "passing" in latest.lower() or "passed" in latest.lower(), latest
                return len(runs)

            eventually(lambda: "Quarkus continuous testing mode started" in log_path.read_text(), 600)
            # Quarkus' test-only mode starts testing on startup, independent of
            # quarkus.test.continuous-testing. Subsequent runs use the console and the same
            # watcher as the application dev target.
            run = eventually(lambda: run_after(0, count=3), 180)
            assert "Profile dev activated" not in log_path.read_text()
            assert "Dev UI" not in log_path.read_text()
            send("r")
            run = eventually(lambda: run_after(run, count=3))
            print("PASS: test-only startup, console rerun, and no dev application", flush=True)

            source = workspace / "dep/Value.java"
            original = source.read_text()
            paused = log_path.read_text().count("Tests paused")
            send("p")
            eventually(lambda: log_path.read_text().count("Tests paused") > paused)
            source.write_text(original.replace("value-v1", "value-v2"))
            time.sleep(5)
            assert len(summaries(log_path)) == run, "paused tests reran after a source edit"
            send("r")
            run = eventually(lambda: run_after(run, failed=True))
            source.write_text(original)
            run = eventually(lambda: run_after(run))

            def edit(relative, before, after, failed=False):
                nonlocal run
                path = workspace / relative
                contents = path.read_text()
                assert before in contents, relative
                path.write_text(contents.replace(before, after))
                run = eventually(lambda: run_after(run, failed=failed))
                print(f"PASS: {relative} changed; failed={failed}", flush=True)

            for relative, old, new in [
                ("helper/Helper.java", "helper-v1", "helper-v2"),
                ("src/main/hello/main.hello", "main-v1", "main-v2"),
                ("src/test/hello/test.hello", "test-v1", "test-v2"),
                ("src/test/resources/input.txt", "resource-v1", "resource-v2"),
                ("submodule/SubmoduleService.java", "module-v1", "module-v2"),
                ("tests/FlatTest.java", '"value-v1/main-v1"', '"deliberate-failure"'),
            ]:
                edit(relative, old, new, failed=True)
                edit(relative, new, old)

            new_test = workspace / "submodule/src/test/java/submodule/AddedSubmoduleTest.java"
            new_test.write_text(
                "package submodule;\n"
                "import static org.junit.jupiter.api.Assertions.assertNotNull;\n"
                "import io.quarkus.test.junit.QuarkusTest;\n"
                "import jakarta.inject.Inject;\n"
                "import org.junit.jupiter.api.Test;\n"
                "@QuarkusTest class AddedSubmoduleTest {\n"
                "  @Inject SubmoduleService service;\n"
                "  @Test void serviceIsInjected() { assertNotNull(service); }\n"
                "}\n"
            )
            run = eventually(lambda: run_after(run))
            assert "AddedSubmoduleTest" in log_path.read_text()
            new_test.unlink()
            run = eventually(lambda: run_after(run))

            source.write_text(original + "\nnot valid java\n")
            build_log = lambda: "\n".join(
                p.read_text(errors="replace") for p in Path(runtime).glob("quarkus_dev_output_*/bazel-hot-reload.log"))
            eventually(lambda: "Build did NOT complete successfully" in build_log())
            time.sleep(3)
            assert len(summaries(log_path)) == run, "failed Bazel build published stale outputs"
            source.write_text(original.replace("value-v1", "value-v2"))
            run = eventually(lambda: run_after(run, failed=True))
            source.write_text(original)
            run = eventually(lambda: run_after(run))

            helper_build = workspace / "helper/BUILD.bazel"
            helper_build.write_text(helper_build.read_text() + "# restart-required smoke check\n")
            eventually(lambda: "Restart the continuous-test session so Bazel declarations" in log_path.read_text())
            time.sleep(3)
            assert len(summaries(log_path)) == run, "BUILD edit triggered a stale-model run"
            assert "Failed to create compiler" not in log_path.read_text()
            print("PASS: source and codegen edits, test discovery, failed-build recovery", flush=True)
        finally:
            stop_process(process)
            for build_log in Path(runtime).glob("quarkus_dev_output_*/bazel-hot-reload.log"):
                log.write("\nBazel rebuild log:\n" + build_log.read_text(errors="replace"))
            subprocess.run([bazel, "shutdown"], cwd=workspace, env=environment,
                           stdout=log, stderr=subprocess.STDOUT, timeout=60, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare-only", type=Path)
    parser.add_argument("--workspace", type=Path)
    args = parser.parse_args()
    if args.prepare_only:
        prepare_console(args.prepare_only)
    elif args.workspace:
        prepare_console(args.workspace)
        certify(args.workspace, args.workspace.parent / "continuous-testing.log")
    else:
        output = Path(os.environ.get("TEST_UNDECLARED_OUTPUTS_DIR", tempfile.gettempdir()))
        log_path = output / "continuous-testing.log"
        with tempfile.TemporaryDirectory(prefix="quarkus-continuous-", dir=os.environ.get("TEST_TMPDIR")) as directory:
            workspace = Path(directory)
            prepare_console(workspace)
            try:
                certify(workspace, log_path)
            except BaseException:
                print(log_path.read_text(errors="replace")[-30000:] if log_path.exists() else "No test log")
                raise

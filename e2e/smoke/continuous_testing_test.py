"""Real Dev UI continuous-testing regression, in a disposable external workspace.

Uses only Python's standard library. JSON-RPC is the same WebSocket interface
as the Dev UI buttons; no test-specific Quarkus endpoints or upstream patches.
"""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import time


class DevUI:
    def __init__(self, port):
        self.port = port
        self.sequence = 0

    def call(self, method):
        # Reconnect for every request: application reload can close old sockets.
        with socket.create_connection(("localhost", self.port), timeout=10) as sock:
            key = base64.b64encode(os.urandom(16)).decode()
            sock.sendall((f"GET /q/dev-ui/json-rpc-ws HTTP/1.1\r\nHost: localhost:{self.port}\r\n"
                          "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
            header = b""
            while not header.endswith(b"\r\n\r\n"):
                header += self.read(sock, 1)
            assert b" 101 " in header, header
            self.sequence += 1
            payload = json.dumps({"jsonrpc": "2.0", "id": self.sequence,
                                  "method": "devui-continuous-testing_" + method, "params": {}}).encode()
            mask = os.urandom(4)
            length = len(payload)
            frame = bytes([0x81, 0x80 | length]) if length < 126 else bytes([0x81, 0xfe]) + struct.pack("!H", length)
            sock.sendall(frame + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(payload)))
            message = b""
            while True:
                first, second = self.read(sock, 2)
                size = second & 127
                if size == 126:
                    size = struct.unpack("!H", self.read(sock, 2))[0]
                elif size == 127:
                    size = struct.unpack("!Q", self.read(sock, 8))[0]
                assert not second & 128, "server frames must be unmasked"
                chunk = self.read(sock, size)
                assert first & 15 != 8, "Dev UI closed WebSocket"
                if first & 15 in (0, 1):
                    message += chunk
                    if first & 128:
                        reply = json.loads(message)
                        message = b""
                        if reply.get("id") != self.sequence:
                            continue
                        assert "error" not in reply, reply
                        result = reply["result"]
                        return result.get("object", result) if isinstance(result, dict) else result

    @staticmethod
    def read(sock, size):
        data = b""
        while len(data) < size:
            part = sock.recv(size - len(data))
            if not part:
                raise ConnectionError("Dev UI disconnected")
            data += part
        return data


def prepare(workspace, version):
    smoke = Path(__file__).resolve().parent
    repository = smoke.parent.parent
    fixture = smoke / "continuous"
    workspace.mkdir(parents=True, exist_ok=True)
    module = (smoke / "MODULE.bazel").read_text().replace('path = "../.."', f'path = {json.dumps(str(repository))}')
    junit = "io_quarkus_quarkus_junit"
    lock = smoke / "maven_install.json"
    if version == "3.27.4":
        module = module.replace("3.33.2", version).replace("quarkus-junit:", "quarkus-junit5:")
        junit += "5"
        lock = fixture / "maven_install_3_27.json"
    shutil.copyfile(lock, workspace / "maven_install.json")
    (workspace / "MODULE.bazel").write_text(module)
    shutil.copyfile(smoke / ".bazelrc", workspace / ".bazelrc")
    shutil.copytree(smoke / "ext", workspace / "ext", dirs_exist_ok=True)
    (workspace / "BUILD.bazel").write_text((fixture / "BUILD.bazel.tpl").read_text().replace("JUNIT_LABEL", junit))
    main_source = workspace / "src/main/java/fixture/Main.java"
    main_source.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(fixture / "Main.java", main_source)
    for directory, source, name, testonly in [("dep", "Value", "value", False), ("helper", "Helper", "helper", True)]:
        package = workspace / directory
        package.mkdir(exist_ok=True)
        (package / "late-data/added.txt").unlink(missing_ok=True)
        shutil.copyfile(fixture / f"{source}.java", package / f"{source}.java")
        (package / "BUILD.bazel").write_text(
            'load("@rules_java//java:java_library.bzl", "java_library")\n'
            f'java_library(name="{name}", srcs=["{source}.java"], testonly={testonly}, '
            f'resources=glob(["late-data/**"], allow_empty=True), resource_strip_prefix="{directory}/late-data", '
            'visibility=["//visibility:public"])\n')
    (workspace / "tests").mkdir(exist_ok=True)
    for name in ("FlatTest", "SelectedTest", "ExcludedTest"):
        shutil.copyfile(fixture / f"{name}.java", workspace / "tests" / f"{name}.java")
    for name, value in {"src/main/hello/main.hello": "main-v1", "src/test/hello/test.hello": "test-v1",
                        "testdata/input.txt": "resource-v1", "src/test/resources/undeclared.txt": "unpackaged"}.items():
        file = workspace / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(value)


def eventually(check, timeout=180):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, AssertionError, KeyError) as error:
            last = error
        time.sleep(0.25)
    raise AssertionError(f"condition timed out: {last}")


def certify(workspace, log_path):
    bazel = shutil.which("bazel") or shutil.which("bazelisk")
    assert bazel, "Bazel must be on PATH for the real rebuild test"
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("TEST_", "RUNFILES_")) and key != "BUILD_WORKSPACE_DIRECTORY"}
    environment.update(QUARKUS_HTTP_PORT="0", QUARKUS_HTTP_TEST_PORT="0", QUARKUS_CONSOLE_COLOR="false")
    command = [bazel, "run", "--define=continuous_fixture=true", "//:app_dev"]
    with tempfile.TemporaryDirectory(prefix="quarkus-continuous-runtime-") as runtime, log_path.open("w") as log:
        environment["TMPDIR"] = runtime
        process = subprocess.Popen(command, cwd=workspace, env=environment, stdout=log,
                                   stderr=subprocess.STDOUT, stdin=subprocess.PIPE, start_new_session=True)
        try:
            def startup():
                if process.poll() is not None:
                    raise RuntimeError("dev process exited before startup")
                match = re.search(r"Listening on: http://localhost:(\d+)", log_path.read_text())
                return int(match.group(1)) if match else None
            ui = DevUI(eventually(startup, 600))
            subprocess.run([bazel, "build", "//:positional_test", "//:without_tests"],
                           cwd=workspace, env=environment, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=600)
            ui.call("start")
            def completed(after, failures):
                status = ui.call("getStatus")
                assert status.get("lastRun", 0) > after and status.get("running", -1) == -1 and status.get("testsFailed") == failures, status
                return status
            status = eventually(lambda: completed(0, 0))
            assert status["testsPassed"] == 2, status
            print("PASS: Dev UI activation, selectors, properties, JVM flags and initial resources", flush=True)

            def edit_and_wait(relative, before, after, failures):
                nonlocal status
                file = workspace / relative
                text = file.read_text()
                assert before in text
                file.write_text(text.replace(before, after))
                status = eventually(lambda: completed(status["lastRun"], failures))
                if failures:
                    results = ui.call("getResults")
                    assert "observesBazelOutputs" in json.dumps(results), results
                print(f"PASS: {relative} -> {after!r}, failures={failures}", flush=True)

            for relative, old, new in [("dep/Value.java", "value-v1", "value-v2"),
                                       ("helper/Helper.java", "helper-v1", "helper-v2"),
                                       ("src/main/hello/main.hello", "main-v1", "main-v2"),
                                       ("src/test/hello/test.hello", "test-v1", "test-v2"),
                                       ("testdata/input.txt", "resource-v1", "resource-v2"),
                                       ("tests/FlatTest.java", '"value-v1/main-v1"', '"deliberate-failure"')]:
                edit_and_wait(relative, old, new, 1)
                edit_and_wait(relative, new, old, 0)

            # A source edit rejected by Bazel must never be independently compiled by Quarkus.
            source = workspace / "dep/Value.java"
            original = source.read_text()
            source.write_text(original + "\nnot valid java\n")
            eventually(lambda: any("Build did NOT complete successfully" in file.read_text()
                                   for file in Path(runtime).glob("quarkus_dev_output_*/bazel-hot-reload.log")))
            time.sleep(3)
            assert ui.call("getStatus")["lastRun"] == status["lastRun"], "failed build published test changes"
            source.write_text(original.replace("value-v1", "value-v2"))
            status = eventually(lambda: completed(status["lastRun"], 1))
            edit_and_wait("dep/Value.java", "value-v2", "value-v1", 0)

            # The first resource in an initially empty glob, then modification and deletion.
            added = workspace / "helper/late-data/added.txt"
            added.parent.mkdir(exist_ok=True)
            added.write_text("added-v2")
            status = eventually(lambda: completed(status["lastRun"], 1))
            edit_and_wait("helper/late-data/added.txt", "added-v2", "added-v1", 0)
            edit_and_wait("helper/late-data/added.txt", "added-v1", "added-v2", 1)
            added.unlink()
            status = eventually(lambda: completed(status["lastRun"], 0))

            # Codegen with byte-for-byte identical Java output must still notify tests on Linux.
            edit_and_wait("src/test/hello/test.hello", "test-v1", "test-v1\n", 0)
            ui.call("stop")
            previous = status["lastRun"]
            source.write_text(original.replace("value-v1", "value-v2"))
            time.sleep(5)
            assert ui.call("getStatus")["lastRun"] == previous, "paused testing reran"
            ui.call("start")
            status = eventually(lambda: completed(previous, 1))
            edit_and_wait("dep/Value.java", "value-v2", "value-v1", 0)
            ui.call("runAll")
            status = eventually(lambda: completed(status["lastRun"], 0))
            previous = status["lastRun"]
            time.sleep(4)
            assert ui.call("getStatus")["lastRun"] == previous, "unchanged outputs caused repeated runs"
            assert "CONTINUOUS_OUTPUT" in json.dumps(ui.call("getResults")) or "CONTINUOUS_OUTPUT" in log_path.read_text()
            assert "Failed to create compiler" not in log_path.read_text()
            print("PASS: failed-build recovery, empty resource glob, no-bytecode codegen, pause/resume, manual rerun, stability", flush=True)
        finally:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            for build_log in Path(runtime).glob("quarkus_dev_output_*/bazel-hot-reload.log"):
                log.write("\nBazel rebuild log:\n" + build_log.read_text())
            subprocess.run([bazel, "shutdown"], cwd=workspace, env=environment,
                           stdout=log, stderr=subprocess.STDOUT, timeout=60, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("version", choices=["3.27.4", "3.33.2"])
    parser.add_argument("--prepare-only", type=Path)
    parser.add_argument("--workspace", type=Path, help="Reuse a disposable workspace while debugging this test")
    args = parser.parse_args()
    if args.prepare_only:
        prepare(args.prepare_only, args.version)
    elif args.workspace:
        prepare(args.workspace, args.version)
        certify(args.workspace, args.workspace.parent / f"continuous-testing-{args.version}.log")
    else:
        output = Path(os.environ.get("TEST_UNDECLARED_OUTPUTS_DIR", tempfile.gettempdir()))
        log_path = output / f"continuous-testing-{args.version}.log"
        with tempfile.TemporaryDirectory(prefix="quarkus-continuous-", dir=os.environ.get("TEST_TMPDIR")) as directory:
            workspace = Path(directory)
            prepare(workspace, args.version)
            try:
                certify(workspace, log_path)
            except BaseException:
                print(log_path.read_text()[-30000:] if log_path.exists() else "No dev log")
                raise

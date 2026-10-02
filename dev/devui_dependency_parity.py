#!/usr/bin/env python3
"""Compares the Dev UI dependency graph of a Maven and a Bazel dev-mode run.

Quarkus builds the Dev UI "Dependencies" page from the post-curation
ApplicationModel: one node per resolved dependency (plus the application
root) and one link per ResolvedDependency.getDependencies() entry, typed
"runtime" or "deployment" by the source's runtime-classpath flag. Running the
same application under `mvn quarkus:dev` and `bazel run :<app>_dev` and
diffing that data is therefore a node-by-node, edge-by-edge parity oracle for
the Bazel-owned model.

The application root is renamed to "<app>" on both sides because Maven uses
the POM coordinates while Bazel uses its workspace identity.

Usage:
  devui_dependency_parity.py capture --url http://localhost:8080 --out maven.json
  devui_dependency_parity.py diff maven.json bazel.json [--allowlist FILE]
  devui_dependency_parity.py run --workspace examples/helloworld_3_40 \
      --bazel-target //:helloworld_dev [--maven-args "-pl app -am"] [--out-dir DIR]

`run` starts both dev modes sequentially on free ports, captures each graph,
stops the processes and prints the diff. It exits non-zero when a difference
is not covered by the allowlist. An allowlist is a JSON object
{"nodes": {"<sha256>": "reason"}, "links": {"<sha256>": "reason"}} whose keys
are the SHA-256 of the exact difference line, so any value change makes an
entry stale.
"""

import argparse
import hashlib
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request

APP = "<app>"


def _exports(text):
    # Values are JSON literals; Quarkus 3.33+ writes one per line, while older
    # lines pretty-print them across several lines.
    values = {}
    decoder = json.JSONDecoder()
    for name in ("root", "allGavs"):
        marker = "export const {} = ".format(name)
        start = text.find(marker)
        if start >= 0:
            values[name], _ = decoder.raw_decode(text, start + len(marker))
    if "root" not in values:
        raise ValueError("devui-data.js has no 'root' export; is the Dependencies page enabled?")
    return values


def normalize(devui_data):
    """Returns the canonical graph: sorted node ids and links with the root renamed."""
    exports = _exports(devui_data)
    root = exports["root"]
    root_id = root["rootId"]

    def name(coords):
        return APP if coords == root_id else coords

    nodes = sorted({name(node["id"]) for node in root["nodes"]})
    links = sorted(
        {
            (name(link["source"]), name(link["target"]), link["type"], bool(link["direct"]))
            for link in root["links"]
        }
    )
    return {"rootId": root_id, "nodes": nodes, "links": [list(link) for link in links]}


def capture(url, timeout=1):
    with urllib.request.urlopen(url.rstrip("/") + "/q/dev-ui/devui-data.js", timeout=timeout) as response:
        return normalize(response.read().decode("utf-8"))


def _line(kind, side, value):
    if kind == "node":
        return "node only in {}: {}".format(side, value)
    source, target, link_type, direct = value
    return "link only in {}: {} -> {} [{}{}]".format(
        side, source, target, link_type, ", direct" if direct else ""
    )


def diff(reference, candidate, allowlist=None):
    """Returns (unexplained, allowlisted) difference lines; reference is Maven."""
    allowlist = allowlist or {"nodes": {}, "links": {}}
    unexplained, allowlisted = [], []
    for kind, key in (("node", "nodes"), ("link", "links")):
        ref = {tuple(v) if isinstance(v, list) else v for v in reference[key]}
        cand = {tuple(v) if isinstance(v, list) else v for v in candidate[key]}
        for side, values in (("maven", ref - cand), ("bazel", cand - ref)):
            for value in sorted(values):
                line = _line(kind, side, value)
                digest = hashlib.sha256(line.encode("utf-8")).hexdigest()
                reason = allowlist.get(key, {}).get(digest)
                (allowlisted if reason else unexplained).append(
                    (line, digest, reason) if reason else (line, digest)
                )
    return unexplained, allowlisted


def _free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _run_and_capture(command, cwd, env, port, log_path, startup_timeout):
    url = "http://localhost:{}".format(port)
    with open(log_path, "w") as log:
        process = subprocess.Popen(
            command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True
        )
    try:
        deadline = time.monotonic() + startup_timeout
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("{} exited early; see {}".format(command[0], log_path))
            try:
                return capture(url)
            except (OSError, ValueError):
                time.sleep(1)
        raise RuntimeError("timed out waiting for the Dev UI of {}; see {}".format(command[0], log_path))
    finally:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=30)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass


def _report(unexplained, allowlisted, reference, candidate):
    print("maven: {} nodes, {} links".format(len(reference["nodes"]), len(reference["links"])))
    print("bazel: {} nodes, {} links".format(len(candidate["nodes"]), len(candidate["links"])))
    for line, digest, reason in allowlisted:
        print("ALLOWED {} ({}) {}".format(line, reason, digest[:12]))
    for line, digest in unexplained:
        print("DIFF    {} {}".format(line, digest))
    print("{} unexplained, {} allowlisted".format(len(unexplained), len(allowlisted)))
    return 1 if unexplained else 0


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    cap = sub.add_parser("capture")
    cap.add_argument("--url", required=True)
    cap.add_argument("--out", required=True)

    dif = sub.add_parser("diff")
    dif.add_argument("reference")
    dif.add_argument("candidate")
    dif.add_argument("--allowlist")

    run = sub.add_parser("run")
    run.add_argument("--workspace", required=True)
    run.add_argument("--bazel-target", required=True)
    run.add_argument("--maven-args", default="")
    run.add_argument("--out-dir", default=".")
    run.add_argument("--allowlist")
    run.add_argument("--startup-timeout", type=int, default=900)

    args = parser.parse_args(argv)
    if args.command == "capture":
        graph = capture(args.url, timeout=30)
        with open(args.out, "w") as out:
            json.dump(graph, out, indent=1)
        return 0

    allowlist = None
    if getattr(args, "allowlist", None):
        with open(args.allowlist) as handle:
            allowlist = json.load(handle)

    if args.command == "diff":
        with open(args.reference) as ref, open(args.candidate) as cand:
            reference, candidate = json.load(ref), json.load(cand)
    else:
        workspace = os.path.abspath(args.workspace)
        out_dir = os.path.abspath(args.out_dir)
        os.makedirs(out_dir, exist_ok=True)
        maven_port, bazel_port = _free_port(), _free_port()
        maven = ["./mvnw", "-B", "quarkus:dev"] + args.maven_args.split() + [
            "-Dquarkus.http.port={}".format(maven_port),
            "-Ddebug=false",
            "-Dquarkus.console.enabled=false",
        ]
        reference = _run_and_capture(
            maven, workspace, dict(os.environ), maven_port, os.path.join(out_dir, "maven-dev.log"),
            args.startup_timeout,
        )
        bazel_env = dict(os.environ, QUARKUS_HTTP_PORT=str(bazel_port))
        candidate = _run_and_capture(
            ["bazel", "run", args.bazel_target], workspace, bazel_env, bazel_port,
            os.path.join(out_dir, "bazel-dev.log"), args.startup_timeout,
        )
        for side, graph in (("maven", reference), ("bazel", candidate)):
            with open(os.path.join(out_dir, side + "-graph.json"), "w") as out:
                json.dump(graph, out, indent=1)

    unexplained, allowlisted = diff(reference, candidate, allowlist)
    return _report(unexplained, allowlisted, reference, candidate)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

# SPDX-License-Identifier: Apache-2.0
"""Shared constants and Docker/CLI helpers for the devnet scripts."""
import json
import os
import pathlib
import subprocess


REPO = pathlib.Path(__file__).resolve().parents[2]
USER = f"{os.getuid()}:{os.getgid()}"  # containers run as the invoking user so mounted run directories stay writable
ENTRY = REPO / "scripts" / "devnet.py"  # re-invoked by the scenarios as `devnet.py --name N load ...`
REFERENCE_IMAGE = "cardano-reference-11.1.3:local"
JDK_IMAGE = "cardano-scala-jdk:local"
MAGIC = 1082026
ENV = "/work/env"
QUERY_PORT = 7071


def data_root(name):
    return pathlib.Path(os.environ.get("CSL_DATA_DIR", "/tmp/cardano-scala-lab")) / name


def docker(*args, check=True, capture=True, timeout=None):
    result = subprocess.run(["docker", *args], text=True, capture_output=capture, timeout=timeout)
    if check and result.returncode != 0:
        raise SystemExit(f"docker {' '.join(args[:3])} failed: {result.stderr.strip()}")
    return result


def ref(name):
    return f"{name}-ref"


def in_ref(name, script, timeout=60):
    return docker("exec", ref(name), "/bin/sh", "-c", script, timeout=timeout)


def query(name, kind, node=1):
    out = in_ref(name, f"cardano-cli conway query {kind} --testnet-magic {MAGIC} "
                       f"--socket-path {ENV}/socket/node{node}/sock").stdout
    return json.loads(out)


TX = "/work/tx"


def put_file(name, path, text):
    r = subprocess.run(["docker", "exec", "-i", ref(name), "/bin/sh", "-c", f"cat > {path}"],
                       input=text, text=True, capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"cannot write {path}: {r.stderr.strip()}")


def read_json(name, path):
    return json.loads(in_ref(name, f"cat {path}").stdout)


CLI = "cardano-cli conway"
SOCKET = f"--testnet-magic {MAGIC} --socket-path {ENV}/socket/node1/sock"
LOAD = "/work/load"


# ---------------------------------------------------------------- era fixtures and e2e

ERAS = ["byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
TAG_ERA = {0: "byron", 1: "byron", 2: "shelley", 3: "allegra", 4: "mary", 5: "alonzo", 6: "babbage", 7: "conway"}


def read_log(path):
    """(events, other lines) of the follower log."""
    events, other = [], []
    if path.exists():
        text = path.read_text(errors="replace")
        lines = text.split("\n")[:-1]  # a last line without newline is still being written
        for line in lines:
            if line.startswith("{"):
                try:
                    events.append(json.loads(line))
                    continue
                except json.JSONDecodeError:
                    pass
            if line.strip() and not line.startswith("SLF4J"):
                other.append(line)
    return events, other

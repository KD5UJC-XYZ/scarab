# SPDX-License-Identifier: Apache-2.0
"""A private network of the Leios team's reference node (docs/leios.md section 12): three pools of
the `ouroboros-leios` proto-devnet, each in a container of an internal Docker network (no published
ports, no route out), their traces on stdout kept per node.

The configuration, genesis files and pool keys are fetched from the pinned revision of the
`ouroboros-leios` repository into the run directory; none of them is kept in this repository (the
keys are throwaway sandbox keys of that repository).
"""
import json
import os
import re
import shutil
import subprocess
import time
from prepare_leios_reference_image import DEFAULT_TAG, OUROBOROS_LEIOS_REVISION
from .util import REPO, USER, cache_dir, data_root, docker, jdk_image

MAGIC = 164
NODE_PORT = 3001
POOLS = (1, 2, 3)
REPOSITORY = "https://github.com/input-output-hk/ouroboros-leios.git"
DEVNET = "demo/proto-devnet"
# the traces the question of docs/leios.md 11.7 needs, at Debug (the pinned configuration has the others)
DEBUG_NAMESPACES = ("Net.Mux.Remote", "Net.InboundGovernor.Remote", "LeiosNotify.Remote", "LeiosFetch.Remote")


def network(name):
    return f"{name}-net"


def node(name, n):
    return f"{name}-n{n}"


def fetch_sources(root):
    """The pinned revision of the `ouroboros-leios` repository in `<root>/src`, fetched by commit id
    (git checks the content against it)."""
    src = root / "src"
    if not (src / ".git").exists():
        src.mkdir(parents=True)
        subprocess.run(["git", "-C", str(src), "init", "-q"], check=True)
    head = subprocess.run(["git", "-C", str(src), "rev-parse", "HEAD"], text=True, capture_output=True).stdout.strip()
    if head != OUROBOROS_LEIOS_REVISION:
        subprocess.run(["git", "-C", str(src), "fetch", "-q", "--depth", "1", REPOSITORY, OUROBOROS_LEIOS_REVISION],
                       check=True)
        subprocess.run(["git", "-C", str(src), "checkout", "-q", "FETCH_HEAD"], check=True)
    return src / DEVNET / "config"


def node_config(text, n):
    """The proto-devnet's `config.yaml` for node `n` with the namespaces of `DEBUG_NAMESPACES` at Debug.

    The file is YAML and the host has no YAML library; the edits are line-exact and each must hit,
    so a changed upstream layout fails here, not silently.
    """
    text, hits = re.subn(r"^TraceOptionNodeName: .*$", f"TraceOptionNodeName: node{n}", text, flags=re.M)
    if hits != 1:
        raise ValueError("TraceOptionNodeName not found in the pinned config.yaml")
    # The LSM-tree backing store needs io_uring, which Docker's default seccomp profile refuses
    # ("setupURing: permission denied"); the in-memory backend is what the config comment offers.
    text, hits = re.subn(r"^  Backend: V2LSM$", "  Backend: V2InMemory", text, flags=re.M)
    if hits != 1:
        raise ValueError("LedgerDB backend not found in the pinned config.yaml")
    for namespace in DEBUG_NAMESPACES:
        text, hits = re.subn(rf"^(  {re.escape(namespace)}:\n    severity: )\w+$", r"\1Debug", text, flags=re.M)
        if hits != 1:
            raise ValueError(f"{namespace} not found in the pinned config.yaml")
    return text


def prepare(root, start_delay):
    """`<root>/devnet`: the pinned genesis with a fresh start time, and a directory per node with its
    configuration, topology and keys."""
    config = fetch_sources(root)
    devnet = root / "devnet"
    shutil.rmtree(devnet, ignore_errors=True)
    shutil.copytree(config / "genesis", devnet / "genesis")
    start = int(time.time()) + start_delay
    byron = json.loads((config / "genesis" / "byron-genesis.json").read_text())
    byron["startTime"] = start
    (devnet / "genesis" / "byron-genesis.json").write_text(json.dumps(byron))
    shelley = json.loads((config / "genesis" / "shelley-genesis.json").read_text())
    shelley["systemStart"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(start))
    (devnet / "genesis" / "shelley-genesis.json").write_text(json.dumps(shelley, indent=2))
    template = json.loads((config / "topology.template.json").read_text())
    for n in POOLS:
        directory = devnet / f"node{n}"
        directory.mkdir()
        (directory / "config.yaml").write_text(node_config((config / "config.yaml").read_text(), n))
        topology = json.loads(json.dumps(template))
        topology["localRoots"][0]["accessPoints"] = [
            {"address": "NODE" + str(m), "port": NODE_PORT} for m in POOLS if m != n]
        (directory / "topology.json").write_text(json.dumps(topology, indent=2))
        for genesis in ("byron", "shelley", "alonzo", "conway", "dijkstra"):
            os.symlink(f"../genesis/{genesis}-genesis.json", directory / f"{genesis}-genesis.json")
        shutil.copytree(config / "pools-keys" / f"pool{n}", directory / "keys")
        for key in (directory / "keys").glob("*.skey"):
            key.chmod(0o400)
    return start


def start_nodes(name, root):
    """The network and the three nodes. Topologies name the peers by their container names."""
    docker("network", "create", "--internal", network(name))
    for n in POOLS:
        text = (root / "devnet" / f"node{n}" / "topology.json").read_text()
        for m in POOLS:
            text = text.replace(f"NODE{m}", node(name, m))
        (root / "devnet" / f"node{n}" / "topology.json").write_text(text)
        docker("run", "-d", "--name", node(name, n), "--network", network(name), "--pull=never",
               "--cpus=1", "--memory=3g", "--user", USER, "-v", f"{root / 'devnet'}:/data",
               "-w", f"/data/node{n}", "-e", "LEIOS_VOL_DB_PATH=leios.db.vol", "-e", "LEIOS_IMM_DB_PATH=leios.db.imm",
               DEFAULT_TAG, "cardano-node", "run", "--config", "config.yaml", "--host-addr", "0.0.0.0",
               "--port", str(NODE_PORT), "--topology", "topology.json", "--database-path", "db",
               "--socket-path", "node.socket", "--shelley-vrf-key", "keys/vrf.skey",
               "--shelley-kes-key", "keys/kes.skey", "--shelley-bls-key", "keys/bls.skey",
               "--shelley-operational-certificate", "keys/opcert.cert")


def tip(name, n=1):
    """`cardano-cli query tip` of node `n`, or `None` while the node is not answering."""
    done = docker("exec", node(name, n), "cardano-cli", "query", "tip", "--testnet-magic", str(MAGIC),
                  "--socket-path", f"/data/node{n}/node.socket", check=False)
    try:
        return json.loads(done.stdout)
    except json.JSONDecodeError:
        return None


def wait_for_block(name, block, timeout):
    """The tip of node 1 once it is at `block` or later; raises on timeout."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = tip(name)
        if current and current.get("block", 0) >= block:
            return current
        time.sleep(3)
    raise SystemExit(f"no block {block} within {timeout} s")


def save_logs(name, root, suffix=""):
    """The traces of each node (stdout of its container) in `<root>/logs`."""
    logs = root / "logs"
    logs.mkdir(exist_ok=True)
    for n in POOLS:
        done = docker("logs", node(name, n), check=False)
        (logs / f"node{n}{suffix}.log").write_text(done.stdout + done.stderr)


def stop(name):
    for n in POOLS:
        docker("rm", "-f", node(name, n), check=False)
    docker("network", "rm", network(name), check=False)


def remove(name):
    stop(name)
    shutil.rmtree(data_root(name), ignore_errors=True)


# ---------------------------------------------------------------- the probes

TEST_CLASSES = "network/target/scala-3.3.8/test-classes"  # where the probe tools are (network/src/test)


def freeze_probes(root):
    """Copy the compiled classes the probes run from (the node's runtime classpath plus the network
    module's test classes, which hold the probe tools), so a rebuild cannot change code under a run.
    Returns the container classpath of the copy at /run/classes."""
    frozen = root / "classes"
    shutil.rmtree(frozen, ignore_errors=True)
    entries = []
    for entry in (REPO / "node/target/runtime-classpath.txt").read_text().strip().split(":"):
        if entry.endswith("/classes"):
            module = entry[len("/work/"):].split("/")[0]
            shutil.copytree(REPO / entry[len("/work/"):], frozen / module)
            entries.append(f"/run/classes/{module}")
        else:
            entries.append(entry)
    shutil.copytree(REPO / TEST_CLASSES, frozen / "network-test")
    return ":".join(["/run/classes/network-test", *entries])


def probe_command(name, root, classpath, main_class, program, container, out=None):
    """`docker run` arguments of one probe: a JVM of the frozen classes on the private network. `out`
    is a directory of the run directory the probe may write to (mounted at /out)."""
    command = ["run", "--rm", "--pull=never", "--name", container, "--network", network(name), "--cpus=1",
               "--memory=1g", "--user", USER, "-e", "MALLOC_ARENA_MAX=2",
               "-v", f"{REPO}:/work:ro", "-v", f"{cache_dir()}:/work/.cache:ro",
               "-v", f"{root / 'classes'}:/run/classes:ro"]
    if out:
        command += ["-v", f"{out}:/out"]
    return command + ["--entrypoint", "java", jdk_image(), "-Xmx512m", "-cp", classpath, main_class, *program]


def run_probe(name, root, classpath, main_class, program, label, timeout):
    """Run a probe to its end, keep its output in `<root>/probes/<label>.out`, and return it."""
    out = root / "probes"
    out.mkdir(exist_ok=True)
    out.chmod(0o777)
    container = f"{name}-probe-{label}"
    done = subprocess.run(["docker", *probe_command(name, root, classpath, main_class, program, container, out)],
                          text=True, capture_output=True, timeout=timeout)
    docker("rm", "-f", container, check=False)
    (out / f"{label}.out").write_text(done.stdout + done.stderr)
    return done.stdout + done.stderr

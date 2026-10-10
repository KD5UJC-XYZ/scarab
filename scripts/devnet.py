#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local devnet: a 3-pool reference cluster plus the Scala node, all in Docker.

    devnet.py up                   create genesis and start the reference cluster
    devnet.py tip [--node N]       reference tip as JSON
    devnet.py follow [--seconds N] run the Scala follower against node1 (logs to the run dir)
    devnet.py down                 remove containers and the run directory

Run data lives in ${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/<name>. The Scala node shares the
reference container's network namespace because the reference nodes bind to loopback.
"""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
REFERENCE_IMAGE = "cardano-reference-11.1.3:local"
JDK_IMAGE = "cardano-public-v023-check:local"
MAGIC = 1082026
ENV = "/work/env"


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


def up(args):
    root = data_root(args.name)
    if root.exists():
        raise SystemExit(f"{root} exists; run `devnet.py down` first")
    (root / "genesis").mkdir(parents=True)
    docker("run", "-d", "--name", ref(args.name), "--pull=never",
           "--cpus=3", "--memory=6g", "--user", "1000:1000",
           "--tmpfs", "/work:rw,exec,size=2g,uid=1000,gid=1000",
           "-e", "PATH=/opt/reference/bin:/usr/local/bin:/usr/bin:/bin",
           "-e", "CARDANO_CLI=/opt/reference/bin/cardano-cli",
           "-e", "CARDANO_NODE=/opt/reference/bin/cardano-node",
           "--entrypoint", "/bin/sh", REFERENCE_IMAGE, "-c", "sleep infinity")
    in_ref(args.name, f"cardano-testnet create-env --num-pool-nodes 3 --testnet-magic {MAGIC} "
                      f"--output {ENV} > /work/create-env.log 2>&1")
    for f in ["shelley-genesis.json", "byron-genesis.json", "alonzo-genesis.json",
              "conway-genesis.json", "configuration.yaml"]:
        (root / "genesis" / f).write_text(in_ref(args.name, f"cat {ENV}/{f}").stdout)
    ports = {n: int(in_ref(args.name, f"cat {ENV}/node-data/node{n}/port").stdout) for n in (1, 2, 3)}
    (root / "ports.json").write_text(json.dumps(ports))
    docker("exec", "-d", ref(args.name), "/bin/sh", "-c",
           f"cd {ENV} && cardano-testnet cardano --node-env {ENV} > /work/cluster.log 2>&1")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            tip = query(args.name, "tip")
            if tip.get("block", 0) > 0:
                print(json.dumps({"up": True, "ports": ports, "tip": tip, "root": str(root)}))
                return
        except (SystemExit, json.JSONDecodeError):
            pass
        time.sleep(2)
    raise SystemExit("reference cluster produced no block within 120 s; see /work/cluster.log")


def tip(args):
    print(json.dumps(query(args.name, "tip", args.node)))


def follow(args):
    root = data_root(args.name)
    ports = json.loads((root / "ports.json").read_text())
    classpath = (REPO / "node/target/runtime-classpath.txt").read_text().strip()
    log = root / "follower.jsonl"
    command = ["run", "--rm", "--name", f"{args.name}-scala", "--pull=never",
               "--network", f"container:{ref(args.name)}", "--cpus=1", "--memory=1g",
               "--user", "1000:1000", "-v", f"{REPO}:/work:ro", "-v", f"{root / 'genesis'}:/genesis:ro",
               "--entrypoint", "java", JDK_IMAGE, "-Xmx512m", "-cp", classpath, "lab.node.Main", "follow",
               "--genesis", "/genesis/shelley-genesis.json", "--peer", f"127.0.0.1:{ports[str(args.node)]}"]
    if args.seconds:
        command += ["--seconds", str(args.seconds)]
    with log.open("a") as out:
        proc = subprocess.Popen(["docker", *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in proc.stdout:
            out.write(line)
            out.flush()
            sys.stdout.write(line)
        sys.exit(proc.wait())


def down(args):
    for c in (f"{args.name}-scala", ref(args.name)):
        docker("rm", "-f", c, check=False)
    shutil.rmtree(data_root(args.name), ignore_errors=True)
    print(json.dumps({"down": True}))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--name", default="csl-devnet")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("up").set_defaults(run=up)
    t = sub.add_parser("tip")
    t.add_argument("--node", type=int, default=1)
    t.set_defaults(run=tip)
    f = sub.add_parser("follow")
    f.add_argument("--seconds", type=int)
    f.add_argument("--node", type=int, default=1)
    f.set_defaults(run=follow)
    sub.add_parser("down").set_defaults(run=down)
    args = p.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()

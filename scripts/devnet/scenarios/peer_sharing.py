# SPDX-License-Identifier: Apache-2.0
"""A bounded, private two-way PeerSharing governor proof against the real Haskell relay."""
import json
import shutil
import subprocess
import sys
import time

from ..cluster import database_mounts, freeze_classes, scala_container, up
from ..util import ENV, MAGIC, QUERY_PORT, REFERENCE_IMAGE, data_root, docker, in_ref, put_file, read_json, read_log, ref
from .relay import RELAY_DIR as RECEIVER_DIR, SCALA_PORT, scala_connections, start_haskell_relay, tip_at

DISCOVERY_PORT = 3352
RELAY_DIR = "/work/peer-sharing-relay"
RELAY_SOCKET = f"{RELAY_DIR}/sock"


def topology(ports, advertise=True):
    return {"bootstrapPeers": None,
            "localRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": port} for port in ports],
                            "advertise": advertise, "behindFirewall": False, "trustable": False,
                            "diffusionMode": "InitiatorAndResponder", "hotValency": len(ports),
                            "warmValency": len(ports)}],
            "publicRoots": [{"accessPoints": [], "advertise": False}], "useLedgerAfterSlot": -1}


def machine_events(text):
    """Read actual machine traces, retaining the complete original log separately."""
    events = []
    for line in text.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        data = value.get("data", value) if isinstance(value, dict) else None
        if isinstance(data, dict) and isinstance(data.get("kind"), str):
            events.append(data)
    return events


def address(value):
    if not isinstance(value, dict) or "address" not in value or "port" not in value:
        return None
    try:
        return f"{value['address']}:{int(value['port'])}"
    except (TypeError, ValueError):
        return None


def reference_answers(events):
    """Only actual successful replies to the reference's request to Scarab count as proof."""
    answers = []
    for event in events:
        if event.get("kind") != "PeerShareResults":
            continue
        for result in event.get("result", []):
            if not isinstance(result, list) or len(result) != 2:
                continue
            peer, verdict = result
            if address(peer) != f"127.0.0.1:{SCALA_PORT}" or not isinstance(verdict, dict):
                continue
            peers = verdict.get("Right")
            if isinstance(peers, list) and peers:
                answers.append([address(peer) for peer in peers])
    return answers


def proof(connections, scala_events, reference_events, pool_ports):
    selected = (connections or {}).get("peerSelection", {})
    peers = selected.get("peers", [])
    eligible = {p["peer"] for p in peers if p.get("advertise") is True and
                p.get("everConnected") is True and p.get("failCount") == 0}
    advertised = {f"127.0.0.1:{port}" for port in pool_ports}
    discovered = {peer for event in scala_events if event.get("action") == "peerShareResults"
                  for peer in event.get("added", [])} & advertised
    requests = [event for event in reference_events if event.get("kind") == "PeerShareRequests" and
                any(address(peer) == f"127.0.0.1:{SCALA_PORT}" for peer in event.get("selectedPeers", []))]
    answers = reference_answers(reference_events)
    bounded = bool(requests and answers) and all(
        None not in answer and len(answer) <= min(10, requests[-1].get("numRequested", 0)) and
        len(set(answer)) == len(answer) and set(answer).issubset(eligible) for answer in answers)
    return {"localRoots": [f"127.0.0.1:{DISCOVERY_PORT}"], "advertisedPoolPeers": sorted(advertised),
            "discoveredByScarab": sorted(discovered), "shareableAtObservation": sorted(eligible),
            "referenceRequests": requests, "referenceAnswers": answers,
            "known": selected.get("counters", {}).get("known"),
            "root": selected.get("counters", {}).get("root"),
            "passed": bool(discovered and bounded and selected.get("counters", {}).get("known", 0) > 1)}


def start_relay(args, root, ports):
    in_ref(args.name, f"mkdir -p {RELAY_DIR}/db")
    config = read_json(args.name, f"{ENV}/configuration.yaml")
    config["PeerSharing"] = True
    config["ExperimentalProtocolsEnabled"] = False
    for namespace in ("Net.PeerSelection", "Net.Handshake.Remote"):
        config.setdefault("TraceOptions", {}).setdefault(namespace, {})["severity"] = "Debug"
    initial = topology(ports)
    for file, value in ((f"{ENV}/peer-sharing-configuration.json", config),
                        (f"{RELAY_DIR}/topology.json", initial)):
        put_file(args.name, file, json.dumps(value, indent=2))
    (root / "relay-configuration.json").write_text(json.dumps(config, indent=2))
    (root / "relay-topology-initial.json").write_text(json.dumps(initial, indent=2))
    docker("exec", "-d", ref(args.name), "/bin/sh", "-c",
           f"cd {ENV} && echo $$ > {RELAY_DIR}/pid && exec cardano-node run --config {ENV}/peer-sharing-configuration.json "
           f"--topology {RELAY_DIR}/topology.json --database-path {RELAY_DIR}/db "
           f"--socket-path {RELAY_SOCKET} --port {DISCOVERY_PORT} --host-addr 127.0.0.1 "
           f"> {RELAY_DIR}/node.log 2>&1")


def start_scala(args, root):
    db = root / "scala-db"
    db.mkdir()
    sock = root / "scala-sock"
    sock.mkdir()
    config = json.loads((root / "genesis/configuration.yaml").read_text())
    config.pop("PeerSharing", None)  # Exercise the actual relay default.
    config["ExperimentalProtocolsEnabled"] = False
    (root / "genesis/scala-configuration.json").write_text(json.dumps(config, indent=2))
    (root / "genesis/scala-topology.json").write_text(json.dumps(topology([DISCOVERY_PORT]), indent=2))
    program = ["follow", "--config", "/genesis/scala-configuration.json", "--genesis-dir", "/genesis",
               "--topology", "/genesis/scala-topology.json", "--query-port", str(QUERY_PORT),
               "--listen", f"127.0.0.1:{SCALA_PORT}", "--db", "/db", "--local-socket", "/sock/node.socket"]
    command = scala_container(args, root, freeze_classes(root), "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro", *database_mounts(db), f"{sock}:/sock"],
                              name=f"{args.name}-scala")
    with (root / "follower.jsonl").open("w") as log:
        return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def peer_sharing(args):
    root = data_root(args.name)
    scala = None
    summary = {"passed": False}
    try:
        up(args)
        ports = list(json.loads((root / "ports.json").read_text()).values())
        start_relay(args, root, ports)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            tip = tip_at(args.name, RELAY_SOCKET)
            if tip and tip.get("block", 0) > 0:
                break
            time.sleep(1)
        else:
            raise SystemExit("the private Haskell relay did not reach a nonempty tip")
        scala = start_scala(args, root)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            current = scala_connections(args)
            peers = (current or {}).get("peerSelection", {}).get("peers", [])
            if any(p.get("everConnected") and p.get("peerSharing") for p in peers):
                break
            if scala.poll() is not None:
                raise SystemExit(f"Scarab exited {scala.returncode}")
            time.sleep(1)
        else:
            raise SystemExit("Scarab did not establish its only local root with sharing enabled")
        (root / "connections-initial.json").write_text(json.dumps(current, indent=2))
        # A second real Haskell relay starts later and has Scarab as its sole upstream.
        # No topology reload or external routing is needed. Its willingness is enabled, while
        # its configured advertisement of Scarab is off (avoids returning Scarab its own address).
        time.sleep(10)
        start_haskell_relay(args, advertise=False)
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            if scala.poll() is not None:
                raise SystemExit(f"Scarab exited {scala.returncode}")
            events, other = read_log(root / "follower.jsonl")
            if other or any(event.get("event") == "error" for event in events):
                raise SystemExit("Scarab logged an error or unparsed output")
            reference = in_ref(args.name, f"cat {RECEIVER_DIR}/node.log").stdout
            current = scala_connections(args)
            summary = proof(current, events, machine_events(reference), ports)
            if summary["passed"]:
                if "DecoderFailure" in reference:
                    raise SystemExit("the reference reported a decoder failure")
                break
            time.sleep(2)
        else:
            raise SystemExit("no complete two-way nonempty governor proof before the bounded timeout")
        (root / "connections-final.json").write_text(json.dumps(current, indent=2))
    except SystemExit as error:
        summary["passed"] = False
        summary["failure"] = str(error)
    finally:
        try:
            if root.exists():
                for source, target in ((f"{RECEIVER_DIR}/node.log", "reference-relay.jsonl"),
                                       (f"{RELAY_DIR}/node.log", "discovery-relay.jsonl"),
                                       ("/work/cluster.log", "reference-cluster.log")):
                    saved = docker("exec", ref(args.name), "cat", source, check=False)
                    (root / target).write_text(saved.stdout)
                versions = docker("exec", ref(args.name), "/bin/sh", "-c",
                                  "cardano-node --version; cardano-cli --version", check=False)
                (root / "reference-version.txt").write_text(versions.stdout)
                (root / "reference-image.json").write_text(docker("image", "inspect", REFERENCE_IMAGE).stdout)
                (root / "peer-sharing-summary.json").write_text(json.dumps(summary, indent=2))
        finally:
            docker("rm", "-f", f"{args.name}-scala", check=False)
            if scala is not None:
                scala.wait()
            docker("rm", "-f", ref(args.name), check=False)
            if root.exists():
                shutil.rmtree(root / "classes", ignore_errors=True)
            print(json.dumps(summary, indent=2), flush=True)
    sys.exit(0 if summary["passed"] else 1)

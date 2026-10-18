# SPDX-License-Identifier: Apache-2.0
"""The `relay` scenario: the Scala node as a relay serving a Haskell relay."""
import json
import pathlib
import shutil
import subprocess
import sys
import time
from ..cluster import freeze_classes, scala_container, up
from ..util import (CLI, ENTRY, ENV, MAGIC, QUERY_PORT, REFERENCE_IMAGE, USER, data_root, docker, in_ref, put_file,
                    read_json, read_log, ref)


# ---------------------------------------------------------------- the Scala node as a relay

SCALA_PORT = 3350   # the Scala node's node-to-node listener (loopback, shared network namespace)
RELAY_PORT = 3351   # the Haskell relay, whose only upstream peer is the Scala node
RELAY_DIR = "/work/relay"
RELAY_SOCKET = f"{RELAY_DIR}/sock"


def start_scala_relay(args, root, peers):
    """The Scala node following `peers` (reference node ports) and listening for node-to-node
    connections, with an on-disk chain so it can serve the whole chain to a syncing peer."""
    classpath = freeze_classes(root)
    db = root / "scala-db"
    db.mkdir(exist_ok=True)
    db.chmod(0o777)
    sock = root / "scala-sock"
    sock.mkdir(exist_ok=True)
    sock.chmod(0o777)
    program = ["follow", "--genesis-dir", "/genesis", "--query-port", str(QUERY_PORT),
               "--listen", f"127.0.0.1:{SCALA_PORT}", "--db", "/db", "--local-socket", "/sock/node.socket"]
    for port in peers:
        program += ["--peer", f"127.0.0.1:{port}"]
    # The Scala node takes part in peer sharing, as the Haskell relay does by default (a node without producer
    # credentials), and reads ExperimentalProtocolsEnabled, which decides the highest node-to-node version it
    # offers: cardano-testnet writes `true`, so the key is set from --max-ntn-version either way (15, the
    # reference's latest release, unless 16 is asked for). The pools keep their own configuration.
    config = json.loads((root / "genesis" / "configuration.yaml").read_text())
    config["PeerSharing"] = bool(getattr(args, "peer_sharing", True))
    config["ExperimentalProtocolsEnabled"] = getattr(args, "max_ntn_version", 15) > 15
    (root / "genesis" / "scala-configuration.json").write_text(json.dumps(config, indent=2))
    program += ["--config", "/genesis/scala-configuration.json"]
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro", f"{db}:/db", f"{sock}:/sock"],
                              name=f"{args.name}-scala")
    log = (root / "follower.jsonl").open("a")
    return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def start_haskell_relay(args):
    """A non-producing reference node whose topology names only the Scala node."""
    in_ref(args.name, f"mkdir -p {RELAY_DIR}/db")
    topology = {"bootstrapPeers": None,
                "localRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": SCALA_PORT}],
                                "advertise": bool(getattr(args, "peer_sharing", True)), "behindFirewall": False,
                                "trustable": True,
                                "diffusionMode": args.relay_mode, "hotValency": 1, "warmValency": 1}],
                "publicRoots": [{"accessPoints": [], "advertise": False}], "useLedgerAfterSlot": -1}
    put_file(args.name, f"{RELAY_DIR}/topology.json", json.dumps(topology))
    # cardano-testnet writes `"PeerSharing": false`; a relay has it on by default (the reference's default for a
    # node without producer credentials), so the relay runs on a copy of the configuration (next to the original,
    # whose genesis file names are relative to it) that turns it on. The pools keep theirs.
    config = read_json(args.name, f"{ENV}/configuration.yaml")
    config["PeerSharing"] = bool(getattr(args, "peer_sharing", True))
    # the reference offers versions above its latest release (15) only with this key (Run.hs 555 to 565);
    # cardano-testnet writes `true`, so a run at 15 turns it off
    config["ExperimentalProtocolsEnabled"] = getattr(args, "max_ntn_version", 15) > 15
    # the relay's handshake traces: the version its connection to the Scala node came out with
    config.setdefault("TraceOptions", {}).setdefault("Net.Handshake.Remote", {})["severity"] = "Debug"
    # the governor's peer-sharing traces, to see when the relay asks us (after `policyPeerShareActivationDelay`, 300 s)
    config.setdefault("TraceOptions", {}).setdefault("Net.PeerSelection", {})["severity"] = "Debug"
    put_file(args.name, f"{ENV}/relay-configuration.yaml", json.dumps(config, indent=2))
    docker("exec", "-d", ref(args.name), "/bin/sh", "-c",
           f"cd {ENV} && cardano-node run --config {ENV}/relay-configuration.yaml --topology {RELAY_DIR}/topology.json "
           f"--database-path {RELAY_DIR}/db --socket-path {RELAY_SOCKET} --port {RELAY_PORT} "
           f"--host-addr 127.0.0.1 > {RELAY_DIR}/node.log 2>&1")


def tip_at(name, socket):
    """`cardano-cli query tip` against one node socket, or None while it cannot answer."""
    r = docker("exec", ref(name), "/bin/sh", "-c",
               f"cardano-cli query tip --testnet-magic {MAGIC} --socket-path {socket}", check=False, timeout=30)
    try:
        tip = json.loads(r.stdout)
        return tip if "hash" in tip else None
    except json.JSONDecodeError:
        return None


def scala_connections(args):
    """The Scala node's connection table and inbound sessions (`GET /connections`), or None."""
    url = f"http://127.0.0.1:{QUERY_PORT}/connections"
    script = f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen('{url}',timeout=20).read().decode())"
    r = docker("exec", f"{args.name}-scala", "python3", "-c", script, check=False, timeout=60)
    try:
        return json.loads(r.stdout) if r.returncode == 0 and r.stdout else None
    except json.JSONDecodeError:
        return None


def inbound_session(connections):
    """The inbound session of the Haskell relay (the only inbound peer), if it exists."""
    sessions = (connections or {}).get("inboundSessions") or []
    return sessions[0] if sessions else None


def wait_inbound_session(args, timeout):
    """Wait for the Scala node's own ChainSync client to follow the Haskell relay over the relay's
    inbound duplex connection: an intersection found and headers received."""
    deadline = time.monotonic() + timeout
    seen = None
    while time.monotonic() < deadline:
        seen = scala_connections(args)
        session = inbound_session(seen)
        if session and session.get("intersection") and session.get("headers", 0) > 0:
            return seen
        time.sleep(1)
    return seen


def log_counts(path):
    """Event counts of the Scala node's JSON log."""
    counts = {}
    events, other = read_log(path)
    for e in events:
        counts[e["event"]] = counts.get(e["event"], 0) + 1
    return counts, events, other


def submit_through_local_socket(args, root):
    """`cardano-cli transaction submit` against the Scala node's node-to-client Unix socket (run in a
    throwaway container of the reference image that shares the socket directory). The transaction
    is built and signed through the Haskell relay. Returns what happened; the caller checks that it
    reached the mempool and a block."""
    tx = f"{root / 'ntc'}"
    pathlib.Path(tx).mkdir(exist_ok=True)
    pathlib.Path(tx).chmod(0o777)
    utxo_dir = f"{ENV}/utxo-keys/utxo3"
    address = in_ref(args.name, f"{CLI} address build --payment-verification-key-file {utxo_dir}/utxo.vkey "
                                f"--testnet-magic {MAGIC}").stdout.strip()
    utxos = json.loads(in_ref(args.name, f"{CLI} query utxo --address {address} --socket-path {RELAY_SOCKET} "
                                         f"--testnet-magic {MAGIC} --output-json").stdout)
    txin = max(utxos, key=lambda k: utxos[k]["value"]["lovelace"])
    target = in_ref(args.name, f"{CLI} address build --payment-verification-key-file "
                               f"{ENV}/utxo-keys/utxo1/utxo.vkey --testnet-magic {MAGIC}").stdout.strip()
    in_ref(args.name, f"{CLI} transaction build --testnet-magic {MAGIC} --socket-path {RELAY_SOCKET} --tx-in {txin} "
                      f"--tx-out {target}+12345678 --change-address {address} --out-file /work/ntc.body && "
                      f"{CLI} transaction sign --tx-body-file /work/ntc.body --signing-key-file {utxo_dir}/utxo.skey "
                      f"--testnet-magic {MAGIC} --out-file /work/ntc.signed")
    txid = in_ref(args.name, f"{CLI} transaction txid --tx-file /work/ntc.signed --output-text").stdout.strip()
    signed = subprocess.run(["docker", "exec", ref(args.name), "cat", "/work/ntc.signed"], capture_output=True, check=True).stdout
    (pathlib.Path(tx) / "ntc.signed").write_bytes(signed)
    r = docker("run", "--rm", "--pull=never", "--user", USER, "-v", f"{root / 'scala-sock'}:/sock",
               "-v", f"{tx}:/tx:ro", "--entrypoint", "/opt/reference/bin/cardano-cli", REFERENCE_IMAGE,
               "conway", "transaction", "submit", "--testnet-magic", str(MAGIC),
               "--socket-path", "/sock/node.socket", "--tx-file", "/tx/ntc.signed", check=False, timeout=60)
    return {"txid": txid, "txin": txin, "exitCode": r.returncode, "stdout": r.stdout.strip()[:300],
            "stderr": r.stderr.strip()[:600]}


def relay_handshakes(log_text):
    """The handshake messages in the Haskell relay's log (`Net.Handshake.Remote` at Debug): what it proposed and
    what came back, as the reference prints them."""
    proposed, accepted = [], []
    for line in log_text.splitlines():
        if '"ns":"Net.Handshake.Remote.' not in line:
            continue
        try:
            msg = json.loads(line)["data"]["event"]["msg"]["msg"]
        except (json.JSONDecodeError, KeyError, TypeError):
            continue
        if msg.startswith("MsgProposeVersions"):
            proposed.append(msg)
        elif msg.startswith("MsgAcceptVersion"):
            accepted.append(msg)
    return {"proposed": proposed, "accepted": accepted}


def relay(args):
    """Fresh 3-pool cluster, the Scala node as a relay in front of a Haskell relay, and a load
    generator that submits through the Haskell relay.

    Transactions travel: Haskell relay -> (TxSubmission2) -> Scala mempool -> (TxSubmission2) -> pools
    -> blocks -> Scala node (ChainSync/BlockFetch from the pools) -> Haskell relay (from the Scala node)."""
    root = data_root(args.name)
    up(args)
    ports = json.loads((root / "ports.json").read_text())
    peers = [ports[str(n)] for n in sorted(map(int, ports))][: args.peers]
    scala, loader = None, None
    samples, failures, summary = [], [], {}
    try:
        scala = start_scala_relay(args, root, peers)
        log = root / "follower.jsonl"
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            counts, _, _ = log_counts(log)
            if counts.get("listening") and counts.get("extended"):
                break
            if scala.poll() is not None:
                raise SystemExit(f"the Scala node exited with {scala.returncode}; see {log}")
            time.sleep(1)
        else:
            raise SystemExit("the Scala node did not start listening and following within 120 s")
        # let the chain grow past k so the relay has to be served from the Scala node's stored blocks too
        grow_until = time.monotonic() + 180
        while time.monotonic() < grow_until:
            counts, _, _ = log_counts(log)
            if counts.get("extended", 0) >= args.relay_after_blocks:
                break
            time.sleep(1)
        relay_blocks = log_counts(log)[0].get("extended", 0)
        start_haskell_relay(args)
        relay_started = time.monotonic()
        reference = f"{ENV}/socket/node1/sock"
        synced_at = None
        deadline = time.monotonic() + args.sync_timeout
        while time.monotonic() < deadline:
            mine, theirs = tip_at(args.name, RELAY_SOCKET), tip_at(args.name, reference)
            if mine and theirs and mine["block"] >= theirs["block"] - 1 and mine["block"] > 0:
                synced_at = time.monotonic()
                break
            time.sleep(1)
        if synced_at is None:
            raise SystemExit(f"the Haskell relay did not sync from the Scala node within {args.sync_timeout} s")
        print(json.dumps({"relaySynced": tip_at(args.name, RELAY_SOCKET)}), flush=True)
        # G2: the Haskell relay connected to us; our own clients follow it over that same connection
        duplex = args.relay_mode == "InitiatorAndResponder"
        at_sync = wait_inbound_session(args, 90) if duplex else scala_connections(args)
        print(json.dumps({"connectionsAtSync": at_sync}), flush=True)
        local = submit_through_local_socket(args, root)
        print(json.dumps({"localSubmit": local}), flush=True)
        command = [sys.executable, str(ENTRY), "--name", args.name, "load", "--seed", str(args.seed),
                   "--socket", RELAY_SOCKET, "--interval", str(args.interval), "--stats-every", "1"]
        loader = subprocess.Popen(command, stdout=(root / "load.log").open("w"), stderr=subprocess.STDOUT)
        stop = time.monotonic() + args.seconds
        while time.monotonic() < stop:
            time.sleep(args.every)
            if scala.poll() is not None:
                failures.append(f"the Scala node exited with {scala.returncode}")
                break
            sample = {"t": round(time.monotonic() - synced_at, 1),
                      "relay": tip_at(args.name, RELAY_SOCKET),
                      "pool1": tip_at(args.name, reference)}
            samples.append(sample)
            r, p = sample["relay"], sample["pool1"]
            print(json.dumps({"t": sample["t"], "relay": r and (r["block"], r["hash"][:12]),
                              "pool1": p and (p["block"], p["hash"][:12])}), flush=True)
        loader.terminate()
        loader.wait()
        loader = None
        # let the chain settle, then compare every node's tip
        final = {}
        settle = time.monotonic() + 60
        while time.monotonic() < settle:
            final = {"relay": tip_at(args.name, RELAY_SOCKET)}
            for n in (1, 2, 3):
                final[f"pool{n}"] = tip_at(args.name, f"{ENV}/socket/node{n}/sock")
            if all(final.values()) and len({t["hash"] for t in final.values()}) == 1:
                break
            time.sleep(1)
        final_hashes = {k: (v["block"], v["hash"]) if v else None for k, v in final.items()}
        counts, events, other = log_counts(log)
        scala_blocks = [e for e in events if e["event"] == "extended"]
        scala_tip = scala_blocks[-1]["block"]["hash"] if scala_blocks else None
        agree = len({v[1] for v in final_hashes.values() if v}) == 1 and all(final_hashes.values())
        lag = [p["pool1"]["block"] - p["relay"]["block"] for p in samples if p["relay"] and p["pool1"]]
        load_lines = (root / "load.log").read_text().strip().splitlines()
        load_stats = next((json.loads(l) for l in reversed(load_lines) if l.startswith("{") and "submitted" in l), {})
        epochs = sorted({p["pool1"]["epoch"] for p in samples if p["pool1"]})
        relay_log = in_ref(args.name, f"cat {RELAY_DIR}/node.log | tail -c 3000").stdout
        # the relay's own verdict on what we sent it: a decoder failure on any mini-protocol means we
        # broke the protocol (the connection is dropped and re-established, which the lag check only
        # catches when it lasts long enough)
        relay_errors = [line for line in in_ref(args.name, f"cat {RELAY_DIR}/node.log").stdout.splitlines()
                        if "DecoderFailure" in line and "ExceptionExit" in line]
        # the versions the Haskell relay proposed to us and the one we accepted (its handshake trace)
        handshakes = relay_handshakes(in_ref(args.name, f"cat {RELAY_DIR}/node.log").stdout)
        final_connections = scala_connections(args)
        first, last = inbound_session(at_sync), inbound_session(final_connections)
        local["adopted"] = any(e["event"] == "mempoolAdopted" and e["tx"] == local["txid"] for e in events)
        local["addedToMempool"] = any(e["event"] == "mempoolAdded" and e["tx"] == local["txid"] for e in events)
        summary = {
            "localSubmit": local,
            "relaySyncSeconds": round(synced_at - relay_started, 1),
            "scalaBlocksWhenRelayStarted": relay_blocks,
            "upstreamPeers": len(peers),
            "samples": len(samples),
            "epochsObserved": epochs,
            "relayLagBlocks": {"max": max(lag, default=None), "atTip": sum(1 for x in lag if x <= 1), "of": len(lag)},
            "finalTips": final_hashes,
            "scalaTip": scala_tip,
            "allTipsEqual": agree,
            "load": load_stats,
            "propagation": {
                "submittedToHaskellRelay": load_stats.get("submitted"),
                "arrivedInScalaMempool": counts.get("mempoolAdded", 0),
                "rejectedByScalaMempool": counts.get("mempoolRejected", 0),
                "includedInBlocks": counts.get("mempoolAdopted", 0),
                "droppedAfterAdding": counts.get("mempoolDropped", 0)},
            "scalaEvents": {k: counts.get(k, 0) for k in
                            ("extended", "rolledBack", "reconnecting", "error", "inbound", "mempoolAdded",
                             "mempoolAdopted", "mempoolRejected", "mempoolDropped")},
            "relayHandshake": handshakes,
            "unparsedScalaOutput": other[:10],
            "inbound": {
                "mode": args.relay_mode,
                "connectionsAtSync": at_sync,
                "connectionsAtEnd": final_connections,
                "sessionHeadersAtSync": first and first.get("headers"),
                "sessionHeadersAtEnd": last and last.get("headers"),
                "events": {k: sum(1 for e in events if e["event"] == "inbound" and e.get("state") == k)
                           for k in ("accepted", "duplex", "unidirectional", "following", "sessionEnded",
                                     "sessionDropped", "refused", "reused", "closed")},
            },
        }
        if not agree:
            failures.append(f"final tips differ: {final_hashes}")
        # a block can be forged between the settle loop and the read of our log: then our tip is the
        # reference's next tip, so take a second look before calling it a divergence
        later = tip_at(args.name, reference) if scala_tip and final.get("pool1") else None
        if scala_tip and final.get("pool1") and scala_tip not in (final["pool1"]["hash"], later and later["hash"]):
            failures.append(f"the Scala node's last block {scala_tip[:12]} is not the final tip")
        if lag and sum(1 for x in lag if x <= 1) < 0.9 * len(lag):
            failures.append("the Haskell relay was more than one block behind in over 10% of samples")
        if len(epochs) < args.min_epochs:
            failures.append(f"only {len(epochs)} epochs observed")
        if not load_stats.get("submitted"):
            failures.append("no transaction was submitted through the Haskell relay")
        elif summary["propagation"]["arrivedInScalaMempool"] < 0.9 * load_stats["submitted"]:
            failures.append("fewer than 90% of the submitted transactions reached the Scala mempool")
        if summary["propagation"]["includedInBlocks"] == 0:
            failures.append("no transaction passing through the Scala mempool was included in a block")
        if local["exitCode"] != 0 or not local["addedToMempool"] or not local["adopted"]:
            failures.append(f"the transaction submitted over the node-to-client socket did not reach a block: {local}")
        if duplex:
            # the relay's inbound connection is duplex and our client on it followed the relay all along
            states = [c["state"].replace(" ", "") for c in (final_connections or {}).get("connections", [])]
            if not any("Established(Inbound,Duplex)" in x for x in states):
                failures.append(f"no established inbound duplex connection at the end: {states}")
            if not first or not first.get("intersection") or first.get("headers", 0) < 1:
                failures.append("the Scala node did not follow the Haskell relay over its inbound connection")
            elif not last or last.get("ended") or last.get("headers", 0) <= first.get("headers", 0):
                failures.append(f"the session on the inbound connection did not keep following: {first} -> {last}")
            else:
                pool_tip = (final.get("pool1") or {}).get("block", 0)
                if last.get("peerTipBlock") is None or pool_tip - int(last["peerTipBlock"]) > 3:
                    failures.append(f"the relay's tip seen over the inbound connection ({last.get('peerTipBlock')}) "
                                    f"is behind the pool's ({pool_tip})")
            if summary["inbound"]["events"]["duplex"] < 1 or summary["inbound"]["events"]["following"] < 1:
                failures.append(f"missing inbound events: {summary['inbound']['events']}")
        wanted = f"NodeToNodeV_{getattr(args, 'max_ntn_version', 15)}"
        if not handshakes["accepted"] or any(not a.startswith(f"MsgAcceptVersion {wanted} ") for a in handshakes["accepted"]):
            failures.append(f"the relay's connection to the Scala node was not accepted as {wanted}: {handshakes}")
        if relay_errors:
            failures.append(f"the Haskell relay failed to decode {len(relay_errors)} message(s) from us: "
                            f"{relay_errors[0][-300:]}")
        if counts.get("error"):
            failures.append(f"{counts['error']} error events in the Scala log")
        if other:
            failures.append(f"unexpected Scala output: {other[0][:200]}")
        summary["relayLogTail"] = relay_log[-600:] if failures else ""
    except SystemExit as e:
        failures.append(str(e))
    finally:
        if loader is not None:
            loader.terminate()
        docker("rm", "-f", f"{args.name}-scala", check=False)
        if scala is not None:
            scala.wait()
        summary["failures"] = failures
        summary["passed"] = not failures
        (root / "relay-summary.json").write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary, indent=2))
        if not args.keep:
            docker("rm", "-f", ref(args.name), check=False)
            shutil.rmtree(root / "classes", ignore_errors=True)
            if args.keep_data:
                pass
    sys.exit(0 if not failures else 1)

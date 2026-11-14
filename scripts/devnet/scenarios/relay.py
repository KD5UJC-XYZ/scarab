# SPDX-License-Identifier: Apache-2.0
"""The `relay` scenario: the Scala node as a relay serving a Haskell relay."""
import json
import pathlib
import shutil
import subprocess
import sys
import time
from ..cluster import database_mounts, freeze_classes, scala_container, up
from ..consensus import trusted_pools
from ..util import (CLI, ENTRY, ENV, MAGIC, QUERY_PORT, REFERENCE_IMAGE, USER, data_root, docker, in_ref, put_file,
                    read_json, read_log, ref)


# ---------------------------------------------------------------- the Scala node as a relay

SCALA_PORT = 3350   # the Scala node's node-to-node listener (loopback, shared network namespace)
RELAY_PORT = 3351   # the Haskell relay, whose only upstream peer is the Scala node
RELAY_DIR = "/work/relay"
RELAY_SOCKET = f"{RELAY_DIR}/sock"
RELAY_REMOTE = f"127.0.0.1:{RELAY_PORT}"   # the relay dials from its listening address (bound, duplex)
TOPOLOGY = "scala-relay-topology.json"
# the outbound side's use of the peer's own connection (ouroboros-network c45735a5,
# ConnectionManager/Core.hs 1335-1370): `Awake^{Duplex}_{Local}` or `PromotedToWarm^{Duplex}_{Local}`
REUSE = {("InboundIdleSt Duplex", "OutboundDupSt Ticking"), ("InboundSt Duplex", "DuplexSt")}


def relay_root(behind_firewall):
    """The Haskell relay as a local root of the Scala node. Behind a firewall its provenance is inbound: the
    Scala node never dials it and can only use the relay's own connection (Core.hs 1390-1399)."""
    return {"accessPoints": [{"address": "127.0.0.1", "port": RELAY_PORT}], "advertise": False,
            "behindFirewall": behind_firewall, "trustable": False, "diffusionMode": "InitiatorAndResponder",
            "hotValency": 1, "warmValency": 1}


def write_topology(root, groups):
    """The Scala node's topology file, read at start and again on SIGHUP."""
    topology = {"bootstrapPeers": None, "publicRoots": [], "useLedgerAfterSlot": -1, "localRoots": groups}
    (root / "genesis" / TOPOLOGY).write_text(json.dumps(topology, indent=2))


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
    # a topology the run extends with the relay once it has connected (SIGHUP); in Genesis mode it is the
    # trusted pools', else the pools stay CLI peers and the topology starts empty
    if getattr(args, "consensus_mode", None) == "GenesisMode":
        trusted_pools(root, peers)
        pools = json.loads((root / "genesis" / "scala-consensus-topology.json").read_text())["localRoots"]
    else:
        pools = []
        for port in peers:
            program += ["--peer", f"127.0.0.1:{port}"]
    write_topology(root, pools)
    (root / "genesis" / "scala-pool-roots.json").write_text(json.dumps(pools))
    program += ["--topology", f"/genesis/{TOPOLOGY}"]
    # The Scala node takes part in peer sharing, as the Haskell relay does by default (a node without producer
    # credentials), and reads ExperimentalProtocolsEnabled, which decides the highest node-to-node version it
    # offers: cardano-testnet writes `true`, so the key is set from --max-ntn-version either way (15, the
    # reference's latest release, unless 16 is asked for). The pools keep their own configuration.
    config = json.loads((root / "genesis" / "configuration.yaml").read_text())
    config["PeerSharing"] = bool(getattr(args, "peer_sharing", True))
    config["ExperimentalProtocolsEnabled"] = getattr(args, "max_ntn_version", 15) > 15
    if getattr(args, "consensus_mode", None) is not None:
        config["ConsensusMode"] = args.consensus_mode
    (root / "genesis" / "scala-configuration.json").write_text(json.dumps(config, indent=2))
    program += ["--config", "/genesis/scala-configuration.json"]
    command = scala_container(args, root, classpath, "xyz.kd5ujc.scarab.node.Main", program,
                              mounts=[f"{root / 'genesis'}:/genesis:ro", *database_mounts(db), f"{sock}:/sock"],
                              name=f"{args.name}-scala")
    log = (root / "follower.jsonl").open("a")
    return subprocess.Popen(["docker", *command], stdout=log, stderr=subprocess.STDOUT, text=True)


def start_haskell_relay(args, advertise=None):
    """A non-producing reference node whose topology names only the Scala node."""
    in_ref(args.name, f"mkdir -p {RELAY_DIR}/db")
    topology = {"bootstrapPeers": None,
                "localRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": SCALA_PORT}],
                                "advertise": bool(getattr(args, "peer_sharing", True)) if advertise is None else advertise, "behindFirewall": False,
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


def relay_connections(connections):
    """The entries of the Scala node's connection table for the Haskell relay's address."""
    return [c for c in (connections or {}).get("connections", []) if c.get("remote") == RELAY_REMOTE]


def relay_temperature(connections):
    """The relay's temperature in the Scala node's peer selection, if it is known."""
    peers = ((connections or {}).get("peerSelection") or {}).get("peers") or []
    return next((p.get("temperature") for p in peers if p.get("peer") == RELAY_REMOTE), None)


def reuse_events(path):
    """The Scala node's `connection` changes of the relay's connection that are the outbound side's reuse."""
    events, _ = read_log(path)
    return [e for e in events if e.get("event") == "connection" and e.get("what") == "changed"
            and e.get("remote") == RELAY_REMOTE and (e.get("from"), e.get("to")) in REUSE]


def relay_session(connections):
    """What the Scala node's running hot session of the relay saw of it (`GET /connections` `sessions`, its
    `active` report: the connection it runs over, its activation, intersection, headers and peer tip), if any."""
    report = next((r for r in (connections or {}).get("sessions") or [] if r.get("peer") == RELAY_REMOTE), None)
    return (report or {}).get("active")


def over_relay_connection(connections, session):
    """Whether `session` runs over one of the relay's connections in the same table."""
    return bool(session) and session.get("connection") in [c.get("connection") for c in relay_connections(connections)]


def following(connections):
    """Whether the relay's running session, over the relay's connection, found an intersection and received
    headers."""
    session = relay_session(connections)
    return bool(over_relay_connection(connections, session) and session.get("intersection")
                and session.get("headers", 0) > 0)


def reuse_failures(first, last, pool_tip, reuses, dialled):
    """Why the outbound governor's use of the relay's own connection does not hold: a reuse transition, never a
    dial, one connection to the relay in duplex use, the relay hot, and its session intersected and still
    receiving headers at the end, its peer's tip within 3 blocks of the pool's. The progress at the end is the
    running session's own: over the relay's connection in the final table, and counted from the first sample
    when that is the same session (connection and activation), else from its own start."""
    failures = []
    relay_states = [c["state"] for c in relay_connections(last)]
    if not reuses:
        failures.append("the Scala node did not reuse the relay's own connection (no reuse transition)")
    if dialled:
        failures.append(f"the Scala node dialled the relay, which is behind a firewall: {dialled[:2]}")
    if len(relay_states) != 1:
        failures.append(f"not exactly one connection to the relay at the end: {relay_states}")
    elif relay_states[0] not in DUPLEX[2:]:
        failures.append(f"the relay's connection is not in duplex use at the end: {relay_states}")
    if relay_temperature(last) != "hot":
        failures.append(f"the relay is not hot at the end: {relay_temperature(last)}")
    before, after = relay_session(first), relay_session(last)
    same = bool(before and after) and all(before.get(k) == after.get(k) for k in ("connection", "activation"))
    if not following(first):
        failures.append(f"the Scala node did not follow the relay over its connection: {before}")
    elif not over_relay_connection(last, after):
        failures.append(f"no running session over the relay's connection at the end: {after}, "
                        f"connections {[c.get('connection') for c in relay_connections(last)]}")
    elif not after.get("intersection") or after.get("headers", 0) <= (before.get("headers", 0) if same else 0):
        failures.append(f"the session on the relay's connection did not keep following: {before} -> {after}")
    elif after.get("peerTipBlock") is None or pool_tip - int(after["peerTipBlock"]) > 3:
        failures.append(f"the relay's tip seen over its connection ({after.get('peerTipBlock')}) is behind the "
                        f"pool's ({pool_tip})")
    return failures


def wait_reuse(args, log):
    """Wait until the outbound governor promoted the relay over the relay's own connection: a reuse transition
    logged, the relay hot on one connection, and its session following it (an intersection and headers).

    A relay initially behind our immutable anchor can return NoMoreIntersection normally.
    The pinned consensus Node/ExitPolicy.hs29-30 waits 120 seconds before repromotion;
    reuse the configured sync budget rather than imposing a shorter duplex-only deadline.
    """
    deadline = time.monotonic() + args.sync_timeout
    seen = None
    while time.monotonic() < deadline:
        seen = scala_connections(args)
        if (reuse_events(log) and relay_temperature(seen) == "hot" and len(relay_connections(seen)) == 1
                and following(seen)):
            return seen
        time.sleep(1)
    return seen


# a negotiated duplex connection, in any use; the governor may already have reused it, the relay being
# known from its own connection through peer sharing (`KnownPeers.hs` 96-237)
DUPLEX = ("InboundIdleSt Duplex", "InboundSt Duplex", "DuplexSt", "OutboundDupSt Ticking", "OutboundDupSt Expired")


def wait_relay_connected(args):
    """Wait for the relay's own duplex connection to the Scala node, negotiated."""
    deadline = time.monotonic() + args.sync_timeout
    seen = None
    while time.monotonic() < deadline:
        seen = scala_connections(args)
        states = [c["state"] for c in relay_connections(seen)]
        if any(state in DUPLEX for state in states):
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
        # G2: the Haskell relay connected to us. Its address becomes a local root behind a firewall (SIGHUP):
        # the outbound governor promotes it, and the only way to reach it is the relay's own connection
        duplex = args.relay_mode == "InitiatorAndResponder"
        at_sync, reused = scala_connections(args), None
        if duplex:
            connected = wait_relay_connected(args)
            print(json.dumps({"relayConnected": relay_connections(connected)}), flush=True)
            pools = json.loads((root / "genesis" / "scala-pool-roots.json").read_text())
            write_topology(root, pools + [relay_root(behind_firewall=True)])
            docker("kill", "-s", "HUP", f"{args.name}-scala", check=False)
            reused = wait_reuse(args, log)
            at_sync = reused
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
        reuses = reuse_events(log)
        dialled = [e for e in events if e["event"] == "connection" and e.get("what") == "opened"
                   and e.get("remote") == RELAY_REMOTE and e.get("provenance") == "Outbound"]
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
                "reused": [(e.get("from"), e.get("to")) for e in reuses],
                "sessionAtSync": relay_session(at_sync),
                "sessionAtEnd": relay_session(final_connections),
                "relayTemperatureAtEnd": relay_temperature(final_connections),
                "events": {k: sum(1 for e in events if e["event"] == "inbound" and e.get("state") == k)
                           for k in ("accepted", "duplex", "unidirectional", "refused", "closed")},
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
            # the outbound governor promoted the relay over the relay's own connection, and follows it there
            pool_tip = (final.get("pool1") or {}).get("block", 0)
            failures += reuse_failures(at_sync, final_connections, pool_tip, reuses, dialled)
            if summary["inbound"]["events"]["duplex"] < 1:
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

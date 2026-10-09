#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bounded relay-only transfer; observed hot peers and configured request delay, no producer fallback."""
import argparse
import copy
from datetime import datetime, timezone
import json
import re
import signal
import time
from private_cluster_transfer import TransferRunner


def events(text):
    result = []
    for line in text.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and "ns" in value and "at" in value:
            result.append(value)
    return result


def connectivity(text, own_port, expected_ports, now):
    if "ncTxSubmissionLogicVersion = TxSubmissionLogicV1" not in text:
        raise ValueError("unexpected transaction logic")
    match = re.search(r"ncTxSubmissionInitDelay = TxSubmissionInitDelay ([0-9]+)s", text)
    if not match or int(match.group(1)) != 60:
        raise ValueError("expected observed default 60-second initialization delay")
    peers = {}
    state = None
    for event in events(text):
        ns, data = event["ns"], event.get("data", {})
        if ns == "Net.ConnectionManager.Remote.ConnectionManagerCounters":
            state = data.get("state")
        connection = data.get("connectionId", {})
        remote = connection.get("remoteAddress", {})
        port = int(remote.get("port", 0))
        if port not in expected_ports:
            continue
        if remote.get("address") != "127.0.0.1" or int(connection.get("localAddress", {}).get("port", 0)) != own_port:
            raise ValueError("unexpected diagnostic connection endpoint")
        if ns == "Net.InboundGovernor.Remote.PromotedToHotRemote":
            peers[port] = datetime.fromisoformat(event["at"].replace("Z", "+00:00")).timestamp()
        elif ns.startswith("Net.InboundGovernor.Remote.") and any(word in ns for word in ["Demoted", "Terminated", "Error"]):
            peers.pop(port, None)
    if set(peers) != set(expected_ports) or not state or state.get("fullDuplex", 0) < 2:
        return None
    ages = {str(port): now - stamp for port, stamp in peers.items()}
    if any(age < 0 for age in ages.values()):
        raise ValueError("connection timestamp is in the future")
    return {"configuredDelaySeconds": 60, "hotPeerAgesSeconds": ages,
            "lastConnectionCounters": state, "delayElapsed": min(ages.values()) >= 65}


def transaction_requests(text, own_port, expected_ports):
    observed = {}
    for event in events(text):
        if event["ns"] not in ("TxSubmission.Remote.Send.RequestTxIds", "TxSubmission.Remote.Receive.RequestTxIds"):
            continue
        data = event.get("data", {})
        if data.get("msg", {}).get("kind") != "MsgRequestTxIds":
            continue
        connection = data.get("peer", {}).get("connectionId", "").split()
        if len(connection) != 2 or connection[0] != "127.0.0.1:" + str(own_port):
            continue
        for port in expected_ports:
            if connection[1] == "127.0.0.1:" + str(port):
                observed.setdefault(port, []).append(event)
    return observed if set(observed) == set(expected_ports) else {}


class RelayRunner(TransferRunner):
    def write_json(self, path, obj):
        if path == "configuration.yaml":
            if obj.get("TraceOptions") != {}:
                raise ValueError("unexpected inherited trace options")
            obj = copy.deepcopy(obj)
            obj["TraceOptions"] = {"": {"backends": ["Stdout MachineFormat"], "severity": "Notice"}}
            for name in ["TxSubmission.Remote", "TxSubmission.TxInbound", "TxSubmission.TxOutbound"]:
                obj["TraceOptions"][name] = {"severity": "Debug", "detail": "DDetailed"}
            for name in ["Mempool", "Net.ConnectionManager.Remote", "Net.InboundGovernor.Remote"]:
                obj["TraceOptions"][name] = {"severity": "Info"}
        return super().write_json(path, obj)

    def execute(self, *args, **kwargs):
        submission = args[:4] == ("cardano-cli", "conway", "transaction", "submit")
        if submission:
            if args[-1] != "/work/env/socket/node3/sock":
                raise ValueError("relay diagnostic forbids producer submission")
            self.submitted_at = {"startedUnixSeconds": time.time(), "startedMonotonicSeconds": time.monotonic()}
        result = super().execute(*args, **kwargs)
        if submission:
            self.submitted_at.update(completedUnixSeconds=time.time(), completedMonotonicSeconds=time.monotonic())
            self.save("relay-submission-timing.md", json.dumps(self.submitted_at, indent=2))
        return result

    def prepare_transfer(self):
        ports = [int(self.read(f"node-data/node{i}/port")) for i in (1, 2, 3)]
        observations = []
        until = min(self.deadline - 150, time.monotonic() + 150)
        while time.monotonic() < until:
            logs = [self.read(f"logs/node{i}/stdout.log") for i in (1, 2, 3)]
            # Evaluate event ages after the observed log reads complete.
            now = time.time()
            connected = [connectivity(logs[i-1], ports[i-1],
                         set(ports) - {ports[i-1]}, now) for i in (1, 2, 3)]
            requests = [transaction_requests(logs[i-1], ports[i-1], set(ports) - {ports[i-1]})
                        for i in (1, 2, 3)]
            tip = self.query("tip")
            observations.append({"observedUnixSeconds": now, "connections": connected, "transactionIdRequestCounts": [sum(len(es) for es in r.values()) for r in requests], "relayTip": tip})
            self.save("relay-readiness.md", json.dumps(observations, indent=2))
            # Connectivity + elapsed per-peer default + fresh early-epoch state are all required.
            if all(c and c["delayElapsed"] for c in connected) and all(requests) and tip.get("slotInEpoch", 501) <= 100:
                self.save("relay-request-readiness.md", json.dumps(requests, indent=2))
                self.save("relay-original-config.md", self.read("configuration.yaml"))
                return
            time.sleep(1)
        raise TimeoutError("observed peer/delay/epoch readiness deadline")

    def submit_producer(self):
        self.save("submission-route.md", "Relay-only submission. No direct producer submission or fallback.\n")

    def scala(self):
        result = super().scala()
        evidence = {}
        for i in (1, 2, 3):
            rows = [e for e in events(self.read(f"logs/node{i}/stdout.log"))
                    if e["ns"].startswith("TxSubmission.") or e["ns"].startswith("Mempool.")]
            evidence[str(i)] = rows
        self.save("relay-transaction-events.md", json.dumps(evidence, indent=2))
        txid = result["transfer"]["transactionId"]
        admissions = {}
        for i, rows in evidence.items():
            admissions[i] = [e for e in rows if e["ns"] == "Mempool.AddedTx"
                             and e.get("data", {}).get("tx", {}).get("txid") in (txid, txid[:8])]
        self.save("relay-admission-evidence.md", json.dumps(admissions, indent=2))
        if not admissions["3"] or not (admissions["1"] or admissions["2"]):
            raise ValueError("relay and producer admission evidence required")
        result["relayOnlySubmission"] = True
        result["observedDefaultDelayElapsed"] = True
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--seconds", type=int, default=420)
    args = parser.parse_args()
    args.capture = False
    if not 300 <= args.seconds <= 480:
        parser.error("relay diagnostic budget must be 300..480 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    RelayRunner(args).run()

if __name__ == "__main__": main()

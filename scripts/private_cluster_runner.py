#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""One bounded in-memory validator follows four original local reference successors."""
import argparse
import json
import signal
import time
from private_cluster import Runner, JDK
from private_cluster_coherent import FIXTURE
from private_cluster_relay import RelayRunner, events
from private_cluster_transfer import converged_tips
from private_cluster_sequence import (SequenceRunner, PRE_PINS, ORACLE_PINS,
    checked_window, checked_report, pair_admissions, workload_budget)

LOG_LIMIT = 12 * 1024 * 1024
TARGET = 4


def records(stdout):
    if len(stdout.encode()) > LOG_LIMIT:
        raise ValueError("observer log exceeds twelve MiB bound")
    result = []
    for line in stdout.splitlines(keepends=True):
        # A docker logs poll may end mid-record; only completed lines are authoritative.
        if not line.endswith("\n"):
            continue
        if line.startswith("{"):
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError("observer record object required")
            result.append(value)
    return result


def exact_int(value, expected):
    return type(value) is int and value == expected


def point_of(row):
    value = {"pointHash": row.get("pointHash"), "slot": row.get("slot")}
    digest = value["pointHash"]
    if (not isinstance(digest, str) or len(digest) != 64
            or any(c not in "0123456789abcdef" for c in digest)
            or type(value["slot"]) is not int or not 0 <= value["slot"] < 1 << 64):
        raise ValueError("canonical bounded point required")
    return value


def trace_chronology(rows, stop):
    trace = [r for r in rows if r.get("record") in
        ("live-validator-ready", "live-validator-download", "live-validator-applied", "live-validator-stop")]
    if not trace or trace[0].get("record") != "live-validator-ready" or trace[-1] != stop:
        raise ValueError("ready and finalized stop must bracket the complete trace")
    initial_alignment = 0
    if len(trace) > 1 and trace[1].get("phase") == "rollback-announced":
        anchor, rollback = trace[0], trace[1]
        if (rollback.get("record") != "live-validator-download" or point_of(rollback) != point_of(anchor)
                or not exact_int(rollback.get("payloadBytes"), 0) or rollback.get("appliedClaim") is not False
                or not exact_int(rollback.get("appliedRevision"), 0)
                or rollback.get("appliedStateId") != anchor.get("stateId")
                or rollback.get("scopedAppliedTip") != anchor.get("scopedAppliedTip")):
            raise ValueError("initial rollback may only repeat the unchanged supplied anchor")
        initial_alignment = 1
        trace = [trace[0]] + trace[2:]
    if not exact_int(stop.get("rollbackEvents"), initial_alignment):
        raise ValueError("rollback counter must equal the zero-or-one initial anchor alignment")
    if len(trace) != 2 + TARGET * 3:
        raise ValueError("exact announced/fetched/applied triples; no later or repeated rollback")
    previous = trace[0]
    if previous.get("scopedAppliedTip") is not None:
        raise ValueError("supplied anchor cannot be a scoped applied tip")
    payload_total = 0
    captures = [r for r in rows if r.get("record") == "transfer-range-block"]
    if len(captures) != TARGET:
        raise ValueError("four original captures must precede finalized stop")
    for ordinal in range(1, TARGET + 1):
        announced, fetched, applied = trace[1 + (ordinal-1)*3:1 + ordinal*3]
        if (announced.get("record"), announced.get("phase"), fetched.get("record"), fetched.get("phase"),
                applied.get("record")) != ("live-validator-download", "announced", "live-validator-download",
                                          "fetched", "live-validator-applied"):
            raise ValueError("download announcement, exact fetch and publication order required")
        current_point = point_of(applied)
        if current_point["slot"] <= point_of(previous)["slot"]:
            raise ValueError("straight-line applied slots must advance")
        if not exact_int(applied.get("blockNo"), previous.get("blockNo", -2) + 1):
            raise ValueError("straight-line applied block numbers must advance")
        for download, bound, field in ((announced, 65535, "headerEnvelopeHex"), (fetched, 1048576, "rawBlockHex")):
            size = download.get("payloadBytes")
            if type(size) is not int or not 0 < size <= bound:
                raise ValueError("download payload bound")
            if (point_of(download) != current_point or download.get("appliedClaim") is not False
                    or not exact_int(download.get("appliedRevision"), ordinal - 1)
                    or download.get("appliedStateId") != previous.get("stateId")
                    or download.get("scopedAppliedTip") != previous.get("scopedAppliedTip")):
                raise ValueError("download cursor must not advance scoped state")
            raw = captures[ordinal-1].get(field)
            if not isinstance(raw, str) or len(raw) != size * 2 or any(c not in "0123456789abcdef" for c in raw):
                raise ValueError("charged download differs from retained original length")
            payload_total += size
        if applied.get("scopedAppliedTip") != current_point:
            raise ValueError("only published record may advance scoped point")
        previous = applied
    if not exact_int(stop.get("returnedBytes"), payload_total) or payload_total > TARGET * (65535 + 1048576):
        raise ValueError("returned-byte accounting differs from exact original payloads")
    for name in ("announcedCursor", "fetchedCursor", "scopedAppliedTip"):
        if stop.get(name) != point_of(previous):
            raise ValueError("final download/scoped cursors differ")


def progress(rows):
    ready = [i for i, r in enumerate(rows) if r.get("record") == "live-validator-ready"]
    applied = [(i, r) for i, r in enumerate(rows) if r.get("record") == "live-validator-applied"]
    stops = [r for r in rows if r.get("record") == "live-validator-stop"]
    if len(ready) > 1 or len(stops) > 1 or (applied and (not ready or ready[0] >= applied[0][0])):
        raise ValueError("readiness must precede every applied point exactly once")
    if ready:
        first = rows[ready[0]]
        if (not exact_int(first.get("revision"), 0) or not exact_int(first.get("retainedBlocks"), 0)
                or first.get("intersectionAccepted") is not True or first.get("coordinatorChecked") is not True):
            raise ValueError("ready requires accepted intersection and pre-anchor coordinator no-op")
    for ordinal, (_, row) in enumerate(applied, 1):
        if not exact_int(row.get("retainedBlocks"), ordinal) or not exact_int(row.get("revision"), ordinal):
            raise ValueError("bounded monotonic live applied revisions required")
        if type(row.get("transactionCount")) is not int or not 0 <= row["transactionCount"] <= 16:
            raise ValueError("applied transaction count required")
    if len(applied) > TARGET:
        raise ValueError("four applied successors maximum")
    if stops:
        stop = stops[0]
        if (stop.get("typedStop") != "TargetReached" or len(applied) != TARGET
                or stop.get("resourcesFinalized") is not True or stop.get("transportCounted") is not True):
            raise ValueError("exact successful finalized four-block stop required")
        for key, expected in (("reconnects", 0), ("peerOpens", 1), ("peerCloses", 1),
                              ("transportOpens", 5), ("transportCloses", 5), ("revision", TARGET), ("retainedBlocks", TARGET)):
            if not exact_int(stop.get(key), expected):
                raise ValueError("straight-line stop counter mismatch: " + key)
        if type(stop.get("events")) is not int or not TARGET <= stop["events"] <= 64:
            raise ValueError("bounded runner event count required")
        last = applied[-1][1]
        for key in ("pointHash", "slot", "blockNo", "revision", "retainedBlocks", "stateId"):
            if stop.get(key) != last.get(key):
                raise ValueError("terminal point differs from final applied point")
        trace_chronology(rows, stop)
    return bool(ready), [r for _, r in applied], stops[0] if stops else None


def exact_post(pre, post, stop):
    window = checked_window(pre, post)
    if (window["expectedCompleteBlocks"] != TARGET or post.get("hash") != stop.get("pointHash")
            or post.get("slot") != stop.get("slot") or post.get("block") != stop.get("blockNo")):
        raise ValueError("reference post snapshot must equal the fixed runner terminal point")
    return window


def capture_lines(stdout):
    selected = []
    for line in stdout.splitlines(keepends=True):
        if line.startswith("{") and line.endswith("\n"):
            if json.loads(line).get("record") == "transfer-range-block":
                selected.append(line)
    if len(selected) != TARGET:
        raise ValueError("exact four complete original capture records required")
    return "".join(selected)


def live_report(report, stop):
    if (report.get("scope") != "live-validator-observation"
            or report.get("typedStop") != "TargetReached" or stop.get("typedStop") != "TargetReached"):
        raise ValueError("live validator final report required")
    for key in ("passed", "onlineBeforePostOracle", "downloadCursorSeparate",
                "finalTupleReferenceMatched", "resourcesFinalized"):
        if report.get(key) is not True:
            raise ValueError("missing live combined-path check: " + key)
    for key in ("fullLedgerValidated", "consensusValidated", "durableClaim", "liveForkClaim",
                "referenceSnapshotAtomic", "authenticatedSnapshot"):
        if report.get(key) is not False:
            raise ValueError("unsupported live validator claim: " + key)
    if report.get("transportCounted") is not True:
        raise ValueError("actual transport lifecycle counts required")
    for name in ("onlineRevision", "offlineInitialPassRevision"):
        if not exact_int(report.get(name), TARGET):
            raise ValueError("online and independent initial-pass revisions must be four")
    if report.get("finalStateId") != stop.get("stateId"):
        raise ValueError("live final report must retain exact stopped tuple identity")
    for name in ("peerOpens", "peerCloses", "transportOpens", "transportCloses", "events",
                 "returnedBytes", "reconnects", "rollbackEvents"):
        if not exact_int(report.get(name), stop.get(name)):
            raise ValueError("live final counter differs from stopped runner: " + name)
    if type(report.get("rollbackEvents")) is not int or report["rollbackEvents"] not in (0, 1):
        raise ValueError("only zero-or-one trace-checked initial anchor alignment is supported")
    return report


class LiveRunner(SequenceRunner):
    def start_observer(self, port):
        self.observer_attempted = True
        result = self.docker("run", "-d", "--pull=never", "--name", self.name + "-scala",
            "--network=container:" + self.name, "--cpus=1", "--memory=1g", "--memory-swap=1g",
            "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000",
            "--read-only", "--tmpfs", "/tmp:size=64m", "--log-driver=json-file",
            "--log-opt=max-size=12m", "--log-opt=max-file=1",
            "-v", str(self.args.scala_repo) + ":/work:ro", "-v", str(self.out) + ":/evidence:ro",
            "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main live-validator ' + str(port) + ' /evidence',
            timeout=5)
        self.save("live-observer-container-id.md", result.stdout)

    def observer_logs(self):
        result = self.docker("logs", "--tail", "1000", self.name + "-scala", check=False, timeout=2)
        self.save("live-validator-stdout.md", result.stdout)
        self.save("live-validator-stderr.md", result.stderr)
        if result.returncode:
            raise ValueError("owned observer logs unavailable")
        if len(result.stdout.encode()) + len(result.stderr.encode()) > LOG_LIMIT:
            raise ValueError("combined observer output exceeds twelve MiB")
        previous = getattr(self, "last_observer_stdout", "")
        if not result.stdout.startswith(previous):
            raise ValueError("observer logs truncated or rewritten")
        self.last_observer_stdout = result.stdout
        return records(result.stdout)

    def observer_state(self):
        result = self.docker("inspect", "--format", "{{json .State}}", self.name + "-scala", timeout=2)
        value = json.loads(result.stdout)
        self.save("live-observer-state.md", value)
        return value

    def await_progress(self, predicate, seconds, label):
        until = min(self.deadline, time.monotonic() + seconds)
        while time.monotonic() < until:
            rows = self.observer_logs()
            current = progress(rows)
            if predicate(current):
                self.save("live-" + label + "-timing.md", {"observedMonotonicSeconds": time.monotonic()})
                return current
            state = self.observer_state()
            if not state.get("Running"):
                raise ValueError("observer exited before " + label + ": " + str(state.get("ExitCode")))
            time.sleep(0.1)
        raise TimeoutError("bounded observer wait expired: " + label)

    def finalize_observer(self):
        previous_deadline = self.deadline
        self.deadline = None  # Cleanup remains bounded by the inherited absolute 600-second deadline.
        try:
            self._finalize_observer()
        finally:
            self.deadline = previous_deadline

    def _finalize_observer(self):
        # Only this task's fixed observer name is touched. Runner.cleanup remains the outer fallback.
        if not getattr(self, "observer_attempted", False):
            return
        try:
            try:
                self.observer_logs()
                self.observer_state()
            except BaseException as exc:
                self.save("live-observer-final-read-error.md", type(exc).__name__ + ": " + str(exc))
        finally:
            result = self.docker("rm", "-f", self.name + "-scala", check=False, timeout=5)
            self.save("live-observer-remove.md", {"exitCode": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr})
            if result.returncode:
                raise ValueError("owned observer cleanup failed")

    def scala(self):
        try:
            return self.live_sequence()
        finally:
            self.finalize_observer()

    def live_sequence(self):
        handshake = Runner.scala(self)
        RelayRunner.prepare_transfer(self)
        pair = self.build_pair()  # All expensive construction occurs with producers running.
        relay = self.read("logs/node3/stdout.log")
        if "shelleyKESSource = Nothing" not in relay or "shelleyVRFFile = Nothing" not in relay:
            raise ValueError("verified nonproducing relay required")
        self.save("relay-role.md", relay.splitlines()[0])
        readiness = []; until = min(self.deadline - 190, time.monotonic() + 75)
        while time.monotonic() < until:
            tips = [self.query("tip", i) for i in (1, 2, 3)]
            readiness.append(tips); self.save("sequence-readiness.md", readiness)
            if converged_tips(tips) and tips[-1].get("epoch", 0) >= 1 and 1 <= tips[-1].get("slotInEpoch", 501) <= 30:
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("early same-epoch sequence anchor unavailable")

        port = int(self.read("node-data/node3/port"))
        with self.paused("pre"):
            pre, before = self.snapshot("pre")
            if not 1 <= pre["slotInEpoch"] <= 80:
                raise ValueError("early pre-anchor window lost")
            utxo = json.loads(before["utxo"])
            if any(utxo.get(s["input"]) != s["originalInput"] for s in pair):
                raise ValueError("prepared pair differs from exact pre-state")
            self.save("transfer-genesis.md", self.read("shelley-genesis.json"))
            self.manifest("coherent-sequence-context.md", "coherent-sequence-context-v1", PRE_PINS)
            self.start_observer(port)
            self.await_progress(lambda p: p[0], 10, "ready")
            ready = [r for r in records(self.last_observer_stdout) if r.get("record") == "live-validator-ready"][0]
            if (ready.get("pointHash"), ready.get("slot"), ready.get("blockNo")) != (pre["hash"], pre["slot"], pre["block"]):
                raise ValueError("live ready intersection differs from supplied pre-anchor")
        _, applied, stop = self.await_progress(lambda p: bool(p[1]), 18, "first-applied")
        if stop is not None or len(applied) != 1 or applied[0]["transactionCount"] != 0:
            raise ValueError("exact first applied empty successor required before pair submission")
        with self.paused("submission"):
            for index, selection in enumerate(pair):
                started = time.monotonic()
                result = self.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file",
                    selection["signedPath"], "--testnet-magic", "1082026", "--socket-path",
                    "/work/env/socket/node3/sock", timeout=3)
                self.save(f"sequence-submission-{index}.md", result.stdout + result.stderr)
                self.save(f"sequence-submission-{index}-timing.md", {"startedMonotonicSeconds": started,
                    "completedMonotonicSeconds": time.monotonic(), "transactionId": selection["transactionId"]})
            until = min(self.deadline, time.monotonic() + 2)
            while time.monotonic() < until:
                rows = events(self.read("logs/node3/stdout.log"))
                admitted = {s["transactionId"]: [e for e in rows if e["ns"] == "Mempool.AddedTx"
                    and e.get("data", {}).get("tx", {}).get("txid") in (s["transactionId"],s["transactionId"][:8])]
                    for s in pair}
                self.save("sequence-relay-admissions.md", admitted)
                if all(admitted.values()):
                    break
                time.sleep(0.1)
            else:
                raise ValueError("both relay admissions required before resuming producers")

        _, _, stop = self.await_progress(lambda p: p[2] is not None, 25, "target-reached")
        # Stop immediately; advancing the endpoint or extending the target would change the test.
        with self.paused("post"):
            post, after = self.snapshot("post")
            window = exact_post(pre, post, stop)
            if before["parameters"] != after["parameters"]:
                raise ValueError("parameters changed")
            self.save("sequence-window.md", window)
        self.save("scala-sequence-capture.md", capture_lines(self.last_observer_stdout))
        # Manifest is the last file: its presence signals complete pinned oracle availability.
        self.manifest("coherent-sequence-oracle.pending.md", "coherent-sequence-oracle-v1", ORACLE_PINS)
        destination = self.out / "coherent-sequence-oracle.md"
        if destination.exists():
            raise ValueError("oracle signal already exists")
        (self.out / "coherent-sequence-oracle.pending.md").replace(destination)
        evidence = {str(i): [e for e in events(self.read(f"logs/node{i}/stdout.log"))
            if e["ns"].startswith("Mempool.") or e["ns"].startswith("TxSubmission.")] for i in (1,2,3)}
        self.save("sequence-transaction-events.md", evidence)
        self.save("sequence-pair-admissions.md", pair_admissions(evidence, [s["transactionId"] for s in pair]))
        until = min(self.deadline - 100, time.monotonic() + 65)
        while time.monotonic() < until:
            state = self.observer_state()
            if not state.get("Running"):
                break
            time.sleep(0.2)
        else:
            raise TimeoutError("live observer final comparison deadline")
        rows = self.observer_logs()
        progress(rows)
        if state.get("ExitCode") != 0 or state.get("OOMKilled") is not False:
            raise ValueError("live observer exited unsuccessfully")
        coherent = [r for r in rows if r.get("scope") == "coherent-sequence-observation"]
        live = [r for r in rows if r.get("scope") == "live-validator-observation"]
        if len(coherent) != 1 or len(live) != 1:
            raise ValueError("exact coherent replay and live runner reports required")
        report = checked_report(coherent[0], TARGET)
        if report.get("transactionIdsInBlockOrder") != [pair[i]["transactionId"]
                for i in report["submissionIndexesInBlockOrder"]]:
            raise ValueError("original block IDs differ from exact submitted transaction identities")
        live_result = live_report(live[0], stop)
        return {"handshake": handshake, "sequence": report, "liveValidator": live_result,
                "fixtureProfile": FIXTURE, "singleAcquiredSnapshot": False,
                "relayOnlySubmission": True, "prefixRollbackReferenceCompared": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-profile", required=True, choices=[FIXTURE])
    parser.add_argument("--reference-image", required=True); parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True); parser.add_argument("--seconds", type=int, default=480)
    args = parser.parse_args(); args.capture = False
    try: workload_budget(args.seconds)
    except ValueError as exc: parser.error(str(exc))
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    LiveRunner(args).run()


if __name__ == "__main__": main()

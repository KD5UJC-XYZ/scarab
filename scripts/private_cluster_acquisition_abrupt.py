#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Abrupt Scala process death after acknowledged publication, never during a write."""
import argparse
import hashlib
import json
import signal
import struct
import time

from private_cluster_acquisition_restart import AcquisitionRestartRunner


def verify_ack(rows, raw, context):
    acknowledgements = [r for r in rows if r.get("record") == "acknowledged-hold"]
    reports = [r for r in rows if r.get("scope") == "bounded-acquisition-process-phase"]
    originals = [r for r in rows if r.get("record") == "original" and r.get("stage") == "published"]
    if len(acknowledgements) != 1 or len(reports) != 1 or len(originals) != 2:
        raise ValueError("missing or duplicate acknowledgement evidence")
    ack, report = acknowledgements[0], reports[0]
    if (report.get("phase") != "a" or report.get("complete") is not True or
            report.get("loadedCount") != 0 or report.get("publishedCount") != 2 or
            any(report.get(k) != v for k, v in context.items()) or
            any(report.get(k) is not False for k in ("ledgerValidated", "consensusValidated", "segmentStoreReused")) or
            ack.get("generation") != report.get("publishedGeneration") or
            ack.get("digest") != report.get("publishedRevision")):
        raise ValueError("acknowledgement differs from independent pins or phase")
    field = lambda b: struct.pack(">i", len(b)) + b
    text = lambda s: field(str(s).encode())
    payload = b"".join(text(s) for s in ("acquisition-checkpoint-v1", context["profile"],
        context["upstreamSource"], context["genesisDigest"]))
    payload += struct.pack(">q", context["networkMagic"]) + text(context["anchor"]["slot"])
    payload += field(bytes.fromhex(context["anchor"]["hash"]))
    payload += struct.pack(">qi", ack["generation"], 2)
    for index, original in enumerate(originals):
        if original.get("index") != index: raise ValueError("original ordering differs")
        payload += field(bytes.fromhex(original["headerEnvelopeHex"])) + field(bytes.fromhex(original["blockHex"]))
    digest = hashlib.sha256(payload).hexdigest()
    if digest != ack["digest"] or raw != payload + digest.encode():
        raise ValueError("acknowledged checkpoint bytes differ")
    return {"generation": ack["generation"], "digest": digest,
            "fileSha256": hashlib.sha256(raw).hexdigest(), "originalCount": 2}


class AbruptAcquisitionRunner(AcquisitionRestartRunner):
    def scala_run(self, args, label, network):
        if args[0] == "a": args = ["a-hold", *args[1:]]
        return super().scala_run(args, label, network)

    def await_phase(self, cid, args, label):
        if args[0] != "a-hold": return super().await_phase(cid, args, label)
        deadline = time.monotonic() + 55
        while time.monotonic() < deadline:
            captured = self.docker("logs", cid)
            logs = captured.stdout + captured.stderr
            if len(logs) > 20 * 1024 * 1024: raise ValueError("acknowledgement output bound")
            rows = [json.loads(line) for line in logs.splitlines() if line.startswith("{")]
            if any(r.get("record") == "acknowledged-hold" for r in rows): break
            state = json.loads(self.docker("inspect", cid).stdout)[0]
            if not state["State"]["Running"]: raise ValueError("A exited before acknowledgement")
            time.sleep(0.2)
        else: raise ValueError("acknowledgement timeout")
        raw = (self.out / "acquisition/state/checkpoint.bin").read_bytes()
        expected = verify_ack(rows, raw, self.phase_context)
        before = json.loads(self.docker("inspect", cid).stdout)[0]
        if before["Id"] != cid or not before["State"]["Running"] or before["State"]["Pid"] <= 0:
            raise ValueError("acknowledged owner no longer running")
        # Host receipt is outside the checkpoint mount; verify before targeting immutable ID.
        self.save("acknowledgement-before-kill.md", {"expected": expected, "containerId": cid,
            "hostPid": before["State"]["Pid"], "verifiedWhileRunning": True, "records": rows})
        killed = self.docker("kill", "--signal=KILL", cid).stdout.strip()
        if killed != cid: raise ValueError("kill receipt identity differs")
        status = self.docker("wait", cid, timeout=10).stdout.strip()
        after = json.loads(self.docker("inspect", cid).stdout)[0]
        if status != "137" or after["State"]["Running"] or after["State"].get("OOMKilled"):
            raise ValueError("expected explicit SIGKILL termination missing")
        if (self.out / "acquisition/state/checkpoint.bin").read_bytes() != raw:
            raise ValueError("checkpoint changed after acknowledgement")
        self.save("acknowledged-kill.md", {"scope": "abrupt-process-death-after-acknowledged-publication",
            "containerId": cid, "signal": "SIGKILL", "exitCode": 137, "oomKilled": False,
            "expected": expected, "checkpointUnchanged": True, "after": after,
            "duringPublication": False, "powerLossRecovery": False})
        return "137"

    def scala(self):
        report = super().scala()
        report["scope"] = "abrupt-process-death-after-acknowledged-publication"
        report["duringPublication"] = False
        self.save("acquisition-restart-result.md", report)
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("reference-image", "scala-repo", "output"): parser.add_argument("--" + name, required=True)
    parser.add_argument("--seconds", type=int, default=420)
    args = parser.parse_args()
    if not 300 <= args.seconds <= 480: parser.error("budget must be 300..480 seconds")
    args.capture = False
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    AbruptAcquisitionRunner(args).run()


if __name__ == "__main__": main()

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Paired private minimum-output boundary, retaining relay isolation and cleanup."""
import argparse
import json
import signal
from private_cluster import JDK
from private_cluster_relay import RelayRunner


def validate_pair(positive, negative):
    if positive.get("profile") != "conway-pv9-testnet-ada-minimum-output-v1" or negative.get("profile") != positive["profile"]:
        raise ValueError("minimum-output profile mismatch")
    p, n = positive["outputs"], negative["outputs"]
    if len(p) != 2 or len(n) != 2 or not positive["satisfied"] or negative["satisfied"]:
        raise ValueError("paired minimum-output outcomes differ")
    if (p[0]["coin"], n[0]["coin"], p[0]["required"], n[0]["required"]) != (849070, 849069, 849070, 849070):
        raise ValueError("actual reference context differs from planned boundary")
    if p[0]["serializedBytes"] != 37 or n[0]["serializedBytes"] != 37:
        raise ValueError("destination byte width differs")
    if not all(x["originalHex"].startswith("82") for x in p + n):
        raise ValueError("expected original array outputs")
    if not p[1]["satisfied"] or not n[1]["satisfied"] or n[1]["coin"] != p[1]["coin"] + 1:
        raise ValueError("change must compensate exactly one lovelace")
    if p[1]["serializedBytes"] != n[1]["serializedBytes"]:
        raise ValueError("change byte width differs")


class MinimumOutputRunner(RelayRunner):
    transfer_amount = 849070

    def predicate(self, filename, expected_code):
        result = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
            "--network=none", "--cpus=1", "--memory=1g", "--memory-swap=1g", "--pids-limit=128",
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000",
            "--read-only", "--tmpfs", "/tmp:size=64m", "-v", str(self.args.scala_repo) + ":/work:ro",
            "-v", str(self.out) + ":/evidence:ro", "-w", "/work", "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main minimum-output /evidence/pre-parameters.md /evidence/' + filename,
            check=False, timeout=20)
        self.save(filename + "-predicate.md", result.stdout + result.stderr)
        if result.returncode != expected_code:
            raise ValueError("unexpected Scala minimum-output result")
        return json.loads(result.stdout.strip())

    def before_submit(self):
        selection = json.loads((self.out / "transfer-selection.md").read_text())
        if selection["amount"] != self.transfer_amount or selection["fee"] != 200000:
            raise ValueError("unexpected positive selection")
        self.execute("cardano-cli", "conway", "transaction", "build-raw", "--tx-in", selection["input"],
            "--tx-out", selection["destination"] + "+849069", "--tx-out",
            selection["changeAddress"] + "+" + str(selection["change"] + 1),
            "--fee", "200000", "--out-file", "/work/below.body")
        self.execute("cardano-cli", "conway", "transaction", "sign", "--tx-body-file", "/work/below.body",
            "--signing-key-file", "/work/env/utxo-keys/utxo1/utxo.skey", "--testnet-magic", "1082026",
            "--out-file", "/work/below.signed")
        signed = json.loads(self.execute("cat", "/work/below.signed").stdout)
        self.save("below-transaction-cbor.md", signed["cborHex"])
        positive = self.predicate("signed-transaction-cbor.md", 0)
        negative = self.predicate("below-transaction-cbor.md", 1)
        validate_pair(positive, negative)
        self.save("minimum-output-pair.md", json.dumps({"positive": positive, "negative": negative}, indent=2))
        self.pause_evidence()
        rejected = self.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", "/work/below.signed",
            "--testnet-magic", "1082026", "--socket-path", "/work/env/socket/node3/sock", check=False)
        output = rejected.stdout + rejected.stderr
        self.save("below-reference-rejection.md", output)
        if rejected.returncode == 0 or "OutputTooSmallUTxO" not in output or "849070" not in output:
            raise ValueError("reference must reject with exact minimum-output boundary")
        self.pause_evidence()
        raw = self.relay_query("utxo", "--whole-utxo", "--output-cbor-hex")
        self.save("after-rejection-utxo-cbor.md", raw)
        if raw != (self.out / "pre-utxo-cbor.md").read_text():
            raise ValueError("reference UTxO changed after rejection")
        tip = self.query("tip")
        self.save("after-rejection-tip.md", json.dumps(tip, indent=2))
        pre = json.loads((self.out / "pre-tips.md").read_text())[0]
        if any(tip[k] != pre[k] for k in ("hash", "slot", "epoch", "block")):
            raise ValueError("reference point changed after rejection")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--reference-image", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--scala-repo", required=True)
    p.add_argument("--seconds", type=int, default=420)
    args = p.parse_args(); args.capture = False
    if not 300 <= args.seconds <= 480: p.error("budget must be 300..480 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    MinimumOutputRunner(args).run()

if __name__ == "__main__": main()

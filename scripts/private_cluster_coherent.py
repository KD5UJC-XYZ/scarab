#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Explicit fresh-genesis, complete-state coherent branch reference fixture."""
import argparse
import copy
import hashlib
import json
import signal
from private_cluster import JDK
from private_cluster_interval import IntervalRunner, check_inclusion_report
from private_cluster_relay import RelayRunner

FIXTURE = "conway-pv9-empty-byron-allocations-coherent-v1"
GENESIS = ("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json")
INPUTS = {
    "genesisSha256": "transfer-genesis.md", "preTipsSha256": "pre-tips.md",
    "preProtocolSha256": "pre-protocol-state.md", "preLedgerSha256": "pre-ledger-state.md",
    "preParametersSha256": "pre-parameters.md", "preUtxoSha256": "pre-utxo.md",
    "preUtxoCborSha256": "pre-utxo-cbor.md", "transactionSha256": "signed-transaction-cbor.md",
    "captureSha256": "scala-transfer.md"}


def sha(raw):
    return hashlib.sha256(raw.encode() if isinstance(raw, str) else raw).hexdigest()


def shelley_funds(genesis):
    if genesis.get("networkId") != "Testnet" or genesis.get("networkMagic") != 1082026:
        raise ValueError("isolated generated Shelley network required")
    funds = genesis["extraConfig"]["initialFunds"]["data"]
    credentials = genesis["extraConfig"]["stakeCredentials"]["data"]
    pools = genesis["extraConfig"]["stakePools"]["data"]
    if not isinstance(funds, dict) or len(funds) != 6 or not credentials or len(pools) != 2:
        raise ValueError("expected six supplied Shelley funds and two producer pools")
    for address, amount in funds.items():
        try:
            raw = bytes.fromhex(address)
        except (ValueError, TypeError):
            raise ValueError("canonical raw genesis address required") from None
        if (raw.hex() != address or not raw or raw[0] & 15 != 0 or
                (raw[0] >> 4, len(raw)) not in ((0, 57), (6, 29)) or
                type(amount) is not int or amount <= 0):
            raise ValueError("supported generated key-payment allocation required")
    if sum(funds.values()) > genesis["maxLovelaceSupply"]:
        raise ValueError("initial funds exceed supply")
    return funds


def fresh_genesis(byron, shelley):
    """Only a new network's initial allocation configuration, never a UTxO projection."""
    shelley_funds(shelley)
    if byron["protocolConsts"]["protocolMagic"] != 1082026 or byron.get("avvmDistr") != {}:
        raise ValueError("expected empty AVVM private genesis")
    balances = byron.get("nonAvvmBalances")
    if (not isinstance(balances, dict) or len(balances) != 2 or
            any(str(v) != "3000000000" for v in balances.values())):
        raise ValueError("expected two generated bootstrap allocations")
    effective = copy.deepcopy(byron)
    effective["nonAvvmBalances"] = {}
    return effective


def complete_utxo(genesis, queried, decoded_addresses):
    expected = shelley_funds(genesis)
    if not isinstance(queried, dict) or len(queried) != len(expected):
        raise ValueError("complete reference UTxO entry count differs from genesis")
    actual = {}
    for ref, output in queried.items():
        if (not isinstance(ref, str) or not ref.endswith("#0") or len(ref) != 66 or
                any(c not in "0123456789abcdef" for c in ref[:-2])):
            raise ValueError("genesis reference expected")
        address = output["address"]
        if address not in decoded_addresses:
            raise ValueError("missing original reference address decoding")
        raw = decoded_addresses[address]
        value = output["value"]
        if raw in actual or set(value) != {"lovelace"} or type(value["lovelace"]) is not int:
            raise ValueError("duplicate address or unsupported complete output")
        if any(output.get(k) is not None for k in
               ("datum", "datumhash", "inlineDatum", "inlineDatumRaw", "referenceScript")):
            raise ValueError("unexpected datum/reference script in initial state")
        actual[raw] = value["lovelace"]
    if actual != expected:
        raise ValueError("complete reference UTxO differs from all effective genesis funds")
    return {"entries": len(actual), "totalLovelace": sum(actual.values()),
            "allGenesisAllocationsMatched": True, "filtered": False}


class CoherentRunner(IntervalRunner):
    def prepare_genesis(self):
        if self.args.fixture_profile != FIXTURE:
            raise ValueError("explicit fresh fixture profile required")
        if hasattr(self, "generated_genesis"):
            raise ValueError("fixture already configured")
        # create-env has completed in a new owned tmpfs; reject any existing node database.
        for i in (1, 2, 3):
            self.execute("test", "!", "-e", f"/work/env/node-data/node{i}/db")
        self.generated_genesis = {name: self.read(name) for name in GENESIS}
        for name, raw in self.generated_genesis.items():
            self.save("generated-" + name + ".md", raw)
        self.save("generated-configuration.md", self.read("configuration.yaml"))
        configuration = json.loads(self.read("configuration.yaml"))
        if "ByronGenesisHash" in configuration:
            raise ValueError("unexpected pinned Byron hash: explicit recomputation needed")
        effective = fresh_genesis(json.loads(self.generated_genesis[GENESIS[0]]),
                                  json.loads(self.generated_genesis[GENESIS[1]]))
        self.write_json("byron-genesis.json", effective)

    def write_json(self, path, obj):
        result = super().write_json(path, obj)
        if path == "configuration.yaml":
            effective = {name: self.read(name) for name in GENESIS}
            original = {name: json.loads(raw) for name, raw in self.generated_genesis.items()}
            expected = copy.deepcopy(original)
            expected[GENESIS[0]]["nonAvvmBalances"] = {}
            expected[GENESIS[1]]["protocolParams"]["protocolVersion"] = {"major": 9, "minor": 0}
            if {n: json.loads(v) for n, v in effective.items()} != expected:
                raise ValueError("unexpected effective genesis mutation")
            for name, raw in effective.items():
                self.save("effective-" + name + ".md", raw)
            self.effective_shelley = json.loads(effective[GENESIS[1]])
            self.save("coherent-fixture.md", {"profile": FIXTURE,
                "generatedSha256": {n: sha(v) for n, v in self.generated_genesis.items()},
                "effectiveSha256": {n: sha(v) for n, v in effective.items()},
                "freshDatabaseRequired": True, "byronAllocations": 0,
                "shelleyFundsAndStakingPreserved": True,
                "fileSha256IsByronCanonicalGenesisHash": False})
        return result

    def snapshot(self, label):
        result = super().snapshot(label)
        if label == "pre":
            queried = json.loads(result[1]["utxo"])
            decoded = {}
            for index, output in enumerate(queried.values()):
                address = output["address"]
                receipt = self.execute("cardano-cli", "address", "info", "--address", address).stdout
                self.save("genesis-address-info-" + str(index) + ".md", receipt)
                decoded[address] = json.loads(receipt)["base16"]
            self.save("complete-genesis-utxo.md", complete_utxo(self.effective_shelley, queried, decoded))
        return result

    def before_submit(self):
        pass  # This profile observes one positive transfer; interval negatives remain separate.

    def scala(self):
        self.negative_started = True
        result = RelayRunner.scala(self)
        records = [json.loads(line) for line in (self.out / "scala-transfer.md").read_text().splitlines()
                   if line.startswith("{")]
        self.save("interval-inclusion.md", check_inclusion_report(records, self.plan, result["transfer"]))
        pre = self.out / "coherent-input"
        pre.mkdir(exist_ok=False)
        for name in INPUTS.values():
            (pre / name).write_bytes((self.out / name).read_bytes())
        (pre / "coherent-branch-input.md").write_text("format\tcoherent-branch-input-v1\n" +
            "".join(k + "\t" + sha((pre / n).read_bytes()) + "\n" for k, n in sorted(INPUTS.items())))
        oracle = {"postUtxoCborSha256": "post-utxo-cbor.md", "postLedgerSha256": "post-ledger-state.md",
                  "postProtocolSha256": "post-protocol-state.md", "postTipsSha256": "post-tips.md"}
        self.save("coherent-branch-oracle.md", "format\tcoherent-branch-oracle-v1\n" +
            "".join(k + "\t" + sha((self.out / n).read_bytes()) + "\n" for k, n in sorted(oracle.items())))
        receipt = self.docker("run", "--rm", "--pull=never", "--name", self.name + "-scala",
            "--network=none", "--cpus=1", "--memory=1g", "--memory-swap=1g", "--pids-limit=128",
            "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "1000:1000",
            "--read-only", "--tmpfs", "/tmp:size=64m", "-v", str(self.args.scala_repo) + ":/work:ro",
            "-v", str(pre) + ":/input:ro", "-v", str(self.out) + ":/oracle:ro", "-w", "/work",
            "--entrypoint=/bin/sh", JDK, "-c",
            'exec java -XX:ActiveProcessorCount=1 -Xmx512m -cp "$(cat app/target/runtime-classpath.txt)" lab.Main coherent-branch /input /oracle',
            check=False, timeout=60)
        self.save("coherent-observation.md", receipt.stdout + receipt.stderr)
        if receipt.returncode:
            raise ValueError("coherent independent observation failed")
        reports = [json.loads(line) for line in receipt.stdout.splitlines() if line.startswith("{")]
        if len(reports) != 1:
            raise ValueError("one coherent report required")
        report = reports[0]
        if (report.get("scopedSuccess") is not True or report.get("independentStateDerived") is not True
                or report.get("referencePostStateMatched") is not True
                or report.get("wholeTupleRollbackReapply") is not True
                or report.get("fullLedgerValidated") is not False
                or report.get("consensusValidated") is not False):
            raise ValueError("coherent scoped result missing")
        result["coherent"] = report
        result["fixtureProfile"] = FIXTURE
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-profile", required=True, choices=[FIXTURE])
    parser.add_argument("--reference-image", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--scala-repo", required=True)
    parser.add_argument("--seconds", type=int, default=480)
    args = parser.parse_args()
    args.capture = False
    if not 360 <= args.seconds <= 540:
        parser.error("coherent fixture workload budget must be 360..540 seconds")
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    CoherentRunner(args).run()


if __name__ == "__main__":
    main()

# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the reference-only Dijkstra cluster (`devnet/dijkstra.py`, #54): plan, genesis spec, node
configuration, topology and the chain capture. No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import hashlib
import json
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, cborspan, cli, dijkstra, util  # noqa: E402

FIXTURE = util.REPO / "fixtures/dijkstra-devnet"


def fixture_blocks():
    rows = [line.rstrip("\n").split("\t") for line in (FIXTURE / "blocks.tsv").read_text().splitlines()[1:]]
    return rows, [bytes.fromhex(r[4]) for r in rows]


def conway_block(block_no, slot, bodies):
    header = [[block_no, slot, b"p" * 32, b"i" * 32, b"v" * 32, [b"o" * 64, b"r" * 80], 0, b"h" * 32,
               [b"k" * 32, 0, 0, b"s" * 64], [12, 0]], b"x" * 448]
    return cbor.enc([7, [header, bodies, [{} for _ in bodies], {}, []]])


class PlanTest(unittest.TestCase):
    def test_conway_from_genesis_then_dijkstra(self):
        self.assertEqual(dijkstra.dijkstra_plan(1, 12), [("conway", 0, 12), ("dijkstra", 1, 12)])

    def test_dijkstra_needs_a_conway_epoch_first(self):
        with self.assertRaises(ValueError):
            dijkstra.dijkstra_plan(0, 12)

    def test_the_image_is_pinned_by_its_digest(self):
        self.assertTrue(dijkstra.LEIOS_IMAGE.endswith("@" + dijkstra.LEIOS_DIGEST))
        self.assertTrue(dijkstra.image_digest_ok([f"ghcr.io/x/y@{dijkstra.LEIOS_DIGEST}"]))
        self.assertFalse(dijkstra.image_digest_ok(["ghcr.io/x/y@sha256:" + "0" * 64]))
        self.assertFalse(dijkstra.image_digest_ok(None))

    def test_the_pin_registry_names_the_node_and_this_script(self):
        pins = json.loads((util.REPO / "docs/data/reference-pins.json").read_text())["pins"]
        pin = next(p for p in pins if p.get("revision") == dijkstra.LEIOS_NODE_REVISION)
        self.assertIn("scripts/devnet/dijkstra.py", pin["dependents"])


class GenesisSpecTest(unittest.TestCase):
    IMAGE = {"activeSlotsCoeff": 0.05, "epochLength": 21600, "genDelegs": {"a": {}}, "initialFunds": {"x": 1},
             "staking": {"pools": {"p": {}}}, "extraConfig": {"y": 1}, "networkMagic": 164,
             "securityParam": 108, "slotLength": 1, "maxKESEvolutions": 62,
             "protocolParams": {"minFeeA": 44, "protocolVersion": {"major": 12, "minor": 0}}}

    def test_the_testnet_funds_delegates_and_stake_are_removed(self):
        spec = dijkstra.shelley_spec(self.IMAGE, 200, 0.1, 0.2, 5, 12)
        self.assertNotIn("extraConfig", spec, "create-testnet-data writes its own pools and delegations")
        self.assertEqual(spec["genDelegs"], {}, "genDelegs must be present or the spec does not decode")
        self.assertEqual(spec["initialFunds"], {})
        self.assertEqual(spec["staking"], {"pools": {}, "stake": {}})

    def test_the_devnet_timing_magic_and_protocol_version(self):
        spec = dijkstra.shelley_spec(self.IMAGE, 200, 0.1, 0.2, 5, 10)
        self.assertEqual((spec["epochLength"], spec["slotLength"], spec["activeSlotsCoeff"], spec["securityParam"]),
                         (200, 0.1, 0.2, 5))
        self.assertEqual(spec["networkMagic"], util.MAGIC)
        self.assertEqual(spec["protocolParams"]["protocolVersion"], {"major": 10, "minor": 0})
        self.assertEqual(spec["protocolParams"]["minFeeA"], 44, "other parameters are the testnet's")
        self.assertEqual(spec["maxKESEvolutions"], 62)
        self.assertEqual(self.IMAGE["protocolParams"]["protocolVersion"]["major"], 12, "the input is not changed")

    def test_the_command_reads_the_image_genesis_and_makes_funded_pools(self):
        command = dijkstra.create_testnet_data_command(3, "2026-11-04T22:00:00Z")
        for part in ("cardano-cli dijkstra genesis create-testnet-data", "--pools 3", "--stake-delegators 3",
                     "--utxo-keys 1", f"--testnet-magic {util.MAGIC}", "--start-time 2026-11-04T22:00:00Z",
                     "--spec-dijkstra /app/config/dijkstra-genesis.json"):
            self.assertIn(part, command)


class NodeConfigTest(unittest.TestCase):
    IMAGE = {"MinNodeVersion": "11.1.0.164-prototype-2026w42", "Protocol": "Cardano",
             "ExperimentalHardForksEnabled": False, "TestDijkstraHardForkAtEpoch": 0, "TestConwayHardForkAtEpoch": 0,
             "ShelleyGenesisHash": "old",
             "TraceOptions": {"": {"backends": ["EKGBackend", "Forwarder", "PrometheusSimple suffix 127.0.0.1 12798",
                                                "Stdout MachineFormat"], "severity": "Notice"},
                              "ChainDB": {"severity": "Info"}}}
    HASHES = {f: f"hash-of-{f}" for f in dijkstra.GENESIS_FILES}

    def config(self, epoch=1):
        return dijkstra.node_config(self.IMAGE, self.HASHES, epoch)

    def test_every_era_from_genesis_but_dijkstra(self):
        config = self.config(2)
        for era in ("Shelley", "Allegra", "Mary", "Alonzo", "Babbage", "Conway"):
            self.assertEqual(config[f"Test{era}HardForkAtEpoch"], 0, era)
        self.assertEqual(config["TestDijkstraHardForkAtEpoch"], 2)

    def test_experimental_hard_forks_are_on(self):
        config = self.config()
        self.assertIs(config["ExperimentalHardForksEnabled"], True,
                      "without it the node's protocol version is 11.0 and Dijkstra is not entered")
        self.assertIs(config["ExperimentalProtocolsEnabled"], True)

    def test_the_genesis_hashes_are_this_networks(self):
        config = self.config()
        for f in dijkstra.GENESIS_FILES:
            era = f.split("-")[0].capitalize()
            self.assertEqual(config[f"{era}GenesisFile"], f)
            self.assertEqual(config[f"{era}GenesisHash"], f"hash-of-{f}")

    def test_no_min_node_version_and_no_shared_ports(self):
        config = self.config()
        self.assertNotIn("MinNodeVersion", config)
        self.assertEqual(config["TraceOptions"][""]["backends"], ["Stdout MachineFormat"])
        self.assertEqual(config["TraceOptions"][""]["severity"], "Notice")
        self.assertEqual(config["TraceOptions"]["ChainDB"], {"severity": "Info"})
        self.assertIn("PrometheusSimple suffix 127.0.0.1 12798", self.IMAGE["TraceOptions"][""]["backends"],
                      "the input is not changed")

    def test_topology_trusts_the_other_pools_only(self):
        t = dijkstra.topology(2, 3)
        roots = t["localRoots"][0]
        self.assertEqual([p["port"] for p in roots["accessPoints"]], [3001, 3003])
        self.assertTrue(roots["trustable"], "a node without trustable peers refuses to start")
        self.assertEqual(roots["valency"], 2)
        self.assertIsNone(t["bootstrapPeers"])
        self.assertEqual(t["useLedgerAfterSlot"], -1)

    def test_node_command_uses_its_own_directory_port_and_keys(self):
        command = dijkstra.node_command(2)
        self.assertTrue(command.startswith(f"cd {util.ENV}/node-data/node2 && "),
                        "the Leios database is opened in the working directory")
        self.assertIn("--port 3002", command)
        self.assertIn("--shelley-kes-key /work/env/pools-keys/pool2/kes.skey", command)
        self.assertIn(f"--socket-path {util.ENV}/socket/node2/sock", command)


class WaitTest(unittest.TestCase):
    def test_finished_once_the_kept_blocks_are_immutable(self):
        tx = {"includedBy": {"block": 37}}
        self.assertFalse(dijkstra.finished(tx, {"block": 45}, 3, 5))
        self.assertTrue(dijkstra.finished(tx, {"block": 46}, 3, 5))
        self.assertFalse(dijkstra.finished({"id": "t"}, {"block": 100}, 3, 5), "not before the inclusion is seen")

    def test_txid_text_or_json(self):
        self.assertEqual(dijkstra.parse_txid('{"txhash": "ab"}'), "ab")
        self.assertEqual(dijkstra.parse_txid("ab"), "ab")


class CaptureTest(unittest.TestCase):
    def test_a_chunk_splits_into_blocks_and_drops_a_cut_tail(self):
        a, b = conway_block(0, 1, []), conway_block(1, 3, [])
        self.assertEqual(dijkstra.split_chunk(a + b), [a, b])
        self.assertEqual(dijkstra.split_chunk(a + b[:-5]), [a])
        self.assertEqual(dijkstra.split_chunk(b""), [])

    def test_a_conway_block_is_described_from_its_original_bytes(self):
        body = {0: [], 1: [], 2: 0}
        raw = conway_block(4, 9, [body])
        d = dijkstra.describe_block(raw)
        header = cbor.enc([[4, 9, b"p" * 32, b"i" * 32, b"v" * 32, [b"o" * 64, b"r" * 80], 0, b"h" * 32,
                            [b"k" * 32, 0, 0, b"s" * 64], [12, 0]], b"x" * 448])
        self.assertEqual((d["tag"], d["era"], d["blockNo"], d["slot"]), (7, "conway", 4, 9))
        self.assertEqual(d["header"], header.hex())
        self.assertEqual(d["headerHash"], hashlib.blake2b(header, digest_size=32).hexdigest())
        self.assertEqual(d["txIds"], [hashlib.blake2b(cbor.enc(body), digest_size=32).hexdigest()])

    def test_another_era_is_refused(self):
        with self.assertRaises(ValueError):
            dijkstra.describe_block(cbor.enc([6, [[[0, 1], b""], [], [], {}, []]]))

    def test_the_captured_dijkstra_block_holds_the_transaction(self):
        rows, blocks = fixture_blocks()
        manifest = json.loads((FIXTURE / "manifest.json").read_text())
        tx = manifest["transaction"]
        d = dijkstra.describe_block(blocks[tx["blockNo"]])
        self.assertEqual((d["tag"], d["era"], d["slot"]), (8, "dijkstra", tx["slot"]))
        self.assertEqual(d["txIds"], [tx["id"]])
        self.assertEqual(d["headerHash"], rows[tx["blockNo"]][3])

    def test_cut_chain_keeps_origin_to_blocks_after_the_transaction(self):
        rows, blocks = fixture_blocks()
        txid = json.loads((FIXTURE / "manifest.json").read_text())["transaction"]["id"]
        kept, described, at = dijkstra.cut_chain(blocks + [blocks[-1]], txid, 3)
        self.assertEqual(at, 37)
        self.assertEqual(len(kept), 41)
        with self.assertRaises(ValueError, msg="not enough blocks after the transaction"):
            dijkstra.cut_chain(blocks, txid, 4)
        with self.assertRaises(ValueError, msg="the transaction is not in the chain"):
            dijkstra.cut_chain(blocks, "00" * 32, 0)
        with self.assertRaises(ValueError, msg="a gap in the chain"):
            dijkstra.cut_chain(blocks[:10] + blocks[11:], txid, 3)

    def test_the_fixture_files_agree_with_the_capture_format(self):
        rows, blocks = fixture_blocks()
        described = [dijkstra.describe_block(b) for b in blocks]
        text = (FIXTURE / "blocks.tsv").read_text()
        self.assertEqual(dijkstra.blocks_tsv(described, blocks), text)
        self.assertEqual(dijkstra.headers_tsv(described), (FIXTURE / "headers.tsv").read_text())
        manifest = json.loads((FIXTURE / "manifest.json").read_text())
        self.assertEqual(manifest["blocksSha256"], hashlib.sha256(text.encode()).hexdigest())
        self.assertEqual([d["blockNo"] for d in described], list(range(len(blocks))), "contiguous from Origin")
        self.assertEqual(manifest["blocksPerEra"],
                         {e: sum(1 for d in described if d["era"] == e) for e in ("conway", "dijkstra")})

    def test_headers_cover_the_boundary(self):
        _, blocks = fixture_blocks()
        described = [dijkstra.describe_block(b) for b in blocks]
        lines = dijkstra.headers_tsv(described, conway_before=2).splitlines()[1:]
        self.assertEqual([line.split("\t")[0] for line in lines[:3]], ["7", "7", "8"])
        self.assertEqual(len(lines), 2 + sum(1 for d in described if d["era"] == "dijkstra"))

    def test_the_cli_and_block_forms_of_the_transaction(self):
        """The CLI's envelope is the 3-element mempool form, the block's the 4-element form with is_valid
        (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:152-182`)."""
        envelope = json.loads((FIXTURE / "transaction.json").read_text())
        self.assertEqual(envelope["type"], "Tx DijkstraEra")
        self.assertEqual(envelope["cborHex"][:2], "83")
        _, blocks = fixture_blocks()
        tx = json.loads((FIXTURE / "manifest.json").read_text())["transaction"]
        raw = blocks[tx["blockNo"]]
        (span,) = cborspan.resolve(raw, [1, 1, 0, 0])
        self.assertEqual(raw[span[0]], 0x84)
        self.assertEqual(raw[span[1] - 1], 0xF5, "is_valid is last and true")


class CliTest(unittest.TestCase):
    def test_the_commands_are_runs_with_their_own_default_name(self):
        self.assertIn("up-dijkstra", cli.RUNS)
        self.assertIn("capture-dijkstra", cli.RUNS)
        seen = {}
        with patch.object(sys, "argv", ["devnet.py", "up-dijkstra"]), \
                patch.object(cli, "run", lambda args: seen.update(vars(args))):
            cli.main()
        self.assertEqual(seen["name"], "scarab-dijkstra")
        self.assertEqual((seen["dijkstra_epoch"], seen["genesis_protocol_major"], seen["pools"]), (1, 12, 3))
        self.assertIs(seen["run"], dijkstra.up_dijkstra)


if __name__ == "__main__":
    unittest.main()

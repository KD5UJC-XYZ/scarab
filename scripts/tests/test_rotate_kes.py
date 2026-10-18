# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the KES rotation driver (`scripts/pool/rotate_kes.py`): no Docker, no node; the cardano-cli and
the node are replaced by fakes that write what the real ones write.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import importlib.util
import json
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "pool" / "rotate_kes.py"
spec = importlib.util.spec_from_file_location("rotate_kes", SCRIPT)
rk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rk)

# a certificate `cardano-cli` wrote for a throwaway cold key: counter 7, start period 12
REAL_CERT = ('{"type": "NodeOperationalCertificate", "description": "", "cborHex": '
             '"8284582036ccb33224f2dc240c8c55dcc4d6163afcd6cd82dd20893495ba5d8b5ec190d8070c584044daaa09697cf955a2f639e8a5e66'
             '30684e334d71dc4c25191a865fd84a67ef75895db4f4eac976a384d247a7640949219972662de852f614566a068ab0ff702582009e8ef34'
             '07c2f3f411da6a1d72e1cc0cb79f8fe8fcca221204eb6be41ad7ab4a"}')
COLD = bytes.fromhex("09e8ef3407c2f3f411da6a1d72e1cc0cb79f8fe8fcca221204eb6be41ad7ab4a")


def envelope(kind, cbor):
    return json.dumps({"type": kind, "description": "", "cborHex": cbor.hex()})


def uint(n):
    return bytes([n]) if n < 24 else bytes([24, n]) if n < 256 else bytes([25]) + n.to_bytes(2, "big")


def opcert_text(hot, counter, start, cold=COLD):
    cbor = b"\x82\x84" + b"\x58\x20" + hot + uint(counter) + uint(start) + b"\x58\x40" + bytes(64) + b"\x58\x20" + cold
    return envelope("NodeOperationalCertificate", cbor)


def vkey_text(key):
    return envelope("KesVerificationKey_ed25519_kes_2^6", b"\x58\x20" + key)


class Fakes:
    """A cardano-cli that writes the files the real one writes, and a node with a tip, a counter and a log."""

    def __init__(self, tip_slot, on_chain, reply="taken", tip=True):
        self.tip_slot, self.on_chain, self.reply, self.tip_known = tip_slot, on_chain, reply, tip
        self.signals, self.generated = 0, 0
        self.issued = None  # (counter, period) the fake cli was asked for
        self.cold_vkey = COLD

    # the Cli
    def run(self, args, mounts=(), check=True):
        work = next(h for h, inside, _ in mounts if inside == "/work")
        if args[1] == "key-gen-KES":
            self.generated += 1
            self.hot = bytes([self.generated]) * 32
            (work / "kes.vkey").write_text(vkey_text(self.hot))
            (work / "kes.skey").write_text("secret key %d" % self.generated)
        elif args[1] == "new-counter":
            self.counter = int(args[args.index("--counter-value") + 1])
            (work / "issue.counter").write_text("counter")
        elif args[1] == "issue-op-cert":
            period = int(args[args.index("--kes-period") + 1])
            self.issued = (self.counter, period)
            (work / "node.cert").write_text(opcert_text(self.hot, self.counter, period, self.cold_vkey))

    # the Node
    def tip(self):
        return {"slot": self.tip_slot} if self.tip_known else None

    def on_chain_counter(self):
        return self.on_chain

    def hangup(self):
        self.signals += 1

    def log_lines(self, since):
        if self.reply == "taken" and self.issued:
            return [{"event": "kesReloaded", "at": since, "opCertCounter": self.issued[0], "kesStart": self.issued[1]}]
        if self.reply == "refused":
            return [{"event": "kesReloadRefused", "at": since, "kind": "Counter", "message": "no"}]
        return []


class DriverTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = pathlib.Path(self.tmp.name)
        self.keys = root / "keys"
        self.keys.mkdir()
        (root / "cold").mkdir()
        (root / "cold" / "cold.skey").write_text("cold secret")
        (root / "cold" / "cold.vkey").write_text(vkey_text(COLD))
        (root / "shelley-genesis.json").write_text(json.dumps({"slotsPerKESPeriod": 100, "maxKESEvolutions": 20}))
        gen = self.keys / "gen-000000-00000000"
        gen.mkdir()
        (gen / "kes.skey").write_text("the first key")
        (gen / "node.cert").write_text(opcert_text(bytes(32), 0, 0))
        rk.switch_link(self.keys, gen.name)
        self.settings = rk.Settings(
            key_dir=self.keys, cold_skey=root / "cold" / "cold.skey", cold_vkey=root / "cold" / "cold.vkey",
            socket=root / "node.socket", shelley_genesis=root / "shelley-genesis.json", testnet_magic=42,
            container="producer", log_file=None, signal_command=[], cli_image="image", user="1000:1000",
            threshold=5, confirm_timeout=0.2, poll=0, state_file=self.keys / "last-counter")

    def go(self, fakes, **changes):
        settings = rk.dataclasses.replace(self.settings, **changes)
        return rk.run(settings, cli=fakes, node=fakes)

    def generations(self):
        return sorted(p.name for p in self.keys.iterdir() if p.name.startswith(("gen-", ".staging")))


class MetricsTest(DriverTest):
    def test_metrics_keep_current_timestamp_precision(self):
        path = self.keys / "rotate-kes.prom"
        settings = rk.dataclasses.replace(self.settings, metrics_file=path)
        now = 1792292758.125
        rk.write_metrics(settings, rk.EXIT_OK, now)
        values = rk.read_metrics(path)
        self.assertEqual(values[rk.LAST_RUN], now)
        self.assertEqual(values[rk.LAST_SUCCESS], now)
        self.assertEqual(values[rk.LAST_STATUS], rk.EXIT_OK)

    def test_failed_run_preserves_the_exact_last_success_timestamp(self):
        path = self.keys / "rotate-kes.prom"
        settings = rk.dataclasses.replace(self.settings, metrics_file=path)
        success = 1792292758.125
        failed = success + 3600.25
        rk.write_metrics(settings, rk.EXIT_OK, success)
        rk.write_metrics(settings, rk.EXIT_NODE_DOWN, failed)
        values = rk.read_metrics(path)
        self.assertEqual(values[rk.LAST_SUCCESS], success)
        self.assertEqual(values[rk.LAST_RUN], failed)
        self.assertEqual(values[rk.LAST_STATUS], rk.EXIT_NODE_DOWN)


class ParseTest(unittest.TestCase):
    def test_a_certificate_written_by_cardano_cli(self):
        cert = rk.parse_opcert(REAL_CERT)
        self.assertEqual((cert.counter, cert.start_period, cert.cold_vkey), (7, 12, COLD))
        self.assertEqual(cert.hot_vkey.hex(), "36ccb33224f2dc240c8c55dcc4d6163afcd6cd82dd20893495ba5d8b5ec190d8")

    def test_json_after_the_check_marks_cardano_cli_prints_first(self):
        out = ("\u2713 Operational certificate's KES period is within the correct KES period interval\n"
               "\u2713 The operational certificate counter agrees with the node protocol state counter\n"
               '{\n    "qKesNodeStateOperationalCertificateNumber": 1,\n    "qKesStartKesInterval": 3\n}\n')
        self.assertEqual(rk.json_in(out)["qKesNodeStateOperationalCertificateNumber"], 1)
        self.assertIsNone(rk.json_in("no object here"))
        self.assertIsNone(rk.json_in("{ not json"))

    def test_periods_counters_and_names(self):
        cert = rk.OpCert(bytes(32), 3, 10, COLD)
        self.assertEqual(rk.periods_left(cert, 10, 62), 62)
        self.assertEqual(rk.periods_left(cert, 71, 62), 1)
        self.assertEqual(rk.periods_left(cert, 72, 62), 0)
        self.assertEqual(rk.kes_period(129599, 129600), 0)
        self.assertEqual(rk.kes_period(129600, 129600), 1)
        self.assertEqual(rk.next_counter(5, None), 6)
        self.assertEqual(rk.next_counter(None, 8), 9)
        self.assertEqual(rk.next_counter(None, None), 1)
        self.assertEqual(rk.generation_name(7, 300), "gen-000007-00000300")


class RotationTest(DriverTest):
    def test_enough_periods_left_changes_nothing(self):
        fakes = Fakes(tip_slot=1000, on_chain=0)  # period 10: 10 periods left, above the threshold of 5
        self.assertEqual(self.go(fakes), rk.EXIT_OK)
        self.assertEqual(fakes.signals, 0)
        self.assertEqual(self.generations(), ["gen-000000-00000000"])

    def test_a_certificate_running_low_is_replaced_and_the_old_pair_erased(self):
        fakes = Fakes(tip_slot=1650, on_chain=0)  # period 16: 4 left
        self.assertEqual(self.go(fakes), rk.EXIT_OK)
        self.assertEqual(fakes.issued, (1, 16))
        self.assertEqual(fakes.signals, 1)
        self.assertEqual(rk.current_generation(self.keys), "gen-000001-00000016")
        self.assertEqual(self.generations(), ["gen-000001-00000016"])
        self.assertFalse((self.keys / "rotation.json").exists())
        self.assertEqual((self.keys / "last-counter").read_text().strip(), "1")
        new = self.keys / "current"
        self.assertEqual(rk.parse_opcert((new / "node.cert").read_text()).start_period, 16)
        self.assertTrue((new / "kes.skey").read_text().startswith("secret key"))
        # nothing left to do now: the new certificate has 20 periods
        self.assertEqual(self.go(fakes), rk.EXIT_OK)
        self.assertEqual(fakes.signals, 1)

    def test_the_counter_is_one_above_the_chains(self):
        fakes = Fakes(tip_slot=1650, on_chain=41)
        self.go(fakes)
        self.assertEqual(fakes.issued[0], 42)

    def test_without_the_chains_counter_the_last_one_issued_here_stands_in(self):
        fakes = Fakes(tip_slot=1650, on_chain=None)
        (self.keys / "last-counter").write_text("9\n")
        self.go(fakes)
        self.assertEqual(fakes.issued[0], 10)

    def test_not_confirmed_leaves_both_pairs_and_the_next_run_finishes(self):
        fakes = Fakes(tip_slot=1650, on_chain=0, reply="silent")
        with self.assertRaises(rk.RotationError) as caught:
            self.go(fakes)
        self.assertEqual(caught.exception.status, rk.EXIT_UNCONFIRMED)
        self.assertEqual(self.generations(), ["gen-000000-00000000", "gen-000001-00000016"])
        self.assertTrue((self.keys / "rotation.json").exists())
        self.assertEqual(rk.current_generation(self.keys), "gen-000001-00000016")
        fakes.reply = "taken"
        self.assertEqual(self.go(fakes), rk.EXIT_OK)
        self.assertEqual(fakes.signals, 2)
        self.assertEqual(self.generations(), ["gen-000001-00000016"])
        self.assertFalse((self.keys / "rotation.json").exists())

    def test_a_crash_before_the_link_moved_is_finished(self):
        fakes = Fakes(tip_slot=1650, on_chain=0)
        # the state a crash leaves between recording the rotation and switching the link
        new = self.keys / "gen-000001-00000016"
        new.mkdir()
        (new / "kes.skey").write_text("secret key")
        (new / "node.cert").write_text(opcert_text(bytes([1]) * 32, 1, 16))
        (self.keys / "rotation.json").write_text(
            json.dumps({"old": "gen-000000-00000000", "new": new.name, "counter": 1, "startPeriod": 16}))
        fakes.issued = (1, 16)
        self.assertEqual(self.go(fakes), rk.EXIT_OK)
        self.assertEqual(rk.current_generation(self.keys), new.name)
        self.assertEqual(self.generations(), [new.name])

    def test_a_refused_reload_puts_the_old_pair_back(self):
        fakes = Fakes(tip_slot=1650, on_chain=0, reply="refused")
        with self.assertRaises(rk.RotationError) as caught:
            self.go(fakes)
        self.assertEqual(caught.exception.status, rk.EXIT_REFUSED)
        self.assertEqual(rk.current_generation(self.keys), "gen-000000-00000000")
        self.assertEqual(self.generations(), ["gen-000000-00000000"])
        self.assertFalse((self.keys / "rotation.json").exists())

    def test_a_node_that_does_not_answer_changes_nothing(self):
        fakes = Fakes(tip_slot=1650, on_chain=0, tip=False)
        with self.assertRaises(rk.RotationError) as caught:
            self.go(fakes)
        self.assertEqual(caught.exception.status, rk.EXIT_NODE_DOWN)
        self.assertEqual(self.generations(), ["gen-000000-00000000"])

    def test_leftovers_of_a_crashed_run_are_erased(self):
        (self.keys / ".staging-gen-000001-00000015").mkdir()
        (self.keys / ".staging-gen-000001-00000015" / "kes.skey").write_text("half a key")
        (self.keys / "gen-000009-00000009").mkdir()
        (self.keys / "gen-000009-00000009" / "kes.skey").write_text("an orphan")
        fakes = Fakes(tip_slot=1000, on_chain=0)
        self.assertEqual(self.go(fakes), rk.EXIT_OK)
        self.assertEqual(self.generations(), ["gen-000000-00000000"])

    def test_a_certificate_that_names_another_key_is_not_installed(self):
        fakes = Fakes(tip_slot=1650, on_chain=0)
        fakes.cold_vkey = bytes(32)  # the fake cli issues it under another cold key
        with self.assertRaises(rk.RotationError) as caught:
            self.go(fakes)
        self.assertEqual(caught.exception.status, rk.EXIT_CONFIG)
        self.assertEqual(self.generations(), ["gen-000000-00000000"])
        self.assertEqual(fakes.signals, 0)


class SettingsTest(unittest.TestCase):
    def test_flags_beat_the_environment_file_beats_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = pathlib.Path(tmp) / "rotate.env"
            env.write_text("# comment\nROTATE_KES_KEY_DIR=/k\nROTATE_KES_COLD_SKEY='/c/cold.skey'\n"
                           "ROTATE_KES_SOCKET=/s\nROTATE_KES_SHELLEY_GENESIS=/g\nROTATE_KES_THRESHOLD=7\n")
            s = rk.parse_args(["--env-file", str(env), "--threshold", "3"], {})
            self.assertEqual((str(s.key_dir), str(s.cold_vkey), s.threshold), ("/k", "/c/cold.vkey", 3))
            s = rk.parse_args(["--env-file", str(env)], {})
            self.assertEqual(s.threshold, 7)
            self.assertIsNone(s.testnet_magic)


if __name__ == "__main__":
    unittest.main()

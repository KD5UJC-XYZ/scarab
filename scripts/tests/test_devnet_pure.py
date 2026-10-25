# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the pure parts of the devnet package (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
from fractions import Fraction

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, cluster, compare, encoding, eraload, eratx, eraview, follower, govextra, govview, ntccases, ntccli, ntclocal, ntcqueries, ntcquery, refused_fixture, util  # noqa: E402
from devnet.scenarios import peercut
from devnet.scenarios import ntc  # noqa: E402
from devnet.scenarios import pool  # noqa: E402
from devnet.scenarios import relay as relay_scenario  # noqa: E402

RFC8032_SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
RFC8032_PUB = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
RFC8032_SIG = ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b"
               "d25bf5f0595bbe24655141438e7a100b")


def bech32_text(hrp, data):
    """Encode bytes as the bech32 data part with a dummy checksum (the decoder ignores the checksum)."""
    acc = int.from_bytes(data, "big")
    bits = len(data) * 8
    pad = (-bits) % 5
    acc <<= pad
    chars = [encoding.BECH32[(acc >> (5 * i)) & 31] for i in reversed(range((bits + pad) // 5))]
    return hrp + "1" + "".join(chars) + "qqqqqq"


class FollowerCommandTest(unittest.TestCase):
    def test_explicit_cpu_limit_reaches_child_cli_without_changing_oracle_option(self):
        with patch.object(follower.subprocess, "Popen") as launch:
            follower.start_follower("audit", pathlib.Path("/tmp/audit"), oracle=True, scala_cpus=4)
        self.assertEqual(launch.call_args.args[0], [
            sys.executable, str(follower.ENTRY), "--name", "audit", "follow",
            "--dump-state", "--scala-cpus", "4"])

    def test_omitted_cpu_limit_preserves_child_cli_default(self):
        with patch.object(follower.subprocess, "Popen") as launch:
            follower.start_follower("audit", pathlib.Path("/tmp/audit"))
        self.assertEqual(launch.call_args.args[0], [
            sys.executable, str(follower.ENTRY), "--name", "audit", "follow"])


class RenameFallbackTest(unittest.TestCase):
    def test_data_dir_prefers_the_variables_then_the_new_default(self):
        told = []
        self.assertEqual(util.data_dir({"SCARAB_DATA_DIR": "/a", "CSL_DATA_DIR": "/b"}, lambda p: True, told.append), pathlib.Path("/a"))
        self.assertEqual(util.data_dir({"CSL_DATA_DIR": "/b"}, lambda p: True, told.append), pathlib.Path("/b"))
        self.assertEqual(util.data_dir({}, lambda p: True, told.append), pathlib.Path("/tmp/scarab"))
        self.assertEqual(util.data_dir({}, lambda p: False, told.append), pathlib.Path("/tmp/scarab"))
        self.assertEqual(told, [])

    def test_data_dir_falls_back_to_the_old_directory_with_a_notice(self):
        told = []
        old = pathlib.Path("/tmp/cardano-scala-lab")
        self.assertEqual(util.data_dir({}, lambda p: p == old, told.append), old)
        self.assertEqual(len(told), 1)

    def test_jdk_image_falls_back_only_when_just_the_old_tag_exists(self):
        self.assertEqual(util.jdk_image(lambda tag: True), "scarab-jdk:local")
        self.assertEqual(util.jdk_image(lambda tag: False), "scarab-jdk:local")
        self.assertEqual(util.jdk_image(lambda tag: tag == "cardano-scala-jdk:local"), "cardano-scala-jdk:local")


class EncodingTest(unittest.TestCase):
    def test_bech32_roundtrip(self):
        for data in (b"\x01\x02\x03\x04\x05", bytes(range(29)), bytes(57)):
            self.assertEqual(encoding.bech32_bytes(bech32_text("addr_test", data))[:len(data)], data)

    def test_base58(self):
        self.assertEqual(encoding.base58_bytes("JxF12TrwUP45BMd"), b"Hello World")
        self.assertEqual(encoding.base58_bytes("112"), b"\0\0\x01")

    def test_address_hex_dispatch(self):
        text = bech32_text("addr_test", bytes(range(29)))
        self.assertTrue(encoding.address_hex(text).startswith(bytes(range(29)).hex()))
        self.assertEqual(encoding.address_hex("JxF12TrwUP45BMd"), b"Hello World".hex())


class CborTest(unittest.TestCase):
    def test_scalars(self):
        self.assertEqual(cbor.enc(0).hex(), "00")
        self.assertEqual(cbor.enc(23).hex(), "17")
        self.assertEqual(cbor.enc(24).hex(), "1818")
        self.assertEqual(cbor.enc(256).hex(), "190100")
        self.assertEqual(cbor.enc(1 << 32).hex(), "1b0000000100000000")
        self.assertEqual(cbor.enc(-1).hex(), "20")
        self.assertEqual(cbor.enc(b"ab").hex(), "426162")
        self.assertEqual(cbor.enc("a").hex(), "6161")
        self.assertEqual(cbor.enc(None).hex(), "f6")
        self.assertEqual(cbor.enc([True, False]).hex(), "82f5f4")

    def test_containers_and_tags(self):
        self.assertEqual(cbor.enc([1, 2]).hex(), "820102")
        self.assertEqual(cbor.enc({1: 2}).hex(), "a10102")
        self.assertEqual(cbor.enc(cbor.Tag(24, b"")).hex(), "d81840")
        self.assertEqual(cbor.enc(cbor.Raw(b"\x01")).hex(), "01")

    def test_map_keys_are_canonically_ordered(self):
        self.assertEqual(cbor.enc({"bb": 1, 10: 2, 1: 3}), cbor.enc({1: 3, 10: 2, "bb": 1}))

    def test_decode_roundtrip(self):
        value = [0, {32784: [1, False]}, b"\x00\xff", -5, 70000, [None]]
        decoded, pos = ntcquery.decode(ntcquery_bytes(value))
        self.assertEqual(decoded, value)
        self.assertEqual(pos, len(ntcquery_bytes(value)))

    def test_decode_incomplete(self):
        with self.assertRaises(ntcquery.Incomplete):
            ntcquery.decode(cbor.enc([1, 2, 3])[:-1])

    def test_decode_tag(self):
        tagged, _ = ntcquery.decode(cbor.enc(cbor.Tag(24, [1, 3])))
        self.assertIsInstance(tagged, ntcquery.Tagged)
        self.assertEqual((tagged.tag, tagged.value), (24, [1, 3]))
        ratio, _ = ntcquery.decode(cbor.enc(cbor.Tag(30, [1, 3])))
        self.assertEqual(ratio, Fraction(1, 3))


def ntcquery_bytes(x):
    return cbor.enc(x)


class Ed25519Test(unittest.TestCase):
    def test_rfc8032_vector_1(self):
        self.assertEqual(eratx.public_key(RFC8032_SEED).hex(), RFC8032_PUB)
        self.assertEqual(eratx.sign(RFC8032_SEED, b"").hex(), RFC8032_SIG)


class TxBuilderTest(unittest.TestCase):
    def key(self):
        return eratx.Key(json.dumps({"cborHex": "5820" + RFC8032_SEED.hex()}))

    def test_key_hash_is_blake2b_224_of_vkey(self):
        key = self.key()
        self.assertEqual(key.vkey.hex(), RFC8032_PUB)
        self.assertEqual(key.hash, eratx.blake2b(key.vkey, 28))
        self.assertEqual(len(key.hash), 28)

    def test_addresses(self):
        h = bytes(28)
        self.assertEqual(eratx.enterprise_address(h)[0], 0x60)
        self.assertEqual(eratx.enterprise_address(h, script=True)[0], 0x70)
        self.assertEqual(eratx.reward_address(h)[0], 0xe0)
        self.assertEqual(eratx.base_address(h, h, 1)[0], 1)
        self.assertEqual(len(eratx.base_address(h, h)), 57)
        # header types: 0 key/key, 1 script/key, 2 key/script, 3 script/script
        self.assertEqual([eratx.base_address(h, h, 0, p, s)[0] >> 4 for p in (False, True) for s in (False, True)],
                         [0, 2, 1, 3])

    def test_script_credentials_in_certificates(self):
        h = bytes(range(28))
        self.assertEqual(eratx.vote_deleg_cert(h, eratx.drep_ref("abstain")), [9, [0, h], [2]])
        self.assertEqual(eratx.vote_deleg_cert(h, eratx.drep_ref("key", h, script=True), script=True),
                         [9, [1, h], [1, h]])
        self.assertEqual(eratx.drep_reg_cert(h, 5, None, script=True), [16, [1, h], 5, None])

    def test_bech32_checksum_matches_bip173(self):
        self.assertEqual(govextra.bech32_from_values("abcdef", range(32)),
                         "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw")
        self.assertEqual(govextra.bech32_from_values("a", []), "a12uel5l")

    def test_stake_address_bech32_roundtrips_through_the_decoder(self):
        h = bytes(range(28))
        for script, header in ((False, 0xe0), (True, 0xf0)):
            raw = encoding.bech32_bytes(govextra.bech32_stake_address(h, script=script))
            self.assertEqual(raw[0], header)
            self.assertEqual(raw[1:29], h)

    def test_native_script_hash_has_tag_prefix(self):
        script = eratx.all_script(eratx.sig_script(bytes(28)), eratx.after_script(10))
        self.assertEqual(eratx.script_hash(script), eratx.blake2b(b"\x00" + cbor.enc(script), 28))

    def test_build_tx_conway_structure(self):
        key = self.key()
        address = eratx.enterprise_address(key.hash)
        out = eratx.tx_out("conway", address, 5_000_000)
        tx, txid = eratx.build_tx("conway", [("11" * 32, 0)], [out], 200_000, [key], ttl=1000)
        decoded, end = ntcquery.decode(tx)
        self.assertEqual(end, len(tx))
        body, witnesses, valid, aux = decoded
        self.assertEqual(body[2], 200_000)
        self.assertEqual(body[3], 1000)
        self.assertEqual(body[0], [[bytes.fromhex("11" * 32), 0]])
        self.assertEqual(valid, True)
        self.assertIsNone(aux)
        vkey, signature = witnesses[0][0]
        self.assertEqual(vkey, key.vkey)
        self.assertEqual(len(signature), 64)
        self.assertEqual(txid, eratx.blake2b(cbor.enc(body)).hex())

    def test_build_tx_is_deterministic_and_pre_alonzo_has_three_elements(self):
        key = self.key()
        out = eratx.tx_out("shelley", eratx.enterprise_address(key.hash), 1_000_000)
        first = eratx.build_tx("shelley", [("22" * 32, 1)], [out], 170_000, [key], ttl=5)
        self.assertEqual(first, eratx.build_tx("shelley", [("22" * 32, 1)], [out], 170_000, [key], ttl=5))
        decoded, _ = ntcquery.decode(first[0])
        self.assertEqual(len(decoded), 3)

    def test_envelope(self):
        env = json.loads(eratx.envelope("conway", b"\x01"))
        self.assertEqual(env["cborHex"], "01")
        self.assertEqual(env["type"], eratx.ERA_TYPE["conway"])


class ProjectionTest(unittest.TestCase):
    def test_diff_reports_only_differences(self):
        self.assertEqual(compare.diff({"a": 1, "b": {"c": [1, 2]}}, {"a": 1, "b": {"c": [1, 2]}}), [])
        self.assertEqual(compare.diff({"a": 1, "b": {"c": 2}, "x": 1}, {"a": 1, "b": {"c": 3}, "y": 1}),
                         [("/b/c", 2, 3), ("/x", 1, "<absent>"), ("/y", "<absent>", 1)])

    def test_cli_gate_requires_all_observed_comparisons_and_nonempty_coverage(self):
        rows = {"tip": {"total": 2, "matched": 2},
                "ledger-state": {"total": 2, "matched": 1},
                "protocol-state": {"total": 0, "matched": 0}}
        self.assertEqual(ntc.incomplete_cli_comparisons(rows), ["ledger-state", "protocol-state"])

    def test_reward_update_absence_is_not_a_computed_update(self):
        computed = {"rs": {"stake": 5}}
        self.assertEqual(compare.diff({"rewardUpdate": None}, {"rewardUpdate": computed}),
                         [("/rewardUpdate", None, computed)])

    def test_conway_output(self):
        text = bech32_text("addr_test", bytes(range(29)))
        view = compare.conway_output({"address": text, "value": {"lovelace": 7, "ab": {"cd": 3}}})
        self.assertEqual(view["lovelace"], 7)
        self.assertEqual(view["assets"], {"ab": {"cd": 3}})
        self.assertNotIn("assets", compare.conway_output({"address": text, "value": {"lovelace": 7}}))

    def test_the_cli_and_local_state_query_utxo_must_hold_the_same_outputs(self):
        text = bech32_text("addr_test", bytes(range(29)))
        address = bytes(range(29)).hex()
        cli = {"aa#0": {"address": text, "value": {"lovelace": 7, "ab": {"cd": 3}}}}
        full = {"aa#0": {"address": address, "lovelace": 7, "assets": {"ab": {"cd": 3}}, "inlineDatum": "01",
                         "scriptRef": "ff"}}
        self.assertTrue(compare.utxo_sources_agree(cli, full))
        # negative controls: a missing output or another amount is a disagreement
        self.assertFalse(compare.utxo_sources_agree(cli, {}))
        self.assertFalse(compare.utxo_sources_agree(cli, {"aa#0": {**full["aa#0"], "lovelace": 8}}))

    def test_the_projection_declares_its_scope_and_the_scala_fields_it_leaves_out(self):
        self.assertEqual(compare.PROJECTION_SCOPE["kind"], "projection")
        self.assertEqual(compare.PROJECTION_SCOPE["issue"], 433)
        self.assertIn("cost models", compare.PROJECTION_SCOPE["notCompared"])
        self.assertEqual(compare.scala_only({"utxo": 1, "nonMyopic": 2, "avvm": 3}, {"utxo": 1}),
                         ["avvm", "nonMyopic"])
        comparisons = compare.Comparisons(pathlib.Path("unused"))  # the tally writes only on record
        self.assertIn("projected ledger comparisons", compare.comparison_table(comparisons).splitlines()[0])

    def test_era_view_helpers(self):
        self.assertEqual(eraview.ratio(0.25), "1/4")
        self.assertEqual(eraview.cred([0, b"\x01"]), "keyHash-01")
        self.assertEqual(eraview.cred([1, b"\x02"]), "scriptHash-02")
        self.assertEqual(eraview.nonce([1, b"\xab"]), "ab")
        self.assertIsNone(eraview.nonce([0]))
        self.assertEqual(eraview.params_view({"a": 0.1 + 0.2}, eraview.ERA_INDEX["conway"]),
                         {"a": 0.1 + 0.2})

    def test_gov_view_helpers(self):
        self.assertEqual(govview.ratio(0.5), "1/2")
        self.assertEqual(govview.ratio({"numerator": 2, "denominator": 6}), "1/3")
        self.assertEqual(govview.cred({"keyHash": "ab"}), "keyHash-ab")
        self.assertEqual(govview.cred("scriptHash-cd"), "scriptHash-cd")
        self.assertEqual(govview.action_id({"txId": "aa", "govActionIx": 2}), "aa#2")
        self.assertIsNone(govview.opt_id(None))
        self.assertEqual(Fraction("0.5"), Fraction(1, 2))


class ReferenceImageTest(unittest.TestCase):
    def test_pinned_constants_and_rejection(self):
        import tempfile
        import prepare_reference_image as pri
        self.assertEqual(len(pri.ARCHIVE_SHA256), 64)
        self.assertTrue(pri.ARCHIVE_URL.startswith("https://github.com/IntersectMBO/cardano-node/releases/download/11.1.3/"))
        self.assertIn("@sha256:", pri.BASE)
        with tempfile.TemporaryDirectory() as d:
            bad = pathlib.Path(d) / "x.tar.gz"
            bad.write_bytes(b"not the archive")
            with self.assertRaises(ValueError):
                pri.verify_archive(bad)


class RestartScenarioTest(unittest.TestCase):
    def test_a_stop_that_reached_the_limit_is_a_hang(self):
        from devnet.scenarios.restart import stop_problem
        self.assertIsNone(stop_problem(3.2, True))
        self.assertIn("did not exit", stop_problem(30.0, True))
        self.assertIn("without a shutdown snapshot", stop_problem(3.0, False))

    def test_the_restart_must_restore(self):
        from devnet.scenarios.restart import restore_problem
        self.assertIsNone(restore_problem([{"event": "snapshotRestored"}]))
        self.assertIn("did not restore", restore_problem([{"event": "extended"}]))

    def test_the_snapshots_must_be_of_the_current_format(self):
        from devnet.scenarios.restart import format_problem
        self.assertIsNone(format_problem([b"SCRBSNP7", b"SCRBSNP7"]))
        self.assertIn("SCRBSNP5", format_problem([b"SCRBSNP7", b"SCRBSNP5"]))
        self.assertIn("no snapshot", format_problem([]))

    def test_retired_migration_flag_is_refused_before_starting_a_cluster(self):
        import io
        from unittest.mock import patch
        from devnet import cli
        with patch("sys.argv", ["devnet.py", "restart", "--migrate-from", "/unused"]), \
                patch("sys.stderr", io.StringIO()), patch("devnet.cli.restart") as run:
            with self.assertRaises(SystemExit) as refusal:
                cli.main()
            self.assertEqual(refusal.exception.code, 2)
            run.assert_not_called()
        with patch("sys.argv", ["devnet.py", "restart"]), patch("devnet.cli.restart") as run:
            cli.main()
            run.assert_called_once()
            self.assertEqual(run.call_args.args[0].command, "restart")


class PoolOracleTest(unittest.TestCase):
    """missed_leader_slots: every scheduled slot in the forger's window is forged or taken by another block."""
    SHELLEY = {"systemStart": "2026-01-01T00:00:00Z", "slotLength": 1, "epochLength": 100}

    @staticmethod
    def at(slot):
        return f"2026-01-01T00:{slot // 60:02d}:{slot % 60:02d}Z"

    def events(self, forged, other, end=90):
        ev = [{"event": "extended", "at": self.at(5), "block": {"slot": 5, "hash": "a"}}]
        ev += [{"event": "forged", "at": self.at(s), "slot": s, "hash": f"f{s}"} for s in forged]
        ev += [{"event": "extended", "at": self.at(s), "block": {"slot": s, "hash": f"o{s}"}} for s in other]
        ev += [{"event": "forgeSkipped", "at": self.at(30), "slot": 30, "reason": "busy"}]
        ev += [{"event": "extended", "at": self.at(end), "block": {"slot": end, "hash": "z"}}]
        return ev

    def test_all_accounted_for(self):
        r = pool.missed_leader_slots(self.events([10, 20], [40]), {0: [2, 10, 20, 40, 89, 95]}, self.SHELLEY)
        self.assertEqual(r["missed"], [])
        self.assertEqual(r["occupied"], [40])
        self.assertEqual(r["scheduled"], 3)  # 2 is before the window, 89 and 95 after its end
        self.assertEqual(r["absentEpochs"], [])

    def test_missed_slot_with_reason(self):
        r = pool.missed_leader_slots(self.events([10], []), {0: [10, 30, 50]}, self.SHELLEY)
        self.assertEqual(r["missed"], [30, 50])
        self.assertEqual(r["missedReasons"], {30: "busy", 50: "not reported"})

    def test_epoch_without_schedule(self):
        r = pool.missed_leader_slots(self.events([10], [], end=250), {0: [10], 2: [210]}, self.SHELLEY)
        self.assertEqual(r["absentEpochs"], [1])

    def test_last_epoch_without_schedule_is_left_out_of_the_window(self):
        # the run ended a few seconds into epoch 1, before its schedule was read: the tail does not count
        r = pool.missed_leader_slots(self.events([10], [], end=150), {0: [10]}, self.SHELLEY)
        self.assertEqual(r["absentEpochs"], [])
        self.assertEqual(r["window"][1], 99)


class BoundaryHelperTests(unittest.TestCase):
    """The era-load helpers that state ledger boundaries (cardano-ledger Mary.TxOut / Mary.Value / Rewards)."""

    POLICY = b"\x11" * 28

    def test_mary_scaled_min_deposit(self):
        # ada only: the parameter; one 4-byte name: 6 + ceil((12 + 4 + 28) / 8) = 12 words, 1000000 // 27 per word
        self.assertEqual(eraload.mary_min_utxo(1_000_000, {}), 1_000_000)
        self.assertEqual(eraload.mary_min_utxo(1_000_000, {self.POLICY: {b"minb": 1}}), 37037 * (27 + 12))
        # the same name under two policies counts once: 24 + 5 + 56 bytes -> 11 words + 6
        two = {self.POLICY: {b"abcde": 1}, b"\x22" * 28: {b"abcde": 1}}
        self.assertEqual(eraload.mary_min_utxo(1_000_000, two), 37037 * (27 + 17))
        # never below the parameter
        self.assertEqual(eraload.mary_min_utxo(40, {self.POLICY: {b"minb": 1}}), 40)

    def test_value_size_search_hits_the_target(self):
        loader = eraload.EraLoad.__new__(eraload.EraLoad)
        for target, coin in [(4000, 30_000_000), (4001, 30_000_000), (5000, 40_000_000), (5001, 40_000_000)]:
            assets = loader.sized_assets(self.POLICY, target, coin)
            self.assertEqual(eraload.value_bytes(coin, assets), target)
            # and the same bytes as the CBOR encoder produces
            value = [coin, {self.POLICY: dict(assets[self.POLICY])}]
            self.assertEqual(len(cbor.enc(value)), target)

    def test_pre_allegra_reward_choice(self):
        # entries are [type, pool, amount]; the leader reward (type 1) is the minimum, then the lower pool
        self.assertEqual(eraview.aggregated_pre_allegra([[0, b"\x01", 5], [1, b"\x09", 7]]), 7)
        self.assertEqual(eraview.aggregated_pre_allegra([[1, b"\x09", 7], [1, b"\x02", 3]]), 3)
        self.assertEqual(eraview.aggregated_pre_allegra([[0, b"\x09", 7], [0, b"\x02", 3]]), 3)

    def test_previous_protocol_major(self):
        pp = [0] * 14 + [[2, 0]] + [0] * 3
        self.assertEqual(eraview.previous_major([None, None, None, pp], 1), 2)
        self.assertIsNone(eraview.previous_major([], 1))

    def test_shelley_epochs_extend_the_plan(self):
        class A:
            shelley_epochs = 3
        plan = cluster.era_plan(A)
        self.assertEqual(plan[:4], [("shelley", 2)] * 3 + [("allegra", 3)])
        self.assertEqual(cluster.era_plan(object()), cluster.ERA_PLAN)


class RelayHandshakeTest(unittest.TestCase):
    def line(self, ns, msg):
        return json.dumps({"ns": ns, "data": {"event": {"kind": "Send", "msg": {"msg": msg}}}}, separators=(",", ":"))

    def test_proposals_and_acceptances_are_read_from_the_handshake_trace(self):
        log = "\n".join([
            "not json",
            self.line("Net.PeerSelection.Churn", "MsgAcceptVersion NodeToNodeV_9 []"),
            self.line("Net.Handshake.Remote.Send.ProposeVersions", "MsgProposeVersions (fromList [(NodeToNodeV_14,TList [])])"),
            self.line("Net.Handshake.Remote.Receive.AcceptVersion", "MsgAcceptVersion NodeToNodeV_15 (TList [])"),
            '{"ns":"Net.Handshake.Remote.Receive.AcceptVersion","data":{}}',
        ])
        got = relay_scenario.relay_handshakes(log)
        self.assertEqual(got["proposed"], ["MsgProposeVersions (fromList [(NodeToNodeV_14,TList [])])"])
        self.assertEqual(got["accepted"], ["MsgAcceptVersion NodeToNodeV_15 (TList [])"])


class StallEvidenceTest(unittest.TestCase):
    def test_stall_evidence_collects_events_and_dumps(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.assertIsNone(util.stall_evidence(root))
            flags, volume = util.stall_dump_arguments(root)
            self.assertEqual(flags, ["--stall-dump", "/stall"])
            self.assertEqual(volume, f"{root / 'stall'}:/stall")
            (root / "follower.jsonl").write_text(
                json.dumps({"event": "extended"}) + "\n" + json.dumps({"event": "stalled", "peerBlock": 9}) + "\n"
                + json.dumps({"event": "stallState", "sessions": []}) + "\n")
            (root / "stall" / "fiber-dump-1.txt").write_text("fibers")
            evidence = util.stall_evidence(root)
            self.assertEqual([e["event"] for e in evidence["events"]], ["stalled", "stallState"])
            self.assertEqual(evidence["fiberDumps"], [{"file": "stall/fiber-dump-1.txt", "bytes": 6}])


if __name__ == "__main__":
    unittest.main()


class RefusedFixtureTest(unittest.TestCase):
    def test_build_keeps_the_vrf_sequence_and_the_refusals_that_replay(self):
        with tempfile.TemporaryDirectory() as d:
            run = pathlib.Path(d)
            (run / "vrf-txs.jsonl").write_text(
                json.dumps({"context": "genesis pools", "protocolMajor": 11, "pools": {}}) + "\n"
                + json.dumps({"label": "xvrf-new-1", "outcome": "accepted", "tx": "00"}) + "\n")
            (run / "negative-txs.jsonl").write_text("".join(json.dumps(e) + "\n" for e in (
                {"label": "xunelected-hot0-ab", "expected": ["UnelectedCommitteeVoters"], "tx": "01"},
                {"label": "xrefoverlap-pv10-cd", "expected": ["BabbageNonDisjointRefInputs"], "tx": "02"},
                {"label": "xrefoverlap-v3-ef", "expected": ["ReferenceInputsNotDisjointFromInputs"], "tx": "03"})))
            lines = refused_fixture.build(run)
        self.assertEqual([l["scenario"] for l in lines], ["vrf", "vrf", "unelected", "refoverlap"])


class SubmissionVerdictTest(unittest.TestCase):
    """What `expect_rejected` makes of the CLI's answer (the xvrf-dup-active observation: error "", not refused)."""
    EXPECTED = ["VRFKeyHashAlreadyRegistered"]
    LEDGER = ("Command failed: transaction submit\nError: Error while submitting tx: ShelleyTxValidationError "
              "ShelleyBasedEraConway (ConwayApplyTxError (ConwayCertsFailure (CertFailure (PoolFailure "
              "(VRFKeyHashAlreadyRegistered x y)))))\nCallStack (from HasCallStack):")

    def kind(self, code, out="", err=""):
        return eraload.submission_verdict(code, out, err, self.EXPECTED).kind

    def test_a_ledger_refusal_naming_the_predicate_is_refused(self):
        self.assertEqual(self.kind(1, err=self.LEDGER), "refused")

    def test_a_refusal_says_whether_the_failure_list_is_exactly_the_expected_one(self):
        single = self.LEDGER.replace("(VRFKeyHashAlreadyRegistered x y)))))",
                                     "(VRFKeyHashAlreadyRegistered x y))) :| []))")
        v = eraload.submission_verdict(1, "", single, self.EXPECTED)
        self.assertEqual((v.kind, v.match, v.failures), ("refused", eraload.ListMatch.EXACT, 1))
        # negative control: an extra failure still names the predicate, but the match is labelled CONTAINS
        extra = single.replace(":| []", ':| [ConwayUtxowFailure (UtxoFailure (FeeTooSmallUTxO "a, b"))]')
        v = eraload.submission_verdict(1, "", extra, self.EXPECTED)
        self.assertEqual((v.kind, v.match, v.failures), ("refused", eraload.ListMatch.CONTAINS, 2))
        # the text without its list (the old fixture form) cannot be classified
        self.assertEqual(eraload.submission_verdict(1, "", self.LEDGER, self.EXPECTED).match,
                         eraload.ListMatch.UNPARSED)
        self.assertEqual(eraload.match_counts([{"match": "exact"}, {"match": "contains"}, {"match": None}]),
                         {"exact": 1, "contains": 1, "unparsed": 0})

    def test_a_predicate_is_named_as_a_whole_constructor(self):
        prefix = self.LEDGER.replace("VRFKeyHashAlreadyRegistered", "VRFKeyHashAlreadyRegisteredPOOL")
        self.assertEqual(self.kind(1, err=prefix), "wrongReason")

    def test_the_failure_list_ignores_separators_in_strings_and_brackets(self):
        text = 'Error: (ConwayApplyTxError (A "a)\\" :| [" (b c) :| [B [1,2], C {x = 1, y = 2}]))'
        self.assertEqual(eraload.failure_list(text), ['A "a)\\" :| [" (b c)', "B [1,2]", "C {x = 1, y = 2}"])
        self.assertIsNone(eraload.failure_list("Error: (ConwayApplyTxError (A :| [B]"))
        self.assertIsNone(eraload.failure_list("Network.Socket.connect: does not exist"))

    def test_a_ledger_refusal_for_another_reason_is_wrong_reason(self):
        other = self.LEDGER.replace("VRFKeyHashAlreadyRegistered", "StakePoolCostTooLow")
        self.assertEqual(self.kind(1, err=other), "wrongReason")

    def test_a_zero_exit_is_an_acceptance_even_without_output(self):
        v = eraload.submission_verdict(0, "", "", self.EXPECTED)
        self.assertEqual(v.kind, "accepted")
        self.assertNotEqual(v.text, "")
        self.assertEqual(self.kind(0, out="Transaction successfully submitted."), "accepted")

    def test_a_failure_without_a_ledger_text_is_no_verdict(self):
        self.assertEqual(self.kind(1), "noVerdict")
        self.assertEqual(self.kind(137), "noVerdict")
        self.assertEqual(self.kind(126, err="OCI runtime exec failed: exec failed"), "noVerdict")
        self.assertEqual(self.kind(1, err="Network.Socket.connect: does not exist (No such file or directory)"),
                         "noVerdict")

    def load(self, answers):
        load = eraload.EraLoad("t", lambda: None, log=lambda m: None)
        calls = []

        def sh(script, stdin=None, timeout=60):
            calls.append(script)
            code, out, err = answers[min(len(calls), len(answers)) - 1]
            return subprocess.CompletedProcess([], code, out, err)
        load.sh = sh
        return load, calls

    def expect(self, load):
        with patch.object(eraload.eratx, "envelope", return_value="{}"), patch.object(eraload.time, "sleep"), \
                patch.object(load, "keep_tx"):
            return load.expect_rejected("conway", b"\x00", "lbl", self.EXPECTED)

    def test_no_verdict_is_submitted_again_and_the_last_verdict_counts(self):
        load, calls = self.load([(1, "", ""), (1, "", ""), (1, "", self.LEDGER)])
        self.assertTrue(self.expect(load))
        self.assertEqual(len(calls), 3)
        self.assertEqual(load.stats, {"negativeOk": 1})

    def test_an_acceptance_is_final_and_kept_for_the_scenario(self):
        load, calls = self.load([(0, "", "")])
        self.assertFalse(self.expect(load))
        self.assertEqual(len(calls), 1)
        self.assertEqual(load.last_verdict.kind, "accepted")
        self.assertEqual(load.stats, {"negativeUnexpected": 1})

    def test_persistent_silence_is_reported_as_unexpected_after_the_attempts(self):
        load, calls = self.load([(1, "", "")])
        self.assertFalse(self.expect(load))
        self.assertEqual(len(calls), eraload.NO_VERDICT_ATTEMPTS)
        self.assertEqual(load.last_verdict.kind, "noVerdict")


class ExtendedOutcomeTest(unittest.TestCase):
    def outcome(self, results, done, protocol, unexpected=0):
        return govextra.extended_outcome(results, set(done), protocol, unexpected)

    OK = {"ok": True, "tries": 1}
    GENERIC = ("xfund", "xrewards", "xscriptreg")
    BEFORE_11 = GENERIC + ("xrefoverlap-pre11",)
    ALL = BEFORE_11 + ("xrefoverlap-11", "xvrf")

    def test_all_stages_done_passes(self):
        results = {k: self.OK for k in self.ALL}
        self.assertEqual(self.outcome(results, results, 11), ([], {}))

    def test_a_stage_that_reported_ok_false_fails_the_run(self):
        results = {k: self.OK for k in self.ALL[:-1]}
        results["xvrf"] = {"ok": False, "tries": 1, "error": ""}
        failures, na = self.outcome(results, self.ALL[:-1], 11)
        self.assertEqual(na, {})
        self.assertEqual(len(failures), 1)
        self.assertIn("xvrf reported ok:false", failures[0])

    def test_a_stage_that_failed_then_succeeded_passes(self):
        results = {k: self.OK for k in self.ALL}
        self.assertEqual(self.outcome({**results, "xscriptreg": {"ok": True, "tries": 2}}, results, 11), ([], {}))

    def test_the_stages_of_protocol_11_before_it_are_not_applicable_with_their_reason(self):
        results = {k: self.OK for k in self.BEFORE_11}
        failures, na = self.outcome(results, results, 10)
        self.assertEqual(failures, [])
        self.assertEqual(sorted(na), ["xrefoverlap-11", "xvrf"])
        self.assertIn("protocol version 11", na["xvrf"])

    def test_the_stage_before_11_applies_from_the_first_conway_version(self):
        results = {k: self.OK for k in self.GENERIC}
        failures, na = self.outcome(results, results, 9)
        self.assertEqual(failures, ["extended stage xrefoverlap-pre11 did not complete (protocol version 9 reached)"])
        self.assertEqual(sorted(na), ["xrefoverlap-11", "xvrf"])

    def test_an_applicable_stage_that_never_ran_fails(self):
        results = {k: self.OK for k in self.ALL[:-1]}
        failures, na = self.outcome(results, results, 11)
        self.assertEqual(na, {})
        self.assertIn("xvrf did not complete", failures[0])

    def test_no_protocol_seen_makes_every_stage_not_applicable_never_a_pass_by_silence_of_applicable_ones(self):
        failures, na = self.outcome({}, [], None)
        self.assertEqual(failures, [])
        self.assertEqual(set(na), set(govextra.LIGHT_STAGES))

    def test_unexpected_negative_cases_fail(self):
        results = {k: self.OK for k in self.ALL}
        failures, _ = self.outcome(results, results, 11, unexpected=1)
        self.assertEqual(len(failures), 1)

    def test_pending_stages_are_those_applicable_not_done_and_not_given_up(self):
        ext = govextra.Extended(types.SimpleNamespace(), full=False)
        self.assertEqual(govextra.extended_pending(ext), [])
        ext.max_protocol = ext.pv = 11
        self.assertEqual(sorted(govextra.extended_pending(ext)), sorted(self.ALL[:3] + self.ALL[4:]))
        ext.done.update(self.GENERIC + ("xrefoverlap-11",))
        ext.failed["xvrf"] = "diverged"
        self.assertEqual(govextra.extended_pending(ext), [])

    def test_a_stage_of_a_version_window_the_chain_left_is_no_longer_waited_for(self):
        ext = govextra.Extended(types.SimpleNamespace(), full=False)
        ext.done.update(self.GENERIC)
        ext.max_protocol = ext.pv = 10
        self.assertEqual(govextra.extended_pending(ext), ["xrefoverlap-pre11"])
        ext.max_protocol = ext.pv = 11
        self.assertEqual(sorted(govextra.extended_pending(ext)), ["xrefoverlap-11", "xvrf"])

    def test_the_light_set_owns_the_hard_forks_only_in_the_e2e(self):
        self.assertIsNone(govextra.Extended(types.SimpleNamespace(), full=False).walk)
        self.assertIsNone(govextra.Extended(types.SimpleNamespace(dynamic_forks=True), full=True).walk)
        self.assertIsNotNone(govextra.Extended(types.SimpleNamespace(dynamic_forks=True), full=False).walk)


class ExtendedStageTest(unittest.TestCase):
    def ext(self):
        logs = []
        load = types.SimpleNamespace(log=logs.append, stats={})
        return govextra.Extended(load, full=False), logs

    def test_a_divergence_ends_the_stage_failed_for_good_and_is_recorded(self):
        ext, logs = self.ext()

        def run():
            raise govextra.ReferenceDiverged("xvrf-dup-active: accepted")
        ext.stage("xvrf", run)
        self.assertEqual(ext.results["xvrf"], {"ok": False, "tries": 1, "error": "xvrf-dup-active: accepted", "protocol": None})
        calls = []
        ext.stage("xvrf", lambda: calls.append(1))
        self.assertEqual(calls, [])  # never retried
        self.assertIn({"extended": True, "stage": "xvrf", "ok": False, "tries": 1,
                       "error": "xvrf-dup-active: accepted"}, logs)

    def test_a_stage_records_the_protocol_version_it_ran_at(self):
        ext, _ = self.ext()
        ext.pv = 11
        ext.stage("xvrf", lambda: True)
        self.assertEqual(ext.results["xvrf"]["protocol"], 11)
        ext.pv = 12
        ext.stage("xother", lambda: None, tries=2)
        self.assertEqual(ext.results["xother"]["protocol"], 12)
        self.assertEqual(ext.limits["xother"], 2)

    def test_a_falsy_result_is_retried_up_to_the_tries(self):
        ext, _ = self.ext()
        calls = []
        for _ in range(8):
            ext.stage("xvrf", lambda: calls.append(1) and None)
        self.assertEqual(len(calls), govextra.Extended.TRIES)
        self.assertFalse(ext.results["xvrf"]["ok"])

    def test_vrf_cases_stop_on_an_accepted_duplicate(self):
        ext, _ = self.ext()
        accepted = eraload.Verdict("accepted", "")

        def sh(script):
            return types.SimpleNamespace(stdout="aa" * 28)

        def act(*a, **kw):
            ext.l.last_verdict = accepted
            return False
        ext.l.sh, ext.l.act, ext.l.keep_tx = sh, act, lambda *a: None
        ext.l.pool_cold = [types.SimpleNamespace(hash=bytes(28))] * 3
        with self.assertRaises(govextra.ReferenceDiverged):
            ext.vrf_cases({"stakePoolDeposit": 500_000_000, "protocolVersion": {"major": 11}})
        self.assertEqual(ext.done, {"xvrf-context"})


class FreezeClassesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.repo = self.tmp / "checkout"
        self.run = self.tmp / "run"
        self.cp = self.repo / "node/target/runtime-classpath.txt"
        self.cp.parent.mkdir(parents=True)
        self.patch = patch.object(cluster, "REPO", self.repo)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def compiled(self, path, content=b"compiled"):
        path.mkdir(parents=True)
        (path / "Main.class").write_bytes(content)
        return path

    def test_freezes_worktree_classes_and_keeps_dependency_paths(self):
        classes = self.compiled(self.repo / "node/target/scala-3.3.8/classes")
        jar = "/work/.cache/scarab/dependency.jar"
        self.cp.write_text(f"/work/node/target/scala-3.3.8/classes:{jar}")
        self.assertEqual(cluster.freeze_classes(self.run), f"/run/classes/node:{jar}")
        (classes / "Main.class").write_bytes(b"rebuilt")
        self.assertEqual((self.run / "classes/node/Main.class").read_bytes(), b"compiled")

    def test_missing_module_fails_before_replacing_a_previous_freeze(self):
        self.compiled(self.repo / "node/target/scala-3.3.8/classes")
        old = self.compiled(self.run / "classes/node", b"previous")
        self.cp.write_text("/work/node/target/scala-3.3.8/classes:/work/ledger/target/scala-3.3.8/classes")
        with self.assertRaisesRegex(SystemExit, "missing compiled classes.*ledger"):
            cluster.freeze_classes(self.run)
        self.assertEqual((old / "Main.class").read_bytes(), b"previous")

    def test_empty_classes_and_wrong_checkout_paths_are_refused(self):
        (self.repo / "node/target/classes").mkdir()
        for entry in ("/work/node/target/classes", "/other/checkout/node/target/classes", ""):
            self.cp.write_text(entry)
            with self.subTest(entry=entry), self.assertRaises(SystemExit):
                cluster.freeze_classes(self.run)
        self.assertFalse((self.run / "classes").exists())

    def test_explicit_previous_build_must_have_every_module(self):
        self.cp.write_text("/work/node/target/classes:/work/ledger/target/classes")
        previous = self.tmp / "previous"
        self.compiled(previous / "node")
        with self.assertRaisesRegex(SystemExit, "missing compiled classes.*ledger"):
            cluster.freeze_classes(self.run, previous)
        self.compiled(previous / "ledger", b"old-ledger")
        self.assertEqual(cluster.freeze_classes(self.run, previous), "/run/classes/node:/run/classes/ledger")
        self.assertEqual((self.run / "classes/ledger/Main.class").read_bytes(), b"old-ledger")


class CacheDirTest(unittest.TestCase):
    """Where the dependency cache is, from a main checkout or a linked worktree."""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.main = self.tmp / "main"
        self.tree = self.main / ".claude" / "worktrees" / "w1"
        (self.main / ".cache" / "tools").mkdir(parents=True)
        (self.tree / ".cache").mkdir(parents=True)  # the empty mount point of a worktree
        self.common = str(self.main / ".git")

    def test_environment_variable_wins(self):
        other = self.tmp / "elsewhere"
        other.mkdir()
        self.assertEqual(util.cache_dir(self.tree, {"SCARAB_CACHE_DIR": str(other)}, self.common), other.resolve())

    def test_populated_own_cache_is_used(self):
        self.assertEqual(util.cache_dir(self.main, {}, ".git"), self.main / ".cache")

    def test_worktree_with_empty_cache_uses_the_main_checkout(self):
        self.assertEqual(util.cache_dir(self.tree, {}, self.common), self.main / ".cache")

    def test_no_shared_cache_falls_back_to_own(self):
        (self.main / ".cache" / "tools").rmdir()
        self.assertEqual(util.cache_dir(self.tree, {}, self.common), self.tree / ".cache")

    def test_both_cache_layouts_resolve_to_the_parent(self):
        # `.cache/scarab` (new), or `.cache/cardano-scala-lab` (old, or the symlink to the new): either way
        # the directory mounted at /work/.cache is `.cache` itself
        (self.main / ".cache" / "tools").rmdir()
        for name in ("cardano-scala-lab", "scarab"):
            (self.main / ".cache" / name).mkdir()
            self.assertEqual(util.cache_dir(self.main, {}, ".git"), self.main / ".cache")
            (self.main / ".cache" / name).rmdir()

    def test_main_checkout_from_relative_absolute_and_absent_common_dir(self):
        self.assertEqual(util.main_checkout(self.main, ".git"), self.main)
        self.assertEqual(util.main_checkout(self.tree, self.common), self.main)
        self.assertEqual(util.main_checkout(self.tree, ""), self.tree)


class StarvationWarningTest(unittest.TestCase):
    def test_starvation_warning_is_not_unparsed_output(self):
        import tempfile
        from devnet import util
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "follower.jsonl"
            p.write_text('{"event": "extended"}\n'
                         "[WARNING] Your app's responsiveness to a new asynchronous event (such as a new "
                         "connection, an upstream response, or a timer) was in excess of 100 milliseconds. "
                         "See https://typelevel.org/cats-effect/docs/core/starvation-and-tuning\n"
                         "something else\n")
            events, other = util.read_log(p)
            self.assertEqual(len(events), 1)
            self.assertEqual(other, ["something else"])
            self.assertEqual(util.starvation_warnings(p), 1)


class NtcQueriesTest(unittest.TestCase):
    def universe(self):
        cred = [0, b"\xab" * 28]
        return ntcqueries.Universe(pools=[b"\x01" * 28, b"\x02" * 28], creds=[tuple(cred)], dreps=[cred],
                                   drep_keys=[cred], addresses=[b"\x61" + b"\x00" * 28],
                                   txins=[[b"\x11" * 32, 0]], actions=[[b"\x22" * 32, 0]], committee=[cred])

    def test_battery_labels_are_unique_and_every_query_encodes(self):
        queries = ntcqueries.battery(self.universe())
        labels = [label for label, _, _ in queries]
        self.assertEqual(len(labels), len(set(labels)))
        for label, query, _ in queries:
            self.assertTrue(cbor.enc(query), label)

    def test_ledger_queries_are_wrapped_for_conway(self):
        by_label = {label: query for label, query, _ in ntcqueries.battery(self.universe())}
        self.assertEqual(by_label["GetCurrentPParams"], [0, [0, [6, [3]]]])
        self.assertEqual(by_label["top:GetChainPoint"], [3])
        self.assertEqual(by_label["hfc:GetInterpreter"], [0, [2, [0]]])

    def test_sets_are_tagged_and_sorted(self):
        encoded = cbor.enc(ntcqueries.sset([[1, b"b"], [0, b"a"]]))
        self.assertEqual(encoded[:3], bytes.fromhex("d90102"))
        self.assertLess(encoded.index(b"a"), encoded.index(b"b"))

    def test_every_battery_query_is_required(self):
        marked = {label for label, _, implemented in ntcqueries.battery(self.universe()) if not implemented}
        self.assertEqual(marked, set())

    def test_the_cli_queries_name_their_arguments(self):
        for label, text in ntc.CLI_QUERIES:
            self.assertFalse(text.startswith("query "), label)
        self.assertEqual(ntc.rendered("x\nError: boom\nCallStack"), "Error: boom")

    def test_the_current_slot_of_a_validity_refusal_is_not_masked(self):
        text = "Error: (OutsideValidityIntervalUTxO (ValidityInterval {invalidHereafter = SJust (SlotNo 1)}) (SlotNo 18204))) :| []"
        case = ntccases.Case("expired", b"", "OutsideValidityIntervalUTxO")
        raw = cbor.enc([[6, [[0, 18204]]]])
        outcome, row = ntc.judge_refusal(case, {"h": raw, "s": raw}, {"h": text, "s": text})
        self.assertIs(outcome, ntc.Refusal.EXACT)
        # negative control: the same refusal judged one slot later is a difference, in bytes and in text
        later = cbor.enc([[6, [[0, 18205]]]])
        outcome, row = ntc.judge_refusal(case, {"h": raw, "s": later},
                                         {"h": text, "s": text.replace("18204", "18205")})
        self.assertIs(outcome, ntc.Refusal.DIFFERS)
        self.assertFalse(row["rawEqual"] or row["textEqual"])
        self.assertFalse(outcome.passes)

    def test_a_submission_counts_only_when_one_tip_brackets_it(self):
        tip, other = (10, "aa"), (11, "bb")
        self.assertTrue(ntc.bracketed((tip, tip), (tip, tip)))
        self.assertFalse(ntc.bracketed((tip, other), (tip, tip)))
        self.assertFalse(ntc.bracketed((tip, tip), (other, other)))
        self.assertFalse(ntc.bracketed((None, None), (None, None)))
        self.assertFalse(ntc.Refusal.UNBRACKETED.passes)

    def test_a_tracked_divergence_holds_exactly_as_declared(self):
        case = ntccases.Case("withdrawal-network-flag-true", b"", "ConwayWithdrawalsMissingAccounts",
                             ntccases.ACCUMULATED_WITHDRAWAL_FAILURES)
        self.assertEqual(case.divergence.issue, 412)
        both = cbor.enc([[6, [[8, 1], [1, 2]]]])
        first = cbor.enc([[6, [[8, 1]]]])
        text = {"h": "Error: (ConwayWithdrawalsMissingAccounts x :| [ConwayUtxowFailure y])",
                "s": "Error: (ConwayWithdrawalsMissingAccounts x :| [])"}
        outcome, row = ntc.judge_refusal(case, {"h": both, "s": first}, text)
        self.assertIs(outcome, ntc.Refusal.TRACKED_DIVERGENCE)
        self.assertEqual((row["referenceFailures"], row["scalaFailures"], row["issue"]), (2, 1, 412))
        self.assertFalse(row["rawEqual"])
        # a different first failure, or another count, is a difference
        other = cbor.enc([[6, [[9, 1]]]])
        self.assertIs(ntc.judge_refusal(case, {"h": both, "s": other}, text)[0], ntc.Refusal.DIFFERS)
        three = cbor.enc([[6, [[8, 1], [1, 2], [1, 3]]]])
        self.assertIs(ntc.judge_refusal(case, {"h": three, "s": first}, text)[0], ntc.Refusal.DIFFERS)
        # once the Scala refusal is the whole list, the exemption fails so that it is removed
        resolved = ntc.judge_refusal(case, {"h": both, "s": both}, {"h": text["h"], "s": text["h"]})[0]
        self.assertIs(resolved, ntc.Refusal.DIVERGENCE_RESOLVED)
        self.assertFalse(resolved.passes)

    def test_the_summary_counts_exact_cases_and_lists_tracked_divergences(self):
        rows = [{"case": "a", "outcome": "exact", "passes": True, "rawEqual": True, "textEqual": True,
                 "referenceNamesRule": True},
                {"case": "b", "outcome": "trackedDivergence", "passes": True, "rawEqual": False, "textEqual": False,
                 "referenceNamesRule": True, "comparison": "first-failure", "issue": 412, "referenceFailures": 2,
                 "scalaFailures": 1}]
        summary = ntc.submission_summary(rows)
        self.assertEqual((summary["cases"], summary["exact"], summary["rawEqual"]), (2, 1, 1))
        self.assertEqual(summary["trackedDivergences"], [{"case": "b", "comparison": "first-failure", "issue": 412,
                                                          "referenceFailures": 2, "scalaFailures": 1}])
        self.assertEqual(summary["differences"], [])

    def test_a_rule_is_named_as_a_whole_constructor(self):
        text = "(ConwayWithdrawalsMissingAccounts (Withdrawals {unWithdrawals = ...}))"
        self.assertTrue(ntc.names_constructor(text, "ConwayWithdrawalsMissingAccounts"))
        self.assertTrue(ntc.names_constructor(text, "Withdrawals"))
        self.assertFalse(ntc.names_constructor(text, "WithdrawalsMissing"))
        self.assertFalse(ntc.names_constructor("BabbageOutputTooSmallUTxO", "OutputTooSmallUTxO"))


class NtcQuerySessionTest(unittest.TestCase):
    def test_a_rejection_reason_is_what_follows_the_message_tag(self):
        raw = bytes.fromhex("82028182068182018200830518c401")
        self.assertEqual(ntcquery.LocalStateQuery._reason(raw), bytes.fromhex("8182068182018200830518c401"))

    def test_transactions_travel_as_cbor_in_cbor(self):
        self.assertEqual(cbor.enc([0, [6, cbor.Tag(24, b"\x01\x02")]]), bytes.fromhex("82008206d818420102"))


class SchedulingSummaryTest(unittest.TestCase):
    def test_summary_takes_the_worst_epoch_window_and_counts_warnings(self):
        import argparse
        from devnet import util
        events = [{"event": "extended"},
                  {"event": "ledgerEpoch", "scheduling": {"maxTimerLatenessMs": 12, "timersLateOver100Ms": 0,
                                                          "longestStepMs": 40, "longestStep": "block"}},
                  {"event": "ledgerEpoch", "scheduling": {"maxTimerLatenessMs": 35, "timersLateOver100Ms": 1,
                                                          "longestStepMs": 310, "longestStep": "epochBoundary"}}]
        with tempfile.TemporaryDirectory() as d:
            log = pathlib.Path(d) / "follower.jsonl"
            log.write_text("starvation-and-tuning\n")
            s = util.scheduling_summary(events, log, argparse.Namespace(scala_cpus=1.0))
        self.assertEqual(s["scalaCpus"], 1.0)
        self.assertEqual(s["cpuStarvationWarnings"], 1)
        self.assertEqual(s["epochWindows"], 2)
        self.assertEqual(s["maxTimerLatenessMs"], 35)
        self.assertEqual(s["timersLateOver100Ms"], 1)
        self.assertEqual((s["longestStepMs"], s["longestStep"]), (310, "epochBoundary"))

    def test_database_mounts_reuse_the_host_sibling_lease_inode(self):
        with tempfile.TemporaryDirectory() as d:
            db = pathlib.Path(d) / "db"
            db.mkdir()
            lock = db.parent / ".db.scarab-lock"
            mounts = cluster.database_mounts(db)
            inode = lock.stat().st_ino
            self.assertEqual(mounts, [f"{db}:/db", f"{lock}:/.db.scarab-lock"])
            self.assertEqual(cluster.database_mounts(db), mounts)
            self.assertEqual(lock.stat().st_ino, inode)
            self.assertEqual(list(db.iterdir()), [])
            alias = db.parent / "alias"
            alias.symlink_to(db, target_is_directory=True)
            self.assertEqual(cluster.database_mounts(alias)[1], mounts[1])


    def test_scala_container_uses_the_scenario_cpus(self):
        import argparse
        args = argparse.Namespace(name="n", scala_cpus=1.0)
        root = pathlib.Path("/r")
        self.assertIn("--cpus=1.0", cluster.scala_container(args, root, "cp", "M", []))
        self.assertIn("--cpus=2", cluster.scala_container(argparse.Namespace(name="n"), root, "cp", "M", []))
        self.assertIn("--cpus=3", cluster.scala_container(args, root, "cp", "M", [], cpus=3))


class SlotClockTest(unittest.TestCase):
    SHELLEY = {"systemStart": "2026-10-11T12:00:00Z", "slotLength": 0.1}
    START = 1791720000  # 2026-10-11T12:00:00Z

    def event(self, kind, at_slot, millis, **extra):
        import datetime
        total = self.START + (at_slot * 100 + millis) / 1000
        stamp = datetime.datetime.fromtimestamp(total, datetime.timezone.utc).isoformat()
        return {"event": kind, "at": stamp, **extra}

    def test_a_clock_in_step_and_a_clock_a_second_behind(self):
        in_step = [self.event("forged", 10, 12, hash="a", slot=10),
                   self.event("extended", 20, 8, block={"slot": 20, "hash": "b"})]
        self.assertEqual(pool.slot_clock(in_step, self.SHELLEY), {"forgeLatencyMs": 12, "arrivalMs": 8})
        behind = [self.event("forged", 10, 1010, hash="a", slot=10),
                  self.event("extended", 20, 9, block={"slot": 20, "hash": "b"}),
                  self.event("extended", 10, 12, block={"slot": 10, "hash": "a"})]
        clock = pool.slot_clock(behind, self.SHELLEY)
        self.assertEqual((clock["forgeLatencyMs"], clock["arrivalMs"]), (1010, 9))
        self.assertEqual(pool.slot_clock([], self.SHELLEY), {"forgeLatencyMs": None, "arrivalMs": None})

    def test_the_host_is_overloaded_when_its_load_exceeds_its_cpus(self):
        calm = {"load1": 3.0, "load5": 4.0, "cpus": 24}
        busy = {"load1": 30.0, "load5": 20.0, "cpus": 24}
        self.assertFalse(pool.overloaded(calm, calm))
        self.assertTrue(pool.overloaded(calm, busy))
        self.assertEqual(set(pool.host_load()), {"load1", "load5", "cpus"})

class NtcLocalTest(unittest.TestCase):
    """The pure parts of the LocalTxMonitor and local ChainSync comparisons."""

    BODY = cbor.enc({0: [[bytes(32), 0]], 1: [], 2: 170000})
    TX = bytes([0x84]) + BODY + cbor.enc({}) + b"\xf5\xf6"

    def test_transaction_id_is_the_hash_of_the_original_body_bytes(self):
        self.assertEqual(ntclocal.raw_tx_id(self.TX), ntclocal.blake(self.BODY))
        item = cbor.enc([6, cbor.Tag(24, self.TX)])
        self.assertEqual(ntclocal.tx_id(item), ntclocal.blake(self.BODY))

    def test_block_point_is_the_slot_and_the_hash_of_the_header(self):
        header = [[7, 4242, bytes(32), bytes(32)], b"signature"]
        block = bytes([0x82, 0x06, 0x85]) + cbor.enc(header) + cbor.enc([]) * 4
        item = cbor.enc(cbor.Tag(24, block))
        self.assertEqual(ntclocal.block_point(item), (4242, ntclocal.blake(cbor.enc(header))))

    def test_block_item_is_the_second_element_of_a_roll_forward(self):
        item = cbor.enc(cbor.Tag(24, b"abc"))
        raw = cbor.enc([2, cbor.Raw(item), [[1, bytes(32)], 1]])
        self.assertEqual(ntclocal.block_item(raw), item)

    @staticmethod
    def followed(reference, scala):
        return {"reference": {"intersect": reference}, "scala": {"intersect": scala}}

    def test_live_follow_takes_a_fresh_tip_while_either_node_lacks_the_tip(self):
        # the Scala node trails the reference: the reference can have dropped the tip (6) while the
        # Scala node still serves it (5), which compares nothing
        for first in ((6, 6), (6, 5), (5, 6)):
            answers = iter([first, (5, 5)])
            tips = iter([(1, "aa"), (2, "bb")])
            seen = []
            result = ntclocal.live_follow(lambda: next(tips), lambda tip: seen.append(tip) or self.followed(*next(answers)))
            self.assertEqual(seen, [(1, "aa"), (2, "bb")])
            self.assertEqual(result["attempts"], 2)
            self.assertEqual(result["reference"]["intersect"], 5)

    def test_live_follow_stops_after_its_attempts_and_without_a_tip(self):
        result = ntclocal.live_follow(lambda: (1, "aa"), lambda tip: self.followed(6, 5), attempts=3)
        self.assertEqual((result["attempts"], result["reference"]["intersect"]), (3, 6))
        self.assertIsNone(ntclocal.live_follow(lambda: None, lambda tip: self.followed(5, 5)))

    def snapshot(self, slot, txs, replies=(("sizes", b"a"),)):
        return {"slot": slot, "txs": list(txs), "steps": list(replies)}

    def test_snapshots_compare_only_with_the_same_slot_and_transactions(self):
        a = self.snapshot(5, [b"t1"])
        self.assertEqual(ntclocal.compare_snapshots(a, self.snapshot(5, [b"t1"])), (True, []))
        self.assertEqual(ntclocal.compare_snapshots(a, self.snapshot(6, [b"t1"])), (False, []))
        self.assertEqual(ntclocal.compare_snapshots(a, self.snapshot(5, [b"t1", b"t2"])), (False, []))

    def test_a_differing_answer_of_comparable_snapshots_is_reported_by_step(self):
        a = self.snapshot(5, [], [("sizes", b"1"), ("measures", b"2")])
        b = self.snapshot(5, [], [("sizes", b"1"), ("measures", b"3")])
        self.assertEqual(ntclocal.compare_snapshots(a, b), (True, [("measures", b"2", b"3")]))


class UserCliVerdictTest(unittest.TestCase):
    """What `ntccli` makes of the results of `cardano-cli latest` against the Scala node."""

    ok_tip = {"exit": 0, "output": "", "era": "Conway", "syncProgress": "100.00"}
    ok_build = {"buildExit": {"reference": 0, "scala": 0}, "buildOutput": {}, "bodiesEqual": True,
                "submitExit": 0, "reachedABlock": True}

    def test_all_passing_is_no_failure(self):
        self.assertEqual(ntccli.failures({"tip": self.ok_tip}, self.ok_build, []), [])

    def test_a_query_that_exits_non_zero_fails(self):
        failed = {"exit": 1, "output": "cardano-cli: bearer closed"}
        self.assertEqual(len(ntccli.failures({"tip": failed}, self.ok_build, [])), 1)

    def test_a_tip_that_is_not_conway_at_full_sync_fails(self):
        behind = {**self.ok_tip, "syncProgress": "99.00"}
        self.assertEqual(len(ntccli.failures({"tip": behind}, self.ok_build, [])), 1)

    def test_a_build_that_differs_or_a_submit_that_does_not_reach_a_block_fails(self):
        self.assertEqual(len(ntccli.failures({}, {**self.ok_build, "bodiesEqual": False}, [])), 1)
        self.assertEqual(len(ntccli.failures({}, {**self.ok_build, "reachedABlock": False}, [])), 1)

    def test_only_a_client_finishing_is_an_expected_close(self):
        bad = ["connection closed: read: broken pipe", "failed: IllegalArgumentException: boom",
               "network defect: boom", "service unavailable: script evaluation"]
        events = [{"event": "inbound", "state": "closed", "reason": r}
                  for r in ["closed by peer", "connection closed: closed by peer", "client finished"] + bad]
        events.append({"event": "inbound", "state": "accepted", "reason": None})
        self.assertEqual([e["reason"] for e in ntccli.unexpected_closes(events)], bad)
        self.assertEqual(len(ntccli.failures({}, self.ok_build, ntccli.unexpected_closes(events))), 1)


class PeercutPolicyTest(unittest.TestCase):
    def test_loss_requires_the_closed_policy_and_proxy_identity(self):
        event = {"event": "peerSelectionDemote", "action": "asynchronous",
                 "peer": "127.0.0.1:3400", "failure": "Transient"}
        self.assertTrue(peercut.transient_proxy_loss([event]))
        for field, bad in [("failure", "Misbehaved"), ("action", "done"),
                           ("peer", "127.0.0.1:3001"), ("event", "reconnecting")]:
            self.assertFalse(peercut.transient_proxy_loss([dict(event, **{field: bad})]))

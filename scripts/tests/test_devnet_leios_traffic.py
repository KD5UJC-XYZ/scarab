# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the reference-only `leios-traffic` scenario (`devnet/scenarios/leios_traffic.py`, #73): the Leios
timing of the genesis spec, the registration and load commands, the reading of Leios headers and certificates, the
judge and the trace counts. No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, cli, dijkstra, util  # noqa: E402
from devnet.scenarios import leios_traffic as lt  # noqa: E402

TESTNET_DIJKSTRA = {"leiosAnnouncementPeriodLength": 1000, "leiosVotePeriodLength": 4000,
                    "leiosDiffusionPeriodLength": 7000, "leiosCommitteeSize": 900, "minPoolMargin": 0.015}


def leios_block(block_no, slot, prev, announces=None, signers=None, tag=8):
    """A `[tag, block]` with a 12-field header body: `announces` an EB hash, `signers` the certificate's bitfield."""
    body = [block_no, slot, prev, b"i" * 32, b"v" * 32, [b"o" * 64, b"r" * 80], 0, b"h" * 32,
            [b"k" * 32, 0, 0, b"s" * 64], [12, 0], signers is not None,
            None if announces is None else [announces, 70]]
    cert = None if signers is None else [signers, b"g" * 48]
    return cbor.enc([tag, [[body, b"x" * 448], [[], cert, None]]])


def chain(*blocks):
    """Described blocks from (announces, signers) pairs, each extending the one before."""
    out, prev = [], None
    for n, (announces, signers) in enumerate(blocks):
        described = lt.describe_leios_block(leios_block(n, 10 * n, prev, announces, signers))
        out.append(described)
        prev = bytes.fromhex(described["hash"])
    return out


class TimingTest(unittest.TestCase):
    def test_the_periods_are_the_testnets_in_slots(self):
        spec = lt.dijkstra_spec(TESTNET_DIJKSTRA, 1, 0.1)
        self.assertEqual([spec[k] for k in lt.LEIOS_PERIODS], [100, 400, 700])
        self.assertEqual(spec["leiosCommitteeSize"], 900, "the other parameters are the testnet's")
        self.assertEqual(TESTNET_DIJKSTRA["leiosVotePeriodLength"], 4000, "the input is not changed")

    def test_the_certification_gap_is_the_testnets_14_slots(self):
        self.assertEqual(lt.certification_gap(TESTNET_DIJKSTRA, 1), 14)
        self.assertEqual(lt.certification_gap(lt.dijkstra_spec(TESTNET_DIJKSTRA, 1, 0.1), 0.1), 14)
        self.assertEqual(lt.certification_gap(lt.dijkstra_spec(TESTNET_DIJKSTRA, 1, 0.2), 0.2), 14)

    def test_the_gap_rounds_a_part_slot_up(self):
        self.assertEqual(lt.certification_gap(TESTNET_DIJKSTRA, 1.5), 10, "14,000 ms in 1,500 ms slots")

    def test_half_the_announcements_are_certifiable_at_the_testnets_f(self):
        self.assertAlmostEqual(lt.certifiable_share(0.05, 14), 0.513, places=3)
        self.assertLess(lt.certifiable_share(0.2, 140), 1e-12, "up-dijkstra's f with the testnet's periods")

    def test_the_committee_holds_a_key_two_boundaries_after_its_registration(self):
        self.assertEqual(lt.committee_epoch(1), 3)


class LoadTest(unittest.TestCase):
    def test_the_rb_holds_the_body_less_its_fixed_overhead(self):
        self.assertEqual(lt.rb_capacity({"maxBlockBodySize": 90112}), 89088)

    def test_the_padding_stays_inside_the_transaction_size(self):
        metadata = lt.padding_metadata(14000)
        strings = metadata["674"]["msg"]
        self.assertTrue(all(len(s.encode()) == 64 for s in strings), "a metadatum string holds 64 bytes")
        self.assertLess(len(cbor.enc(strings)), 14000)
        self.assertGreater(len(cbor.enc(strings)), 13000)

    def test_a_wave_tops_the_mempool_up_to_the_target(self):
        self.assertEqual(lt.wave_size(0, 133632, 14000, 400), 10)
        self.assertEqual(lt.wave_size(120000, 133632, 14000, 400), 1)
        self.assertEqual(lt.wave_size(133632, 133632, 14000, 400), 0)
        self.assertEqual(lt.wave_size(0, 133632, 14000, 3), 3, "no more than are left")

    def test_each_input_line_makes_one_signed_load_transaction(self):
        script = lt.load_txs_script("addr", 900000)
        self.assertTrue(script.startswith("while read i txin; do "))
        self.assertIn(f"--tx-out addr+{lt.FAN_OUT_LOVELACE - 900000} --fee 900000", script)
        self.assertIn(f"--out-file {lt.LOAD}/t$i.tx || exit 1; done < {lt.LOAD}/inputs", script)

    def test_the_fan_outs_cover_the_load(self):
        self.assertEqual(lt.fan_out_plan(400, 200), [200, 200])
        self.assertEqual(lt.fan_out_plan(450, 200), [200, 200, 50])

    def test_a_fan_out_keeps_its_change_and_refuses_too_little(self):
        command, change = lt.fan_out_command(0, "aa#0", 10 * lt.FAN_OUT_LOVELACE, "addr", 2, 1_000_000)
        self.assertEqual(change, 8 * lt.FAN_OUT_LOVELACE - 1_000_000)
        self.assertEqual(command.count(f"--tx-out addr+{lt.FAN_OUT_LOVELACE}"), 2)
        self.assertIn(f"--tx-out addr+{change} --fee 1000000", command)
        with self.assertRaises(ValueError):
            lt.fan_out_command(0, "aa#0", 2 * lt.FAN_OUT_LOVELACE, "addr", 2, 1)


class RegistrationTest(unittest.TestCase):
    def test_the_certificate_carries_the_bls_key_and_the_minimum_cost_and_margin(self):
        command = lt.registration_certificate_command(2, 170000000, 0.015)
        for part in ("cardano-cli dijkstra stake-pool registration-certificate", "--pool-cost 170000000",
                     "--pool-margin 0.015", "--bls-signing-key-file /work/env/pools-keys/pool2/bls.skey",
                     "--cold-verification-key-file /work/env/pools-keys/pool2/cold.vkey",
                     "--vrf-verification-key-file /work/env/pools-keys/pool2/vrf.vkey"):
            self.assertIn(part, command)

    def test_the_transaction_is_witnessed_by_every_cold_and_reward_key(self):
        command = lt.registration_tx_command(3, "aa#0", "addr")
        self.assertEqual(command.count("--certificate-file"), 3)
        self.assertIn("--witness-override 7", command)
        for n in (1, 2, 3):
            self.assertIn(f"pool{n}/cold.skey", command)
            self.assertIn(f"pool{n}/staking-reward.skey", command)

    def test_the_node_is_given_its_bls_key(self):
        command = dijkstra.node_command(2, "/work/env/pools-keys/pool2/bls.skey")
        self.assertIn("--shelley-bls-key /work/env/pools-keys/pool2/bls.skey ", command)
        self.assertNotIn("--shelley-bls-key", dijkstra.node_command(2))


class BlockTest(unittest.TestCase):
    def test_a_captured_dijkstra_block_announces_nothing(self):
        rows = [r.split("\t") for r in (util.REPO / "fixtures/dijkstra-devnet/blocks.tsv").read_text().splitlines()[1:]]
        described = [lt.describe_leios_block(bytes.fromhex(r[4])) for r in rows]
        self.assertEqual([d["hash"] for d in described], [r[3] for r in rows])
        self.assertTrue(all(d["prevHash"] == p["hash"] for p, d in zip(described, described[1:])))
        dijkstra_blocks = [d for d in described if d["era"] == "dijkstra"]
        self.assertTrue(dijkstra_blocks)
        self.assertTrue(all(d["announces"] is None and not d["certifies"] for d in dijkstra_blocks))

    def test_an_announcement_and_a_certificate_are_read(self):
        announcing, certifying = chain((b"e" * 32, None), (None, b"\x0f\x01"))
        self.assertEqual((announcing["announces"], announcing["ebSize"]), ((b"e" * 32).hex(), 70))
        self.assertFalse(announcing["certifies"])
        self.assertEqual((certifying["certifies"], certifying["signers"]), (True, 5))
        self.assertEqual(certifying["prevHash"], announcing["hash"])

    def test_a_header_that_disagrees_with_its_body_is_refused(self):
        raw = leios_block(1, 1, None, None, b"\x01").replace(b"\xf5", b"\xf4", 1)
        with self.assertRaises(ValueError):
            lt.describe_leios_block(raw)


class JudgeTest(unittest.TestCase):
    EB = b"e" * 32

    def test_an_announcement_certified_by_its_child_on_every_node_passes(self):
        blocks = chain((None, None), (self.EB, None), (None, b"\x0f"), (None, None))
        verdict = lt.judge({1: blocks, 2: blocks[:3]}, {1: {self.EB.hex()}, 2: {self.EB.hex()}},
                           {1: {"LeiosVoted": 2}, 2: {"LeiosVoted": 2}})
        self.assertTrue(verdict["pass"], verdict["problems"])
        self.assertEqual((verdict["commonBlocks"], verdict["announcingBlocks"], verdict["certifyingBlocks"]), (3, 1, 1))
        self.assertEqual((verdict["votesCast"], verdict["certificateSigners"]), (4, 4))
        self.assertEqual(verdict["certified"][0]["gapSlots"], 10)

    def test_fewer_certificates_than_asked_for_fail(self):
        blocks = chain((None, None), (self.EB, None), (None, b"\x0f"), (None, None))
        verdict = lt.judge({1: blocks, 2: blocks}, {1: {self.EB.hex()}, 2: {self.EB.hex()}},
                           {1: {"LeiosVoted": 2}, 2: {"LeiosVoted": 2}}, certificates=3)
        self.assertFalse(verdict["pass"])
        self.assertTrue(any("fewer than the 3 asked for" in p for p in verdict["problems"]), verdict["problems"])

    def test_a_certificate_only_one_node_holds_fails(self):
        blocks = chain((self.EB, None), (None, b"\x0f"))
        verdict = lt.judge({1: blocks, 2: blocks[:1]})
        self.assertFalse(verdict["pass"])
        self.assertIn("no block every node holds certifies an endorser block", verdict["problems"])

    def test_chains_that_differ_fail(self):
        verdict = lt.judge({1: chain((self.EB, None), (None, b"\x0f")), 2: chain((None, None))})
        self.assertFalse(verdict["pass"])
        self.assertIn("node 2's immutable chain is not a prefix of the longest", verdict["problems"])

    def test_a_missing_closure_or_no_votes_fails(self):
        blocks = chain((self.EB, None), (None, b"\x0f"))
        verdict = lt.judge({1: blocks, 2: blocks}, {1: {self.EB.hex()}, 2: set()}, {1: {}, 2: {}})
        self.assertFalse(verdict["pass"])
        self.assertEqual(len(verdict["problems"]), 2, verdict["problems"])

    def test_no_announcement_fails(self):
        verdict = lt.judge({1: chain((None, None))})
        self.assertIn("no block every node holds announces an endorser block", verdict["problems"])


class TraceTest(unittest.TestCase):
    def test_leios_kinds_and_not_voted_reasons_are_counted(self):
        lines = [json.dumps({"ns": "Consensus.LeiosKernel", "data": {"kind": "LeiosVoted"}}),
                 json.dumps({"ns": "Consensus.LeiosKernel", "data": {"kind": "LeiosVoted"}}),
                 json.dumps({"data": {"kind": "LeiosNotVoted", "reason": "TooLate"}}),
                 json.dumps({"data": {"kind": "TraceAddBlockEvent"}}), "not json", "[]"]
        kinds, reasons = lt.trace_kinds(lines)
        self.assertEqual(kinds, {"LeiosVoted": 2, "LeiosNotVoted": 1})
        self.assertEqual(reasons, {"TooLate": 1})


class ProgressTest(unittest.TestCase):
    def test_the_uniq_counts_of_the_logs_are_read(self):
        text = '      7 "kind":"LeiosBlockCertified"\n     35 "kind":"LeiosVoted"\n'
        self.assertEqual(lt.kind_counts(text), {"LeiosBlockCertified": 7, "LeiosVoted": 35})
        self.assertEqual(lt.kind_counts(""), {})

    def test_the_command_counts_the_three_progress_kinds_of_every_log(self):
        self.assertIn("/work/node*.log", lt.KIND_COUNT_COMMAND)
        for kind in ("BlockForged", "BlockCertified", "Voted"):
            self.assertIn(kind, lt.KIND_COUNT_COMMAND)


class VotingTest(unittest.TestCase):
    """`--follow-vote`: the follower's own votes, the reference nodes that counted them, and the verdict."""

    events = [{"event": "leiosVoted", "rbHash": "aa", "seat": 4, "weight": "1/5"},
              {"event": "leiosVoted", "rbHash": "bb", "seat": 4, "weight": "1/5"},
              {"event": "leiosNotVoted", "ebHash": "cc", "ebSlot": 3, "reason": "tooLate"}]

    def test_the_followers_votes_are_its_voted_lines(self):
        self.assertEqual(lt.follower_votes(self.events), {("aa", 4), ("bb", 4)})

    def test_a_reference_node_counted_only_the_votes_it_acquired_of_the_follower(self):
        def line(rb, voter, kind="LeiosVoteAcquired"):
            return json.dumps({"data": {"kind": kind, "vote": {"rbHash": rb, "voterId": voter}}})
        lines = [line("aa", 4), line("aa", 4), line("aa", 1), line("bb", 4, kind="LeiosVoted"), "not json"]
        self.assertEqual(lt.acquired_votes(lines, {("aa", 4), ("bb", 4)}), 1)

    def test_no_vote_is_incomplete_and_no_vote_acquired_fails(self):
        self.assertEqual(lt.voting_verdict(set(), {1: 0})["verdict"], "incomplete")
        self.assertEqual(lt.voting_verdict({("aa", 4)}, {1: 0, 2: 0})["verdict"], "failed")
        self.assertEqual(lt.voting_verdict({("aa", 4)}, {1: 0, 2: 1}), {"verdict": "passed", "problems": []})

    def test_with_quiet_pools_every_certificate_must_count_the_followers_seat(self):
        cast, acquired = {("aa", 4)}, {1: 1}
        self.assertEqual(lt.voting_verdict(cast, acquired, 2, 0)["verdict"], "passed")
        self.assertEqual(lt.voting_verdict(cast, acquired, 2, 1, needed=True)["verdict"], "failed")
        self.assertEqual(lt.voting_verdict(cast, acquired, 0, 0, needed=True)["verdict"], "incomplete")
        self.assertEqual(lt.voting_verdict(cast, acquired, 2, 2, needed=True)["verdict"], "passed")

    def test_a_certificates_seats_are_its_set_bits_most_significant_first(self):
        self.assertEqual(lt.signer_seats(bytes([0b10100000, 0b00000001])), [0, 2, 15])
        chain = [{"certifies": True, "signerSeats": [0, 1, 2, 3]}, {"certifies": True, "signerSeats": [0, 4]},
                 {"certifies": False, "signerSeats": None}]
        self.assertEqual((lt.certified_with(chain, [4]), lt.certified_with(chain, [5])), (1, 0))

    def test_the_voters_listener_is_a_further_local_root(self):
        roots = dijkstra.topology(2, 3, (lt.VOTER_PORT,))["localRoots"][0]
        self.assertEqual([a["port"] for a in roots["accessPoints"]], [3001, 3003, lt.VOTER_PORT])
        self.assertEqual(roots["valency"], 3)


class ForgingTest(unittest.TestCase):
    """`--follow-forge`: the follower's ranking, endorser and certifying blocks on every reference node's chain."""

    @staticmethod
    def block(no, era="dijkstra", announces=None, certifies=False, prev=None):
        return {"era": era, "blockNo": no, "slot": 10 * no, "hash": f"h{no}", "prevHash": prev,
                "announces": announces, "ebSize": 1 if announces else None, "certifies": certifies,
                "signers": 4 if certifies else None}

    def chains(self):
        chain = [self.block(1, era="conway"), self.block(2, announces="e2"), self.block(3, certifies=True, prev="h2"),
                 self.block(4)]
        return {1: chain, 2: chain[:3]}

    events = [{"event": "forged", "hash": "h2", "slot": 20}, {"event": "forged", "hash": "h3", "slot": 30},
              {"event": "leiosBlockForged", "hash": "e2"}, {"event": "leiosBlockCertified", "atSlot": 30},
              {"event": "leiosVoted", "vote": {"rbHash": "h2", "voterId": 4}}]

    def test_the_followers_blocks_on_every_chain_with_the_closure_everywhere_pass(self):
        verdict = lt.forging_verdict(self.chains(), {1: {"e2"}, 2: {"e2"}}, self.events)
        self.assertEqual(verdict["verdict"], "passed", verdict)
        self.assertEqual((verdict["dijkstraOnChain"], verdict["endorserBlocksEverywhere"],
                          verdict["certifyingOnChain"]), (2, 1, 1))
        self.assertEqual(verdict["lines"], {"forged": 2, "leiosBlockForged": 1, "leiosBlockCertified": 1,
                                            "leiosVoted": 1})

    def test_a_closure_one_node_lacks_fails(self):
        verdict = lt.forging_verdict(self.chains(), {1: {"e2"}, 2: set()}, self.events)
        self.assertEqual(verdict["verdict"], "failed")

    def test_no_certifying_block_forged_is_incomplete_and_one_off_the_chain_fails(self):
        uncertified = [e for e in self.events if e["event"] != "leiosBlockCertified"]
        self.assertEqual(lt.forging_verdict(self.chains(), {1: {"e2"}, 2: {"e2"}}, uncertified)["verdict"],
                         "incomplete")
        lost = [e for e in self.events if e.get("hash") != "h3"]
        self.assertEqual(lt.forging_verdict(self.chains(), {1: {"e2"}, 2: {"e2"}}, lost)["verdict"], "failed")

    def test_a_lost_slot_battle_is_no_failure_of_a_forging_follower(self):
        kinds = lt.forging_severities({"didntAdoptBlock": "Error", "fatal": "Critical"})
        self.assertEqual(kinds, {"didntAdoptBlock": "Warning", "fatal": "Critical"})

    def test_the_forging_pools_node_is_not_started(self):
        calls = []
        args = type("A", (), {"name": "n", "pools": 3})()
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "genesis").mkdir()
            with mock.patch.object(dijkstra, "in_ref"), mock.patch.object(dijkstra, "put_file"), \
                    mock.patch.object(dijkstra, "docker", side_effect=lambda *a: calls.append(a)), \
                    mock.patch.object(dijkstra.subprocess, "run", return_value=mock.Mock(stdout=b"{}")):
                ports = dijkstra.start_nodes(args, root, skip=2)
        self.assertEqual(ports, {1: 3001, 3: 3003})
        self.assertEqual(len(calls), 2)


class CliTest(unittest.TestCase):
    def test_the_command_is_a_run_with_its_own_default_name(self):
        self.assertIn("leios-traffic", cli.RUNS)
        seen = {}
        with patch.object(sys, "argv", ["devnet.py", "leios-traffic"]), \
                patch.object(cli, "run", lambda args: seen.update(vars(args))):
            cli.main()
        self.assertEqual(seen["name"], "scarab-leios")
        self.assertEqual((seen["pools"], seen["active_slots_coeff"], seen["slot_length"]), (5, 0.05, 0.1))
        self.assertIs(seen["run"], lt.leios_traffic)


if __name__ == "__main__":
    unittest.main()

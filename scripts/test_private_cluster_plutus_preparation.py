#!/usr/bin/env python3
"""Offline guards for fresh batched identity reads and preparation diagnostics."""
import copy
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import private_cluster_fork as process
import private_cluster_plutus_submission as c


def controller(launcher=None):
    return c.controller_type(SimpleNamespace(Launcher=launcher or type("Launcher", (), {}),
        process=SimpleNamespace(process_identity=process.process_identity, same_tip=lambda a, b: a == b)))


def stat(pid=42, ticks="123", state="S", comm="cardano-node"):
    return f"{pid} ({comm}) {state} " + "0 " * 18 + ticks + " 0\n"


def tip(slot=90):
    return dict(era="Conway", epoch=0, slot=slot, block=1, hash="1" * 64)


class BatchedProcessTests(unittest.TestCase):
    def setUp(self):
        self.obj = object.__new__(controller())
        self.argv = ["/opt/reference/bin/cardano-node", "run", "--config", "/owned/config"]
        self.identity = dict(pid=42, startTicks="123", argv=self.argv)
        self.obj.active = {1: dict(pid=42, argv=self.argv, identity=self.identity)}
        self.calls = []
        self.raw = self.frame()
        def execute(*args):
            self.calls.append(args)
            return SimpleNamespace(stdout=self.raw)
        self.obj.execute = execute

    def frame(self, before=None, argv=None, after=None):
        return (stat() if before is None else before) + "\0".join(self.argv if argv is None else argv) + "\0" + (
            stat() if after is None else after)

    def test_one_exec_keeps_original_identity_and_fresh_reads(self):
        self.assertEqual(self.obj.process(1), self.identity)
        self.assertEqual(self.calls, [("cat", "--", "/proc/42/stat", "/proc/42/cmdline", "/proc/42/stat")])
        self.raw = self.frame(after=stat(ticks="124"))
        with self.assertRaisesRegex(ValueError, "PID/start identity changed"):
            self.obj.process(1)
        self.assertEqual(len(self.calls), 2)

    def test_argv_newlines_and_empty_arguments_are_preserved(self):
        self.argv.extend(["with\nnewline", "", "last", ""])
        self.raw = self.frame()
        self.assertEqual(self.obj.process(1), self.identity)

    def test_replaced_or_exited_process_and_changed_argv_rejected(self):
        values = [self.frame(before=stat(pid=43)), self.frame(after=stat(pid=43)),
                  self.frame(before=stat(ticks="124")), self.frame(after=stat(ticks="124")),
                  self.frame(before=stat(state="Z")), self.frame(after=stat(state="X")),
                  self.frame(argv=["foreign", "run"])]
        for raw in values:
            with self.subTest(raw=repr(raw)), self.assertRaises(ValueError):
                self.raw = raw
                self.obj.process(1)

    def test_truncated_ambiguous_or_oversized_frame_fails_closed(self):
        values = ["", stat(), stat()+"unterminated"+stat(), self.frame(after=""),
                  self.frame(after="42 (cardano-node) S\n"), self.frame()[:-1],
                  self.frame(before=stat(comm="cardano\nnode")), "x" * 65537]
        for raw in values:
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                self.raw = raw
                self.obj.process(1)

    def test_invalid_saved_pid_is_rejected_before_execution(self):
        for pid in (True, 1, "42"):
            self.obj.active[1]["pid"] = pid
            with self.subTest(pid=pid), self.assertRaises(ValueError):
                self.obj.process(1)
        self.assertEqual(self.calls, [])

    def test_disappearing_process_command_failure_is_not_retried_or_replaced(self):
        error = ValueError("helper failed: process disappeared")
        calls = []
        def fail(*args):
            calls.append(args)
            raise error
        self.obj.execute = fail
        with self.assertRaises(ValueError) as caught:
            self.obj.process(1)
        self.assertIs(caught.exception, error)
        self.assertEqual(len(calls), 1)


class DockerTimingTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.result = SimpleNamespace(returncode=0, stdout="private output", stderr="")
        self.failure = None
        owner = self
        class Launcher:
            def docker(self, *args, **kwargs):
                owner.calls.append((args, kwargs))
                if owner.failure is not None:
                    raise owner.failure
                return owner.result
        self.obj = object.__new__(controller(Launcher))
        self.obj.deadline = 103
        self.obj._preparation_commands = []
        self.obj._preparation_stage = "funding-after"

    def test_forwarding_and_deadline_ceiling_are_exactly_described(self):
        args = ("exec", "container", "cat", "/proc/42/cmdline")
        options = dict(data=b"secret", check=False, timeout=9)
        with patch.object(c.time, "monotonic", side_effect=[100, 102]):
            self.assertIs(self.obj.docker(*args, **options), self.result)
        self.assertEqual(self.calls, [(args, options)])
        row = self.obj._preparation_commands[0]
        self.assertEqual((row["requestedTimeoutSeconds"], row["remainingBudgetSeconds"],
                          row["dispatchTimeoutCeilingSeconds"], row["elapsedSeconds"]), (9, 3, 3, 2))
        self.assertEqual((row["stage"], row["executable"], row["processFiles"]),
                         ("funding-after", "cat", ["/proc/42/cmdline"]))
        self.assertNotIn("secret", repr(row))
        self.assertNotIn("private output", repr(row))

    def test_default_and_explicit_timeout_caps(self):
        self.obj.deadline = 130
        for options, expected in (({}, 10), (dict(timeout=4), 4)):
            with patch.object(c.time, "monotonic", return_value=100):
                self.obj.docker("inspect", "container", **options)
            self.assertEqual(self.obj._preparation_commands[-1]["dispatchTimeoutCeilingSeconds"], expected)

    def test_original_baseexceptions_survive_even_when_budget_is_exhausted(self):
        self.obj.deadline = 99
        for failure in (TimeoutError("private failure"), KeyboardInterrupt(), SystemExit()):
            self.failure = failure
            with patch.object(c.time, "monotonic", side_effect=[100, 102]):
                with self.assertRaises(type(failure)) as caught:
                    self.obj.docker("inspect", "container")
            self.assertIs(caught.exception, failure)
            row = self.obj._preparation_commands[-1]
            self.assertEqual(row["dispatchTimeoutCeilingSeconds"], 0)
            self.assertEqual(row["errorType"], type(failure).__name__)
            self.assertNotIn("private failure", repr(row))
        self.assertEqual(len(self.calls), 3)  # Dispatch policy remains inherited.

    def test_no_recording_outside_preparation(self):
        self.obj._preparation_commands = None
        with patch.object(c.time, "monotonic", side_effect=AssertionError("unexpected timing")):
            self.assertIs(self.obj.docker("inspect", "container"), self.result)


class PreparationReceiptTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.obj = object.__new__(controller())
        self.obj.out = Path(temporary.name)
        self.obj.boundary_ms, self.obj.deadline = 1_100_000, 9000
        self.obj.prepare_funding = lambda anchor: tip(200)
        self.clock = patch.object(c.time, "monotonic", return_value=5000)
        self.clock.start()
        self.addCleanup(self.clock.stop)
        self.wall = [1_020_000_000_000]
        self.wallclock = patch.object(c.time, "time_ns", side_effect=lambda: self.wall[0])
        self.wallclock.start()
        self.addCleanup(self.wallclock.stop)

    def read(self):
        return c.base.decode((self.obj.out / "preparation-timing.json").read_bytes())

    def test_success_flushes_new_receipt_and_restores_deadline(self):
        self.assertEqual(self.obj.prepare_initial(tip()), tip(200))
        receipt = self.read()
        self.assertEqual((receipt["outcome"], receipt["preparationDeadlineMonotonicSeconds"],
                          receipt["operationDeadlineMonotonicSeconds"], receipt["slot300DeadlineUnixMillis"]),
                         ("body-completed", 5010, 9000, 1_030_000))
        self.assertFalse(receipt["preparationAcceptanceProven"])
        self.assertIsNone(self.obj._preparation_commands)
        self.assertEqual(self.obj.deadline, 9000)

    def test_failure_is_recorded_and_original_exception_preserved(self):
        error = TimeoutError("funding-after timeout")
        def fail(_): raise error
        self.obj.prepare_funding = fail
        with self.assertRaises(TimeoutError) as caught:
            self.obj.prepare_initial(tip())
        self.assertIs(caught.exception, error)
        self.assertEqual((self.read()["outcome"], self.read()["errorType"]), ("failed", "TimeoutError"))
        self.assertEqual(self.obj.deadline, 9000)

    def test_failed_receipt_cannot_mask_any_original_baseexception(self):
        for error in (TimeoutError(), KeyboardInterrupt(), SystemExit()):
            def fail(_): raise error
            self.obj.prepare_funding = fail
            with patch.object(c.base, "write", side_effect=OSError("diagnostic unavailable")):
                with self.assertRaises(type(error)) as caught:
                    self.obj.prepare_initial(tip())
            self.assertIs(caught.exception, error)
            self.assertEqual(self.obj.deadline, 9000)
            self.assertIsNone(self.obj._preparation_commands)

    def test_failed_success_receipt_fails_closed(self):
        with patch.object(c.base, "write", side_effect=OSError("diagnostic unavailable")):
            with self.assertRaises(OSError):
                self.obj.prepare_initial(tip())
        self.assertEqual(self.obj.deadline, 9000)
        self.assertIsNone(self.obj._preparation_commands)

    def test_late_return_records_failure_and_slow_receipt_cannot_extend_window(self):
        def late(_):
            self.wall[0] = 1_030_000_000_000
            return tip(299)
        self.obj.prepare_funding = late
        with self.assertRaisesRegex(TimeoutError, "slot-300"):
            self.obj.prepare_initial(tip())
        self.assertEqual(self.read()["outcome"], "failed")
        (self.obj.out / "preparation-timing.json").unlink()
        self.wall[0] = 1_020_000_000_000
        self.obj.prepare_funding = lambda _: tip(299)
        original_write = c.base.write
        def slow_write(path, value):
            original_write(path, value)
            self.wall[0] = 1_030_000_000_000
        with patch.object(c.base, "write", side_effect=slow_write):
            with self.assertRaisesRegex(TimeoutError, "slot-300"):
                self.obj.prepare_initial(tip())
        self.assertEqual(self.obj.deadline, 9000)
        self.assertEqual(self.read()["outcome"], "body-completed")
        self.assertFalse(self.read()["preparationAcceptanceProven"])


class SnapshotBracketTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.obj = object.__new__(controller())
        self.obj.out = Path(temporary.name)
        self.obj._preparation_stage = "funding"
        self.roles = {str(i): dict(pid=i+40, startTicks="123", argv=["keyless"]) for i in (1, 2)}
        self.events = []
        self.final_roles = copy.deepcopy(self.roles)
        def roles():
            self.events.append("roles")
            return self.roles if len(self.events) == 1 else self.final_roles
        self.obj.roles = roles
        def query(node, kind, *options):
            self.events.append(kind)
            self.assertEqual(self.obj._preparation_stage, "funding-after")
            if kind == "utxo": return "{}\n"
            return '{"stateBefore":{"esLState":{"utxoState":{"fees":0}}}}\n'
        self.obj.query_node = query
        self.tips = {1: tip(), 2: tip()}
        def final_tip(node):
            self.events.append("tip"+str(node))
            return self.tips[node]
        self.obj.tip = final_tip

    def test_fresh_both_node_brackets_and_originals_remain_separate(self):
        # Snapshot.check requires nonempty UTxO; isolate acquisition/guard behavior.
        with patch.object(c.fixture.Snapshot, "checked", lambda self: self):
            self.obj.snapshot("funding-after", tip())
        self.assertEqual(self.events, ["roles", "utxo", "ledger-state", "roles", "tip1", "tip2"])
        bracket = c.base.decode((self.obj.out / "funding-after-bracket.json").read_bytes())
        self.assertTrue(bracket["separateAcquisitions"])
        self.assertFalse(bracket["atomicSnapshot"])
        self.assertEqual((self.obj.out / "funding-after-utxo.json").read_text(), "{}\n")
        self.assertEqual(self.obj._preparation_stage, "funding")

    def test_changed_pid_start_or_argv_cannot_hide_behind_unchanged_tips(self):
        for node in ("1", "2"):
            for field, value in (("pid", 99), ("startTicks", "124"), ("argv", ["forging"])):
                self.final_roles = copy.deepcopy(self.roles)
                self.final_roles[node][field] = value
                self.events.clear()
                with self.subTest(node=node, field=field), self.assertRaises(ValueError):
                    self.obj.snapshot("funding-after", tip())
                self.assertEqual(list(self.obj.out.iterdir()), [])
                self.assertEqual(self.obj._preparation_stage, "funding")

    def test_each_final_tip_and_both_node_presence_still_checked(self):
        for node in (1, 2):
            for field, value in (("slot", 91), ("block", 2), ("hash", "2" * 64)):
                self.tips = {1: tip(), 2: tip()}
                self.tips[node][field] = value
                self.events.clear()
                with self.subTest(node=node, field=field), self.assertRaises(ValueError):
                    self.obj.snapshot("funding-after", tip())
                self.assertEqual(list(self.obj.out.iterdir()), [])
        self.roles.pop("2")
        self.events.clear()
        with self.assertRaisesRegex(ValueError, "both owned"):
            self.obj.snapshot("funding-after", tip())
        self.assertEqual(self.events, ["roles"])


if __name__ == "__main__": unittest.main()

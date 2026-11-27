# SPDX-License-Identifier: Apache-2.0
"""The secret-path timing check (`scripts/secret_timing.py`): its reading of the tool's reports, the repeat rule
that turns runs into a verdict, the summaries and the commands it runs. Offline: no Docker, no JVM."""
import argparse
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import secret_timing as timing  # noqa: E402
from conformance.scenarios import TURBO_REFERENCE_CPU  # noqa: E402

ROLES = {
    "vrf-prove": "secret",
    "kes-sign": "secret",
    "control-random": "noise-control",
    "control-field": "value-control",
    "control-vartime": "leak-control",
}
CLEAN = {"vrf-prove": 2.0, "kes-sign": 3.0, "control-random": 1.5, "control-field": 4.0, "control-vartime": 2500.0}


def record(statistics=None, flaws=(), probably=10.0):
    """A report as the tool writes it, its statistics `CLEAN`'s with `statistics` over them."""
    values = {**CLEAN, **(statistics or {})}
    return {
        "tool": "SecretPathTiming", "version": 1, "measurements": 100000, "proofs": 25000, "crops": 100,
        "probably": probably, "definitely": 500.0,
        "targets": [{"id": ident, "name": f"the {ident}", "role": ROLES[ident], "statistic": value,
                     "over": value > probably} for ident, value in values.items()],
        "conclusive": not flaws, "flaws": list(flaws), "sink": "0x00000000",
    }


def report(statistics=None, flaws=(), cpu="Some CPU"):
    return timing.parse_report(json.dumps(record(statistics, flaws)), "run.json", cpu)


NOISY = ("control: [k]H, random/random reads 14.0, above 10: this machine reports a difference where there is none",)


class ReadingTest(unittest.TestCase):
    def test_a_report_of_the_tool_is_read_with_every_target(self):
        read = report()
        self.assertEqual([target.id for target in read.targets], list(CLEAN), "every target, in the tool's order")
        self.assertEqual(read.probably, 10.0, "the threshold is the report's own")
        self.assertTrue(read.conclusive, "no flaw, so conclusive")
        self.assertEqual(read.cpu, "Some CPU", "the CPU it was read with")

    def test_what_is_not_a_report_is_refused(self):
        cases = {
            "another tool": {**record(), "tool": "TimingProbeMain"},
            "another version": {**record(), "version": 2},
            "a role missing": {**record(), "targets": [t for t in record()["targets"] if t["role"] != "leak-control"]},
            "an id twice": {**record(), "targets": record()["targets"] + record()["targets"][:1]},
            "conclusive with a flaw": {**record(), "flaws": ["a control failed"]},
            "a statistic that is no number": {**record(), "targets": [{**t, "statistic": None}
                                                                     for t in record()["targets"]]},
            "no threshold": {**record(), "probably": 0},
        }
        for case, value in cases.items():
            with self.subTest(case=case):
                with self.assertRaises(timing.CheckError, msg=f"{case} is refused"):
                    timing.parse_report(json.dumps(value), "run.json", None)
        with self.assertRaises(timing.CheckError, msg="text that is not JSON is refused"):
            timing.parse_report("SecretPathTiming: 100000 measurements", "run.json", None)


class RuleTest(unittest.TestCase):
    def test_no_secret_target_above_the_threshold_is_no_leak(self):
        decision = timing.decide([report(), report(), report()])
        self.assertEqual(decision.status, timing.NO_LEAK, "three conclusive runs, nothing above 10")
        self.assertEqual(decision.conclusive, 3, "every run conclusive")
        self.assertEqual(decision.confirmed, (), "nothing confirmed")

    def test_one_target_above_in_two_conclusive_runs_is_a_leak(self):
        decision = timing.decide([report({"kes-sign": 40.0}), report(), report({"kes-sign": 12.0})])
        self.assertEqual(decision.status, timing.LEAK, "above 10 in two of three conclusive runs")
        self.assertEqual(decision.confirmed, ("kes-sign",), "the target that repeated")

    def test_above_once_is_listed_and_is_no_failure(self):
        decision = timing.decide([report({"vrf-prove": 600.0}), report(), report()])
        self.assertEqual(decision.status, timing.NO_LEAK, "one run alone is not a finding, however high")
        self.assertEqual(decision.once, ("vrf-prove",), "but it is named")

    def test_two_targets_above_once_each_are_not_a_leak(self):
        decision = timing.decide([report({"vrf-prove": 11.0}), report({"kes-sign": 11.0}), report()])
        self.assertEqual(decision.status, timing.NO_LEAK, "the same target must repeat, not any target")
        self.assertEqual(decision.once, ("kes-sign", "vrf-prove"), "both named")

    def test_an_inconclusive_run_confirms_nothing(self):
        decision = timing.decide([report({"vrf-prove": 50.0}), report({"vrf-prove": 50.0}, NOISY), report()])
        self.assertEqual(decision.status, timing.NO_LEAK, "the second run's 50 does not count: its control failed")
        self.assertEqual(decision.conclusive, 2, "two conclusive runs")
        self.assertEqual(decision.once, ("vrf-prove",), "above once in a conclusive run")

    def test_too_few_conclusive_runs_is_inconclusive(self):
        decision = timing.decide([report(flaws=NOISY), report(), report(flaws=NOISY)])
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "one conclusive run cannot confirm a leak")
        decision = timing.decide([report({"vrf-prove": 900.0}), report(flaws=NOISY), report(flaws=NOISY)])
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "nor can a high value in the only conclusive run")

    def test_a_control_above_the_threshold_is_not_a_leak(self):
        decision = timing.decide([report({"control-vartime": 3000.0})] * 3)
        self.assertEqual(decision.status, timing.NO_LEAK, "the variable-time control is meant to be above")

    def test_reports_of_two_thresholds_are_not_decided_together(self):
        other = timing.parse_report(json.dumps(record(probably=4.5)), "other.json", None)
        with self.assertRaises(timing.CheckError, msg="one threshold for every run"):
            timing.decide([report(), other])


class SummaryTest(unittest.TestCase):
    def test_the_summary_names_the_verdict_the_cpu_and_each_statistic(self):
        reports = [report({"kes-sign": 40.0}, cpu=TURBO_REFERENCE_CPU), report(cpu=TURBO_REFERENCE_CPU),
                   report({"kes-sign": 12.0}, cpu=TURBO_REFERENCE_CPU)]
        decision = timing.decide(reports)
        text = timing.markdown(reports, decision)
        self.assertIn("## Secret-path timing: leak confirmed", text, "the verdict heads it")
        self.assertIn(f"{TURBO_REFERENCE_CPU} (the reference CPU)", text, "the CPU and whether it is the reference")
        self.assertIn("| the kes-sign | secret | **40.0** | 3.0 | **12.0** | 2 of 3 |", text,
                      "a statistic above 10 in bold, with the count of conclusive runs above it")
        data = timing.summary(reports, decision)
        self.assertEqual(data["confirmed"], ["kes-sign"], "the confirmed target")
        self.assertEqual(data["on_reference_cpu"], [True, True, True], "each run's CPU against the reference")
        self.assertEqual(data["results"][0]["statistics"]["kes-sign"], 40.0, "every statistic kept")

    def test_an_inconclusive_summary_says_why(self):
        reports = [report(flaws=NOISY, cpu="Other CPU"), report(cpu="Other CPU"), report(flaws=NOISY, cpu=None)]
        text = timing.markdown(reports, timing.decide(reports))
        self.assertIn("## Secret-path timing: inconclusive", text, "the verdict heads it")
        self.assertIn("neither a pass nor a failure", text, "inconclusive is told apart")
        self.assertIn("Run 1 inconclusive: control: [k]H, random/random reads 14.0", text, "the failed control")
        self.assertIn("| *CPU* | | Other CPU | Other CPU | unknown | |", text, "runs on two CPUs each named")


class ProgramTest(unittest.TestCase):
    def test_reports_are_decided_with_the_cpu_of_their_provenance(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            paths = []
            for index, statistics in enumerate([{"vrf-prove": 30.0}, {}, {"vrf-prove": 25.0}], start=1):
                path = root / f"run-{index}.json"
                path.write_text(json.dumps(record(statistics)), encoding="utf-8")
                paths.append(str(path))
            (root / "provenance.json").write_text(json.dumps({"environment": {"cpu": {"model": "Model X"}}}),
                                                  encoding="utf-8")
            with redirect_stdout(io.StringIO()):
                status = timing.main(["--out", str(root / "out"), "--reports", *paths])
            self.assertEqual(status, timing.LEAK, "exit 1 for a confirmed leak")
            data = json.loads((root / "out" / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(data["cpus"], ["Model X"], "the CPU the provenance names")
            self.assertTrue((root / "out" / "summary.md").is_file(), "the job summary is written")

    def test_a_report_that_is_not_one_exits_3(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "run-1.json"
            path.write_text("{}", encoding="utf-8")
            with redirect_stderr(io.StringIO()) as err:
                status = timing.main(["--out", str(Path(scratch) / "out"), "--reports", str(path)])
            self.assertEqual(status, timing.ERROR, "the check could not run")
            self.assertIn("not a version 1 report", err.getvalue(), "and says why")

    def test_an_output_directory_with_reports_in_it_is_refused_before_anything_runs(self):
        with tempfile.TemporaryDirectory() as scratch:
            (Path(scratch) / "run-1.json").write_text(json.dumps(record()), encoding="utf-8")
            with mock.patch.object(timing.subprocess, "run") as docker, redirect_stderr(io.StringIO()) as err:
                status = timing.main(["--out", scratch])
            self.assertEqual(status, timing.ERROR, "a stale report could pass for a run that wrote none")
            self.assertIn("already holds reports", err.getvalue(), "and it says so")
            self.assertEqual(docker.call_args_list, [], "no container is started")

    def test_usage_errors_exit_3_not_2(self):
        for argv in (["--out", "x", "--repetitions", "1"], ["--out", "x", "--measurements", "10"], []):
            with self.subTest(argv=argv):
                with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as stopped:
                    timing.arguments(argv)
                self.assertEqual(stopped.exception.code, timing.ERROR, "2 is inconclusive, so usage is 3")

    def test_each_run_is_a_container_of_its_own_with_nothing_writable_but_the_output(self):
        args = argparse.Namespace(owner="secret-timing-7", cpus="3", memory="4g", out=Path("/out-dir"),
                                  image="scarab-jdk:local", measurements=50000)
        checkout = mock.Mock(root=Path("/checkout"), cache=Path("/cache"))
        command = timing.tool_command(checkout, "/work/a.jar", args, 2)
        self.assertEqual(command[:4], ["docker", "run", "--rm", "--name"], "a fresh container, removed")
        self.assertIn("none", command[command.index("--network") + 1:][:1], "no network")
        self.assertIn("/checkout:/work:ro", command, "the checkout read-only")
        self.assertIn("/cache:/work/.cache:ro", command, "the dependency cache read-only")
        self.assertIn("/out-dir:/out", command, "only the output writable")
        self.assertIn("xyz.kd5ujc.scarab.owner=secret-timing-7", command, "labelled with its owner")
        tail = command[command.index("java"):]
        self.assertEqual(tail, ["java", *timing.JVM, "-cp", "/work/a.jar", timing.MAIN, "50000", "/out/run-2.json"],
                         "the tool's JVM, its size and its report path")

    def test_a_run_that_takes_too_long_is_stopped_and_fails_the_check(self):
        args = argparse.Namespace(owner="secret-timing", cpus="3", memory="4g", image="scarab-jdk:local",
                                  measurements=1000, repetitions=3, run_timeout=5)
        checkout = mock.Mock(root=Path("/checkout"), cache=Path("/cache"))
        calls = []

        def fake(command, **kwargs):
            calls.append(command)
            if command[:2] == ["docker", "run"]:
                raise subprocess.TimeoutExpired(command, 5, output="partial", stderr="")
            return subprocess.CompletedProcess(command, 0, "", "")

        with tempfile.TemporaryDirectory() as scratch:
            args.out = Path(scratch)
            with mock.patch.object(timing.subprocess, "run", side_effect=fake), redirect_stderr(io.StringIO()):
                with self.assertRaises(timing.CheckError) as failed:
                    timing.run_tool(checkout, "/work/a.jar", args, 1)
            self.assertIn("took more than 5 s", str(failed.exception), "the reason")
            self.assertEqual(calls[-1], ["docker", "rm", "-f", "secret-timing-run-1"], "its container removed")
            self.assertEqual((Path(scratch) / "run-1.log").read_text(encoding="utf-8"), "partial",
                             "what it printed is kept")


if __name__ == "__main__":
    unittest.main()

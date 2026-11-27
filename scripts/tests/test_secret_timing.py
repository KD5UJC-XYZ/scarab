# SPDX-License-Identifier: Apache-2.0
"""The secret-path timing check (`scripts/secret_timing.py`): its reading of the run records, the rule that turns
runs into a verdict, the refusals of records it cannot trust, the summaries, the workflow's last step and the
commands it runs. Offline: no Docker, no JVM."""
import argparse
import copy
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import secret_timing as timing  # noqa: E402
from conformance.scenarios import TURBO_REFERENCE_CPU  # noqa: E402

CLEAN = {ident: 2.0 for ident in timing.ROSTER}
CLEAN.update({"control-random": 1.5, "control-field": 4.0, "control-vartime": 2500.0})
COMMIT = "c" * 40


def _entry(ident, value, measurements=100000, proofs=25000):
    """A target as the tool writes it, its parts consistent: t on all measurements and the largest crop's both the
    statistic, and classes that hold the measurements of its size."""
    written = "Infinity" if value == float("inf") else value
    measured = proofs if ident in timing.PROOF_SIZED else measurements
    return {"id": ident, "name": f"the {ident}", "role": timing.ROSTER[ident], "measurements": measured,
            "calibration": max(100, measured // 10), "fixed": {"n": measured // 2}, "random": {"n": measured - measured // 2},
            "t_all": written, "max_t_crops": written, "at_crop": 5, "crops_counted": 100, "statistic": written,
            "over": value > 10, "slowest_entry": None}


def report(statistics=None, **changes):
    """A tool report whose flags agree with its statistics (`CLEAN`'s with `statistics` over them)."""
    values = {**CLEAN, **(statistics or {})}
    flaws = [ident for ident, value in values.items()
             if (timing.ROSTER[ident] in ("noise-control", "value-control") and value > 10)
             or (timing.ROSTER[ident] == "leak-control" and not value > 10)]
    body = {
        "tool": "SecretPathTiming", "version": 2, "run_id": str(uuid.uuid4()), "layout_seed": "1",
        "measurements": 100000, "proofs": 25000, "calibration_share": 10, "pool": 1024, "crops": 100,
        "min_crop_class": 1000, "probably": 10.0, "definitely": 500.0,
        "targets": [_entry(ident, value) for ident, value in values.items()],
        "conclusive": not flaws, "flaws": [f"{ident} failed" for ident in flaws], "sink": "0x00000000",
    }
    body.update(changes)
    return body


def record(statistics=None, run_changes=None, **changes):
    tool = report(statistics, **changes)
    run = {"index": 1, "id": tool["run_id"], "commit": COMMIT, "dirty": False, "jvm": list(timing.JVM),
           "cpu": "Some CPU", "exit_status": 0, "seconds": 150.0}
    run.update(run_changes or {})
    return {"run": run, "report": tool}


def parsed(statistics=None, run_changes=None, **changes):
    return timing.parse_record(json.dumps(record(statistics, run_changes, **changes)), "run.json")


def decided(*statistics):
    return timing.decide([parsed(values) for values in statistics])


class RuleTest(unittest.TestCase):
    def test_three_clean_runs_with_nothing_above_is_no_leak(self):
        decision = decided({}, {}, {})
        self.assertEqual(decision.status, timing.NO_LEAK, "three clean runs, nothing above 10")
        self.assertEqual(decision.clean, 3, "every run clean")

    def test_one_target_counted_in_two_clean_runs_is_a_leak(self):
        decision = decided({"kes-sign": 40.0}, {}, {"kes-sign": 12.0})
        self.assertEqual(decision.status, timing.LEAK, "above 10 in two clean runs")
        self.assertEqual(decision.confirmed, ["kes-sign"], "the target that repeated")
        self.assertEqual(decision.counted["kes-sign"], [1, 3], "and the runs it counted in")

    def test_a_failed_field_control_does_not_erase_a_reading_far_above_it(self):
        # the reviewer's case: KES signing at 100 twice, the field control failing in one of those runs
        decision = decided({"kes-sign": 100.0, "control-field": 10.1}, {"kes-sign": 100.0}, {})
        self.assertEqual(decision.status, timing.LEAK, "100 is above 5 times the floor of 10.1")
        self.assertEqual(decision.counted["kes-sign"], [1, 2], "the noisy run's reading counts")
        decision = decided({"kes-sign": 100.0, "control-field": 10.1}, {"kes-sign": 100.0, "control-field": 10.1}, {})
        self.assertEqual(decision.status, timing.LEAK, "and so it does when the field control fails twice")
        self.assertEqual(decision.clean, 1, "with one clean run")

    def test_a_reading_within_five_times_a_failed_control_does_not_count_and_is_no_pass(self):
        decision = decided({"kes-sign": 100.0, "control-field": 25.0}, {"kes-sign": 100.0, "control-field": 25.0}, {})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "100 is not above 5 times 25, yet it is above 10")
        self.assertEqual(decision.counted, {}, "nothing counted")
        self.assertEqual(decision.above["kes-sign"], [(1, 100.0), (2, 100.0)], "the readings are named")
        decision = decided({"vrf-prove": 60.0, "control-random": 11.0}, {"vrf-prove": 60.0}, {})
        self.assertEqual(decision.status, timing.LEAK, "the random/random control's floor is used the same way")
        decision = decided({"kes-sign": 30.0, "control-keys": 8.0}, {"kes-sign": 30.0, "control-keys": 12.0}, {})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "the same-seed control's floor too: 30 is not 5 times 12")
        self.assertEqual(decision.counted["kes-sign"], [1], "counted in the clean run only")

    def test_a_reading_above_the_threshold_once_is_never_no_leak(self):
        decision = decided({"vrf-prove": 13.0}, {}, {})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "one reading above 10 in a clean run is not a pass")
        self.assertIn("`vrf-prove` 13.0 in run 1", timing.summary([parsed({"vrf-prove": 13.0})] + [parsed()] * 0,
                                                                  decision)["why"], "the target and its reading")
        decision = decided({"vrf-prove": 11.0, "control-field": 12.0}, {}, {})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "nor is one in a noisy run that did not count")

    def test_two_targets_above_once_each_are_not_a_leak(self):
        decision = decided({"vrf-prove": 11.0}, {"kes-sign": 11.0}, {})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "the same target must repeat, not any target")
        self.assertEqual(sorted(decision.above), ["kes-sign", "vrf-prove"], "both named")

    def test_a_blind_run_votes_neither_way_and_its_readings_still_bar_a_pass(self):
        decision = decided({"kes-sign": 100.0, "control-vartime": 5.0}, {"kes-sign": 100.0, "control-vartime": 5.0}, {})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "a run that cannot see a known leak confirms nothing")
        self.assertEqual(decision.counted, {}, "nothing counted")
        decision = decided({"control-vartime": 5.0}, {}, {})
        self.assertEqual(decision.status, timing.NO_LEAK, "two clean runs remain for a pass")
        decision = decided({"control-vartime": 5.0}, {"control-vartime": 9.0}, {})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "one clean run cannot vote clean alone")

    def test_too_few_clean_runs_is_inconclusive(self):
        decision = decided({"control-random": 14.0}, {}, {"control-field": 12.0})
        self.assertEqual(decision.status, timing.INCONCLUSIVE, "one clean run")

    def test_a_control_above_the_threshold_is_not_a_leak(self):
        decision = decided({"control-vartime": 3000.0}, {}, {})
        self.assertEqual(decision.status, timing.NO_LEAK, "the variable-time control is meant to be above")

    def test_an_infinite_statistic_counts(self):
        decision = decided({"kh-zero": float("inf")}, {"kh-zero": float("inf")}, {})
        self.assertEqual(decision.status, timing.LEAK, "two classes separated with no spread are a leak")


class RefusalTest(unittest.TestCase):
    def assert_refused(self, value, why):
        with self.assertRaises(timing.CheckError, msg=why):
            timing.parse_record(json.dumps(value), "run.json")

    def test_flags_that_disagree_with_the_statistics_are_refused(self):
        lying = record({"kes-sign": 40.0})
        next(t for t in lying["report"]["targets"] if t["id"] == "kes-sign")["over"] = False
        self.assert_refused(lying, "KES signing at 40 with `over` false")
        lying = record({"control-field": 20.0})
        lying["report"].update(conclusive=True, flaws=[])
        self.assert_refused(lying, "the field control at 20 with `conclusive` true")

    def test_a_statistic_that_is_not_the_larger_of_its_parts_is_refused(self):
        # the reviewer's: t on all measurements 60, no crop, a statistic of 2, voted clean
        lying = record()
        target = next(t for t in lying["report"]["targets"] if t["id"] == "kes-sign")
        target.update(t_all=60.0, max_t_crops=0.0, statistic=2.0, over=False)
        self.assert_refused(lying, "a statistic below t on all measurements")
        lying = record()
        next(t for t in lying["report"]["targets"] if t["id"] == "kh-zero").update(t_all="-Infinity", statistic=2.0)
        self.assert_refused(lying, "an infinite t with a finite statistic")
        honest = record()
        next(t for t in honest["report"]["targets"] if t["id"] == "kh-zero").update(
            t_all="-Infinity", max_t_crops=3.0, statistic="Infinity", over=True)
        self.assertEqual(timing.parse_record(json.dumps(honest), "run.json").statistic("kh-zero"), float("inf"),
                         "an infinite t is read back as one")

    def test_sizes_that_contradict_the_counts_are_refused(self):
        cases = {
            "classes holding more than the target's measurements": lambda t: t["fixed"].update(n=t["fixed"]["n"] + 1),
            "a target of the wrong size": lambda t: t.update(measurements=100000),
            "a calibration batch its sizes do not give": lambda t: t.update(calibration=7),
            "a crop beyond the crops": lambda t: t.update(at_crop=100),
            "no crop counted but a largest one": lambda t: t.update(crops_counted=0),
            "a slowest entry beyond the pool": lambda t: t.update(slowest_entry={"entry": 1024}),
        }
        for case, change in cases.items():
            with self.subTest(case=case):
                lying = record()
                change(next(t for t in lying["report"]["targets"] if t["id"] == "kes-sign"))
                self.assert_refused(lying, case)
        self.assert_refused(record(proofs=20000), "proofs its measurements do not give")

    def test_a_bare_infinity_is_refused(self):
        text = json.dumps(record()).replace('"sink": "0x00000000"', '"sink": Infinity')
        with self.assertRaises(timing.CheckError, msg="JSON has no bare Infinity"):
            timing.parse_record(text, "run.json")

    def test_a_roster_that_is_not_exactly_the_expected_one_is_refused(self):
        missing = record()
        missing["report"]["targets"] = [t for t in missing["report"]["targets"] if t["id"] != "kes-evolve-1-2"]
        self.assert_refused(missing, "a target missing")
        extra = record()
        extra["report"]["targets"].append({**extra["report"]["targets"][0], "id": "kes-evolve-31-32"})
        self.assert_refused(extra, "a target not on the roster")
        twice = record()
        twice["report"]["targets"].append(copy.deepcopy(twice["report"]["targets"][0]))
        self.assert_refused(twice, "a target twice")
        swapped = record()
        swapped["report"]["targets"][0]["role"] = "noise-control"
        self.assert_refused(swapped, "a target under another role")

    def test_too_few_measurements_in_a_class_are_refused(self):
        small = record()
        small["report"]["targets"][0]["fixed"]["n"] = 999
        self.assert_refused(small, "a class under the minimum")

    def test_what_is_not_a_record_is_refused(self):
        cases = {
            "the tool's bare report": report(),
            "another version": record(version=1),
            "another threshold": record(probably=4.5),
            "a tool that did not exit 0": record(run_changes={"exit_status": 1}),
            "an identity that is not its report's": record(run_changes={"id": "another"}),
            "no commit": record(run_changes={"commit": None}),
            "crops counted from fewer": record(min_crop_class=10),
            "a statistic that is no number": record({"vrf-prove": float("nan")}),
        }
        for case, value in cases.items():
            with self.subTest(case=case):
                self.assert_refused(json.loads(json.dumps(value).replace("NaN", "null")), case)
        with self.assertRaises(timing.CheckError, msg="text that is not JSON is refused"):
            timing.parse_record("SecretPathTiming: 100000 measurements", "run.json")

    def test_a_run_given_twice_is_refused(self):
        one = parsed()
        with self.assertRaises(timing.CheckError, msg="a copy is not another experiment"):
            timing.check_together([one, parsed(), one], allow_mixed=True)

    def test_runs_of_different_commits_or_sizes_are_decided_together_only_when_asked(self):
        other = parsed(run_changes={"commit": "d" * 40})
        with self.assertRaises(timing.CheckError, msg="two commits"):
            timing.check_together([parsed(), other], allow_mixed=False)
        timing.check_together([parsed(), other], allow_mixed=True)
        with self.assertRaises(timing.CheckError, msg="two sizes"):
            timing.check_together([parsed(), parsed(measurements=50000, proofs=12500)], allow_mixed=False)


class SummaryTest(unittest.TestCase):
    def test_the_summary_names_the_verdict_the_cpu_and_each_statistic(self):
        runs = [parsed({"kes-sign": 40.0}, {"cpu": TURBO_REFERENCE_CPU}), parsed(run_changes={"cpu": TURBO_REFERENCE_CPU}),
                parsed({"kes-sign": 120.0, "control-field": 20.0}, {"cpu": TURBO_REFERENCE_CPU})]
        decision = timing.decide(runs)
        text = timing.markdown(runs, decision)
        self.assertIn("## Secret-path timing: leak confirmed", text, "the verdict heads it")
        self.assertIn(f"{TURBO_REFERENCE_CPU} (the reference CPU)", text, "the CPU and whether it is the reference")
        self.assertIn("| the kes-sign | secret | **40.0** | 2.0 | **120.0** | 1, 3 |", text,
                      "a counted statistic in bold, with the runs it counted in")
        self.assertIn("| *run* | | clean | clean | noisy, floor 20.0 | |", text, "each run's kind")
        data = timing.summary(runs, decision)
        self.assertEqual(data["confirmed"], ["kes-sign"], "the confirmed target")
        self.assertEqual(data["on_reference_cpu"], [True, True, True], "each run's CPU against the reference")
        self.assertEqual(data["results"][2]["kind"], "noisy", "the kind of each run")

    def test_the_summary_writes_no_bare_infinity(self):
        runs = [parsed({"kh-zero": float("inf")}), parsed({"kh-zero": float("inf")}), parsed()]
        written = timing.dumps(timing.summary(runs, timing.decide(runs)))
        data = timing.loads(written)
        self.assertEqual(data["results"][0]["statistics"]["kh-zero"], "Infinity", "written as the tool writes it")
        self.assertEqual(data["above"]["kh-zero"][0]["statistic"], "Infinity", "in the readings above too")

    def test_a_confirmation_in_noisy_runs_only_says_so(self):
        runs = [parsed({"kes-sign": 100.0, "control-field": 12.0}), parsed({"kes-sign": 100.0, "control-field": 12.0}),
                parsed()]
        decision = timing.decide(runs)
        self.assertEqual(decision.status, timing.LEAK, "100 is above five times 12 twice")
        self.assertEqual(timing.summary(runs, decision)["confirmed_in_noisy_runs_only"], ["kes-sign"], "flagged")
        self.assertIn("confirmed in noisy runs only", timing.markdown(runs, decision), "and said in the summary")
        runs = [parsed({"kes-sign": 100.0, "control-field": 12.0}), parsed({"kes-sign": 100.0}), parsed()]
        decision = timing.decide(runs)
        self.assertEqual(timing.summary(runs, decision)["confirmed_in_noisy_runs_only"], [], "one clean run is enough")

    def test_an_inconclusive_summary_says_why(self):
        runs = [parsed({"control-random": 14.0, "vrf-output": 11.0}, {"cpu": "Other CPU"}), parsed(), parsed()]
        text = timing.markdown(runs, timing.decide(runs))
        self.assertIn("## Secret-path timing: inconclusive", text, "the verdict heads it")
        self.assertIn("`vrf-output` 11.0 in run 1", text, "the reading that bars a pass")
        self.assertIn("neither a pass nor a failure", text, "inconclusive is told apart")
        self.assertIn("Run 1 noisy: control-random 14.0.", text, "the failed control")
        self.assertIn("| *CPU* | | Other CPU | Some CPU | Some CPU | |", text, "runs on two CPUs each named")
        self.assertIn("| the vrf-output | secret | *11.0* | 2.0 | 2.0 |", text, "above but not counted, in italics")


def evidence(root, *statistics):
    """A directory as the workflow's test step leaves it: a record per run, the provenance and the summaries that
    `--reports` writes; the status that wrote them."""
    names = []
    for index, values in enumerate(statistics, start=1):
        name = f"run-{index}.json"
        (root / name).write_text(json.dumps(record(values)), encoding="utf-8")
        names.append(name)
    provenance = {"scarab": {"commit": COMMIT, "dirty": False}, "runs": [{"record": name} for name in names]}
    (root / "provenance.json").write_text(json.dumps(provenance), encoding="utf-8")
    with redirect_stdout(io.StringIO()):
        return timing.main(["--out", str(root), "--reports", *(str(root / name) for name in names)])


class OutcomeTest(unittest.TestCase):
    def outcome(self, root, status):
        with redirect_stdout(io.StringIO()) as out:
            code = timing.outcome(root, status)
        return code, out.getvalue()

    def test_each_status_with_its_evidence(self):
        cases = {"0": ([{}, {}, {}], 0, "no leak found"),
                 "1": ([{"kes-sign": 40.0}, {}, {"kes-sign": 12.0}], 1, "::error title=Secret-path timing: leak confirmed::"),
                 "2": ([{"kes-sign": 40.0}, {}, {}], 0, "::warning title=Secret-path timing: inconclusive::")}
        for status, (runs, code, line) in cases.items():
            with self.subTest(status=status), tempfile.TemporaryDirectory() as scratch:
                self.assertEqual(evidence(Path(scratch), *runs), int(status), "the test step's status")
                found, printed = self.outcome(Path(scratch), status)
                self.assertEqual(found, code, f"status {status} makes the job {'red' if code else 'green'}")
                self.assertIn(line, printed, "with its annotation")

    def test_evidence_that_does_not_hold_is_red(self):
        def broken(change):
            with tempfile.TemporaryDirectory() as scratch:
                root = Path(scratch)
                evidence(root, {}, {}, {})
                change(root)
                return self.outcome(root, "0")

        def summary(data):
            return lambda root: (root / "summary.json").write_text(json.dumps(data), encoding="utf-8")

        cases = {
            "the reviewer's: no run, an empty job summary, an empty provenance": lambda root: (
                summary({"status": 0, "results": [None]})(root),
                (root / "summary.md").write_text("", encoding="utf-8"),
                (root / "provenance.json").write_text("{}", encoding="utf-8")),
            "a run that is not a record": lambda root: (root / "run-2.json").write_text("{}", encoding="utf-8"),
            "a run's record missing": lambda root: (root / "run-2.json").unlink(),
            "an empty job summary": lambda root: (root / "summary.md").write_text(" \n", encoding="utf-8"),
            "an empty provenance": lambda root: (root / "provenance.json").write_text("{}", encoding="utf-8"),
            "a result that names a path elsewhere": lambda root: summary(
                {"status": 0, "results": [{"record": "../run-1.json"}]})(root),
            "a summary the records do not give": lambda root: (root / "run-1.json").write_text(
                json.dumps(record({"kes-sign": 40.0})), encoding="utf-8"),
            "no summary at all": lambda root: (root / "summary.json").unlink(),
        }
        for case, change in cases.items():
            with self.subTest(case=case):
                code, printed = broken(change)
                self.assertEqual(code, 1, f"{case} is red")
                self.assertIn("could not run", printed, "and is not called a leak or a pass")

    def test_a_status_the_records_do_not_give_is_red(self):
        with tempfile.TemporaryDirectory() as scratch:
            evidence(Path(scratch), {}, {}, {})
            for status in ("1", "2", "3", ""):
                with self.subTest(status=status):
                    code, printed = self.outcome(Path(scratch), status)
                    self.assertEqual(code, 1, f"an exit status of {status!r} over records that decide 0 is red")
                    self.assertIn("could not run", printed, "a crash's 1 is not called a leak")


class ProgramTest(unittest.TestCase):
    def test_records_are_decided_from_a_directory_of_them(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            paths = []
            for index, statistics in enumerate([{"vrf-prove": 30.0}, {}, {"vrf-prove": 25.0}], start=1):
                path = root / f"run-{index}.json"
                path.write_text(json.dumps(record(statistics, {"cpu": "Model X"})), encoding="utf-8")
                paths.append(str(path))
            with redirect_stdout(io.StringIO()):
                status = timing.main(["--out", str(root / "out"), "--reports", *paths])
            self.assertEqual(status, timing.LEAK, "exit 1 for a confirmed leak")
            data = json.loads((root / "out" / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(data["cpus"], ["Model X"], "the CPU the records name")
            self.assertTrue((root / "out" / "summary.md").is_file(), "the job summary is written")
            with redirect_stderr(io.StringIO()) as err:
                status = timing.main(["--out", str(root / "again"), "--reports", paths[0], paths[0], paths[1]])
            self.assertEqual(status, timing.ERROR, "a record given twice")
            self.assertIn("given twice", err.getvalue(), "and it says so")

    def test_a_summary_that_cannot_be_written_exits_3_not_1(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            paths = []
            for index in (1, 2, 3):
                path = root / f"run-{index}.json"
                path.write_text(json.dumps(record({"vrf-prove": 30.0})), encoding="utf-8")
                paths.append(str(path))
            (root / "out" / "summary.md").mkdir(parents=True)
            with redirect_stderr(io.StringIO()), redirect_stdout(io.StringIO()):
                status = timing.main(["--out", str(root / "out"), "--reports", *paths])
            self.assertEqual(status, timing.ERROR, "a leak was found, but the summary could not be kept")

    def test_any_failure_of_the_program_itself_exits_3(self):
        with mock.patch.object(timing, "main", side_effect=KeyError("a defect")), redirect_stderr(io.StringIO()):
            self.assertEqual(timing.entry(), timing.ERROR, "never 1, which is a leak")

    def test_an_import_that_failed_exits_3(self):
        with mock.patch.object(timing, "IMPORT_FAILURE", ImportError("no module")), redirect_stderr(io.StringIO()):
            self.assertEqual(timing.entry(), timing.ERROR, "never Python's 1, which is a leak")

    def test_a_closed_output_exits_3_not_the_verdict(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            paths = []
            for index, statistics in enumerate([{"vrf-prove": 30.0}, {}, {"vrf-prove": 25.0}], start=1):
                path = root / f"run-{index}.json"
                path.write_text(json.dumps(record(statistics)), encoding="utf-8")
                paths.append(str(path))
            script = Path(timing.__file__).resolve()
            # a pipe whose reading end is closed before the program starts: every write to it fails
            reading, writing = os.pipe()
            os.close(reading)
            try:
                done = subprocess.run([sys.executable, "-I", "-B", str(script), "--out", str(root / "out"),
                                       "--reports", *paths], stdout=writing, stderr=subprocess.DEVNULL, timeout=60)
            finally:
                os.close(writing)
            self.assertEqual(done.returncode, timing.ERROR, "a leak it could not print is 3, never 1")

    def test_an_output_directory_with_reports_in_it_is_refused_before_anything_runs(self):
        with tempfile.TemporaryDirectory() as scratch:
            (Path(scratch) / "run-1.json").write_text(json.dumps(record()), encoding="utf-8")
            with mock.patch.object(timing.subprocess, "run") as docker, redirect_stderr(io.StringIO()) as err:
                status = timing.main(["--out", scratch])
            self.assertEqual(status, timing.ERROR, "a stale record could pass for a run that wrote none")
            self.assertIn("already holds reports", err.getvalue(), "and it says so")
            self.assertEqual(docker.call_args_list, [], "no container is started")

    def test_usage_errors_exit_3_not_2(self):
        for argv in (["--out", "x", "--repetitions", "1"], ["--out", "x", "--measurements", "5000"], [],
                     ["--out", "x", "--allow-mixed"]):
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
        self.assertEqual(command[command.index("--network") + 1], "none", "no network")
        self.assertIn("/checkout:/work:ro", command, "the checkout read-only")
        self.assertIn("/cache:/work/.cache:ro", command, "the dependency cache read-only")
        self.assertIn("/out-dir:/out", command, "only the output writable")
        self.assertIn("xyz.kd5ujc.scarab.owner=secret-timing-7", command, "labelled with its owner")
        tail = command[command.index("java"):]
        self.assertEqual(tail, ["java", *timing.JVM, "-cp", "/work/a.jar", timing.MAIN, "50000", "/out/tool-2.json"],
                         "the tool's JVM, its size and its report path")

    def test_a_run_that_takes_too_long_is_stopped_and_a_container_left_behind_is_named(self):
        args = argparse.Namespace(owner="secret-timing", cpus="3", memory="4g", image="scarab-jdk:local",
                                  measurements=10000, repetitions=3, run_timeout=5)
        checkout = mock.Mock(root=Path("/checkout"), cache=Path("/cache"))
        calls = []

        def fake(command, **kwargs):
            calls.append((command, kwargs))
            if command[:2] == ["docker", "run"]:
                raise subprocess.TimeoutExpired(command, 5, output="partial", stderr="")
            return subprocess.CompletedProcess(command, 1, "", "Error response from daemon: busy")

        with tempfile.TemporaryDirectory() as scratch:
            args.out = Path(scratch)
            with mock.patch.object(timing.subprocess, "run", side_effect=fake), redirect_stderr(io.StringIO()):
                with self.assertRaises(timing.RunTimedOut) as failed:
                    timing.run_tool(checkout, "/work/a.jar", args, 1, "Some CPU", (COMMIT, False))
            self.assertEqual((failed.exception.index, failed.exception.seconds), (1, 5), "the run and its bound")
            self.assertEqual(failed.exception.left, "secret-timing-run-1", "the container that did not go")
            command, kwargs = calls[-1]
            self.assertEqual(command, ["docker", "rm", "-f", "secret-timing-run-1"], "its container removed")
            self.assertEqual(kwargs.get("timeout"), timing.REMOVE_TIMEOUT, "within a bound")
            self.assertEqual((Path(scratch) / "run-1.log").read_text(encoding="utf-8"), "partial",
                             "what it printed is kept")

    def test_a_log_that_cannot_be_written_does_not_skip_the_stop(self):
        args = argparse.Namespace(owner="secret-timing", cpus="3", memory="4g", image="scarab-jdk:local",
                                  measurements=10000, repetitions=3, run_timeout=5)
        checkout = mock.Mock(root=Path("/checkout"), cache=Path("/cache"))
        calls = []

        def fake(command, **kwargs):
            calls.append(command)
            if command[:2] == ["docker", "run"]:
                raise subprocess.TimeoutExpired(command, 5, output="partial", stderr="")
            return subprocess.CompletedProcess(command, 0, "", "")

        with tempfile.TemporaryDirectory() as scratch:
            args.out = Path(scratch) / "gone"
            with mock.patch.object(timing.subprocess, "run", side_effect=fake), redirect_stderr(io.StringIO()):
                with self.assertRaises(timing.RunTimedOut) as failed:
                    timing.run_tool(checkout, "/work/a.jar", args, 1, "Some CPU", (COMMIT, False))
            self.assertEqual(calls[-1], ["docker", "rm", "-f", "secret-timing-run-1"], "stopped though the log failed")
            self.assertIsNone(failed.exception.left, "and removed, so nothing is left")

    def test_a_run_is_recorded_with_its_identity_commit_and_status(self):
        args = argparse.Namespace(owner="secret-timing", cpus="3", memory="4g", image="scarab-jdk:local",
                                  measurements=10000, repetitions=3, run_timeout=5)
        checkout = mock.Mock(root=Path("/checkout"), cache=Path("/cache"))
        with tempfile.TemporaryDirectory() as scratch:
            args.out = Path(scratch)
            tool = report()

            def fake(command, **kwargs):
                (Path(scratch) / "tool-1.json").write_text(json.dumps(tool), encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "out", "")

            with mock.patch.object(timing.subprocess, "run", side_effect=fake), redirect_stderr(io.StringIO()):
                timing.run_tool(checkout, "/work/a.jar", args, 1, "Some CPU", (COMMIT, True))
            run = timing.parse_record((Path(scratch) / "run-1.json").read_text(encoding="utf-8"), "run-1.json")
            self.assertEqual(run.id, tool["run_id"], "the tool's identity of the run")
            self.assertEqual((run.commit, run.dirty, run.cpu), (COMMIT, True, "Some CPU"), "the commit and the CPU")
            self.assertFalse((Path(scratch) / "tool-1.json").exists(), "the bare report is folded into the record")


if __name__ == "__main__":
    unittest.main()

# SPDX-License-Identifier: Apache-2.0
"""The node-diversity-conformance runner (`scripts/conformance`): the schema checker, the pins and fetching, the
report names against the site's own selection rule, the scenario checks and the finishing of a report. Offline:
no network, no Docker."""
import gzip
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from conformance import inputs, provenance, publish, run, scenarios  # noqa: E402
from conformance.schema import NonStandardJson, UnsupportedSchema, date_time, loads, validate  # noqa: E402

COMMIT = "a" * 40
FALLBACK = datetime(2001, 2, 3, tzinfo=timezone.utc)


class SchemaTest(unittest.TestCase):
    def test_boolean_subschema_under_properties(self):
        # ledger-rules puts `"additionalProperties": false` among its properties: a property of that name
        schema = {"type": "object", "properties": {"additionalProperties": False, "id": {"type": "string"}}}
        self.assertEqual(validate({"id": "1"}, schema), [])
        self.assertTrue(validate({"additionalProperties": 1}, schema))

    def test_const_and_type_are_strict_about_booleans(self):
        self.assertTrue(validate(1, {"const": True}), "1 is not the constant true")
        self.assertEqual(validate(True, {"const": True}), [])
        self.assertTrue(validate(True, {"type": "integer"}), "a boolean is not an integer")
        self.assertEqual(validate(3, {"type": ["integer", "null"], "minimum": 0}), [])
        self.assertTrue(validate(-1, {"type": "integer", "minimum": 0}))

    def test_objects_references_and_alternatives(self):
        schema = {
            "type": "object", "required": ["a"], "additionalProperties": False,
            "properties": {"a": {"$ref": "#/$defs/x"}},
            "propertyNames": {"pattern": "^[a-z]$"},
            "$defs": {"x": {"oneOf": [{"const": "pass"}, {"type": "string", "enum": ["fail"]}]}},
        }
        self.assertEqual(validate({"a": "pass"}, schema), [])
        self.assertTrue(validate({"a": "other"}, schema))
        self.assertTrue(validate({"a": "pass", "b": 1}, schema), "no additional property")
        self.assertTrue(validate({}, schema), "a is required")

    def test_date_time_is_asserted(self):
        schema = {"type": "string", "format": "date-time"}
        self.assertEqual(validate("2026-11-14T15:04:05.123456789Z", schema), [])
        self.assertEqual(validate("2016-12-31T23:59:60Z", schema), [], "a leap second")
        for bad in ("2026-11-14 15:04", "2026-11-14T00:00:00+00:99", "2026-02-30T00:00:00Z",
                    "2026-11-14T24:00:00Z", "2026-11-14T00:00:00Z\n", "2026-13-01T00:00:00Z"):
            self.assertTrue(validate(bad, schema), repr(bad))
            self.assertIsNone(date_time(bad), repr(bad))

    def test_json_has_no_nan_or_infinity(self):
        for text in ('{"x": NaN}', '{"x": Infinity}', '{"x": -Infinity}', '{"x": 1e999}'):
            with self.assertRaises(NonStandardJson, msg=text):
                loads(text)
        self.assertEqual(loads('{"x": 1.5, "y": 2}'), {"x": 1.5, "y": 2})
        self.assertTrue(validate(float("nan"), {"type": "number", "minimum": 0}), "NaN is not a number")
        self.assertTrue(validate(float("inf"), {"type": ["number", "null"]}), "nor is an infinity")

    def test_unknown_keyword_is_refused(self):
        with self.assertRaises(UnsupportedSchema):
            validate({}, {"type": "object", "if": {"type": "object"}})
        with self.assertRaises(UnsupportedSchema):
            validate({}, {"$ref": "https://example.org/schema.json"})


class NamingTest(unittest.TestCase):
    def emitted(self, date):
        names = []
        for scenario in scenarios.SCENARIOS.values():
            for era in scenario.eras:
                name = scenarios.report_name(date, scenario.id, era)
                names += [name, scenarios.provenance_name(name)]
        return names

    def test_each_scenario_and_era_has_names_of_its_own(self):
        names = self.emitted("20261114")
        self.assertEqual(len(names), len(set(names)), "no two files share a name")
        reports = [name for name in names if scenarios.NAME.match(name)]
        self.assertEqual(len(reports), 5, "one report per scenario and era")
        self.assertTrue(all(scenarios.NAME.match(name) is None for name in names if name not in reports),
                        "a provenance file is never taken for a report")

    def test_the_date_is_the_only_eight_digit_run(self):
        for name in self.emitted("20261114"):
            self.assertEqual(re.findall(r"(?<!\d)\d{8}(?!\d)", name), ["20261114"], name)
            self.assertNotRegex(name, r"\d{9}")

    def test_names_are_strict(self):
        self.assertEqual(scenarios.report_name("20261114", "cbor-decoding", "conway"),
                         "scarab-20261114-cbor-decoding-conway.json.gz")
        with self.assertRaises(ValueError):
            scenarios.report_name("2026111", "ledger-rules", None)
        self.assertIsNone(scenarios.NAME.match("scarab-20261114-ledger-rules.json"))
        self.assertIsNone(scenarios.NAME.match("scarab-20261114-uplc-turbo-conway.json.gz"))

    def test_report_date(self):
        self.assertEqual(scenarios.report_date("2026-11-14T23:59:59.123456789Z", FALLBACK), "20261114")
        self.assertEqual(scenarios.report_date("2026-11-14T20:00:00-05:00", FALLBACK), "20261115", "UTC date")
        self.assertEqual(scenarios.report_date(1763164800000, FALLBACK), "20251115", "milliseconds since the epoch")
        self.assertEqual(scenarios.report_date("yesterday", FALLBACK), "20010203")
        self.assertEqual(scenarios.report_date("2026-11-14T00:00:00+00:99", FALLBACK), "20010203",
                         "an impossible offset gives no date of its own")
        self.assertEqual(scenarios.report_date(True, FALLBACK), "20010203")

    def test_compression_is_reproducible(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp, "a.gz"), Path(tmp, "b.gz")
            scenarios.compress(b'{"x": 1}\n', a)
            scenarios.compress(b'{"x": 1}\n', b)
            self.assertEqual(a.read_bytes(), b.read_bytes())
            self.assertEqual(gzip.decompress(a.read_bytes()), b'{"x": 1}\n')


class PinsTest(unittest.TestCase):
    def test_tracked_pins_load(self):
        pins = inputs.load_pins()
        needed = {name for scenario in scenarios.SCENARIOS.values() for name in scenario.inputs}
        self.assertLessEqual(needed, set(pins), "every scenario input is pinned")
        self.assertEqual(pins["site"].directory, "site-628006278309")

    def test_malformed_pins_are_refused(self):
        bad = [
            {"kind": "git", "repository": "a/b", "commit": "abc", "paths": ["x"]},
            {"kind": "git", "repository": "a/b", "commit": COMMIT, "paths": ["../x"]},
            {"kind": "files", "repository": "a/b", "commit": COMMIT, "root": "r", "files": {"/etc/x": "0" * 64}},
            {"kind": "tarball", "url": "http://example.org/x.tar.zst", "sha256": "0" * 64},
            {"kind": "other"},
        ]
        for spec in bad:
            with self.assertRaises(inputs.InputError, msg=str(spec)):
                inputs.check_pin("x", spec)

    def test_git_commands_are_shallow_sparse_and_inert(self):
        pin = inputs.check_pin("site", {"kind": "git", "repository": "o/r", "commit": COMMIT, "paths": ["a/b"]})
        commands = inputs.git_commands(pin, Path("/t"))
        flat = [" ".join(command) for command in commands]
        self.assertTrue(all("core.symlinks=false" in line and "core.hooksPath=/dev/null" in line
                            for line in flat[1:]), "no symbolic links, no hooks")
        self.assertIn("sparse-checkout set --no-cone /a/b/", flat[2])
        self.assertIn(f"--depth 1 --filter=blob:none --no-tags https://github.com/o/r {COMMIT}", flat[3])
        self.assertEqual(commands[-1][-2:], ["rev-parse", "HEAD"])

    def test_fetch_git_refuses_another_commit(self):
        pin = inputs.check_pin("site", {"kind": "git", "repository": "o/r", "commit": COMMIT, "paths": ["a"]})
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(inputs.InputError):
            inputs.fetch_git(pin, Path(tmp), run=lambda argv: "b" * 40 + "\n")


class FetchTest(unittest.TestCase):
    def files_pin(self, digest):
        return inputs.check_pin("docs", {"kind": "files", "repository": "o/r", "commit": COMMIT, "root": "t",
                                         "files": {"common/x.json": digest}})

    def test_files_are_kept_only_with_their_sha256(self):
        body = b'{"a": 1}'
        digest = __import__("hashlib").sha256(body).hexdigest()
        seen = []

        def get(url, target):
            seen.append(url)
            target.write_bytes(body)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = inputs.fetch(self.files_pin(digest), root, get=get)
            self.assertEqual((directory / "common" / "x.json").read_bytes(), body)
            self.assertEqual(seen, [f"https://raw.githubusercontent.com/o/r/{COMMIT}/t/common/x.json"])
            inputs.fetch(self.files_pin(digest), root, get=get)
            self.assertEqual(len(seen), 1, "a verified directory is reused")
            cached = directory / "common" / "x.json"
            for change in (lambda: cached.write_bytes(b'{"a": 2}'), cached.unlink,
                           lambda: (directory / "common" / "extra.json").write_text("{}")):
                change()
                inputs.fetch(self.files_pin(digest), root, get=get)
                self.assertEqual(cached.read_bytes(), body, "a changed cache is fetched again")
                self.assertFalse((directory / "common" / "extra.json").exists())
            self.assertEqual(len(seen), 4, "each change to the cache made it fetch again")
            with self.assertRaises(inputs.InputError):
                inputs.fetch(self.files_pin("0" * 64), root, get=get)
            self.assertFalse(list(root.rglob("*.part")), "a mismatched file is not kept")

    def tar(self, path, members):
        with tarfile.open(path, "w") as tar:
            for name, body in members:
                info = tarfile.TarInfo(name)
                info.size = len(body)
                tar.addfile(info, io.BytesIO(body))

    def test_tar_extraction_refuses_escaping_members(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp, "x.tar")
            self.tar(archive, [("../evil", b"x")])
            with self.assertRaises(tarfile.TarError):
                inputs.extract_tar(archive, Path(tmp, "out"))
            self.assertFalse(Path(tmp, "evil").exists())
            self.tar(archive, [("/abs/evil", b"x")])
            inputs.extract_tar(archive, Path(tmp, "out"))
            self.assertTrue(Path(tmp, "out", "abs", "evil").is_file(), "an absolute member lands inside")
            archive = Path(tmp, "ok.tar")
            self.tar(archive, [("cost-models/v1.json", b"{}"), ("290/a-v1.flat", b"\x01")])
            self.assertEqual(inputs.extract_tar(archive, Path(tmp, "ok")), 2)
            self.assertTrue(Path(tmp, "ok", "290", "a-v1.flat").is_file())

    def test_tarball_is_checked_before_it_is_opened(self):
        pin = inputs.check_pin("corpus", {"kind": "tarball", "url": "https://example.org/c.tar.zst",
                                          "sha256": "0" * 64})
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(inputs, "decompress_zstd", side_effect=AssertionError("opened")):
            with self.assertRaises(inputs.InputError):
                inputs.fetch(pin, Path(tmp), get=lambda url, target: target.write_bytes(b"not it"))


class Site:
    """A scratch site checkout with the parts the checks read."""

    def __init__(self, root):
        self.root = Path(root)
        fixtures = self.root / "scenarios" / "ledger-rules" / "fixtures" / "scenarios"
        fixtures.mkdir(parents=True)
        for name in ("00001-a.json", "00002-b.json"):
            (fixtures / name).write_text("{}")
        uplc = self.root / "scenarios" / "uplc-conformance"
        uplc.mkdir(parents=True)
        (uplc / "expectations.json").write_text(json.dumps({"scenarios": [
            {"id": "v1", "plutus_version": "V3", "protocol_version": "10.0"},
            {"id": "v1", "plutus_version": "V3", "protocol_version": "11.0"},
            {"id": "v2", "plutus_version": "V2", "protocol_version": "11.0"}]}))
        (self.root / "scenarios" / "ledger-rules" / "schema.json").write_text(json.dumps({
            "type": "object", "required": ["version", "scenario", "generated_at", "scenarios"],
            "properties": {"version": {"const": "1"}, "scenario": {"const": "ledger-rules"},
                           "generated_at": {"type": "string", "format": "date-time"},
                           "scenarios": {"type": "array", "items": {"type": "object", "required": ["id", "result"]}}}}))
        cbor = self.root / "scenarios" / "cbor-decoding"
        cbor.mkdir(parents=True)
        (cbor / "schema.json").write_text(json.dumps({
            "type": "object", "properties": {"implementation_commit": {"type": "string"}}}))


def context(site, dataset=Path("/nonexistent")):
    pins = inputs.load_pins()
    return scenarios.Context(COMMIT, pins, {"site": site, "cbor-dataset": dataset}, {})


def cbor_dataset(root, era, samples):
    for sample in samples:
        path = Path(root, "dataset", era, *sample.split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"\xa0")
    return Path(root)


def ledger_report(ids, dataset=None):
    return {"version": "1", "scenario": "ledger-rules", "generated_at": "2026-11-14T10:00:00.5Z",
            "dataset_commit": dataset or inputs.load_pins()["ledger-rules-documents"].spec["commit"],
            "implementation": {"name": "scarab"}, "scenarios": [{"id": i, "result": "pass"} for i in ids]}


class ChecksTest(unittest.TestCase):
    def test_ledger_rules_needs_every_fixture_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            ctx = context(Site(tmp).root)
            check = scenarios.SCENARIOS["ledger-rules"].checks
            self.assertEqual(check(ledger_report(["00001", "00002"]), ctx, None), [])
            self.assertTrue(any("have no result" in p for p in check(ledger_report(["00001"]), ctx, None)))
            self.assertTrue(any("more often than expected" in p
                                for p in check(ledger_report(["00001", "00002", "00002"]), ctx, None)))
            self.assertTrue(any("dataset_commit" in p
                                for p in check(ledger_report(["00001", "00002"], "b" * 40), ctx, None)))

    def test_uplc_conformance_refuses_no_verdict(self):
        with tempfile.TemporaryDirectory() as tmp:
            ctx = context(Site(tmp).root)
            check = scenarios.SCENARIOS["uplc-conformance"].checks
            def item(id, protocol, evaluation, plutus="V3"):
                return {"id": id, "plutus_version": plutus, "protocol_version": protocol, "evaluation": evaluation}

            full = {"scenarios": [item("v1", "10.0", "(program 1.1.0 (con integer 1))"),
                                  item("v1", "11.0", "(program 1.1.0 (con integer 1))"),
                                  item("v2", "11.0", "evaluation failure", "V2")]}
            self.assertEqual(check(full, ctx, None), [], "an id may recur at another protocol")
            full["scenarios"][2]["evaluation"] = "no verdict"
            self.assertEqual(check(full, ctx, None), ["1 vectors have no verdict from the evaluator"])
            twice = {"scenarios": full["scenarios"][:1] * 2 + full["scenarios"][2:]}
            problems = check(twice, ctx, None)
            self.assertTrue(any("more often than expected" in p for p in problems), problems)
            self.assertTrue(any("1 of 3 vectors have no result: v1/V3/11.0" in p for p in problems), problems)
            numeric = {"scenarios": [dict(entry) for entry in full["scenarios"]]}
            numeric["scenarios"][2]["evaluation"] = "evaluation failure"
            numeric["scenarios"][1]["protocol_version"] = 11.0
            problems = check(numeric, ctx, None)
            self.assertTrue(any("name no vector of the corpus: v1/V3/11.0" in p for p in problems),
                            f"11.0 as a number is not the site's '11.0': {problems}")

    def test_cbor_report_must_be_of_its_era_and_cover_its_samples(self):
        samples = ["a/valid/x.input.cbor", "a/invalid-zap-1/y.input.cbor"]
        with tempfile.TemporaryDirectory() as tmp:
            dataset = cbor_dataset(tmp, "conway", samples)
            cbor_dataset(tmp, "dijkstra", samples)
            Path(tmp, "dataset", "conway", "a", "valid", "x.expected.cbor").write_bytes(b"\xa0")
            ctx = context(Path("/nonexistent"), dataset)
            check = scenarios.SCENARIOS["cbor-decoding"].checks
            commit = inputs.load_pins()["cbor-dataset"].spec["commit"]

            def report(results, version="11.0"):
                return {"protocol_version": version, "dataset_commit": commit, "results": results}

            full = {sample: True for sample in samples}
            self.assertEqual(check(report(full), ctx, "conway"), [])
            self.assertTrue(check(report(full), ctx, "dijkstra"), "11.0 is Conway")
            self.assertEqual(check(report(full, "12.0"), ctx, "dijkstra"), [])
            missing = check(report({samples[0]: True}), ctx, "conway")
            self.assertTrue(any("1 of 2 samples have no result" in p for p in missing), missing)
            stray = check(report({**full, "nonexistent.input.cbor": True}), ctx, "conway")
            self.assertTrue(any("name no sample of the corpus: nonexistent.input.cbor" in p for p in stray), stray)
        self.assertEqual(scenarios.era_of_protocol("12.0"), "dijkstra")
        self.assertIsNone(scenarios.era_of_protocol("9.0"))

    def test_the_plutus_scenarios_name_no_evaluator(self):
        # Scarab's Plutus evaluator is its own (`uplc`), the only one: no tool takes an evaluator argument
        mounted = {name: f"/inputs/{name}" for name in inputs.load_pins()}
        ctx = scenarios.Context(COMMIT, inputs.load_pins(), {}, mounted)
        for name in ("ledger-rules", "uplc-conformance", "uplc-turbo"):
            arguments = scenarios.SCENARIOS[name].arguments(ctx, None, "/out")
            for absent in ("scarab", "scalus", "--plutus-vm", "--memory-limit"):
                self.assertNotIn(absent, arguments, name)
        self.assertEqual(scenarios.SCENARIOS["uplc-conformance"].arguments(ctx, None, "/out"),
                         [mounted["uplc-conformance-corpus"],
                          f"{mounted['site']}/scenarios/uplc-conformance/expectations.json", "/out/report.json"])
        self.assertEqual(scenarios.SCENARIOS["uplc-turbo"].arguments(ctx, None, "/out"),
                         [mounted["uplc-turbo-corpus"], "/out/report.json"])
        self.assertEqual(scenarios.SCENARIOS["ledger-rules"].arguments(ctx, None, "/out")[-2:],
                         ["--dataset-commit", ctx.pins["ledger-rules-documents"].spec["commit"]])
        self.assertIn(COMMIT, scenarios.SCENARIOS["cbor-decoding"].arguments(ctx, "conway", "/out"))
        self.assertEqual({scenario.id: scenario.reference_cpu for scenario in scenarios.SCENARIOS.values()
                          if scenario.reference_cpu is not None}, {"uplc-turbo": scenarios.TURBO_REFERENCE_CPU},
                         "uplc-turbo alone reports timings, and has a reference CPU")


def checkout(root):
    return run.Checkout(Path(root), COMMIT, False, Path(root) / ".git", Path(root) / ".cache")


def before_finish():
    """The provenance run.py has built when it hands a report to `finish`, which adds the report's fields."""
    return {
        "schemaVersion": 1, "scenario": "ledger-rules", "era": None,
        "scarab": {"repository": "KD5UJC-XYZ/scarab", "commit": COMMIT, "dirty": False},
        "tool": {"main": "m", "arguments": [], "jvm": [], "exit_status": 0}, "inputs": [],
        "environment": {"image": "i", "image_id": None, "java": None, "docker": None, "python": "3",
                        "runner": "local", "cpu": {"model": None, "logical_cpus": 4, "available_cpus": 3,
                                                   "quota_cpus": None},
                        "limits": {"cpus": "3", "memory": "8g"}},
        "timings_seconds": {"build": 1.0, "fetch": 0.0, "run": 1.0},
        "started_at": "2026-11-14T10:00:00Z", "finished_at": "2026-11-14T10:01:00Z",
    }


# The fields `finish` adds, and the validation it records.
AFTER = {"validation": {"schema": "s", "valid": True, "checks": []}, "report": "r", "sha256": "0" * 64, "bytes": 1,
         "json_sha256": "0" * 64, "generated_at": 0}


class ProvenanceTest(unittest.TestCase):
    def test_the_shape_run_py_writes(self):
        self.assertEqual(provenance.problems(before_finish() | AFTER), [])
        cases = {
            "a count as text": ("cpu", {"model": None, "logical_cpus": "4", "available_cpus": None,
                                        "quota_cpus": None}),
            "a count of zero": ("cpu", {"model": None, "logical_cpus": 0, "available_cpus": None, "quota_cpus": None}),
            "a boolean count": ("cpu", {"model": None, "logical_cpus": True, "available_cpus": None,
                                        "quota_cpus": None}),
            "a field it does not have": ("cpu", {"model": None, "logical_cpus": 4, "available_cpus": 3,
                                                 "quota_cpus": None, "speed": 1}),
            "a missing field": ("cpu", {"model": None, "logical_cpus": 4}),
            "cpu not an object": ("cpu", "x"),
            "limits of another form": ("limits", {"cpus": "3 cpus", "memory": "8g"}),
            "a runner it does not know": ("runner", "lab"),
        }
        for case, (field, value) in cases.items():
            with self.subTest(case):
                record = before_finish() | AFTER
                record["environment"] = dict(record["environment"], **{field: value})
                self.assertTrue(provenance.problems(record), case)
        self.assertTrue(provenance.problems(dict(before_finish() | AFTER, extra=1)), "no field of its own")
        self.assertEqual(provenance.cpu_model({"environment": {"cpu": {"model": "M"}}}), "M")
        for record in ({}, {"environment": None}, {"environment": {"cpu": {"model": ""}}}, None):
            self.assertIsNone(provenance.cpu_model(record), repr(record))


class RunTest(unittest.TestCase):
    def test_classpath_is_the_last_work_path_line(self):
        output = "[info] welcome\n/work/old:/work/x\n[success] done\n/work/a/classes:/work/.cache/b.jar\n"
        self.assertEqual(run.parse_classpath(output), ["/work/a/classes", "/work/.cache/b.jar"])
        with self.assertRaises(run.RunError):
            run.parse_classpath("[error] failed\n")

    def test_main_class_found_in_a_directory_or_a_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            facts = checkout(tmp)
            classes = Path(tmp, "tools", "classes", "x", "y")
            classes.mkdir(parents=True)
            (classes / "Main.class").write_bytes(b"")
            Path(tmp, ".cache").mkdir()
            with zipfile.ZipFile(Path(tmp, ".cache", "lib.jar"), "w") as jar:
                jar.writestr("p/Q.class", b"")
            classpath = ["/work/tools/classes", "/work/.cache/lib.jar"]
            self.assertTrue(run.has_class(classpath, "x.y.Main", facts))
            self.assertTrue(run.has_class(classpath, "p.Q", facts))
            self.assertFalse(run.has_class(classpath, "x.y.Absent", facts))

    def test_tool_runs_without_network_on_read_only_inputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            facts = checkout(tmp)
            mounted = {name: f"/inputs/{name}" for name in inputs.load_pins()}
            ctx = scenarios.Context(COMMIT, inputs.load_pins(), {}, mounted)
            command = run.tool_command(scenarios.SCENARIOS["uplc-turbo"], None, facts, ctx, "/work/cp", Path("/i"),
                                       Path("/w"), "img", "owner-1", "3", "8g")
            text = " ".join(command)
            self.assertIn("--network none", text)
            self.assertIn(f"{tmp}:/work:ro", text)
            self.assertIn("/i:/inputs:ro", text)
            self.assertIn("xyz.kd5ujc.scarab.owner=owner-1", text)
            self.assertIn("-Xms4g -Xmx4g", text)

    def test_finish_names_a_valid_report_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = Site(Path(tmp, "site")).root
            out = Path(tmp, "out")
            out.mkdir()
            ctx = context(site)
            raw = Path(tmp, "report.json")
            raw.write_text(json.dumps(ledger_report(["00001", "00002"])))
            outcome = run.finish(scenarios.SCENARIOS["ledger-rules"], None, raw, ctx, out, before_finish())
            self.assertEqual(outcome.status, "report", (outcome.detail, outcome.checks))
            self.assertEqual(outcome.report, "scarab-20261114-ledger-rules.json.gz")
            record = json.loads((out / "scarab-20261114-ledger-rules.provenance.json").read_text())
            self.assertTrue(record["validation"]["valid"])
            self.assertEqual(record["sha256"], __import__("hashlib").sha256(
                (out / outcome.report).read_bytes()).hexdigest())
            self.assertEqual(json.loads(gzip.decompress((out / outcome.report).read_bytes())),
                             json.loads(raw.read_text()))

            failed_out = Path(tmp, "failed")
            failed_out.mkdir()
            raw.write_text(json.dumps(ledger_report(["00001"])))
            outcome = run.finish(scenarios.SCENARIOS["ledger-rules"], None, raw, ctx, failed_out, before_finish())
            self.assertEqual(outcome.status, "failed")
            self.assertEqual(list(failed_out.iterdir()), [], "a failed report is not named")

    def test_a_provenance_of_another_shape_is_never_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = Site(Path(tmp, "site")).root
            out = Path(tmp, "out")
            out.mkdir()
            raw = Path(tmp, "report.json")
            raw.write_text(json.dumps(ledger_report(["00001", "00002"])))
            record = before_finish()
            record["environment"]["limits"] = {"cpus": "three", "memory": "8g"}
            outcome = run.finish(scenarios.SCENARIOS["ledger-rules"], None, raw, context(site), out, record)
            self.assertEqual(outcome.status, "failed")
            self.assertIn("its provenance is not one publish.py takes", outcome.detail)
            self.assertIn("$.environment.limits.cpus", outcome.detail)
            self.assertEqual(list(out.iterdir()), [], "neither the report nor its provenance is written")

    def test_implementation_commit_must_be_this_checkout(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = Site(Path(tmp, "site")).root
            ctx = context(site)
            dataset = inputs.load_pins()["cbor-dataset"].spec["commit"]
            raw = Path(tmp, "report.json")
            raw.write_text(json.dumps({"protocol_version": "11.0", "dataset_commit": dataset, "generated_at": 0,
                                       "implementation_commit": "b" * 40, "results": {"r/valid/x.input.cbor": True}}))
            ctx = context(site, cbor_dataset(Path(tmp, "cbor"), "conway", ["r/valid/x.input.cbor"]))
            outcome = run.finish(scenarios.SCENARIOS["cbor-decoding"], "conway", raw, ctx, Path(tmp), {})
            self.assertEqual(outcome.status, "failed")
            self.assertTrue(any("implementation_commit" in check for check in outcome.checks))

    def test_a_report_with_nan_is_neither_named_nor_published(self):
        with tempfile.TemporaryDirectory() as tmp:
            site = Site(Path(tmp, "site")).root
            out = Path(tmp, "out")
            out.mkdir()
            raw = Path(tmp, "report.json")
            raw.write_text(json.dumps(ledger_report(["00001", "00002"]))[:-1] + ', "extra": NaN}')
            outcome = run.finish(scenarios.SCENARIOS["ledger-rules"], None, raw, context(site), out, {})
            self.assertEqual(outcome.status, "failed")
            self.assertIn("not standard JSON", outcome.detail)
            self.assertEqual(list(out.iterdir()), [], "nothing is named")
            self.assertEqual(publish.collect([out]), [], "and nothing is there to publish")

    def test_the_cache_is_created_before_docker_mounts_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = Path(tmp, "fresh", "cache")
            run.prepare_cache(cache)
            self.assertTrue(cache.is_dir())
            if os.geteuid() != 0:
                locked = Path(tmp, "locked")
                locked.mkdir(mode=0o500)
                with self.assertRaises(run.RunError):
                    run.prepare_cache(locked)
                locked.chmod(0o700)

    def test_turbo_runs_on_ci_as_any_scenario_does(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), \
                mock.patch.object(run, "checkout_facts", return_value=checkout(tmp)), \
                mock.patch.object(run, "run_scenarios", return_value=[]) as ran, \
                mock.patch("sys.stdout", new=io.StringIO()), mock.patch("sys.stderr", new=io.StringIO()):
            self.assertEqual(run.main(["ledger-rules", "uplc-turbo", "--out", str(Path(tmp, "out"))]), 0)
            self.assertEqual([scenario.id for scenario in ran.call_args.args[2]], ["ledger-rules", "uplc-turbo"])
        with mock.patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            run.arguments(["uplc-turbo", "--out", "/x", "--allocation-only"])

    def test_the_cpu_is_read_as_the_probe_prints_it(self):
        x86 = "logical 4\navailable 3\nquota 300000 100000\nmodel name\t: Intel(R) Xeon(R) 6975P-C\n"
        self.assertEqual(run.parse_cpu(x86), {"model": "Intel(R) Xeon(R) 6975P-C", "logical_cpus": 4,
                                              "available_cpus": 3, "quota_cpus": 3.0})
        self.assertEqual(run.parse_cpu("logical 8\navailable 8\nquota max 100000\n"),
                         {"model": None, "logical_cpus": 8, "available_cpus": 8, "quota_cpus": None},
                         "a kernel that names no model, and a cgroup that sets no quota")
        self.assertEqual(run.parse_cpu("logical 16\navailable \nquota 150000 100000\nmodel name\t:\n"),
                         {"model": None, "logical_cpus": 16, "available_cpus": None, "quota_cpus": 1.5},
                         "an empty name is none, and nproc's absence leaves the machine's count")
        for output in ("", "logical 0\n", "logical -2\n", "logical four\n", "logical 4.5\n", "4\n"):
            self.assertIsNone(run.parse_cpu(output)["logical_cpus"], repr(output))
        for quota in ("", "300000", "300000 0", "a b", "300000 100000 1"):
            self.assertIsNone(run.parse_cpu(f"quota {quota}\n")["quota_cpus"], repr(quota))

    def test_the_environment_records_the_cpu_and_limits_the_tool_container_sees(self):
        seen = []

        def docker(argv, **kwargs):
            seen.append(argv)
            output = "logical 4\navailable 3\nquota 300000 100000\nmodel name\t: Some CPU @ 2.00GHz\n" \
                if argv[-3:-1] == ["sh", "-c"] else "x\n"
            return subprocess.CompletedProcess(argv, 0, output, "")

        with mock.patch.object(run.subprocess, "run", side_effect=docker), \
                mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            recorded = run.environment("img", "owner-1", "3", "8g")
        self.assertEqual(recorded["cpu"], {"model": "Some CPU @ 2.00GHz", "logical_cpus": 4, "available_cpus": 3,
                                           "quota_cpus": 3.0})
        self.assertEqual(recorded["limits"], {"cpus": "3", "memory": "8g"})
        self.assertEqual(recorded["runner"], "github-actions")
        probe = next(argv for argv in seen if argv[-3:-1] == ["sh", "-c"])
        self.assertEqual(probe[-1], run.CPU_PROBE)
        text = " ".join(probe)
        for flags in ("--network none", "--cpus 3 --memory 8g", "xyz.kd5ujc.scarab.owner=owner-1"):
            self.assertIn(flags, text, "the probe runs as the tool's containers do")
        with mock.patch.object(run.subprocess, "run", side_effect=lambda argv, **kwargs: subprocess.CompletedProcess(
                argv, 125, "", "docker: Error response from daemon: no such image\nmore")):
            self.assertEqual(run.environment("img", "o", "3", "8g")["cpu"],
                             {"model": None, "logical_cpus": None, "available_cpus": None, "quota_cpus": None,
                              "failure": "the probe exited 125: docker: Error response from daemon: no such image"},
                             "a failed probe records no CPU, and why")
        with mock.patch.object(run.subprocess, "run",
                               side_effect=lambda argv, **kwargs: subprocess.CompletedProcess(argv, 0, "", "")):
            self.assertEqual(run.environment("img", "o", "3", "8g")["cpu"]["failure"], "the probe printed no CPU count")
        with mock.patch.object(run.subprocess, "run", side_effect=FileNotFoundError(2, "No such file or directory")):
            recorded = run.environment("img", "o", "3", "8g")
        self.assertEqual((recorded["image_id"], recorded["java"], recorded["docker"]), (None, None, None),
                         "a command that cannot start gives None, not an exception")
        self.assertEqual(recorded["cpu"]["failure"], "docker could not be started: No such file or directory")
        self.assertEqual(provenance.problems(dict(before_finish(), environment=recorded) | AFTER), [],
                         "and the record keeps its shape")

    def test_on_ci_a_timing_scenario_off_its_reference_cpu_ends_before_the_build(self):
        reference = scenarios.TURBO_REFERENCE_CPU
        cases = {
            "another CPU": ("Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz", "github-actions", False, True),
            "an unknown CPU": (None, "github-actions", False, True),
            "the reference CPU": (reference, "github-actions", False, False),
            "any CPU asked for": ("Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz", "github-actions", True, False),
            "a local machine": ("Some CPU", "local", False, False),
        }
        selected = [scenarios.SCENARIOS["ledger-rules"], scenarios.SCENARIOS["uplc-turbo"]]
        for case, (model, runner, any_cpu, skipped) in cases.items():
            with self.subTest(case), mock.patch("sys.stderr", new=io.StringIO()) as err:
                env = {"runner": runner, "cpu": {"model": model, "logical_cpus": 4, "failure": "the probe exited 1"}
                       if model is None else {"model": model, "logical_cpus": 4}}
                kept, outcomes = run.off_reference(selected, env, any_cpu)
                self.assertEqual([scenario.id for scenario in kept],
                                 ["ledger-rules"] if skipped else ["ledger-rules", "uplc-turbo"])
                self.assertEqual([(o.scenario, o.status) for o in outcomes],
                                 [("uplc-turbo", "not-reference-cpu")] if skipped else [])
                if skipped:
                    self.assertIn(f"not the reference {reference}", outcomes[0].detail)
                    self.assertIn(model or "unknown (the probe exited 1)", outcomes[0].detail)
                if model != reference and not skipped:
                    self.assertIn("publish.py will refuse its report", err.getvalue(), "measured, and said so")

    def test_a_run_off_the_reference_cpu_on_ci_builds_nothing_and_succeeds(self):
        calls = []

        def docker(argv, **kwargs):
            calls.append(argv)
            if argv[-3:-1] == ["sh", "-c"]:
                return subprocess.CompletedProcess(argv, 0, "logical 4\navailable 3\nquota 300000 100000\n"
                                                   "model name\t: Another CPU\n", "")
            return subprocess.CompletedProcess(argv, 0, "x\n", "")

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), \
                mock.patch.object(run, "checkout_facts", return_value=checkout(tmp)), \
                mock.patch.object(run.subprocess, "run", side_effect=docker), \
                mock.patch("sys.stdout", new=io.StringIO()) as out, mock.patch("sys.stderr", new=io.StringIO()):
            Path(tmp, scenarios.SCENARIOS["uplc-turbo"].source).parent.mkdir(parents=True)
            Path(tmp, scenarios.SCENARIOS["uplc-turbo"].source).write_text("")
            self.assertEqual(run.main(["uplc-turbo", "--out", str(Path(tmp, "out"))]), 0, "the job stays green")
            summary = json.loads(Path(tmp, "out", "summary.json").read_text())
        self.assertFalse([argv for argv in calls if "scripts/sbtw" in argv], "the tools were not built")
        self.assertEqual([(o["scenario"], o["status"]) for o in summary["outcomes"]],
                         [("uplc-turbo", "not-reference-cpu")])
        self.assertIn("not-reference-cpu uplc-turbo: this runner's CPU is Another CPU", out.getvalue())

    def test_an_out_directory_with_reports_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(run, "checkout_facts", return_value=checkout(tmp)), \
                mock.patch.object(run, "run_scenarios", side_effect=AssertionError("ran")), \
                mock.patch("sys.stderr", new=io.StringIO()):
            Path(tmp, "scarab-20261114-ledger-rules.json.gz").write_bytes(b"")
            self.assertEqual(run.main(["ledger-rules", "--out", tmp]), 2)

    def test_owner_label_is_checked(self):
        with mock.patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            run.arguments(["ledger-rules", "--out", "/x", "--owner", "a b"])


if __name__ == "__main__":
    unittest.main()

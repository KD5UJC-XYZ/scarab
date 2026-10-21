# SPDX-License-Identifier: Apache-2.0
"""Controller safety/evidence tests; synthetic archives are never native acceptance receipts."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

MODULE = Path(__file__).resolve().parents[1] / "candidate_acceptance.py"
spec = importlib.util.spec_from_file_location("candidate_acceptance", MODULE)
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)
DATA = MODULE.parent / "candidate_acceptance"


class CandidateAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = candidate.load_json(DATA / "inputs.json")
        self.cases = candidate.load_json(DATA / "cases.json")

    def available(self):
        value = copy.deepcopy(self.manifest)
        value.update(status="reviewed", provenance="synthetic controller test only", nativeK1SecondaryCount=29035)
        value["artifact"].update(url="https://example.invalid/public-fixtures.tar", sha256="ab" * 32)
        return value

    def archive(self, entries):
        path = self.root / "archive.tar"
        with tarfile.open(path, "w:gz", format=tarfile.USTAR_FORMAT) as archive:
            for name, kind, payload in entries:
                info = tarfile.TarInfo(name)
                info.type = kind
                info.size = len(payload) if kind == tarfile.REGTYPE else 0
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    info.linkname = "../outside"
                archive.addfile(info, io.BytesIO(payload) if info.size else None)
        return path

    def rejected_extract(self, entries, **limits):
        manifest = self.available()
        artifact = dict(manifest["artifact"], **limits)
        archive = self.archive(entries)
        with self.assertRaises((candidate.Rejected, OSError, tarfile.TarError)):
            candidate.extract(archive, self.root / "inputs", artifact, manifest["environment"])

    def report(self):
        required, additional = candidate.identities(self.cases)
        root = ET.Element("testsuite", tests=str(len(required | additional)), failures="0", errors="0", skipped="0")
        for suite, name in sorted(required | additional):
            ET.SubElement(root, "testcase", classname=suite, name=name)
        path = self.root / "report.xml"
        ET.ElementTree(root).write(path)
        return path

    def test_unavailable_manifest_fails_before_download_or_docker(self):
        with patch.object(candidate, "download") as download, patch.object(candidate.subprocess, "run") as docker, patch.object(candidate.subprocess, "check_output", return_value="a" * 40):
            with self.assertRaisesRegex(candidate.Rejected, "unavailable"):
                candidate.run(DATA / "inputs.json", DATA / "cases.json", MODULE.parents[1], self.root / "output")
            download.assert_not_called()
            docker.assert_not_called()
        receipt = candidate.load_json(self.root / "output/failure.json")
        self.assertEqual(receipt["status"], "failed")
        self.assertFalse((self.root / "output/receipt.json").exists())

    def test_download_checks_actual_bytes_and_compressed_limit(self):
        artifact = self.available()["artifact"]
        payload = b"synthetic controller bytes"
        import hashlib
        artifact["sha256"] = hashlib.sha256(payload).hexdigest()
        for bad in (None, "hash", "size", "redirect"):
            with self.subTest(bad=bad):
                response = io.BytesIO(payload)
                response.url = "http://example.invalid/a" if bad == "redirect" else artifact["url"]
                current = dict(artifact)
                if bad == "hash":
                    current["sha256"] = "ab" * 32
                if bad == "size":
                    current["maxBytes"] = len(payload) - 1
                target = self.root / (str(bad) + ".tar")
                with patch.object(candidate, "open_https", return_value=response):
                    if bad is None:
                        candidate.download(current, target)
                        self.assertEqual(target.read_bytes(), payload)
                    else:
                        with self.assertRaises(candidate.Rejected):
                            candidate.download(current, target)

    def test_manifest_rejects_missing_identity_limits_and_unapproved_environment(self):
        good = self.available()
        candidate.validate_manifest(good)
        mutations = [lambda x: x["artifact"].update(sha256=None),
                     lambda x: x["artifact"].update(url="http://example.invalid/a"),
                     lambda x: x["artifact"].update(maxEntries=0),
                     lambda x: x["artifact"].update(maxBytes=True),
                     lambda x: x["environment"].update(SCARAB_IMPORT_DB={"path": "original", "kind": "directory"}),
                     lambda x: x["environment"]["HASKELL_STATE_FILE"].update(path="../outside")]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                value = copy.deepcopy(good)
                mutate(value)
                with self.assertRaises(candidate.Rejected):
                    candidate.validate_manifest(value)

    def test_archive_rejects_traversal_links_devices_and_unmapped_members(self):
        for name, kind in [("../outside", tarfile.REGTYPE), ("/absolute", tarfile.REGTYPE),
                           ("preview-k1/link", tarfile.SYMTYPE), ("preview-k1/hard", tarfile.LNKTYPE),
                           ("preview-k1/device", tarfile.CHRTYPE), ("unmapped", tarfile.REGTYPE),
                           ("preview-k1/operator.skey", tarfile.REGTYPE)]:
            with self.subTest(name=name):
                self.rejected_extract([(name, kind, b"data")])
                if (self.root / "inputs").exists():
                    import shutil
                    shutil.rmtree(self.root / "inputs")
        self.assertFalse((self.root.parent / "outside").exists())

    def test_extended_archive_headers_fail_before_their_payload_is_processed(self):
        for kind in (tarfile.XHDTYPE, tarfile.XGLTYPE, tarfile.GNUTYPE_LONGNAME):
            with self.subTest(kind=kind):
                manifest = self.available()
                archive = self.archive([("preview-k1/metadata", kind, b"ignored")])
                with self.assertRaisesRegex(candidate.Rejected, "without extended headers"):
                    candidate.extract(archive, self.root / "inputs", manifest["artifact"], manifest["environment"])
                import shutil
                shutil.rmtree(self.root / "inputs")

    def test_duplicate_and_oversized_archive_members_fail(self):
        for entries, limits in [([("preview-k1/a", tarfile.REGTYPE, b"a")] * 2, {}),
                                 ([("preview-k1/a", tarfile.REGTYPE, b"abcd")], {"maxUnpackedBytes": 3}),
                                 ([("preview-k1/a", tarfile.REGTYPE, b"a"), ("preview-k1/b", tarfile.REGTYPE, b"b")], {"maxEntries": 1})]:
            with self.subTest(limits=limits):
                self.rejected_extract(entries, **limits)
                import shutil
                shutil.rmtree(self.root / "inputs")

    def test_regular_data_extracts_readonly_and_owned_cleanup_preserves_evidence(self):
        manifest = self.available()
        archive = self.archive([("preview-k1/a", tarfile.REGTYPE, b"original")])
        output = self.root / "output"
        output.mkdir()
        (output / ".candidate-owned.json").write_text(json.dumps({"path": str(output)}))
        (output / "receipt.json").write_text('{"status":"synthetic-controller-test"}')
        counts = candidate.extract(archive, output / "inputs", manifest["artifact"], manifest["environment"])
        self.assertEqual(counts, {"entries": 1, "payloadBytes": 8, "unpackedBytes": 10240})
        self.assertEqual((output / "inputs/preview-k1/a").read_bytes(), b"original")
        self.assertEqual((output / "inputs/preview-k1/a").stat().st_mode & 0o222, 0)
        self.assertEqual((output / "inputs").stat().st_mode & 0o222, 0)
        candidate.cleanup(output)
        self.assertTrue((output / "receipt.json").exists())
        self.assertFalse((output / "inputs").exists())

    def test_exact_twelve_and_declared_controls_pass_but_missing_duplicate_extra_or_skipped_fail(self):
        path = self.report()
        result = candidate.verify_reports([path], self.cases)
        self.assertEqual(result["mandatoryPassed"], 12)
        self.assertEqual(result["additionalPassed"], len(self.cases["additionalPassing"]))
        for kind in ("missing", "duplicate", "extra", "skipped", "error", "failure", "count"):
            with self.subTest(kind=kind):
                path = self.report()
                root = ET.parse(path).getroot()
                mandatory, _ = candidate.identities(self.cases)
                selected = next(case for case in root if (case.get("classname"), case.get("name")) in mandatory)
                if kind == "missing":
                    root.remove(selected)
                    root.set("tests", str(len(root)))
                elif kind == "duplicate":
                    root.append(copy.deepcopy(selected))
                    root.set("tests", str(len(root)))
                elif kind == "extra":
                    selected.set("name", "unknown case")
                elif kind == "count":
                    root.set("tests", "999")
                else:
                    ET.SubElement(selected, kind)
                ET.ElementTree(root).write(path)
                with self.assertRaises(candidate.Rejected):
                    candidate.verify_reports([path], self.cases)
        with self.assertRaises(candidate.Rejected):
            candidate.verify_reports([], self.cases)

    def test_duplicate_json_and_report_entities_are_not_accepted(self):
        path = self.root / "bad.json"
        path.write_text('{"status":"passed","status":"failed"}')
        with self.assertRaises(candidate.Rejected):
            candidate.load_json(path)
        path.write_text('<!DOCTYPE x [<!ENTITY y "x">]><testsuite tests="0"/>')
        with self.assertRaises(candidate.Rejected):
            candidate.verify_reports([path], self.cases)

    def test_aggregate_root_errors_and_hidden_failure_nodes_never_count_as_pass(self):
        path = self.report()
        original = ET.parse(path).getroot()
        for root in (ET.Element("testsuites", errors="1"), copy.deepcopy(original)):
            if root.tag == "testsuites":
                root.append(copy.deepcopy(original))
            else:
                ET.SubElement(root, "error").text = "failure outside testcase"
            ET.ElementTree(root).write(path)
            with self.assertRaises(candidate.Rejected):
                candidate.verify_reports([path], self.cases)

    def test_failing_sbt_and_verifier_retain_actual_report_bytes_without_passing_receipt(self):
        from contextlib import ExitStack
        from types import SimpleNamespace
        for fails_sbt in (True, False):
            with self.subTest(fails_sbt=fails_sbt), ExitStack() as patches:
                checkout = self.root / str(fails_sbt)
                checkout.mkdir()
                manifest = checkout / "inputs.json"
                manifest.write_text(json.dumps(self.available()))
                output = self.root / ("evidence-" + str(fails_sbt))
                report = checkout / "storage/target/test-reports/TEST-failed.xml"
                body = b'<testsuite tests="1" failures="1"><testcase classname="bad" name="failed"><failure>actual test failure</failure></testcase></testsuite>'
                def check_output(command, **_):
                    if command[:2] == ["git", "status"]:
                        return ""
                    if command[:2] == ["docker", "info"]:
                        return str(32 * 1024**3)
                    return "a" * 40
                def execute(command, **_):
                    if command[:2] == ["docker", "run"]:
                        report.parent.mkdir(parents=True)
                        report.write_bytes(body)
                        if fails_sbt:
                            raise candidate.subprocess.CalledProcessError(7, command)
                patches.enter_context(patch.object(candidate.subprocess, "check_output", side_effect=check_output))
                patches.enter_context(patch.object(candidate.subprocess, "run", side_effect=execute))
                patches.enter_context(patch.object(candidate.shutil, "disk_usage", return_value=SimpleNamespace(free=2**50)))
                patches.enter_context(patch.object(candidate, "download"))
                patches.enter_context(patch.object(candidate, "extract", return_value={"entries": 1, "bytes": 1}))
                patches.enter_context(patch.object(candidate, "mapped_inputs", return_value={key: self.root for key in candidate.INPUT_KINDS}))
                patches.enter_context(patch.object(candidate, "native_input_checks"))
                with self.assertRaises((candidate.Rejected, candidate.subprocess.CalledProcessError)):
                    candidate.run(manifest, DATA / "cases.json", checkout, output)
                self.assertEqual((output / "junit/storage/TEST-failed.xml").read_bytes(), body)
                self.assertTrue((output / "sbt.log").exists())
                self.assertEqual(candidate.load_json(output / "failure.json")["status"], "failed")
                self.assertFalse((output / "receipt.json").exists())

    def local_fixture(self):
        import hashlib
        root = self.root / "local"
        root.mkdir()
        (root / "dataset").mkdir()
        (root / "dataset/a").write_bytes(b"original")
        inventory = self.root / "inventory.json"
        inventory.write_text(json.dumps({"schemaVersion": 1, "files": [
            {"path": "dataset/a", "bytes": 8, "sha256": hashlib.sha256(b"original").hexdigest()}
        ]}))
        artifact = dict(self.available()["artifact"], kind="local-inventory", url=None,
                        inventory="inventory.json", sha256=candidate.sha256(inventory))
        environment = {"SCARAB_MEMPACK_TABLES": {"path": "dataset/tables", "kind": "file"},
                       "fixture": {"path": "dataset", "kind": "directory"}}
        return root, artifact, inventory, environment

    def test_local_originals_are_verified_without_chmod_copy_or_download(self):
        root, artifact, inventory, environment = self.local_fixture()
        before = (root / "dataset/a").stat()
        with patch.object(candidate, "download") as download:
            result = candidate.verify_local_inputs(root, artifact, inventory, environment)
            download.assert_not_called()
        self.assertEqual(result["files"], 1)
        self.assertEqual(result["payloadBytes"], 8)
        after = (root / "dataset/a").stat()
        self.assertEqual((before.st_ino, before.st_mode, before.st_mtime_ns),
                         (after.st_ino, after.st_mode, after.st_mtime_ns))

    def test_local_inventory_rejects_changed_missing_extra_linked_and_unmapped_inputs(self):
        import shutil
        for kind in ("changed", "missing", "extra", "linked", "inventory", "unmapped", "duplicate", "size", "entries"):
            with self.subTest(kind=kind):
                root, artifact, inventory, environment = self.local_fixture()
                if kind == "changed":
                    (root / "dataset/a").write_bytes(b"modified")
                elif kind == "missing":
                    (root / "dataset/a").unlink()
                elif kind == "extra":
                    (root / "dataset/extra").write_bytes(b"extra")
                elif kind == "linked":
                    (root / "dataset/a").unlink()
                    (root / "dataset/a").symlink_to(inventory)
                elif kind == "inventory":
                    inventory.write_text("{}")
                elif kind == "unmapped":
                    environment["fixture"]["path"] = "other"
                elif kind == "duplicate":
                    document = candidate.load_json(inventory)
                    document["files"] *= 2
                    inventory.write_text(json.dumps(document))
                    artifact["sha256"] = candidate.sha256(inventory)
                elif kind == "size":
                    artifact["maxUnpackedBytes"] = 7
                else:
                    artifact["maxEntries"] = 1
                with self.assertRaises(candidate.Rejected):
                    candidate.verify_local_inputs(root, artifact, inventory, environment)
                shutil.rmtree(root)

    def test_local_mode_and_pinned_native_count_are_explicit(self):
        value = self.available()
        value["artifact"].update(kind="local-inventory", url=None, inventory="inventory.json")
        candidate.validate_manifest(value)
        for kind in ("missing-inventory", "url", "count", "boolean-count"):
            altered = copy.deepcopy(value)
            if kind == "missing-inventory":
                altered["artifact"]["inventory"] = "../escape"
            elif kind == "url":
                altered["artifact"]["url"] = "https://example.invalid/data"
            else:
                altered["nativeK1SecondaryCount"] = True if kind == "boolean-count" else 0
            with self.assertRaises(candidate.Rejected):
                candidate.validate_manifest(altered)

    def test_output_cannot_be_created_inside_source_checkout(self):
        checkout = self.root / "checkout"
        checkout.mkdir()
        with self.assertRaisesRegex(candidate.Rejected, "outside"):
            candidate.run(DATA / "inputs.json", DATA / "cases.json", checkout, checkout / "output")
        self.assertFalse((checkout / "output").exists())

    def test_insufficient_docker_memory_fails_before_download(self):
        from types import SimpleNamespace
        manifest = self.root / "reviewed.json"
        manifest.write_text(json.dumps(self.available()))
        def check_output(command, **_):
            if command[:2] == ["git", "status"]:
                return ""
            if command[:2] == ["docker", "info"]:
                return str(2 * 1024**3)
            return "a" * 40
        with patch.object(candidate.subprocess, "check_output", side_effect=check_output), patch.object(candidate, "download") as download, patch.object(candidate.shutil, "disk_usage", return_value=SimpleNamespace(free=2**50)):
            with self.assertRaisesRegex(candidate.Rejected, "24 GiB"):
                candidate.run(manifest, DATA / "cases.json", MODULE.parents[1], self.root / "evidence")
            download.assert_not_called()

    def test_native_export_count_without_real_body_comparisons_is_not_receipt(self):
        path = self.root / "summary.json"
        for value in [{"blocks": 4, "referenceTargetsExported": 4},
                      {"blocks": 4, "referenceTargetsRequired": 4, "referenceBodyComparisons": 3},
                      {"blocks": 4, "referenceTargetsRequired": 0, "referenceBodyComparisons": 0},
                      {"blocks": 4, "referenceTargetsRequired": 1, "referenceBodyComparisons": True}]:
            path.write_text(json.dumps(value))
            with self.assertRaises(candidate.Rejected):
                candidate.replay_receipt(path)
        path.write_text(json.dumps({"blocks": 4, "referenceTargetsRequired": 4, "referenceBodyComparisons": 4}))
        self.assertEqual(candidate.replay_receipt(path)["referenceBodyComparisons"], 4)

    def test_runtime_receipt_requires_observed_fork_configuration(self):
        path = self.root / "sbt.log"
        heap = 16 * 1024**3
        good = f"candidate-runtime project=storage maxHeapBytes={heap} processors=4\ncandidate-runtime project=node maxHeapBytes={heap} processors=4\n"
        path.write_text(good)
        self.assertEqual(candidate.runtime_receipt(path)["storage"]["maxHeapBytes"], heap)
        for value in ("", good + good, good.replace(str(heap), str(2 * 1024**3)), good.replace("processors=4", "processors=2")):
            path.write_text(value)
            with self.assertRaises(candidate.Rejected):
                candidate.runtime_receipt(path)

    def test_commands_run_whole_suites_without_benchmark_or_filtered_skip_workaround(self):
        commands = candidate.sbt_commands(self.cases)
        self.assertFalse(any("--tests" in command or "Bench" in command for command in commands))
        self.assertTrue(any("-Xmx16g" in command for command in commands))
        self.assertEqual(sum("/testOnly " in command for command in commands), 2)
        self.assertEqual(sum("Tests.SubProcess" in command for command in commands), 2)

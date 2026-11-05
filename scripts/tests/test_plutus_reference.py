# SPDX-License-Identifier: Apache-2.0
import gzip
import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import plutus_reference as reference

PARAM_NAMES = """module PlutusLedgerApi.{language}.ParamName where
data ParamName =
    AddInteger'cpu'arguments'intercept
  | AddInteger'cpu'arguments'slope
  -- Plomin
  | {extra}
  deriving stock (Eq, Ord, Enum, Ix, Bounded, Generic)
"""


def archive(commit: str, files: dict[str, str], extra: str = "AndByteString'cpu'arguments") -> bytes:
    """A codeload-shaped archive: every path under `plutus-<commit>/`."""
    contents = {
        **{f"plutus-core/cost-model/data/{name}": f"{{\"{name}\": 1}}" for name in reference.COST_MODEL_FILES},
        **{f"plutus-ledger-api/src/PlutusLedgerApi/{language}/ParamName.hs":
           PARAM_NAMES.format(language=language, extra=extra) for language in reference.PARAM_NAME_LANGUAGES},
        "plutus-core/cost-model/data/models.R": "ignored",
        **{f"plutus-conformance/test-cases/uplc/evaluation/{path}": text for path, text in files.items()},
    }
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as bundle:
        for path, text in contents.items():
            data = text.encode()
            member = tarfile.TarInfo(f"plutus-{commit}/{path}")
            member.size = len(data)
            bundle.addfile(member, io.BytesIO(data))
    return gzip.compress(buffer.getvalue(), mtime=0)


OLD = {
    "a/a.uplc": "(program 1.0.0 (con integer 1))",
    "a/a.uplc.expected": "(program 1.0.0 (con integer 1))",
    "a/a.budget.expected": "({cpu: 1 | mem: 1})",
    "a/a.flat": "ignored",
    "v/v.uplc": "(program 1.0.0 (con value []))",
    "gone/gone.uplc": "(program 1.0.0 (con unit ()))",
    "README.md": "ignored",
}
NEW = {**{k: v for k, v in OLD.items() if not k.startswith("gone/")},
       "a/a.budget.expected": "({cpu: 2 | mem: 1})",
       "new/new.uplc": "(program 1.1.0 (con unit ()))"}


class ParamNamesTest(unittest.TestCase):
    def test_the_constructor_after_a_comment_is_kept(self):
        names = reference.param_names(PARAM_NAMES.format(language="V3", extra="AndByteString'cpu'arguments"))
        self.assertEqual(names.decode().splitlines(),
                         ["AddInteger'cpu'arguments'intercept", "AddInteger'cpu'arguments'slope",
                          "AndByteString'cpu'arguments"])


class ReleasesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name) / "plutus-reference"
        old = archive("old", OLD)
        view = reference.upstream_view(old)
        reference.write_files(self.root, view)
        (self.root / "conformance/v/v.flat.cbor.hex").write_text("4100")
        (self.root / "README.md").write_text("about")
        manifest = {"schemaVersion": 1, "base": "1", "generated": [
            {"path": "conformance/v/v.flat.cbor.hex", "sha256": reference.sha256(b"4100")}],
            "releases": [{"tag": "1", "commit": "old", "archiveSha256": reference.sha256(old),
                          "files": len(view), "digest": reference.digest(view), "absent": []}]}
        (self.root / reference.MANIFEST).write_text(json.dumps(manifest))
        self.old = view

    def tearDown(self):
        self.directory.cleanup()

    def test_the_upstream_view_keeps_only_cases_cost_models_and_parameter_names(self):
        self.assertEqual(sorted(p for p in self.old if p.startswith("conformance/")),
                         ["conformance/a/a.budget.expected", "conformance/a/a.uplc", "conformance/a/a.uplc.expected",
                          "conformance/gone/gone.uplc", "conformance/v/v.uplc"])
        self.assertIn("ledger-api/V3-param-names.txt", self.old)
        self.assertNotIn("cost-model/models.R", self.old)

    def test_a_new_release_becomes_the_base_and_the_old_keeps_only_its_differences(self):
        self.assertEqual(reference.check(self.root), [])
        reference.add_release(self.root, "2", "new", archive("new", NEW))
        self.assertEqual(reference.check(self.root), [])
        overlay = reference.files_under(self.root / "overlays/1")
        self.assertEqual(sorted(overlay), ["conformance/a/a.budget.expected", "conformance/gone/gone.uplc"])
        manifest = reference.read_manifest(self.root)
        self.assertEqual(manifest["base"], "2")
        self.assertEqual(manifest["releases"][0]["absent"], ["conformance/new/new.uplc"])
        self.assertEqual((self.root / "README.md").read_text(), "about")
        old_view = {p: f.read_bytes() for p, f in reference.release_view(self.root, "1").items()}
        self.assertEqual(reference.upstream_part(old_view, {"conformance/v/v.flat.cbor.hex": ""}), self.old)
        new_view = reference.release_view(self.root, "2")
        self.assertEqual(new_view["conformance/a/a.budget.expected"].read_text(), "({cpu: 2 | mem: 1})")
        self.assertIn("conformance/v/v.flat.cbor.hex", new_view)

    def test_a_changed_file_fails_the_check(self):
        (self.root / "conformance/a/a.budget.expected").write_text("({cpu: 9 | mem: 9})")
        self.assertTrue(any("digest" in problem for problem in reference.check(self.root)))

    def test_an_overlay_file_equal_to_the_base_is_refused(self):
        reference.add_release(self.root, "2", "new", archive("new", NEW))
        reference.write_files(self.root / "overlays/1", {"conformance/a/a.uplc": OLD["a/a.uplc"].encode()})
        self.assertIn("overlay 1/conformance/a/a.uplc repeats the base", reference.check(self.root))

    def test_an_overlay_holding_a_generated_or_absent_file_is_refused(self):
        reference.add_release(self.root, "2", "new", archive("new", NEW))
        reference.write_files(self.root / "overlays/1", {"conformance/v/v.flat.cbor.hex": b"4101",
                                                          "conformance/new/new.uplc": b"other"})
        problems = reference.check(self.root)
        self.assertIn("overlay 1/conformance/v/v.flat.cbor.hex is a generated file, shared by every release", problems)
        self.assertIn("overlay 1/conformance/new/new.uplc is also listed as absent", problems)

    def test_a_release_whose_sidecar_program_changed_is_refused(self):
        changed = {**NEW, "v/v.uplc": "(program 1.0.0 (con value [(#00, [(#, 1)])]))"}
        with self.assertRaises(reference.ReferenceError):
            reference.add_release(self.root, "2", "new", archive("new", changed))
        self.assertEqual(reference.check(self.root), [])

    def test_a_tampered_archive_is_refused(self):
        cache = Path(self.directory.name) / "cache"
        cache.mkdir()
        (cache / "old.tar.gz").write_bytes(archive("old", {**OLD, "a/a.uplc": "tampered"}))
        with self.assertRaises(reference.ReferenceError):
            reference.verify(self.root, cache)

    def test_verify_compares_each_view_with_its_archive(self):
        cache = Path(self.directory.name) / "cache"
        cache.mkdir()
        (cache / "old.tar.gz").write_bytes(archive("old", OLD))
        self.assertEqual(reference.verify(self.root, cache), [])


if __name__ == "__main__":
    unittest.main()

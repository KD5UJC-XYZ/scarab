# SPDX-License-Identifier: Apache-2.0
"""The refusal corpus as recorded: its manifest pins every file, and the family each failure is filed under
is the one the reference's own `cardano-cli` rendering names."""
import hashlib
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import refusalcorpus  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2] / "fixtures" / "reference" / "refusals"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class RecordedCorpus(unittest.TestCase):
    def corpora(self):
        manifest = json.loads((ROOT / "manifest.json").read_text())
        for entry in manifest["corpora"]:
            directory = ROOT / entry["path"]
            self.assertEqual(sha256(directory / "corpus.json"), entry["sha256"], entry["path"])
            yield directory, json.loads((directory / "corpus.json").read_text())

    def test_every_file_is_pinned(self):
        for directory, meta in self.corpora():
            listed = {c["file"] for c in meta["cases"]}
            present = {f"cases/{p.name}" for p in (directory / "cases").iterdir()}
            self.assertEqual(present, listed, directory.name)
            for c in meta["cases"]:
                self.assertEqual(sha256(directory / c["file"]), c["sha256"], c["file"])
            for name, digest in meta["genesis"].items():
                self.assertEqual(sha256(directory / "genesis" / name), digest, name)
            for sub in ("states", "utxo"):
                for p in (directory / sub).iterdir():
                    self.assertEqual(sha256(p), p.stem, p.name)

    def test_families_are_read_from_the_reference_bytes_and_named_in_its_rendering(self):
        for directory, meta in self.corpora():
            for c in meta["cases"]:
                text = (directory / c["file"]).read_text()
                cases = ([json.loads(text)] if meta["format"] == 1
                         else [json.loads(line) for line in text.splitlines()])
                for case in cases:
                    reason = bytes.fromhex(case["reason"])
                    self.assertEqual(hashlib.sha256(reason).hexdigest(), case["reasonSha256"], case["label"])
                    families = refusalcorpus.families(case["era"], reason)
                    self.assertEqual(families, case["families"], case["label"])
                    self.assertEqual(refusalcorpus.unnamed(case["era"], families, case["rendered"]), [], case["label"])

    def test_the_manifest_requires_every_era_the_corpora_hold(self):
        manifest = json.loads((ROOT / "manifest.json").read_text())
        self.assertEqual(manifest["coverage"], refusalcorpus.COVERAGE)
        for _, meta in self.corpora():
            self.assertIn(meta["era"], manifest["coverage"])


if __name__ == "__main__":
    unittest.main()

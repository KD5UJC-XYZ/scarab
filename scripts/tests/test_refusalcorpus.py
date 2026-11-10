# SPDX-License-Identifier: Apache-2.0
"""The refusal corpus as recorded: its manifest pins every file, and the family each failure is filed under
is the one the reference's own `cardano-cli` rendering names."""
import hashlib
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import ntcquery, refusalcorpus  # noqa: E402

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
                self.assertEqual(sha256(ROOT / "genesis" / f"{digest}-{name}"), digest, name)
            self.assertFalse((directory / "genesis").exists(), "genesis files are shared, not copied per corpus")
            for sub in ("states", "utxo"):
                for p in (directory / sub).iterdir():
                    self.assertEqual(sha256(p), p.stem, p.name)

    def test_every_shared_genesis_file_is_pinned_by_a_corpus(self):
        pinned = {f"{digest}-{name}" for _, meta in self.corpora() for name, digest in meta["genesis"].items()}
        self.assertEqual({p.name for p in (ROOT / "genesis").iterdir()}, pinned)

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

    def test_each_corpus_was_recorded_by_the_node_of_its_build(self):
        """A corpus without `referenceBuild` (one recorded before corpora named their build, #826) is 11.1's, and
        its provenance says so; `ReferenceRefusalSuite` reads it the same way. Every build has a corpus."""
        builds = set()
        for directory, meta in self.corpora():
            build = meta.get("referenceBuild", refusalcorpus.DEFAULT_BUILD)
            self.assertIn(build, refusalcorpus.BUILDS, directory.name)
            node = meta["provenance"]["reference"]
            self.assertTrue(refusalcorpus.is_build(build, node["node"], node["revision"]), directory.name)
            builds.add(build)
        self.assertEqual(builds, set(refusalcorpus.BUILDS))


class Builds(unittest.TestCase):
    ELEVEN = ("cardano-node 11.1.3 - linux-x86_64 - ghc-9.6", "938cba990357ae7c4b7f95c8f75dd9d31174bbeb")
    LEIOS = ("cardano-node 11.2.0.164 - linux-x86_64 - ghc-9.6", "b004fe0c50913dcdaa827a98e68a43d0ccce152a")

    def test_the_release_line_by_version_and_the_leios_build_by_commit(self):
        self.assertTrue(refusalcorpus.is_build("11.1", *self.ELEVEN))
        self.assertFalse(refusalcorpus.is_build("11.1", *self.LEIOS))
        self.assertTrue(refusalcorpus.is_build("leios-b004fe0c", *self.LEIOS))
        self.assertFalse(refusalcorpus.is_build("leios-b004fe0c", *self.ELEVEN))
        self.assertFalse(refusalcorpus.is_build("leios-b004fe0c", self.LEIOS[0], "b004fe0c" + "0" * 32))

    def test_the_leios_cluster_files_are_its_genesis_and_config_json(self):
        files = refusalcorpus.BUILDS["leios-b004fe0c"]["files"]
        self.assertIn("dijkstra-genesis.json", files)
        self.assertIn("config.json", files)
        self.assertEqual(refusalcorpus.BUILDS["11.1"]["files"], refusalcorpus.GENESIS)


@unittest.skipUnless(shutil.which("perl"), "no perl on this host")
class PerlRelay(unittest.TestCase):
    """The relay used where an image has no `socat` copies bytes both ways unchanged."""

    def test_bytes_go_both_ways_unchanged(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "sock")
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            server.bind(path)
            server.listen(1)
            payload = bytes(range(256)) * 1024

            def echo():
                conn, _ = server.accept()
                with conn:
                    got = b""
                    while len(got) < len(payload):
                        got += conn.recv(65536)
                    conn.sendall(got[::-1])

            worker = threading.Thread(target=echo)
            worker.start()
            relay = subprocess.run(ntcquery.RELAYS["perl"](path), input=payload, capture_output=True, timeout=30)
            worker.join(timeout=30)
            server.close()
            self.assertEqual(relay.returncode, 0, relay.stderr)
            self.assertEqual(relay.stdout, payload[::-1])


if __name__ == "__main__":
    unittest.main()

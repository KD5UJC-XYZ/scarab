# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the retained-fixture validator of the Dijkstra state-translation oracle
(`scripts/oracle/dijkstra-state-translation/run.py`, #826): the committed fixture passes, and changing any field
that binds the pins, the ledger sources or the accepted oracle build is refused. No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load():
    spec = importlib.util.spec_from_file_location(
        "dijkstra_state_translation_run", ROOT / "scripts/oracle/dijkstra-state-translation/run.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


run = load()
CASE, _ = run.inputs()
PROVENANCE = json.loads(run.PROVENANCE.read_text())
ROWS = run.recorded()
CASES_SHA256 = hashlib.sha256(run.CASES_FILE.read_bytes()).hexdigest()
ZERO = "0" * 64
BINARY = run.IMAGE_BINARY


def problems(provenance, rows=ROWS, cases_sha256=CASES_SHA256):
    return run.validate(provenance, rows, cases_sha256, CASE)


def changed(edit):
    provenance = copy.deepcopy(PROVENANCE)
    edit(provenance)
    return provenance


def setter(*path, value):
    def edit(provenance):
        target = provenance
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = value
    return edit


def remover(*path):
    def edit(provenance):
        target = provenance
        for key in path[:-1]:
            target = target[key]
        del target[path[-1]]
    return edit


def both_binary_digests(provenance):
    provenance["oracle"]["imageFilesSha256"][BINARY] = ZERO
    provenance["oracle"]["oracleBinarySha256"] = ZERO


MUTATIONS = {
    "schema version": setter("schemaVersion", value=2),
    "extra field": setter("baseImageId", value="sha256:" + ZERO),
    "image": setter("image", value="scarab-leios-testnet:local"),
    "image ID syntax": setter("imageId", value="scarab-dijkstra-state-translation-oracle:local"),
    "ledger revision": setter("ledger", "revision", value="f649f975" + "0" * 32),
    "ledger packages": setter("ledger", "packages", value=[]),
    "consensus revision": setter("consensusSourceCommit", value="0" * 40),
    "a ledger source digest": setter("sourceSha256", "Dijkstra/Translation.hs", value=ZERO),
    "a ledger source missing": remover("sourceSha256", "Dijkstra/Translation.hs"),
    "program digest": setter("programSha256", value=ZERO),
    "package digest": setter("packageSha256", value=ZERO),
    "an input digest": setter("inputs", run.STATE, value=ZERO),
    "case count": setter("cases", value=2),
    "case file digest": setter("casesSha256", value=ZERO),
    "measured date": setter("measuredAt", value="yesterday"),
    "network": setter("oracle", "network", value="bridge"),
    "oracle field missing": remover("oracle", "network"),
    "the image's Main.hs": setter("oracle", "imageFilesSha256", run.IMAGE_HARNESS, value=ZERO),
    "the image's package file": setter("oracle", "imageFilesSha256", run.IMAGE_BUILD[0], value=ZERO),
    "the image's cabal project": setter("oracle", "imageFilesSha256", run.IMAGE_BUILD[1], value=ZERO),
    "the image's cabal.project.local": setter("oracle", "imageFilesSha256", run.IMAGE_BUILD[2], value=ZERO),
    "an image file missing": remover("oracle", "imageFilesSha256", run.IMAGE_BUILD[1]),
    "an image digest not a SHA-256": setter("oracle", "imageFilesSha256", BINARY, value="ab"),
    "the executable's digest alone": setter("oracle", "oracleBinarySha256", value=ZERO),
    "a pinned checkout changed": lambda p: p["oracle"]["pinnedSources"].update(
        {next(iter(p["oracle"]["pinnedSources"])): "changed"}),
    "a pinned checkout missing": lambda p: p["oracle"]["pinnedSources"].pop(next(iter(p["oracle"]["pinnedSources"]))),
    "an unpinned checkout": setter("oracle", "pinnedSources", "f" * 40, value="unchanged"),
}


class RetainedFixtureTest(unittest.TestCase):
    def test_the_committed_fixture_is_valid(self):
        self.assertEqual(problems(PROVENANCE), [])

    def test_each_binding_field_changed_is_refused(self):
        for name, edit in MUTATIONS.items():
            with self.subTest(name):
                self.assertNotEqual(problems(changed(edit)), [], name)

    def test_the_executable_digest_changed_consistently_still_validates_offline(self):
        # Offline, a different executable is a build the record accepted; replay refuses it against the image.
        self.assertEqual(problems(changed(both_binary_digests)), [])

    def test_the_rows_are_bound(self):
        self.assertNotEqual(problems(PROVENANCE, rows=ROWS + ROWS), [])
        self.assertNotEqual(problems(PROVENANCE, rows=[dict(ROWS[0], name="other")]), [])
        self.assertNotEqual(problems(PROVENANCE, cases_sha256=ZERO), [])


if __name__ == "__main__":
    unittest.main()

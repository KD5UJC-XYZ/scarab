# SPDX-License-Identifier: Apache-2.0
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import candidate_fixture
import check_test_coverage
import evidence_packet as packet

PIN = "0123456789abcdef" * 4
COMMIT = "c" * 40
# A synthetic host name; the real one never appears in tests.
HOST = "packet-test-host"
GIT = ["git", "-c", "user.name=Packet test", "-c", "user.email=packet-test@example.invalid",
       "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"]
DATES = {"GIT_AUTHOR_DATE": "2026-01-02T03:04:05+00:00", "GIT_COMMITTER_DATE": "2026-01-02T03:04:05+00:00"}


def tree():
    """The smallest tree every packet section can read, with the conformance pins it names."""
    manifest = lambda **v: json.dumps(v) + "\n"
    files = {
        "build.sbt": 'lazy val codec = scarab("codec")\n\nlazy val blstNative = scarab("blstNative", "blst-native")\n'
                     '\nlazy val crypto = scarab("crypto")\n  .dependsOn(codec % "compile->compile;test->test", blstNative)\n',
        "codec/src/main/scala/Codec.scala": "object Codec\n",
        "codec/src/test/scala/CodecSuite.scala": 'val db = sys.env.get("SCARAB_K1_DB")\n',
        "blst-native/src/main/resources/lib.txt": "native\n",
        "crypto/src/main/scala/Crypto.scala": "object Crypto\n",
        "CONTRIBUTING.md": "Run `bash scripts/sbtw check` and `python3 scripts/devnet.py --name <yours> e2e`.\n",
        ".github/workflows/ci.yml": f"steps:\n  - uses: actions/checkout@{'d' * 40} # v7\n"
                                    "  - run: bash scripts/sbtw check node/runtimeClasspathFile\n"
                                    f"  - run: docker run tool/lint:1.0@sha256:{PIN}\n",
        "docker/jdk.Dockerfile": f"FROM eclipse-temurin@sha256:{PIN}\nWORKDIR /work\n",
        "scripts/sbtw": "#!/usr/bin/env bash\nversion=1.10.7\nsha=" + PIN + "\n",
        "scripts/tests/test_ignored.py": f"IMAGE = 'synthetic/image@sha256:{'e' * 64}'\n",
        "scripts/prepare_reference_image.py": f'BASE = "node/ref@sha256:{PIN}"\nARCHIVE_SHA256 = "{PIN}"\n'
                                              'ARCHIVE_NAME = "node.tar.gz"\nARCHIVE_URL = "https://example.invalid/" + ARCHIVE_NAME\n',
        "node/src/test/resources/upstream/conway-govcert/manifest.json": manifest(
            upstream=dict(commit=COMMIT), dependencies={"formal-ledger-specifications": COMMIT,
                                                        "constrained-generators": COMMIT}),
        "node/src/test/resources/upstream/conway-govcert/README.md": "Ledger pins.\n",
        "fixtures/reference/ouroboros-network-protocol-codecs/keepalive.tsv.manifest.json": manifest(sourceCommit=COMMIT),
        "fixtures/reference/ouroboros-network-protocol-codecs/README.md": "Network pins.\n",
        "fixtures/reference/wallet-queries/manifest.json": manifest(
            ledger=dict(commit=COMMIT, sources=["a.hs"]), network=dict(commit=COMMIT, sources=["b.hs"])),
        "fixtures/reference/wallet-queries/README.md": "Wallet pins.\n",
        "fixtures/reference/genesis-density/manifest.json": manifest(sourceCommit=COMMIT),
        "fixtures/reference/genesis-density/README.md": "Consensus pins.\n",
        "docs/plutus-conformance.md": f"IntersectMBO/plutus tag `1.0`, commit `{COMMIT}`\n",
        "scripts/oracle/rewards/README.md": f"The source revision is `{COMMIT}`.\n",
        "docs/compatibility.md": "Recorded evidence.\n",
    }
    return files


class Repo:
    def __init__(self, base: Path):
        self.root = base / "repo"
        self.root.mkdir()
        self.run("init", "--quiet", "-b", "main")
        for name, text in tree().items():
            self.write(name, text)
        inventory = check_test_coverage.fixture_inventory(self.root)
        self.write(check_test_coverage.INVENTORY, json.dumps(inventory, indent=2) + "\n")
        self.write(check_test_coverage.REGISTER, json.dumps(dict(schemaVersion=1, entries=[dict(
            id="CR-1", classification="sampling-limit", status="open", scope="s", assertion="a", rationale="r",
            owner="o", tracking="#1", resolution="z", evidence=["docs/compatibility.md"],
            candidateRequired=False)]), indent=2) + "\n")
        self.commit("initial")

    def run(self, *args):
        return subprocess.run(GIT + list(args), cwd=self.root, check=True, capture_output=True, text=True,
                              env=dict(os.environ, **DATES)).stdout.strip()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit(self, message):
        self.run("add", "-A")
        self.run("commit", "--quiet", "-m", message)
        return self.run("rev-parse", "HEAD")


class PacketTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.repo = Repo(self.base)
        self.host = mock.patch.object(packet.socket, "gethostname", return_value=HOST)
        self.host.start()

    def tearDown(self):
        self.host.stop()
        self.temporary.cleanup()

    def build(self, name, revision="HEAD", **options):
        options.setdefault("allow_incomplete", True)
        return packet.build(self.repo.root, revision, self.base / name, None, options.pop("check", False), **options)

    def members(self, name):
        archive, _, _ = self.build(name)
        with tarfile.open(archive) as tar:
            return {m.name: tar.extractfile(m).read() for m in tar.getmembers()}, tar.getmembers()

    def test_two_runs_give_byte_identical_archives(self):
        first, digest, complete = self.build("one", check=True)
        second, again, _ = self.build("two")
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(digest, again)
        self.assertFalse(complete)
        self.assertIn(digest, (self.base / "one/SHA256SUMS").read_text())

    def test_tar_members_carry_no_clock_owner_or_host(self):
        _, members = self.members("out")
        self.assertEqual([m.name for m in members], sorted(m.name for m in members))
        for member in members:
            self.assertEqual((member.mtime, member.uid, member.gid, member.uname, member.gname, member.mode),
                             (0, 0, 0, "", "", 0o644))

    def test_uncommitted_edits_do_not_change_the_packet(self):
        clean, digest, _ = self.build("clean")
        self.repo.write("codec/src/main/scala/Codec.scala", "object Changed\n")
        self.repo.write("untracked.txt", "local\n")
        _, dirty, _ = self.build("dirty")
        self.assertEqual(digest, dirty)

    def test_sections_describe_the_revision(self):
        contents, _ = self.members("out")
        manifest = json.loads(contents["packet/manifest.json"])
        commit = self.repo.run("rev-parse", "HEAD")
        self.assertEqual(manifest["revision"]["commit"], commit)
        self.assertEqual(manifest["revision"]["committerTime"], 1767323045)
        outcomes = {g["gate"]: g["outcome"] for g in manifest["gates"]}
        self.assertEqual(outcomes, {"coverage-register": "passed", "fixture-inventory": "passed",
                                    "reference-pins": "passed", "release-candidate-sbom": "not-supplied",
                                    "source-map": "passed"})
        listed = {m["path"]: m["sha256"] for m in manifest["members"]}
        self.assertEqual(set(listed), set(contents) - {"packet/manifest.json"})
        sources = json.loads(contents["packet/source-map.json"])
        self.assertEqual([(m["id"], m["directory"], m["dependsOn"], m["mainFiles"], m["testFiles"])
                          for m in sources["modules"]],
                         [("blstNative", "blst-native", [], 1, 0), ("codec", "codec", [], 1, 1),
                          ("crypto", "crypto", ["blstNative", "codec"], 1, 0)])
        commands = json.loads(contents["packet/commands.json"])
        check = next(c for c in commands["commands"] if c["command"] == "bash scripts/sbtw check")
        self.assertEqual(check["definedIn"], [".github/workflows/ci.yml", "CONTRIBUTING.md"])
        candidate = next(c for c in commands["commands"] if c["family"] == "release-candidate")
        self.assertIn("--expected-commit " + commit, candidate["command"])
        images = {(i["reference"], i["digest"]) for i in commands["images"]["images"]}
        self.assertIn(("eclipse-temurin", "sha256:" + PIN), images)
        self.assertIn(("tool/lint:1.0", "sha256:" + PIN), images)
        self.assertNotIn(("synthetic/image", "sha256:" + "e" * 64), images)
        self.assertEqual(commands["images"]["actions"], [dict(action="actions/checkout", commit="d" * 40,
                                                               files=[".github/workflows/ci.yml"])])
        references = json.loads(contents["packet/references.json"])
        self.assertEqual({u["commit"] for u in references["upstream"]}, {COMMIT})
        self.assertEqual(references["referenceNode"]["ARCHIVE_URL"], "https://example.invalid/node.tar.gz")
        inventories = json.loads(contents["packet/inventories.json"])
        self.assertEqual(inventories["optionalData"],
                         [dict(variable="SCARAB_K1_DB", readBy=["codec/src/test/scala/CodecSuite.scala"])])
        self.assertEqual([r["id"] for r in inventories["coverage"]["notCovered"]], ["CR-1"])
        self.assertEqual([r["path"] for r in inventories["recordedEvidence"]["files"]], ["docs/compatibility.md"])
        self.assertEqual(inventories["candidateSbom"], {"status": "not-supplied"})
        committed = (self.repo.root / check_test_coverage.INVENTORY).read_bytes()
        self.assertEqual(inventories["fixtures"], dict(
            committed=check_test_coverage.INVENTORY, sha256=packet.sha256(committed), matchesCommitted=True,
            corpora=json.loads(committed)["corpora"], files=len(json.loads(committed)["files"])))
        self.assertNotIn(committed, contents.values())

    def test_annotated_tag_is_recorded_with_its_object(self):
        self.repo.run("tag", "-a", "v1.2.3-alpha.1", "-m", "candidate")
        contents, _ = self.members_for("v1.2.3-alpha.1")
        tag = json.loads(contents["packet/manifest.json"])["revision"]["tag"]
        self.assertEqual(tag, dict(name="v1.2.3-alpha.1", annotated=True,
                                   object=self.repo.run("rev-parse", "refs/tags/v1.2.3-alpha.1")))

    def members_for(self, revision):
        archive, _, _ = self.build("tagged", revision)
        with tarfile.open(archive) as tar:
            return {m.name: tar.extractfile(m).read() for m in tar.getmembers()}, tar.getmembers()

    def test_stale_fixture_inventory_is_an_incomplete_gate(self):
        self.repo.write("fixtures/reference/genesis-density/extra.bin", "new payload\n")
        self.repo.commit("unrecorded fixture")
        with self.assertRaises(packet.Incomplete):
            self.build("strict", allow_incomplete=False)
        self.assertFalse((self.base / "strict").exists())
        contents, _ = self.members("kept")
        outcomes = {g["gate"]: g["outcome"] for g in json.loads(contents["packet/manifest.json"])["gates"]}
        self.assertEqual(outcomes["fixture-inventory"], "failed")
        summary = json.loads(contents["packet/inventories.json"])["fixtures"]
        self.assertEqual((summary["matchesCommitted"], summary["error"]), (False, "ValueError"))

    def test_local_paths_and_host_name_fail_the_run(self):
        register_path = self.repo.root / check_test_coverage.REGISTER
        register = json.loads(register_path.read_text())
        register["entries"][0]["scope"] = "captured under /ho" + "me/alice/checkout"
        self.repo.write(check_test_coverage.REGISTER, json.dumps(register, indent=2) + "\n")
        self.repo.commit("copied member records a home path")
        with self.assertRaises(packet.ProvenanceLeak):
            self.build("home")
        self.repo.run("reset", "--hard", "--quiet", "HEAD~1")
        self.repo.write(f"docs/{HOST.upper()}.md", "notes\n")
        self.repo.commit("a path names the host")
        with self.assertRaises(packet.ProvenanceLeak):
            self.build("host")
        self.assertFalse((self.base / "host").exists())

    def test_guard_matches_tokens_only_as_whole_words(self):
        packet.guard({"a": b"the packet-test-hosting word"}, [HOST.encode()])
        with self.assertRaises(packet.ProvenanceLeak):
            packet.guard({"a": b"built on packet-test-host."}, [HOST.encode()])

    def test_output_must_be_fresh_and_outside_the_checkout(self):
        with self.assertRaises(packet.UnsafeOutput):
            packet.build(self.repo.root, "HEAD", self.repo.root / "packet", None, False, allow_incomplete=True)
        (self.base / "exists").mkdir()
        with self.assertRaises(packet.UnsafeOutput):
            self.build("exists")

    def test_main_reports_failure_without_a_traceback(self):
        with mock.patch("sys.stderr", new_callable=io.StringIO) as stderr:
            code = packet.main(["--repo", str(self.repo.root), "--revision", "HEAD",
                                "--output", str(self.base / "strict")])
        self.assertEqual(code, 1)
        self.assertIn("release-candidate-sbom", stderr.getvalue())


class CandidateTest(unittest.TestCase):
    def test_validated_candidate_sboms_are_copied_and_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "candidate"
            candidate_fixture.make_candidate(root, release=False)
            outcome, summary, copies = packet.candidate(root, "a" * 40)
            self.assertIs(outcome, packet.Outcome.PASSED)
            self.assertEqual(sorted(copies), ["sbom/candidate.json", "sbom/image.cdx.json", "sbom/jvm.cdx.json"])
            self.assertEqual(copies["sbom/candidate.json"], (root / "candidate.json").read_bytes())
            self.assertEqual(sorted(s["kind"] for s in summary["subjects"]), ["jar", "oci-archive"])
            with self.assertRaises(packet.CandidateMismatch):
                packet.candidate(root, "b" * 40)
            (root / "sbom/jvm.cdx.json").write_text("{}\n")
            with self.assertRaises(packet.CandidateMismatch):
                packet.candidate(root, "a" * 40)

    def test_absent_candidate_is_not_supplied(self):
        self.assertEqual(packet.candidate(None, "a" * 40), (packet.Outcome.NOT_SUPPLIED, {"status": "not-supplied"}, {}))


if __name__ == "__main__":
    unittest.main()

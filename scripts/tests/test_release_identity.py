# SPDX-License-Identifier: Apache-2.0
"""Version authority controls use disposable local repositories, never Scarab release tags."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_identity as version
import reproducible_build as repro


class ReleaseIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / "repo"
        self.repo.mkdir()
        self.git("init", "--quiet")
        self.git("config", "user.name", "Version control")
        self.git("config", "user.email", "version@example.invalid")
        actual = Path(__file__).resolve().parents[2]
        for name in ["project/BuildIdentity.scala", "scripts/release_identity.py", "build.sbt"]:
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((actual / name).read_bytes())
        self.git("add", "project", "scripts", "build.sbt")
        self.commit = self.advance("first")
        self.git("branch", "release/prod")
        self.git("branch", "release/beta")

    def git(self, *args):
        return version.git(self.repo, *args)

    def advance(self, value):
        (self.repo / "source.txt").write_text(value)
        self.git("add", "source.txt")
        self.git("commit", "--quiet", "-m", value)
        return self.git("rev-parse", "HEAD")

    def release(self, tag="v1.2.3", **overrides):
        inputs = dict(tag=tag, channel="beta" if "-beta." in tag else "prod",
                      expected_commit=self.commit, expected_tag_object=self.git("rev-parse", "refs/tags/" + tag),
                      source_ref="refs/heads/release/" + ("beta" if "-beta." in tag else "prod"))
        inputs.update(overrides)
        return version.resolve(self.repo, self.commit, **inputs)

    def test_development_versions_are_deterministic_distinct_and_unreleased(self):
        first = version.resolve(self.repo)
        self.assertEqual(first, version.resolve(self.repo))
        self.assertEqual(first["version"], "0.0.0-dev.g" + self.commit)
        self.assertEqual(first["mode"], "development")
        self.advance("second")
        self.assertNotEqual(first["version"], version.resolve(self.repo)["version"])

    def test_exact_lightweight_and_annotated_tags_derive_selected_versions(self):
        for tag in ["v1.2.3", "v1.2.3-beta.1"]:
            with self.subTest(tag=tag):
                self.git("tag", "-a", tag, "-m", "local control")
                identity = self.release(tag)
                self.assertEqual(identity["version"], tag[1:])
                self.assertEqual(identity["gitTag"], tag)
                self.assertEqual(identity["gitCommit"], self.commit)
        self.assertNotEqual(self.release("v1.2.3")["version"], self.release("v1.2.3-beta.1")["version"])
        self.git("tag", "v2.0.0")
        self.assertEqual(self.release("v2.0.0")["gitTagObject"], self.commit)

    def test_release_rejects_unselected_or_incomplete_authority(self):
        self.git("tag", "v1.2.3")
        for inputs in [dict(channel="prod"), dict(tag="v1.2.3"), dict(expected_version="1.2.3")]:
            with self.subTest(inputs=inputs), self.assertRaises(ValueError):
                version.resolve(self.repo, **inputs)

    def test_dirty_index_worktree_and_untracked_inputs_are_refused(self):
        self.git("tag", "v1.2.3")
        (self.repo / "source.txt").write_text("dirty")
        with self.assertRaisesRegex(ValueError, "clean worktree"):
            self.release()
        self.git("add", "source.txt")
        with self.assertRaisesRegex(ValueError, "clean worktree"):
            self.release()
        self.git("reset", "--hard", "HEAD")
        (self.repo / "new.txt").write_text("untracked")
        with self.assertRaisesRegex(ValueError, "clean worktree"):
            self.release()

    def test_moved_tag_and_mismatched_source_identity_are_refused(self):
        self.git("tag", "-a", "v1.2.3", "-m", "original")
        original = self.release()
        self.git("tag", "-f", "-a", "v1.2.3", "-m", "changed tag object")
        with self.assertRaisesRegex(ValueError, "moved"):
            self.release(expected_tag_object=original["gitTagObject"])
        new_commit = self.advance("new source")
        with self.assertRaisesRegex(ValueError, "moved"):
            self.release(expected_commit=new_commit)
        self.git("tag", "-f", "v1.2.3", new_commit)
        with self.assertRaisesRegex(ValueError, "moved"):
            self.release(expected_tag_object=original["gitTagObject"])

    def test_missing_tag_or_source_fails_closed(self):
        with self.assertRaises(subprocess.CalledProcessError):
            version.resolve(self.repo, tag="v1.2.3", channel="prod", expected_commit=self.commit,
                            expected_tag_object=self.commit, source_ref="refs/heads/release/prod")
        self.git("tag", "v1.2.3")
        self.git("branch", "-D", "release/prod")
        with self.assertRaises(subprocess.CalledProcessError):
            self.release()

    def test_channel_source_and_proposal_cannot_override_tag(self):
        self.git("tag", "v1.2.3-beta.1")
        for override in [dict(channel="prod"), dict(source_ref="refs/heads/main"),
                         dict(expected_version="1.2.3"), dict(expected_commit="short")]:
            with self.subTest(override=override), self.assertRaises(ValueError):
                self.release("v1.2.3-beta.1", **override)

    def test_historic_retry_survives_source_branch_advancement_without_version_drift(self):
        self.git("tag", "v1.2.3")
        before = self.release()
        new_commit = self.advance("later release preparation")
        self.git("branch", "-f", "release/prod", new_commit)
        self.assertEqual(self.release(), before)
        self.git("checkout", "--orphan", "unrelated")
        self.git("commit", "--quiet", "-m", "unrelated history")
        self.git("branch", "-f", "release/prod", "HEAD")
        with self.assertRaisesRegex(ValueError, "not retained"):
            self.release()

    def test_semver_is_exact_and_not_git_describe_or_nearby_beta(self):
        for tag in ["v01.2.3", "v1.2", "1.2.3", "v1.2.3-rc.1", "v1.2.3-beta.01", "v1.2.3+build"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version.resolve(self.repo, tag=tag, channel="prod", expected_commit=self.commit,
                                expected_tag_object=self.commit, source_ref="refs/heads/release/prod")
        self.git("tag", "v1.2.3-beta.1")
        self.advance("after beta")
        self.assertEqual(version.resolve(self.repo)["mode"], "development")
        with self.assertRaisesRegex(ValueError, "moved"):
            version.resolve(self.repo, tag="v1.2.3-beta.1", channel="beta",
                            expected_commit=self.git("rev-parse", "HEAD"), expected_tag_object=self.commit,
                            source_ref="refs/heads/release/beta")

    def test_export_freezes_identity_and_binds_both_copies_before_build(self):
        self.git("tag", "v1.2.3")
        identity = self.release()
        export = self.repo.parent / "export"
        repro.export_source(self.repo, self.commit, export, tag="v1.2.3", channel="prod",
                            expected_commit=self.commit, expected_tag_object=self.commit,
                            source_ref="refs/heads/release/prod")
        manifest = json.loads((export / repro.MANIFEST).read_text())
        self.assertEqual(manifest["buildIdentity"], identity)
        self.assertIn(version.IDENTITY_FILE, manifest["files"])
        for child in ["first", "second"]:
            destination = self.repo.parent / child
            repro.copy_source(export, destination, manifest)
            self.assertEqual((destination / version.IDENTITY_FILE).read_bytes(),
                             (export / version.IDENTITY_FILE).read_bytes())
            self.assertEqual((destination / ".git/HEAD").read_text().strip(), self.commit)
        with (export / version.IDENTITY_FILE).open("a") as stream:
            stream.write("version=9.9.9\n")
        with self.assertRaisesRegex(ValueError, "frozen source changed"):
            repro.copy_source(export, self.repo.parent / "tampered", manifest)

    def test_frozen_metadata_rejects_missing_fields_and_mismatched_claims(self):
        self.git("tag", "v1.2.3")
        identity = self.release()
        for override in [dict(version="9.9.9"), dict(channel="beta"), dict(gitTag="v9.9.9"),
                         dict(sourceRefCommit="f"*40), dict(mode="development"), dict(schemaVersion="2")]:
            with self.subTest(override=override), self.assertRaises(ValueError):
                version.validate(dict(identity, **override))
        del identity["gitTag"]
        with self.assertRaisesRegex(ValueError, "incomplete"):
            version.validate(identity)

    def test_pre_versioning_revision_is_refused_before_build(self):
        self.git("rm", "project/BuildIdentity.scala", "scripts/release_identity.py")
        self.git("commit", "--quiet", "-m", "simulate historical versioning contract")
        with self.assertRaisesRegex(ValueError, "predates the verified version export contract"):
            repro.export_source(self.repo, "HEAD", self.repo.parent / "unsupported")

    def test_committed_export_metadata_cannot_be_an_independent_version_truth(self):
        (self.repo / version.IDENTITY_FILE).write_text("version=9.9.9\n")
        self.git("add", version.IDENTITY_FILE)
        self.git("commit", "--quiet", "-m", "reserved metadata")
        with self.assertRaisesRegex(ValueError, "reserved generated export metadata"):
            repro.export_source(self.repo, "HEAD", self.repo.parent / "reserved")

    def test_shallow_release_history_is_refused(self):
        self.git("tag", "v1.2.3")
        shallow = self.repo.parent / "shallow"
        subprocess.run(["git", "clone", "--quiet", "--depth=1", self.repo.as_uri(), str(shallow)], check=True)
        with self.assertRaisesRegex(ValueError, "complete Git history"):
            version.resolve(shallow, tag="v1.2.3", channel="prod", expected_commit=self.commit,
                            expected_tag_object=self.commit, source_ref="refs/remotes/origin/release/prod")

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Resolve Git version authority before exporting source; never author a VERSION file."""
import argparse
import json
from pathlib import Path
import re
import subprocess


IDENTITY_FILE = ".scarab-build-identity.properties"
NUMBER = r"(?:0|[1-9][0-9]*)"
VERSION = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}(?:-(?P<prerelease>alpha|beta|rc)\.(?P<ordinal>{NUMBER}))?")
COMMIT = re.compile(r"[0-9a-f]{40}")
KEYS = {"schemaVersion", "mode", "version", "gitCommit", "gitTag", "gitTagObject",
        "channel", "sourceRef", "sourceRefCommit"}


def channel_for_version(version: str) -> str:
    """Map an exact, unprefixed release version to its distribution channel."""
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError("release version requires canonical stable, alpha.N, beta.N or rc.N SemVer")
    return {None: "prod", "alpha": "alpha", "beta": "beta", "rc": "preprod"}[match.group("prerelease")]


def git(repo, *args):
    return subprocess.check_output(["git", *args], cwd=repo, text=True, stderr=subprocess.PIPE).strip()


def validate(identity):
    if set(identity) != KEYS or identity["schemaVersion"] != "1":
        raise ValueError("unsupported or incomplete build identity")
    commit = identity["gitCommit"]
    if not COMMIT.fullmatch(commit):
        raise ValueError("build identity requires a full Git commit")
    if identity["mode"] == "development":
        if (identity["version"] != f"0.0.0-dev.g{commit}" or identity["channel"] != "development"
                or any(identity[key] for key in ["gitTag", "gitTagObject", "sourceRef", "sourceRefCommit"])):
            raise ValueError("development identity cannot claim a release")
    elif identity["mode"] == "release":
        version = identity["version"]
        channel = channel_for_version(version)
        if identity["gitTag"] != "v" + version:
            raise ValueError("release version must equal the exact selected SemVer tag")
        if identity["channel"] != channel:
            raise ValueError("release tag and channel disagree")
        if not COMMIT.fullmatch(identity["gitTagObject"]) or identity["sourceRefCommit"] != commit:
            raise ValueError("release tag object and approved source commit are required")
        if identity["sourceRef"] not in {f"refs/heads/release/{channel}",
                                          f"refs/remotes/origin/release/{channel}"}:
            raise ValueError("release source ref does not match its channel")
    else:
        raise ValueError("unknown build identity mode")
    return identity


def resolve(repo, revision="HEAD", *, tag=None, channel=None, expected_commit=None,
            expected_tag_object=None, source_ref=None, expected_version=None):
    commit = git(repo, "rev-parse", "--verify", "--end-of-options", revision + "^{commit}")
    identity = dict.fromkeys(KEYS, "")
    identity.update(schemaVersion="1", mode="development", gitCommit=commit,
                    version=f"0.0.0-dev.g{commit}", channel="development")
    release_inputs = [channel, expected_commit, expected_tag_object, source_ref, expected_version]
    if tag is None:
        if any(value is not None for value in release_inputs):
            raise ValueError("release validation inputs require --release-tag")
        return validate(identity)
    if not all([channel, expected_commit, expected_tag_object, source_ref]):
        raise ValueError("release requires channel, expected commit/tag object and approved source ref")
    if (not tag.startswith("v") or not VERSION.fullmatch(tag[1:])
            or not COMMIT.fullmatch(expected_commit) or not COMMIT.fullmatch(expected_tag_object)):
        raise ValueError("release requires an exact SemVer tag and full expected Git identities")
    if git(repo, "rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("release identity requires complete Git history")
    if git(repo, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("release builds require a clean worktree and index")
    tag_ref = "refs/tags/" + tag
    tag_object = git(repo, "rev-parse", "--verify", "--end-of-options", tag_ref)
    tagged_commit = git(repo, "rev-parse", "--verify", "--end-of-options", tag_ref + "^{commit}")
    if commit != expected_commit or tagged_commit != commit or tag_object != expected_tag_object:
        raise ValueError("selected release tag moved or does not match the expected source/tag identity")
    identity.update(mode="release", version=tag[1:], gitTag=tag, gitTagObject=tag_object,
                    channel=channel, sourceRef=source_ref, sourceRefCommit=expected_commit)
    validate(identity)
    if expected_version is not None and expected_version != identity["version"]:
        raise ValueError("proposed version does not match the selected release tag")
    # Record the approved cut, not a moving branch tip. A later immutable retry may retain it
    # as an ancestor after further approved releases have advanced the distribution branch.
    git(repo, "rev-parse", "--verify", "--end-of-options", source_ref + "^{commit}")
    ancestry = subprocess.run(["git", "merge-base", "--is-ancestor", commit, source_ref], cwd=repo)
    if ancestry.returncode:
        raise ValueError("selected source is not retained by the approved release source ref")
    return identity


def write(identity, target):
    validate(identity)
    target.write_text("".join(f"{key}={identity[key]}\n" for key in sorted(identity)), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--revision", default="HEAD")
    parser.add_argument("--release-tag")
    parser.add_argument("--channel", choices=["alpha", "beta", "preprod", "prod"])
    parser.add_argument("--expected-commit")
    parser.add_argument("--expected-tag-object")
    parser.add_argument("--source-ref")
    parser.add_argument("--expected-version")
    args = parser.parse_args()
    print(json.dumps(resolve(args.repo, args.revision, tag=args.release_tag, channel=args.channel,
                             expected_commit=args.expected_commit, expected_tag_object=args.expected_tag_object,
                             source_ref=args.source_ref, expected_version=args.expected_version), sort_keys=True))


if __name__ == "__main__":
    main()

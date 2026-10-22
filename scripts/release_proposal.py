#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read a reviewed proposal from one exact Git cut; this does not authorize publication."""
import argparse
import json
from pathlib import Path

import release_fragments as fragments
from publication_common import require
from release_identity import channel_for_version


PROPOSAL = ".release/proposal.json"
MANIFEST = ".release-please-manifest.json"
AUTHORITY = "release-please-authority.json"
PROPOSAL_BRANCH_PREFIX = "release-please--branches--"
PROPOSAL_PATHS = (PROPOSAL, ".release/proposals/")


def require_authority(repo, source, receipt):
    """The receipt's baseline authority must be the reviewed authority file at its own source commit."""
    require(isinstance(receipt.get("authority"), dict), "proposal receipt has no baseline authority")
    try:
        reviewed = json.loads(fragments.blob(repo, source, AUTHORITY), object_pairs_hook=fragments.unique_object)
    except fragments.ReleaseError:
        raise fragments.ReleaseError("reviewed authority file is missing at the proposal source")
    require(receipt["authority"] == reviewed, "proposal receipt authority differs from the reviewed authority file")


def check_branch(repo, branch, base, cut, same_repo):
    """Refuse proposal state on any branch that is not a same-repository proposal branch.

    A proposal branch gets the full verification; any other branch must leave the proposal receipts alone.
    Returns True when the branch is a proposal branch that was verified.
    """
    fragments.commit(repo, base)
    fragments.commit(repo, cut)
    if branch.startswith(PROPOSAL_BRANCH_PREFIX):
        require(same_repo, "proposal branch must be in this repository")
        load_proposal(repo, cut)
        return True
    origin = fragments.git(repo, "merge-base", base, cut).decode().strip()
    changed = fragments.git(repo, "diff", "--name-only", origin, cut).decode().splitlines()
    touched = [path for path in changed if path == PROPOSAL_PATHS[0] or path.startswith(PROPOSAL_PATHS[1])]
    require(not touched, "proposal receipts changed on a branch not named " + PROPOSAL_BRANCH_PREFIX + "*: " + ", ".join(touched))
    return False


def load_proposal(repo, cut):
    fragments.commit(repo, cut)
    data = fragments.blob(repo, cut, PROPOSAL)
    require(len(data) <= 32 * 1024 * 1024, "oversized proposal receipt")
    receipt = json.loads(data, object_pairs_hook=fragments.unique_object)
    require(receipt.get("schemaVersion") == 1 and receipt.get("state") == "proposed"
            and receipt.get("repository") == "KD5UJC-XYZ/scarab" and receipt.get("tool") == "release-please-17.11.2",
            "unrecognized proposal receipt")
    fragments.tree(repo, cut, ".release")
    source = fragments.commit(repo, receipt["source"])
    fragments.ancestor(repo, source, cut)
    require_authority(repo, source, receipt)
    require(fragments.blob(repo, cut, AUTHORITY) == fragments.blob(repo, source, AUTHORITY),
            "authority file changed between the proposal source and the cut")
    version = receipt["version"]
    channel = channel_for_version(version)
    require(channel == receipt["channel"], "proposal version/channel mismatch")
    require(fragments.blob(repo, cut, f".release/proposals/{version}.json") == data, "versioned proposal receipt differs")
    require(json.loads(fragments.blob(repo, cut, MANIFEST)) == {".": version}, "proposal planning manifest differs")
    plan, notes, changelog = fragments.build(repo, source, receipt["authority"]["source"], version,
                                           receipt["date"], receipt["fragmentPlan"]["selected"])
    require(plan == receipt["fragmentPlan"] and fragments.digest(fragments.json_bytes(plan)) == receipt["fragmentPlanSha256"],
            "proposal fragment plan differs from frozen original inputs")
    require(fragments.digest(notes) == receipt["notesSha256"], "proposal notes hash differs")
    notes_path = f".release/changelog/{version}.md" if channel == "prod" else f".release/notes/{version}.md"
    require(receipt["notesPath"] == notes_path and fragments.blob(repo, cut, notes_path) == notes, "same-cut notes differ")
    require(fragments.digest(fragments.blob(repo, cut, "release-please-config.json")) == receipt["configSha256"],
            "proposal control-plane config differs at the selected cut")
    expected = {notes_path: notes, MANIFEST: fragments.json_bytes({".": version})}
    if channel == "prod":
        expected["CHANGELOG.md"] = changelog
        expected.update((item["path"], None) for item in plan["inputs"])
        expected[f".release/changelog/{version}.json"] = fragments.json_bytes(dict(
            plan, state="pending-publication", planSha256=fragments.digest(fragments.json_bytes(plan))))
    hashes = {path: fragments.identity(content) for path, content in expected.items()}
    require(receipt["generatedFiles"] == hashes, "generated-file selection differs")
    for path, content in expected.items():
        exists = fragments.git(repo, "ls-tree", cut, "--", path)
        actual = fragments.blob(repo, cut, path) if exists else None
        require(actual == content, "same-cut generated bytes differ: " + path)
    changed = set(fragments.git(repo, "diff", "--name-only", source, cut).decode().splitlines())
    require(changed == set(expected) | {PROPOSAL, f".release/proposals/{version}.json"},
            "selected cut contains changes outside the reviewed proposal; regenerate final coverage")
    return {"receipt": receipt, "notes": notes, "fragmentPlan": plan}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--cut", required=True)
    parser.add_argument("--branch", help="pull request head branch; with --base, applies the branch rule")
    parser.add_argument("--base", help="pull request base commit")
    parser.add_argument("--same-repo", action="store_true", help="the pull request head is in this repository")
    args = parser.parse_args()
    if args.branch is not None or args.base is not None:
        require(args.branch is not None and args.base is not None, "--branch and --base go together")
        verified = check_branch(args.repo, args.branch, args.base, args.cut, args.same_repo)
        print(json.dumps({"branch": args.branch, "proposalVerified": verified}))
        return
    result = load_proposal(args.repo, args.cut)
    print(json.dumps({"version": result["receipt"]["version"], "channel": result["receipt"]["channel"],
                      "source": result["receipt"]["source"], "notesSha256": result["receipt"]["notesSha256"]}))


if __name__ == "__main__":
    main()

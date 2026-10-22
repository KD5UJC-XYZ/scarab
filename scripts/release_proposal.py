#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read a reviewed proposal from one exact Git cut; this does not authorize publication."""
import argparse
import json
from pathlib import Path

import release_fragments as fragments
from pr_policy import is_promotion
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


def touched_receipts(repo, base, cut):
    origin = fragments.git(repo, "merge-base", base, cut).decode().strip()
    changed = fragments.git(repo, "diff", "--name-only", origin, cut).decode().splitlines()
    return [path for path in changed if path == PROPOSAL_PATHS[0] or path.startswith(PROPOSAL_PATHS[1])]


def verify_promoted_receipts(repo, cut, touched):
    """Receipts carried by a promotion must be exactly those of a proposal that was verified when it merged.

    The merged proposal is the last first-parent commit that changed the receipt; its own cut gets the full
    verification, and the promoted head must carry the same two receipt files byte for byte.
    """
    receipt = json.loads(fragments.blob(repo, cut, PROPOSAL), object_pairs_hook=fragments.unique_object)
    require(isinstance(receipt, dict) and isinstance(receipt.get("version"), str), "unrecognized promoted receipt")
    channel_for_version(receipt["version"])
    owned = (PROPOSAL, f".release/proposals/{receipt['version']}.json")
    extra = sorted(set(touched) - set(owned))
    require(not extra, "promotion carries receipts other than the one merged proposal: " + ", ".join(extra))
    merged = fragments.git(repo, "log", "--first-parent", "-n1", "--format=%H", cut, "--", PROPOSAL).decode().strip()
    require(merged, "promoted receipts have no merged proposal")
    load_proposal(repo, merged)
    for path in owned:
        require(fragments.blob(repo, cut, path) == fragments.blob(repo, merged, path),
                "promoted receipt differs from the verified merged proposal: " + path)


def check_branch(repo, branch, base, cut, same_repo, target=None):
    """Refuse proposal state except on a verified proposal branch or an authenticated promotion.

    A proposal branch gets the full verification. A same-repository head that is the reviewed promotion source
    of the target (`pr_policy.PROMOTION_SOURCES`) may carry receipts only when they verify as a proposal merged
    earlier. Any other branch must leave the proposal receipts alone.
    Returns True when a proposal was verified, whether on a proposal branch or carried by a promotion.
    """
    fragments.commit(repo, base)
    fragments.commit(repo, cut)
    if branch.startswith(PROPOSAL_BRANCH_PREFIX):
        require(same_repo, "proposal branch must be in this repository")
        load_proposal(repo, cut)
        return True
    touched = touched_receipts(repo, base, cut)
    if touched and target is not None and is_promotion(target, branch, same_repo):
        verify_promoted_receipts(repo, cut, touched)
        return True
    require(not touched, "proposal receipts changed on a branch that is neither named " + PROPOSAL_BRANCH_PREFIX
            + "* nor a reviewed promotion: " + ", ".join(touched))
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
    parser.add_argument("--target", help="pull request target branch; identifies a reviewed promotion with --same-repo")
    parser.add_argument("--same-repo", action="store_true", help="the pull request head is in this repository")
    args = parser.parse_args()
    if args.branch is not None or args.base is not None:
        require(args.branch is not None and args.base is not None, "--branch and --base go together")
        verified = check_branch(args.repo, args.branch, args.base, args.cut, args.same_repo, args.target)
        print(json.dumps({"branch": args.branch, "proposalVerified": verified}))
        return
    result = load_proposal(args.repo, args.cut)
    print(json.dumps({"version": result["receipt"]["version"], "channel": result["receipt"]["channel"],
                      "source": result["receipt"]["source"], "notesSha256": result["receipt"]["notesSha256"]}))


if __name__ == "__main__":
    main()

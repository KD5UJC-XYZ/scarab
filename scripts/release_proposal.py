#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read a reviewed proposal from one exact Git cut; this does not authorize publication."""
import argparse
import json
from pathlib import Path
import re
import subprocess

import release_fragments as fragments
from publication_common import require
from release_identity import channel_for_version


PROPOSAL = ".release/proposal.json"
MANIFEST = ".release-please-manifest.json"
AUTHORITY = "release-please-authority.json"
PROPOSAL_BRANCH_PREFIX = "release-please--branches--"
PROPOSAL_PATHS = (PROPOSAL, ".release/proposals/")
RECEIPT_NAME = re.compile(r"[0-9A-Za-z.+-]+\.json")
RECEIPT_KEYS = {"schemaVersion", "state", "tool", "target", "repository", "source", "authority", "version", "channel",
                "date", "maintenance", "nextVersion", "overrideReason", "configSha256", "commits", "fragmentPlan",
                "fragmentPlanSha256", "notesPath", "notesSha256", "generatedFiles", "pullRequestTitle",
                "pullRequestBodySha256"}
SHA256 = re.compile(r"[0-9a-f]{64}")


def require_authority(repo, source, receipt):
    """The receipt's baseline authority must be the reviewed authority file at its own source commit."""
    require(isinstance(receipt.get("authority"), dict), "proposal receipt has no baseline authority")
    try:
        reviewed = json.loads(fragments.blob(repo, source, AUTHORITY), object_pairs_hook=fragments.unique_object)
    except fragments.ReleaseError:
        raise fragments.ReleaseError("reviewed authority file is missing at the proposal source")
    require(receipt["authority"] == reviewed, "proposal receipt authority differs from the reviewed authority file")


def touched_receipts(repo, base, cut):
    """Return the merge base and the proposal receipt paths that the cut changes relative to it."""
    origin = fragments.git(repo, "merge-base", base, cut).decode().strip()
    changed = fragments.git(repo, "diff", "--name-only", origin, cut).decode().splitlines()
    return origin, [path for path in changed if path == PROPOSAL or path.startswith(PROPOSAL_PATHS[1])]


def exists(repo, commit, path):
    return bool(fragments.git(repo, "ls-tree", commit, "--", path))


def is_ancestor(repo, older, newer):
    return subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", older, newer]).returncode == 0


def verify_carried_receipts(repo, origin, cut, touched):
    """Content-validate receipts that a same-repository head carries from earlier, merged proposals.

    Each new versioned receipt must be byte-identical to the file at a non-merge commit that added it and whose
    own cut passes the full proposal verification. Those commits are found by the file's addition rather than by
    first-parent history, so merge commits and several proposals in one range work. A versioned receipt that the
    merge base already has may not change, and `.release/proposal.json` must equal the latest versioned receipt.
    """
    require(exists(repo, cut, PROPOSAL), "carried receipts have no " + PROPOSAL)
    current = fragments.blob(repo, cut, PROPOSAL)
    latest = json.loads(current, object_pairs_hook=fragments.unique_object)
    require(isinstance(latest, dict) and isinstance(latest.get("version"), str), "unrecognized carried receipt")
    channel_for_version(latest["version"])
    latest_path = f".release/proposals/{latest['version']}.json"
    require(exists(repo, cut, latest_path) and fragments.blob(repo, cut, latest_path) == current,
            "proposal.json differs from its versioned receipt")
    sources = []
    for path in touched:
        if path == PROPOSAL:
            continue
        name = path[len(PROPOSAL_PATHS[1]):]
        require(RECEIPT_NAME.fullmatch(name), "unexpected receipt path: " + path)
        channel_for_version(name[:-len(".json")])
        require(exists(repo, cut, path), "carried receipt was deleted: " + path)
        require(not exists(repo, origin, path), "an already-merged versioned receipt may not change: " + path)
        data = fragments.blob(repo, cut, path)
        adders = fragments.git(repo, "log", "--full-history", "--no-merges", "--diff-filter=A", "--format=%H",
                               f"{origin}..{cut}", "--", path).decode().split()
        failures = []
        for adder in adders:
            if fragments.blob(repo, adder, path) != data:
                continue
            try:
                sources.append(load_proposal(repo, adder)["receipt"]["source"])
                break
            except ValueError as error:
                failures.append(str(error))
        else:
            raise fragments.ReleaseError("carried receipt matches no verified merged proposal: " + path
                                         + "".join("; " + failure for failure in failures))
    require(all(source == latest["source"] or not is_ancestor(repo, latest["source"], source) for source in sources),
            "proposal.json is not the latest carried receipt")


def check_branch(repo, branch, base, cut, same_repo):
    """Refuse proposal state except on a verified proposal branch or when carried receipts verify by content.

    A proposal branch gets the full verification. Any other same-repository head (a `dev` to `main` promotion or
    a `main` to release branch propagation among them) may carry receipts only when each new versioned receipt is
    byte-identical to one added by a commit whose cut passes the full verification; the branch name decides
    nothing. The verifier cannot show that such a commit was reviewed and merged through a pull request: that
    needs branch protection (#161), and a same-repository writer can still commit a self-consistent proposal.
    Returns True when a proposal was verified, whether on a proposal branch or carried by another head.
    """
    fragments.commit(repo, base)
    fragments.commit(repo, cut)
    if branch.startswith(PROPOSAL_BRANCH_PREFIX):
        require(same_repo, "proposal branch must be in this repository")
        load_proposal(repo, cut)
        return True
    origin, touched = touched_receipts(repo, base, cut)
    if not touched:
        return False
    require(same_repo, "proposal receipts changed on a fork branch: " + ", ".join(touched))
    verify_carried_receipts(repo, origin, cut, touched)
    return True


def require_receipt_fields(repo, receipt):
    """Check every receipt field that is not already compared with recomputed bytes.

    The commit list is recomputed from Git. The coordinator's inputs (maintenance, version override) are checked
    for shape and consistency. Not recomputable from the tree: `target` (the coordinator derives `main` or `dev`
    from the promotion policy variable at generation time) and the release-please pull-request title and body hash.
    They name no file content and no input of the generated bytes, so a shape check is the most that can be asserted.
    """
    require(set(receipt) == RECEIPT_KEYS, "proposal receipt fields differ from the coordinator's receipt")
    require(receipt["target"] in ("main", "dev"), "proposal receipt target is not a proposal integration branch")
    require(type(receipt["maintenance"]) is bool, "proposal receipt maintenance flag is not a boolean")
    override, reason = receipt["nextVersion"], receipt["overrideReason"]
    require((override is None and reason is None) or (override == receipt["version"] and isinstance(reason, str) and reason.strip()),
            "proposal receipt version override is inconsistent")
    require(isinstance(receipt["pullRequestTitle"], str) and isinstance(receipt["pullRequestBodySha256"], str)
            and SHA256.fullmatch(receipt["pullRequestBodySha256"]), "proposal receipt pull-request identity malformed")
    history = fragments.git(repo, "rev-list", "--first-parent", f"{receipt['authority']['source']}..{receipt['source']}")
    expected = [{"sha": sha, "messageSha256": fragments.digest(
        fragments.git(repo, "show", "-s", "--format=%B", sha).decode().rstrip().encode())} for sha in history.decode().split()]
    require(receipt["commits"] == expected, "proposal receipt commit list differs from the source history")


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
    require_receipt_fields(repo, receipt)
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

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Merge a reviewed same-repository dev PR through the cutover's restricted App."""
import argparse
import json
import os
from pathlib import Path
from pr_policy import COMMIT, REPOSITORY, evaluate, require
from promotion_rules import verify_rulesets
from publication_common import GitHub


def promote(api, *, repository, repository_id, number, expected_head, expected_base,
            promotion_app_id, release_app_id, mode, invocation_ref, invocation_sha, ruleset_audit=None):
    require(mode == "dev-only-main", "promotion cutover is not active")
    require(invocation_ref == "refs/heads/main", "promotion controller must run from trusted main")
    require(REPOSITORY.fullmatch(repository), "invalid repository identity")
    require(type(number) is int and number > 0, "require pull-request number")
    require(COMMIT.fullmatch(expected_head) and COMMIT.fullmatch(expected_base), "require full approved commits")
    require(invocation_sha == expected_base, "controller source must equal the approved main commit")
    prefix = "/repos/" + repository
    repo = api.request("GET", prefix)
    require(repo.get("id") == repository_id and repo.get("default_branch") == "main",
            "unexpected repository identity or default branch")
    listed = api.request("GET", prefix + "/rulesets?includes_parents=true&per_page=100")
    require(len(listed) < 100, "ruleset list requires explicit pagination review")
    snapshots = [api.request("GET", prefix + "/rulesets/" + str(item["id"])) for item in listed]
    verify_rulesets(snapshots, promotion_app_id, release_app_id, audit=ruleset_audit,
                    actor_role="promotion", repository=repository)
    pr = api.request("GET", prefix + "/pulls/" + str(number))
    result = evaluate(pr, repository, repository_id, mode)
    require(result["base"] == "main", "promotion controller only merges PRs targeting main")
    require(pr.get("state") == "open" and pr.get("draft") is False, "PR must be open and ready")
    require(result["headSha"] == expected_head and pr["base"].get("sha") == expected_base,
            "PR differs from the explicitly approved head/base commits")
    require(pr.get("mergeable") is True and pr.get("mergeable_state") == "clean",
            "GitHub has not confirmed clean, current required checks and reviews")
    current = api.request("GET", prefix + "/git/ref/heads/main")
    require(current.get("object", {}).get("sha") == expected_base, "main advanced after approval")
    merged = api.request("PUT", prefix + "/pulls/" + str(number) + "/merge",
                         {"sha": expected_head, "merge_method": "merge"})
    require(merged.get("merged") is True, "GitHub refused the reviewed PR merge")
    return dict(result, baseSha=expected_base, mergeSha=merged["sha"], merged=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pull-request", type=int, required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--expected-base", required=True)
    parser.add_argument("--promotion-app-id", type=int, required=True)
    parser.add_argument("--release-app-id", type=int, required=True)
    parser.add_argument("--ruleset-audit", type=Path,
                        help="exact preflight artifact approved in the protected environment")
    args = parser.parse_args()
    policy = json.loads((Path(__file__).resolve().parents[1] / "release/promotion-policy.json").read_text())
    require(bool(os.environ.get("GH_TOKEN")), "require a scoped promotion App installation token")
    print(json.dumps(promote(GitHub(os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"]),
                            repository=os.environ["GITHUB_REPOSITORY"],
                            repository_id=int(os.environ["GITHUB_REPOSITORY_ID"]),
                            number=args.pull_request, expected_head=args.expected_head,
                            expected_base=args.expected_base, promotion_app_id=args.promotion_app_id,
                            release_app_id=args.release_app_id,
                            mode=os.environ.get("SCARAB_PROMOTION_POLICY"),
                            invocation_ref=os.environ.get("GITHUB_REF"),
                            invocation_sha=os.environ.get("GITHUB_SHA"),
                            ruleset_audit=(json.loads(args.ruleset_audit.read_text()) if args.ruleset_audit
                                           else policy.get("rulesetAudit"))), sort_keys=True))


if __name__ == "__main__":
    main()

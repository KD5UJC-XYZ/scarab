#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render reviewed cutover rulesets; this command never installs repository settings."""
import argparse
import json

from pr_policy import require


BRANCHES = ["main", "dev", "release/alpha", "release/beta", "release/preprod", "release/prod"]
CHECKS = ["PR title and promotion source", "scalafmt, compile and test",
          "scripts (compile, unit tests, help)", "workflow lint", "secret scan (gitleaks, full history)",
          "dependency review", "crypto guard (variable-time math stays in the vrf package)",
          "semgrep (crypto rules, WARNING-gated)", "portable Linux amd64 native byte comparison",
          "decoder fuzz gate", "CDDL comparison gate"]
ACTIONS_APP_ID = 15368


def rulesets(promotion_app_id, release_app_id):
    require(type(promotion_app_id) is int and promotion_app_id > 0, "require promotion App ID")
    require(type(release_app_id) is int and release_app_id > 0, "require release App ID")
    require(len({promotion_app_id, release_app_id, ACTIONS_APP_ID}) == 3,
            "promotion, release and GitHub Actions identities must differ")

    def policy(name, target, refs, rules, bypass=()):
        return {"name": name, "target": target, "enforcement": "active", "bypass_actors": list(bypass),
                "conditions": {"ref_name": {"include": refs, "exclude": []}}, "rules": rules}

    return [
        policy("Scarab reviewed branches", "branch", ["refs/heads/" + name for name in BRANCHES], [
            {"type": "deletion"}, {"type": "non_fast_forward"},
            {"type": "pull_request", "parameters": {
                "required_approving_review_count": 1, "dismiss_stale_reviews_on_push": True,
                "require_code_owner_review": False, "require_last_push_approval": True,
                "required_review_thread_resolution": True}},
            {"type": "required_status_checks", "parameters": {
                "strict_required_status_checks_policy": True,
                "do_not_enforce_on_create": False,
                "required_status_checks": [{"context": name, "integration_id": ACTIONS_APP_ID}
                                           for name in CHECKS]}}]),
        policy("Scarab main promotion", "branch", ["refs/heads/main"], [{"type": "update"}], [
            {"actor_id": promotion_app_id, "actor_type": "Integration", "bypass_mode": "pull_request"}]),
        policy("Scarab authorized release tags", "tag", ["refs/tags/v*"], [{"type": "creation"}], [
            {"actor_id": release_app_id, "actor_type": "Integration", "bypass_mode": "always"}]),
        policy("Scarab immutable release tags", "tag", ["refs/tags/v*"],
               [{"type": "update"}, {"type": "deletion"}]),
    ]


def contains(actual, expected):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(key in actual and contains(actual[key], value)
                                                for key, value in expected.items())
    if isinstance(expected, list):
        return (isinstance(actual, list) and len(actual) == len(expected)
                and all(any(contains(item, value) for item in actual) for value in expected))
    return type(actual) is type(expected) and actual == expected


def verify_rulesets(actual, promotion_app_id, release_app_id):
    for expected in rulesets(promotion_app_id, release_app_id):
        matches = [item for item in actual if item.get("name") == expected["name"]]
        require(len(matches) == 1 and contains(matches[0], expected),
                "missing or changed active ruleset: " + expected["name"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--promotion-app-id", type=int, required=True)
    parser.add_argument("--release-app-id", type=int, required=True)
    args = parser.parse_args()
    print(json.dumps(rulesets(args.promotion_app_id, args.release_app_id), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()

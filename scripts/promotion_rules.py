#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render reviewed cutover rulesets; this command never installs repository settings."""
import argparse
import json
import re
from pathlib import Path

from pr_policy import require


BRANCHES = ["main", "dev", "release/alpha", "release/beta", "release/preprod", "release/prod"]
CHECKS = ["PR title and promotion source", "release proposal controls", "scalafmt, compile and test",
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


def owner_audit(actual, promotion_app_id, release_app_id, repository):
    """Capture visible owner-reviewed settings, without granting runtime Apps administration."""
    verify_rulesets(actual, promotion_app_id, release_app_id)
    snapshots = []
    for expected in rulesets(promotion_app_id, release_app_id):
        item = next(item for item in actual if item.get("name") == expected["name"])
        require(type(item.get("id")) is int and item["id"] > 0,
                "owner audit requires server ruleset IDs")
        require(isinstance(item.get("updated_at"), str) and
                re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", item["updated_at"]),
                "owner audit requires server ruleset update timestamps")
        require(item.get("source_type") == "Repository" and item.get("source") == repository,
                "owner audit requires this repository's rulesets")
        snapshots.append(dict(expected, id=item["id"], updated_at=item["updated_at"],
                              source_type="Repository", source=repository))
    return dict(schemaVersion=1, repository=repository, rulesets=snapshots)


def verify_rulesets(actual, promotion_app_id, release_app_id, *, audit=None, actor_role=None,
                    repository=None):
    expected_rules = rulesets(promotion_app_id, release_app_id)
    if audit is not None:
        require(isinstance(audit, dict) and audit.get("schemaVersion") == 1 and
                audit.get("repository") == repository and isinstance(repository, str),
                "ruleset audit must belong to the approved repository")
        require(owner_audit(audit.get("rulesets", []), promotion_app_id, release_app_id, repository) == audit,
                "ruleset audit differs from reviewed policy")
    actor_modes = {"promotion": ["never", "pull_requests_only", "never", "never"],
                   "release": ["never", "never", "always", "never"]}
    require(actor_role is None or actor_role in actor_modes, "unknown ruleset actor role")
    for index, expected in enumerate(expected_rules):
        matches = [item for item in actual if item.get("name") == expected["name"]]
        require(len(matches) == 1, "missing or duplicated active ruleset: " + expected["name"])
        observed = matches[0]
        visible_expected = dict(expected)
        if "bypass_actors" not in observed:
            require(audit is not None and actor_role is not None,
                    "redacted ruleset bypass actors require an owner-reviewed audit and App role")
            del visible_expected["bypass_actors"]
        require(contains(observed, visible_expected),
                "missing or changed active ruleset: " + expected["name"])
        if audit is not None:
            reviewed = audit["rulesets"][index]
            require(all(observed.get(key) == reviewed[key] for key in
                        ["id", "updated_at", "source_type", "source"]),
                    "ruleset changed since owner audit: " + expected["name"])
        if actor_role is not None:
            require(observed.get("current_user_can_bypass") == actor_modes[actor_role][index],
                    "unexpected live App bypass authority: " + expected["name"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--promotion-app-id", type=int, required=True)
    parser.add_argument("--release-app-id", type=int, required=True)
    parser.add_argument("--snapshot", type=Path, help="saved owner GET responses; render reviewed audit only")
    parser.add_argument("--repository", help="repository identity required with --snapshot")
    args = parser.parse_args()
    result = (owner_audit(json.loads(args.snapshot.read_text()), args.promotion_app_id,
                         args.release_app_id, args.repository) if args.snapshot else
              rulesets(args.promotion_app_id, args.release_app_id))
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""GET-only preflight: never create an environment merely by referring to its name."""
import argparse
import os
import re
import urllib.parse
from pathlib import Path

from publication_common import GitHub, canonical, digest, load_json, require


CHANNELS = {"alpha", "beta", "preprod", "prod"}


def environment_snapshot(github, channel, policy):
    require(channel in CHANNELS, "publication channel mismatch")
    require(set(policy.get("environments", {})) == CHANNELS, "all four environment policies must be explicit")
    expected = policy["environments"][channel]
    require(expected.get("name") == "release-" + channel, "unexpected protected environment name")
    return inspect_environment(github, expected, policy)


def named_environment_snapshot(github, name, expected_ref, policy):
    """Shared App-credential preflight for preparation/promotion; no channel authority implied."""
    require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_.-]+", name), "invalid protected environment name")
    expected = policy.get("automationEnvironments", {}).get(name)
    require(isinstance(expected, dict) and expected.get("name") == name, "automation environment absent from reviewed policy")
    require(expected_ref == "refs/heads/main", "preparation and promotion credentials require the trusted main ref")
    require(expected.get("branches") == [{"type": "branch", "name": "main"}],
            "automation App credentials must be restricted to main only")
    return inspect_environment(github, expected, policy)


def inspect_environment(github, expected, policy):
    require(policy.get("schemaVersion") == 1 and policy.get("enabled") is True,
            "publication policy has not been explicitly enabled in reviewed source")
    require(policy.get("repository") == github.repository, "publication repository mismatch")
    name = urllib.parse.quote(expected["name"], safe="")
    environment = github.get("/environments/" + name)
    require(environment.get("name") == expected["name"] and type(environment.get("id")) is int
            and environment["id"] > 0,
            "existing environment identity required")
    # GitHub returns this field even though its REST OpenAPI environment model omits it.
    require(environment.get("can_admins_bypass") is False, "admin bypass must be observably disabled")
    rules = environment.get("protection_rules", [])
    reviewers = [rule for rule in rules if rule.get("type") == "required_reviewers"]
    require(len(reviewers) == 1 and reviewers[0].get("prevent_self_review") is True,
            "required independent environment review is absent")
    actual_reviewers = sorted((item["type"], item["reviewer"]["id"]) for item in reviewers[0].get("reviewers", []))
    wanted_reviewers = sorted((item["type"], item["id"]) for item in expected.get("reviewers", []))
    require(wanted_reviewers and actual_reviewers == wanted_reviewers
            and all(kind in {"User", "Team"} and type(identity) is int and identity > 0
                    for kind, identity in wanted_reviewers), "environment reviewers differ from reviewed policy")
    require(environment.get("deployment_branch_policy") == {"protected_branches": False, "custom_branch_policies": True},
            "explicit deployment branch/tag restrictions required")
    branch_rules = github.pages("/environments/" + name + "/deployment-branch-policies", "branch_policies")
    actual_branches = sorted((rule.get("type"), rule.get("name")) for rule in branch_rules)
    wanted_branches = sorted((rule["type"], rule["name"]) for rule in expected.get("branches", []))
    require(wanted_branches and actual_branches == wanted_branches, "environment branch/tag rules differ")
    snapshot = {"repository": github.repository, "environment": environment["name"], "environmentId": environment["id"],
                "reviewers": actual_reviewers, "branches": actual_branches, "preventSelfReview": True,
                "canAdminsBypass": False, "policySha256": digest(canonical(policy))}
    return dict(snapshot, snapshotSha256=digest(canonical(snapshot)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    purpose = parser.add_mutually_exclusive_group(required=True)
    purpose.add_argument("--channel", choices=sorted(CHANNELS))
    purpose.add_argument("--environment")
    parser.add_argument("--expected-ref")
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    github = GitHub(args.repository, os.environ.get("GH_TOKEN"))
    policy = load_json(args.policy)
    if args.environment:
        require(not os.environ.get("GITHUB_REF") or os.environ["GITHUB_REF"] == args.expected_ref,
                "workflow invocation ref differs from the approved automation ref")
        snapshot = named_environment_snapshot(github, args.environment, args.expected_ref, policy)
    else:
        snapshot = environment_snapshot(github, args.channel, policy)
    if args.previous:
        require(snapshot == load_json(args.previous), "environment policy changed after approval/preflight")
    args.output.write_bytes(canonical(snapshot) + b"\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            stream.write("environment=" + snapshot["environment"] + "\n")
            stream.write("snapshot=" + snapshot["snapshotSha256"] + "\n")


if __name__ == "__main__":
    main()

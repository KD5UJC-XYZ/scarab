#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate pull-request metadata without loading pull-request code."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re


MODES = ("transition", "dev-only-main")
TITLE = re.compile(r"(?:build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test)(?:\([^\s():!]+\))?!?: \S[^\r\n]*")
COMMIT = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
# Source branches of the reviewed promotion path in docs/design/releases.md ("Branches and protected promotion"):
# dev -> main, then the selected source into the channel branches. Only `main` is enforced here (dev-only-main).
PROMOTION_SOURCES = {"main": ("dev",), "release/alpha": ("dev",), "release/beta": ("dev",),
                     "release/preprod": ("main",), "release/prod": ("main",)}


def is_promotion(target, head_ref, same_repository):
    """A same-repository head that the release design names as the promotion source of this target."""
    return bool(same_repository) and head_ref in PROMOTION_SOURCES.get(target, ())


class PolicyError(ValueError):
    """Metadata does not satisfy the repository's reviewed promotion policy."""


def require(condition, message):
    if not condition:
        raise PolicyError(message)


def evaluate(pr, repository, repository_id, mode):
    require(mode in MODES, "unknown promotion policy mode")
    require(REPOSITORY.fullmatch(repository), "invalid repository identity")
    require(type(repository_id) is int and repository_id > 0, "repository ID must be a positive integer")
    require(type(pr.get("number")) is int and pr["number"] > 0, "missing pull-request number")
    require(type(pr.get("title")) is str and TITLE.fullmatch(pr["title"]),
            "require a conventional PR title: type(scope): description")
    base, head = pr.get("base", {}), pr.get("head", {})
    require(base.get("repo", {}).get("id") == repository_id
            and base.get("repo", {}).get("full_name") == repository,
            "pull request does not target the expected repository")
    require(type(base.get("ref")) is str and bool(base["ref"]), "missing target branch")
    require(type(head.get("ref")) is str and bool(head["ref"]), "missing source branch")
    require(type(head.get("sha")) is str and COMMIT.fullmatch(head["sha"]), "missing full source commit")
    if mode == "dev-only-main" and base["ref"] == "main":
        require(head["ref"] == "dev" and (head.get("repo") or {}).get("id") == repository_id
                and (head.get("repo") or {}).get("full_name") == repository,
                "main accepts only dev from the same repository")
    return {"schemaVersion": 1, "repository": repository, "repositoryId": repository_id,
            "pullRequest": pr["number"], "mode": mode, "base": base["ref"],
            "head": head["ref"], "headSha": head["sha"],
            "titleSha256": hashlib.sha256(pr["title"].encode()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", type=Path, default=os.environ.get("GITHUB_EVENT_PATH"))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"), required=False)
    parser.add_argument("--repository-id", type=int, default=os.environ.get("GITHUB_REPOSITORY_ID"))
    parser.add_argument("--mode", default=os.environ.get("SCARAB_PROMOTION_POLICY") or "transition")
    args = parser.parse_args()
    require(args.event is not None and args.repository is not None, "require event and repository identity")
    event = json.loads(args.event.read_text())
    require(event.get("repository", {}).get("id") == args.repository_id,
            "event repository identity differs from the trusted runner")
    print(json.dumps(evaluate(event.get("pull_request", {}), args.repository,
                              args.repository_id, args.mode), sort_keys=True))


if __name__ == "__main__":
    main()

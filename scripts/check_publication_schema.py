#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the published receipt's actual JSON Schema; install publication-requirements.txt first."""
import argparse
from copy import deepcopy
from pathlib import Path

from publication_common import load_json, require
from published_receipt import CHECKS, WORKFLOW


def check():
    from jsonschema import Draft202012Validator
    schema = load_json(Path(__file__).parent / "schemas/published-verification-v1.json")
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    example = {"schemaVersion": 1, "status": "verified", "phase": "public", "repository": "example/scarab",
               "tag": "v1.2.3", "sourceCommit": "a" * 40, "version": "1.2.3", "channel": "prod",
               "candidateSha256": "b" * 64, "subjects": {"jarSha256": "c" * 64, "imageDigest": "sha256:" + "d" * 64,
                                                       "bomSha256": ["e" * 64, "f" * 64]},
               "publisher": {"workflow": WORKFLOW, "workflowCommit": "a" * 40, "runId": 1},
               "checks": {key: True for key in CHECKS}}
    count = 0
    for version, channel in [("1.2.3", "prod"), ("1.2.3-alpha.0", "alpha"), ("1.2.3-beta.1", "beta"), ("1.2.3-rc.0", "preprod")]:
        positive = dict(example, version=version, tag="v" + version, channel=channel)
        require(validator.is_valid(positive), "schema refused canonical public receipt")
        count += 1
    for version in ["01.2.3", "1.02.3", "1.2.03", "1.2.3-alpha.01", "1.2.3-beta.01", "1.2.3-rc.01",
                    "1.2.3-preprod.1", "1.2.3+build", "1.2.3\n", "v1.2.3"]:
        for field, value in [("version", version), ("tag", "v" + version)]:
            require(not validator.is_valid(dict(example, **{field: value})), "schema accepted noncanonical " + field)
            count += 1
    for change in [{"phase": "staged"}, {"schemaVersion": True}, {"status": "pending"}, {"checks": {}}]:
        require(not validator.is_valid(dict(example, **change)), "public schema accepted incomplete or staged receipt")
        count += 1
    incomplete = deepcopy(example)
    incomplete["checks"]["anonymousDownloads"] = False
    require(not validator.is_valid(incomplete), "public schema accepted authenticated-only downloads")
    return count + 1


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    print("PASS " + str(check()) + " actual JSON Schema controls; synthetic mechanism cases, not publication evidence")


if __name__ == "__main__":
    main()

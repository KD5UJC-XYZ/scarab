#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Bind owner-observed ruleset data to one protected workflow approval."""
import argparse
import hashlib
import os
from pathlib import Path

from promotion_rules import verify_rulesets
from publication_common import canonical, decode_json, load_json, require


def approved_bytes(encoded, repository, promotion_app_id, release_app_id):
    require(len(encoded) <= 65536, 'ruleset audit exceeds the workflow input budget')
    audit = decode_json(encoded)
    if audit is not None:
        verify_rulesets(audit['rulesets'], promotion_app_id, release_app_id,
                        audit=audit, repository=repository)
    return canonical(audit)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--promotion-app-id', type=int, required=True)
    parser.add_argument('--release-app-id', type=int, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--input', type=Path)
    parser.add_argument('--expected-sha256')
    args = parser.parse_args()
    if args.input:
        require(args.input.stat().st_size <= 65536, 'ruleset audit artifact exceeds input budget')
        encoded = args.input.read_bytes()
        require(hashlib.sha256(encoded).hexdigest() == args.expected_sha256,
                'approved ruleset audit artifact digest differs')
    else:
        policy = load_json(Path(__file__).resolve().parents[1] / 'release/promotion-policy.json')
        encoded = os.environ.get('RULESET_AUDIT', '').encode() or canonical(policy.get('rulesetAudit'))
    checked = approved_bytes(encoded, args.repository, args.promotion_app_id, args.release_app_id)
    if args.input:
        require(checked == encoded, 'ruleset audit artifact is not canonical approved data')
    else:
        require(args.output is not None, 'require fresh preflight audit output')
        with args.output.open('xb') as output:
            output.write(checked)
        digest = hashlib.sha256(checked).hexdigest()
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('sha256=' + digest + '\n')
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write('Review retained ruleset-audit.json before approval. SHA-256: `' + digest + '`\n')


if __name__ == '__main__':
    main()

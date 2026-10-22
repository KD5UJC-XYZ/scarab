# SPDX-License-Identifier: Apache-2.0
"""Read-only server authority checks performed before any coordinator mutation."""
import base64
import os
import re
import subprocess
from pathlib import Path

from publication_common import commit, digest, require, sha256
from promotion_rules import verify_rulesets

CONTROL_PATHS = ['.github/workflows/release-publication.yml', '.github/workflows/release-candidate.yml']


def control_plane(github, source, trusted):
    commit(source)
    commit(trusted)
    main = github.get('/git/ref/heads/main')
    require(main['object']['sha'] == trusted, 'trusted default branch advanced; start a new reviewed dispatch')
    def workflow_tree(revision):
        rows = github.get('/contents/.github/workflows?ref=' + revision)
        require(isinstance(rows, list) and rows, 'complete workflow directory listing required')
        identities = [(row['name'], row['type'], row['sha']) for row in rows]
        require(len({row[0] for row in identities}) == len(identities), 'duplicate workflow directory identity')
        return sorted(identities)
    # GitHub release creation otherwise requires Workflows write for any differing workflow file.
    require(workflow_tree(source) == workflow_tree(trusted),
            'selected cut differs from the approved complete workflow tree; align the release cut')
    for path in CONTROL_PATHS:
        approved = github.get('/contents/' + path + '?ref=' + trusted)
        selected = github.get('/contents/' + path + '?ref=' + source)
        require(approved.get('type') == 'file' and selected.get('sha') == approved.get('sha'),
                'selected source has an unapproved release control workflow: ' + path)
    # These are deliberately trusted-main helpers, never scripts imported from the selected candidate.
    for relative in ['scripts/publication_authority.py', 'scripts/publication_coordinator.py',
                     'scripts/published_receipt.py']:
        expected = github.get('/contents/' + relative + '?ref=' + trusted)
        require(expected.get('encoding') == 'base64'
                and base64.b64decode(expected['content']) == (Path(__file__).parent.parent / relative).read_bytes(),
                'local coordinator differs from approved control plane')


def rules(github, policy, audit=None):
    require(policy.get('enabled') is True and policy.get('repository') == github.repository,
            'publication is not activated by reviewed owner policy')
    listing = github.pages('/rulesets?includes_parents=true')
    actual = [github.get('/rulesets/' + str(row['id'])) for row in listing]
    verify_rulesets(actual, policy['promotionAppId'], policy['releaseAppId'],
                    audit=audit or policy.get('rulesetAudit'), actor_role='release', repository=github.repository)


def candidate_run(github, run_id, source, tag, artifact_id, artifact_digest):
    asset = github.get('/actions/artifacts/' + str(artifact_id))
    match = re.fullmatch(re.escape('release-candidate-' + source + '-') + r'([1-9][0-9]*)', asset.get('name', ''))
    require(match is not None, 'candidate artifact has no canonical retained attempt identity')
    attempt = int(match.group(1))
    require(asset.get('workflow_run', {}).get('id') == run_id and asset.get('expired') is False
            and asset.get('digest') == 'sha256:' + sha256(artifact_digest)
            and asset.get('workflow_run', {}).get('head_sha') == source,
            'candidate artifact belongs to another run/source or has changed')
    route = '/actions/runs/' + str(run_id) + '/attempts/' + str(attempt)
    run = github.get(route)
    require(run.get('head_sha') == source and run.get('head_branch') == tag
            and run.get('run_attempt') == attempt and run.get('event') == 'workflow_dispatch'
            and run.get('path') == '.github/workflows/release-publication.yml'
            and run.get('repository', {}).get('full_name') == github.repository,
            'candidate run is not the canonical exact-tag publisher invocation')
    jobs = github.pages(route + '/jobs', key='jobs')
    for name in ['exact-cut-controls', 'candidate / candidate']:
        gate = [job for job in jobs if job.get('name') == name]
        require(len(gate) == 1 and gate[0].get('conclusion') == 'success',
                'canonical exact-cut gate is not successful: ' + name)
    return asset


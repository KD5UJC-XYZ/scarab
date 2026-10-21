# SPDX-License-Identifier: Apache-2.0
"""Read-only server authority checks performed before any coordinator mutation."""
import base64
import os
import subprocess
from pathlib import Path

from publication_common import commit, digest, require, sha256
from promotion_rules import verify_rulesets

CONTROL_PATHS = ['.github/workflows/release-publication.yml', '.github/workflows/release-candidate.yml',
                 '.github/workflows/release-production-acceptance.yml']


def control_plane(github, source, trusted):
    commit(source)
    commit(trusted)
    main = github.get('/git/ref/heads/main')
    require(main['object']['sha'] == trusted, 'trusted default branch advanced; start a new reviewed dispatch')
    for path in CONTROL_PATHS:
        approved = github.get('/contents/' + path + '?ref=' + trusted)
        selected = github.get('/contents/' + path + '?ref=' + source)
        require(approved.get('type') == 'file' and selected.get('sha') == approved.get('sha'),
                'selected source has an unapproved release control workflow: ' + path)
    # These are deliberately trusted-main helpers, never scripts imported from the selected candidate.
    for relative in ['scripts/publication_authority.py', 'scripts/publication_coordinator.py',
                     'scripts/published_receipt.py', 'scripts/production_acceptance.py']:
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
    run = github.get('/actions/runs/' + str(run_id))
    require(run.get('head_sha') == source and run.get('head_branch') == tag
            and run.get('event') == 'workflow_dispatch'
            and run.get('path') == '.github/workflows/release-publication.yml'
            and run.get('repository', {}).get('full_name') == github.repository,
            'candidate run is not the canonical exact-tag publisher invocation')
    jobs = github.pages('/actions/runs/' + str(run_id) + '/jobs', key='jobs')
    for name in ['exact-cut-controls', 'candidate / candidate']:
        gate = [job for job in jobs if job.get('name') == name]
        require(len(gate) == 1 and gate[0].get('conclusion') == 'success',
                'canonical exact-cut gate is not successful: ' + name)
    matches = [job for job in jobs if job.get('name') == 'candidate / candidate']
    require(len(matches) == 1 and matches[0].get('conclusion') == 'success',
            'trusted reusable candidate workflow did not complete --check successfully')
    asset = github.get('/actions/artifacts/' + str(artifact_id))
    require(asset.get('workflow_run', {}).get('id') == run_id and asset.get('expired') is False
            and asset.get('name') == 'release-candidate-' + source + '-' + str(run['run_attempt'])
            and asset.get('digest') == 'sha256:' + sha256(artifact_digest)
            and asset.get('workflow_run', {}).get('head_sha') == source,
            'candidate artifact belongs to another run/source or has changed')
    return asset


def production_run(github, run_id, source, tag):
    run = github.get('/actions/runs/' + str(run_id))
    require(run.get('head_sha') == source and run.get('head_branch') == tag
            and run.get('path') == '.github/workflows/release-production-acceptance.yml'
            and run.get('event') == 'workflow_dispatch' and run.get('conclusion') == 'success'
            and run.get('repository', {}).get('full_name') == github.repository,
            'production proof is not a successful canonical exact-tag observer run')
    jobs = github.pages('/actions/runs/' + str(run_id) + '/jobs', key='jobs')
    for name in ['observe', 'authenticate-observation']:
        found = [row for row in jobs if row.get('name') == name]
        require(len(found) == 1 and found[0].get('conclusion') == 'success', 'production capture/hosted attestor incomplete')
    artifacts = github.pages('/actions/runs/' + str(run_id) + '/artifacts', key='artifacts')
    found = [row for row in artifacts if row.get('name') == 'public-produced-block-observation']
    require(len(found) == 1 and found[0].get('expired') is False
            and found[0].get('workflow_run', {}).get('id') == run_id,
            'original canonical observation artifact missing or ambiguous')
    sha256(found[0]['digest'].removeprefix('sha256:'))
    return found[0]

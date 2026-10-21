#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fixed workflow adapter: environment values are arguments, never shell program text."""
import argparse
import os
from pathlib import Path
import subprocess

from publication_common import GitHub, canonical, load_json, require
from publication_policy import environment_snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['preflight', 'reserve', 'stage', 'publish', 'finalize', 'prepare', 'verify-staged', 'verify-public', 'select-candidate', 'approved-audit', 'authorize'])
    args = parser.parse_args()
    temporary = Path(os.environ['RUNNER_TEMP'])
    common = ['--source', os.environ['RELEASE_SOURCE'], '--tag', os.environ['RELEASE_TAG'],
              '--channel', os.environ['RELEASE_CHANNEL']]
    candidate = temporary / 'candidate'
    if args.operation == 'authorize':
        import publication_authority
        github = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ.get('GH_TOKEN'))
        policy = load_json('release/publication-policy.json')
        audit = temporary / 'preflight/ruleset-audit.json'
        publication_authority.control_plane(github, os.environ['RELEASE_SOURCE'], os.environ['TRUSTED_SOURCE'])
        publication_authority.rules(github, policy, load_json(audit) if audit.exists() else None)
        require(environment_snapshot(github, os.environ['RELEASE_CHANNEL'], policy) ==
                load_json(temporary / 'preflight/environment.json'), 'environment changed after approval')
        from publication_candidate import checked_candidate
        from publication_coordinator import Publication
        manifest = checked_candidate(candidate, os.environ['RELEASE_SOURCE'], os.environ['RELEASE_TAG'],
                                     os.environ['RELEASE_CHANNEL'], os.environ['CANDIDATE_SHA256'])
        publication_authority.candidate_run(github, int(os.environ['CANDIDATE_RUN_ID']), os.environ['RELEASE_SOURCE'],
                                           os.environ['RELEASE_TAG'], int(os.environ['CANDIDATE_ARTIFACT_ID']),
                                           os.environ['CANDIDATE_ARTIFACT_DIGEST'])
        publication = Publication(github, manifest['source']['buildIdentity'], os.environ['CANDIDATE_SHA256'],
                                  manifest['image']['manifest']['digest'])
        publication.check_source(retry=publication.checked_release(optional=True) is not None)
        return
    if args.operation == 'approved-audit':
        from promotion_rules import owner_audit
        from publication_common import file_digest
        path = temporary / 'preflight/ruleset-audit.json'
        if path.exists():
            require(path.stat().st_size <= 65536, 'ruleset audit exceeds review budget')
            policy = load_json('release/publication-policy.json')
            value = load_json(path)
            require(owner_audit(value['rulesets'], policy['promotionAppId'], policy['releaseAppId'],
                                os.environ['GITHUB_REPOSITORY']) == value, 'approved audit differs from reviewed template')
            expected = (temporary / 'preflight/ruleset-audit.sha256').read_text().strip()
            require(file_digest(path) == expected, 'approved audit digest differs from retained reviewer input')
        return
    if args.operation == 'select-candidate':
        from publication_authority import candidate_run
        from publication_common import sha256
        previous = os.environ.get('PREVIOUS_CANDIDATE_RUN', '')
        selected = {key: os.environ.get('PREVIOUS_' + key.upper().replace('-', '_'), '') for key in
                    ['artifact-id', 'artifact-digest', 'manifest-sha256']}
        if previous:
            github = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ.get('GH_TOKEN'))
            candidate_run(github, int(previous), os.environ['RELEASE_SOURCE'], os.environ['RELEASE_TAG'],
                          int(selected['artifact-id']), selected['artifact-digest'])
            sha256(selected['manifest-sha256'])
            selected['run-id'] = previous
        else:
            selected = {key: os.environ['FRESH_' + key.upper().replace('-', '_')] for key in
                        ['artifact-id', 'artifact-digest', 'manifest-sha256']}
            selected['run-id'] = os.environ['GITHUB_RUN_ID']
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write(''.join(key + '=' + value + '\n' for key, value in selected.items()))
        return
    if args.operation == 'preflight':
        policy = load_json('release/publication-policy.json')
        github = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ.get('GH_TOKEN'))
        snapshot = environment_snapshot(github, os.environ['RELEASE_CHANNEL'], policy)
        (temporary / 'preflight').mkdir(exist_ok=False)
        (temporary / 'preflight/environment.json').write_bytes(canonical(snapshot) + b'\n')
        audit = os.environ.get('RULESET_AUDIT', '')
        require(len(audit.encode()) <= 65536, 'ruleset audit exceeds review budget')
        if not audit and policy.get('rulesetAudit') is not None:
            audit = canonical(policy['rulesetAudit']).decode()
        if audit:
            from promotion_rules import owner_audit
            from publication_common import decode_json
            audited = decode_json(audit)
            require(owner_audit(audited['rulesets'], policy['promotionAppId'], policy['releaseAppId'],
                                github.repository) == audited, 'operator audit differs from reviewed full policy')
            encoded = canonical(audited) + b'\n'
            (temporary / 'preflight/ruleset-audit.json').write_bytes(encoded)
            from publication_common import digest
            identity = digest(encoded)
            (temporary / 'preflight/ruleset-audit.sha256').write_text(identity + '\n')
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write('Reviewed ruleset audit SHA256: `' + identity + '`\n')
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write('environment=' + snapshot['environment'] + '\n')
        return
    tools = temporary / 'publication-tools/bin'
    if args.operation == 'prepare':
        command = ['python3', 'scripts/prepare_publication.py', '--candidate', str(candidate), *common,
                   '--candidate-sha256', os.environ['CANDIDATE_SHA256']]
    elif args.operation.startswith('verify-'):
        command = ['python3', 'scripts/verify_published_release.py', *common, '--repository', os.environ['GITHUB_REPOSITORY'],
                   '--candidate-sha256', os.environ['CANDIDATE_SHA256'], '--image-repository', os.environ['IMAGE_REPOSITORY'],
                   '--phase', args.operation.removeprefix('verify-'), '--run-id', os.environ['GITHUB_RUN_ID'],
                   '--tools', str(tools), '--jdk-image', os.environ['VERIFIER_JDK'],
                   '--output', str(temporary / (args.operation + '-result'))]
    else:
        command = ['python3', 'scripts/publish_release.py', args.operation, *common,
                   '--repository', os.environ['GITHUB_REPOSITORY'], '--trusted-source', os.environ['TRUSTED_SOURCE'],
                   '--image-repository', os.environ['IMAGE_REPOSITORY'], '--policy', 'release/publication-policy.json',
                   '--environment-snapshot', str(temporary / 'preflight/environment.json'), '--tools', str(tools)]
        audit = temporary / 'preflight/ruleset-audit.json'
        if audit.exists():
            command.extend(['--ruleset-audit', str(audit)])
        if args.operation != 'reserve':
            command.extend(['--candidate', str(candidate), '--candidate-sha256', os.environ['CANDIDATE_SHA256'],
                            '--artifact-id', os.environ['CANDIDATE_ARTIFACT_ID'], '--artifact-digest', os.environ['CANDIDATE_ARTIFACT_DIGEST'],
                            '--run-id', os.environ['CANDIDATE_RUN_ID']])
        if args.operation in {'publish', 'finalize'}:
            command.extend(['--receipt', str(temporary / 'verification/verification.json')])
        acceptance = temporary / 'acceptance/observation.json'
        if acceptance.exists():
            command.extend(['--acceptance', str(acceptance), '--acceptance-run', os.environ['PRODUCTION_PROOF_RUN']])
    subprocess.run(command, check=True)


if __name__ == '__main__':
    main()

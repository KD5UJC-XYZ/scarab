#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Explicit coordinator entry points; invocation alone never grants publication authority."""
import argparse
import os
from pathlib import Path
import subprocess

import publication_authority
from publication_candidate import checked_candidate, records, release_checksums
from publication_common import GitHub, canonical, digest, file_digest, load_json, require
from publication_coordinator import Publication, reserve_tag
from publication_policy import environment_snapshot
from production_acceptance import authenticate as acceptance_proof
from published_receipt import authenticate_receipt
from publication_registry import copy as registry_copy


def execute(args):
    github = GitHub(args.repository, os.environ.get('GH_TOKEN'))
    policy = load_json(args.policy)
    require(args.repository == 'KD5UJC-XYZ/scarab', 'publisher is scoped to the Scarab repository')
    publication_authority.control_plane(github, args.source, args.trusted_source)
    publication_authority.rules(github, policy, load_json(args.ruleset_audit) if args.ruleset_audit else None)
    snapshot = environment_snapshot(github, args.channel, policy)
    require(snapshot == load_json(args.environment_snapshot), 'environment policy changed after protected approval')
    require(policy['imageRepository'] == args.image_repository, 'image repository differs from reviewed policy')
    if args.operation == 'reserve':
        require(os.environ.get('GITHUB_REF') == 'refs/heads/main'
                and os.environ.get('GITHUB_SHA') == args.trusted_source, 'reservation must run from trusted main')
        # Exact-cut notes and their reviewed planning baseline must be authenticated before tag creation.
        from release_proposal import load_proposal
        proposal = load_proposal(Path.cwd(), args.source)
        require(proposal['receipt']['version'] == args.tag[1:], 'proposal and requested authoritative tag differ')
        baseline = proposal['receipt']['authority']
        if baseline['kind'] == 'bootstrap':
            require(policy.get('bootstrap') == {'source': baseline['source'], 'version': baseline['version']},
                    'unpublished bootstrap planning anchor differs from reviewed first-release policy')
        else:
            require(baseline['kind'] == 'published-stable', 'unknown baseline authority')
            raise_if_missing_baseline(policy, baseline, args.tools)
        reserve_tag(github, args.tag, args.source, args.channel)
        github.request('POST', github.repo_path('/actions/workflows/release-publication.yml/dispatches'), payload={
            'ref': args.tag, 'inputs': {'operation': 'publish', 'source': args.source, 'tag': args.tag,
                                      'channel': args.channel, 'trusted-source': args.trusted_source,
                                      'ruleset-audit': canonical(load_json(args.ruleset_audit)).decode() if args.ruleset_audit else ''}})
        return
    require(os.environ.get('GITHUB_REF') == 'refs/tags/' + args.tag and os.environ.get('GITHUB_SHA') == args.source,
            'publisher requires an explicit exact-tag event, never a branch pretending to be a tag')
    publication_authority.candidate_run(github, args.run_id, args.source, args.tag, args.artifact_id, args.artifact_digest)
    manifest = checked_candidate(args.candidate, args.source, args.tag, args.channel, args.candidate_sha256)
    identity = manifest['source']['buildIdentity']
    publication = Publication(github, identity, args.candidate_sha256, manifest['image']['manifest']['digest'])
    from release_proposal import load_proposal
    proposal = load_proposal(Path.cwd(), args.source)
    notes = proposal['notes'].decode('utf-8') + '\n\nImage: `' + args.image_repository + '@' + manifest['image']['manifest']['digest'] + '`\n\n' + \
        'The release assets retain the standalone JAR, JVM/image SBOMs, SHA256SUMS, signatures and complete candidate proof archive. ' + \
        'Completion retains original public-verification receipt bytes in the release notes; authenticate their GitHub attestation. ' + \
        'An incomplete state is not a verified delivery.\n'
    from publication_common import asset_name
    jar_subject = next(row for row in manifest['subjects'] if row['kind'] == 'jar')
    base = 'https://github.com/' + args.repository + '/releases/download/' + args.tag + '/'
    notes += '\n[JAR](' + base + asset_name(jar_subject['file']['path']) + ') | [GHCR package](https://github.com/' + args.repository + '/pkgs/container/scarab)\n'
    for subject in manifest['subjects']:
        notes += '[SBOM for ' + subject['kind'] + '](' + base + asset_name(subject['bom']['file']['path']) + ')\n'
    if args.operation == 'stage':
        existing = publication.checked_release(optional=True)
        if existing is not None:
            from publication_coordinator import state_from_release
            current = state_from_release(existing)['status']
            if current != 'draft':
                publication.check_source(retry=True)
                output_status(current)
                return
        release = publication.ensure_draft(notes)
        from publication_retry import reuse_bundles
        reuse_bundles(github, release, args.candidate, manifest, args.candidate.parent / 'candidate-transport.tar',
                      args.image_repository, args.tools / 'gh')
        oci = next(row for row in manifest['subjects'] if row['kind'] == 'oci-archive')
        registry_copy(args.tools / 'oras', str(args.candidate / oci['file']['path']) + '@' + publication.expected['imageDigest'],
                      args.image_repository, 'staging-' + args.source + '-' + args.candidate_sha256,
                      publication.expected['imageDigest'], from_layout=True)
        upload = []
        for row in records(args.candidate, manifest):
            upload.append(dict(row, path=args.candidate / row['path']))
        checksums = args.candidate / 'SHA256SUMS'
        require(checksums.read_bytes() == release_checksums(args.candidate, manifest), 'signing inventory differs from candidate')
        archive = args.candidate.parent / 'candidate-transport.tar'
        upload.append({'name': archive.name, 'path': archive, 'size': archive.stat().st_size, 'sha256': file_digest(archive)})
        for name in ['SHA256SUMS', 'provenance.sigstore.json', 'jar-sbom.sigstore.json',
                     'image-provenance.sigstore.json', 'image-sbom.sigstore.json']:
            path = args.candidate / name
            upload.append({'name': name, 'path': path, 'size': path.stat().st_size, 'sha256': file_digest(path)})
        publication.upload(release, upload)
        publication.transition('draft', 'draft-staged', notes)
        output_status('draft-staged')
    elif args.operation == 'publish':
        proof = authenticate_receipt(args.receipt, args.repository, args.source, identity['version'], args.tools / 'gh', 'staged')
        publication.transition('draft-staged', 'verified', notes, proof)
        accepted = None
        if args.channel == 'prod':
            require(args.acceptance is not None, 'production remains blocked pending real produced/adopted preprod block evidence')
            require(args.acceptance_run is not None, 'canonical production observer run identity required')
            publication_authority.production_run(github, args.acceptance_run, args.source, args.tag)
            accepted = acceptance_proof(args.acceptance, args.repository, args.source, args.tag, args.candidate_sha256,
                                        proof.document['subjects']['jarSha256'], publication.expected['imageDigest'],
                                        policy['preprodGenesisSha256'], args.tools / 'gh', args.acceptance_run)
        publication.transition('verified', 'published', notes, proof, accepted)
    else:
        proof = authenticate_receipt(args.receipt, args.repository, args.source, identity['version'], args.tools / 'gh')
        # Alias writes happen only after actual anonymous downloads and both runtime checks.
        registry_copy(args.tools / 'oras', args.image_repository + '@' + publication.expected['imageDigest'],
                      args.image_repository, identity['version'], publication.expected['imageDigest'])
        publication.transition('published', 'aliased', notes, proof)


def output_status(status):
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('status=' + status + '\n')


def raise_if_missing_baseline(policy, baseline, tools):
    from published_receipt import authenticate
    approved = policy.get('publishedBaselines', {}).get(baseline['version'])
    require(approved is not None and approved.get('source') == baseline['source'], 'reviewed published baseline authority missing')
    path = Path(approved['path'])
    require(file_digest(path) == approved['sha256'], 'reviewed baseline receipt bytes changed')
    authenticate(path, policy['repository'], approved['source'], baseline['version'], tools / 'gh')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['reserve', 'stage', 'publish', 'finalize'])
    for name in ['repository', 'source', 'trusted-source', 'tag', 'channel', 'image-repository']:
        parser.add_argument('--' + name, required=True)
    for name in ['policy', 'environment-snapshot', 'tools']:
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ['ruleset-audit', 'candidate', 'receipt', 'acceptance']:
        parser.add_argument('--' + name, type=Path)
    parser.add_argument('--candidate-sha256')
    parser.add_argument('--artifact-id', type=int)
    parser.add_argument('--artifact-digest')
    parser.add_argument('--run-id', type=int)
    parser.add_argument('--acceptance-run', type=int)
    args = parser.parse_args()
    if args.operation != 'reserve':
        require(all([args.candidate, args.candidate_sha256, args.artifact_id, args.artifact_digest, args.run_id]),
                'canonical exact-run candidate transport identity required')
    execute(args)


if __name__ == '__main__':
    main()

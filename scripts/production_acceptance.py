#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Authenticate exact-artifact produced-block evidence, never a follower-only observation."""
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
import re
import subprocess

from publication_common import canonical, commit, decode_json, load_json, require, sha256

WORKFLOW = '.github/workflows/release-production-acceptance.yml'
PREDICATE = 'https://scarab.kd5ujc.xyz/attestations/produced-block/v1'
_AUTHORITY = object()


@dataclass(frozen=True)
class AuthenticatedAcceptance:
    encoded: bytes
    authority: object = field(repr=False)

    def __post_init__(self):
        require(self.authority is _AUTHORITY, 'raw observations are not authenticated acceptance')

    @property
    def document(self):
        return decode_json(self.encoded)


def validate(value, repository, source, candidate_hash, jar_hash, image_digest):
    require(set(value) == {'schemaVersion', 'repository', 'sourceCommit', 'candidateSha256', 'jarSha256',
                           'imageDigest', 'network', 'produced', 'native', 'observedAt'}, 'incomplete produced-block evidence')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1, 'unsupported acceptance schema')
    commit(source)
    require(value['repository'] == repository and value['sourceCommit'] == source
            and value['candidateSha256'] == sha256(candidate_hash) and value['jarSha256'] == sha256(jar_hash)
            and value['imageDigest'] == image_digest and re.fullmatch(r'sha256:[0-9a-f]{64}', image_digest),
            'produced-block evidence describes other artifacts')
    network = value['network']
    require(set(network) == {'name', 'magic', 'genesisSha256'} and network['name'] == 'preprod'
            and type(network['magic']) is int and network['magic'] == 1, 'genuine preprod identity required')
    require(set(network['genesisSha256']) == {'byron', 'shelley', 'alonzo', 'conway'}, 'all reviewed genesis hashes required')
    for item in network['genesisSha256'].values():
        sha256(item)
    produced = value['produced']
    require(set(produced) == {'slot', 'hash', 'pool', 'vrfKeyHash', 'headerCbor', 'forged', 'adopted'},
            'produced original header and both Scarab events required')
    require(type(produced['slot']) is int and produced['slot'] > 0 and produced['forged'] is True
            and produced['adopted'] is True, 'a follower tip is not Scarab production')
    sha256(produced['hash'])
    require(re.fullmatch(r'[0-9a-f]{56}', produced['pool']) and re.fullmatch(r'[0-9a-f]{64}', produced['vrfKeyHash'])
            and re.fullmatch(r'(?:[0-9a-f]{2}){1,65536}', produced['headerCbor']), 'public header/key evidence invalid')
    from observe_produced_block import header_identity
    require(header_identity(bytes.fromhex(produced['headerCbor'])) ==
            {key: produced[key] for key in ['slot', 'hash', 'pool', 'vrfKeyHash']},
            'original header does not bind produced point and public producer identity')
    native = value['native']
    require(set(native) == {'binarySha256', 'acquiredSlot', 'acquiredHash', 'queryPointMatched'}
            and native['acquiredSlot'] == produced['slot'] and native['acquiredHash'] == produced['hash']
            and native['queryPointMatched'] is True, 'native peer did not acquire this produced block')
    from prepare_reference_image import BINARY_HASHES
    require(native['binarySha256'] == BINARY_HASHES['cardano-node'], 'unrecognized independent native binary')
    # No date/receipt flag can waive the real produced-point evidence above.
    observed = datetime.fromisoformat(value['observedAt'].replace('Z', '+00:00'))
    require(observed.tzinfo is not None, 'observation requires a timezone-qualified actual capture time')
    return value


def authenticate(path, repository, source, tag, candidate_hash, jar_hash, image_digest, genesis_hashes, gh, run_id):
    value = validate(load_json(path), repository, source, candidate_hash, jar_hash, image_digest)
    require(value['network']['genesisSha256'] == genesis_hashes, 'acceptance network differs from reviewed preprod genesis')
    command = [str(gh), 'attestation', 'verify', str(path), '--repo', repository,
               '--signer-workflow', repository + '/' + WORKFLOW, '--signer-digest', source,
               '--source-digest', source, '--source-ref', 'refs/tags/' + tag,
               '--cert-oidc-issuer', 'https://token.actions.githubusercontent.com',
               '--deny-self-hosted-runners', '--predicate-type', PREDICATE, '--format', 'json']
    result = subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    verified = decode_json(result.stdout)
    require(isinstance(verified, list) and verified, 'no canonical authenticated production proof')
    require(type(run_id) is int and run_id > 0, 'canonical observation run identity required')
    expected = 'https://github.com/' + repository + '/actions/runs/' + str(run_id) + '/attempts/'
    require(any(canonical(item['verificationResult']['statement']['predicate']) == canonical(value)
                and re.fullmatch(re.escape(expected) + r'[1-9][0-9]*',
                                 item['verificationResult']['signature']['certificate'].get('runInvocationURI', ''))
                for item in verified), 'canonical attestation does not carry this observation')
    return AuthenticatedAcceptance(canonical(value), _AUTHORITY)

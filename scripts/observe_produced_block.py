#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read public evidence from existing processes only; never starts a forger or accesses its keys."""
import argparse
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import posixpath
import re
import subprocess
import tempfile

from devnet.ntcquery import LocalStateQuery, decode
from publication_candidate import checked_candidate
from publication_common import canonical, decode_json, file_digest, load_json, require
from production_acceptance import validate


def header_identity(raw):
    require(0 < len(raw) <= 65536, 'header inspection budget exceeded')
    value, end = decode(raw)
    require(end == len(raw) and isinstance(value, list) and len(value) == 2
            and isinstance(value[0], list) and len(value[0]) == 10, 'original Conway header required')
    body = value[0]
    require(type(body[1]) is int and body[1] > 0 and isinstance(body[3], bytes) and len(body[3]) == 32
            and isinstance(body[4], bytes) and len(body[4]) == 32, 'header slot/issuer/VRF fields invalid')
    return {'slot': body[1], 'hash': hashlib.blake2b(raw, digest_size=32).hexdigest(),
            'pool': hashlib.blake2b(body[3], digest_size=28).hexdigest(),
            'vrfKeyHash': hashlib.blake2b(body[4], digest_size=32).hexdigest()}


def public_events(lines, point):
    require(len(lines) <= 32 * 1024 * 1024, 'bounded public log window exceeded')
    # Traced[ForgeEvent] emits these exact public fields; log bodies are never retained.
    started = forged = adopted = False
    for line in lines.splitlines():
        if not line.startswith('{'):
            continue
        row = decode_json(line)
        if row.get('event') == 'forgeStarted':
            started = row.get('pool') == point['pool'] and row.get('vrfKeyHash') == point['vrfKeyHash']
        elif row.get('event') == 'forged' and started:
            forged = forged or (row.get('slot') == point['slot'] and row.get('hash') == point['hash'])
        elif row.get('event') == 'adopted' and forged:
            block = row.get('block', {})
            adopted = adopted or (block.get('slot') == point['slot'] and block.get('hash') == point['hash'])
    require(started and forged and adopted, 'missing matching Scarab Started/Forged/Adopted public event sequence')


def docker(*arguments):
    return subprocess.run(['docker', *arguments], check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=120).stdout


def inspect_running(name, mounts=False):
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', name), 'explicit existing container name required')
    template = ('{"State":{"Running":{{json .State.Running}}},"Config":{"Image":{{json .Config.Image}}},'
                '"Image":{{json .Image}},"Path":{{json .Path}},"Args":{{json .Args}}')
    template += ',"Mounts":{{json .Mounts}}}' if mounts else '}'
    row = decode_json(docker('inspect', '--format', template, name))
    require(row['State']['Running'] is True, 'observer requires an already-running process')
    return row


def observe(args):
    manifest = checked_candidate(args.candidate, args.source, args.tag, args.channel, args.candidate_sha256)
    jar = next(row for row in manifest['subjects'] if row['kind'] == 'jar')
    forger = inspect_running(args.forger)
    require(forger['Config']['Image'] == args.image_repository + '@' + manifest['image']['manifest']['digest'],
            'running forger was not started from the exact final OCI digest')
    running_image = decode_json(docker('image', 'inspect', '--format',
                                      '{"Id":{{json .Id}},"RepoDigests":{{json .RepoDigests}}}', forger['Image']))
    require(running_image['Id'] == manifest['image']['configDigest']
            and args.image_repository + '@' + manifest['image']['manifest']['digest'] in running_image.get('RepoDigests', []),
            'running image content differs from final candidate')
    require('--forge' in forger.get('Args', []) and forger.get('Path') == 'java', 'existing Scarab forge process required')
    with tempfile.TemporaryDirectory(prefix='scarab-public-observation-') as temporary:
        copied = Path(temporary) / 'scarab.jar'
        docker('cp', args.forger + ':/opt/scarab/scarab.jar', str(copied))
        require(file_digest(copied) == jar['file']['sha256'], 'running forger JAR differs from final candidate')
    raw = args.header.read_bytes()
    point = header_identity(raw)
    logs = docker('logs', '--since', args.since, '--tail', '100000', args.forger).decode()
    public_events(logs, point)
    native = inspect_running(args.native, mounts=True)
    process = docker('exec', args.native, 'cat', '/proc/1/cmdline').decode().split('\0')
    require('--socket-path' in process and '--config' in process, 'independent native process/config/socket identity missing')
    native_socket = process[process.index('--socket-path') + 1]
    config_path = process[process.index('--config') + 1]
    binary = docker('exec', args.native, 'sha256sum', '/proc/1/exe').decode().split()[0]
    from prepare_reference_image import BINARY_HASHES
    require(binary == BINARY_HASHES['cardano-node'], 'independent process is not the pinned genuine native node')
    config = decode_json(docker('exec', args.native, 'cat', config_path))
    genesis = load_json(args.genesis_hashes)
    require(set(genesis) == {'byron', 'shelley', 'alonzo', 'conway'}, 'reviewed preprod genesis roots missing')
    for era in genesis:
        path = config[era.title() + 'GenesisFile']
        path = path if path.startswith('/') else posixpath.join(posixpath.dirname(config_path), path)
        require(docker('exec', args.native, 'sha256sum', path).decode().split()[0] == genesis[era],
                'native process does not use reviewed preprod genesis')
    # Socket mapping is checked in memory; mount paths and process arguments are never retained.
    mappings = [Path(row['Source']) / native_socket[len(row['Destination'].rstrip('/')) + 1:]
                for row in native['Mounts'] if native_socket.startswith(row['Destination'].rstrip('/') + '/')]
    require(args.socket.resolve() in [path.resolve() for path in mappings], 'NtC socket is not mapped to the verified native process')
    with LocalStateQuery(None, str(args.socket), 1, host=True, version=23) as query:
        query.acquire([point['slot'], bytes.fromhex(point['hash'])])
        require(query.query_raw([3]) == [point['slot'], bytes.fromhex(point['hash'])],
                'native peer did not retain/acquire the actual Scarab-produced point')
    result = {'schemaVersion': 1, 'repository': args.repository, 'sourceCommit': args.source,
              'candidateSha256': args.candidate_sha256, 'jarSha256': jar['file']['sha256'],
              'imageDigest': manifest['image']['manifest']['digest'],
              'network': {'name': 'preprod', 'magic': 1, 'genesisSha256': genesis},
              'produced': dict(point, headerCbor=raw.hex(), forged=True, adopted=True),
              'native': {'binarySha256': binary, 'acquiredSlot': point['slot'], 'acquiredHash': point['hash'],
                         'queryPointMatched': True}, 'observedAt': datetime.now(timezone.utc).isoformat()}
    validate(result, args.repository, args.source, args.candidate_sha256, jar['file']['sha256'], result['imageDigest'])
    args.output.write_bytes(canonical(result) + b'\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ['repository', 'source', 'tag', 'channel', 'candidate-sha256', 'image-repository', 'forger', 'native', 'since']:
        parser.add_argument('--' + field, required=True)
    for field in ['candidate', 'header', 'genesis-hashes', 'socket', 'output']:
        parser.add_argument('--' + field, type=Path, required=True)
    observe(parser.parse_args())


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepare exact candidate subjects for the fixed attestation steps, without service writes."""
import argparse
import os
from pathlib import Path

from publication_candidate import checked_candidate, descriptors, release_checksums
from publication_common import canonical, file_digest, require


def prepare(root, source, tag, channel, candidate_hash):
    manifest = checked_candidate(root, source, tag, channel, candidate_hash)
    from publication_archive import pack
    archive = root.parent / 'candidate-transport.tar'
    packed = pack(root, archive)
    require(0 < packed['size'] < 2 * 1024 ** 3, 'complete transport exceeds GitHub individual asset limit; publication incomplete')
    (root / 'SHA256SUMS').write_bytes(release_checksums(root, manifest))
    # Signature names describe the retained paths, while digests bind independently downloaded bytes.
    signed = ['candidate.json', 'SHA256SUMS', *[row['path'] for row in descriptors(manifest)]]
    (root / 'signing-subjects.txt').write_text(''.join(file_digest(root / relative) + '  ' + str(root / relative) + '\n'
                                                    for relative in signed) + file_digest(archive) + '  ' + str(archive) + '\n')
    jar = next(row for row in manifest['subjects'] if row['kind'] == 'jar')
    image = next(row for row in manifest['subjects'] if row['kind'] == 'oci-archive')
    return {'jar': str(root / jar['file']['path']), 'jar-bom': str(root / jar['bom']['file']['path']),
            'image-bom': str(root / image['bom']['file']['path']), 'image-digest': manifest['image']['manifest']['digest']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    for name in ['source', 'tag', 'channel', 'candidate-sha256']:
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    result = prepare(args.candidate.resolve(), args.source, args.tag, args.channel, args.candidate_sha256)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(''.join(key + '=' + value + '\n' for key, value in result.items()))


if __name__ == '__main__':
    main()

# SPDX-License-Identifier: Apache-2.0
"""Stage a private writable immutable-store prefix for an offline production replay.

Mount --immutable read-only at --container-immutable in the replay container. Older chunk bodies
are symlinks into that mount; every index, format file and the newest chunk has a private inode.
"""
import argparse
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from pathlib import Path, PurePosixPath

from preview_failure_corpus import CaptureRefusal, read_block, read_index, sha


class StageProblem(Enum):
    OUTPUT_EXISTS = 'output-exists'
    OUTPUT_IN_ARCHIVE = 'output-in-archive'
    CONTAINER_PATH = 'container-path'
    PREFIX_LIMIT = 'prefix-limit'
    EMPTY_ARCHIVE = 'empty-archive'
    PREFIX_ORDER = 'prefix-order'
    CHUNK_BOUNDS = 'chunk-bounds'
    SOURCE_CHANGED = 'source-changed'


@dataclass
class StageRefusal(Exception):
    problem: StageProblem
    location: str
    detail: object = None


def require(condition, problem, location, detail=None):
    if not condition:
        raise StageRefusal(problem, str(location), detail)


def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for raw in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(raw)
    return digest.hexdigest()


def fingerprint(path):
    stat = path.stat()
    return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns


def stage(immutable, output, container_immutable=PurePosixPath('/input/immutable'), max_blocks=None):
    immutable = immutable.resolve(strict=True)
    output = output.absolute()
    require(not output.exists() and not output.is_symlink(), StageProblem.OUTPUT_EXISTS, output)
    require(not output.resolve().is_relative_to(immutable.parent), StageProblem.OUTPUT_IN_ARCHIVE, output)
    container_immutable = PurePosixPath(container_immutable)
    require(container_immutable.is_absolute() and '..' not in container_immutable.parts, StageProblem.CONTAINER_PATH, container_immutable)
    require(max_blocks is None or (type(max_blocks) is int and max_blocks > 0), StageProblem.PREFIX_LIMIT, max_blocks)
    selected = []
    count = 0
    previous = None
    for index in sorted(immutable.glob('*.index')):
        index_fingerprint = fingerprint(index)
        rows, index_sha = read_index(index)
        require(fingerprint(index) == index_fingerprint, StageProblem.SOURCE_CHANGED, index)
        if not rows:
            continue
        limit = len(rows) if max_blocks is None else min(len(rows), max_blocks - count)
        chosen = rows[:limit]
        require(previous is None or (chosen[0].block == previous.block + 1 and chosen[0].slot > previous.slot), StageProblem.PREFIX_ORDER, index)
        if previous is None:
            require(chosen[0].block == 0, StageProblem.PREFIX_ORDER, index, chosen[0].block)
        previous = chosen[-1]
        chunk = index.with_suffix('.chunk')
        chunk_fingerprint = fingerprint(chunk)
        end = chosen[-1].offset + chosen[-1].length
        require(chunk_fingerprint[2] >= end, StageProblem.CHUNK_BOUNDS, chunk, end)
        selected.append((index, [chosen[0], chosen[-1]], index_sha, index_fingerprint, chunk_fingerprint, len(chosen)))
        count += len(chosen)
        if max_blocks is not None and count == max_blocks:
            break
    require(bool(selected), StageProblem.EMPTY_ARCHIVE, immutable)
    require(max_blocks is None or count == max_blocks, StageProblem.PREFIX_LIMIT, immutable, {'requested': max_blocks, 'available': count})
    format_path = immutable / 'format'
    format_fingerprint = fingerprint(format_path)
    format_raw = format_path.read_bytes()
    require(fingerprint(format_path) == format_fingerprint, StageProblem.SOURCE_CHANGED, format_path)
    # Authenticate both prefix endpoints from original bytes, reusing the exporter's CBOR/header checks.
    for index, rows, _, _, _, _ in (selected[0], selected[-1]):
        with index.with_suffix('.chunk').open('rb') as source:
            for row in (rows[0], rows[-1]):
                read_block(source, row)
    output.mkdir(parents=True)
    destination = output / 'immutable'
    destination.mkdir()
    (destination / 'format').write_bytes(format_raw)
    files = []
    for number, (index, rows, source_sha, index_fingerprint, chunk_fingerprint, entry_count) in enumerate(selected):
        raw_index = index.read_bytes()
        require(sha(raw_index) == source_sha and fingerprint(index) == index_fingerprint, StageProblem.SOURCE_CHANGED, index)
        staged_index = raw_index[:8 + 68 * entry_count]
        target_index = destination / index.name
        target_index.write_bytes(staged_index)
        chunk = index.with_suffix('.chunk')
        target_chunk = destination / chunk.name
        newest = number == len(selected) - 1
        end = rows[-1].offset + rows[-1].length
        row = {'index': index.name, 'sourceIndexSha256': source_sha, 'stagedIndexSha256': sha(staged_index),
               'selectedEntries': entry_count, 'sourceEntries': (len(raw_index) - 8) // 68,
               'first': rows[0].public(), 'last': rows[-1].public(), 'chunk': chunk.name,
               'sourceChunkBytes': chunk_fingerprint[2], 'selectedChunkBytes': end,
               'sourceIndexIdentity': list(index_fingerprint), 'sourceChunkIdentity': list(chunk_fingerprint)}
        if newest:
            digest = hashlib.sha256()
            prefix_digest = hashlib.sha256()
            remaining = end
            with chunk.open('rb') as source, target_chunk.open('xb') as target:
                for raw in iter(lambda: source.read(1024 * 1024), b''):
                    digest.update(raw)
                    copied = raw[:remaining]
                    target.write(copied)
                    prefix_digest.update(copied)
                    remaining -= len(copied)
            require(remaining == 0, StageProblem.CHUNK_BOUNDS, chunk, remaining)
            row.update({'mode': 'private-copy', 'sourceChunkSha256': digest.hexdigest(), 'stagedChunkSha256': prefix_digest.hexdigest()})
        else:
            target = container_immutable / chunk.name
            target_chunk.symlink_to(str(target))
            row.update({'mode': 'read-only-container-symlink', 'target': str(target)})
        require(fingerprint(chunk) == chunk_fingerprint, StageProblem.SOURCE_CHANGED, chunk)
        files.append(row)
    require(fingerprint(format_path) == format_fingerprint, StageProblem.SOURCE_CHANGED, format_path)
    manifest = {'schema': 1, 'purpose': 'production-chain-storage-offline-prefix', 'sourceImmutable': str(immutable),
                'outputDb': str(output), 'containerImmutableReadOnlyMount': str(container_immutable),
                'blocks': count, 'first': selected[0][1][0].public(), 'last': selected[-1][1][-1].public(),
                'format': {'sha256': sha(format_raw), 'sourceIdentity': list(format_fingerprint)}, 'files': files,
                'safety': {'indexesPrivate': True, 'formatPrivate': True, 'newestChunkPrivate': True, 'olderChunksRequireReadOnlyMount': True}}
    (output / 'staging-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--immutable', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--container-immutable', default='/input/immutable', type=PurePosixPath)
    parser.add_argument('--max-blocks', type=int)
    args = parser.parse_args()
    try:
        result = stage(args.immutable, args.output, args.container_immutable, args.max_blocks)
    except (StageRefusal, CaptureRefusal) as refusal:
        print(json.dumps({'problem': refusal.problem.value, 'location': refusal.location, 'detail': refusal.detail}))
        return 1
    print(json.dumps({'blocks': result['blocks'], 'first': result['first'], 'last': result['last']}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

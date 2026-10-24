# SPDX-License-Identifier: Apache-2.0
"""Export contiguous native-V5 replay windows for Preview's historical 242 invalid transactions.

All archive inputs are read-only. Original CBOR spans are copied, never reconstructed.
The CLI renders structured capture refusals; no diagnostic text is used for decisions.
"""
import argparse
from collections import Counter
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from pathlib import Path
import struct
import zlib

from devnet import cborspan


class Problem(Enum):
    INDEX_FORMAT = 'index-format'
    INDEX_ORDER = 'index-order'
    BLOCK_BOUNDS = 'block-bounds'
    BLOCK_CRC = 'block-crc'
    HEADER_HASH = 'header-hash'
    HEADER_POINT = 'header-point'
    CBOR = 'cbor'
    BLOCK_SHAPE = 'block-shape'
    IDENTITY = 'identity'
    COVERAGE = 'coverage'
    SNAPSHOT = 'snapshot'
    CHECKPOINT = 'checkpoint'
    OUTPUT_EXISTS = 'output-exists'


@dataclass
class CaptureRefusal(Exception):
    problem: Problem
    location: str
    detail: object = None


def require(condition, problem, location, detail=None):
    if not condition:
        raise CaptureRefusal(problem, str(location), detail)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def head(raw, pos):
    try:
        return cborspan.head(raw, pos)
    except cborspan.Malformed:
        raise CaptureRefusal(Problem.CBOR, str(pos)) from None


def item_end(raw, pos):
    try:
        return cborspan.item_end(raw, pos)
    except cborspan.Malformed:
        raise CaptureRefusal(Problem.CBOR, str(pos)) from None


def children(raw, pos, expected):
    major, _, _ = head(raw, pos)
    require(major == expected, Problem.BLOCK_SHAPE, pos, major)
    try:
        return cborspan.children(raw, pos)
    except cborspan.Malformed:
        raise CaptureRefusal(Problem.CBOR, str(pos)) from None


def unsigned(raw, at):
    major, value, _ = head(raw, at)
    require(major == 0 and value is not None, Problem.BLOCK_SHAPE, at)
    return value


@dataclass(frozen=True)
class Record:
    chunk: str
    offset: int
    length: int
    crc: int
    slot: int
    block: int
    hash: str

    def public(self):
        return {k: getattr(self, k) for k in ('chunk', 'offset', 'length', 'slot', 'block', 'hash')}


def read_index(path):
    raw = path.read_bytes()
    require(len(raw) >= 8 and (len(raw) - 8) % 68 == 0 and raw[:8] in (b'CSLIDX01', b'SCRBIDX2'), Problem.INDEX_FORMAT, path)
    rows = []
    previous_end = 0
    previous = None
    for at in range(8, len(raw), 68):
        offset, length, crc, slot, block = struct.unpack_from('>QIIQQ', raw, at)
        require(length > 0 and offset == previous_end and raw[at + 64] == 0 and raw[at + 65:at + 68] == b'\0\0\0', Problem.INDEX_ORDER, path, at)
        require(previous is None or (slot > previous.slot and block == previous.block + 1), Problem.INDEX_ORDER, path, at)
        row = Record(path.with_suffix('.chunk').name, offset, length, crc, slot, block, raw[at + 32:at + 64].hex())
        rows.append(row)
        previous, previous_end = row, offset + length
    return rows, sha(raw)


def read_block(stream, record):
    stream.seek(record.offset)
    raw = stream.read(record.length)
    require(len(raw) == record.length, Problem.BLOCK_BOUNDS, record.chunk, record.offset)
    require(zlib.crc32(raw) == record.crc, Problem.BLOCK_CRC, record.chunk, record.offset)
    require(item_end(raw, 0) == len(raw), Problem.BLOCK_SHAPE, record.chunk, record.offset)
    outer = children(raw, 0, 4)
    require(len(outer) == 2, Problem.BLOCK_SHAPE, record.chunk)
    era = unsigned(raw, outer[0][0])
    require(era in (5, 6, 7), Problem.BLOCK_SHAPE, record.chunk, era)
    parts = children(raw, outer[1][0], 4)
    require(len(parts) == 5, Problem.BLOCK_SHAPE, record.chunk)
    start, end = parts[0]
    require(hashlib.blake2b(raw[start:end], digest_size=32).hexdigest() == record.hash, Problem.HEADER_HASH, record.chunk, record.slot)
    header = children(raw, start, 4)
    require(len(header) == 2, Problem.BLOCK_SHAPE, record.chunk, record.slot)
    fields = children(raw, header[0][0], 4)
    require(len(fields) >= 2, Problem.BLOCK_SHAPE, record.chunk, record.slot)
    require(unsigned(raw, fields[0][0]) == record.block and unsigned(raw, fields[1][0]) == record.slot,
            Problem.HEADER_POINT, record.chunk, record.slot)
    return raw, era, parts


def invalid_transactions(raw, era, parts, record):
    at = parts[4][0]
    major, tag, after = head(raw, at)
    if major == 6:
        require(tag == 258, Problem.BLOCK_SHAPE, at, tag)
        at = after
    invalid = [unsigned(raw, start) for start, _ in children(raw, at, 4)]
    require(len(set(invalid)) == len(invalid), Problem.BLOCK_SHAPE, record.chunk, invalid)
    if not invalid:
        return []
    bodies = children(raw, parts[1][0], 4)
    witnesses = children(raw, parts[2][0], 4)
    require(len(bodies) == len(witnesses), Problem.BLOCK_SHAPE, record.chunk)
    auxiliary = children(raw, parts[3][0], 5)
    aux = {}
    for i in range(0, len(auxiliary), 2):
        key = unsigned(raw, auxiliary[i][0])
        require(key not in aux, Problem.BLOCK_SHAPE, record.chunk, key)
        a, b = auxiliary[i + 1]
        aux[key] = raw[a:b].hex()
    result = []
    for txindex in invalid:
        require(txindex < len(bodies), Problem.BLOCK_SHAPE, record.chunk, txindex)
        a, b = bodies[txindex]
        c, d = witnesses[txindex]
        body, witness = raw[a:b], raw[c:d]
        result.append({**record.public(), 'sha256': sha(raw), 'era': {5: 'Alonzo', 6: 'Babbage', 7: 'Conway'}[era],
                       'epoch': record.slot // 86400, 'txIndex': txindex, 'txId': hashlib.blake2b(body, digest_size=32).hexdigest(),
                       'isValid': False, 'body': body.hex(), 'witnesses': witness.hex(), 'auxiliary': aux.get(txindex),
                       'bodySha256': sha(body), 'witnessesSha256': sha(witness)})
    return result


def load_golden(path):
    value = json.loads(path.read_text())
    rows = value['transactions']
    require(value['schema'] == 1 and value['network'] == 'preview' and len(rows) == 242, Problem.COVERAGE, path)
    require(len({r['txId'] for r in rows}) == 242, Problem.COVERAGE, path)
    require(rows == sorted(rows, key=lambda r: (r['slot'], r['txIndex'])), Problem.IDENTITY, path)
    return value


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def export(immutable, snapshots, checkpoints, golden_path, output, selected_epochs=None):
    golden = load_golden(golden_path)
    require(not output.exists(), Problem.OUTPUT_EXISTS, output)
    expected = [r for r in golden['transactions'] if selected_epochs is None or r['epoch'] in selected_epochs]
    require(bool(expected) and (selected_epochs is None or {r['epoch'] for r in expected} == set(selected_epochs)), Problem.COVERAGE, 'selected-epochs')
    epochs = sorted({r['epoch'] for r in expected})
    snapshot_rows = []
    for path in snapshots.glob('snapshot-*-*.bin'):
        _, slot, block = path.stem.split('-')
        snapshot_rows.append((int(slot), int(block), path))
    plans = []
    for epoch in epochs:
        start, end = epoch * 86400, (epoch + 1) * 86400
        candidates = [r for r in snapshot_rows if r[0] < start]
        require(bool(candidates), Problem.SNAPSHOT, epoch)
        slot, block, path = max(candidates)
        checkpoint = checkpoints / path.stem
        with path.open('rb') as stream:
            # archived corpora are V5; databases written since #395 are V7
            require(stream.read(8) in (b'SCRBSNP5', b'SCRBSNP7'), Problem.SNAPSHOT, path)
        require(checkpoint.is_dir(), Problem.CHECKPOINT, checkpoint)
        plans.append({'epoch': epoch, 'anchorSlot': slot, 'anchorBlock': block, 'snapshot': str(path.resolve()),
                      'checkpoint': str(checkpoint.resolve()), 'endSlotExclusive': end, 'records': [], 'invalid': [],
                      'snapshotSha256': sha(path.read_bytes())})
    # Index metadata is small; block payloads are read only for selected intervals.
    output.mkdir(parents=True)
    indexes = []
    found_tip = None
    for index in sorted(immutable.glob('*.index')):
        with index.open('rb') as probe:
            prefix = probe.read(76)
            if len(prefix) == 8:
                require(prefix in (b'CSLIDX01', b'SCRBIDX2'), Problem.INDEX_FORMAT, index)
                continue
            require(len(prefix) == 76 and (index.stat().st_size - 8) % 68 == 0, Problem.INDEX_FORMAT, index)
            probe.seek(-68, 2)
            last = probe.read(68)
        first_slot, last_slot = struct.unpack_from('>Q', prefix, 24)[0], struct.unpack_from('>Q', last, 16)[0]
        if not any(first_slot < p['endSlotExclusive'] and last_slot > p['anchorSlot'] for p in plans) and not first_slot <= golden['originalFinalTip']['slot'] <= last_slot:
            continue
        rows, index_sha = read_index(index)
        relevant = [r for r in rows if any(p['anchorSlot'] < r.slot < p['endSlotExclusive'] for p in plans) or r.slot == golden['originalFinalTip']['slot']]
        if not relevant:
            continue
        indexes.append({'name': index.name, 'sha256': index_sha})
        with index.with_suffix('.chunk').open('rb') as stream:
            for row in relevant:
                raw, era, parts = read_block(stream, row)
                public = {**row.public(), 'sha256': sha(raw)}
                if row.slot == golden['originalFinalTip']['slot']:
                    require(all(public[k] == golden['originalFinalTip'][k] for k in ('slot', 'block', 'hash')), Problem.IDENTITY, index, row.slot)
                    found_tip = public
                invalid = invalid_transactions(raw, era, parts, row)
                for plan in plans:
                    if plan['anchorSlot'] < row.slot < plan['endSlotExclusive']:
                        plan['records'].append(public)
                        plan['invalid'].extend(invalid)
    require(found_tip is not None, Problem.COVERAGE, 'original-final-tip')
    windows = []
    for plan in plans:
        records = plan.pop('records')
        invalid = plan.pop('invalid')
        require(bool(records) and records[0]['block'] == plan['anchorBlock'] + 1, Problem.INDEX_ORDER, plan['epoch'])
        require(all(b['block'] == a['block'] + 1 and b['slot'] > a['slot'] for a, b in zip(records, records[1:])), Problem.INDEX_ORDER, plan['epoch'])
        actual = [{k: row[k] for k in expected[0]} for row in invalid]
        window_expected = [r for r in golden['transactions'] if plan['anchorSlot'] < r['slot'] < plan['endSlotExclusive']]
        require(actual == window_expected, Problem.IDENTITY, plan['epoch'])
        targets = [r for r in invalid if r['epoch'] == plan['epoch']]
        stem = f"epoch-{plan['epoch']:04d}"
        capture = output / stem
        capture.mkdir()
        (capture / 'immutable').symlink_to(immutable.resolve(), target_is_directory=True)
        record_file, invalid_file, target_file = capture / 'records.jsonl', capture / 'invalid.jsonl', capture / 'targets.jsonl'
        for path, entries in ((record_file, records), (invalid_file, invalid), (target_file, targets)):
            path.write_text(''.join(json.dumps(row, separators=(',', ':')) + '\n' for row in entries))
        windows.append({**plan, 'capture': str(capture.resolve()), 'records': str(record_file.resolve()), 'recordsSha256': sha(record_file.read_bytes()),
                        'expectedInvalidTransactions': str(invalid_file.resolve()), 'invalidSha256': sha(invalid_file.read_bytes()),
                        'targetInvalidTransactions': str(target_file.resolve()), 'targetsSha256': sha(target_file.read_bytes()),
                        'blocks': len(records), 'failedTransactions': len(invalid), 'targetTransactions': len(targets)})
    manifest = {'schema': 1, 'network': 'preview', 'immutable': str(immutable.resolve()), 'goldenSha256': sha(golden_path.read_bytes()),
                'originalFinalTip': found_tip, 'indexes': indexes, 'windows': windows,
                'failedTransactions': len({r['txId'] for r in golden['transactions'] if any(p['anchorSlot'] < r['slot'] < p['endSlotExclusive'] for p in windows)}),
                'targetTransactions': sum(p['targetTransactions'] for p in windows), 'byEra': dict(Counter(r['era'] for r in expected))}
    write_json(output / 'manifest.json', manifest)
    batch = [{'capture': w['capture'], 'snapshot': w['snapshot'], 'checkpoint': w['checkpoint'],
              'output': str((output / 'replay' / f"epoch-{w['epoch']:04d}").resolve()),
              'expectedFailures': w['expectedInvalidTransactions']} for w in windows]
    (output / 'windows.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in batch))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('immutable', 'snapshots', 'checkpoints', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--epochs', nargs='+', type=int, help='Optional failing-epoch subset for a bounded smoke replay')
    parser.add_argument('--golden', type=Path, default=Path(__file__).resolve().parents[1] / 'fixtures/preview/phase2-failures/identities.json')
    args = parser.parse_args()
    try:
        manifest = export(args.immutable, args.snapshots, args.checkpoints, args.golden, args.output, args.epochs)
    except CaptureRefusal as refusal:
        print(json.dumps({'problem': refusal.problem.value, 'location': refusal.location, 'detail': refusal.detail}))
        return 1
    print(json.dumps({'windows': len(manifest['windows']), 'failedTransactions': manifest['failedTransactions'], 'byEra': manifest['byEra']}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

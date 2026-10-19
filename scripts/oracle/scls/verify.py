# SPDX-License-Identifier: Apache-2.0
"""Independent bounded fixture verifier; not the production streaming importer."""
from pathlib import Path
from hashlib import blake2b, sha256
import json
import argparse


def require(condition):
    if not condition:
        raise ValueError('invalid SCLS reference fixture structure or commitment')


def digest(b):
    return blake2b(b, digest_size=28).digest()


def root(leaves):
    if not leaves:
        return digest(b'')
    nodes = list(leaves)
    while len(nodes) > 1:
        nodes = [digest(b'\0' + nodes[i] + nodes[i+1]) if i+1 < len(nodes) else nodes[i]
                 for i in range(0, len(nodes), 2)]
    return nodes[0]


class Cursor:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        if n < 0 or self.pos+n > len(self.data):
            raise ValueError('truncated fixture')
        b = self.data[self.pos:self.pos+n]
        self.pos += n
        return b

    def uint(self, n, order='big'):
        return int.from_bytes(self.take(n), order)

    def text(self):
        return self.take(self.uint(4)).decode('utf-8', errors='strict')

    def end(self):
        if self.pos != len(self.data):
            raise ValueError('trailing fixture data')


def verify(path):
    raw = path.read_bytes()
    file = Cursor(raw)
    chunks, entries, ns_leaves = {}, [], {}
    last_namespace = ''
    manifest = None
    while file.pos < len(raw):
        start = file.pos
        size = file.uint(4)
        frame = Cursor(file.take(size))
        tag = frame.uint(1)
        if start == 0:
            require(tag == 0 and frame.take(4) == b'SCLS' and frame.uint(4) == 1)
        elif tag == 0x10:
            require(manifest is None)
            seq, compression, namespace = frame.uint(8), frame.uint(1), frame.text()
            require(compression == 0 and namespace >= last_namespace)
            last_namespace = namespace
            key_size = frame.uint(4)
            data = Cursor(frame.take(size-frame.pos-32))
            expected_count, expected_chunk_hash = frame.uint(4), frame.take(28)
            leaves = []
            last_key = None
            while data.pos < len(data.data):
                entry = Cursor(data.take(data.uint(4)))
                key, value = entry.take(key_size), entry.take(len(entry.data)-key_size)
                require(last_key is None or last_key < key)
                last_key = key
                leaf = digest(b'\1'+namespace.encode()+key+value)
                leaves.append(leaf)
                entries.append((namespace, key.hex(), value.hex()))
                entry.end()
            require(len(leaves) == expected_count)
            require(digest(b''.join(leaves)) == expected_chunk_hash)
            prior_chunks, prior_count = chunks.get(namespace, (0, 0))
            require(seq == prior_chunks+1)
            chunks[namespace] = (seq, prior_count+len(leaves))
            ns_leaves.setdefault(namespace, []).extend(leaves)
        elif tag == 1:
            require(manifest is None)
            slot = frame.uint(8)
            total_entries, total_chunks = frame.uint(8), frame.uint(8)
            summary = [frame.text() for _ in range(3)]
            manifest_ns = []
            while True:
                length = frame.uint(4)
                if length == 0:
                    break
                count, n_chunks = frame.uint(8), frame.uint(8)
                namespace, expected_root = frame.take(length).decode(), frame.take(28)
                require(not manifest_ns or manifest_ns[-1]['namespace'] < namespace)
                require(chunks.get(namespace, (0, 0)) == (n_chunks, count))
                require(root(ns_leaves.get(namespace, [])) == expected_root)
                manifest_ns.append(dict(namespace=namespace, entries=count, chunks=n_chunks, root=expected_root.hex()))
            previous, expected_root, footer = frame.uint(8), frame.take(28), frame.uint(4)
            require(previous == 0 and footer == size)
            require(total_entries == len(entries) == sum(v['entries'] for v in manifest_ns))
            require(total_chunks == sum(v[0] for v in chunks.values()))
            require(set(chunks) <= {v['namespace'] for v in manifest_ns})
            require(root([digest(b'\1'+bytes.fromhex(v['root'])) for v in manifest_ns]) == expected_root)
            manifest = dict(slot=slot, entries=total_entries, chunks=total_chunks, root=expected_root.hex(), summary=summary, namespaces=manifest_ns)
        else:
            raise ValueError(f'unsupported fixture frame {tag}')
        frame.end()
    require(manifest is not None)
    return dict(sha256=sha256(raw).hexdigest(), size=len(raw), **manifest), entries


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    parser.add_argument('--manifest-out', type=Path)
    parser.add_argument('--entries-out', type=Path)
    args = parser.parse_args()
    path = args.file
    manifest, entries = verify(path)
    require(manifest['slot'] == 4593)
    if args.manifest_out is not None:
        args.manifest_out.write_text(json.dumps(manifest, indent=2)+'\n')
    if args.entries_out is not None:
        args.entries_out.write_text(''.join('\t'.join(e)+'\n' for e in entries))
    print(json.dumps(manifest, indent=2))

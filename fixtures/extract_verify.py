#!/usr/bin/env python3
"""Extract or verify pinned Haskell golden slices; Python 3 standard library only.

Download the original corpus separately using the command in fixture-provenance.md.
This script never uses network access. Default operation only verifies files.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path

COMMIT = "226b002d5b5e83e24355f8a28ab214f3259eabda"
SOURCE_PATH = "eras/conway/impl/golden/translations.cbor"
SOURCE_SHA256 = "5d02c08e392f135fa56ed3177d1987ef0d90f8274f9f37d450d4f4d1d4c9cc7a"
CASES = (44, 57, 42)


@dataclass
class Node:
    major: int
    value: object
    start: int
    end: int
    indefinite: bool = False


class CborReader:
    """Independent structural reader retaining source spans, not a ledger codec."""

    def __init__(self, data: bytes):
        self.data = data

    def read(self, pos: int = 0, depth: int = 0) -> Node:
        if depth > 512:
            raise ValueError("CBOR nesting exceeds extraction limit")
        start = pos
        if pos >= len(self.data):
            raise ValueError("Truncated CBOR header")
        initial = self.data[pos]
        pos += 1
        major, additional = initial >> 5, initial & 31
        indefinite = additional == 31
        if additional < 24:
            argument = additional
        elif additional in (24, 25, 26, 27):
            size = 1 << (additional - 24)
            if pos + size > len(self.data):
                raise ValueError("Truncated CBOR argument")
            argument = int.from_bytes(self.data[pos:pos + size], "big")
            pos += size
        elif indefinite and major in (2, 3, 4, 5):
            argument = None
        else:
            raise ValueError(f"Unsupported or invalid CBOR header at {start}")

        if major in (0, 1, 7):
            value = argument
        elif major in (2, 3) and not indefinite:
            end = pos + argument
            if end > len(self.data):
                raise ValueError("Truncated CBOR string")
            value, pos = self.data[pos:end], end
        elif major in (2, 3, 4, 5):
            children = []
            count = None if indefinite else argument * (2 if major == 5 else 1)
            while count is None or len(children) < count:
                if pos >= len(self.data):
                    raise ValueError("Truncated CBOR container")
                if indefinite and self.data[pos] == 255:
                    pos += 1
                    break
                child = self.read(pos, depth + 1)
                if major in (2, 3) and (child.major != major or child.indefinite):
                    raise ValueError("Invalid indefinite string chunk")
                children.append(child)
                pos = child.end
            if major == 5 and len(children) % 2:
                raise ValueError("Odd number of CBOR map items")
            value = children
        elif major == 6:
            value = self.read(pos, depth + 1)
            pos = value.end
        else:
            raise ValueError(f"Unsupported major type {major}")
        return Node(major, value, start, pos, indefinite)


def walk(node: Node):
    yield node
    if isinstance(node.value, list):
        for child in node.value:
            yield from walk(child)
    elif isinstance(node.value, Node):
        yield from walk(node.value)


def require(condition: bool, message: str):
    if not condition:
        raise ValueError(message)


def package(corpus: bytes) -> dict[str, bytes]:
    require(hashlib.sha256(corpus).hexdigest() == SOURCE_SHA256,
            "Wrong corpus SHA256: refusing to change or verify expectations")
    root = CborReader(corpus).read()
    require(root.end == len(corpus), "Trailing corpus bytes")
    require(root.major == 4 and root.indefinite and len(root.value) == 100,
            "Unexpected corpus shape")
    rows = ["# name\tkind\tcbor_hex\texpected_hash"]
    records = []
    files = {}
    for index in CASES:
        case = root.value[index]
        require(case.major == 4 and len(case.value) == 5, "Bad case shape")
        version, language, _, tx, expected = case.value
        lang = language.value
        require(lang in (0, 1, 2), "Unknown language")
        require(tx.major == 4 and len(tx.value) == 4, "Bad transaction envelope")
        body = tx.value[0]
        require(body.major == 5, "Transaction body is not a map")
        require(expected.major == 4 and len(expected.value) == 2,
                "Bad VersionedTxInfo shape")
        require(expected.value[0].value == lang, "Mismatched language constructor")
        tx_info = expected.value[1]
        tx_id = tx_info.value[10 if lang == 0 else 12]
        require(tx_id.major == 4 and len(tx_id.value) == 2 and
                tx_id.value[0].value == 0 and tx_id.value[1].major == 2 and
                len(tx_id.value[1].value) == 32, "Bad expected TxId newtype")
        # Crucially, obtain the expectation from the Haskell TxInfo bytes first.
        expected_hash = tx_id.value[1].value.hex()
        record = {
            "case_index": index,
            "language": f"PlutusV{lang + 1}",
            "protocol_major": version.value[0].value,
            "expected_txid": expected_hash,
            "txid_corpus_byte_range": [tx_id.start, tx_id.end],
            "txid_encoding_hex": corpus[tx_id.start:tx_id.end].hex(),
            "artifacts": [],
        }
        for kind, node, suffix in (("transaction-body", body, "body"),
                                   ("transaction-envelope", tx, "tx")):
            name = f"conway-translation-{index:03d}-{suffix}"
            raw = corpus[node.start:node.end]
            path = f"raw/{name}.cbor"
            files[path] = raw
            rows.append("\t".join((name, kind, raw.hex(), expected_hash)))
            record["artifacts"].append({
                "path": path,
                "kind": kind,
                "bytes": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "corpus_byte_range": [node.start, node.end],
                "indefinite_container_count": sum(n.indefinite for n in walk(node)),
            })
            if kind == "transaction-body":
                # Packaging sanity check, never the source of expected_hash.
                actual = hashlib.blake2b(raw, digest_size=32).hexdigest()
                require(actual == expected_hash, f"Haskell TxId mismatch for case {index}")
        records.append(record)
    manifest = {
        "source_commit": COMMIT,
        "source_path": SOURCE_PATH,
        "source_sha256": SOURCE_SHA256,
        "byte_ranges": "zero-based half-open offsets in uncompressed corpus",
        "records": records,
    }
    files["cardano-golden.tsv"] = ("\n".join(rows) + "\n").encode()
    files["extraction-manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path, help="Downloaded uncompressed translations.cbor")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent,
                        help="Fixture directory; defaults to this script's directory")
    parser.add_argument("--write", action="store_true",
                        help="Regenerate the eight derived fixture files instead of verifying")
    args = parser.parse_args()
    generated = package(args.corpus.read_bytes())
    for relative, expected in generated.items():
        destination = args.output / relative
        if args.write:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(expected)
        else:
            require(destination.read_bytes() == expected, f"Fixture differs: {relative}")
    action = "Regenerated" if args.write else "Verified"
    print(f"{action} 6 raw slices, 6 TSV rows, manifest offsets, and 3 Haskell-recorded TxIds.")


if __name__ == "__main__":
    main()

# SPDX-License-Identifier: Apache-2.0
"""Original-byte synthetic Shelley/Allegra/Mary auxiliary inputs; no signed admission claims.

Every row is an input only. The verdicts come from running the unchanged released Haskell ledger
(`run.py`); nothing here predicts them. Eras are paired with their historical protocol version
(Shelley 2, Allegra 3, Mary 4), the way the node's era decoder pairs them.
"""
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'scripts'))
from devnet.cbor import head

ERAS = (('Shelley', 2), ('Allegra', 3), ('Mary', 4))
H28 = b'\x11' * 28


def u(n):
    return head(0, n)


def neg(n):
    return head(1, n)


def b(x):
    return head(2, len(x)) + x


def t(x):
    raw = x.encode()
    return head(3, len(raw)) + raw


def arr(*items):
    return head(4, len(items)) + b''.join(items)


def mp(*pairs):
    return head(5, len(pairs)) + b''.join(k + v for k, v in pairs)


def tag(n, item):
    return head(6, n) + item


def iarr(*items):
    return b'\x9f' + b''.join(items) + b'\xff'


def imp(*pairs):
    return b'\xbf' + b''.join(k + v for k, v in pairs) + b'\xff'


def ibytes(*chunks):
    return b'\x5f' + b''.join(b(c) for c in chunks) + b'\xff'


def itext(*chunks):
    return b'\x7f' + b''.join(t(c) for c in chunks) + b'\xff'


def meta(*values):
    """A metadata map {0: v0, 1: v1, ...}."""
    return mp(*[(u(i), v) for i, v in enumerate(values)])


def nested(depth, leaf=u(1)):
    return b'\x81' * depth + leaf


def sig(h=H28):
    return arr(u(0), b(h))


def native_cases():
    sc = sig()
    return [
        ('native-sig', sc), ('native-all-empty', arr(u(1), arr())), ('native-any-empty', arr(u(2), arr())),
        ('native-all', arr(u(1), arr(sc, sc))), ('native-any', arr(u(2), arr(sc))),
        ('native-mof-0', arr(u(3), u(0), arr())), ('native-mof-2-of-1', arr(u(3), u(2), arr(sc))),
        ('native-mof-negative', arr(u(3), neg(0), arr(sc))),
        ('native-before-0', arr(u(4), u(0))), ('native-after-max', arr(u(5), u(2**64 - 1))),
        ('native-before-2pow64-bignum', arr(u(4), tag(2, b(b'\x01' + b'\x00' * 8)))),
        ('native-before-negative', arr(u(4), neg(0))), ('native-after-wrapped-tag', arr(u(2**32), b(H28))),
        ('native-unknown-tag-6', arr(u(6), b(H28))), ('native-sig-hash-27', sig(b'\x11' * 27)),
        ('native-sig-hash-29', sig(b'\x11' * 29)), ('native-sig-hash-text', arr(u(0), t('x' * 28))),
        ('native-sig-missing-hash', arr(u(0))), ('native-sig-extra', arr(u(0), b(H28), u(1))),
        ('native-all-not-list', arr(u(1), b(H28))), ('native-before-extra', arr(u(4), u(1), u(2))),
        ('native-as-map', mp((u(0), b(H28)))), ('native-as-bytes', b(H28)),
        ('native-nested-all-any-mof', arr(u(1), arr(arr(u(2), arr(arr(u(3), u(1), arr(sc, arr(u(4), u(7)))))), sc))),
        ('native-indefinite-inner-all', arr(u(1), iarr(sc, sc))),
        ('native-indefinite-inner-any-nested', arr(u(2), iarr(arr(u(1), iarr(sc))))),
        ('native-indefinite-inner-mof', arr(u(3), u(1), iarr(sc))),
        ('native-nested-invalid-inner-tag', arr(u(1), arr(sc, arr(u(6), b(H28))))),
        ('native-nested-invalid-inner-hash', arr(u(2), arr(arr(u(1), arr(sig(b'\x11' * 27)))))),
        ('native-nested-invalid-inner-mof', arr(u(3), u(1), arr(sc, arr(u(3), u(1))))),
        ('native-nested-invalid-deep', arr(u(1), arr(arr(u(2), arr(arr(u(3), u(1), arr(arr(u(4)))))))))
    ]


def metadata_cases():
    """Each value is a complete metadata map used as the whole auxiliary data."""
    big = 'é'
    return [
        ('meta-empty', mp()), ('meta-int', meta(u(7))), ('meta-nested', meta(arr(b(b'data'), t('value'), mp((u(1), arr(neg(0), u(2))))))),
        ('meta-label-max', mp((u(2**64 - 1), u(0)))), ('meta-label-2pow64-bignum', mp((tag(2, b(b'\x01' + b'\x00' * 8)), u(0)))),
        ('meta-label-negative', mp((neg(0), u(0)))), ('meta-label-text', mp((t('label'), u(0)))),
        ('meta-label-bytes', mp((b(b'\x00'), u(0)))), ('meta-label-null', mp((b'\xf6', u(0)))),
        ('meta-label-nonminimal-1800', mp((b'\x18\x00', u(0)))), ('meta-label-nonminimal-1900', mp((b'\x19\x00\x01', u(0)))),
        ('meta-label-duplicate', mp((u(0), u(1)), (u(0), u(2)))),
        ('meta-label-duplicate-invalid-first', mp((u(0), b'\xf5'), (u(0), u(2)))),
        ('meta-label-unsorted', mp((u(2), u(0)), (u(1), u(0)))),
        ('meta-top-indefinite-map', imp((u(0), u(1)), (u(1), u(2)))),
        ('meta-int-max', meta(u(2**64 - 1))), ('meta-int-min', meta(neg(2**64 - 1))),
        ('meta-int-2pow64-bignum', meta(tag(2, b(b'\x01' + b'\x00' * 8)))),
        ('meta-int-min-minus-one-bignum', meta(tag(3, b(b'\x01' + b'\x00' * 8)))),
        ('meta-int-small-bignum', meta(tag(2, b(b'\x01')))), ('meta-int-nonminimal-1800', meta(b'\x18\x00')),
        ('meta-null', meta(b'\xf6')), ('meta-true', meta(b'\xf5')), ('meta-undefined', meta(b'\xf7')),
        ('meta-simple-16', meta(b'\xf0')), ('meta-float16', meta(b'\xf9\x3c\x00')),
        ('meta-float64', meta(b'\xfb\x3f\xf0\x00\x00\x00\x00\x00\x00')),
        ('meta-tag-24', meta(tag(24, b(b'\x01')))), ('meta-tag-259', meta(tag(259, mp()))),
        ('meta-bytes-0', meta(b(b''))), ('meta-bytes-63', meta(b(b'x' * 63))), ('meta-bytes-64', meta(b(b'x' * 64))),
        ('meta-bytes-65', meta(b(b'x' * 65))), ('meta-bytes-64-nonminimal-head', meta(b'\x59\x00\x40' + b'x' * 64)),
        ('meta-bytes-65-nonminimal-head', meta(b'\x59\x00\x41' + b'x' * 65)),
        ('meta-bytes-chunked-64', meta(ibytes(b'x' * 32, b'y' * 32))), ('meta-bytes-chunked-65', meta(ibytes(b'x' * 32, b'y' * 33))),
        ('meta-bytes-chunked-each-64-total-128', meta(ibytes(b'x' * 64, b'y' * 64))),
        ('meta-bytes-chunked-empty', meta(ibytes())),
        ('meta-text-64-ascii', meta(t('a' * 64))), ('meta-text-65-ascii', meta(t('a' * 65))),
        ('meta-text-32-two-byte', meta(t(big * 32))), ('meta-text-33-two-byte', meta(t(big * 33))),
        ('meta-text-21-three-byte-63', meta(t('€' * 21))), ('meta-text-22-three-byte-66', meta(t('€' * 22))),
        ('meta-text-chunked-64', meta(itext(big * 16, big * 16))), ('meta-text-chunked-66', meta(itext(big * 16, big * 17))),
        ('meta-text-invalid-utf8', meta(b'\x61\xff')), ('meta-text-invalid-utf8-65', meta(b'\x78\x41' + b'\xff' * 65)),
        ('meta-key-bytes-65', meta(mp((b(b'x' * 65), u(1))))), ('meta-key-text-65', meta(mp((t('a' * 65), u(1))))),
        ('meta-key-bytes-64', meta(mp((b(b'x' * 64), u(1))))),
        ('meta-key-list', meta(mp((arr(u(1)), u(1))))), ('meta-key-map', meta(mp((mp(), u(1))))),
        ('meta-key-null', meta(mp((b'\xf6', u(1))))), ('meta-key-negative', meta(mp((neg(4), u(1))))),
        ('meta-nested-map-duplicate-key', meta(mp((u(1), u(1)), (u(1), u(2))))),
        ('meta-nested-map-indefinite', meta(imp((u(1), u(1)), (t('k'), b(b'v'))))),
        ('meta-nested-map-duplicate-invalid-first', meta(mp((u(1), b'\xf5'), (u(1), u(2))))),
        ('meta-list-indefinite', meta(iarr(u(1), t('x')))), ('meta-list-empty', meta(arr())),
        ('meta-list-with-65-bytes', meta(arr(u(1), b(b'x' * 65)))),
        ('meta-map-value-with-65-text', meta(mp((u(1), t('a' * 65))))),
        ('meta-second-label-65-bytes', mp((u(0), u(1)), (u(1), b(b'x' * 65)))),
        ('meta-depth-100', meta(nested(100))), ('meta-depth-1000', meta(nested(1000))),
        ('meta-depth-1000-65-bytes-leaf', meta(nested(1000, b(b'x' * 65)))),
        ('meta-label-key-in-array-position', arr(u(0), u(1))),
    ]


def shape_cases():
    scripts = native_cases()
    cases = []
    sc = sig()
    cases += [
        ('array-empty-meta-empty-scripts', arr(mp(), arr())), ('array-meta-with-script', arr(meta(u(7)), arr(sc))),
        ('array-meta-with-script-hash-27', arr(meta(u(7)), arr(sig(b'\x11' * 27)))),
        ('array-meta-scripts-indefinite', arr(meta(u(7)), iarr(sc))), ('array-indefinite-outer', iarr(meta(u(7)), arr(sc))),
        ('array-indefinite-outer-extra', iarr(meta(u(7)), arr(sc), u(1))),
        ('array-meta-bytes-65', arr(meta(b(b'x' * 65)), arr())), ('array-script-valid-meta-bytes-65', arr(meta(b(b'x' * 65)), arr(sc))),
        ('array-scripts-duplicate', arr(mp(), arr(sc, sc))), ('array-scripts-field-map', arr(mp(), mp())),
        ('array-scripts-field-bytes', arr(mp(), b(H28))), ('array-scripts-null', arr(mp(), b'\xf6')),
        ('array-arity-0', arr()), ('array-arity-1', arr(mp())), ('array-arity-3', arr(mp(), arr(), arr())),
        ('array-meta-is-list', arr(arr(), arr())), ('array-meta-is-null', arr(b'\xf6', arr())),
        ('array-meta-tagged-259', arr(tag(259, mp()), arr())),
        ('array-meta-duplicate-label', arr(mp((u(0), u(1)), (u(0), u(2))), arr())),
        ('array-scripts-many-valid', arr(mp(), arr(*[arr(u(4), u(i)) for i in range(40)]))),
        ('array-scripts-deep-100', arr(mp(), arr(_deep_native(100)))),
        ('array-scripts-deep-1000', arr(mp(), arr(_deep_native(1000)))),
        ('tag259-empty', tag(259, mp())), ('tag259-meta', tag(259, mp((u(0), meta(u(1)))))),
        ('tag259-native', tag(259, mp((u(1), arr(sc))))), ('tag259-meta-native', tag(259, mp((u(0), meta(u(7))), (u(1), arr(sc))))),
        ('tag259-plutus-v1', tag(259, mp((u(2), arr(b(b'\x01\x00\x00')))))),
        ('tag259-plutus-v2', tag(259, mp((u(3), arr(b(b'\x01\x00\x00')))))),
        ('tag259-plutus-v3', tag(259, mp((u(4), arr(b(b'\x01\x00\x00')))))),
        ('tag259-plutus-v1-empty', tag(259, mp((u(2), arr())))), ('tag259-unknown-key', tag(259, mp((u(99), arr())))),
        ('tag259-meta-bytes-65', tag(259, mp((u(0), meta(b(b'x' * 65)))))),
        ('tag259-duplicate-key', tag(259, mp((u(1), arr(sc)), (u(1), arr())))),
        ('tag259-not-map', tag(259, arr())), ('tag258-map', tag(258, mp())),
        ('bare-map-label-2-list-of-bytes-is-metadata', mp((u(2), arr(b(b'\x01\x00\x00'))))),
        ('bare-map-labels-2-and-1-are-metadata', mp((u(2), arr(b(b'\x01\x00\x00'))), (u(1), arr(sc)))),
        ('top-uint', u(0)), ('top-bytes', b(b'\x00')), ('top-text', t('x')), ('top-null', b'\xf6'), ('top-true', b'\xf5'),
        ('top-tag-24-map', tag(24, b(mp()))), ('top-empty', b''), ('top-trailing-byte', mp() + b'\x00'),
        ('top-two-items', mp() + mp()),
    ]
    cases += [(f'script-{name}', arr(mp(), arr(script))) for name, script in scripts]
    return cases


def _deep_native(depth):
    node = sig()
    for _ in range(depth):
        node = arr(u(1), arr(node))
    return node


def blake2b256(data):
    return hashlib.blake2b(data, digest_size=32).digest()


def body(auxhash=None, ttl=100):
    pairs = [(u(0), arr()), (u(1), arr()), (u(2), u(0)), (u(3), u(ttl))]
    if auxhash is not None:
        pairs.append((u(7), b(auxhash)))
    return mp(*pairs)


def tx_cases():
    """Whole-transaction controls. Fields: body, witnesses, auxiliary (null or bytes)."""
    sc = sig()
    good = meta(u(7))
    list_form = arr(meta(u(7)), arr(sc))
    big = meta(b(b'x' * 65))
    non_canonical = mp((b'\x18\x00', b(b'a')))
    canonical = mp((u(0), b(b'a')))
    bad_shape = tag(259, mp())
    zero = b'\x00' * 32

    def full(hash_, aux):
        encoded = b'\xf6' if aux is None else aux
        return head(4, 3) + body(hash_) + mp() + encoded

    rows = [
        ('none-none', None, None), ('hash-no-aux', blake2b256(good), None), ('aux-no-hash', None, good),
        ('match', blake2b256(good), good), ('wrong-hash', zero, good), ('wrong-hash-list-form', zero, list_form),
        ('match-list-form', blake2b256(list_form), list_form),
        ('wrong-hash-structurally-invalid-tag259', zero, bad_shape),
        ('match-structurally-invalid-tag259', blake2b256(bad_shape), bad_shape),
        ('wrong-hash-bytes-65', zero, big), ('match-bytes-65', blake2b256(big), big),
        ('wrong-hash-bad-native', zero, arr(mp(), arr(arr(u(6), b(H28))))),
        ('no-hash-structurally-invalid', None, bad_shape),
        ('wrong-hash-no-aux', zero, None),
        ('noncanonical-match-original-bytes', blake2b256(non_canonical), non_canonical),
        ('noncanonical-hash-of-canonical-reencoding', blake2b256(canonical), non_canonical),
        ('indefinite-map-match-original-bytes', blake2b256(imp((u(0), b(b'a')))), imp((u(0), b(b'a')))),
        ('indefinite-map-hash-of-canonical-reencoding', blake2b256(mp((u(0), b(b'a')))), imp((u(0), b(b'a')))),
        ('duplicate-label-match', blake2b256(mp((u(0), u(1)), (u(0), u(2)))), mp((u(0), u(1)), (u(0), u(2)))),
        ('list-form-indefinite-match', blake2b256(iarr(meta(u(7)), arr(sc))), iarr(meta(u(7)), arr(sc))),
    ]
    out = []
    for name, hash_, aux in rows:
        out.append((name, full(hash_, aux), aux, hash_))
    # Envelope shapes: arity and a trailing validity flag are not pre-Alonzo transaction shapes.
    flagged = head(4, 4) + body(blake2b256(good)) + mp() + b'\xf5' + good
    out.append(('alonzo-shaped-four-array', flagged, good, blake2b256(good)))
    out.append(('two-array', head(4, 2) + body() + mp(), None, None))
    out.append(('aux-hash-31-bytes', head(4, 3) + mp((u(0), arr()), (u(1), arr()), (u(2), u(0)), (u(3), u(100)), (u(7), b(b'\x00' * 31))) + mp() + good,
                good, b'\x00' * 31))
    return out


def prepare():
    rows = []
    for era, pv in ERAS:
        for kind, value in metadata_cases() + shape_cases():
            raw = value
            rows.append({'name': f'{era.lower()}-pv{pv}-{kind}', 'era': era, 'protocolMajor': pv, 'mode': 'aux',
                         'cbor': raw.hex(), 'auxiliary': raw.hex(), 'kind': kind,
                         'expectedAuxHash': blake2b256(raw).hex(),
                         'provenance': {'kind': 'synthetic-historical-auxiliary-api-input', 'ledgerAdmissionClaim': False}})
        for kind, tx, aux, hash_ in tx_cases():
            rows.append({'name': f'{era.lower()}-pv{pv}-tx-{kind}', 'era': era, 'protocolMajor': pv, 'mode': 'tx',
                         'cbor': tx.hex(), 'auxiliary': None if aux is None else aux.hex(), 'kind': f'tx-{kind}',
                         'bodyAuxHash': None if hash_ is None else hash_.hex(),
                         'expectedAuxHash': None if aux is None else blake2b256(aux).hex(),
                         'provenance': {'kind': 'synthetic-historical-auxiliary-api-input', 'ledgerAdmissionClaim': False}})
    return rows


if __name__ == '__main__':
    import argparse
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.write_text(json.dumps(prepare(), indent=1) + '\n')

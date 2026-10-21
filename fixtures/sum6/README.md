# Sum6 KES fixtures

Public evidence for `xyz.kd5ujc.scarab.crypto.kes.Sum6Kes` (verification of the 448-byte Sum6 KES signature of a block header),
read by `Sum6KesSuite`.

| File | Contents |
|---|---|
| `originals.tsv` | 4 rows `slot  KES root key  relative period  signed message  signature`: the header body and KES signature of four archived public preprod headers (slots 70070331, 70070379, 70070426, 70070464; relative KES periods 35, 29, 35, 28), with the hot key of the operational certificate in the header as the root key. |
| `observations.tsv` | 17,336 rows `slot  case  verdict  ...`: for each original, the verdict (`VERIFIED` or `REJECTED`) the model of the reference algorithm gave for the original and for each mutation: every other relative period, the message emptied, extended or truncated, every byte of the message and of the signature xor-ed, and so on. Four rows verify (the originals), 17,332 reject; the last two columns count the verification steps and the Ed25519 leaf calls (3,752 in all). |
| `sha256.json` | SHA-256 of the two tables and the evidence files; the suite checks the pins before reading them. |
| `evidence/` | The reference sources, unchanged: `KES-Class.hs`, `KES-Single.hs`, `KES-Sum.hs` (IntersectMBO cardano-base at `060819b59c184b951a54e3c563304983c53a3eac`), the Amaru parameter sources (`amaru-*.rs`) and the four header files `preprod_*.cbor` (pragma-org/amaru at `34a453005bcaaf837ee73bd996b99eab8ef92961`), `historical-oracle.json` (counts of the research run) and `sources.json` (URLs, SHA-256, relative period per slot). |

## How produced

The headers are the public test data of the Amaru project. The verdicts of `observations.tsv` come from a
source-derived model of the cardano-base Sum6 verification (a Python tree following `KES-Sum.hs`, with the Ed25519
leaf checked by the Cardano fork of libsodium built from the pinned source); `historical-oracle.json` states that
there was no independent full KES oracle and no JVM run in that research. The Scala verifier is tested to give the
same verdict on every row. No signing key, seed or private data is involved: only public verification keys,
messages and signatures. Agreement is differential evidence, not proof of equivalence with the node's binary
(`docs/compatibility.md`).

## Provenance and licence

`evidence/*.hs` are verbatim Apache-2.0 sources of IntersectMBO cardano-base (`evidence/cardano-base-target-LICENSE`);
the Amaru files are Apache-2.0 (`../praos/evidence/amaru-LICENSE` holds the same licence text). The `.tsv` tables are
our own data derived from those public headers; the repository's Apache-2.0 applies. The headers are public chain
data. See `../../NOTICE` and `../../docs/licenses.md`.

## Comparison, integrity and regeneration limits

`Sum6KesSuite` pins both TSV hashes before parsing and requires all 17,336 observation indices
exactly once. It compares verification verdicts and hash/leaf-call counts for every original and
mutation; no rejected mutation is omitted. This is source-model evidence with a native Ed25519
leaf, not 17,336 independent Haskell full-KES executions.

Verify the maintained `sha256.json` entries from the repository root:

```sh
python3 - <<'PYVERIFY'
import hashlib, json, pathlib
root = pathlib.Path("fixtures/sum6")
for name, expected in json.loads((root / "sha256.json").read_text()).items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, name
PYVERIFY
```

The original source-derived model runner is not maintained here; the public headers, exact source
pins, mutation recipes and recorded observations are preserved. A new capture should reproduce the
Sum6 tree from the pinned Haskell files, identify its Ed25519 backend, replay every original recipe
and archive complete outcomes before comparison. Preserve these historical goldens. A full native
KES oracle would add independent evidence rather than silently replace the model's provenance.

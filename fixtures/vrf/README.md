# VRF (ECVRF draft-03) fixtures

Public evidence for the strict draft-03 verifier of the Cardano VRF (`lab.vrf.StrictDraft03`, with the Java
arithmetic in `core/src/main/java/lab/vrf`). The verifier must give the
verdict of the Cardano fork of libsodium on every input, including the inputs that other implementations accept.

| File | Contents |
|---|---|
| `vectors.tsv`, `expected.tsv` | 2,048 rows. `vectors.tsv`: `id  public key  proof  alpha`; `expected.tsv`: `id  verdict` (`VALID:<64-byte output>`, `INVALID` or `MALFORMED`). Three valid rows (the published IETF draft-03 examples; the first has the key of RFC 8032 test 1 and an empty alpha), 2,018 invalid, 27 malformed envelopes. The invalid rows are bit-flips of the published examples and of 14 negative cases of the upstream tests, 45 non-canonical response scalars and 36 point-boundary encodings. |
| `additional.tsv`, `additional-results.tsv` | 2,338 further direct calls: fresh re-runs of every fixed-size original (repeated on purpose: rows, not distinct proofs), 34 mixed-order, torsion and negative-zero mutations, 256 deterministic unstructured inputs and 21 scalar boundaries. |
| `helpers.tsv`, `helper-results.tsv` | 3,426 comparisons of the intermediate steps: point decoding with the small-order test (612), the Elligator map (519), the double-scalar equation on mixed-order and prime-order points (240) and 2,055 full traces of the verification pipeline. |
| `affine-inputs.tsv`, `affine-results.tsv` | 104 rows of the double-scalar equation in affine coordinates (checked by `evidence/affine_check.py`). |
| `sha256.json` | SHA-256 of every file here. |
| `evidence/` | Machine-readable inputs and results (`vectors.json`, `additional.json`, `helpers.json`, `summary.json`), the helper C file used with the native library (`helper.c`) and the scripts (`prepare_helpers.py`, `affine_check.py`), the pinned inputs (`pinned-inputs.json`), the comparison of the published Bloxbean sources (`published-source-comparison.json`), and three write-ups: `pinned-crypto-oracle.md`, `strict-vrf-prototype.md` and `next-vrf-slice.md` (decision and limits). |
| `licenses/` | `CCL-LICENSE` (MIT, Bloxbean cardano-client-lib), `sodium-LICENSE` (ISC) and `BC-LICENSE` (Bouncy Castle). |

## How produced

The expected verdicts come from the VRF verification function of the Cardano fork of libsodium
(`IntersectMBO/libsodium` at `dbb48cce5429cb6585c9034f002568964f1ce567`), built from the pinned source on the
research machine (build flags and archive hash in `pinned-crypto-oracle.md`), through `crypto_vrf_ietfdraft03_verify`
only. The helper cases call the library's public group arithmetic through `helper.c`. No prover, signer, key
generation or private key was run to make the rows, and none of the files holds a secret: every key, proof and
message is public. `summary.json` records zero mismatches between the native library and the strict prototype that became
`StrictDraft03` on every row; `StrictDraft03Suite` replays them. This is a source build, not the node's shipped binary, and published valid proofs with mixed-order
points do not exist in the corpus (the helper cases are not accepted proofs): agreement is evidence, not proof of
equivalence (`docs/compatibility.md`).

## Provenance and licence

`core/src/main/java/lab/vrf/Ed25519Point.java` and `Draft03Math.java` adapt Bloxbean cardano-client-lib
(commit `45906b783973b4c44ac6883253af26bdd07bc1b4`, MIT; the full notice is `licenses/CCL-LICENSE`). The helper C
file includes internals of the Cardano libsodium fork (ISC, `licenses/sodium-LICENSE`); the library itself and its
binaries are not redistributed. The IETF draft-03 examples are public specification data. The tables and the
JSON results are our own derived data; the repository's Apache-2.0 applies to them. See `../../NOTICE` and
`../../docs/licenses.md`.

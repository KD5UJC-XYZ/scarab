# Published Plutus boundary inputs against the supported ledger API

The 2026-10-20 native run evaluates **54 original-byte requests through each of two native
oracles** (108 observations). Six probes cover V1/V2/V3 at protocols 9, 10, 11 with the same explicit
released 1.70 cost lists and budgets. `UpstreamPlutusBoundarySuite` replays all 54 released results
through Scarab's production decoder and evaluator, checking decoder admission and the distinct
setup, scope, budget, runtime and success stages. This is API evidence, not signed-transaction
admission or a full newer-release upgrade.

The two newly published cases come unchanged from
[Plutus `8fd7b6f3`](https://github.com/IntersectMBO/plutus/tree/8fd7b6f345d3265ea3a128b45576685c0538f083/plutus-conformance/test-cases/uplc/evaluation/term/free-vars),
under Apache-2.0. Original Flat, textual and generic expected files are retained under `upstream/`.
Only a single definite CBOR byte-string wrapper is added to Flat for the ledger API. The generic
suite expects parse/decode errors; **that is not the released ledger API's failure stage**:

- `unused-free-var`: decoder admission succeeds, then the scope check rejects in all 9 cases.
- `constr-free-var`: decoder admission succeeds; V1/V2 at PV9/10 reject UPLC 1.1 at setup, while the
  five other combinations reach evaluation and fail there. Released scope checks deliberately
  skip `Constr` fields. Do not change that behavior globally when importing generic suite inputs.

The existing [boundary corpus](../plutus-boundaries/README.md) already tests wider variables and
ignored/evaluated `Case`/`Constr` subterms. These new cases add the exact published upstream bytes,
not a claim that those general shapes were previously untested. Four existing header 31/33 and
constructor 1024/1025 probes are reused byte-for-byte instead of replacing their original evidence.

## Isolated native upgrade observation

The released oracle uses the exact 1.70 source and full crypto backend of the
[pinned recipe](../../../scripts/oracle/plutus/README.md). The second executable applies only the
[decoder-bound change](https://github.com/IntersectMBO/plutus/commit/bf6917633a7c697b816e19f5b0491966c1f771ce)
to that same source. The retained [two-file patch](../../../scripts/oracle/plutus/historical-bounds.patch)
is extracted from that upstream commit. This isolation makes the old-versus-new bound observable
without changing the cost parameters, interpreter, UPLC versions or builtin tables. **It is not an
execution of the whole 1.72 release.**

Exactly 12 admissions differ: header 33 and constructor 1025 at PV9/10, in all three languages. Released
V3 evaluates these four combinations successfully; released V1/V2 instead reject the 1.1 program
version at setup. The backported bound rejects all 12 during decoding. Header 31, constructor 1024,
all PV11 admissions and both free-variable probes retain the same stages. Scarab continues to match
the released oracle. No production decoder policy changes, PV12 activation or default budget
changes are made. The original 13 conformance-harness qualifications remain unchanged.

`manifest.json` binds the source, image, native executable and original/public file hashes. Raw
requests, native diagnostics and build output remain outside the checkout; the fixture omits
unstable diagnostic text and retains typed stage categories. No semantic native run failed. An
initial build-launch attempt used a login shell that hid GHC from PATH; it failed before compiling,
was retained in private evidence, and was corrected by preserving the image's normal environment.

## Reproduction

Build the released image with the existing pinned recipe, then export the exact 54 requests:

```sh
python3 scripts/oracle/plutus/upstream_boundaries.py /tmp/plutus-boundary-requests.jsonl
docker run --rm --cpus 2 -i scarab-plutus-oracle:local < /tmp/plutus-boundary-requests.jsonl > /tmp/plutus-boundaries-released.jsonl
```

In a separately named disposable container from that image, mount this checkout at `/work`, apply
`/work/scripts/oracle/plutus/historical-bounds.patch` with `git -C /source apply`, and execute
`cabal build scarab-plutus-oracle --offline --jobs=2` from `/source`. Use a non-login shell so the
image's GHC PATH remains intact. Run the executable returned by `cabal list-bin scarab-plutus-oracle`
against the **same** request file. Remove the disposable container afterwards. The retained run
used `scarab-plutus175-oracle:local`, whose immutable image ID is in the manifest; it is the pinned
released recipe's previously reproduced full-crypto image.

Ordinary Docker `check` replays the retained verdicts without Haskell or network access.

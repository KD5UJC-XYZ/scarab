# Audited parameter-role differential packet

Source-only packet. Native compilation and execution have **not** been performed. Parent review is required before running the native executable. There is no runtime admission or consensus claim.

The packet independently checks the finite distinction at the actual native NEWEPOCH boundary. The supplied outer previous PParams contain V1/V3 cost models; outer current PParams additionally contain V2. Their complete 31-field originals differ only at field 15. The previous-completed case retains `oldEnact.current == outerPrevious` and `oldEnact.previous == outerPrevious`. It does not rewrite that history to outer current to make a profile check pass.

Pinned Conway EPOCH lines 323–325 select `nextEpochPParams govState0`, copy outer current to previous, and reset future parameters to PotentialNone. Pinned core `State/Governance.hs` lines 111–133 choose outer current for NoPParamsUpdate; the old completed EnactState parameter value does not replace it. `source-pins.json` records archive/member hashes and exact reused harness source hashes.

Three fixed cases are defined in AuditedRolesMain.hs and independently projected by packet.py:

1. Exact-current completed EnactState, with unchanged previous/current distinction.
2. Previous-completed EnactState, with only the outer cost-model field changed.
3. A non-cost fee mutation rejected by the **finite profile gate before STS**. It is not a claim that native consensus rejects the broader input.

Both accepted cases apply native NEWEPOCH to the existing independent synthetic six-account harness, then normalize its newly created DRep pulser with `finishDRepPulser`. They compare entire native parameter objects and emit whole native CBOR parameter encodings for all four before/after roles. The input outer previous and completed roles remain visible. Output is compared against exact independently extracted parameter originals, not a Scala-produced result. No live DRep cursor parity is claimed. All other state remains the synthetic fixture; this is not native execution of the audited full epoch state.

## External source contract

The audited bundle `/path/to/reviewed-bundle` is read-only. No actual bundle contents, private cluster data, CBOR parameter originals, seed components, logs, or binaries are checked into this directory. Only hashes and source code are retained.

`packet.py` checks the complete native-projection.json SHA256, component encoding labels, both component SHA256 values, full 31-field CBOR framing, the exact differing field set `{15}`, language keys `[0,2]` versus `[0,1,2]`, complete V1/V2/V3 model lengths 166/175/251, and signed-64-bit bounds for every model value. Input and result JSON reject duplicate keys and nonfinite constants. Native indefinite cost-model arrays are retained byte-for-byte. There is no CBOR normalization. Preparation creates a new output directory outside Git; it never overwrites an existing directory or writes to the source bundle. The Haskell reader requires bounded full decoding and exact native re-encoding equality with each supplied original.

## Reviewable commands and resources

Read-only inspection and Python tests do not execute native code:

```sh
python3 reference/audited-governance-roles-diff/packet.py inspect --bundle /path/to/reviewed-bundle
python3 -B -m unittest discover -s reference/audited-governance-roles-diff -p 'test_*.py'
```

After review, prepare an explicitly chosen, nonexistent directory outside Git:

```sh
python3 reference/audited-governance-roles-diff/packet.py prepare --bundle /path/to/reviewed-bundle --output /path/to/REVIEWED-UNIQUE-roles-input
```

Use the existing pinned GHC 9.6.7/native dependency closure in one isolated Docker build: `--network none --cpus 2 --memory 2g --pids-limit 128`, timeout 600 seconds, source mounted read-only, private writable build/cache/output, no shared cache writes. The cabal file reuses the same 16 direct dependency versions; no package resolution or downloads are permitted. A private project file should select only `reference/audited-governance-roles-diff/audited-governance-roles-diff.cabal`. Build with `cabal build --offline -j1 exe:audited-governance-roles-diff` in the reviewed isolated copy. Do not build in a shared checkout/cache.

Run the resulting executable in a similarly constrained container with the prepared directory mounted read-only at `/input`, using `timeout 60 audited-governance-roles-diff /input`. Capture stdout to an external result.json, cap it at 256 KiB, and preserve compiler/binary/image/source hashes and exit status separately. Then compare:

```sh
python3 reference/audited-governance-roles-diff/packet.py compare --bundle /path/to/reviewed-bundle --result /path/to/REVIEWED-UNIQUE-roles-output/result.json
```

Container/image/cache paths must be selected and reviewed by the parent before execution. `resource-plan.json` records the limits. There is no automatic launcher that bypasses that review. Compilation/API compatibility and native output remain unverified until that run occurs.

Pinned native CostModels/Language and binary Encoding sources are also recorded. Encoder.hs lines 493–508 emit native indefinite lists above length 23; the long audited cost-model arrays are therefore intentionally preserved as indefinite originals. This packet does not equate a preferred definite CBOR tree with native canonical encoding.

# Body commitment v0.20 test matrix

The fixture matrix is executable from immutable tables and reproduced independently
in Python. Cases intentionally accepted by the shallow profile do not establish
reference/ledger acceptance. Prior structural index/fetch/store suites remain part
of aggregate regression; no old expected identities are updated to fit this slice.

| Area | Required outcome / executable coverage |
|---|---|
| Six original era layouts | 36 raw fixtures; exact raw SHA, era, original header hash, declarations, every component span/digest; 35 combined matches |
| Known answers / wrong algorithms | BLAKE2b-256 existing primitive vector plus empty three/four-component literals; all 36 flat-body hashes differ; no encoder in production path |
| Toy golden | Hash true, size false; declared 2345 versus actual 6950, never relabelled valid |
| Each component independent | Transaction-body, witness, auxiliary and synthetic nonempty invalid-index payload changed with original header; same size, changed raw SHA/hash |
| Declaration independence | Size-only, hash-only and both changes; all four match-state combinations are observations |
| Word32 | 0 and max accepted as declarations and compared; 2^32 and uint64 max malformed in new API, still accepted structurally |
| Wrong declarations | Negative, null, tagged bignum, wrong hash type/length; no signed narrowing or allocation from declaration |
| Arity and era | Wrong envelope/block/header/header-body/VRF arities in every era; wrong Praos nested cert/version; unknown/Byron tags |
| Shallow component types | Nonarrays for bodies/witnesses/invalid indices, nonmap transaction/witness/auxiliary, negative auxiliary/invalid indices |
| Width/indefinite boundaries | Wider outer/block/era heads and indefinite outer/block/header; offsets remain relative to raw original; outer changes preserve header and component identities |
| Component encoding changes | Every empty array/map switched to indefinite under old declarations rejects; coordinated declarations match local profile, with no reference-acceptance claim |
| Coordinated semantic invalidity | Count mismatch, duplicate/out-of-range auxiliary, invalid-index duplicate/order/range can match; header identity changes; never ledger positives |
| Generic malformed CBOR | Empty/truncated/trailing, reserved arguments, unexpected breaks, indefinite uint, enormous length declarations, odd map, bad/nested/missing chunks, invalid/split UTF-8, floats/undefined/simple forms |
| Every prefix | All 864 proper prefixes of the small Babbage synthetic block malformed |
| Exact input cap | Structurally accepted 1,048,576-byte body observation; sentinel byte above cap rejects |
| Exact depth cap | Depth32 accepts, depth33 rejects under the production API |
| Exact item cap | 100000 items accept, 100001 reject; malformed enormous container declarations reject before allocation |
| String cumulative cap | Two 256-byte indefinite chunks accept maxString512, reject511; chunk items count toward total |
| Caller bounds | Bytes/depth/items/string exact minimal thresholds and ±1; every hard maximum+1 and invalid lower values rejected; limits cannot loosen hard caps |
| Arithmetic | Word32 represented nonnegative Long; actual size checked Long, span checked Int; true overflow unreachable under input cap and three/four-component arity, not falsely claimed as an executed huge allocation |
| Immutability | Mutating caller array or arrays returned from every digest cannot change any observation field or result |
| CLI | Six era positives, toy, separate mismatch categories, local malformed/overflow, missing/oversized/directory/link/FIFO inputs, usage/help, non-repository cwd and repeated read-only input checks |
| Shared regression | Existing all-era structural identity/uint64/body-mutation tests, source/store/resume and direct-range scripts unchanged |

The finite synthetic table has 347 cases: 96 matches, 27 hash mismatches, 12 size
mismatches, 27 combined mismatches and 185 malformed inputs. Counts describe
registered mutations rather than independent authentic blocks.

## Explicit remaining gates

- A fresh pinned Haskell oracle for decoder acceptance and original hash/size,
  especially non-shortest/indefinite map/list forms, duplicated/unsorted indices,
  generic duplicate-key rules and intra-era protocol-version changes.
- Real Alonzo/Conway blocks with nonempty invalid-index lists; current fixtures
  cannot provide real-feature coverage for that component's nonempty payloads.
- Evidence-typed integration with requested-header/context binding, strict-policy
  migration, receipt reuse tied to raw digest/profile, atomic publication,
  interruption/cancellation and stale-result handling. None is silently simulated
  by changing structural fetch/store semantics in v0.20.
- Header cryptography, source authentication, complete schema/ledger transitions,
  consensus and historical replay. No amount of commitment-only mutation coverage
  establishes these independent properties.

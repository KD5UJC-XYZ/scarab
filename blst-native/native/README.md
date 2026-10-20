# Raw-byte domain separation tags

`raw-dst.c` backports the byte-array `hashToGroup` entry from Scalus commit
[`c132a2c8615db9cd27b464b7863863cd576d23be`](https://github.com/scalus3/scalus/commit/c132a2c8615db9cd27b464b7863863cd576d23be),
`scalus-crypto-jni/native/blst.c` and `jni_util.h` (Apache-2.0).
The local changes retain only G1/G2 hashing and return standard compressed points instead of native
structs, so the existing blst-java P1/P2 classes can read them. The adapter implements no curve math.
Both calls pass message and DST bytes with their exact lengths to `blst_hash_to_g1/g2`, then compress
with `blst_p1/p2_compress`. The library remains portable blst v0.3.15 at
`6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c`; upstream JNI methods remain exported unchanged.

As of 2026-10-18, Maven Central's latest Scalus is 1.3.0 (tag
`31531c14d4e556fb38c984d702ee60dd82b6453f`); the byte-array fix is 26 commits later and unreleased.
The backport avoids adopting an unpublished dependency. Remove it when a released Scalus with this
fix and equivalent portable native support passes the retained oracle vectors.

Rebuild from the repository root:

```sh
docker build -f docker/blst.Dockerfile --target artifact -o blst-native/out .
```

Artifact SHA-256: `5e5176a1056c0fcf5b253315fefb997466187f79977a0f65e1ee78e74c569e21`.
The export golden contains the original exports plus `Java_xyz_kd5ujc_scarab_blst_RawDst_hashG1`
and `Java_xyz_kd5ujc_scarab_blst_RawDst_hashG2`. The Dockerfile-specific ignore file sends only the
adapter source as build context. G1/G2 vectors come from the real pinned Haskell crypto backend,
not the no-crypto build: `fixtures/reference/plutus-boundaries/hash-vectors.jsonl`.

Other platforms retain explicit `EvalFailure.Unsupported` for tags that the old String API cannot
represent. Ledger validation propagates that as unavailable, never as a script failure or a
consensus predicate failure. The evaluator preserves the upstream 255-byte DST limit and costing.

The final artifact passed the separate 2026-10-18 offline no-ADX/BMI2 smoke: the CPU feature set
was empty, portable library selected, all five startup known answers and the three public builtin
scripts passed, exit 0. The test used a network-disabled throwaway container with read-only public
bundle and native overlay, without changing any operational node or configuration.

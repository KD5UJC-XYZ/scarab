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

## Rebuild and byte verification (#229)

Run from a clean checkout with Docker/BuildKit supporting `ADD --checksum` (Dockerfile 1.6+):

```sh
python3 scripts/check_native_reproducibility.py --output /tmp/scarab-native-verification
```

The output directory must be new and outside the checkout. The controller freezes the recipe,
ignore file, raw-DST adapter, startup identity and committed library; records their SHA-256 hashes
and the observed Git revision/dirty status; and makes two separate `--no-cache`, Linux/amd64
Docker builds. Neither build context permits access to the committed library. Every rebuilt byte
must equal both the frozen committed artifact and the other build. The controller never replaces
that artifact or updates `Blst.portableSha256`. A changed input, missing evidence, failed build,
invalid signature or byte difference is a failure, with logs and `receipt.json` retained.

The builder pins Ubuntu by image digest, package repositories to the signed
`20261020T000000Z` Ubuntu snapshot, and direct compiler/tool packages by version. Transitive
packages come from that same snapshot. Explicit `__ADX__` plus `__BLST_PORTABLE__` flags retain
the committed runtime-dispatch configuration even on a builder whose CPU lacks ADX. A checksum-pinned CA package bootstraps HTTPS in the minimal
base; APT still verifies Ubuntu archive signatures, index checksums and downloaded package hashes.
Only historical-index expiry is disabled. The controller independently reverifies all three
exported `InRelease` signatures in a network-disabled container using the original pinned Ubuntu
image's trusted keyring. It records every installed package/version/architecture, the hashes of
all downloaded `.deb` files and signed indexes, and the Docker engine version. This is an Ubuntu
archive trust boundary, not an independently trusted compiler or a signed Scarab release.

The blst source is fetched at the exact commit above and its exported source tar must have SHA-256
`15e976ff729ebac12da5377ba454b6761aa7f77747cf5693074ee99336637024` (Git tree
`c804aeb7b55481472f2030c459d1aa31420b43ee`). These reviewed pins and HTTPS bind the input;
no upstream developer signature is asserted. The compiled raw-DST input hash is checked against
the frozen local source. Source archive, ELF headers/sections/dynamic metadata, disassembly and
symbol lists accompany each local result. A mismatch also records sizes, SHA-256 identities and
the first differing byte offsets; inspect these diagnostics before proposing any binary update.
Two locally clean builds share the same trusted Docker engine and pinned toolchain, so this does
not establish independence from a compromised builder.

[Native CI](../../.github/workflows/native-reproducibility.yml) runs the same comparison and retains
its actual input/result receipt and diagnostics. Its Python negative controls exercise changed and
truncated outputs, missing evidence, input drift and incomplete signature verification; synthetic
controls are not native observations. The ordinary `BlstSuite` still checks startup known answers,
resource selection and JNI compatibility; byte identity does not replace those runtime checks.

This verification is only for the committed portable **Linux/amd64** resource. Linux/aarch64 and
macOS continue using blst-java's bundled libraries under [ADR 0012](../../docs/adr/0012-portable-blst.md)
and B25; no source rebuild, byte equality or runtime portability is claimed for those platforms.
The historical no-ADX/BMI2 smoke above remains tied to its recorded run; this checker does not
simulate an older CPU. Signed attestations, SBOMs and published-release verification remain
separate [release obligations](../../docs/design/releases.md#acceptance-under-issue-75).

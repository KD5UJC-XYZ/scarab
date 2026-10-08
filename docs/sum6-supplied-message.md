# Experimental Sum6KES supplied-message predicate

Version 0.19 adds a public-input, JVM-only six-level SumKES adapter over the
existing experimental strict Ed25519 verifier. Its successful result is named
`SuppliedMessageSignatureVerified`: the supplied root, relative period and
signature authenticate the supplied bytes under that profile. It does not
construct those bytes or establish a valid Cardano header.

## Contract

`lab.kes.Sum6Kes` has private-construction immutable `Root32`, `Signature448`,
`RelativePeriod` and `SuppliedMessage` inputs. Checked constructors reject null
(including malformed `Bytes(null)` storage), wrong lengths, periods outside
0..63, and messages exceeding 65,536 bytes. Empty messages are permitted.
The message cap is a local experimental resource policy, not a Cardano rule.
Array ingress checks bounds before copying; getters return independent arrays.
There is no unchecked `copy` constructor or caller-selected production provider.
The byte-oriented `verify` facade checks every input before cryptographic work.

The signature starts with the 64-byte leaf signature. The next six 64-byte
pairs are ordered nearest-leaf to root. At depth `d`, the pair begins at
`64 + 64 * (d - 1)` and consists of left32 followed by right32. Starting at
`d = 6`, require Blake2b-256(left32 || right32) to equal the current root.
This is digest output parameter 32 bytes, not truncated Blake2b-512. No CBOR,
domain tag or depth is included. Consume period bits 5 through 0: select left
for zero, right for one; the residual period must be zero after six levels.
Stop on the first mismatching commitment.

Only after all six checks, call the existing `StrictEd25519` once with the
selected leaf32, original signature bytes [0,64), and supplied message unchanged.
There is no prehash, period prefix or append, header substitution or serialization.
Tree children are opaque bytes; only the selected leaf undergoes the existing
strict curve/encoding checks. Public inputs and per-call state permit concurrent
verification; no constant-time claim is made.

Malformed envelopes yield `Failure.Malformed`. Well-shaped non-verifying inputs
yield `SignatureRejected`. Unexpected nonfatal failures yield typed
`InternalFailure`; fatal JVM errors propagate. A package-scoped test seam checks
failure separation, unchanged bytes and bounded work without changing the public
acceptance profile. Spy routing tests always reject at the leaf and do not
manufacture cryptographic positives.

## Evidence and limitations

Four untouched public Amaru archive headers supply four authentic positive
signatures. Authentic relative periods are only **28, 29 and 35**. Their exact
407-byte body spans happen to be the signed messages for these admitted fixtures.
This proves neither a general CBOR header parser nor protocol-version-aware
signable serialization. The projector admits exact archive SHA-256 values before
its narrow decoder runs.

Each original contributes 4,333 deterministic synthetic rejection controls:
63 other periods; all 3,584 signature bit flips; all 256 root bit flips; 407
message low-bit flips; the other seven bits of the first and last message bytes;
six pair swaps; and empty, truncated and zero-appended messages. Total: four
positives and **17,332 synthetic controls**. Wrong-period checks do not supply
new authentic periods. Mutation rejection is not exhaustive adversarial
Ed25519 acceptance coverage or a security proof.

`fixtures/sum6/originals.tsv` stores four byte projections.
`observations.tsv` stores compact mutation names, historical outcomes, pair-check
counts and leaf-call counts. Mutation names use zero-based offsets and low-bit
numbering. No thousands of duplicate signature/message blobs are stored.
Scala tests reconstruct all controls and compare actual JVM outcomes and work
traces. They admit both TSV digests before parsing. The historical experiment
used 3,752 native leaf calls; no native library is bundled or invoked here.

Historical expectations came from a source-derived Python tree plus the pinned
Cardano sodium `crypto_sign_ed25519_verify_detached` leaf, revision
`dbb48cce5429cb6585c9034f002568964f1ce567`, binary SHA-256
`76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f`.
This is **not an independent full Haskell KES oracle**, demonstrated official-node
binary equivalence, or an independent oracle for the complete tree. The portable
projector rechecks byte extraction, deterministic recipes and commitment traces;
it does not rerun or independently establish the historical leaf outcomes.

Separate gates remain: exact-release header-body serialization; checked
slot/start arithmetic and authoritative lifetime context; cold-key operational
certificate signature and counter state; pool registration, VRF binding, stake,
nonce and leader eligibility; body/chain continuity; ledger validity and historical
inclusion. Scheme capacity 64 is distinct from the archive's protocol lifetime 62.
This primitive takes relative period directly and does not implement either
slot conversion or lifetime policy. There is no signing, key generation, proving,
key update, native runtime dependency or private-key fixture.

## Reproduction

```sh
python3 scripts/project-sum6-fixtures.py
python3 scripts/verify-sum6-projector.py
JDK_JAVA_OPTIONS=-XX:ActiveProcessorCount=4 ./scripts/sbtw check app/runtimeClasspathFile
python3 scripts/verify-sum6-cli.py
JDK_JAVA_OPTIONS=-XX:ActiveProcessorCount=4 ./scripts/sbtw 'app/run sum6-demo'
```

The bounded demo admits one exact SHA-pinned file, at most 64 KiB, and verifies
only the four originals. It accepts no arbitrary fixture or header arguments.
The full synthetic corpus is covered by the test suite. Negative CLI checks use
portable temporary directories and reject mutated, oversized, missing files and
edited manifests. The projector has a 1 MiB per-file read cap, an embedded
manifest digest, exact file-set checks and source hashes. It has no network or
native dependency.

The explicit per-process `ActiveProcessorCount=4` profile is test sizing, not a
claim to cure earlier startup stalls or TCP resets. Existing evidence remains
qualified as before. See the release verification record for actual executed
gates rather than treating these reproduction commands as proof of a run:
[executed verification](sum6-verification.md).

## Public source and license provenance

Retained sources and exact immutable URLs/SHA-256 values are in
`fixtures/sum6/evidence/sources.json`, admitted by `fixtures/sum6/sha256.json`.
The source-derived algorithm follows Cardano-base revision
`060819b59c184b951a54e3c563304983c53a3eac`: `Sum.hs` lines 190–196 and 328–351,
`Single.hs` lines 104–106 and `Class.hs` lines 655–661. The retained target
Apache-2.0 LICENSE is included alongside those source files. Its exact-target
provenance is the immutable commit URL; no independently retained target-tree
proof or legal clearance is claimed.

The four public fixtures come from Amaru revision
`34a453005bcaaf837ee73bd996b99eab8ef92961`, under Apache-2.0. Its existing retained
license is `fixtures/licenses/amaru-LICENSE`. Retained public consensus/global
parameter sources pin the archive context of 129,600 slots per period and
maximum lifetime 62; these are fixture context, not parameters enforced by the
primitive. Existing witness documentation
records the ISC-licensed historical sodium leaf provenance and the strict JVM
arithmetic dependency. No native binary or new software is admitted in this slice.
A retained MIT-licensed Cardano Client Lib source comparison corroborated layout;
no CCL code is copied or built for this implementation and it is not a runtime
or differential oracle.

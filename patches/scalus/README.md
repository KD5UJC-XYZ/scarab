# Scalus CEK hooks and native scalar text

The node still uses Scalus's CEK machine and builtins ([ADR 0003](../../docs/adr/0003-plutus-through-scalus.md)).
The two protected hooks in `cek-hooks.patch` expose successful-result discharge and saturated-builtin
charging without changing their default behavior. They are preparation for the reference budget repair
[#485](https://github.com/KD5UJC-XYZ/scarab/issues/485), not that repair: settlement before discharge and demand-driven costing remain outstanding.

The two native-text patches normalize unpaired surrogate values to U+FFFD, retain UTF-8 byte length
with each immutable `Constant.String`, and align the actual `Program.parseUplc` string-token boundary
with the pinned native parser. Numeric supplementary escapes preserve their complete scalar value;
separate surrogate escapes stay separate replacements, `\&` is empty, and out-of-range scalars and
the non-native `\u{...}` extension refuse. Production `FlatScan` already decodes UTF-8 strictly; this
change does not align the dependency's generic Flat decoder or character-level helper API.

`manifest.json` pins the complete upstream 1.4.0 source archive, each changed source file and patch, Scala/sbt versions
and JDK. The source is upstream commit `b836856a5b4e54cd40fe0aa5947922478e8f1237`; its `Cek.scala`
is byte-identical to that in the published 1.4.0 source artifact. The CEK patch adds only:

- `beforeResultDischarge`, a no-op immediately before `dischargeCekValue` in `returnCek NoFrame`;
- `spendBuiltinBudget`, whose default body is the existing costing call followed by the existing charge.

These are the positions needed by the reference's `returnCek` and `evalBuiltinApp`, respectively:
Plutus `507cab8cf6192e5143f12be5b2d3005a2928dc6d`,
`plutus-core/untyped-plutus-core/src/UntypedPlutusCore/Evaluation/Machine/Cek/Internal.hs:891-894,1059-1064`.
No transition, denotation, discharge implementation, exception boundary or native library is replaced.

## Build and identity

Run the ordinary `bash scripts/sbtw ...` inside the repository's pinned JDK image. It first runs
`scripts/prepare_scalus.py`, which checks the JDK, downloads and verifies the source archive, applies
each patch with exact context, and builds the upstream JVM artifact. It then produces one jar from the separately SHA-pinned released
1.4.0 jar, replacing only thirteen explicit class/TASTy members listed in the manifest: the original five CEK
members and eight scalar-text/parser members. The complete affected source-family inventory of 52
members is also pinned; untouched siblings retain their original released bytes.
Every other released member's bytes, including license and manifest resources, must be unchanged;
the compiler output cannot add an unlisted member to those source families. The published POM is the pinned
original with only its root artifact version changed, preserving dependencies and license metadata.
This avoids inheriting nondeterministic, unrelated on-chain compiler output from a full source rebuild.
The result's version is
`1.4.0-scarab-<SHA256>`, where the digest frames and hashes the complete manifest, all three ordered patches and bootstrap
helper. `project/ScalusPatch.scala` computes the same identity and rejects a stale dependency version.
When changing these inputs, update the literal version in `project/Dependencies.scala` and the matching
NOTICE, runtime license table and license-index coordinate together. `check_notice.py` verifies the
complete version, including its suffix.

The helper publishes to an isolated file-Maven repository under
`.cache/scarab/patched-scalus/<digest>/repository`. It never overwrites the released 1.4.0 coordinate or
publishes to a remote registry. `sbtw` includes that repository in its explicit resolver configuration;
ordinary classpath export, assembly and release dependency inventory therefore use one Scalus artifact.
The compiler plugin is built from the same source as part of the upstream build. Native crypto remains
the published `scalus-crypto-jni:0.1.0` artifact.

Completed entries contain a receipt naming source/patch/released-artifact identities, the complete
replacement allowlist with original and patched member hashes, and the final jar/POM hashes. Cache hits
verify both artifacts. Missing offline entries and corrupted entries fail; set `SCARAB_OFFLINE=1` to
require the prepared dependency cache. Other ordinary sbt dependencies must also be cached when the
machine has no network. `SCARAB_BUILD_PROCESSORS` controls build JVM processor count (default4), not
node runtime flags. Concurrent workers use a per-input lock and atomic publication; source extraction
and compiler outputs are temporary and cleaned after the build. The upstream compiler embeds source
paths, so compilation uses a fixed per-input directory under `/tmp`, protected by a second lock.
A pre-existing source directory is refused, preserving its contents for an ownership check. Original source downloads and the
finished immutable repository are reusable dependency cache.

Packaging sorts ZIP members and fixes ZIP metadata while preserving every class/resource byte. Signed
or duplicate-member input jars are refused. Independent clean builds compare the resulting jar and
POM hashes; matching metadata alone is not a reproducibility check.

## Controls and limits

`ScalusCekHooksSuite` checks the hook positions, partial-builtin behavior, and original budget error
identity. Its `ScalusDefaultMachineControl` entry point uses only the original public API: running the
same compiled control with the original and patched jar in separate JVMs compares exact JSON for
successful terms, partial application, builtin and user errors, trace logs, budget refusal, and every
charge in order. The bootstrap's Python controls reject wrong patch context, corrupted downloads or
artifacts, incorrect publication coordinates, changed native crypto, and offline cache misses.

`NativeTextReferenceSuite` replays all 26 native value/parser/UTF-8 observations and verifies public
constructor, copy, product/sum Mirror, nested lifting, builtin production and small-stack behavior.
The genuine case class keeps one `value` product field; explicit Mirrors preserve the original
thirteen-case Constant sum. The public one-string constructor normalizes; only the class can call its
JVM-private numeric primary constructor, so callers cannot forge a retained length. Existing compiled
callers and complete public/protected descriptors are checked alongside the original default-machine
controls. The new field is not yet a replacement for the pending native lazy-costing adapter.

These controls do not establish full Plutus conformance or repair #485's remaining costing behavior.
The ordinary upstream suite and exact cost-model fixtures remain unchanged and required for that work.

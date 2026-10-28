# Verified local release candidates

**Status: Candidate build and inventory tooling; no publisher, signing service or production approval.**

The [release design](design/releases.md) separates preparation from publication. This read-only
pipeline builds a runnable fat JAR and Linux/amd64 OCI archive, checks their contents and produces
subject-bound CycloneDX 1.6 inventories. `verified` means inventory, hashes, coverage and subject
binding passed. It does **not** approve licences, clear vulnerability advisories, authenticate a
publisher, enable repository protections or close the adopted-preprod-block gate.

## Inputs and execution

Use Docker only for Java/sbt. From a clean committed checkout:

```sh
python3 scripts/build_release_candidate.py \
  --image scarab-jdk:local --cache /path/to/dependency-cache \
  --output /path/outside/the/checkout/candidate --check
```

This creates an unpublished development candidate. A release additionally requires an existing
exact tag, independently approved full commit/tag-object identities, channel and approved source
ref; pass the same `--release-tag`, `--expected-commit`, `--expected-tag-object`, `--channel` and
`--source-ref` inputs used by the [immutable-source verifier](reproducible-builds.md).
The tool neither creates tags nor accepts an overriding version. An RC and stable version require
separate builds. Output must be fresh and outside the source checkout; use an ext4-backed output
directory with room for both clean builds, image exports and retained evidence.

The read-only `Verified release candidate` workflow supports manual invocation and `workflow_call`.
It checks the supplied full commit out with tag/ref history, always runs the first clean build's
full `check`, and returns artifact name, numeric artifact ID, artifact transport digest, source SHA
and candidate-manifest SHA. A later coordinator must consume the exact run/artifact ID and digest;
names alone are not immutable identities. No registry token, OIDC attestation permission or release
write permission is available to this job. Failure evidence is uploaded under an explicitly
incomplete name, never as a verified candidate.

## What is checked

1. The existing verifier exports the selected committed source and verifies two independent clean
   fat-JAR builds in one immutable local JDK image. Original standalone, native/Plutus, service,
   multi-release and BuildInfo smoke checks remain active. `--inventory` additionally retains the
   original Git source archive, frozen source manifest and both assembly inventories. Ordinary
   verifier callers retain their existing behavior.
2. `node/releaseInventory` uses sbt's **full** runtime update report: the ordinary cached `update`
   report omits caller edges. It exports selected resolved artifacts, licences and caller edges
   with the actual runtime classpath. Identical resolver rows may contribute additional callers;
   conflicting artifact coordinates/licences fail. Project-module callers are represented by the
   root application, while external dependency edges remain explicit.
3. Every non-directory assembly entry is attributed to its original inputs or the generated JAR
   manifest. The checker implements the pinned sbt-assembly 2.5.0 rules and the explicit build
   overrides: exact deduplication, configuration concatenation, service/registry distinct lines,
   exact legal/readme renaming, module/signature/Maven/plexus metadata discards, and project-native
   precedence. Each discarded or shadowed input remains in the coverage report with its rule and
   original hash. Unexplained additions, losses and transformations fail. No shading is configured;
   introducing it requires a new explicit mapping contract, not a blanket exception.
4. Two uncached source-built portable JNI libraries must match the committed and packaged bytes,
   using the [native rebuild contract](../blst-native/native/README.md). Source archive, raw-DST,
   complete package inventories and authenticated Ubuntu indexes are retained. Candidate input
   hashes must match those native build inputs. Only Linux/amd64 runtime behavior is evidenced;
   every other bundled JNI resource is inventoried with its actual owner and hash, not advertised
   as another tested platform.
5. The image is built once from the verified JAR and the digest-pinned Temurin 21.0.11+10 JRE.
   It runs as numeric user `10001:10001`, with entrypoint
   `java -jar /opt/scarab/scarab.jar`. Read-only runtime smoke checks need executable JNI scratch:
   `--tmpfs /tmp:rw,exec,nosuid,size=256m`; Docker's default `--tmpfs /tmp` is `noexec`.
   CLI and portable native/Plutus smoke checks run in the actual image. The existing resource,
   service, multi-release, BuildInfo, BouncyCastle and RocksDB verifier is compiled once in the
   pinned JDK and executed against the actual JRE image's JAR, with no product changes or host Java.
   OCI export validation
   checks every blob, manifest/config, compressed layer digest, uncompressed filesystem identity
   and the exact embedded JAR. The live Docker ID is bound through its retained, hashed descriptor
   graph to exactly one Linux/amd64 configuration; containerd index IDs and legacy configuration
   IDs remain distinct. Archive SHA-256 and OCI manifest/config digests are distinct.
6. Pinned Syft inventories the actual image offline. Each installed dpkg name/version/architecture
   must be represented and the detected Java runtime must match its actual `release` file.
   Scanner guesses about the assembled JAR are replaced by its resolver-backed inventory;
   separate JRE archives remain inventoried. OS and Java legal files are retained from the actual
   image filesystem with hashes. Both final BOMs pass pinned CycloneDX CLI schema validation.
   The portable validator regenerates component/relationship expectations from retained evidence
   and checks subject bindings, rather than accepting a nonempty BOM as sufficient.

The JAR claim is same-byte reproducibility for the existing verifier's bounded inputs. The OCI
claim is **build once from the verified JAR**, with verified content-addressed export and runtime
evidence. It is not a claim of reproducible image bytes across builders, Docker versions or time.
All builds share a trusted Docker engine and download cache; source-built native comparison does
not constitute independent-builder trust or an upstream developer signature.

## Frozen transport contract

`candidate.json` has schema version 1. The public read-only Python API is
`candidate_manifest.validate(directory, require_release=False)`; publication must use
`require_release=True`, separately enforce its approved source/workflow identity, and authenticate
the candidate before treating its self-authored receipts as trusted evidence.

| Field | Meaning |
|---|---|
| `source.buildIdentity` | The existing exact tag/commit/object/version/channel/ref contract, not another version authority |
| `source.archive` | Original Git source tar descriptor |
| `subjects` | Exactly one JAR and one OCI archive, with filenames, descriptors and explicit BOM-subject bindings |
| `image` | Linux/amd64 platform, OCI manifest media type/digest, config digest, pinned base and bounded reproducibility scope |
| `evidence` | Exactly source export, JAR reproducibility, native reproducibility, assembly coverage, JVM resolution, image inventory, toolchain and licence-review descriptors |
| `assurance` | Inventory/hash/coverage/subject-binding scope and separate `review-required` licence status/unresolved count |

File descriptors contain a safe relative `path`, SHA-256 and byte `size`; symlinks, traversal,
duplicate JSON fields, missing/changed files and ambiguous identities fail. BOM subjects bind the
JAR hash or OCI manifest digest, never the image archive's transport hash. The validator also
checks source-archive hashes, original two-build reports, packaged JNI/native receipts, retained
legal files and image/JVM evidence. `signing_subjects` returns the manifest, JAR, both BOMs, OCI
archive and OCI manifest digest. Signing and independent retrieval remain separate #75 stages.

Dependency licence names/URLs are declarations, not a legal conclusion. Original retained legal
bytes and hashes support review. Missing/uninterpreted package licence evidence is listed in
`evidence/licence-review.json`; the manifest always preserves a `review-required` boundary even
when that list is empty. File-inventory entries do not each become a separately licensed package.
Production approval can require owner review of that report. Advisory scanning/triage is separate.

Pinned tooling: [Syft 1.55.0](https://github.com/anchore/syft/releases/tag/v1.55.0),
[CycloneDX CLI 0.30.0](https://github.com/CycloneDX/cyclonedx-cli/releases/tag/v0.30.0),
[CycloneDX 1.6](https://cyclonedx.org/docs/1.6/json/),
[sbt full update report](https://www.scala-sbt.org/1.x/api/sbt/Keys$.html), and
[sbt-assembly 2.5.0 merge rules](https://github.com/sbt/sbt-assembly/blob/v2.5.0/src/main/scala/sbtassembly/MergeStrategy.scala).
Tool/runtime image digests are pinned in the controller and candidate Dockerfile; each attempt
retains their identities and exact JDK/native parameters. Changing them requires renewed evidence.

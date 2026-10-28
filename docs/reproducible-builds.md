# Reproducible node fat JARs

**Status: Current** — `node/assembly` packaging and a two-clean-build verification task. This is
the artifact portion of [O2 / #75](https://github.com/KD5UJC-XYZ/scarab/issues/75).
The [candidate pipeline](release-candidates.md) consumes this contract for SBOMs and local OCI
artifacts; signed releases, provenance attestations and published-image verification remain separate.

## Artifact contract

Inside the [JDK build container](../CONTRIBUTING.md#build-docker-only), run:

```sh
bash scripts/sbtw node/assembly
java --enable-native-access=ALL-UNNAMED -jar node/target/scala-3.3.8/scarab-node-<resolved-version>-assembly.jar <node arguments>
```

The versioned filename follows the build's project and Scala versions. The fat JAR contains the
application, Scala libraries, runtime dependencies and native resources; it needs Java 25 but no
checkout, dependency cache, launcher script or external classpath.

The pinned [sbt-assembly plugin](https://github.com/sbt/sbt-assembly#usage) supplies assembly and
repeatable entry ordering. Fixed package timestamps, relative compiler source paths and stable
generated-source ordering remove build-path and output-state differences. Explicit enum export
selectors keep generated forwarder ordering stable. BuildInfo retains source
identity; `META-INF/scarab/build-info.properties` records the full commit, project/Scala/sbt versions
and JDK vendor/runtime. Direct `node/assembly` invocation packages current working files while
identifying HEAD; use the immutable-source wrapper below for verified release-candidate identity.

## Version identity

Release versions come from the exact selected Git tag. The wrapper is the sole release-version
entrypoint, with this channel contract:

| Channel | Approved source branch | Tag example | Embedded version |
|---|---|---|---|
| `alpha` | `release/alpha` | `v0.2.0-alpha.1` | `0.2.0-alpha.1` |
| `beta` | `release/beta` | `v0.2.0-beta.1` | `0.2.0-beta.1` |
| `preprod` | `release/preprod` | `v0.2.0-rc.1` | `0.2.0-rc.1` |
| `prod` | `release/prod` | `v0.2.0` | `0.2.0` |

`preprod` denotes release-candidate maturity, separately from the Cardano preprod network.
`-preprod.N` and `-prod.N` are not accepted version suffixes. Ordinals allow zero but no leading
zeroes. These planned channel contracts do not create remote branches, deployment environments,
approval settings or publishers; those remain staged under #161/#75.
The pinned [sbt-dynver configuration](https://github.com/sbt/sbt-dynver/tree/v5.1.0) deliberately keeps ordinary workspace builds unreleased,
including a clean checkout at a release tag. In the standard JDK image, which has no Git executable,
that version is `0.0.0-dev.g<FULL_COMMIT>.workspace`; it identifies HEAD without attesting the
working files are clean. With Git available, dynver supplies the nearby tag and dirty state,
and the custom formatter retains an explicit `-dev.g<FULL_COMMIT>` suffix without dates.
Different committed development revisions therefore have different versions. A raw source archive
without Git or frozen export identity is refused instead of falling back to a timestamp.

The existing `--revision <commit>` command remains a development reproducibility check. It exports
only committed files, with deterministic `0.0.0-dev.g<FULL_COMMIT>`, independent of nearby release
tags or subsequent working-tree edits. It does not label an untagged candidate as released.
Revisions predating the verified version-export contract are explicitly refused by this wrapper;
use their historical verifier separately without claiming this release identity contract.

A future trusted release coordinator supplies the selected tag, channel, full expected source SHA,
full expected tag-object SHA and approved source ref. For example, with identities obtained from
that coordinator rather than inferred from a possibly moved tag:

```sh
python3 scripts/reproducible_build.py --revision "$APPROVED_COMMIT" \
  --release-tag "$APPROVED_TAG" --channel preprod \
  --expected-commit "$APPROVED_COMMIT" --expected-tag-object "$APPROVED_TAG_OBJECT" \
  --source-ref refs/remotes/origin/release/preprod --expected-version "$PROPOSED_VERSION" \
  --cache /path/to/dependency-cache --output /tmp/scarab-rc-repro --check
```

Release mode refuses a dirty index/worktree or untracked inputs, shallow history, missing tag/ref,
wrong channel/source, moved tag object or commit, and a proposal version that differs from the tag.
It never auto-selects a tag: prerelease and stable tags may legitimately identify the same source,
but each must be built explicitly with its own exact tag identity and embedded version. An RC JAR
and a stable JAR differ because their embedded versions differ; moving an image alias cannot turn
one into the other. Verify the unsuffixed artifact separately before production use.
The approved `release/<channel>` local/origin-tracking ref must retain the selected cut
as an ancestor. Its later advancement does not alter the frozen version or prevent an immutable
retry of that cut. Deleted or rewritten authority is not silently accepted.

These checks validate local Git inputs; they do not authenticate a publisher, prove branch/tag
protection, authorize publication, or establish that a locally supplied expected identity was
approved. Protected refs, trusted workflow/event inputs and publication authorization remain
[#161](https://github.com/KD5UJC-XYZ/scarab/issues/161) and
[#75](https://github.com/KD5UJC-XYZ/scarab/issues/75). No release tags or repository settings are
created by this wrapper.

Before archive creation, the host resolves the identity and checks it again after export. It writes
sorted `.scarab-build-identity.properties` and binds its SHA-256 in `.reproducible-source.json`.
The latter also records `buildIdentity`: schema version, mode, version, full commit, selected tag
and object, channel, source ref and approved cut commit. These generated inputs transport verified
Git identity; they are not a hand-maintained `VERSION` authority. Tracked files using the reserved
export-metadata names are refused. Both clean copies verify those bytes and receive identical
metadata. The build refuses missing/mismatched fields or a frozen identity injected into a live
checkout. Artifact filenames, compiled BuildInfo, the started log/metrics version and packaged
`META-INF/scarab/build-info.properties` use that identity. The verifier checks the filename and
packaged version/channel/tag/source against the frozen inputs, then checks compiled BuildInfo at
runtime. `build-environment.json` retains the resolved identity alongside the JDK image ID.
After the selected revision's build process exits, the host independently reads both actual JARs,
recomputes their hashes, checks the exact assembly-name reports and embedded/manifest versions,
and compares their complete bytes. A separate container runs the caller's runtime verifier against
both JARs; the selected revision's own PASS message cannot replace these checks.
`verified-artifacts.json` records those outer checks and the caller-verifier hash. Historical
verifiers lacking the output contract, changed artifacts and mismatched metadata are refused.

The host controls use temporary local repositories:
`python3 -m unittest scripts.tests.test_release_identity scripts.tests.test_reproducible_build`.
Inside the JDK image, `python3 scripts/check_build_identity.py` exercises the actual sbt settings
in isolated minimal projects, including alpha/beta/preprod/prod/development archives and refused metadata.
CI runs these sbt controls inside the JDK Docker image after its ordinary full check, including
with Python optimization enabled so control failures cannot disappear with assertions.
The full verifier additionally compiles and smokes the actual node artifacts. SBOMs, provenance and
image labels must consume this same identity when their separate implementation lands; this PR
does not claim those outputs exist.

## Resource merging

The assembly uses the plugin's documented defaults unless an explicit rule is needed:

- Service descriptors merge distinct lines; configuration resources use the standard concatenation
  rule. Unexpected differing classes or other resources fail deduplication.
- License and notice metadata are retained through the standard rename strategy. Scarab's own
  `LICENSE` and `NOTICE` remain under `META-INF/scarab/`; dependency metadata is not an SBOM.
- The committed portable Linux/amd64 blst resource wins over blst-java's alternate resource at the
  same path. Other native resources retain their original bytes, among them `libscalus_crypto` of
  scalus-crypto-jni (pinned by hash at start-up, [ADR 0020](adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md)).
  Packaging does not rebuild them.
- Dependency manifests, obsolete JAR indexes and invalidated dependency signature files are removed.
  The resulting artifact is unsigned. Dependency module descriptors are omitted because the single
  assembled application has no combined Java module descriptor.
- Multi-release class entries are retained, and the new manifest enables multi-release lookup.

There is no blanket first-wins rule, class shading or handwritten ZIP packager. Verification checks
service-provider preservation, dependency legal metadata, native and multi-release resource bytes,
the portable blst overlay, source identity and the absence of an external manifest classpath.

## Two independent clean builds

From the host, with Docker available and the JDK image built as CONTRIBUTING describes:

```sh
python3 scripts/reproducible_build.py --revision <commit> \
  --cache /path/to/dependency-cache --output /tmp/scarab-repro-check --check
```

Commit the candidate first; the output directory must be fresh. `--check` includes the repository's
full gate in the first build. The default image is `scarab-jdk:local`; `--image` selects a locally
available image, resolved to its immutable local image ID before execution. The wrapper fixes UTC
and a UTF-8 locale.

The wrapper exports the requested commit with `git archive`, hashes every exported input and starts
the first-class sbt task `reproducibleCheck`. That task creates two fresh source trees at different
absolute paths, verifies the frozen inputs and runs a separate sbt JVM in each. Neither receives
compiled classes, managed sources, Zinc state, project outputs or assembled artifacts from the
checkout or the other build. Each has a private sbt global directory. Only downloaded launcher,
boot, Ivy and Coursier dependencies share a cache.

Both builds execute `node/assembly` and compare the complete fat-JAR bytes. Each artifact runs from
an empty temporary directory using only that one JAR: `java -jar ... --help`, actual JVM directory,
service-provider and multi-release lookups, BuildInfo identity, BouncyCastle cipher and RocksDB
native loading, and the existing `BlstCheckMain`
through `java -cp <same-fat-jar>`. The latter verifies portable BLS startup and Plutus builtins;
this native smoke profile targets Linux/amd64. No dependency jars enter either runtime classpath.

The output contains both JARs, separate build logs and `build-environment.json` with the source
commit and image ID. A mismatch names differing JAR entries, or reports metadata/ordering when
contents match. Changed frozen source also fails. These local diagnostics are not a signed auditor
evidence bundle or provenance attestation.

The contract is identical committed source, JDK image/runtime, sbt/compiler/plugin versions and
resolved dependency bytes. The existing pinned dependencies and SHA-256-checked sbt launcher are
build inputs; dependency versions alone do not authenticate downloaded bytes. The two builds use
one image and one trusted download cache, so this does not establish independent dependency supply
chains, cross-JDK or cross-platform reproducibility. The [native source rebuild and byte comparison](../blst-native/native/README.md)
is a separate check from preserving packaged native bytes. The [candidate pipeline](release-candidates.md)
combines those contracts with SBOM/image inventories. Signed releases, independent dependency
authentication, provenance attestations and published-image verification remain under #75.

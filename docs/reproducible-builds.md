# Reproducible node fat JARs

**Status: Current** — `node/assembly` packaging and a two-clean-build verification task. This is
the artifact portion of [O2 / #75](https://github.com/KD5UJC-XYZ/scarab/issues/75); signed releases,
SBOMs, provenance attestations and published-image reproducibility remain separate work.

## Artifact contract

Inside the [JDK build container](../CONTRIBUTING.md#build-docker-only), run:

```sh
bash scripts/sbtw node/assembly
java -jar node/target/scala-3.3.8/scarab-node-0.1.0-assembly.jar <node arguments>
```

The versioned filename follows the build's project and Scala versions. The fat JAR contains the
application, Scala libraries, runtime dependencies and native resources; it needs Java 21 but no
checkout, dependency cache, launcher script or external classpath.

The pinned [sbt-assembly plugin](https://github.com/sbt/sbt-assembly#usage) supplies assembly and
repeatable entry ordering. Fixed package timestamps, relative compiler source paths and stable
generated-source ordering remove build-path and output-state differences. Explicit enum export
selectors keep generated forwarder ordering stable. BuildInfo retains source
identity; `META-INF/scarab/build-info.properties` records the full commit, project/Scala/sbt versions
and JDK vendor/runtime. Direct `node/assembly` invocation packages current working files while
identifying HEAD; use the immutable-source wrapper below for verified release-candidate identity.

## Resource merging

The assembly uses the plugin's documented defaults unless an explicit rule is needed:

- Service descriptors merge distinct lines; configuration resources use the standard concatenation
  rule. Unexpected differing classes or other resources fail deduplication.
- License and notice metadata are retained through the standard rename strategy. Scarab's own
  `LICENSE` and `NOTICE` remain under `META-INF/scarab/`; dependency metadata is not an SBOM.
- The committed portable Linux/amd64 blst resource wins over blst-java's alternate resource at the
  same path. Other native resources retain their original bytes. Packaging does not rebuild them.
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
chains, cross-JDK or cross-platform reproducibility. Rebuilding the committed libblst from its
source recipe remains [#229](https://github.com/KD5UJC-XYZ/scarab/issues/229), distinct from preserving
the packaged native bytes. Signed releases, dependency verification, SBOM/provenance and published
image verification remain under #75.

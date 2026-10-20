# Reproducible node artifacts

**Status: Current** — deterministic runtime packaging and a two-clean-build check. This is the
artifact portion of [O2 / #75](https://github.com/KD5UJC-XYZ/scarab/issues/75); it does not complete
signed releases, an SBOM, provenance attestations, published images or a fat jar.

## Artifact contract

Inside the [JDK build container](../CONTRIBUTING.md#build-docker-only), run
`bash scripts/sbtw node/releaseArchive`. It creates `node/target/release/scarab-node-<version>.zip`.
Extract it and run `sh scarab <node arguments>` with Java 21 available. The launcher resolves its
own directory; no checkout, dependency cache or build path is required at runtime.

The ZIP contains an explicit `classpath.txt`, local module jars, unchanged resolved dependency jars,
`build-info.properties`, per-jar SHA-256 sums, `LICENSE` and `NOTICE`. Classpath order is preserved,
including the portable blst resource before blst-java. Dependency jars retain their resources,
service descriptors and signatures; packaging does not flatten or merge them.

Entries are sorted, stored without compression and use a fixed 1980 timestamp. Module jars use the
same rules. The Scala compiler records source paths relative to the checkout, and generated source
files are returned in stable order. BuildInfo retains the source commit, project version, Scala
version and sbt version; the artifact additionally records the full commit and JDK vendor/runtime.
Identical builds require identical source, JDK runtime, sbt/compiler versions and resolved dependency
bytes. The existing pinned versions and sha256-checked sbt launcher remain the build inputs.
This is not a claim that dependency versions alone authenticate downloaded bytes.

## Two clean builds

From the host, with Docker available and the JDK image built as CONTRIBUTING describes:

```sh
python3 scripts/reproducible_build.py --revision <commit> \
  --cache /path/to/dependency-cache --output /tmp/scarab-repro-check
```

The output directory must be fresh. The default image is `scarab-jdk:local`; `--image` selects a
locally available image. The script resolves it to its immutable local image ID before execution,
exports the requested commit with `git archive`, and hashes every exported input. Uncommitted and
untracked files are excluded. Commit the candidate before checking it.

The wrapper starts the first-class sbt task `reproducibleCheck` in that JDK image. The task creates
two fresh source trees at different absolute paths, verifies their inputs against the frozen export,
and runs a separate sbt JVM in each. Neither receives compiled classes, managed sources, Zinc state,
project outputs or packaged artifacts from the checkout or the other build. Each has its own sbt
global directory. Only downloaded launcher, boot, Ivy and Coursier dependencies share a cache.
Both builds compile and package the node before comparing the complete distributable bytes.
Each extracted artifact must also run the CLI launcher and pass the portable BLS native-library
and Plutus-builtin startup checks. Add `--check` to run the full repository gate in the first build.

The output contains both ZIPs, separate complete build logs and `build-environment.json` with the
source commit and image ID. A mismatch fails and names differing archive entries, or reports ZIP
metadata/ordering when entry contents match. A source change after export also fails. These local
outputs are verification diagnostics, not a signed auditor evidence bundle or provenance attestation.

The two builds use one image and one resolved dependency cache. This catches output-state, source
path, timestamp and packaging nondeterminism; it does not establish independent dependency supply
chains, cross-JDK or cross-platform reproducibility. Rebuilding the committed native libblst from
its source recipe remains [#229](https://github.com/KD5UJC-XYZ/scarab/issues/229). The packaged native
resource is preserved byte-for-byte, which is a different guarantee.

The current release workflow is not implemented. Publishing a fat jar needs explicit resource and
native-library merge policy, and image publication, dependency verification, signed SBOM/provenance
and user signature verification remain under #75. Do not describe this packaging task as a signed
release or as having rebuilt the native libraries.

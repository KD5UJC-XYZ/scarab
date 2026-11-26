# Dependency licences

**Status: Historical dependency inventory.** The resolved versions and licence review below
describe the recorded build, not a continuously generated SBOM; regenerate them for a release
or external audit under [O2](roadmap.md#o-review-readiness-and-operations).

Research software, not legal advice. This is a record of what the build resolves, so that the list in
[NOTICE](../NOTICE) can be checked against it.

## How this was produced

The Runtime classpath of the `node` project (its transitive runtime dependencies, excluding
test-only and generator modules)
was written with `sbt node/runtimeClasspathFile` in the JDK image, first at v0.1.0 plus this review and
again for the Scalus 1.4.0 update (2026-10-25). For every jar on it, the `<licenses>` element of its POM
in the Coursier cache was read; where a POM declares none, the parent POM chain was followed. 63
third-party jars resolve (64 before log4s was excluded, below), 23 of them direct dependencies (the 22 runtime libraries of
`project/Dependencies.scala` and the Scala 3 library). At v0.1.0 jar contents were listed for licence
files and native libraries; for the Scalus 1.4.0 update only the jar of scalus-crypto-jni was. The
licence names below are the POM names, normalised to SPDX identifiers where they are unambiguous.

The rows for dnsjava, kittens, scodec-core, cats-parse, monocle, and slf4j-api were added when
`NOTICE` and this file were reconciled with `project/Dependencies.scala` (checked by `scripts/check_notice.py`).
Their licences were read from the POMs of the declared versions on Maven Central. The regeneration for the
Scalus 1.4.0 update found two more jars, alleycats-core and shapeless3-deriving (transitive through
kittens), whose rows were added from their POMs in the Coursier cache.

On 2026-11-15 the Scalus backend was removed
([ADR 0027, amendment of 2026-11-15](adr/0027-own-uplc-evaluator-as-a-correctness-backend.md#amendment-2026-11-15-the-own-evaluator-is-the-only-evaluator)).
The rows of what only Scalus brought were removed from the tables below: Scalus itself; the runtime
dependencies its POM declares that no other cached POM declares (paiges-core, upickle with ujson, upack,
upickle-implicits, upickle-core and geny, jsoniter-scala-core and jsoniter-scala-macros, borer-core and
borer-derivation); and scalus-crypto-jni with native-lib-loader. They were found by reading the POMs in the
Coursier cache, not by a new resolution of the class path, which a release regeneration confirms. The counts
above are those of 2026-10-25.

`node` excludes `org.log4s:log4s_3` (#214), which http4s-core declares. On 2026-10-25 the class files of
all 76 entries of `node/runtimeClasspathFile` (36,645 classes: every jar and the class directories of the
project's modules) were searched for the names `org/log4s/` and `org.log4s`: only log4s's own classes
contain them, and its jar registers no service. Without it `node/releaseInventory` resolves the same
modules less `log4s_3` 1.10.0, and the assembly holds no `org/log4s/` entry. log4cats stays: its names
appear in 47 classes of http4s-core, -server, -ember-core, -ember-server and -circe, and
`EmberServerBuilder.default`, which `QueryServer.resource` and `MetricsServer.resource` call, builds its logger with
`log4cats.slf4j.Slf4jLogger.getLogger`. That logger is how http4s's lines reach the node's log through
SLF4J (`node/log/LibraryLogs.scala`).

## Result

- No dependency is GPL-only, LGPL, AGPL, proprietary or without a declared licence. Every artifact is
  Apache-2.0, MIT, BSD-3-Clause, Bouncy Castle (MIT-style) or the RocksDB dual licence; the SQLite natives
  that sqlite-jdbc bundles are public domain. All of
  these are compatible with distributing this project under Apache-2.0.
- RocksDB (`rocksdbjni`) is `Apache-2.0 OR GPL-2.0-only`. The project uses it under Apache-2.0. The GPL
  option is not elected and no GPL-2.0-only obligations arise.
- No dependency is redistributed in this repository: dependencies are retrieved from Maven Central at build
  time (the third-party code the repository does hold is the next item's). The node's assembly JAR (and the
  candidate OCI image, which adds only that JAR to its base) redistributes all of the below, so it carries their
  licence texts: see [Licence texts in binaries](#licence-texts-in-binaries).
- Three sources come from MIT-licensed code rather than from dependencies: the draft03 VRF arithmetic
  (`crypto/src/main/java/xyz/kd5ujc/scarab/crypto/vrf`, from Bloxbean CCL; notice in
  `fixtures/vrf/licenses/CCL-LICENSE`); `MuslMath` and `MuslData` (`ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules`),
  a port of musl 1.2.5's `log`, `pow` and `exp` (Arm's optimized-routines); and the Edwards-curve arithmetic of
  curve25519-elisabeth (`crypto/src/main/java/com/weavechain`), vendored from its source, with `VartimeEd25519`
  (`crypto/src/main/java/xyz/kd5ujc/scarab/crypto/witness`), the same arithmetic written over arrays its caller
  passes: MIT, with a BSD-3-Clause part and CC0 portions (see
  [curve25519-elisabeth provenance](#curve25519-elisabeth-provenance)). The musl notice is
  `ledger/licenses/LICENSE-musl`, which the assembly carries as `META-INF/scarab/licenses/ledger/LICENSE-musl`, and
  curve25519-elisabeth's is `crypto/licenses/LICENSE-curve25519-elisabeth`, carried as
  `META-INF/scarab/licenses/crypto/LICENSE-curve25519-elisabeth`.

## Native libraries bundled in jars

| Jar | Native content | Upstream licence | Note |
|---|---|---|---|
| `org.rocksdb:rocksdbjni:10.2.1` | `librocksdbjni` for Linux (x86, x86_64, aarch64, ppc64le, s390x, riscv64; glibc and musl), macOS (x86_64, arm64), Windows x64 | Apache-2.0 OR GPL-2.0-only (RocksDB) | The jar has no licence file. RocksDB statically links compression libraries (zlib, snappy, lz4, zstd, bzip2; BSD- or zlib-style licences) whose texts are not in the jar; the assembly adds them (see [Licence texts in binaries](#licence-texts-in-binaries)). Only the Linux x86_64 library is exercised by this project's Docker build. |
| `org.xerial:sqlite-jdbc:3.54.1.0` | `libsqlitejdbc` (SQLite 3.54.1 with the JNI glue) for Linux (x86, x86_64, arm, armv6, armv7, aarch64, ppc64, riscv64; glibc, and musl for x86, x86_64 and aarch64), FreeBSD (x86, x86_64, aarch64), macOS (x86_64, aarch64), Windows (x86, x86_64, armv7, aarch64) | Apache-2.0 (sqlite-jdbc), with the BSD-style notice of the Zentus code it derives from; SQLite is public domain | The jar carries both licence texts (`META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` and its Zentus notice), kept as `licenses/sqlite-jdbc-3.54.1.0/LICENSE` and `licenses/sqlite-jdbc-3.54.1.0/LICENSE.zentus`, which the assembly packages. Used only by the offline import and export of a Leios reference database (`storage/src/main/scala/xyz/kd5ujc/scarab/storage/leios/LeiosReferenceDb.scala`), never by the running node. Only the Linux x86_64 library is exercised by this project's Docker build. |
| `tech.pegasys:jblst:0.3.18` | `libblst` (supranational/blst v0.3.17) for Linux amd64/aarch64, macOS x86_64/arm64, Windows amd64; the Linux amd64 one is a portable build | Apache-2.0 (supranational/blst) and MIT (the jblst repository's `LICENSE`) | No licence file in the jar, and its POM declares Apache-2.0 while the repository's `LICENSE` is MIT; both texts ship (`licenses/jblst-0.3.18/LICENSE`, `crypto/licenses/LICENSE-blst`). Used by the Leios BLS keys (`MinSig`) and the BLS12-381 builtins of Plutus; hashes pinned in [ADR 0025](adr/0025-jblst.md). |

Earlier `NOTICE` text said these natives are bundled "for linux x86_64"; the jars hold several platforms
and `NOTICE` now says so.

## Runtime classpath

Every row is on the Runtime classpath (Compile dependencies are a subset). Test-only libraries (munit,
munit-scalacheck, munit-cats-effect, discipline-munit, cats-laws, cats-mtl-laws, cats-kernel-laws,
cats-effect-testkit, monocle-law) are not shipped; their licences in `NOTICE` come from earlier POM reads and
were not regenerated here.

| Artifact | Version | Licence | Kind | Note |
|---|---|---|---|---|
| org.scala-lang:scala3-library_3 | 3.9.0 | Apache-2.0 | direct |  |
| org.typelevel:cats-core_3 | 2.13.0 | MIT | direct |  |
| org.typelevel:cats-effect_3 | 3.7.1 | Apache-2.0 | direct |  |
| org.typelevel:cats-mtl_3 | 1.7.0 | Apache-2.0 | direct |  |
| dnsjava:dnsjava | 3.6.5 | BSD-3-Clause | direct | Relay-root DNS resolver. POM read from Maven Central. |
| org.typelevel:kittens_3 | 3.5.0 | Apache-2.0 | direct | POM read from Maven Central. |
| org.scodec:scodec-core_3 | 2.3.3 | BSD-3-Clause | direct | POM read from Maven Central. |
| org.typelevel:cats-parse_3 | 1.1.0 | MIT | direct | The build tool `models-gen` declares it directly as well. |
| dev.optics:monocle-core_3 | 3.3.0 | MIT | direct |  |
| dev.optics:monocle-macro_3 | 3.3.0 | MIT | direct |  |
| org.slf4j:slf4j-api | 2.0.17 | MIT | direct | Declared at 2.0.17 in `Dependencies.scala`; POM parent states MIT. |
| org.rocksdb:rocksdbjni | 10.2.1 | Apache-2.0 OR GPL-2.0-only | direct | Dual licence; Apache-2.0 elected. The jar bundles native libraries (see below). |
| org.xerial:sqlite-jdbc | 3.54.1.0 | Apache-2.0 | direct | The import and export of a Leios reference database only. Its POM's one runtime dependency, SLF4J's API, is optional. The jar bundles native libraries (see above). POM read from Maven Central (2026-11-18). |
| io.circe:circe-parser_3 | 0.14.16 | Apache-2.0 | direct |  |
| org.http4s:http4s-ember-server_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.http4s:http4s-dsl_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.http4s:http4s-circe_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.bouncycastle:bcprov-jdk18on | 1.86 | Bouncy Castle Licence (MIT-style) | direct | The jar carries META-INF/LICENSE.md. |
| org.typelevel:cats-kernel_3 | 2.13.0 | MIT | direct |  |
| co.fs2:fs2-core_3 | 3.14.0 | MIT | direct |  |
| co.fs2:fs2-io_3 | 3.14.0 | MIT | direct |  |
| org.scala-lang:scala-library | 3.9.0 | Apache-2.0 | transitive | The Scala 3 standard library from Scala 3.8; the Scala 2.13 library is no longer on the class path. |
| org.typelevel:cats-effect-kernel_3 | 3.7.1 | Apache-2.0 | transitive |  |
| org.typelevel:cats-effect-std_3 | 3.7.1 | Apache-2.0 | transitive |  |
| tech.pegasys:jblst | 0.3.18 | Apache-2.0 AND MIT | direct (crypto, Leios BLS) | Bundles native libblst (supranational/blst v0.3.17, Apache-2.0) for Linux, macOS and Windows; its POM's Guava runtime dependency is excluded (no class uses it). |
| io.circe:circe-jawn_3 | 0.14.16 | Apache-2.0 | transitive |  |
| io.circe:circe-core_3 | 0.14.16 | Apache-2.0 | transitive |  |
| org.http4s:http4s-ember-core_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.http4s:http4s-server_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.typelevel:log4cats-slf4j_3 | 2.8.0 | Apache-2.0 | transitive | Used: `EmberServerBuilder.default` builds its logger with `Slf4jLogger` (see above). |
| org.http4s:http4s-core_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.http4s:http4s-jawn_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.scodec:scodec-bits_3 | 1.2.5 | BSD-3-Clause | transitive |  |
| com.comcast:ip4s-core_3 | 3.8.0 | Apache-2.0 | transitive |  |
| org.typelevel:cats-free_3 | 2.12.0 | MIT | transitive |  |
| org.typelevel:jawn-parser_3 | 1.7.0 | MIT | transitive |  |
| io.circe:circe-numbers_3 | 0.14.16 | Apache-2.0 | transitive |  |
| org.typelevel:log4cats-core_3 | 2.8.0 | Apache-2.0 | transitive | Used by http4s's server and Ember (see above). |
| com.twitter:hpack | 1.0.2 | Apache-2.0 | transitive |  |
| org.typelevel:case-insensitive_3 | 1.5.0 | Apache-2.0 | transitive |  |
| org.http4s:http4s-crypto_3 | 0.2.5 | Apache-2.0 | transitive |  |
| org.typelevel:literally_3 | 1.2.0 | Apache-2.0 | transitive |  |
| org.typelevel:idna4s-core_3 | 0.1.0 | MIT | transitive |  |
| org.typelevel:cats-collections-core_3 | 0.9.10 | MIT and BSD-3-Clause | transitive | The POM lists both. |
| org.typelevel:algebra_3 | 2.13.0 | MIT | transitive |  |
| org.typelevel:vault_3 | 3.7.0 | MIT | transitive |  |
| org.typelevel:jawn-fs2_3 | 2.6.0 | Apache-2.0 | transitive |  |
| org.typelevel:alleycats-core_3 | 2.13.0 | MIT | transitive | Brought by kittens_3 (3.5.0). |
| org.typelevel:shapeless3-deriving_3 | 3.5.0 | Apache-2.0 | transitive | Brought by kittens_3 (3.5.0). |

## curve25519-elisabeth provenance

`com.weavechain:curve25519-elisabeth:0.1.3` (#199) was traced on 2026-10-25 from the jar to its source
and to the components its `LICENSE` and README name.

Since #1418 it is no longer a dependency. Its source is vendored in `crypto/src/main/java/com/weavechain`: the
17 Java files of `src/main/java` of weavechain/curve25519-elisabeth at `81bd79d`, the revision published as
0.1.3 (below), in their packages `com.weavechain.curve25519` and `com.weavechain.subtle`. `module-info.java`
is not vendored. Each file starts with the SPDX line of a source adapted from code under other licences, above
the upstream notice it carries: `Apache-2.0 AND MIT`, and `Apache-2.0 AND MIT AND BSD-3-Clause` for
`UnpackedScalar.java`, the arithmetic ported from curve25519-dalek (below). The portions from eddsa-java add no
identifier: they are CC0, which asks for no notice, and elisabeth's `LICENSE` states them. The `package-info.java`
of each package is Scarab's own (`Apache-2.0`). From then on they are Scarab's sources. Later in #1418 the decoders
came to return an `Optional` instead of throwing, so `InvalidEncodingException.java`, which only they threw, was
removed, and so were the `java.io.Serializable` hooks of five classes, which nothing in Scarab serializes. The
ristretto255 group, which nothing in Scarab used, was removed after them: `CompressedRistretto.java`,
`RistrettoElement.java` and `RistrettoGeneratorTable.java`, with the constants only they read (the generator and its
table and five field constants in `Constants`, and the constant -1 of `FieldElement`). 13 of the 17 files remain. `VartimeEd25519.java`, in
`xyz.kd5ujc.scarab.crypto.witness`, writes the field arithmetic, point formulas, decoding and double-scalar
multiplication of `FieldElement`, `CompletedPoint`, `ProjectivePoint`, `EdwardsPoint`, the Niels points,
`CompressedEdwardsY` and `Scalar.nonAdjacentForm` again over arrays its caller passes (#1418); it carries the same
SPDX line, `Apache-2.0 AND MIT`, and names its source and the notice.

| Item | Identity |
|---|---|
| Jar (Maven Central, as in the Coursier cache) | 52,823 bytes, SHA-256 `256048db6904c00832ab6045c624c69844d7617f719e3cd446691257aab8ffcb`, SHA-1 `918d9db1face8ef8ad5ceb33664399a8a4701e45` (equal to Central's `.sha1`) |
| POM | SHA-256 `d51e81c745e08d206c2f174dba2027cae08057633a23fda45f6a7c632601f11a`; licence "MIT License"; SCM github.com/weavechain/curve25519-elisabeth; developer Jack Grigg |
| Sources jar (Central) | SHA-256 `6259b983a6368c4a39eb39f3db1e78193ba9b82da5d7ec6ea42ccc5514328dda`, SHA-1 `6a2869613b6d618289d4a1dcbf0340005f9caf56` (equal to Central's `.sha1`) |
| Source revision | weavechain/curve25519-elisabeth `81bd79dbd0b323e5fc5729bca883d124b95b4e54` (2023-06-14, "Package update, namespace change for publishing"), the only commit whose `build.gradle.kts` says `version = "0.1.3"`. The repository has no tags. |
| Fork base | cryptography-cafe/curve25519-elisabeth `26c014bda6cf87498b9180e8b2ec8024fdb95e80` (2022-06-19, its last commit), an ancestor of `81bd79d` |

How the jar was matched to the revision:

- The sources jar holds the same 17 Java files as `src/main/java` at `81bd79d` (without `module-info.java`,
  which the build compiles separately), identical apart from CRLF line endings.
- `81bd79d` compiled with `javac -g --release 11` (JDK 21.0.11, the `scarab-jdk:local` image; the original
  toolchain is not recorded) gives the jar's 22 classes. 18 are byte-identical. The other four
  (`EdwardsPoint`, `FieldElement`, `RistrettoElement`, `Scalar`) have the same `javap -v -c -p -l`
  output outside the constant pool and the same set of pool entries, in a different order (the
  string-concatenation bootstrap entries). The jar's classes are class-file version 55 (Java 11), as the
  build file sets. The jar's `.asc` signature on Central was not checked against a trusted key.

Licence notices, each checked at its source:

| Component | What is used | Licence | Source checked |
|---|---|---|---|
| curve25519-elisabeth | the library | MIT, Copyright (c) 2019 Jack Grigg | `LICENSE` at `81bd79d` (blob `685a9b1291cddff06d38ab3d8520633822acb3fd`, unchanged since cryptography-cafe `77969505e166e1d6d06ec80f8d47df6caa845937`, 2019-03-16); every source file except `StrUtils.java` (no header) carries "Copyright (c) 2019 Jack Grigg, See LICENSE". Weavechain's changes in `81bd79d` (namespace; `Scalar.square`, `invert` and `divide`) add no notice of their own. |
| eddsa-java (str4d/ed25519-java) | field and scalar arithmetic, point decompression and `Scalar` radix/NAF code ("Extracted from ed25519-java, License: CC0" in the cafe commits of 2019-02-03 to 2019-02-05) | CC0 1.0 | `LICENSE.txt` at `901aec0383938a0d9992649231af7dd7ccd2b387`, the last commit before 2019-02-03 (blob `bb127922f0790edfc6f0269d191a082478571be0`, unchanged since 2016). CC0 asks for no notice. |
| ref10 (SUPERCOP Ed25519) | the origin of eddsa-java's arithmetic | public domain | eddsa-java's README; ed25519.cr.yp.to/software.html: "The Ed25519 software is in the public domain." |
| curve25519-dalek | `UnpackedScalar` (`UnpackedScalar.java`, whose SPDX line adds BSD-3-Clause; radix-2^29 Montgomery scalar arithmetic, cafe commit `e4e2354`, 2019-02-13), and test vectors that are neither in the jar nor vendored | BSD-3-Clause, Copyright (c) 2016-2018 Isis Agora Lovecruft, Henry de Valence | elisabeth's `LICENSE` reproduces lines 1 to 28 of dalek's `LICENSE` at tag `1.0.3` (`9cd4551b40a7328eed18717b527bc8ba9331fa27`, blob `d94fdb512eaab593968ff565e62f20b2c8787975`) word for word. That file goes on with a notice for parts derived from Adam Langley's Go ed25519 (agl/ed25519). That notice does not apply here: the ported code is dalek's `src/backend/u32/scalar.rs`, and agl/ed25519 at `5312a61534124124185d41f09206b9fef1d88403` has only ref10's radix-2^21 `ScMulAdd`/`ScReduce`, no Montgomery scalar code. |

The full text, with all three notices, is `crypto/licenses/LICENSE-curve25519-elisabeth`. `NOTICE`
names it. The assembly JAR carries it as `META-INF/scarab/licenses/crypto/LICENSE-curve25519-elisabeth`
(see [Licence texts in binaries](#licence-texts-in-binaries)).

Maintenance, as of 2026-10-25: neither repository is archived. Central has 0.1.2 to 0.1.5, the last
published on 2023-08-06. weavechain's last commit (2025-03-07) sets version 0.1.7, which was never
published. The 0.1.4 and 0.1.5 releases add Straus and Pippenger multiscalar multiplication, which
Scarab does not use. The source vendored is that of 0.1.3: it is traced to source and reproduces from it, and
the strict prototype of the Ed25519 witness corpus ran on that release (`strict_prototype` in
`fixtures/witness/manifest.json`). The expected verdicts of the Ed25519 and VRF corpora are not this library's:
they come from the libsodium builds their fixtures name (`fixtures/witness/README.md`, `fixtures/vrf/README.md`).
Any change to the vendored arithmetic has to keep the suites that run it on those corpora and vectors passing
unchanged (`StrictEd25519Suite`, `StrictEd25519DifferentialSuite`, `ByronEd25519ReferenceSuite`,
`Draft03ProverSuite`).

## Licence texts in binaries

The assembly JAR (`node/assembly`, the artifact of `scripts/build_release_candidate.py`) carries, besides
this repository's `LICENSE` and `NOTICE` in `META-INF/scarab/`, the licence texts of everything bundled in
it under `META-INF/scarab/licenses/`, with `licenses/index.json` beside them (#75). The index names, for
each resolved runtime artifact (`group:artifact:version`) and each native library in the JAR (every `.so`,
`.dll`, `.dylib` and `.jnilib` entry, as `scripts/assembly_inventory.py` finds them), the texts its licence asks
a binary to carry, and where each text comes from:

- a file of an upstream Git revision (repository, tag and the commit the tag named when it was fetched);
- the component's own jar, where the jar ships its text (slf4j-api, dnsjava, ip4s-core,
  bcprov);
- or a text kept elsewhere in this repository with its own provenance (`crypto/licenses/`),
  packaged under the module's name.

Two texts are not a dependency's, and are packaged the same way: `ledger/licenses/LICENSE-musl`, the notice of
the musl code that `MuslMath` and `MuslData` adapt, as `META-INF/scarab/licenses/ledger/LICENSE-musl`; and
`crypto/licenses/LICENSE-curve25519-elisabeth`, the notice of the vendored curve25519-elisabeth source, as
`META-INF/scarab/licenses/crypto/LICENSE-curve25519-elisabeth`.

The upstream legal files that jars carry at their root or in `META-INF/` are kept as well: the merge
strategy renames them per artifact (for example `META-INF/LICENSE_dnsjava-3.6.5`), and
`scripts/assembly_inventory.py` fails a build that loses or changes one.

What each licence asks of a binary, as its text says:

| Licence | Clause | Components |
|---|---|---|
| MIT (and the Bouncy Castle licence, an MIT licence) | "The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software." | cats, fs2, monocle, cats-parse, vault, idna4s, jawn, slf4j-api, bcprov, curve25519-elisabeth |
| BSD-2-Clause, BSD-3-Clause | "Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution." | dnsjava, scodec, scodec-bits, the third-party sections of the COPYING files of cats, cats-mtl and cats-collections, the argonaut and ephox notices of circe, curve25519-dalek (in elisabeth's LICENSE), LevelDB (`LICENSE.leveldb` of RocksDB), snappy, lz4, zstd, the Zentus notice of sqlite-jdbc (`licenses/sqlite-jdbc-3.54.1.0/LICENSE.zentus`) |
| Apache-2.0 | §4(a) "give any other recipients of the Work or Derivative Works a copy of this License"; §4(d) "include a readable copy of the attribution notices contained within such NOTICE file" | Scala 3 and its standard library, cats-effect, circe, http4s, http4s-crypto, log4cats, kittens, ip4s, case-insensitive, literally, jawn-fs2, shapeless-3, hpack, RocksDB (Apache-2.0 elected), jblst (with its MIT text as well), blst, sqlite-jdbc; each NOTICE file upstream has is packaged |
| zlib, bzip2 | conditions on source distributions and misrepresentation only; the texts are included anyway | zlib, bzip2 |
| CC0, public domain | none | eddsa-java, ref10 (both inside curve25519-elisabeth) |

Two upstreams publish no licence file. jawn 1.7.0 states "All code is available to you under the MIT
license" and its copyright in its README, and shapeless-3 3.5.0 states the Apache License v2 in its
README. For both, the README at the tag is packaged with the SPDX licence text (spdx/license-list-data
v3.29.0).

The RocksDB natives link zlib 1.3.1, bzip2 1.0.8, snappy 1.2.1, lz4 1.9.4 and zstd 1.5.5, the versions of
RocksDB's `Makefile` at v10.2.1. The Linux and macOS binaries show `deflate 1.3.1`, `1.0.8, 13-Jul-2019`
and `snappy-1.2.1` paths, and they reference jemalloc only weakly (it is not linked). The Windows DLL is
built differently: it shows `deflate 1.2.13` and no bzip2, and its snappy, lz4 and zstd versions are not
recorded in any source of RocksDB 10.2.1. Its texts are those of the versions above; only the Linux
x86_64 library is run by this project.

Checks:

- `scripts/assembly_inventory.py` (`export`, run by `scripts/reproducible_build.py --inventory` and so by
  every candidate build) reads the index from the JAR. It fails when a resolved component or a packaged
  native library has no entry, when an entry names something not in the JAR (a removed dependency or an
  old version), or when a text is missing or differs from its recorded SHA-256.
- `scripts/check_notice.py` (CI) checks that every text in `licenses/` has its recorded SHA-256 and that
  the index names exactly the artifacts of the runtime table above.
- After a dependency change, `python3 scripts/licence_texts.py fetch` writes the texts named by the index
  (it checks that each tag still names the recorded commit and reads jar entries from the Coursier cache)
  and records their digests. A new component or version needs its entry first: the source and the texts
  it must carry.

The OCI image of a candidate is the pinned Temurin base plus the JAR (`docker/candidate.Dockerfile`), so
the JAR's texts cover what the image adds; the base image's own packages keep their copyright files,
which the candidate's OS inventory records.

## Re-running

```sh
docker run --rm --user 1000:1000 -e HOME=/work/.cache/home -v "$PWD":/work -w /work scarab-jdk:local \
  bash scripts/sbtw node/runtimeClasspathFile
```

then read the POM `<licenses>` of each jar in `node/target/runtime-classpath.txt`. Re-run it whenever
dependency versions in `build.sbt` change, and update `NOTICE` and this file together.

## Name and trademark wording

Checked against the Cardano Foundation Trademark Policy (version "Updated 06.03.24", read from
cardanofoundation.org/policy/trademark-policy on 2026-10-11). Appendix 1 lists the word marks ADA,
ADACOIN, ADAPAY, CARDANO and CARDANO FOUNDATION, plus the logos.

- Compatibility is nominative use: section 2.1 allows using the marks "to indicate that your product
  is compatible with the Cardano Code". The project says it is a Cardano-compatible node and never
  that it distributes the Cardano Foundation or IOG software.
- Section 3.1 grants automatic permission for a software package title "exclusively intended to
  support the Cardano Code and Project" (or to "extend or improve" it) when no official sponsorship
  is implied. This repository is a free, open-source, unofficial compatible implementation, so the
  name `Scarab` falls under it. The policy's own permitted example is a free theme called
  "Gold Plated Cardano".
- Section 3.3 requires written permission for domain names, company or organisation names,
  advertising and anything suggesting an official link. None apply. Section 2.2.8 prohibits using
  the marks in one's own trademark or product name. For that reason the project is named Scarab, which
  contains none of the marks (roadmap M7).
- Section 2.2.2 asks for the attribution "'Cardano' is a trademark of the Cardano Foundation,
  CHE-184.477.354 …" wherever possible. It is in the README and `NOTICE`, with ™ (only the owner
  may use ®).
- Section 4 usage: "Cardano" is capitalised and used as an adjective ("a Cardano-compatible node",
  "the Cardano protocol"), never as a verb or a possessive.
- Not used: the Cardano logo or other brand assets. No domain, organisation name or package
  namespace refers to the marks (the sbt organisation is `xyz.kd5ujc`).
- The disclaimer names the Cardano Partners the policy lists (Cardano Foundation, IOHK/IOG, EMURGO)
  and also Intersect.

## Vendored specification files

`models/src/main/cddl/upstream/` holds verbatim copies of CDDL files from three Apache-2.0 repositories
(IntersectMBO/ouroboros-network, ouroboros-consensus, cardano-ledger) at the commits listed in its `SOURCES`
file. Their LICENSE and NOTICE texts are in [fixtures/licenses](../fixtures/licenses). The build-time tool that
reads them (`models-gen`) uses cats-parse 1.1.0 (MIT) and nothing else beyond what `codec` already uses.

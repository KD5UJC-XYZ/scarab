# Dependency licences

**Status: Historical dependency inventory.** The resolved versions and licence review below
describe the recorded build, not a continuously generated SBOM; regenerate them for a release
or external audit under [O2](roadmap.md#o-review-readiness-and-operations-v03).

Research software, not legal advice. This is a record of what the build resolves, so that the list in
[NOTICE](../NOTICE) can be checked against it.

## How this was produced

The Runtime classpath of the `node` project (its transitive runtime dependencies, excluding
test-only and generator modules)
was written with `sbt node/runtimeClasspathFile` in the JDK image, at v0.1.0 plus this review. For every
jar on it, the `<licenses>` element of its POM in the Coursier cache was read; where a POM declares
none, the parent POM chain was followed. 59 third-party jars resolve, 15 of them direct dependencies
(60 since the Scalus 1.4.0 update: scalus-secp256k1-jni left, scalus-crypto-jni and native-lib-loader came).
Jar contents were listed for licence files and native libraries. The licence names below are the POM
names, normalised to SPDX identifiers where they are unambiguous.

The rows for dnsjava, kittens, scodec-core, cats-parse, monocle, and slf4j-api were added when
`NOTICE` and this file were reconciled with `project/Dependencies.scala` (checked by `scripts/check_notice.py`).
Their licences were read from the POMs of the declared versions on Maven Central. The resolved classpath and
the jar count above were not regenerated for that change; the re-run below does that.

## Result

- No dependency is GPL-only, LGPL, AGPL, proprietary or without a declared licence. Every artifact is
  Apache-2.0, MIT, BSD-2/3-Clause, MPL-2.0, Bouncy Castle (MIT-style) or the RocksDB dual licence. All of
  these are compatible with distributing this project under Apache-2.0.
- RocksDB (`rocksdbjni`) is `Apache-2.0 OR GPL-2.0-only`. The project uses it under Apache-2.0. The GPL
  option is not elected and no GPL-2.0-only obligations arise.
- Borer (`borer-core`, `borer-derivation`, transitive through Scalus) is MPL-2.0, a file-level weak
  copyleft licence. Using the unmodified jars from Maven Central imposes no obligation on this project's
  code. If a borer source file were ever modified or vendored, that file would have to stay MPL-2.0 and
  be made available. Earlier versions of `NOTICE` did not mention it.
- Nothing is redistributed in this repository: dependencies are retrieved from Maven Central at build
  time. A binary distribution (a fat jar or a container image) would redistribute all of the below and
  would need to carry their licence texts. No such artefact is published; that review stays open.

## Native libraries bundled in jars

| Jar | Native content | Upstream licence | Note |
|---|---|---|---|
| `org.rocksdb:rocksdbjni:10.2.1` | `librocksdbjni` for Linux (x86, x86_64, aarch64, ppc64le, s390x, riscv64; glibc and musl), macOS (x86_64, arm64), Windows x64 | Apache-2.0 OR GPL-2.0-only (RocksDB) | The jar has no licence file. RocksDB statically links compression libraries (zlib, snappy, lz4, zstd, bzip2; BSD- or zlib-style licences) whose texts are not in the jar, so a binary distribution must add them (not verified here). Only the Linux x86_64 library is exercised by this project's Docker build. |
| `foundation.icon:blst-java:0.3.2` | `libblst` for Linux amd64/aarch64, macOS x86_64/arm64 | Apache-2.0 (supranational/blst) | No licence file in the jar. Used by the Leios BLS keys (`MinSig`) only; on Linux amd64 the library of `blst-native` (below) is loaded instead. |
| `blst-native` (this repository) | `libblst` for Linux amd64, built from supranational/blst v0.3.15 with `-D__BLST_PORTABLE__` | Apache-2.0 (supranational/blst) | Licence text in `blst-native/LICENSE-blst`; recipe and checksum in [ADR 0012](adr/0012-portable-blst.md). |
| `org.scalus:scalus-crypto-jni:0.1.0` | `libscalus_crypto` for Linux x86_64/arm64 (glibc 2.33 or newer), macOS x86_64/arm64, Windows x64; libsodium (IOG fork `dbb48cce`), libsecp256k1 v0.3.2 and blst v0.3.15 statically linked | Wrapper Apache-2.0 per its POM; libsodium ISC, libsecp256k1 MIT, blst Apache-2.0 | No licence file in the jar. The texts from the pinned upstream sources are in `plutus/licenses/` (`LICENSE-libsodium`, `LICENSE-libsecp256k1`) and `blst-native/LICENSE-blst`. Pins and provenance in [ADR 0020](adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md). |

Earlier `NOTICE` text said these natives are bundled "for linux x86_64"; the jars hold several platforms
and `NOTICE` now says so.

## Runtime classpath

Every row is on the Runtime classpath (Compile dependencies are a subset). Test-only libraries (munit,
munit-scalacheck, munit-cats-effect, discipline-munit, cats-laws, cats-mtl-laws, cats-kernel-laws,
cats-effect-testkit, monocle-law) are not shipped; their licences in `NOTICE` come from earlier POM reads and
were not regenerated here.

| Artifact | Version | Licence | Kind | Note |
|---|---|---|---|---|
| org.scala-lang:scala3-library_3 | 3.3.8 | Apache-2.0 | direct |  |
| org.typelevel:cats-core_3 | 2.13.0 | MIT | direct |  |
| org.typelevel:cats-effect_3 | 3.7.1 | Apache-2.0 | direct |  |
| org.typelevel:cats-mtl_3 | 1.7.0 | Apache-2.0 | direct |  |
| org.scalus:scalus_3 | 1.4.0 | Apache-2.0 | direct |  |
| dnsjava:dnsjava | 3.6.5 | BSD-3-Clause | direct | Relay-root DNS resolver. POM read from Maven Central. |
| org.typelevel:kittens_3 | 3.5.0 | Apache-2.0 | direct | POM read from Maven Central. |
| org.scodec:scodec-core_3 | 2.3.3 | BSD-3-Clause | direct | POM read from Maven Central. |
| org.typelevel:cats-parse_3 | 1.1.0 | MIT | direct | Also transitive via Scalus; the build tool `models-gen` declares it directly. |
| dev.optics:monocle-core_3 | 3.3.0 | MIT | direct | Also transitive via Scalus. |
| dev.optics:monocle-macro_3 | 3.3.0 | MIT | direct | Also transitive via Scalus. |
| org.slf4j:slf4j-api | 2.0.17 | MIT | direct | Declared at 2.0.17 in `Dependencies.scala`; POM parent states MIT. |
| org.rocksdb:rocksdbjni | 10.2.1 | Apache-2.0 OR GPL-2.0-only | direct | Dual licence; Apache-2.0 elected. The jar bundles native libraries (see below). |
| io.circe:circe-parser_3 | 0.14.16 | Apache-2.0 | direct |  |
| org.http4s:http4s-ember-server_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.http4s:http4s-dsl_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.http4s:http4s-circe_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.bouncycastle:bcprov-jdk18on | 1.86 | Bouncy Castle Licence (MIT-style) | direct | The jar carries META-INF/LICENSE.md. |
| com.weavechain:curve25519-elisabeth | 0.1.3 | MIT | direct | The jar carries no licence file; further MIT, CC0 and BSD-3-Clause notices of adapted components are recorded in the project README (not verified here). |
| org.typelevel:cats-kernel_3 | 2.13.0 | MIT | direct |  |
| co.fs2:fs2-core_3 | 3.14.0 | MIT | direct |  |
| co.fs2:fs2-io_3 | 3.14.0 | MIT | direct |  |
| org.scala-lang:scala-library | 2.13.18 | Apache-2.0 | transitive |  |
| org.typelevel:cats-effect-kernel_3 | 3.7.1 | Apache-2.0 | transitive |  |
| org.typelevel:cats-effect-std_3 | 3.7.1 | Apache-2.0 | transitive |  |
| org.typelevel:paiges-core_3 | 0.4.4 | Apache-2.0 | transitive |  |
| com.lihaoyi:upickle_3 | 4.4.3 | MIT | transitive |  |
| com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_3 | 2.41.2 | MIT | transitive |  |
| com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-macros_3 | 2.41.2 | MIT | transitive |  |
| io.bullet:borer-core_3 | 1.18.0 | MPL-2.0 | transitive | MPL-2.0 is file-level copyleft: compatible when used unmodified as a separate jar (via Scalus); duties arise only if its files are modified. |
| io.bullet:borer-derivation_3 | 1.18.0 | MPL-2.0 | transitive | As borer-core_3. |
| foundation.icon:blst-java | 0.3.2 | Apache-2.0 | direct (crypto, Leios BLS) | Bundles native libblst (supranational/blst, Apache-2.0) for Linux amd64/aarch64 and macOS. |
| org.scalus:scalus-crypto-jni | 0.1.0 | Apache-2.0 | transitive (Scalus) | Bundles `libscalus_crypto` (libsodium ISC, libsecp256k1 MIT, blst Apache-2.0) for Linux, macOS and Windows; the POM states Apache-2.0 for the wrapper. |
| org.scijava:native-lib-loader | 2.5.0 | BSD-2-Clause | transitive (scalus-crypto-jni) | Extracts and loads `libscalus_crypto`. No licence file in the jar; the text from its tag `native-lib-loader-2.5.0` is `plutus/licenses/LICENSE-native-lib-loader`. |
| io.circe:circe-jawn_3 | 0.14.16 | Apache-2.0 | transitive |  |
| io.circe:circe-core_3 | 0.14.16 | Apache-2.0 | transitive |  |
| org.http4s:http4s-ember-core_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.http4s:http4s-server_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.typelevel:log4cats-slf4j_3 | 2.8.0 | Apache-2.0 | transitive |  |
| org.http4s:http4s-core_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.http4s:http4s-jawn_3 | 0.23.37 | Apache-2.0 | transitive |  |
| org.scodec:scodec-bits_3 | 1.2.5 | BSD-3-Clause | transitive |  |
| com.comcast:ip4s-core_3 | 3.8.0 | Apache-2.0 | transitive |  |
| com.lihaoyi:ujson_3 | 4.4.3 | MIT | transitive |  |
| com.lihaoyi:upack_3 | 4.4.3 | MIT | transitive |  |
| com.lihaoyi:upickle-implicits_3 | 4.4.3 | MIT | transitive |  |
| org.typelevel:cats-free_3 | 2.12.0 | MIT | transitive |  |
| org.typelevel:jawn-parser_3 | 1.7.0 | MIT | transitive |  |
| io.circe:circe-numbers_3 | 0.14.16 | Apache-2.0 | transitive |  |
| org.typelevel:log4cats-core_3 | 2.8.0 | Apache-2.0 | transitive |  |
| com.twitter:hpack | 1.0.2 | Apache-2.0 | transitive |  |
| org.typelevel:case-insensitive_3 | 1.5.0 | Apache-2.0 | transitive |  |
| org.http4s:http4s-crypto_3 | 0.2.5 | Apache-2.0 | transitive |  |
| org.typelevel:literally_3 | 1.2.0 | Apache-2.0 | transitive |  |
| org.typelevel:idna4s-core_3 | 0.1.0 | MIT | transitive |  |
| org.typelevel:cats-collections-core_3 | 0.9.10 | MIT and BSD-3-Clause | transitive | The POM lists both. |
| org.typelevel:algebra_3 | 2.13.0 | MIT | transitive |  |
| org.typelevel:vault_3 | 3.7.0 | MIT | transitive |  |
| org.log4s:log4s_3 | 1.10.0 | Apache-2.0 | transitive |  |
| org.typelevel:jawn-fs2_3 | 2.6.0 | Apache-2.0 | transitive |  |
| com.lihaoyi:upickle-core_3 | 4.4.3 | MIT | transitive |  |
| com.lihaoyi:geny_3 | 1.1.1 | MIT | transitive |  |

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

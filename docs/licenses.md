# Dependency licences

Research software, not legal advice. This is a record of what the build resolves, so that the list in
[NOTICE](../NOTICE) can be checked against it.

## How this was produced

The Runtime classpath of the `node` project (which includes `core`, `network` and `network-runtime`)
was written with `sbt node/runtimeClasspathFile` in the JDK image, at v0.1.0 plus this review. For every
jar on it, the `<licenses>` element of its POM in the Coursier cache was read; where a POM declares
none, the parent POM chain was followed. 59 third-party jars resolve, 15 of them direct dependencies.
Jar contents were listed for licence files and native libraries. The licence names below are the POM
names, normalised to SPDX identifiers where they are unambiguous.

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
| `foundation.icon:blst-java:0.3.2` | `libblst` for Linux amd64/aarch64, macOS x86_64/arm64 | Apache-2.0 (supranational/blst) | No licence file in the jar. |
| `org.scalus:scalus-secp256k1-jni:0.6.0` | `libscalus_secp256k1` for Linux x86_64/arm64, macOS x86_64/arm64 | Wrapper Apache-2.0 per its POM; wraps bitcoin-core/secp256k1 (MIT) | No licence file in the jar; the MIT status of the wrapped library is upstream's, not read from the jar. |

Earlier `NOTICE` text said these natives are bundled "for linux x86_64"; the jars hold several platforms
and `NOTICE` now says so.

## Runtime classpath

Every row is on the Runtime classpath (Compile dependencies are a subset). Test-only libraries (munit,
munit-scalacheck, munit-cats-effect, discipline-munit, cats-laws, cats-mtl-laws, cats-kernel-laws,
cats-effect-testkit) are not shipped; their licences in `NOTICE` come from earlier POM reads and were not
regenerated here.

| Artifact | Version | Licence | Kind | Note |
|---|---|---|---|---|
| org.scala-lang:scala3-library_3 | 3.3.8 | Apache-2.0 | direct |  |
| org.typelevel:cats-core_3 | 2.13.0 | MIT | direct |  |
| org.typelevel:cats-effect_3 | 3.7.1 | Apache-2.0 | direct |  |
| org.typelevel:cats-mtl_3 | 1.7.0 | Apache-2.0 | direct |  |
| org.scalus:scalus_3 | 1.2.0 | Apache-2.0 | direct |  |
| org.rocksdb:rocksdbjni | 10.2.1 | Apache-2.0 OR GPL-2.0-only | direct | Dual licence; Apache-2.0 elected. The jar bundles native libraries (see below). |
| io.circe:circe-parser_3 | 0.14.16 | Apache-2.0 | direct |  |
| org.http4s:http4s-ember-server_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.http4s:http4s-dsl_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.http4s:http4s-circe_3 | 0.23.37 | Apache-2.0 | direct |  |
| org.bouncycastle:bcprov-jdk18on | 1.85.2 | Bouncy Castle Licence (MIT-style) | direct | The jar carries META-INF/LICENSE.md. |
| com.weavechain:curve25519-elisabeth | 0.1.3 | MIT | direct | The jar carries no licence file; further MIT, CC0 and BSD-3-Clause notices of adapted components are recorded in the project README (not verified here). |
| org.typelevel:cats-kernel_3 | 2.13.0 | MIT | direct |  |
| co.fs2:fs2-core_3 | 3.14.0 | MIT | direct |  |
| co.fs2:fs2-io_3 | 3.14.0 | MIT | direct |  |
| org.scala-lang:scala-library | 2.13.18 | Apache-2.0 | transitive |  |
| org.typelevel:cats-effect-kernel_3 | 3.7.1 | Apache-2.0 | transitive |  |
| org.typelevel:cats-effect-std_3 | 3.7.1 | Apache-2.0 | transitive |  |
| org.typelevel:cats-parse_3 | 1.1.0 | MIT | transitive |  |
| org.typelevel:paiges-core_3 | 0.4.4 | Apache-2.0 | transitive |  |
| com.lihaoyi:upickle_3 | 4.4.3 | MIT | transitive |  |
| com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-core_3 | 2.40.1 | MIT | transitive |  |
| com.github.plokhotnyuk.jsoniter-scala:jsoniter-scala-macros_3 | 2.40.1 | MIT | transitive |  |
| io.bullet:borer-core_3 | 1.18.0 | MPL-2.0 | transitive | MPL-2.0 is file-level copyleft: compatible when used unmodified as a separate jar (via Scalus); duties arise only if its files are modified. |
| io.bullet:borer-derivation_3 | 1.18.0 | MPL-2.0 | transitive | As borer-core_3. |
| dev.optics:monocle-core_3 | 3.3.0 | MIT | transitive |  |
| dev.optics:monocle-macro_3 | 3.3.0 | MIT | transitive |  |
| foundation.icon:blst-java | 0.3.2 | Apache-2.0 | transitive | Bundles native libblst (supranational/blst, Apache-2.0) for Linux amd64/aarch64 and macOS. |
| org.scalus:scalus-secp256k1-jni | 0.6.0 | Apache-2.0 | transitive | Bundles native secp256k1 (bitcoin-core, MIT) for Linux and macOS; the POM states Apache-2.0 for the wrapper. |
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
| org.scijava:native-lib-loader | 2.5.0 | BSD-2-Clause | transitive | Extracts bundled native libraries at run time. |
| org.typelevel:jawn-parser_3 | 1.7.0 | MIT | transitive |  |
| io.circe:circe-numbers_3 | 0.14.16 | Apache-2.0 | transitive |  |
| org.typelevel:log4cats-core_3 | 2.8.0 | Apache-2.0 | transitive |  |
| com.twitter:hpack | 1.0.2 | Apache-2.0 | transitive |  |
| org.slf4j:slf4j-api | 1.7.36 | MIT | transitive |  |
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
docker run --rm --user 1000:1000 -e HOME=/work/.cache/home -v "$PWD":/work -w /work cardano-scala-jdk:local \
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
  name `cardano-scala-lab` falls under it. The policy's own permitted example is a free theme called
  "Gold Plated Cardano".
- Section 3.3 requires written permission for domain names, company or organisation names,
  advertising and anything suggesting an official link. None apply. Section 2.2.8 prohibits using
  the marks in one's own trademark or product name. For that reason the owner will choose a distinct
  project name before this becomes a named product or a published artefact (roadmap M7).
- Section 2.2.2 asks for the attribution "'Cardano' is a trademark of the Cardano Foundation,
  CHE-184.477.354 …" wherever possible. It is in the README and `NOTICE`, with ™ (only the owner
  may use ®).
- Section 4 usage: "Cardano" is capitalised and used as an adjective ("a Cardano-compatible node",
  "the Cardano protocol"), never as a verb or a possessive.
- Not used: the Cardano logo or other brand assets. No domain, organisation name or package
  namespace refers to the marks (the sbt organisation is `io.github.ottobot-ai`).
- The disclaimer names the Cardano Partners the policy lists (Cardano Foundation, IOHK/IOG, EMURGO)
  and also Intersect.

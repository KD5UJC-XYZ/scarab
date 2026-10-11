# Dependency licences

Research software, not legal advice. This is a record of what the build resolves, so that the list in
[NOTICE](../NOTICE) can be checked against it.

## How this was produced

The Runtime classpath of the `node` project (which includes `core`, `network` and `network-runtime`)
was written with `sbt node/runtimeClasspathFile` in the JDK image, at v0.1.0 plus this review. For every
jar on it, the `<licenses>` element of its POM in the Coursier cache was read; where a POM declares
none, the parent POM chain was followed. 56 third-party jars resolve, 15 of them direct dependencies.
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
| org.typelevel:cats-effect_3 | 3.6.3 | Apache-2.0 | direct |  |
| org.typelevel:cats-mtl_3 | 1.6.0 | Apache-2.0 | direct |  |
| org.scalus:scalus_3 | 1.2.0 | Apache-2.0 | direct |  |
| org.rocksdb:rocksdbjni | 10.2.1 | Apache-2.0 OR GPL-2.0-only | direct | Dual licence; Apache-2.0 elected. The jar bundles native libraries (see below). |
| io.circe:circe-parser_3 | 0.14.10 | Apache-2.0 | direct |  |
| org.http4s:http4s-ember-server_3 | 0.23.30 | Apache-2.0 | direct |  |
| org.http4s:http4s-dsl_3 | 0.23.30 | Apache-2.0 | direct |  |
| org.http4s:http4s-circe_3 | 0.23.30 | Apache-2.0 | direct |  |
| org.bouncycastle:bcprov-jdk18on | 1.85.2 | Bouncy Castle Licence (MIT-style) | direct | The jar carries META-INF/LICENSE.md. |
| com.weavechain:curve25519-elisabeth | 0.1.3 | MIT | direct | The jar carries no licence file; further MIT, CC0 and BSD-3-Clause notices of adapted components are recorded in the project README (not verified here). |
| org.typelevel:cats-kernel_3 | 2.13.0 | MIT | direct |  |
| co.fs2:fs2-core_3 | 3.11.0 | MIT | direct |  |
| co.fs2:fs2-io_3 | 3.11.0 | MIT | direct |  |
| org.scala-lang:scala-library | 2.13.18 | Apache-2.0 | transitive |  |
| org.typelevel:cats-effect-kernel_3 | 3.6.3 | Apache-2.0 | transitive |  |
| org.typelevel:cats-effect-std_3 | 3.6.3 | Apache-2.0 | transitive |  |
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
| io.circe:circe-jawn_3 | 0.14.10 | Apache-2.0 | transitive |  |
| io.circe:circe-core_3 | 0.14.10 | Apache-2.0 | transitive |  |
| org.http4s:http4s-ember-core_3 | 0.23.30 | Apache-2.0 | transitive |  |
| org.http4s:http4s-server_3 | 0.23.30 | Apache-2.0 | transitive |  |
| org.typelevel:log4cats-slf4j_3 | 2.7.0 | Apache-2.0 | transitive |  |
| org.http4s:http4s-core_3 | 0.23.30 | Apache-2.0 | transitive |  |
| org.http4s:http4s-jawn_3 | 0.23.30 | Apache-2.0 | transitive |  |
| org.scodec:scodec-bits_3 | 1.1.38 | BSD-3-Clause | transitive |  |
| com.comcast:ip4s-core_3 | 3.6.0 | Apache-2.0 | transitive |  |
| com.lihaoyi:ujson_3 | 4.4.3 | MIT | transitive |  |
| com.lihaoyi:upack_3 | 4.4.3 | MIT | transitive |  |
| com.lihaoyi:upickle-implicits_3 | 4.4.3 | MIT | transitive |  |
| org.typelevel:cats-free_3 | 2.12.0 | MIT | transitive |  |
| org.scijava:native-lib-loader | 2.5.0 | BSD-2-Clause | transitive | Extracts bundled native libraries at run time. |
| org.typelevel:jawn-parser_3 | 1.6.0 | MIT | transitive |  |
| io.circe:circe-numbers_3 | 0.14.10 | Apache-2.0 | transitive |  |
| org.typelevel:log4cats-core_3 | 2.7.0 | Apache-2.0 | transitive |  |
| com.twitter:hpack | 1.0.2 | Apache-2.0 | transitive |  |
| org.slf4j:slf4j-api | 1.7.36 | MIT | transitive |  |
| org.typelevel:case-insensitive_3 | 1.4.2 | Apache-2.0 | transitive |  |
| org.http4s:http4s-crypto_3 | 0.2.4 | Apache-2.0 | transitive |  |
| org.typelevel:literally_3 | 1.1.0 | Apache-2.0 | transitive |  |
| org.typelevel:vault_3 | 3.6.0 | MIT | transitive |  |
| org.log4s:log4s_3 | 1.10.0 | Apache-2.0 | transitive |  |
| org.typelevel:jawn-fs2_3 | 2.4.0 | Apache-2.0 | transitive |  |
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

The Cardano Foundation's trademark and brand guidance (cardanofoundation.org) could not be fetched
during this review: the site answered automated requests with a bot challenge (HTTP 429), so its
current wording was not read and is not quoted here. The recommendation below is therefore a
judgement, not a confirmed reading of that policy.

- "cardano-scala-lab" uses the word "Cardano" descriptively, to say which protocol the software
  targets, as many community projects do, and the README, `NOTICE` and `SECURITY.md` state that it is
  unofficial and not affiliated with or endorsed by Input Output Global, Intersect, the Cardano
  Foundation or Emurgo. The name is acceptable on that basis.
- Not used: the Cardano logo or brand assets, and no domain, organisation name or package namespace
  claims to be official (the sbt organisation is `io.github.ottobot-ai`).
- If the Foundation or another owner objects, rename the repository; the Scala package root `lab` and
  sbt organisation are already independent of the name (roadmap M7).

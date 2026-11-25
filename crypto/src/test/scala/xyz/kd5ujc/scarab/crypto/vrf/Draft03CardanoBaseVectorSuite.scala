// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes

/** The reference's seven ECVRF draft-03 test vectors (`fixtures/vrf-cardano-base`, cardano-base
  * `060819b5` `cardano-crypto-praos/test_vectors/vrf_ver03_*`), each read whole and checked as the
  * reference's `checkVer03TestVector` checks it (`Test/Crypto/VRF.hs:112-136`): proving alpha with
  * `sk || pk` gives pi, the key of sk is pk, and verifying pi with pk gives beta. The output alone
  * ([[Draft03Prover.SigningKey.output]]) is checked to be beta too. Proving runs on the vendored
  * curve25519 arithmetic; verifying on the separate arithmetic of `StrictDraft03`.
  */
class Draft03CardanoBaseVectorSuite extends munit.FunSuite:
  private val root = Path.of("fixtures/vrf-cardano-base")

  private val files = Vector(
    "vrf_ver03_generated_1" -> "6c4f8a82c453acda114a3a3887c61fee7c2da223c6518ffb1618a34b614089c1",
    "vrf_ver03_generated_2" -> "d15875d0598b4ecc962c966e272985a1e7113add367aa87c1ffbad8ccd2bef49",
    "vrf_ver03_generated_3" -> "10e371f743bc78fdc344cc7b631335c9fa4bccc290143ccf720155b9f6ee1a5a",
    "vrf_ver03_generated_4" -> "c8ea137dfe7a730a2fa83458d3efaf2faf6a394e888d38d5138c2c36a4af6252",
    "vrf_ver03_standard_10" -> "f21491dab4462e9146da3995991c13c04ac26ce0a5f6fbff1597cba97d41f814",
    "vrf_ver03_standard_11" -> "2723c93fef149961f05e02516c3032453bb2852a9a2f2284e93eda6e112f2c9b",
    "vrf_ver03_standard_12" -> "b3875a5c5ea570b87beb4e84690455845b4c00d29e074bb004d93811f5719ae7"
  )

  private val fields = Vector("vrf", "ver", "ciphersuite", "sk", "pk", "alpha", "pi", "beta")

  private final case class Vector03(sk: Bytes, pk: Bytes, alpha: Bytes, pi: Bytes, beta: Bytes)

  private def hex(s: String): Bytes =
    if s == "empty" then Bytes.empty else Bytes.fromHex(s).fold(e => fail(e.message), identity)

  /** A vector's `field: value` lines, in the order and with the lengths of the reference's parser.
    */
  private def read(name: String, sha256: String): Vector03 =
    val bytes = Files.readAllBytes(root.resolve(name))
    assertEquals(Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).hex, sha256, name)
    val lines = Files.readString(root.resolve(name), UTF_8).linesIterator.toVector
    val parsed = lines.map(_.split(":", 2).map(_.trim).toVector)
    assertEquals(parsed.map(_.head), fields, s"$name: its fields")
    val value = parsed.map(f => f(0) -> f(1)).toMap
    assertEquals((value("vrf"), value("ver")), ("PraosVRF", "ietfdraft03"), name)
    assertEquals(value("ciphersuite"), "ECVRF-ED25519-SHA512-Elligator2", name)
    val v = Vector03(
      hex(value("sk")),
      hex(value("pk")),
      hex(value("alpha")),
      hex(value("pi")),
      hex(value("beta"))
    )
    assertEquals(
      (v.sk.size, v.pk.size, v.pi.size, v.beta.size),
      (32, 32, 80, 64),
      s"$name: field lengths"
    )
    v

  files.foreach { (name, sha256) =>
    test(s"$name: the key, the proof and the output are the reference's") {
      val v = read(name, sha256)
      val key =
        Draft03Prover.SigningKey.fromBytes(v.sk ++ v.pk).fold(e => fail(e.toString), identity)
      assertEquals(key.publicKey.hex, v.pk.hex, "the key of sk")
      assertEquals(
        Draft03Prover.SigningKey.fromSeed(v.sk).map(_.publicKey.hex),
        Right(v.pk.hex),
        "the key of the seed alone"
      )
      val proved = key.prove(v.alpha).fold(e => fail(e.toString), identity)
      assertEquals(proved.proof.hex, v.pi.hex, "pi")
      assertEquals(proved.output.hex, v.beta.hex, "beta from proving")
      assertEquals(key.output(v.alpha).map(_.hex), Right(v.beta.hex), "beta alone")
      assertEquals(
        StrictDraft03.verify(v.pk, v.pi, v.alpha),
        StrictDraft03.Result.Verified(v.beta),
        "verify"
      )
    }
  }

  test("all seven vectors of the reference are read") {
    assertEquals(files.size, 7)
    assertEquals(files.map(_._1).toSet.size, 7)
    val present =
      Using.resource(Files.list(root))(_.iterator.asScala.map(_.getFileName.toString).toSet)
    assertEquals(
      present.filter(_.startsWith("vrf_")),
      files.map(_._1).toSet,
      "the vector files of the fixture"
    )
  }

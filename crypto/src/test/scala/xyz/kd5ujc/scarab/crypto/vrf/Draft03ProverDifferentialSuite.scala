// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import xyz.kd5ujc.scarab.codec.Bytes

/** The prover against the prover it replaced ([[LegacyDraft03Prover]], kept unchanged): proofs and
  * outputs are deterministic functions of the key and alpha, so the two must agree byte for byte on
  * every generated key and input, and so must the public key, the `vrf.skey` layout and the
  * refusals of a malformed key.
  *
  * What it can catch: a difference in the steps that changed, which the two provers take
  * differently over the same arithmetic. The new one multiplies by the clamped scalar unreduced,
  * the old one by the scalar reduced modulo L; the new one takes the output from the constant-time
  * point, the old one from Gamma decoded by `Ed25519Point`; the new one hashes the challenge over
  * the encodings as computed, the old one over U and V decoded and encoded again; the new one keeps
  * the seed, the nonce and the digits in a scratch, the old one in library scalars. A slip in any
  * of these, on a key or input no vector covers, shows here.
  *
  * What it cannot catch: a fault in the vendored arithmetic itself. The legacy prover is the old
  * Scala, but the vendored `Scalar` and point code it calls is today's: its
  * `fromBytesModOrderWide`, `multiplyAndAdd(Scalar, Scalar)`, `toRadix16()` and `multiply(Scalar)`
  * now compute through the array forms the new prover calls (`reduceWide`,
  * `multiplyAndAdd(byte[]…)`, `toRadix16(byte[], byte[])`, `multiplyRadix16`), so a fault there is
  * in both and they agree on it. That arithmetic is checked by suites that do not share it:
  * `ScalarSuite` and `EdwardsPointSuite` against exact integers and an independent model of the
  * curve (both forms of each operation), and `Draft03ProverSuite` and
  * `Draft03CardanoBaseVectorSuite` against the outputs of the reference's libsodium.
  */
class Draft03ProverDifferentialSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  private def bytesOf(n: Int): Gen[Array[Byte]] =
    Gen.listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray)

  /** Seeds: uniform, and the constant fills a hand-made key file would hold. */
  private val seed: Gen[Bytes] = Gen
    .frequency(
      9 -> bytesOf(32),
      1 -> Gen.oneOf(0x00, 0x01, 0x7f, 0x80, 0xff).map(b => Array.fill(32)(b.toByte))
    )
    .map(Bytes.fromArray)

  /** Alphas: empty, the 40 bytes of a Praos input (slot and nonce), and lengths across the SHA-512
    * block of 128 bytes.
    */
  private val alpha: Gen[Bytes] = Gen
    .frequency(
      1 -> Gen.const(0),
      3 -> Gen.const(32),
      2 -> Gen.choose(1, 300)
    )
    .flatMap(bytesOf)
    .map(Bytes.fromArray)

  property("proof, output and public key are the replaced prover's, byte for byte") {
    forAllNoShrink(seed, alpha) { (s, a) =>
      val key = Draft03Prover.SigningKey.fromSeed(s).toOption.get
      val legacy = LegacyDraft03Prover.SigningKey.fromSeed(s).toOption.get
      assertEquals(key.publicKey, legacy.publicKey, s"public key of seed ${s.hex}")
      assertEquals(key.toBytes, legacy.toBytes, "the vrf.skey layout")
      val proved = key.prove(a).toOption.get
      val expected = legacy.prove(a).toOption.get
      assertEquals(proved.proof.hex, expected.proof.hex, s"proof of seed ${s.hex}, alpha ${a.hex}")
      assertEquals(proved.output.hex, expected.output.hex, "output certified")
      assertEquals(
        key.output(a).toOption.map(_.hex),
        legacy.output(a).toOption.map(_.hex),
        "output alone"
      )
    }
  }

  property("a key read from its vrf.skey layout proves as the key it was written from") {
    forAllNoShrink(seed, alpha) { (s, a) =>
      val written = LegacyDraft03Prover.SigningKey.fromSeed(s).toOption.get
      val read = Draft03Prover.SigningKey.fromBytes(written.toBytes).toOption.get
      assertEquals(
        read.prove(a).toOption.map(_.proof.hex),
        written.prove(a).toOption.map(_.proof.hex)
      )
    }
  }

  test("a stored public key that is not the seed's is refused, as before") {
    val good = Draft03Prover.SigningKey.fromSeed(Bytes.fromArray(Array.fill(32)(7.toByte)))
    val payload = good.toOption.get.toBytes.toArray
    payload(40) = (payload(40) ^ 1).toByte
    val wrong = Bytes.fromArray(payload)
    assert(
      Draft03Prover.SigningKey.fromBytes(wrong) match
        case Left(Draft03Prover.Failure.PublicKeyMismatch) => true
        case _                                             => false
    )
    assert(
      LegacyDraft03Prover.SigningKey.fromBytes(wrong) match
        case Left(LegacyDraft03Prover.Failure.PublicKeyMismatch) => true
        case _                                                   => false
    )
  }

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes

import cats.syntax.either.*
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.secret.{Blake2b256, Leaf}
import xyz.kd5ujc.scarab.crypto.secret.{Scratch, Sha512}

/** The KES signing key against the key it replaced ([[LegacySum6KesSigning]], kept unchanged): key
  * generation, evolution, signing and the `kes.skey` layout are deterministic functions of the
  * seed, so the two must agree byte for byte at every period of a generated key's life, on every
  * message, and must load and refuse the same payloads with the same failures. The parts that
  * changed are pinned on their own too: the Blake2b-256 of the secret path against Bouncy Castle's
  * digest, and the leaves' Ed25519 against Bouncy Castle's. The vectors of `Sum6KesSigningSuite`
  * and `Sum6KesSuite` pin both keys to cardano-cli and cardano-base.
  */
class Sum6KesSigningDifferentialSuite extends munit.ScalaCheckSuite:
  import Sum6KesSigning.*

  // the JDK's SHA-512, which the leaves hash with
  private given Sha512.Provider = Sha512.Provider.found.toOption.get

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private def bytesOf(n: Int): Gen[Array[Byte]] =
    Gen.listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray)

  /** Seeds: uniform, and the constant fills a hand-made key would hold. */
  private val seedArray: Gen[Array[Byte]] = Gen.frequency(
    9 -> bytesOf(32),
    1 -> Gen.oneOf(0x00, 0x01, 0x7f, 0x80, 0xff).map(b => Array.fill(32)(b.toByte))
  )
  private val seed: Gen[Bytes] = seedArray.map(Bytes.fromArray)

  /** Messages: empty, the size of a header body, and lengths across SHA-512's 128-byte block. */
  private val message: Gen[Bytes] = Gen
    .frequency(1 -> Gen.const(0), 2 -> Gen.choose(600, 1100), 3 -> Gen.choose(1, 300))
    .flatMap(bytesOf)
    .map(Bytes.fromArray)

  private def legacyFailure(f: LegacySum6KesSigning.Failure): Failure = f match
    case LegacySum6KesSigning.Failure.MissingSeed          => Failure.MissingSeed
    case LegacySum6KesSigning.Failure.InvalidSeedLength(n) => Failure.InvalidSeedLength(n)
    case LegacySum6KesSigning.Failure.MissingKey           => Failure.MissingKey
    case LegacySum6KesSigning.Failure.InvalidKeyLength(n)  => Failure.InvalidKeyLength(n)
    case LegacySum6KesSigning.Failure.PeriodOutOfRange(p)  => Failure.PeriodOutOfRange(p)
    case LegacySum6KesSigning.Failure.SeedStateMismatch(d, p, s) =>
      Failure.SeedStateMismatch(
        d,
        p,
        s match
          case LegacySum6KesSigning.SeedState.Erased  => SeedState.Erased
          case LegacySum6KesSigning.SeedState.Present => SeedState.Present
      )
    case LegacySum6KesSigning.Failure.VerificationKeyMismatch(d) =>
      Failure.VerificationKeyMismatch(d)
    case LegacySum6KesSigning.Failure.RightSeedMismatch(d) => Failure.RightSeedMismatch(d)

  /** A loaded key as what can be seen of it: its period, its verification key and its payload. */
  private def seen(k: SigningKey): (Int, Bytes, Bytes) = (k.period, k.verificationKey, k.toBytes)
  private def seenLegacy(k: LegacySum6KesSigning.SigningKey): (Int, Bytes, Bytes) =
    (k.period, k.verificationKey, k.toBytes)

  property("Blake2b-256 is Bouncy Castle's, for lengths across and between blocks, at any offset") {
    forAllNoShrink(Gen.choose(0, 400).flatMap(bytesOf), Gen.choose(0, 7)) { (input, offset) =>
      val padded = Array.fill[Byte](offset)(0x5a) ++ input
      val out = new Array[Byte](32 + offset)
      Scratch.within(s => Blake2b256.hash(s)(padded, offset, input.length, out, offset))
      val digest = new Blake2bDigest(256)
      digest.update(input, 0, input.length)
      val expected = new Array[Byte](32)
      digest.doFinal(expected, 0)
      assertEquals(out.drop(offset).toVector, expected.toVector, s"length ${input.length}")
      assert(out.take(offset).forall(_ == 0), "the digest went before its offset")
    }
  }

  property("a leaf's public key and signature are Bouncy Castle's Ed25519") {
    forAllNoShrink(seedArray, message) { (leaf, m) =>
      val publicKey = new Array[Byte](32)
      val signature = new Array[Byte](64)
      Scratch.within { s =>
        Leaf.publicKey(s)(leaf, 0, publicKey, 0)
        Leaf.sign(s)(leaf, 0, m.toArray, signature, 0)
      }
      val expectedKey = new Array[Byte](Ed25519.PUBLIC_KEY_SIZE)
      Ed25519.generatePublicKey(leaf, 0, expectedKey, 0)
      val expectedSignature = new Array[Byte](Ed25519.SIGNATURE_SIZE)
      Ed25519.sign(leaf, 0, m.toArray, 0, m.size, expectedSignature, 0)
      assertEquals(Bytes.fromArray(publicKey).hex, Bytes.fromArray(expectedKey).hex, "public key")
      assertEquals(
        Bytes.fromArray(signature).hex,
        Bytes.fromArray(expectedSignature).hex,
        s"signature of ${m.size} bytes"
      )
    }
  }

  test("at every period of a key's life its payload, key and signature are the replaced key's") {
    // 30 seeds and messages from fixed generator seeds: a key's whole life is 64 periods of each
    (1 to 30).foreach { i =>
      val s = seed.pureApply(Gen.Parameters.default, Seed(i.toLong))
      val m = message.pureApply(Gen.Parameters.default, Seed(1000L + i))
      val key = generate(s).toOption.get
      val legacy = LegacySum6KesSigning.generate(s).toOption.get
      (0 until Periods).foreach { p =>
        assertEquals(seen(key), seenLegacy(legacy), s"period $p of seed ${s.hex}")
        assertEquals(key.sign(m).map(_.hex), Right(legacy.sign(m).hex), s"signature at period $p")
        assertEquals(key.evolve(), legacy.evolve().leftMap(legacyFailure), s"evolution from $p")
      }
      assertEquals(seen(key), seenLegacy(legacy), "after the last period")
    }
  }

  property("a jump to any period gives the replaced key's payload and signature there") {
    forAllNoShrink(seed, Gen.choose(0, Periods - 1), message) { (s, p, m) =>
      val key = generate(s).toOption.get
      val legacy = LegacySum6KesSigning.generate(s).toOption.get
      assertEquals(key.evolveTo(p), legacy.evolveTo(p).leftMap(legacyFailure))
      assertEquals(seen(key), seenLegacy(legacy), s"period $p")
      assertEquals(key.sign(m).map(_.hex), Right(legacy.sign(m).hex), s"signature at period $p")
    }
  }

  /** A payload of the replaced key at a period, with no byte, one byte or the whole of a seed slot
    * damaged.
    */
  private val payload: Gen[(Bytes, Int)] =
    for
      s <- seed
      p <- Gen.choose(0, Periods - 1)
      damage <- Gen.frequency(
        3 -> Gen.const(Option.empty[(Int, Int, Byte)]),
        3 -> Gen
          .zip(Gen.choose(0, KeyBytes - 1), Gen.choose(1, 255))
          .map((i, x) => Some((i, 1, x.toByte))),
        1 -> Gen.oneOf(0, 32, 128, 224, 320, 416, 512).map(at => Some((at, 32, 0.toByte)))
      )
    yield
      val legacy = LegacySum6KesSigning.generate(s).toOption.get
      legacy.evolveTo(p)
      val raw = legacy.toBytes.toArray
      damage.foreach { (at, length, x) =>
        (at until at + length).foreach(i => raw(i) = if length == 1 then (raw(i) ^ x).toByte else x)
      }
      (Bytes.fromArray(raw), p)

  property("a payload loads, or is refused, as the replaced key loads or refuses it") {
    forAllNoShrink(payload, Gen.choose(0, Periods - 1)) { case ((raw, p), other) =>
      assertEquals(
        fromEvolved(raw).map(seen),
        LegacySum6KesSigning.fromEvolved(raw).map(seenLegacy).leftMap(legacyFailure),
        s"the payload of period $p, read off its seeds"
      )
      List(p, other).foreach { at =>
        assertEquals(
          fromBytes(raw, at).map(seen),
          LegacySum6KesSigning.fromBytes(raw, at).map(seenLegacy).leftMap(legacyFailure),
          s"the payload of period $p, loaded at $at"
        )
      }
    }
  }

  property("evolution targets, and their refusals, are the replaced key's") {
    val target = Gen.oneOf(
      Gen.choose(-3, Periods + 3).map(BigInt(_)),
      Gen.oneOf(
        BigInt(Int.MaxValue),
        BigInt(Int.MaxValue) + 1,
        BigInt(Int.MinValue) - 1,
        BigInt(1) << 64
      )
    )
    // the two keys of one seed, generated once: each case evolves copies of its own
    val first = generate(Bytes.fromArray(Array.fill(32)(7.toByte))).toOption.get
    val firstLegacy =
      LegacySum6KesSigning.generate(Bytes.fromArray(Array.fill(32)(7.toByte))).toOption.get
    forAllNoShrink(Gen.choose(0, Periods - 1), target) { (start, t) =>
      val key = first.copy
      val legacy = firstLegacy.copy
      key.evolveTo(start)
      legacy.evolveTo(start)
      assertEquals(key.evolveTo(t), legacy.evolveTo(t).leftMap(legacyFailure), s"$start to $t")
      assertEquals(key.period, legacy.period)
      assertEquals(key.toBytes, legacy.toBytes)
    }
  }

  test("malformed seeds and payloads are refused with the replaced key's failures") {
    Vector(0, 31, 33).foreach { n =>
      val bytes = Bytes.fromArray(new Array[Byte](n))
      assertEquals(
        generate(bytes).map(seen),
        LegacySum6KesSigning.generate(bytes).map(seenLegacy).leftMap(legacyFailure)
      )
    }
    Vector(0, KeyBytes - 1, KeyBytes + 1).foreach { n =>
      val bytes = Bytes.fromArray(new Array[Byte](n))
      assertEquals(
        fromEvolved(bytes).map(seen),
        LegacySum6KesSigning.fromEvolved(bytes).map(seenLegacy).leftMap(legacyFailure)
      )
      assertEquals(
        fromBytes(bytes).map(seen),
        LegacySum6KesSigning.fromBytes(bytes).map(seenLegacy).leftMap(legacyFailure)
      )
    }
    val zero = Bytes.fromArray(new Array[Byte](KeyBytes))
    assertEquals(
      fromEvolved(zero).map(seen),
      LegacySum6KesSigning.fromEvolved(zero).map(seenLegacy).leftMap(legacyFailure)
    )
    Vector(-1, Periods).foreach { p =>
      assertEquals(
        fromBytes(zero, p).map(seen),
        LegacySum6KesSigning.fromBytes(zero, p).map(seenLegacy).leftMap(legacyFailure)
      )
    }
    assertEquals(generate(null).map(seen), Left(Failure.MissingSeed))
    assertEquals(fromEvolved(null).map(seen), Left(Failure.MissingKey))
  }

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

class Sum6KesSigningSuite extends munit.FunSuite:
  import Sum6KesSigning.*

  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get
  private val seed = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
  private def fresh = generate(seed).toOption.get

  private def verified(vk: Bytes, period: Int, msg: Bytes, sig: Bytes): Boolean =
    Sum6Kes.verify(vk, period, msg, sig) == Right(Sum6Kes.Result.SuppliedMessageSignatureVerified)

  test("cardano-cli key-gen-KES vectors: regenerating the right depth-5 tree from r1 gives vk1") {
    val rows =
      Files.readString(Path.of("fixtures", "kes-gen", "vectors.tsv")).linesIterator.toVector
    assert(rows.nonEmpty)
    rows.foreach { row =>
      val Array(name, r1, vk1, vk0, vk) = row.split("\t")
      assertEquals(rootOfSeed(hex(r1), 5).hex, vk1, name)
      assertEquals(
        Blake2b.hash256.hash(Bytes(hex(vk0).value ++ hex(vk1).value)).hex,
        vk,
        name
      )
    }
  }

  test("the key layout is 608 bytes and the public key is the hash of the top pair") {
    val k = fresh
    assertEquals(k.toBytes.size, 608)
    val raw = k.toBytes.toArray
    val vk0 = Bytes.fromArray(raw.slice(544, 576))
    val vk1 = Bytes.fromArray(raw.slice(576, 608))
    assertEquals(k.verificationKey, Blake2b.hash256.hash(Bytes(vk0.value ++ vk1.value)))
    assertEquals(rootOfSeed(Bytes.fromArray(raw.slice(512, 544)), 5), vk1)
  }

  test("every period signs a message the verifier accepts, and only for that period") {
    val k = fresh
    val vk = k.verificationKey
    val msg = hex("deadbeef")
    (0 until Periods).foreach { p =>
      assertEquals(k.period, p)
      val sig = k.sign(msg)
      assertEquals(sig.size, SignatureBytes)
      assert(verified(vk, p, msg, sig), s"period $p")
      if p > 0 then assert(!verified(vk, p - 1, msg, sig), s"period $p under ${p - 1}")
      assert(!verified(vk, p, hex("deadbee0"), sig))
      if p < Periods - 1 then assertEquals(k.evolve(), Right(()))
    }
    assertEquals(k.evolve(), Left(Failure.PeriodOutOfRange(Periods)))
  }

  test("evolving erases the past: no seed of a finished period survives") {
    val k = fresh
    val before = k.toBytes.toArray
    val leafSeed = before.slice(0, 32) // the period-0 leaf seed
    val topSeed = before.slice(512, 544) // the right half's seed
    def contains(hay: Array[Byte], needle: Array[Byte]) =
      hay.indices.exists(i => i + 32 <= hay.length && hay.slice(i, i + 32).sameElements(needle))
    k.evolve()
    assert(!contains(k.toBytes.toArray, leafSeed))
    k.evolveTo(32)
    assert(!contains(k.toBytes.toArray, topSeed))
    assert(k.toBytes.toArray.slice(512, 544).forall(_ == 0))
    k.evolveTo(63)
    val last = k.toBytes.toArray
    assertEquals(k.evolveTo(10), Left(Failure.PeriodOutOfRange(10)))
    assert(!contains(last, topSeed))
    k.forget()
    assert(k.isForgotten)
  }

  test("a key saved at a period loads again at that period and continues") {
    val k = fresh
    k.evolveTo(37)
    val loaded = fromBytes(k.toBytes, 37).toOption.get
    assertEquals(loaded.verificationKey, k.verificationKey)
    val msg = hex("00")
    assert(verified(k.verificationKey, 37, msg, loaded.sign(msg)))
    loaded.evolve()
    assert(verified(k.verificationKey, 38, msg, loaded.sign(msg)))
    // the same bytes do not load at another half
    assertEquals(
      fromBytes(k.toBytes, 3),
      Left(Failure.SeedStateMismatch(Depth, 3, SeedState.Erased))
    )
    assertEquals(
      fromBytes(fresh.toBytes, 40),
      Left(Failure.SeedStateMismatch(Depth, 40, SeedState.Present))
    )
  }

  test("missing material, invalid lengths and periods have distinct error cases") {
    assertEquals(generate(null), Left(Failure.MissingSeed))
    Vector(0, SeedBytes - 1, SeedBytes + 1).foreach { n =>
      assertEquals(
        generate(Bytes.fromArray(new Array[Byte](n))),
        Left(Failure.InvalidSeedLength(n))
      )
    }
    assertEquals(fromBytes(null), Left(Failure.MissingKey))
    assertEquals(fromEvolved(null), Left(Failure.MissingKey))
    Vector(0, KeyBytes - 1, KeyBytes + 1).foreach { n =>
      val bytes = Bytes.fromArray(new Array[Byte](n))
      assertEquals(fromBytes(bytes), Left(Failure.InvalidKeyLength(n)))
      assertEquals(fromEvolved(bytes), Left(Failure.InvalidKeyLength(n)))
    }
    val bytes = fresh.toBytes
    Vector(-1, Periods).foreach { p =>
      assertEquals(fromBytes(bytes, p), Left(Failure.PeriodOutOfRange(p)))
    }
  }

  test("key consistency errors retain the failing tree depth and kind") {
    val bytes = fresh.toBytes
    def flipped(at: Int): Bytes =
      val array = bytes.toArray
      Bytes.wrap(array.updated(at, (array(at) ^ 1).toByte))

    // Layout: leaf seed; level 1's r1/vk0/vk1; ...; level 6's r1/vk0/vk1.
    assertEquals(fromBytes(flipped(0)), Left(Failure.VerificationKeyMismatch(1)))
    assertEquals(fromBytes(flipped(544)), Left(Failure.VerificationKeyMismatch(Depth)))
    assertEquals(fromBytes(flipped(512)), Left(Failure.RightSeedMismatch(Depth)))
    assertEquals(fromBytes(flipped(576)), Left(Failure.RightSeedMismatch(Depth)))
    assertEquals(bytes, fresh.toBytes, "refusing a key does not erase the caller's bytes")
  }

  test("a period-0 key evolved by loading equals one evolved in place") {
    val a = fresh
    val b = fresh
    a.evolveTo(50)
    b.evolveTo(20)
    b.evolveTo(50)
    assertEquals(a.toBytes, b.toBytes)
  }

  test("corrupt or mis-sized keys are refused") {
    val raw = fresh.toBytes.toArray
    assert(fromBytes(Bytes.fromArray(raw.take(100))).isLeft)
    (0 until 608 by 37).foreach { i =>
      val bad = raw.clone()
      bad(i) = (bad(i) ^ 1).toByte
      // a flipped byte of any stored seed, key or verification key is detected at period 0
      assert(fromBytes(Bytes.fromArray(bad)).isLeft, s"byte $i")
    }
  }

  test("an evolved key written out is read back at the same period, for every period") {
    val k = fresh
    val msg = hex("0badc0de")
    (0 until Periods).foreach { p =>
      k.evolveTo(p)
      val back = fromEvolved(k.toBytes).fold(e => fail(s"period $p: $e"), identity)
      assertEquals(back.period, p)
      assertEquals(back.verificationKey, k.verificationKey)
      assertEquals(back.sign(msg), k.sign(msg), s"period $p")
    }
  }

  test("an erased or damaged evolved key is refused, never read as some period") {
    assert(fromEvolved(Bytes.fromArray(new Array[Byte](KeyBytes))).isLeft)
    val k = fresh
    k.evolveTo(9)
    val raw = k.toBytes.toArray
    raw(3) = (raw(3) ^ 1).toByte
    assert(fromEvolved(Bytes.fromArray(raw)).isLeft)
    assert(fromEvolved(Bytes.fromArray(new Array[Byte](10))).isLeft)
  }

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import java.time.Instant
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen
import xyz.kd5ujc.scarab.codec.Rational

/** Slot/epoch arithmetic across the Byron prefix and the Shelley-based eras. */
class EpochGeometrySuite extends ScalaCheckSuite:
  private val start = Instant.parse("2026-01-01T00:00:00Z")

  /** Byron: `byronEpochs` epochs of `byronSlots` one-second slots; then `epochLength` 100 ms slots.
    */
  private def geometry(byronEpochs: Int, byronSlots: Int, epochLength: Int) =
    EpochGeometry(
      start,
      100,
      epochLength,
      TestGeometry.k(5),
      TestGeometry.f("0.2"),
      if byronEpochs == 0 then ByronSpan.Absent else ByronSpan(byronEpochs, byronSlots, 1000)
    )

  private val geometries: Gen[EpochGeometry] = for
    epochs <- Gen.choose(0, 4)
    byronSlots <- Gen.choose(10, 200)
    length <- Gen.choose(300, 5000)
  yield geometry(epochs, byronSlots, length)

  test("the Byron epoch is 50 slots, then Shelley-based epochs of 500 (the up-eras cluster)") {
    val g = geometry(1, 50, 500)
    assertEquals(
      Seq(0, 49, 50, 549, 550, 1049, 1050).map(s => g.epochOf(s)),
      Seq(0, 0, 1, 1, 2, 2, 3).map(BigInt(_))
    )
    assertEquals(Seq(0, 1, 2, 3).map(e => g.firstSlot(e)), Seq(0, 50, 550, 1050).map(BigInt(_)))
    assertEquals(g.firstShelleyEpoch, BigInt(1))
    assert(g.isByronSlot(49) && !g.isByronSlot(50))
  }

  test("without a Byron prefix (Preview) nothing changes") {
    val g = geometry(0, 1, 86400)
    assertEquals(g.epochOf(86399), BigInt(0))
    assertEquals(g.epochOf(86400), BigInt(1))
    assertEquals(g.firstSlot(3), BigInt(3 * 86400))
    assertEquals(g.firstShelleyEpoch, BigInt(0))
  }

  test(
    "a derived Byron span is open-ended until resolved, and resolving it gives the same history"
  ) {
    val derived = geometry(0, 1, 500).copy(byron = ByronSpan.derived(50, 1000))
    assert(derived.byron.derived)
    // until the chain enters Shelley every slot is a Byron slot of the genesis epoch length
    assertEquals(
      Seq(0, 49, 50, 1000, 100000).map(s => derived.epochOf(s)),
      Seq(0, 0, 1, 20, 2000).map(BigInt(_))
    )
    assertEquals(derived.resolved(1), geometry(1, 50, 500))
    // resolving an already fixed history changes nothing
    assertEquals(geometry(2, 50, 500).resolved(7), geometry(2, 50, 500))
  }

  property("an epoch starts at the slot that maps back to it, and slots lie inside their epoch") {
    forAll(geometries, Gen.choose(0L, 100000L)) { (g, slot) =>
      val epoch = g.epochOf(slot)
      g.firstSlot(epoch) <= slot && slot < g.firstSlot(epoch + 1) &&
      g.epochOf(g.firstSlot(epoch)) == epoch &&
      g.firstSlot(epoch + 1) - g.firstSlot(epoch) == g.epochSlots(epoch)
    }
  }

  property("epochs are monotone in the slot") {
    forAll(geometries, Gen.choose(0L, 100000L), Gen.choose(0L, 1000L)) { (g, slot, delta) =>
      g.epochOf(slot) <= g.epochOf(slot + delta)
    }
  }

  property("slot start times invert slotAt, with the slot length changing at the Shelley start") {
    forAll(geometries, Gen.choose(0L, 100000L)) { (g, slot) =>
      g.slotAt(g.slotStart(slot)) == BigInt(slot) &&
      g.slotStart(slot + 1).isAfter(g.slotStart(slot))
    }
  }

  test("slot length changes at the Shelley start") {
    val g = geometry(1, 50, 500)
    assertEquals(g.slotStart(49), start.plusMillis(49000))
    assertEquals(g.slotStart(50), start.plusMillis(50000))
    assertEquals(g.slotStart(51), start.plusMillis(50100))
  }

  private val offsets: Gen[EpochGeometry] = for
    millis <- Gen.choose(0L, 1999L)
    slot <- Gen.oneOf(100L, 200L, 250L, 500L, 1000L, 20000L)
    epochs <- Gen.choose(0, 3)
    length <- Gen.choose(300, 2000)
  yield EpochGeometry(
    start.plusMillis(millis),
    slot,
    length,
    TestGeometry.k(5),
    TestGeometry.f("0.2"),
    if epochs == 0 then ByronSpan.Absent else ByronSpan(epochs, 50, 20000)
  )

  property(
    "a slot starts exactly at its time: slotAt is the inverse of slotStart, to the millisecond"
  ) {
    forAll(offsets, Gen.choose(0L, 100000L)) { (g, slot) =>
      val at = g.slotStart(slot)
      g.slotAt(at) == BigInt(slot) &&
      g.slotAt(at.plusMillis(g.slotLengthMillis.min(1000) - 1)) == BigInt(slot)
    }
  }

  property(
    "the instant before a slot start still belongs to the slot before (no whole-second rounding)"
  ) {
    forAll(offsets, Gen.choose(1L, 100000L)) { (g, slot) =>
      g.slotAt(g.slotStart(slot).minusMillis(1)) == BigInt(slot - 1)
    }
  }

  test("a fractional system start moves slot boundaries by the fraction, not by a second") {
    val g = EpochGeometry(start.plusMillis(350), 100, 500, TestGeometry.k(5), TestGeometry.f("0.2"))
    assertEquals(g.slotAt(start.plusMillis(349)), BigInt(0))
    assertEquals(g.slotAt(start.plusMillis(449)), BigInt(0))
    assertEquals(g.slotAt(start.plusMillis(450)), BigInt(1))
    assertEquals(g.slotAt(start.plusMillis(1349)), BigInt(9))
    assertEquals(g.slotAt(start.plusMillis(1350)), BigInt(10))
    // a system start one whole second apart is ten slots apart
    val earlier = g.copy(systemStart = start.minusMillis(650))
    assertEquals(
      earlier.slotAt(start.plusMillis(1350)) - g.slotAt(start.plusMillis(1350)),
      BigInt(10)
    )
  }

  private def fixtureWindows(network: String): Either[ConfigError, (BigInt, BigInt)] =
    Genesis
      .shelley(Files.readString(Path.of(s"fixtures/$network/genesis/shelley-genesis.json")))
      .map(g =>
        (g.geometry.stabilityWindow.toBigInt, g.geometry.randomnessStabilisationWindow.toBigInt)
      )

  test("3k/f and 4k/f of the real network configurations keep their values (exact 3k/f, 4k/f)") {
    // mainnet and preprod: k 2160, f 1/20; preview: k 432, f 1/20 (the Shelley genesis fixtures)
    assertEquals(fixtureWindows("mainnet"), Right((BigInt(129600), BigInt(172800))))
    assertEquals(fixtureWindows("preprod"), Right((BigInt(129600), BigInt(172800))))
    assertEquals(fixtureWindows("preview"), Right((BigInt(25920), BigInt(34560))))
  }

  property("the windows are the exact ceilings of 3k/f and 4k/f") {
    forAll(Gen.choose(1, 5000), Gen.choose(1, 999)) { (k, thousandths) =>
      val g = geometry(0, 1, 1000000).copy(
        securityParam = TestGeometry.k(k),
        activeSlotsCoeff = TestGeometry.f(Rational(thousandths, 1000))
      )
      // ceil(a / b) for positive integers, in integer arithmetic only
      def ceilDiv(a: BigInt, b: BigInt): BigInt = (a + b - 1) / b
      g.stabilityWindow.toBigInt == ceilDiv(BigInt(3 * k) * 1000, BigInt(thousandths)) &&
      g.randomnessStabilisationWindow.toBigInt == ceilDiv(BigInt(4 * k) * 1000, BigInt(thousandths))
    }
  }

  test("the windows are Word64: ceiling of the exact 3k/f and 4k/f, low 64 bits kept") {
    val maxWord = BigInt(2).pow(64) - 1
    def windows(k: BigInt, f: Rational) =
      val g = geometry(0, 1, 1000).copy(
        securityParam = TestGeometry.k(k.toLong),
        activeSlotsCoeff = TestGeometry.f(f)
      )
      (g.stabilityWindow.toBigInt, g.randomnessStabilisationWindow.toBigInt)
    val modulus = BigInt(2).pow(64)
    // 3 * 2^63 and 4 * 2^63 do not fit: 2^63 and 0 remain
    assertEquals(windows(BigInt(2).pow(63), Rational.one), (BigInt(2).pow(63), BigInt(0)))
    // 8 * (2^64 - 1) = 2^64 - 8 modulo 2^64
    assertEquals(windows(maxWord, Rational(1, 2))._2, modulus - 8)
    // the smallest coefficient, 10^-19: 3 * 2160 * 10^19 modulo 2^64
    assertEquals(
      windows(2160, Rational(1, BigInt(10).pow(19))),
      ((3 * 2160 * BigInt(10).pow(19)) % modulus, (4 * 2160 * BigInt(10).pow(19)) % modulus)
    )
  }

  private def shelley(edit: io.circe.JsonObject => io.circe.JsonObject) =
    val text = Files.readString(Path.of("fixtures/preview/genesis/shelley-genesis.json"))
    val json = io.circe.parser.parse(text).fold(e => fail(e.message), identity)
    Genesis.shelley(json.mapObject(edit).noSpaces)

  test("activeSlotsCoeff is a PositiveUnitInterval: the object form and the decimal limits") {
    def coefficient(value: String) =
      shelley(
        _.add("activeSlotsCoeff", io.circe.parser.parse(value).fold(e => fail(e.message), identity))
      )
        .map(_.geometry.activeSlotsCoeff.rational)
    assertEquals(coefficient("""{"numerator": 1, "denominator": 20}"""), Right(Rational(1, 20)))
    assertEquals(
      coefficient("0.1234567890123456789"),
      Right(Rational(BigInt("1234567890123456789"), BigInt(10).pow(19)))
    )
    assertEquals(
      coefficient("0.00000000000000000001"),
      Left(
        ConfigError(
          ConfigProblem.GenesisNumber(
            GenesisNumberField.ActiveSlotsCoeff,
            json.NumberError.TooPrecise(json.Scientific(1, -20))
          )
        )
      )
    )
    assertEquals(
      coefficient("1.5"),
      Left(
        ConfigError(
          ConfigProblem.GenesisNumber(
            GenesisNumberField.ActiveSlotsCoeff,
            json.NumberError.OutsideOfBounds(Rational(3, 2))
          )
        )
      )
    )
  }

  test("securityParam is a NonZero Word64") {
    def k(value: io.circe.Json) =
      shelley(
        _.add("securityParam", value).add("epochLength", io.circe.Json.fromLong(Long.MaxValue))
      )
        .map(_.geometry.k)
    assertEquals(k(io.circe.Json.fromLong(432)), Right(BigInt(432)))
    assertEquals(k(io.circe.Json.fromBigInt(BigInt(2).pow(32))), Right(BigInt(2).pow(32)))
    assertEquals(
      k(io.circe.Json.fromLong(0)),
      Left(
        ConfigError(
          ConfigProblem.GenesisNumber(GenesisNumberField.SecurityParam, json.NumberError.Zero)
        )
      )
    )
    assertEquals(
      k(io.circe.Json.fromLong(-1)),
      Left(
        ConfigError(
          ConfigProblem.GenesisNumber(
            GenesisNumberField.SecurityParam,
            json.NumberError.NotWord64(json.Scientific(-1, 0))
          )
        )
      )
    )
  }

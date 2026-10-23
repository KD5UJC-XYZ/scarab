// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusData.{I, List as PList, Map as PMap}

/** Every reader of a rational field reads tag 30 as the reference does below decoder version 12
  * (`decodeRationalWithTag`, `Binary/Plain.hs:155-163`, then `boundRational`,
  * `BaseTypes.hs:348-374`, f649f975): signed and bignum parts are accepted when the reduced value
  * is in the field's interval, and a value outside it is refused. The oracle corpus reaches body
  * decoding; these cases reach the three readers of an already decoded parameter update.
  */
class RationalFieldsSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def big(power: Int, negative: Boolean = false): String =
    val magnitude = BigInt(1) << power
    val raw = magnitude.toByteArray.dropWhile(_ == 0)
    val len = raw.length
    (if negative then "c3" else "c2") + f"${0x40 + len}%02x" + raw
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def ratio(n: String, d: String): String = "d81e82" + n + d

  private val half = ratio("01", "02")
  private val halfSigned = ratio("20", "21")
  private val halfBig = ratio(big(64), big(65))
  private val threeHalves = ratio("03", "02")
  private val threeHalvesSigned = ratio("22", "21")
  private val zeroDenominator = ratio("01", "00")
  private val minusHalf = ratio("20", "02")
  private val tooBig = ratio(big(64), "01")
  private val indefiniteBignum = ratio("c25f4101ff", "02")
  private val widenedTag = ratio("d802410" + "1", "02")

  private val conway = TestLedger.params(9)

  private def update(fields: (Int, String)*): ParamUpdate =
    ParamUpdate(fields.map((k, v) => k -> hex(v)).toMap)

  private def applied(fields: (Int, String)*): Either[LedgerDecodeError, ProtocolParams] =
    ProtocolParams.update(conway, update(fields*))

  private def array(items: String*): String =
    "8" + items.length.toHexString + items.mkString

  test(
    "a rational written with signed or bignum parts is its reduced value in ProtocolParams.update"
  ) {
    for form <- Seq(half, halfSigned, halfBig) do
      val Right(p) = applied(9 -> form, 10 -> form, 11 -> form): @unchecked
      assertEquals((p.a0, p.rho, p.tau), (Rational(1, 2), Rational(1, 2), Rational(1, 2)), form)
    val Right(prices) = applied(19 -> array(halfSigned, halfBig)): @unchecked
    assertEquals((prices.priceMem, prices.priceSteps), (Rational(1, 2), Rational(1, 2)))
  }

  test("ProtocolParams.update refuses a unit-interval field outside 0 to 1, in any written form") {
    for
      (key, name) <- Seq(10 -> "rho", 11 -> "tau", 12 -> "d");
      form <- Seq(threeHalves, threeHalvesSigned, minusHalf)
    do
      applied(key -> form) match
        case Left(LedgerDecodeError.RationalOutOfBounds(`name`, _, _)) => ()
        case other => fail(s"$name $form: $other")
  }

  test(
    "ProtocolParams.update lets the non-negative fields exceed one and refuses what does not fit"
  ) {
    val Right(p) = applied(9 -> threeHalvesSigned, 33 -> threeHalves): @unchecked
    assertEquals(p.a0, Rational(3, 2))
    assertEquals(p.minFeeRefScriptCostPerByte, Rational(3, 2))
    assert(applied(9 -> minusHalf).isLeft)
    assert(applied(9 -> tooBig).isLeft)
    assert(applied(19 -> array(half, minusHalf)).isLeft)
  }

  test(
    "ProtocolParams.update refuses a zero denominator and the integer forms cborg does not read"
  ) {
    assertEquals(applied(9 -> zeroDenominator), Left(LedgerDecodeError.ZeroDenominator("a0")))
    for form <- Seq(indefiniteBignum, widenedTag) do
      applied(9 -> form) match
        case Left(LedgerDecodeError.Expected("a0", LedgerDecodeError.Shape.Integer, _)) => ()
        case other => fail(s"$form: $other")
  }

  test("voting thresholds are unit-interval rationals") {
    val five = array(Seq.fill(5)(halfSigned)*)
    val ten = array(Seq.fill(10)(halfBig)*)
    assert(applied(25 -> five, 26 -> ten).isRight)
    assert(applied(25 -> array(Seq.fill(4)(halfSigned) :+ threeHalvesSigned*)).isLeft)
    assert(applied(26 -> array(Seq.fill(9)(halfBig) :+ threeHalves*)).isLeft)
  }

  private val lite =
    val genesis = Genesis
      .shelley(Files.readString(Path.of("fixtures/preview/genesis/shelley-genesis.json")))
      .fold(e => fail(e.message), identity)
    LiteLedger.fromGenesis(genesis.raw).fold(e => fail(e.message), identity).params

  test("LiteLedger.applyUpdate reads d as a unit-interval rational") {
    for form <- Seq(half, halfSigned, halfBig) do
      assertEquals(LiteLedger.applyUpdate(lite, update(12 -> form)).map(_.d), Right(Rational(1, 2)))
    for form <- Seq(threeHalves, threeHalvesSigned, minusHalf) do
      assertEquals(
        LiteLedger.applyUpdate(lite, update(12 -> form)),
        Left(
          LedgerDecodeError.RationalOutOfBounds("d", BigInt(if form == minusHalf then -1 else 3), 2)
        )
      )
    assertEquals(
      LiteLedger.applyUpdate(lite, update(12 -> zeroDenominator)),
      Left(LedgerDecodeError.ZeroDenominator("d"))
    )
  }

  private def translated(fields: (Int, String)*) = ScriptContexts.changedParameters(update(fields*))
  private val undecodable = Left(TranslationError.Undecodable(DataSite.ParameterUpdate))

  test("ScriptContexts.changedParameters translates a rational in its field's interval") {
    for form <- Seq(half, halfSigned, halfBig) do
      assertEquals(
        translated(10 -> form),
        Right(PMap(Vector((I(10): PlutusData) -> PList(Vector(I(1), I(2)))))),
        form
      )
    assert(translated(9 -> threeHalvesSigned, 33 -> threeHalves).isRight)
  }

  test("ScriptContexts.changedParameters refuses rho, tau and the thresholds above one") {
    for key <- Seq(10, 11) do
      assertEquals(translated(key -> threeHalves), undecodable)
      assertEquals(translated(key -> threeHalvesSigned), undecodable)
    assertEquals(translated(25 -> array(half, threeHalves)), undecodable)
    assertEquals(translated(26 -> array(halfSigned, threeHalvesSigned)), undecodable)
    assert(translated(25 -> array(half, halfBig)).isRight)
  }

  test("ScriptContexts.changedParameters refuses a rational the reference cannot decode") {
    for form <- Seq(minusHalf, tooBig, zeroDenominator, indefiniteBignum, widenedTag) do
      assertEquals(translated(9 -> form), undecodable, form)
  }

  test("the test's bignum helper writes the heads cborg reads") {
    val decoded = Cbor.decode(hex(big(64))).fold(e => fail(e.toString), identity)
    assertEquals(decoded.original, hex("c249010000000000000000"))
  }

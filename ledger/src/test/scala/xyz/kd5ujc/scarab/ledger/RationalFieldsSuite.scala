// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusData.{I, List as PList, Map as PMap}

/** Every reader of a rational field reads tag 30 as the reference does below decoder version 12
  * (`decodeRationalWithTag`, `Binary/Plain.hs:155-163`, then `boundRational`,
  * `BaseTypes.hs:348-374`, f649f975): signed and bignum parts are accepted when the reduced value
  * is in the field's interval, and a value outside it is refused. The oracle corpus reaches body
  * decoding; these cases reach the two readers of an already decoded parameter update.
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
    ProtocolParams.update[xyz.kd5ujc.scarab.ledger.state.Conway](conway, update(fields*))

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
      (key, name) <- Seq(10 -> DecodeField.Rho, 11 -> DecodeField.Tau, 12 -> DecodeField.D);
      form <- Seq(threeHalves, threeHalvesSigned, minusHalf)
    do
      applied(key -> form) match
        case Left(LedgerDecodeError.RationalOutOfBounds(`name`, _)) => ()
        case other                                                  => fail(s"$name $form: $other")
  }

  test(
    "ProtocolParams.update lets the non-negative fields exceed one and refuses what does not fit"
  ) {
    val Right(p) = applied(9 -> threeHalvesSigned, 33 -> threeHalves): @unchecked
    assertEquals(p.a0, Rational(3, 2))
    assertEquals(p.minFeeRefScriptCostPerByte, Rational(3, 2))
    assert(applied(9 -> minusHalf) match {
      case Left(
            LedgerDecodeError
              .RationalOutOfBounds(DecodeField.A0, LedgerDecodeError.OutOfBounds.Reduced(_))
          ) =>
        true
      case _ => false
    })
    assert(applied(9 -> tooBig) match {
      case Left(
            LedgerDecodeError
              .RationalOutOfBounds(DecodeField.A0, LedgerDecodeError.OutOfBounds.Wide(_, _))
          ) =>
        true
      case _ => false
    })
    assert(applied(19 -> array(half, minusHalf)) match {
      case Left(
            LedgerDecodeError
              .RationalOutOfBounds(DecodeField.PriceSteps, LedgerDecodeError.OutOfBounds.Reduced(_))
          ) =>
        true
      case _ => false
    })
  }

  test(
    "ProtocolParams.update refuses a zero denominator and the integer forms cborg does not read"
  ) {
    assertEquals(
      applied(9 -> zeroDenominator),
      Left(LedgerDecodeError.ZeroDenominator(DecodeField.A0))
    )
    for form <- Seq(indefiniteBignum, widenedTag) do
      applied(9 -> form) match
        case Left(LedgerDecodeError.Expected(DecodeField.A0, LedgerDecodeError.Shape.Integer, _)) =>
          ()
        case other => fail(s"$form: $other")
  }

  test("voting thresholds are unit-interval rationals") {
    val five = array(Seq.fill(5)(halfSigned)*)
    val ten = array(Seq.fill(10)(halfBig)*)
    assert(applied(25 -> five, 26 -> ten).isRight)
    assert(applied(25 -> array(Seq.fill(4)(halfSigned) :+ threeHalvesSigned*)) match {
      case Left(
            LedgerDecodeError.RationalOutOfBounds(
              DecodeField.PoolVotingThresholds,
              LedgerDecodeError.OutOfBounds.Reduced(_)
            )
          ) =>
        true
      case _ => false
    })
    assert(applied(26 -> array(Seq.fill(9)(halfBig) :+ threeHalves*)) match {
      case Left(
            LedgerDecodeError.RationalOutOfBounds(
              DecodeField.DRepVotingThresholds,
              LedgerDecodeError.OutOfBounds.Reduced(_)
            )
          ) =>
        true
      case _ => false
    })
  }

  private def shelley(fields: (Int, String)*): Either[LedgerDecodeError, ProtocolParams] =
    ProtocolParams.update[xyz.kd5ujc.scarab.ledger.state.Shelley](
      TestLedger.params(2),
      update(fields*)
    )

  test("ProtocolParams.update reads d as a unit-interval rational") {
    for form <- Seq(half, halfSigned, halfBig) do
      assertEquals(shelley(12 -> form).map(_.d), Right(Rational(1, 2)))
    for (form, numerator) <- Seq(threeHalves -> 3, threeHalvesSigned -> 3, minusHalf -> -1) do
      assertEquals(
        shelley(12 -> form),
        Left(
          LedgerDecodeError.RationalOutOfBounds(
            DecodeField.D,
            LedgerDecodeError.OutOfBounds.Reduced(Rational(numerator, 2))
          )
        )
      )
    assertEquals(
      shelley(12 -> zeroDenominator),
      Left(LedgerDecodeError.ZeroDenominator(DecodeField.D))
    )
  }

  private def conwayUpdate(fields: (Int, String)*) =
    ConwayParamUpdate.fromEncoded(fields.toVector.map((k, v) => BigInt(k) -> hex(v)))

  private def translated(fields: (Int, String)*) =
    conwayUpdate(fields*).map(ScriptContexts.changedParameters)

  private def outOfBounds(result: Either[LedgerDecodeError, PlutusData]): Boolean = result match
    case Left(LedgerDecodeError.RationalOutOfBounds(_, _)) => true
    case _                                                 => false

  test("ScriptContexts.changedParameters translates a rational in its field's interval") {
    for form <- Seq(half, halfSigned, halfBig) do
      assertEquals(
        translated(10 -> form),
        Right(PMap(Vector((I(10): PlutusData) -> PList(Vector(I(1), I(2)))))),
        form
      )
    assert(translated(9 -> threeHalvesSigned, 33 -> threeHalves).isRight)
  }

  test("a Conway update refuses rho, tau and the thresholds above one when it is decoded") {
    for key <- Seq(10, 11) do
      assert(outOfBounds(translated(key -> threeHalves)))
      assert(outOfBounds(translated(key -> threeHalvesSigned)))
    assert(outOfBounds(translated(25 -> array(half, half, half, half, threeHalves))))
    assert(outOfBounds(translated(26 -> array(Seq.fill(9)(halfSigned) :+ threeHalvesSigned*))))
    assert(translated(25 -> array(half, halfBig, half, half, half)).isRight)
  }

  test("a Conway update refuses a rational the reference cannot decode") {
    for form <- Seq(minusHalf, tooBig, zeroDenominator, indefiniteBignum, widenedTag) do
      assert(
        translated(9 -> form) match {
          case Left(LedgerDecodeError.Expected(_, _, _)) |
              Left(LedgerDecodeError.RationalOutOfBounds(_, _)) |
              Left(LedgerDecodeError.ZeroDenominator(_)) =>
            true
          case _ => false
        },
        form
      )
  }

  test("the test's bignum helper writes the heads cborg reads") {
    val decoded = Cbor.decode(hex(big(64))).fold(e => fail(e.toString), identity)
    assertEquals(decoded.original, hex("c249010000000000000000"))
  }

  /** A CBOR head of any argument, as hex. */
  private def head(major: Int, n: Long): String =
    val m = major << 5
    if n < 24 then f"${m | n.toInt}%02x"
    else if n < 0x100 then f"${m | 24}%02x$n%02x"
    else if n < 0x10000 then f"${m | 25}%02x$n%04x"
    else if n < 0x100000000L then f"${m | 26}%02x$n%08x"
    else f"${m | 27}%02x$n%016x"

  /** A non-negative integer as a tag 2 bignum over a definite byte string with no leading zero. */
  private def bignum(magnitude: BigInt): String =
    val raw = magnitude.toByteArray.dropWhile(_ == 0)
    "c2" + head(2, raw.length.toLong) + Bytes.fromArray(raw).hex

  /** A number of exactly `bits` bits, its other bits drawn from `seed`. */
  private def random(bits: Int, seed: Long): BigInt =
    BigInt(bits - 1, new scala.util.Random(seed)).setBit(bits - 1)

  private def wide(numeratorBits: Int, denominatorBits: Int) =
    LedgerDecodeError.OutOfBounds.Wide(numeratorBits, denominatorBits)

  test("a rational whose reduced form is beyond Word64 is refused unreduced, in a short message") {
    val coprimeWide = ratio(bignum(random(8 * 262144, 1)), bignum(random(8 * 262144, 2)))
    val overOne = ratio(bignum(random(8 * 1048576 - 64, 3)), "01")
    for (form, value) <- Seq(coprimeWide -> wide(2097152, 2097152), overOne -> wide(8388544, 1)) do
      applied(9 -> form) match
        case Left(e @ LedgerDecodeError.RationalOutOfBounds(DecodeField.A0, `value`)) =>
          assert(e.message.length < 256, s"${e.message.length} characters")
        case other => fail(s"not refused as $value: ${other.left.map(_.productPrefix)}")
  }

  test("a rational is refused as soon as its reduced numerator or denominator reaches 2^64") {
    val max = (BigInt(1) << 64) - 1
    val g = random(8 * 102400, 6)
    assertEquals(applied(9 -> ratio(bignum(max), "01")).map(_.a0), Right(Rational(max)))
    assertEquals(
      applied(9 -> ratio(bignum(g * max), bignum(g * (max - 1)))).map(_.a0),
      Right(Rational(max, max - 1))
    )
    assertEquals(
      applied(9 -> ratio(bignum(max + 1), "01")),
      Left(LedgerDecodeError.RationalOutOfBounds(DecodeField.A0, wide(65, 1)))
    )
    assertEquals(
      applied(9 -> ratio(bignum(g * (max + 1)), bignum(g * max))),
      Left(
        LedgerDecodeError.RationalOutOfBounds(
          DecodeField.A0,
          wide((g * (max + 1)).bitLength, (g * max).bitLength)
        )
      )
    )
  }

  test("a rational whose parts share a factor of 100 KB is its reduced value in every reader") {
    val g = random(8 * 102400, 7)
    val halfHuge = ratio(bignum(g), bignum(g * 2))
    val minusHalfHuge = ratio("c3" + bignum(g - 1).drop(2), bignum(g * 2))
    val Right(p) = applied(9 -> halfHuge, 10 -> halfHuge, 11 -> halfHuge): @unchecked
    assertEquals((p.a0, p.rho, p.tau), (Rational(1, 2), Rational(1, 2), Rational(1, 2)))
    assertEquals(shelley(12 -> halfHuge).map(_.d), Right(Rational(1, 2)))
    assertEquals(
      translated(10 -> halfHuge),
      Right(PMap(Vector((I(10): PlutusData) -> PList(Vector(I(1), I(2))))))
    )
    assert(translated(25 -> array(halfHuge, half, half, half, half)).isRight)
    assertEquals(
      applied(9 -> minusHalfHuge),
      Left(
        LedgerDecodeError.RationalOutOfBounds(
          DecodeField.A0,
          LedgerDecodeError.OutOfBounds.Reduced(Rational(-1, 2))
        )
      )
    )
  }

  test("a zero denominator is refused before the length of the numerator matters") {
    assertEquals(
      applied(9 -> ratio(bignum(random(8 * 1048576 - 64, 8)), "00")),
      Left(LedgerDecodeError.ZeroDenominator(DecodeField.A0))
    )
  }

  private lazy val conwayBlock: (Header, Bytes) = java.nio.file.Files
    .readString(java.nio.file.Path.of("fixtures/eras/blocks.hex"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(hex)
    .map(raw => Block.decode(raw, BigInt(50)).fold(e => fail(e.message), b => b.header -> raw))
    .find(_._1.era == xyz.kd5ujc.scarab.protocol.Era.Conway)
    .getOrElse(fail("no Conway block in the fixture"))

  private def items(node: xyz.kd5ujc.scarab.codec.Node): Vector[xyz.kd5ujc.scarab.codec.Node] =
    node.value match
      case xyz.kd5ujc.scarab.codec.Value.Arr(values) => values
      case other                                     => fail(s"expected an array, got $other")

  /** The announced Conway header's block with one transaction, whose body registers a pool with
    * `margin`; the body hash is the header's no longer, but the transactions are decoded first.
    */
  private def forgedBlock(margin: String): (Header, Bytes) =
    val (header, raw) = conwayBlock
    val block = items(items(Cbor.decode(raw).fold(e => fail(e.message), identity))(1))
    val certificate = "8a03" + "581c" + "02" * 28 + "5820" + "03" * 32 + "0101" + margin +
      "581de1" + "04" * 28 + "8080f6"
    val body = "a4" + "00818258" + "20" + "01" * 32 + "00" + "0181825" + "81d61" + "07" * 28 +
      "1a000f4240" + "021a00030d40" + "0481" + certificate
    header -> (raw.take(block(1).offset) ++ hex("81" + body + "81a0a080"))

  test("a fetched body whose pool margin is two bignums of 128 KB is refused in a short message") {
    val (header, forged) =
      forgedBlock(ratio(bignum(random(8 * 131072, 4)), bignum(random(8 * 131072, 5))))
    BlockTransport.check(header, forged) match
      case Left(
            e @ BlockTransportError.TransactionDecode(
              _,
              LedgerDecodeError.RationalOutOfBounds(DecodeField.PoolMargin, value)
            )
          ) =>
        assertEquals(value, wide(1048576, 1048576))
        assert(e.message.length < 256, s"${e.message.length} characters")
      case other => fail(s"not refused for its margin: ${other.left.map(_.productPrefix)}")
  }

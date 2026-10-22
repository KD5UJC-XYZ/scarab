// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

/** Field 18 of a protocol-parameter update, decoded by the rule of the era's decoder version
  * (`CostModels.hs:302-337,426-440`, cardano-ledger `f649f975`). The cases are derived from those
  * lines, entering through the transaction body (Alonzo and Babbage, decoder version below 9) and
  * the Conway parameter-change proposal (version 9 and above). They have not been run against the
  * Haskell decoder.
  */
class CostModelDecodeSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def head(major: Int, n: BigInt): String =
    if n < 24 then f"${(major << 5) | n.toInt}%02x"
    else if n < 256 then f"${(major << 5) | 24}%02x${n.toInt}%02x"
    else if n < 65536 then f"${(major << 5) | 25}%02x${n.toInt}%04x"
    else f"${(major << 5) | 27}%02x${n.toLong}%016x"

  /** CBOR integer (the unsigned 64-bit argument is written in full from 65536 on). */
  private def int(v: BigInt): String = if v >= 0 then head(0, v) else head(1, -1 - v)

  private def array(items: Seq[String]): String = head(4, items.size) + items.mkString
  private def model(size: Int): String = array(Seq.fill(size)(int(1)))
  private def models(entries: (BigInt, String)*): String =
    head(5, entries.size) + entries.map((k, m) => int(k) + m).mkString

  private val genesisKey = "581c" + "11" * 28

  /** A transaction body whose update proposal sets only the cost models. */
  private def body(costModels: String): Bytes =
    hex(
      "a4" + "00" + "80" + "01" + "80" + "02" + "00" +
        "06" + "82" + "a1" + genesisKey + "a1" + "12" + costModels + "01"
    )

  private def decodedLegacy(costModels: String) =
    TxBody.decode(body(costModels), Address.Mode.Rigorous).map(_.update)

  private val account = "581d" + "e0" + "22" * 28
  private val anchor = "82" + "6161" + "5820" + "33" * 32

  /** A Conway proposal for a parameter change that sets only the cost models. */
  private def proposal(costModels: String) =
    val raw = hex(
      "84" + "00" + account + "84" + "00" + "f6" + "a1" + "12" + costModels + "f6" + anchor
    )
    CborRead.decode(raw).flatMap(Proposal.decode).map(_.action)

  private val int64 = (BigInt(Long.MinValue), BigInt(Long.MaxValue))

  private def valueOutOfRange(v: BigInt) =
    LedgerDecodeError.NumberOutOfRange("cost", v, int64._1, int64._2)

  private def withValue(language: Int, v: BigInt, size: Int): String =
    models(BigInt(language) -> array(int(v) +: Seq.fill(size - 1)(int(1))))

  private def appliedFields(costModels: String) =
    decodedLegacy(costModels).map(_.map(_.proposals.values.head.fields))

  // Before decoder version 9 (Alonzo, Babbage): CostModels.hs:306-337.

  test("legacy: exactly 166 PlutusV1 and 175 PlutusV2 parameters are accepted") {
    val both = models(BigInt(0) -> model(166), BigInt(1) -> model(175))
    val read = appliedFields(both).fold(e => fail(e.message), identity)
    assertEquals(read.map(_.keySet), Some(Set(18)))
  }

  test("legacy: a PlutusV1 list of another length is refused") {
    for n <- Seq(0, 165, 167) do
      assertEquals(
        decodedLegacy(models(BigInt(0) -> model(n))).map(_ => ()),
        Left(LedgerDecodeError.CostModelLength(0, 166, n)),
        s"V1 with $n"
      )
  }

  test("legacy: a PlutusV2 list of another length is refused") {
    for n <- Seq(0, 174, 176) do
      assertEquals(
        decodedLegacy(models(BigInt(1) -> model(n))).map(_ => ()),
        Left(LedgerDecodeError.CostModelLength(1, 175, n)),
        s"V2 with $n"
      )
  }

  test("legacy: PlutusV3 and PlutusV4 are not read before version 9") {
    for l <- Seq(2, 3) do
      assertEquals(
        decodedLegacy(models(BigInt(l) -> model(251))).map(_ => ()),
        Left(LedgerDecodeError.UnsupportedCostModelLanguage(l))
      )
  }

  test("legacy: a language that is not a Language is refused, an unknown one is not kept") {
    for l <- Seq(BigInt(4), BigInt(255), BigInt(-1)) do
      assertEquals(
        decodedLegacy(models(l -> model(10))).map(_ => ()),
        Left(LedgerDecodeError.UnknownTag("cost model language", l))
      )
  }

  test("legacy: a parameter outside Int64 is refused, the bounds are accepted") {
    for v <- Seq(BigInt(2).pow(63), -BigInt(2).pow(63) - 1) do
      assertEquals(
        decodedLegacy(withValue(0, v, 166)).map(_ => ()),
        Left(valueOutOfRange(v))
      )
    for v <- Seq(int64._1, int64._2) do
      assert(decodedLegacy(withValue(1, v, 175)).isRight, s"bound $v")
  }

  test("legacy: a repeated language is read once, as the reference does before version 9") {
    val twice = models(BigInt(0) -> model(166), BigInt(0) -> model(166))
    assert(decodedLegacy(twice).isRight)
  }

  // From decoder version 9 (Conway): CostModels.hs:302-304,426-440.

  test("open: any length is accepted for a known language") {
    for (l, n) <- Seq((0, 0), (0, 3), (1, 1), (1, 300), (2, 5), (2, 252), (3, 1)) do
      assert(proposal(models(BigInt(l) -> model(n))).isRight, s"language $l with $n")
  }

  test("open: an unknown language up to 255 is kept, and an empty map is accepted") {
    assert(proposal(models(BigInt(4) -> model(2), BigInt(255) -> model(0))).isRight)
    assert(proposal(models()).isRight)
  }

  test("open: a language that is not a Word8 is refused") {
    for l <- Seq(BigInt(256), BigInt(65536), BigInt(-1)) do
      assertEquals(
        proposal(models(l -> model(2))).map(_ => ()),
        Left(LedgerDecodeError.NumberOutOfRange("cost model language", l, 0, 255))
      )
  }

  test("open: a parameter outside Int64 is refused, in a known and an unknown language") {
    for
      language <- Seq(0, 1, 2, 77)
      v <- Seq(BigInt(2).pow(63), -BigInt(2).pow(63) - 1)
    do
      assertEquals(
        proposal(withValue(language, v, 3)).map(_ => ()),
        Left(valueOutOfRange(v)),
        s"language $language value $v"
      )
    for v <- Seq(int64._1, int64._2) do assert(proposal(withValue(2, v, 3)).isRight, s"bound $v")
  }

  test("open: a repeated language is refused (decodeMapByKey enforces no duplicates)") {
    assertEquals(
      proposal(models(BigInt(0) -> model(2), BigInt(0) -> model(3))).map(_ => ()),
      Left(LedgerDecodeError.DuplicateKey("cost models"))
    )
  }

  test("a cost-model entry that is not an array of integers is refused by both rules") {
    val text = models(BigInt(0) -> array(Seq("6161")))
    assert(decodedLegacy(text).isLeft)
    assert(proposal(text).isLeft)
  }

  test("the update keeps the original bytes of field 18") {
    val read = models(BigInt(0) -> model(166))
    val fields = appliedFields(read).fold(e => fail(e.message), identity).get
    assertEquals(fields(18), hex(read))
  }
end CostModelDecodeSuite

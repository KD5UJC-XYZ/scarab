// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The Conway `PParamsUpdate` of a parameter-change proposal (`Core/PParams.hs:257-293`,
  * `Conway/PParams.hs:643-709,862-894`, cardano-ledger f649f975): exactly the Conway update keys,
  * each value at its field's type, refused when the transaction is decoded, and applied totally.
  * The reference verdicts for these encodings inside a whole body are in the oracle corpus
  * (`fixtures/reference/conway-decoding`, "parameter update: ..."); these cases check the typed
  * values and errors.
  */
class ConwayParamUpdateSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def decode(s: String): Either[LedgerDecodeError, ConwayParamUpdate] =
    CborRead.decode(hex(s)).flatMap(ConwayParamUpdate.decode)

  private def decoded(s: String): ConwayParamUpdate = decode(s).fold(e => fail(e.message), identity)

  private val site = DecodeField.ParameterUpdate

  test("every Conway update key has a decoder, and the field it reads carries that key") {
    val conwayKeys = ((0 to 11) ++ (16 to 33)).toSet
    assertEquals(ConwayParam.decoders.keySet, conwayKeys.map(BigInt(_)))
    val samples: Map[Int, String] = Map(
      9 -> "d81e820103",
      10 -> "d81e820103",
      11 -> "d81e820103",
      18 -> "a1008101",
      19 -> "82d81e820103d81e820103",
      20 -> "820102",
      21 -> "820102",
      25 -> ("85" + "d81e820103" * 5),
      26 -> ("8a" + "d81e820103" * 10),
      33 -> "d81e820103"
    )
    for key <- conwayKeys do
      val value = samples.getOrElse(key, "01")
      val encodedKey = if key < 24 then f"$key%02x" else f"18$key%02x"
      val update = decoded("a1" + encodedKey + value)
      assertEquals(update.keys, Set(key), s"key $key")
      assertEquals(update.params.map(_.key), Vector(key), s"key $key")
  }

  test("typed values are the values read, and the original encoding of each is kept") {
    // {0: 44 written with a 4-byte head, 9: 2/4, 19: [1/2, 1/3], 20: [7, 8]}
    val raw = "a4" + "001a0000002c" + "09d81e820204" + "1382d81e820102d81e820103" + "1482" + "0708"
    val update = decoded(raw)
    import ConwayParam.*
    assertEquals(
      update.params,
      Vector(
        TxFeePerByte(Unsigned.from[Unsigned.Width.W64](44).getOrElse(fail("44"))),
        A0(Rational(1, 2)),
        Prices(Rational(1, 2), Rational(1, 3)),
        MaxTxExUnits(ExUnits(7, 8))
      )
    )
    assertEquals(
      update.encodings,
      Vector(
        0 -> hex("1a0000002c"),
        9 -> hex("d81e820204"),
        19 -> hex("82d81e820102d81e820103"),
        20 -> hex("820708")
      )
    )
  }

  test("an empty update decodes (the GOV rule refuses it as malformed)") {
    assert(decoded("a0").isEmpty)
    assert(!GovChecks.wellFormed(TestLedger.params(10), decoded("a0")))
  }

  test("keys 12 to 15 are not Conway fields: d, extra entropy, protocol version, min UTxO value") {
    val legacy = Vector(
      12 -> "0cd81e820102",
      13 -> "0d8100",
      14 -> "0e820b00",
      15 -> "0f1a000f4240"
    )
    for (key, field) <- legacy do
      assertEquals(decode("a1" + field), Left(LedgerDecodeError.UnknownKey(site, key)), s"$key")
  }

  test("an unknown key is refused, also beyond 2^32 where an Int would wrap to a Conway key") {
    assertEquals(decode("a1182200"), Left(LedgerDecodeError.UnknownKey(site, 34)))
    assertEquals(
      decode("a11b0000000100000010" + "01"),
      Left(LedgerDecodeError.UnknownKey(site, (BigInt(1) << 32) + 16))
    )
    assertEquals(
      decode("a2" + "0018" + "2c" + "0cd81e820102"),
      Left(LedgerDecodeError.UnknownKey(site, 12))
    )
  }

  test("a repeated key is refused before its value is read, whatever the values") {
    val duplicate = Left(LedgerDecodeError.DuplicateKey(site))
    assertEquals(decode("a2" + "00182c" + "00182c"), duplicate)
    assertEquals(decode("a2" + "1001" + "1002"), duplicate)
    assertEquals(decode("a2" + "00182c" + "006178"), duplicate)
    assertEquals(decode("a2" + "00182c" + "1b0000000000000000182c"), duplicate)
  }

  test("a value of the wrong type or out of its field's range is refused with a typed error") {
    def refusal(s: String): Option[LedgerDecodeError] = decode(s).swap.toOption
    assertEquals(
      refusal("a104" + "1a00010000"),
      Some(LedgerDecodeError.NumberOutOfRange(DecodeField.MaxBlockHeaderSize, 65536, 0, 65535))
    )
    assertEquals(
      refusal("a102" + "1b0000000100000000"),
      Some(
        LedgerDecodeError.NumberOutOfRange(
          DecodeField.MaxBlockBodySize,
          BigInt(1) << 32,
          0,
          (BigInt(1) << 32) - 1
        )
      )
    )
    assert(refusal("a1006234 34".replace(" ", "")).exists {
      case LedgerDecodeError.Expected(DecodeField.MinFeeA, LedgerDecodeError.Shape.Unsigned, _) =>
        true
      case _ => false
    })
    assert(refusal("a10a01").exists {
      case LedgerDecodeError.ExpectedTag(DecodeField.Rho, _, _) => true
      case _                                                    => false
    })
    assertEquals(
      refusal("a10ad81e820302"),
      Some(LedgerDecodeError.RationalOutOfBounds(DecodeField.Rho, 3, 2))
    )
    assertEquals(
      refusal("a114" + "83010203"),
      Some(LedgerDecodeError.ArrayLength(DecodeField.MaxTxExUnits, 2, 3))
    )
    assertEquals(
      refusal("a115" + "821b800000000000000001"),
      Some(
        LedgerDecodeError.NumberOutOfRange(
          DecodeField.MaxBlockExUnits,
          BigInt(1) << 63,
          0,
          BigInt(Long.MaxValue)
        )
      )
    )
    assertEquals(
      refusal("a118" + "19" + ("84" + "d81e820103" * 4)),
      Some(LedgerDecodeError.ArrayLength(DecodeField.PoolVotingThresholds, 5, 4))
    )
  }

  test("enacting a ratified parameter change applies every field: it cannot fail") {
    val update = TestLedger.conwayUpdate(16 -> 75000000, 30 -> 100000)
    val id = GovActionId(Bytes(Vector.fill(32)(1.toByte)), 0)
    val st = EnactState(
      None,
      ConwayGenesis.noConstitution,
      TestLedger.params(10),
      TestLedger.params(10),
      0,
      Map.empty,
      PrevIds()
    )
    val enacted = Ratify.enact(st, id, GovAction.ParameterChange(None, update, None))
    assertEquals(enacted.params.minPoolCost, BigInt(75000000))
    assertEquals(enacted.params.govActionDeposit, BigInt(100000))
    assertEquals(enacted.prevIds.paramUpdate, Some(id))
  }

  test("the Plutus data of an update is the typed values in key order") {
    import xyz.kd5ujc.scarab.plutus.PlutusData
    import PlutusData.{I, List as PList, Map as PMap}
    val update = decoded("a2" + "09d81e820204" + "00182c")
    assertEquals(
      ScriptContexts.changedParameters(update),
      PMap(
        Vector(
          (I(0): PlutusData) -> I(44),
          (I(9): PlutusData) -> PList(Vector(I(1), I(2)))
        )
      )
    )
  }

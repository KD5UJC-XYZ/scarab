// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  DecodeField,
  DecodeSite,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W16, W32}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraParamUpdate
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayPParams,
  DRepVotingThresholds,
  PoolVotingThresholds
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{
  DijkstraEraPParams,
  DijkstraPParams,
  Milliseconds32,
  SlotInterval
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CompactCoin,
  Conway,
  Dijkstra,
  EpochInterval,
  EraTag,
  HasZero,
  NonZero
}

/** The CBOR of the parameters and of an update on the era types (`EncCBOR`/`DecCBOR (PParams era)`
  * and `(PParamsUpdate era)`, cardano-ledger 9df9f98c `libs/cardano-ledger-core/src/Cardano/
  * Ledger/Core/PParams.hs:204-301`), folded over the descriptions: every update of
  * `fixtures/reference/dijkstra-pparams` the Leios build accepts reads as the flat decoder's typed
  * value and is written as the build writes it back (`serialize'` at version 12), the build's
  * refusals refused with the typed cause of the fault it finds first; the Leios testnet's two
  * updates; refusals below version 12, of the parameters and of the protocol version, in the order
  * the reference's decoders find them at their pins; and both eras' parameters and updates read
  * back as themselves.
  */
class EraPParamsCborSuite extends ScalaCheckSuite:
  private val dijkstra = summon[DijkstraEraPParams[Dijkstra]]
  private val conway = summon[ConwayEraPParams[Conway]]

  private val at12 = DecodeAt(DecoderVersion.Dijkstra, DecodeField.ParameterUpdate)

  private def version(major: Int): DecoderVersion =
    DecoderVersion.ofMajor(BigInt(major)).getOrElse(fail(s"no version $major"))

  private lazy val at11 = DecodeAt(version(11), DecodeField.ParameterUpdate)

  private def hex(text: String): Bytes =
    Bytes.fromHex(text.replace(" ", "")).fold(e => fail(e.toString), identity)

  private def read[A: DecCBOR](bytes: Bytes)(using DecodeAt): Either[LedgerDecodeError, A] =
    Cbor
      .decode(bytes)
      .left
      .map(LedgerDecodeError.Syntax(_))
      .flatMap(DecCBOR[A].decCBOR(_))

  private def readAt[A: DecCBOR](at: DecodeAt, bytes: Bytes): Either[LedgerDecodeError, A] =
    read[A](bytes)(using summon[DecCBOR[A]], at)

  private def write[A: EncCBOR](a: A): Bytes = EncCBOR[A].encCBOR(a).bytes

  private def flat(bytes: Bytes): Either[LedgerDecodeError, DijkstraParamUpdate] =
    Cbor
      .decode(bytes)
      .left
      .map(LedgerDecodeError.Syntax(_))
      .flatMap(DijkstraParamUpdate.decode)

  /** A case of the corpus: its update, and the reference's verdict on it, its refusal or the update
    * it writes back (`serialize'` at version 12).
    */
  private final case class Row(id: String, update: Bytes, verdict: Option[Bytes])

  private val rows: Vector[Row] =
    Files
      .readAllLines(Path.of("fixtures/reference/dijkstra-pparams/cases.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map { line =>
        val c = parse(line).fold(e => fail(e.message), identity).hcursor
        Row(
          c.get[String]("id").fold(e => fail(e.message), identity),
          hex(c.get[String]("update").fold(e => fail(e.message), identity)),
          c.downField("reference")
            .get[Option[String]]("reencoded")
            .fold(e => fail(e.message), _.map(hex))
        )
      }

  private val update = DecodeField.ParameterUpdate

  /** The build's refusals and the typed cause of each, of the fault the build finds first where an
    * update has two (the fixture's `refused` is the build's message): a ratio's items before its
    * length, a cost-model map's entries before a repeated language, a record's fields before its
    * length, its break refused by the field it lacks, and an update's entries in order.
    */
  private val refusals: Map[String, LedgerDecodeError] = Map(
    "refScriptCostStride zero" -> LedgerDecodeError.Zero(update),
    "refScriptCostMultiplier zero" -> LedgerDecodeError.RationalOutOfBounds(
      update,
      LedgerDecodeError.OutOfBounds.Reduced(Rational.zero)
    ),
    "perasHealingFactor zero" -> LedgerDecodeError.RationalOutOfBounds(
      update,
      LedgerDecodeError.OutOfBounds.Reduced(Rational.zero)
    ),
    "an unknown key" -> LedgerDecodeError.UnknownKey(update, BigInt(57)),
    "a ratio of three items, the third null" ->
      LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Unsigned, Value.Null),
    "a ratio of three items, a zero denominator" -> LedgerDecodeError.ArrayLength(update, 2, 3),
    "a ratio of one null item" ->
      LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Unsigned, Value.Null),
    "a ratio of a zero denominator" -> LedgerDecodeError.ZeroDenominator(update),
    "an indefinite ratio of three items, the third null" ->
      LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Unsigned, Value.Null),
    "a ratio of a negative item" ->
      LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Unsigned, Value.NInt(-1)),
    "cost models of a repeated language, its second list bad" ->
      LedgerDecodeError.Expected(DecodeField.Cost, LedgerDecodeError.Shape.Integer, Value.Null),
    "cost models of a repeated language" ->
      LedgerDecodeError.DuplicateElement(DecodeField.CostModels / DecodeSite.Qualifier.Key),
    "a cost-model language past Word8, its list bad" ->
      LedgerDecodeError.NumberOutOfRange(DecodeField.CostModelLanguage, 256, 0, 255),
    "a negative cost-model language" -> LedgerDecodeError.Expected(
      DecodeField.CostModelLanguage,
      LedgerDecodeError.Shape.Unsigned,
      Value.NInt(-1)
    ),
    "a cost-model parameter as a bignum" -> LedgerDecodeError.Expected(
      DecodeField.Cost,
      LedgerDecodeError.Shape.Integer,
      Value.Tag(2, Node(Value.ByteString(hex("01")), Bytes.empty))
    ),
    "a cost-model parameter past Int64" -> LedgerDecodeError.NumberOutOfRange(
      DecodeField.Cost,
      BigInt(Long.MaxValue) + 1,
      BigInt(Long.MinValue),
      BigInt(Long.MaxValue)
    ),
    "indefinite execution units of one item" ->
      LedgerDecodeError.PParamsCodec(PParamsDecodeError.Break(DecodeField.ExUnits, 1)),
    "indefinite execution units of three items" ->
      LedgerDecodeError.ArrayLength(DecodeField.ExUnits, 2, 3),
    "execution units of one item, a key after it" ->
      LedgerDecodeError.ArrayLength(DecodeField.ExUnits, 2, 1),
    "execution units of one item, last" -> LedgerDecodeError.ArrayLength(DecodeField.ExUnits, 2, 1),
    "execution units of three items, the first null" ->
      LedgerDecodeError.Expected(DecodeField.ExUnits, LedgerDecodeError.Shape.Unsigned, Value.Null),
    "execution units of null" ->
      LedgerDecodeError.Expected(DecodeField.ExUnits, LedgerDecodeError.Shape.Array, Value.Null),
    "indefinite prices of one item" ->
      LedgerDecodeError.PParamsCodec(PParamsDecodeError.Break(DecodeField.Prices, 1)),
    "prices of three items, the first null" ->
      LedgerDecodeError.ExpectedTag(DecodeField.PriceMem, BigInt(30), Value.Null),
    "indefinite pool thresholds of four" -> LedgerDecodeError.PParamsCodec(
      PParamsDecodeError.Break(DecodeField.PoolVotingThresholds, 4)
    ),
    "indefinite DRep thresholds of ten, the tenth null" ->
      LedgerDecodeError.ExpectedTag(DecodeField.DRepVotingThresholds, BigInt(30), Value.Null),
    "a repeated key, its second value bad" ->
      LedgerDecodeError.PParamsCodec(PParamsDecodeError.DuplicateKey(update, BigInt(0))),
    "a bad value before an unknown key" ->
      LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Unsigned, Value.Null),
    "an unknown key after a good one" -> LedgerDecodeError.UnknownKey(update, BigInt(57)),
    "a negative word" ->
      LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Unsigned, Value.NInt(-1)),
    "a Word32 past its width" ->
      LedgerDecodeError.NumberOutOfRange(update, BigInt(1) << 32, 0, (BigInt(1) << 32) - 1)
  )

  test(
    "every update of the corpus the Leios build accepts reads as the flat decoder's and is written as the build writes it"
  ):
    given DecodeAt = at12
    assertEquals(rows.size, 167)
    val (refused, accepted) = rows.partition(_.verdict.isEmpty)
    assertEquals(accepted.size, 136)
    accepted.foreach { r =>
      val era = read[PParamsUpdate[Dijkstra]](r.update).fold(e => fail(s"${r.id}: $e"), identity)
      val typed = flat(r.update).flatMap(u =>
        DijkstraPParams.fromUpdate(u).left.map(e => fail(s"${r.id}: $e"))
      )
      assertEquals(Right(era.hkd), typed, r.id)
      assertEquals(Some(write(era)), r.verdict, r.id)
      assertEquals(Right(DijkstraPParams.toUpdate(era.hkd)), flat(r.update), r.id)
    }
    // the others are written otherwise than their input: a list or map past 23 items indefinite,
    // and what the noncanonical cases hold as the build writes it
    assertEquals(accepted.count(r => r.verdict.contains(r.update)), 113)
    assertEquals(refused.map(_.id).toSet, refusals.keySet)
    refused.foreach { r =>
      assertEquals(read[PParamsUpdate[Dijkstra]](r.update).left.toOption, refusals.get(r.id), r.id)
      assert(
        flat(r.update) match {
          case Left(LedgerDecodeError.ArrayLength(_, _, _)) |
              Left(LedgerDecodeError.DuplicateElement(_)) |
              Left(LedgerDecodeError.Expected(_, _, _)) |
              Left(LedgerDecodeError.ExpectedTag(_, _, _)) |
              Left(LedgerDecodeError.NumberOutOfRange(_, _, _, _)) |
              Left(LedgerDecodeError.RationalOutOfBounds(_, _)) |
              Left(LedgerDecodeError.UnknownKey(_, _)) | Left(LedgerDecodeError.Zero(_)) |
              Left(LedgerDecodeError.ZeroDenominator(_)) =>
            true
          case _ => false
        },
        r.id
      )
    }

  test("the Leios testnet's two updates read and write their bytes"):
    given DecodeAt = at12
    def roundTrip(bytes: Bytes): DijkstraPParams[StrictMaybe] =
      val u = read[PParamsUpdate[Dijkstra]](bytes).fold(e => fail(e.toString), identity)
      assertEquals(write(u), bytes)
      u.hkd
    val first = roundTrip(hex("a2182d1a0004b320182e1a001e8480"))
    assertEquals(
      first,
      DijkstraPParams.emptyUpdate.copy(
        maxEndorserBlockReferencesSize = THKD(Unsigned.from[W32](308_000)),
        maxEndorserBlockTxsSize = THKD(Unsigned.from[W32](2_000_000))
      )
    )
    val second = roundTrip(hex("a2182f821a80befc001b0000008bb2c9700018301a00b71b00"))
    assertEquals(
      second.maxEndorserBlockExUnits.unTHKD.map(_.unOrdExUnits.units),
      Some(xyz.kd5ujc.scarab.plutus.ExUnits(BigInt(2_160_000_000L), BigInt(600_000_000_000L)))
    )
    assertEquals(second.maxRefScriptSizePerEndorserBlock.unTHKD, Unsigned.from[W32](12_000_000))

  test("ratios are written reduced, an indefinite map is read, and entries are refused in order"):
    given DecodeAt = at12
    val mixed = read[PParamsUpdate[Dijkstra]](hex("a3 00 182c 09 d81e820204 1825 d81e820604"))
    assertEquals(mixed.map(write), Right(hex("a3 00 182c 09 d81e820102 1825 d81e820302")))
    assertEquals(
      read[PParamsUpdate[Dijkstra]](hex("bf 00 182c ff")).map(write),
      Right(hex("a1 00 182c"))
    )
    // a duplicate key, by the key, before its value is read; a key past 56 after key 34; a bad
    // value of key 10
    assertEquals(
      read[PParamsUpdate[Dijkstra]](hex("a2 00 01 00 f6")),
      Left(
        LedgerDecodeError.PParamsCodec(
          PParamsDecodeError.DuplicateKey(DecodeField.ParameterUpdate, BigInt(0))
        )
      )
    )
    assertEquals(
      read[PParamsUpdate[Dijkstra]](hex("a2 1822 01 1839 f6")),
      Left(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, BigInt(57)))
    )
    assertEquals(
      read[PParamsUpdate[Dijkstra]](hex("a2 1822 01 0a d81e822001")),
      Left(
        LedgerDecodeError
          .Expected(DecodeField.ParameterUpdate, LedgerDecodeError.Shape.Unsigned, Value.NInt(-1))
      )
    )

  test("below version 12 a duplicate key is refused by the key too"):
    given DecodeAt = at11
    assertEquals(
      read[PParamsUpdate[Conway]](hex("a2 1820 01 1820 f6")),
      Left(
        LedgerDecodeError.PParamsCodec(
          PParamsDecodeError.DuplicateKey(DecodeField.ParameterUpdate, BigInt(32))
        )
      )
    )

  test("a record's fields are read before its length, as decodeRecordNamed reads them"):
    // key 20 of three items: from 12 the null memory is refused first (`decodeRecordNamed`)
    val nullMemory = hex("a1 14 83 f6 00 00")
    assertEquals(
      readAt[PParamsUpdate[Dijkstra]](at12, nullMemory),
      Left(
        LedgerDecodeError
          .Expected(DecodeField.ExUnits, LedgerDecodeError.Shape.Unsigned, Value.Null)
      )
    )
    // below 12 it is a `RecD` record, whose length is refused before any field
    assertEquals(
      readAt[PParamsUpdate[Conway]](at11, nullMemory),
      Left(LedgerDecodeError.ArrayLength(DecodeField.ExUnits, 2, 3))
    )
    // the prices of three items: from 12 the null memory price first, below 12 the length
    val nullPrice = hex("a1 13 83 f6 d81e820001 d81e820001")
    assertEquals(
      readAt[PParamsUpdate[Dijkstra]](at12, nullPrice),
      Left(LedgerDecodeError.ExpectedTag(DecodeField.PriceMem, BigInt(30), Value.Null))
    )
    assertEquals(
      readAt[PParamsUpdate[Conway]](at11, nullPrice),
      Left(LedgerDecodeError.ArrayLength(DecodeField.Prices, 2, 3))
    )
    // the protocol version, the thresholds and the parameters are `decodeRecordNamed` at every
    // version
    for v <- Vector(at12, at11) do
      given DecodeAt = v
      assertEquals(
        read[ProtVer](hex("83 f6 00 00")),
        Left(
          LedgerDecodeError.Expected(
            DecodeField.Major,
            LedgerDecodeError.Shape.Unsigned,
            Value.Null
          )
        )
      )
      assertEquals(
        read[ProtVer](hex("81 0a")),
        Left(LedgerDecodeError.ArrayLength(DecodeField.ProtocolVersion, 2, 1))
      )
      assertEquals(
        read[PoolVotingThresholds](hex("86 f6 00 00 00 00 00")),
        Left(
          LedgerDecodeError.ExpectedTag(DecodeField.PoolVotingThresholds, BigInt(30), Value.Null)
        )
      )
      val conwayEmpty = write[PParams[Conway]](conway.emptyPParams)
      // 32 items, the first null: the first parameter's refusal, not the length's
      assertEquals(conwayEmpty.slice(0, 3), hex("98 1f 00"))
      assertEquals(
        read[PParams[Conway]](hex("98 20 f6") ++ conwayEmpty.drop(3) ++ hex("00")),
        Left(
          LedgerDecodeError
            .Expected(DecodeSite.Index(0), LedgerDecodeError.Shape.Unsigned, Value.Null)
        )
      )

  test("an indefinite record that ends before a field is refused by the field it lacks"):
    for v <- Vector(at12, at11) do
      given DecodeAt = v
      assertEquals(
        read[ProtVer](hex("9f 0a ff")),
        Left(
          LedgerDecodeError.PParamsCodec(PParamsDecodeError.Break(DecodeField.ProtocolVersion, 1))
        )
      )
      assertEquals(
        read[DRepVotingThresholds](hex("9f d81e820102 ff")),
        Left(
          LedgerDecodeError.PParamsCodec(
            PParamsDecodeError.Break(DecodeField.DRepVotingThresholds, 1)
          )
        )
      )
      // past its fields, the break's absence: the length
      assertEquals(
        read[ProtVer](hex("9f 0a 00 00 ff")),
        Left(LedgerDecodeError.ArrayLength(DecodeField.ProtocolVersion, 2, 3))
      )
    given DecodeAt = at12
    assertEquals(
      read[PParams[Dijkstra]](hex("9f 00 ff")),
      Left(LedgerDecodeError.PParamsCodec(PParamsDecodeError.Break(update, 1)))
    )

  test(
    "below version 12 a ratio's items are read before its length, as decodeRationalWithTag reads them"
  ):
    given DecodeAt = at11
    // a three-item ratio of key 10 whose third is null: the null, not the length
    assertEquals(
      read[PParamsUpdate[Conway]](hex("a1 0a d81e 83 01 02 f6")),
      Left(LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Integer, Value.Null))
    )
    assertEquals(
      read[PParamsUpdate[Conway]](hex("a1 09 d81e 9f f6 ff")),
      Left(LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Integer, Value.Null))
    )
    // three integers, the second zero: the length, not the denominator
    assertEquals(
      read[PParamsUpdate[Conway]](hex("a1 0a d81e 83 01 00 05")),
      Left(LedgerDecodeError.ArrayLength(update, 2, 3))
    )
    assertEquals(
      read[PParamsUpdate[Conway]](hex("a1 0a d81e 82 01 00")),
      Left(LedgerDecodeError.ZeroDenominator(update))
    )
    // a negative item is an integer below 12, so the length is refused
    assertEquals(
      read[PParamsUpdate[Conway]](hex("a1 0a d81e 81 20")),
      Left(LedgerDecodeError.ArrayLength(update, 2, 1))
    )
    assertEquals(
      read[PositiveInterval](hex("d81e 83 01 02 f6")),
      Left(LedgerDecodeError.Expected(update, LedgerDecodeError.Shape.Integer, Value.Null))
    )
    assertEquals(
      read[PositiveInterval](hex("d81e 81 07")),
      Left(LedgerDecodeError.ArrayLength(update, 2, 1))
    )
    assertEquals(
      read[PositiveInterval](hex("d81e 82 0e 04")).map(_.rational),
      Right(Rational(7, 2))
    )

  test(
    "an indefinite StrictMaybe reads its item before its break, a definite one its length first"
  ):
    given DecodeAt = at12
    val site = DecodeField.ParameterUpdate
    assertEquals(
      read[Option[Unsigned[W32]]](hex("9f f6 00 ff")),
      Left(LedgerDecodeError.Expected(site, LedgerDecodeError.Shape.Unsigned, Value.Null))
    )
    assertEquals(
      read[Option[Unsigned[W32]]](hex("9f 07 00 ff")),
      Left(LedgerDecodeError.AtMostOne(site, 2))
    )
    assertEquals(
      read[Option[Unsigned[W32]]](hex("82 f6 00")),
      Left(LedgerDecodeError.AtMostOne(site, 2))
    )
    assertEquals(read[Option[Unsigned[W32]]](hex("9f 07 ff")), Right(Unsigned.from[W32](7)))
    assertEquals(read[Option[Unsigned[W32]]](hex("9f ff")), Right(None))

  test("below version 12 an update's detached execution units read through DetachedRecords"):
    given DecodeAt = at11
    // `a1 14 9f ff 01 02`: key 20's `RecD` record written as `9f ff` with its fields after it,
    // which the reference accepts; a plain decode refuses the bytes after the empty list
    val raw = hex("a1 14 9f ff 01 02")
    assert(read[PParamsUpdate[Conway]](raw) match {
      case Left(LedgerDecodeError.Syntax(Cbor.Error.TrailingBytes)) => true
      case _                                                        => false
    })
    val detached = DetachedRecords.read(
      raw,
      Cbor.Limits.Unbounded,
      DetachedRecords.Shapes.parameterUpdate(Era.Conway)
    )(LedgerDecodeError.Syntax(_))(DecCBOR[PParamsUpdate[Conway]].decCBOR(_))
    val units = ExUnits
      .from(xyz.kd5ujc.scarab.plutus.ExUnits(BigInt(1), BigInt(2)))
      .getOrElse(fail("no units"))
    assertEquals(
      detached.map(_.hkd),
      Right(ConwayPParams.emptyUpdate.copy(maxTxExUnits = THKD(Some(OrdExUnits(units)))))
    )
    // written back as the definite record the reference's encoder writes
    assertEquals(detached.map(write(_)), Right(hex("a1 14 82 01 02")))

  test(
    "the Peras bootstrap round is null or the round in an update, [] or [round] in the parameters"
  ):
    given DecodeAt = at12
    val none = DijkstraPParams.emptyUpdate.copy(perasBootstrapRound = THKD(Some(None)))
    val some =
      DijkstraPParams.emptyUpdate.copy(perasBootstrapRound = THKD(Some(Unsigned.from[W32](7))))
    assertEquals(write(PParamsUpdate[Dijkstra](none)), hex("a1 1835 f6"))
    assertEquals(write(PParamsUpdate[Dijkstra](some)), hex("a1 1835 07"))
    assertEquals(read[PParamsUpdate[Dijkstra]](hex("a1 1835 f6")).map(_.hkd), Right(none))
    assertEquals(
      read[PParamsUpdate[Dijkstra]](hex("a1 1835 80")),
      Left(
        LedgerDecodeError
          .Expected(
            DecodeField.ParameterUpdate,
            LedgerDecodeError.Shape.Unsigned,
            Value.Arr(Vector())
          )
      )
    )
    val empty = write[PParams[Dijkstra]](dijkstra.emptyPParams)
    assertEquals(empty.slice(empty.size - 8, empty.size - 7), hex("80"))
    val round = PParams[Dijkstra](
      DijkstraPParams.empty.copy(perasBootstrapRound = THKD(Unsigned.from[W32](7)))
    )
    assertEquals(read[PParams[Dijkstra]](write(round)), Right(round))

  test("the parameters are a list of as many as the era's descriptions"):
    given DecodeAt = at12
    assertEquals(
      Cbor.decode(write[PParams[Dijkstra]](dijkstra.emptyPParams)).map(_.enforceSize(54).isRight),
      Right(true)
    )
    assertEquals(
      Cbor.decode(write[PParams[Conway]](conway.emptyPParams)).map(_.enforceSize(31).isRight),
      Right(true)
    )
    assertEquals(
      read[PParams[Conway]](write[PParams[Dijkstra]](dijkstra.emptyPParams)),
      Left(LedgerDecodeError.ArrayLength(DecodeField.ParameterUpdate, 31, 54))
    )

  // ---- generated parameters and updates

  private def word[W <: Unsigned.Width: Unsigned.Bound](max: Long): Gen[Unsigned[W]] =
    Gen
      .oneOf(Gen.const(0L), Gen.choose(0L, max), Gen.const(max))
      .flatMap(n => Unsigned.from[W](BigInt(n)).fold(Gen.fail)(Gen.const))

  private val w16 = word[W16](0xffffL)
  private val w32 = word[W32](0xffffffffL)
  private val compact: Gen[CompactCoin] =
    Gen
      .oneOf(Gen.choose(0L, 1_000_000L), Gen.choose(Long.MinValue, Long.MaxValue))
      .map(CompactCoin.fromUnsigned)
  private val perByte = compact.map(CoinPerByte(_))
  private val epochs = w32.map(EpochInterval.fromWord32)

  private def ratio[A](lo: Long, hi: Long)(bound: Rational => Option[A]): Gen[A] =
    for
      den <- Gen.choose(1L, 1_000L)
      num <- Gen.choose(lo, hi * den)
      r <- bound(Rational(num, den)).fold(Gen.fail)(Gen.const)
    yield r

  private val unit = ratio(0, 1)(UnitInterval.boundRational)
  private val nonNegative = ratio(0, 1_000)(NonNegativeInterval.boundRational)
  private val positive = ratio(1, 1_000)(PositiveInterval.boundRational)

  private val exUnits: Gen[ExUnits] =
    for
      m <- Gen.choose(0L, Long.MaxValue)
      s <- Gen.choose(0L, Long.MaxValue)
      u <- ExUnits.from(xyz.kd5ujc.scarab.plutus.ExUnits(m, s)).fold(Gen.fail)(Gen.const)
    yield u

  /** Up to 30 languages, so a map past 23 entries is written indefinite, each with up to 200
    * parameters, so a list past 23 is too.
    */
  private val costModels: Gen[CostModels] =
    for
      count <- Gen.choose(0, 30)
      tags <- Gen.pick(count, 0 to 255)
      models <- Gen.sequence[Vector[(Int, Vector[BigInt])], (Int, Vector[BigInt])](
        tags.toVector.map(t =>
          Gen
            .choose(0, 200)
            .flatMap(Gen.listOfN(_, Gen.choose(Long.MinValue, Long.MaxValue).map(BigInt(_))))
            .map(ps => t -> ps.toVector)
        )
      )
      c <- CostModels.from(models.toMap).fold(_ => Gen.fail, Gen.const)
    yield c

  private given HasZero[Unsigned[W32]] = HasZero.instance(_.value.signum == 0)

  private val protVer: Gen[ProtVer] =
    for
      major <- Gen
        .choose(0, Version.Max)
        .flatMap(m => Version.mkVersion(m).fold(Gen.fail)(Gen.const))
      minor <- w32
    yield ProtVer(major, minor)

  /** Dijkstra's parameters, each field drawn over its type. */
  private val genDijkstra: Gen[DijkstraPParams[Identity]] =
    for
      a <- Gen.sequence[Vector[CoinPerByte], CoinPerByte](Vector.fill(3)(perByte))
      c <- Gen.sequence[Vector[CompactCoin], CompactCoin](Vector.fill(6)(compact))
      w <- Gen.sequence[Vector[Unsigned[W32]], Unsigned[W32]](Vector.fill(14)(w32))
      h <- Gen.sequence[Vector[Unsigned[W16]], Unsigned[W16]](Vector.fill(8)(w16))
      e <- Gen.sequence[Vector[EpochInterval], EpochInterval](Vector.fill(4)(epochs))
      u <- Gen.sequence[Vector[UnitInterval], UnitInterval](Vector.fill(20)(unit))
      nn <- Gen.sequence[Vector[NonNegativeInterval], NonNegativeInterval](
        Vector.fill(5)(nonNegative)
      )
      p <- Gen.sequence[Vector[PositiveInterval], PositiveInterval](Vector.fill(2)(positive))
      x <- Gen.sequence[Vector[ExUnits], ExUnits](Vector.fill(3)(exUnits))
      v <- protVer
      models <- costModels
      stride <- w32.flatMap(n => NonZero.from(n).fold(_ => Gen.fail, Gen.const))
      leverage <- Gen.option(nonNegative)
      round <- Gen.option(w32)
      coin <- Gen.choose(0L, Long.MaxValue).map(n => Coin(BigInt(n) * 2))
    yield DijkstraPParams[Identity](
      THKD(a(0)),
      THKD(c(0)),
      THKD(w(0)),
      THKD(w(1)),
      THKD(h(0)),
      THKD(c(1)),
      THKD(c(2)),
      THKD(e(0)),
      THKD(h(1)),
      THKD(nn(0)),
      THKD(u(0)),
      THKD(u(1)),
      v,
      THKD(c(3)),
      THKD(a(1)),
      THKD(models),
      THKD(Prices(nn(1), nn(2))),
      THKD(OrdExUnits(x(0))),
      THKD(OrdExUnits(x(1))),
      THKD(w(2)),
      THKD(h(2)),
      THKD(h(3)),
      THKD(PoolVotingThresholds(u(2), u(3), u(4), u(5), u(6))),
      THKD(DRepVotingThresholds(u(7), u(8), u(9), u(10), u(11), u(12), u(13), u(14), u(15), u(16))),
      THKD(h(4)),
      THKD(e(1)),
      THKD(e(2)),
      THKD(c(4)),
      THKD(c(5)),
      THKD(e(3)),
      THKD(nn(3)),
      THKD(w(3)),
      THKD(w(4)),
      THKD(stride),
      THKD(p(0)),
      THKD(MaxPledgeLeverage(leverage)),
      THKD(u(17)),
      THKD(Milliseconds32(w(5))),
      THKD(Milliseconds32(w(6))),
      THKD(Milliseconds32(w(7))),
      THKD(h(5)),
      THKD(u(18)),
      THKD(w(8)),
      THKD(w(9)),
      THKD(OrdExUnits(x(2))),
      THKD(w(10)),
      THKD(SlotInterval(w(11))),
      THKD(p(1)),
      THKD(h(6)),
      THKD(h(7)),
      THKD(round),
      THKD(u(19)),
      THKD(coin),
      THKD(a(2))
    )

  private val genConway: Gen[ConwayPParams[Identity]] = genDijkstra.map(DijkstraPParams.downgrade)

  /** The update that sets the fields of `p` the mask picks, through the descriptions' lenses. */
  private def updateOf[E <: EraTag](era: EraPParams[E], p: PParams[E], mask: Vector[Boolean]) =
    era.eraPParams.zip(mask).foldLeft(era.emptyPParamsUpdate) { case (acc, (d, pick)) =>
      d.ppUpdate.filter(_ => pick).fold(acc)(u => u.ppuLens.replace(Some(d.ppLens.get(p)))(acc))
    }

  private def masks(size: Int): Gen[Vector[Boolean]] =
    Gen.oneOf(
      Gen.const(Vector.fill(size)(true)),
      Gen.listOfN(size, Gen.oneOf(true, false)).map(_.toVector)
    )

  private val conwayVersions = Gen.oneOf(9, 10, 11).map(version)

  property("Dijkstra's parameters read back as themselves at version 12"):
    given DecodeAt = at12
    forAll(genDijkstra) { r =>
      val p = PParams[Dijkstra](r)
      assertEquals(read[PParams[Dijkstra]](write(p)), Right(p))
    }

  property("Conway's parameters read back as themselves at versions 9 to 11"):
    forAll(genConway, conwayVersions) { (r, v) =>
      given DecodeAt = DecodeAt(v, DecodeField.ParameterUpdate)
      val p = PParams[Conway](r)
      assertEquals(read[PParams[Conway]](write(p)), Right(p))
    }

  property("Dijkstra's updates read back as themselves, and as the flat decoder's"):
    given DecodeAt = at12
    forAll(genDijkstra, masks(54)) { (r, mask) =>
      val u = updateOf(dijkstra, PParams[Dijkstra](r), mask)
      val bytes = write(u)
      assertEquals(read[PParamsUpdate[Dijkstra]](bytes), Right(u))
      assertEquals(flat(bytes), Right(DijkstraPParams.toUpdate(u.hkd)))
      assertEquals(DijkstraPParams.fromUpdate(DijkstraPParams.toUpdate(u.hkd)), Right(u.hkd))
    }

  property("Conway's updates read back as themselves at versions 9 to 11"):
    forAll(genConway, masks(31), conwayVersions) { (r, mask, v) =>
      given DecodeAt = DecodeAt(v, DecodeField.ParameterUpdate)
      val u = updateOf(conway, PParams[Conway](r), mask)
      assertEquals(read[PParamsUpdate[Conway]](write(u)), Right(u))
    }

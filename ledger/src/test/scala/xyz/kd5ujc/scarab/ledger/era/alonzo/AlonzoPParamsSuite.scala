// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  Nonce,
  ParamUpdate,
  ProtocolParams,
  TestLedger,
  TxBody,
  TxChecks,
  TxOut as FlatTxOut,
  Unsigned,
  Update as StoredUpdate,
  Value as FlatValue
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.babbage.{BabbagePParams, DowngradeBabbagePParams}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageCbor.*
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Coin, CompactCoin, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era as ProtocolEra

/** Alonzo's protocol parameters as higher-kinded data, against what the node keeps and applies now:
  * the parameters projected from the stored record and back, an update applied as the flat rule
  * applies it, the descriptions and the ranged classes, the update proposal of an Alonzo body read
  * the typed way against the flat decoder (generated, at the edges of both readers, and the
  * recorded ones), Babbage's upgrade from Alonzo and downgrade to it, and the least coin of an
  * output through the cost per UTxO word.
  */
class AlonzoPParamsSuite extends ScalaCheckSuite:
  private val era = summon[AlonzoEraPParamsExactAlonzo[Alonzo]]
  private val version = DecoderVersion.of(ProtocolEra.Alonzo)
  private given DecodeAt = DecodeAt(version, DecodeField.ParameterUpdate)

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def hex(s: String): Bytes = right("hex")(Bytes.fromHex(s))

  private def alonzo(p: ProtocolParams): AlonzoPParams[Identity] =
    right("Alonzo's parameters")(AlonzoPParams.fromStored(p))

  // ---- Alonzo's update values: Babbage's generators, with the decentralisation, the extra
  // entropy and a version Alonzo's update takes (a major up to 7)

  private val alonzoKeys = ParamUpdate.Keys.Alonzo.keys.toVector.sorted

  private def genAlonzoValue(key: Int): Gen[String] = key match
    case 12 =>
      for d <- Gen.choose(1L, 1000L); n <- Gen.choose(0L, d); w <- genWide yield ratio(n, d, w)
    case 13 =>
      Gen.oneOf(Gen.const("8100"), Gen.choose(0, 255).map(b => "82015820" + f"$b%02x" * 32))
    case 14 =>
      for major <- Gen.choose(0, 7); minor <- Gen.choose(0, 3); w <- genWide
      yield array(Seq(uint(major, w), uint(minor, w)))
    case _ => genValue(key)

  private def genAlonzoFields(keys: Vector[Int]): Gen[Vector[(Int, String)]] =
    for
      chosen <- Gen.someOf(keys)
      ordered <- Gen.pick(chosen.size, chosen).map(_.toVector)
      values <- Gen.sequence[Vector[String], String](ordered.map(genAlonzoValue))
    yield ordered.zip(values)

  private val genAlonzoUpdate: Gen[(String, Vector[Vector[(Int, String)]])] =
    for
      n <- Gen.choose(1, 3)
      updates <- Gen.listOfN(n, genAlonzoFields(alonzoKeys)).map(_.toVector)
      epoch <- Gen.choose(0L, 1000L)
    yield (
      array(
        Seq(
          map(
            updates.zipWithIndex.map((u, i) => genesisKey(i) -> map(u.map((k, v) => uint(k) -> v)))
          ),
          uint(epoch)
        )
      ),
      updates
    )

  private def updateOf(fields: Vector[(Int, String)]): PParamsUpdate[Alonzo] =
    right("update")(
      CborRead
        .decode(hex(map(fields.map((k, v) => uint(k) -> v))))
        .flatMap(PParamsUpdate.decodePParamsUpdate[Alonzo](_))
    )

  // ---- the parameters

  test("the stored parameters come back as they were"):
    val p = TestLedger.params(6).copy(d = xyz.kd5ujc.scarab.codec.Rational(1, 2))
    assertEquals(AlonzoPParams.toStored(alonzo(p), p), p)
    assertEquals(era.ppDG.get(PParams[Alonzo](alonzo(p))).rational, p.d)

  property("an update applied is the flat rule's, but for the cost models"):
    forAll(genAlonzoFields(alonzoKeys.filterNot(_ == 18))) { fields =>
      val p = TestLedger.params(6)
      val applied = era.applyPPUpdates(PParams[Alonzo](alonzo(p)), updateOf(fields))
      val flat = ParamUpdate(fields.map((k, v) => k -> hex(v)).toMap)
      val expected = ProtocolParams.update(p, flat)(using ShelleyBasedEra.SAlonzo)
      assertEquals(Right(AlonzoPParams.toStored(applied.hkd, p)), expected)
    }

  test("an update's cost models replace the parameters' whole, as the reference's generic apply"):
    val pp = alonzo(TestLedger.params(6))
    val v1 = right("models")(CostModels.from(Map(0 -> Vector(BigInt(9)))))
    val ppu = PParamsUpdate[Alonzo](AlonzoPParams.emptyUpdate.copy(costModels = Some(v1)))
    assertEquals(era.applyPPUpdates(PParams[Alonzo](pp), ppu).hkd.costModels, v1)

  property("an update is its CBOR's, read again"):
    forAll(genAlonzoFields(alonzoKeys)) { fields =>
      val ppu = updateOf(fields)
      val bytes = PParamsUpdate.encodePParamsUpdate(ppu).bytes
      assertEquals(
        PParamsUpdate.decodePParamsUpdate[Alonzo](right("cbor")(CborRead.decode(bytes))),
        Right(ppu)
      )
    }

  test(
    "the record is its CBOR's, and an update of the Alonzo-only fields is written as the reference writes it"
  ):
    val pp = PParams[Alonzo](
      alonzo(
        TestLedger.params(6).copy(costModels = Map.empty, extraEntropy = Nonce.Hash(hex("ab" * 32)))
      )
    )
    assertEquals(
      PParams.decodePParams[Alonzo](
        right("cbor")(CborRead.decode(PParams.encodePParams(pp).bytes))
      ),
      Right(pp)
    )
    val ppu = PParamsUpdate[Alonzo](
      AlonzoPParams.emptyUpdate.copy(
        d = UnitInterval.boundRational(xyz.kd5ujc.scarab.codec.Rational(1, 2)),
        extraEntropy = Some(Nonce.Neutral),
        coinsPerUTxOWord = Some(CoinPerWord(Coin(34482)))
      )
    )
    // the tags in the order of the descriptions: 12, 13, 17
    assertEquals(
      PParamsUpdate.encodePParamsUpdate(ppu).bytes.hex,
      "a3" + "0c" + "d81e820102" + "0d" + "8100" + "11" + "1986b2"
    )

  test("the descriptions are the reference's names and tags, in its order"):
    assertEquals(
      era.eraPParams.map(d => (d.ppName, d.ppUpdate.map(_.ppuTag.value.toInt))),
      Vector(
        "txFeePerByte" -> 0,
        "txFeeFixed" -> 1,
        "maxBlockBodySize" -> 2,
        "maxTxSize" -> 3,
        "maxBlockHeaderSize" -> 4,
        "stakeAddressDeposit" -> 5,
        "stakePoolDeposit" -> 6,
        "poolRetireMaxEpoch" -> 7,
        "stakePoolTargetNum" -> 8,
        "poolPledgeInfluence" -> 9,
        "monetaryExpansion" -> 10,
        "treasuryCut" -> 11,
        "decentralization" -> 12,
        "extraPraosEntropy" -> 13,
        "protocolVersion" -> 14,
        "minPoolCost" -> 16,
        "utxoCostPerByte" -> 17,
        "costModels" -> 18,
        "executionUnitPrices" -> 19,
        "maxTxExecutionUnits" -> 20,
        "maxBlockExecutionUnits" -> 21,
        "maxValueSize" -> 22,
        "collateralPercentage" -> 23,
        "maxCollateralInputs" -> 24
      ).map((n, t) => n -> Some(t))
    )
    // the update keys the flat decoder takes at Alonzo
    assertEquals(era.eraPParams.flatMap(_.ppUpdate.map(_.ppuTag.value.toInt)), alonzoKeys)

  test("the empty parameters are the reference's, at Alonzo's lowest version"):
    assertEquals(
      era.ppProtocolVersionL.get(era.emptyPParams),
      ProtVer(Version.lit(5), Unsigned.lit[W32](0))
    )
    assertEquals(era.ppDG.get(era.emptyPParams), UnitInterval.zero)
    assertEquals(
      EraPParamsToAlonzo.ppExtraEntropyL[Alonzo].get(era.emptyPParams),
      Nonce.Neutral
    )
    assertEquals(
      AlonzoEraPParamsExactAlonzo.ppCoinsPerUTxOWordL[Alonzo].get(era.emptyPParams),
      CoinPerWord(Coin(0))
    )

  test("the ranged classes have exactly their eras"):
    assertEquals(compileErrors("summon[AlonzoEraPParamsExactAlonzo[Alonzo]]"), "")
    assertEquals(compileErrors("summon[EraPParamsToAlonzo[Alonzo]]"), "")
    assertEquals(compileErrors("summon[EraPParamsToBabbage[Alonzo]]"), "")
    assert(
      compileErrors(
        "summon[AlonzoEraPParamsExactAlonzo[xyz.kd5ujc.scarab.ledger.state.Babbage]]"
      ).nonEmpty
    )
    assert(
      compileErrors("summon[EraPParamsToAlonzo[xyz.kd5ujc.scarab.ledger.state.Babbage]]").nonEmpty
    )
    assert(compileErrors("summon[BabbageEraPParams[Alonzo]]").nonEmpty)

  test("no value of an Alonzo-only parameter, and no Alonzo update, can be shown as Plutus data"):
    // the reference's instances of the two are `error "unsupported"`, reached by nothing: the one
    // reader asks for `ConwayEraPParams`
    assert(compileErrors("summon[ToPlutusData[CoinPerWord]]").nonEmpty)
    assert(compileErrors("summon[ToPlutusData[xyz.kd5ujc.scarab.ledger.Nonce]]").nonEmpty)
    assert(compileErrors("summon[ToPlutusData[PParamsUpdate[Alonzo]]]").nonEmpty)
    assert(compileErrors("summon[ConwayEraPParams[Alonzo]]").nonEmpty)

  // ---- the update proposal of an Alonzo body

  /** An Alonzo body with no input, no output, no fee and the update `update`. */
  private def body(update: String): Bytes = hex("a4" + "0080" + "0180" + "0200" + "06" + update)

  private def flat(update: String): Either[LedgerDecodeError, StoredUpdate] =
    TxBody.decode(body(update), ProtocolEra.Alonzo).map(_.update.getOrElse(fail("no update")))

  private def typed(update: String): Either[LedgerDecodeError, KeptUpdate[Alonzo]] =
    DetachedRecords.read(
      body(update),
      Cbor.Limits.Unbounded,
      DetachedRecords.Shapes.body(ProtocolEra.Alonzo, version)
    )(LedgerDecodeError.Syntax(_))(root =>
      root.value match
        case Value.Map(kvs) =>
          kvs
            .collectFirst { case (k, v) if k.value == Value.UInt(6) => v }
            .toRight(
              LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Update)
            )
            .flatMap(KeptUpdate.decode[Alonzo](_))
        case other => fail(s"not a body: $other")
    )

  property(
    "a body's update read the typed way is stored as the flat decoder stores it, byte for byte"
  ):
    forAll(genAlonzoUpdate) { (update, fields) =>
      val stored = right("flat")(flat(update))
      val kept = right("typed")(typed(update))
      assertEquals(kept.stored, stored)
      assertEquals(
        stored.proposals.toVector.sortBy(_._1.hex).map(_._2.fields),
        fields.map(_.map((k, v) => k -> hex(v)).toMap)
      )
      val back = right("from stored")(KeptUpdate.fromStored[Alonzo](stored))
      assertEquals(back.update, kept.update)
      assertEquals(back.stored, stored)
    }

  test(
    "a non-canonical value is stored as written, and is the same typed value as the canonical one"
  ):
    def one(fields: (String, String)*): String =
      array(Seq(map(Seq(genesisKey(1) -> map(fields))), uint(7)))
    Vector(
      (17, uint(34482), uint(34482, wide = true)),
      (12, ratio(1, 2, false), ratio(2, 4, false)),
      (12, ratio(1, 2, false), ratio(1, 2, true)),
      (13, "8100", "9f00ff")
    ).foreach { (key, short, other) =>
      val (s, w) =
        (
          right("short")(typed(one(uint(key) -> short))),
          right("other")(typed(one(uint(key) -> other)))
        )
      assertEquals(s.update, w.update, other)
      assertNotEquals(s.stored, w.stored, other)
      assertEquals(w.stored, right("flat")(flat(one(uint(key) -> other))), other)
      assertEquals(w.stored.proposals.values.headOption.map(_.fields(key)), Some(hex(other)))
    }

  test("what the flat decoder refuses at Alonzo the typed decoder refuses, with the same refusal"):
    def one(fields: (String, String)*): String =
      array(Seq(map(Seq(genesisKey(2) -> map(fields))), uint(3)))
    Vector(
      "the minimum UTxO value" -> one(uint(15) -> uint(1)),
      "a major above Alonzo's" -> one(uint(14) -> array(Seq(uint(8), uint(0)))),
      "a neutral nonce of two" -> one(uint(13) -> "820000"),
      "a nonce hash of 31 bytes" -> one(uint(13) -> ("8201581f" + "00" * 31)),
      "a nonce of tag 2" -> one(uint(13) -> "8102")
    ).foreach { (what, u) =>
      assert(flat(u).isLeft, s"$what: flat ${flat(u)}")
      assertEquals(typed(u).map(_.stored), flat(u), what)
    }
    // Known diagnostic gap (#85), as at Babbage: the shared decoder of an update names the update
    // as the site of a ratio out of its interval, the flat one the parameter
    val above = one(uint(12) -> ratio(3, 2, false))
    val reduced = LedgerDecodeError.OutOfBounds.Reduced(xyz.kd5ujc.scarab.codec.Rational(3, 2))
    assertEquals(flat(above), Left(LedgerDecodeError.RationalOutOfBounds(DecodeField.D, reduced)))
    assertEquals(
      typed(above).map(_.stored),
      Left(LedgerDecodeError.RationalOutOfBounds(DecodeField.ParameterUpdate, reduced))
    )
    // and a cost per word that is not a `Word64`
    val bignum = one(uint(17) -> ("c249" + "01" + "00" * 8))
    def site(r: Either[LedgerDecodeError, StoredUpdate]) = r match
      case Left(LedgerDecodeError.Expected(at, LedgerDecodeError.Shape.Unsigned, _)) => Some(at)
      case _                                                                         => None
    assertEquals(site(flat(bignum)), Some(DecodeField.CoinsPerUTxO))
    assertEquals(site(typed(bignum).map(_.stored)), Some(DecodeField.ParameterUpdate))
    // Alonzo's update reads a major up to 7, `succVersion (ProtVerHigh AlonzoEra)`
    assertEquals(
      typed(one(uint(14) -> array(Seq(uint(7), uint(0))))).map(_.update.proposals.updates.size),
      Right(1)
    )

  test("the recorded Alonzo update proposals read the typed way as the flat decoder stores them"):
    val files = Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/chain-dep/blocks.hex",
      "fixtures/preview/alonzo-first-20.hex",
      "fixtures/preview/alonzo-txs.hex"
    )
    val compared = EraCorpus
      .shelleyBlocks(files)
      .filter(_.era == ProtocolEra.Alonzo)
      .flatMap(_.transactions)
      .flatMap { t =>
        val flatTx =
          right(s"${t.id.hex} flat")(xyz.kd5ujc.scarab.ledger.Tx.fromBlock(t, ProtocolEra.Alonzo))
        val key6 = right("body")(CborRead.decode(t.body)).value match
          case Value.Map(kvs) => kvs.collectFirst { case (k, v) if k.value == Value.UInt(6) => v }
          case other          => fail(s"not a body: $other")
        key6.map { n =>
          val kept = right(s"${t.id.hex} typed")(KeptUpdate.decode[Alonzo](n))
          assertEquals(Some(kept.stored), flatTx.body.update, t.id.hex)
          assertEquals(KeptUpdate.fromStored[Alonzo](kept.stored).map(_.update), Right(kept.update))
          // a mutant of the proposal's first value, one bit of its last byte: read alike, and
          // a different proposal where it is read
          val (raw, fields) = kept.stored.proposals.head
          val (key, value) = fields.fields.toVector.minBy(_._1)
          val at = n.original.hex.indexOf(value.hex) / 2
          assert(at > 0, t.id.hex)
          val mutant = Bytes.fromArray(
            n.original.toArray
              .updated(at + value.size - 1, (n.original.toArray(at + value.size - 1) ^ 0x01).toByte)
          )
          val m = right("mutant")(CborRead.decode(mutant))
          val mutantBody = TxBody.decode(
            hex(t.body.hex.replace(n.original.hex, mutant.hex)),
            ProtocolEra.Alonzo
          )
          (KeptUpdate.decode[Alonzo](m), mutantBody) match
            case (Right(k), Right(b)) =>
              assertEquals(b.update, Some(k.stored), t.id.hex)
              assertNotEquals(k.stored, kept.stored, t.id.hex)
              assertNotEquals(k.update, kept.update, t.id.hex)
            case (Left(_), Left(_)) => ()
            case other              => fail(s"${t.id.hex} mutant of ${raw.hex}/$key: $other")
          (t.id.hex, kept.update.proposals.updates.size)
        }
      }
    // the era walk's and the chain-dependent capture's proposals by their three delegates, and
    // Preview's by its seven
    assertEquals(compared.map(_._2), Vector(3, 3, 3, 3, 7, 7))

  // ---- Babbage's upgrade from Alonzo and downgrade to it

  test("Babbage's upgrade keeps Alonzo's fields and divides the cost per word by 8, rounding down"):
    val a = alonzo(TestLedger.params(6))
    val b = BabbagePParams.upgrade(a)
    assertEquals(b.coinsPerUTxOByte.unCoinPerByte.toBigInt, BigInt(34482 / 8))
    assertEquals(b.costModels, a.costModels)
    assertEquals(b.protocolVersion, a.protocolVersion)
    // the flat translation into Babbage (`Translate`) does the same over the stored record
    assertEquals(
      BabbagePParams.toStored(b, TestLedger.params(6)).coinsPerUTxOByte,
      TestLedger.params(6).coinsPerUTxOWord / 8
    )
    val back = BabbagePParams.downgrade(DowngradeBabbagePParams[Identity](a.d, a.extraEntropy), b)
    assertEquals(back, a.copy(coinsPerUTxOWord = CoinPerWord(Coin(34482 / 8 * 8))))

  test("in a transaction the cost per word is taken as the cost per byte, refused outside Word64"):
    val u = AlonzoPParams.emptyUpdate.copy(coinsPerUTxOWord = Some(CoinPerWord(Coin(34482))))
    assertEquals(
      BabbagePParams.upgradeInTx(u).map(_.coinsPerUTxOByte.map(_.unCoinPerByte.toBigInt)),
      Right(Some(BigInt(34482)))
    )
    assertEquals(
      BabbagePParams.upgrade(u).coinsPerUTxOByte.map(_.unCoinPerByte.toBigInt),
      Some(BigInt(4310))
    )
    val big =
      AlonzoPParams.emptyUpdate.copy(coinsPerUTxOWord = Some(CoinPerWord(Coin(BigInt(1) << 64))))
    assertEquals(
      BabbagePParams.upgradeInTx(big).left.toOption,
      CompactCoin.fromBigInt(BigInt(1) << 64).left.toOption
    )
    assert(BabbagePParams.upgradeInTx(big).isLeft)
    // the reference's `fromIntegral` keeps the low 64 bits of the eighth
    assertEquals(
      BabbagePParams.upgrade(big).coinsPerUTxOByte.map(_.unCoinPerByte.toBigInt),
      Some(BigInt(1) << 61)
    )
    val wraps = AlonzoPParams.emptyUpdate.copy(coinsPerUTxOWord =
      Some(CoinPerWord(Coin((BigInt(1) << 67) + 8)))
    )
    assertEquals(
      BabbagePParams.upgrade(wraps).coinsPerUTxOByte.map(_.unCoinPerByte.toBigInt),
      Some(BigInt(1))
    )
    val negative = AlonzoPParams.emptyUpdate.copy(coinsPerUTxOWord = Some(CoinPerWord(Coin(-9))))
    assertEquals(
      BabbagePParams.upgrade(negative).coinsPerUTxOByte.map(_.unCoinPerByte.toBigInt),
      Some((BigInt(1) << 64) - 2)
    )

  property(
    "the upgrade of an update and its downgrade give it back, but the cost per word's remainder"
  ):
    forAll(genAlonzoFields(alonzoKeys)) { fields =>
      val a = updateOf(fields).hkd
      val back = BabbagePParams.downgrade(
        DowngradeBabbagePParams[StrictMaybe](a.d, a.extraEntropy),
        BabbagePParams.upgrade(a)
      )
      assertEquals(
        back,
        a.copy(coinsPerUTxOWord =
          a.coinsPerUTxOWord.map(w => CoinPerWord(Coin(w.unCoinPerWord.value / 8 * 8)))
        )
      )
    }

  // ---- the least coin of an output

  private given ByteStrings = ByteStrings.of(version)
  private val mode = Address.Mode.at(version)
  private val values = FlatValue.Decoding.of(ProtocolEra.Alonzo, version)

  private def output(h: String): (AlonzoTxOut, FlatTxOut) =
    val n = right("cbor")(CborRead.decode(hex(h)))
    (
      right("era")(AlonzoTxOut.read(n, mode, values)),
      right("flat")(
        FlatTxOut.decode(
          n,
          mode,
          FlatTxOut.Forms.PairOrDatumHash,
          xyz.kd5ujc.scarab.ledger.ScriptKind.PlutusV1,
          values
        )
      )
    )

  test("the least coin is the cost per word times the entry size, as the flat path's"):
    val p = TestLedger.params(6)
    val pp = PParams[Alonzo](alonzo(p))
    val address = s"581d60${"11" * 28}"
    val dh = s"5820${"33" * 32}"
    val p1 = "01" * 28
    val p2 = "02" * 28
    val long = "5818" + "61" * 24
    // an ada-only output (2 words of value), with a datum hash (10 more); one policy and one name
    // of one byte (12 + 28 + 1 = 41 bytes, 6 words, and 6 of overhead); two policies, three
    // triples and the names "", 02 and 24 bytes (36 + 56 + 25 = 117 bytes, 15 words, 6 more); and
    // the values the decoder prunes below version 9, a zero quantity and an empty policy, which
    // are ada-only
    val cases = Vector(
      s"82${address}1a000f4240" -> 29,
      s"83${address}1a000f4240$dh" -> 39,
      s"82${address}821a000f4240a1581c${p1}a1410101" -> 39,
      s"83${address}821a000f4240a1581c${p1}a1410101$dh" -> 49,
      s"83${address}821a000f4240a2581c${p1}a1400a581c${p2}a2410202${long}03$dh" -> 58,
      s"82${address}821a000f4240a1581c${p1}a1410100" -> 29,
      s"82${address}821a000f4240a1581c${p1}a0" -> 29
    )
    cases.foreach { (h, words) =>
      val (out, flat) = output(h)
      assertEquals(AlonzoTxOuts.utxoEntrySize[Alonzo](out), BigInt(words), h)
      val least = AlonzoTxOuts.getMinCoinTxOut[Alonzo](pp, out)
      assertEquals(least, Coin(BigInt(words) * 34482), h)
      assertEquals(least.value, TxChecks.minUTxO[Alonzo](p, flat)(using ShelleyBasedEra.SAlonzo), h)
    }
    assertEquals(AlonzoTxOuts.dataHashSize(None), BigInt(0))

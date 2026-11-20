// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.protocol.StateLayout
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  PlutusCase,
  Phase2,
  PredicateFailure,
  ScriptContexts,
  TestLedger,
  TranslationError,
  Tx as FlatTx,
  TxIn,
  TxOut as FlatTxOut,
  Unsigned,
  testByteStrings
}
import xyz.kd5ujc.scarab.ledger.rules.Test as RuleTest
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayContextError,
  ConwayPParams,
  ConwayPlutusPurpose,
  ConwayPlutusScript,
  ConwayTx,
  ConwayTxInfo,
  ConwayTxOut
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}
import cats.Id
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  EvalFailure,
  PlutusData,
  PlutusLanguage,
  ScriptDecodeFailure,
  ScriptDecoder
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** Conway's Plutus V3 script context on the era types (`EraPlutusTxInfo 'PlutusV3 ConwayEra`,
  * `era/conway/ConwayTxInfo.scala`) against the flat translation (`ScriptContexts`) and,
  * decisively, the reference's own contexts byte for byte: every row of the recorded contexts
  * (`fixtures/reference/recorded-contexts`, the recorded Preview and preprod transactions and the
  * several-voter probes), of the context matrix (`fixtures/reference/context-matrix`), of the
  * context errors (`fixtures/reference/context-errors`) and of the script results
  * (`fixtures/reference/script-results`) that is Conway's, each the pinned Haskell collection
  * (`collectPlutusScriptsWithContext`, cardano-ledger f649f975); and the recorded Conway blocks.
  *
  * For each row the typed V3 `TxInfo` of the transaction equals the flat one, or both refuse with
  * the same failure, whatever the languages its scripts use; and the context of each Plutus V3
  * request the reference collected, built from the redeemer's pointer (`redeemerPointerInverse`),
  * the spending datum (`getSpendingDatum`) and `toPlutusArgs`, serialises to the reference's bytes.
  * The same holds of Plutus V1 and V2: their `TxInfo` s, through the era's memoised result
  * (`EraPlutusContext`), and their arguments, argument for argument.
  */
class EraPlutusContextSuite extends munit.FunSuite:
  private val RecordedBlockCount = 62
  private val t = EraTx.conway
  private given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
  private val u = EraUTxO.conway
  private val v3 = EraPlutusTxInfo.conwayPlutusV3

  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: ${e.getMessage}"), identity)

  private def hex(text: String): Bytes = Bytes.fromHex(text).fold(e => fail(e.toString), identity)

  private def cbor(bytes: Bytes): Node = Cbor.decode(bytes).fold(e => fail(e.toString), identity)

  private def rows(path: String): Vector[Json] =
    parse(Files.readString(Path.of(path)))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail(s"$path is not an array"))
      .filter(field[String](_, "era") == "Conway")

  /** The rows of a corpus whose transactions are all Conway's and which names no era. */
  private def conwayRows(path: String): Vector[Json] =
    parse(Files.readString(Path.of(path)))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail(s"$path is not an array"))

  /** One Conway case: the flat transaction and its parts as they arrived, what it spends and
    * references, its version and its clock.
    */
  private final case class Case(
      name: String,
      parts: Block.Transaction,
      flat: FlatTx,
      spent: Map[TxIn, FlatTxOut],
      references: Map[TxIn, FlatTxOut],
      major: Int,
      costModels: Map[Int, Vector[BigInt]],
      posix: BigInt => Either[TranslationError, BigInt],
      oracle: Json
  )

  private def recorded(row: Json): Case =
    val c = PlutusCase.load(Path.of(field[String](field[Json](row, "provenance"), "sourcePath")))
    val w = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    Case(
      field[String](row, "name"),
      Block.Transaction(c.tx.body.raw, w, c.tx.auxiliary, c.tx.isValid),
      c.tx,
      c.spent,
      c.references,
      c.protocolMajor,
      c.costModels,
      c.posix,
      field[Json](row, "oracle")
    )

  private def derived(row: Json, group: String): Case =
    val parts = cbor(hex(field[String](row, "transaction"))).value match
      case CborValue.Arr(Vector(body, witnesses, flag, auxiliary)) =>
        val valid = flag.value match
          case CborValue.Bool(value) => value
          case other                 => fail(s"not a validity flag: $other")
        Block.Transaction(
          body.original,
          witnesses.original,
          Option.unless(auxiliary.value == CborValue.Null)(auxiliary.original),
          valid
        )
      case other => fail(s"not a complete transaction: $other")
    val flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(e.toString), identity)
    val known = field[Vector[Json]](row, "utxo").map { entry =>
      TxIn.decode(cbor(hex(field[String](entry, "input")))).fold(e => fail(e.toString), identity) ->
        FlatTxOut
          .fromBytes(hex(field[String](entry, "output")))
          .fold(e => fail(e.toString), identity)
    }.toMap
    // a row with no system start and slot length was measured on the clock its own suites read,
    // slot `s` at `100 s` milliseconds (`ContextReferenceCases`)
    val start = row.hcursor.get[Int]("systemStartSeconds").fold(_ => BigInt(0), BigInt(_) * 1000)
    val length = row.hcursor.get[Int]("slotLengthMilliseconds").fold(_ => BigInt(100), BigInt(_))
    Case(
      group + field[String](row, "name"),
      parts,
      flat,
      known.filter((in, _) => flat.body.inputs.contains(in)),
      known.filter((in, _) => flat.body.referenceInputs.contains(in)),
      field[Int](row, "protocolMajor"),
      field[Map[String, Vector[String]]](row, "costModels")
        .map((language, values) => language.toInt -> values.map(BigInt(_))),
      slot => Right(start + slot * length),
      field[Json](row, "oracle")
    )

  private def load(group: String)(row: Json): Case =
    field[String](field[Json](row, "provenance"), "kind") match
      case "recorded-chain-transaction" => recorded(row)
      // a matrix row retains its transaction and outputs whole, as a derived one does
      case "api-only-derived-transaction" | "retained-original-reference-transaction" |
          "original-signed-admitted-transaction" =>
        derived(row, group)
      case other => fail(s"unknown case kind $other")

  private lazy val cases: Vector[Case] =
    rows("fixtures/reference/recorded-contexts/contexts.json").map(load("")) ++
      rows("fixtures/reference/context-matrix/matrix.json").map(load("matrix/")) ++
      conwayRows("fixtures/reference/context-errors/collection.json").map(load("errors/")) ++
      conwayRows("fixtures/reference/script-results/aggregation.json").map(load("results/"))

  private def protVer(major: Int): ProtVer =
    ProtVer(
      Version.mkVersion(major).getOrElse(fail(s"version $major")),
      Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
    )

  /** The typed transaction, UTxO set and translation input of a case; a case the era types do not
    * read fails, never skips (CNF-2).
    */
  private def typed(
      c: Case,
      major: Int = -1
  ): (ConwayTx, UTxO[Conway], LedgerTxInfo[TxLevel.Top, Conway]) =
    val tx =
      ConwayTx.decode(c.parts, StateLayout.Node11_1).fold(e => fail(s"${c.name}: $e"), identity)
    val utxo = UTxO[Conway]((c.spent ++ c.references).map { (in, out) =>
      in -> ConwayTxOut.fromStored(out).fold(e => fail(s"${c.name}: $e"), identity)
    })
    val pv = protVer(if major < 0 then c.major else major)
    (
      tx,
      utxo,
      LedgerTxInfo(pv, c.posix, utxo, tx, Nil, LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty))
    )

  private def flatEnv(c: Case, major: Int): ScriptContexts.Environment =
    ScriptContexts.Environment(ShelleyBasedEra.SConway, major, c.spent, c.references, c.posix)

  /** The flat failure a typed one corresponds to, case by case. */
  private def asFlat(e: ConwayContextError[Conway]): TranslationError = e match
    case ConwayContextError.BabbageContextError(b) =>
      b match
        case BabbageContextError.AlonzoContextError(a) =>
          a match
            case AlonzoContextError.TranslationLogicMissingInput(in) =>
              TranslationError.UnresolvedInput(in)
            case AlonzoContextError.TimeTranslationPastHorizon(cause) => cause
            case AlonzoContextError.Undecodable(site, _) => TranslationError.Undecodable(site)
            case AlonzoContextError.CertificateNotSupported(_) |
                AlonzoContextError.PlutusPurposeNotSupported(_) =>
              fail(s"Conway's injection keeps $a its own")
        case BabbageContextError.ByronTxOutInContext(source) =>
          TranslationError.ByronTxOutInContext(source)
        case BabbageContextError.RedeemerPointerPointsToNothing(ptr) =>
          TranslationError.RedeemerPointerPointsToNothing(ptr.tag, ptr.ix.value)
        case BabbageContextError.InlineDatumsNotSupported(source) =>
          TranslationError.InlineDatumsNotSupported(source)
        case BabbageContextError.ReferenceScriptsNotSupported(source) =>
          TranslationError.ReferenceScriptsNotSupported(source)
        case BabbageContextError.ReferenceInputsNotSupported(ins) =>
          TranslationError.ReferenceInputsNotSupported(ins)
    case ConwayContextError.ReferenceInputsNotDisjointFromInputs(common) =>
      TranslationError.ReferenceInputsNotDisjointFromInputs(common)
    case other => fail(s"no Plutus V3 translation fails with $other")

  private def typedTxInfo(
      lti: LedgerTxInfo[TxLevel.Top, Conway]
  ): Either[ConwayContextError[Conway], PV3.TxInfo] =
    v3.toPlutusTxInfo(lti).unPlutusTxInfoResult

  /** The typed V3 `TxInfo` of `c` at `major` against the flat one: equal data, or equal failures.
    */
  private def sameTxInfo(c: Case, major: Int): Either[TranslationError, PlutusData] =
    val (_, _, lti) = typed(c, major)
    val ours = typedTxInfo(lti).map(i => ToData[PV3.TxInfo].toBuiltinData(i))
    val flat = ScriptContexts.txInfo(PlutusLanguage.V3, c.flat, flatEnv(c, major))
    assertEquals(ours.left.map(asFlat), flat, s"${c.name} at version $major")
    flat

  private def ptr(tag: Int, index: BigInt): ConwayPlutusPurpose[AsIx] =
    val ix = Unsigned.from[Unsigned.Width.W32](index).getOrElse(fail(s"index $index"))
    tag match
      case 0     => ConwayPlutusPurpose.ConwaySpending(AsIx(ix))
      case 1     => ConwayPlutusPurpose.ConwayMinting(AsIx(ix))
      case 2     => ConwayPlutusPurpose.ConwayCertifying(AsIx(ix))
      case 3     => ConwayPlutusPurpose.ConwayWithdrawing(AsIx(ix))
      case 4     => ConwayPlutusPurpose.ConwayVoting(AsIx(ix))
      case 5     => ConwayPlutusPurpose.ConwayProposing(AsIx(ix))
      case other => fail(s"not a Conway tag: $other")

  /** The typed V3 script context of the redeemer at `pointer`, serialised. */
  private def typedContext(c: Case, pointer: ConwayPlutusPurpose[AsIx]): Bytes =
    val (tx, _, lti) = typed(c)
    val body = t.body[TxLevel.Top](tx)
    val purpose = t.bodyOf
      .redeemerPointerInverse[TxLevel.Top](body, pointer)
      .getOrElse(fail(s"${c.name}: $pointer names nothing"))
    val redeemer = t.witsOf.rdmrsTxWits(t.wits[TxLevel.Top](tx)).entries(pointer)._1
    val args = for
      info <- PlutusContext.mkPlutusTxInfoFromResult[Language.PlutusV3, Conway](
        ConwayPlutusPurpose.hoistPlutusPurpose[
          AsIxItem,
          AsPurpose
        ]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsPurpose(a))(
          purpose
        ),
        v3.toPlutusTxInfo(lti)
      )
      a <- v3.toPlutusArgs(lti, info, purpose, redeemer)
    yield a
    args.fold(
      e => fail(s"${c.name}: $e"),
      a => DataSerialisation.serialise(ToData[PV3.ScriptContext].toBuiltinData(a.unPlutusV3Args))
    )

  /** The arguments of a reference request (`PlutusWithContext`): its language and the bytes of its
    * script context, for a Plutus V3 request.
    */
  private def referenceV3(script: Json): Option[Bytes] =
    val encoding = hex(
      script.hcursor.downField("contextCbor").focus.flatMap(_.asString).getOrElse(fail("digested"))
    )
    cbor(encoding).value match
      case CborValue.Arr(Vector(_, s, arguments, _, _)) =>
        s.value match
          case CborValue.Arr(Vector(language, _)) =>
            language.value match
              case CborValue.UInt(l) if l == BigInt(PlutusLanguage.V3.id) =>
                Some(arguments.original)
              case _ => None
          case other => fail(s"not a script: $other")
      case other => fail(s"not a PlutusWithContext: $other")

  test("the corpus: every Conway row of the recorded contexts and of the context matrix") {
    assertEquals(cases.size, cases.map(_.name).distinct.size)
    // 27 rows of the recorded contexts, 235 of the matrix, 145 of the context errors and 25 of the
    // script results
    assertEquals(cases.size, 27 + 235 + 145 + 25)
  }

  test("the typed V3 TxInfo is the flat one on every Conway row, at its version and at 9 to 11") {
    val outcomes =
      cases.flatMap(c => (Vector(c.major) ++ Vector(9, 10, 11)).distinct.map(sameTxInfo(c, _)))
    assert(outcomes.exists(_.isRight))
    // the refusals the corpus reaches; the others are crafted below
    assertEquals(
      outcomes.collect {
        case Left(TranslationError.ByronTxOutInContext(_))               => "Byron"
        case Left(TranslationError.RedeemerPointerPointsToNothing(_, _)) => "pointer"
      }.toSet,
      Set("Byron", "pointer")
    )
  }

  test("each Plutus V3 context the reference collected is the typed one, byte for byte") {
    val matched = cases.flatMap { c =>
      // the context errors' corpus records the count of the scripts collected, not their contexts
      if !field[Boolean](c.oracle, "collected") || c.name.startsWith("errors/") then Vector.empty
      else
        val requests = Phase2
          .collect(
            c.flat,
            TestLedger.params(c.major).copy(costModels = c.costModels),
            (c.spent.values ++ c.references.values)
              .flatMap(_.referenceScript)
              .map(s => s.hash -> s)
              .toMap ++ c.flat.witnesses.scriptsByHash,
            c.spent,
            c.references,
            c.posix
          )(using ShelleyBasedEra.SConway)
          .fold(e => fail(s"${c.name}: $e"), identity)
        val references = field[Vector[Json]](c.oracle, "scripts")
        assertEquals(requests.size, references.size, c.name)
        requests.zip(references).flatMap { case ((redeemer, request), reference) =>
          referenceV3(reference).map { expected =>
            assertEquals(request.language, PlutusLanguage.V3, c.name)
            val ours = typedContext(c, ptr(redeemer.tag, redeemer.index))
            assertEquals(ours, DataSerialisation.serialise(request.args.head), s"${c.name}: flat")
            assertEquals(ours, expected, s"${c.name}: reference ${redeemer.tag}.${redeemer.index}")
            c.name
          }
        }
    }
    // the Plutus V3 requests of the Conway rows, 54 recorded, 64 of the matrix and 24 of the script
    // results: a filter that loses one fails here
    assertEquals(matched.size, 54 + 64 + 24, matched.distinct)
  }

  // -------------------------------------------------------------------------------------------
  // Crafted cases, for what the corpus lacks, against the flat translation

  /** A Conway case whose V3 information both paths build at its version, spending a script, with a
    * validity interval and inputs the UTxO set holds.
    */
  private lazy val base: Case =
    cases
      .find(c =>
        sameTxInfo(c, c.major).isRight && c.flat.body.inputs.sizeIs >= 2 &&
          c.flat.body.ttl.isDefined && c.flat.witnesses.redeemers.nonEmpty
      )
      .getOrElse(fail("no base case"))

  test("an input the UTxO set lacks: TranslationLogicMissingInput, as the flat UnresolvedInput") {
    val missing = base.flat.body.inputs.sorted.last
    val c = base.copy(spent = base.spent - missing)
    assertEquals(sameTxInfo(c, c.major), Left(TranslationError.UnresolvedInput(missing)))
    val (_, _, lti) = typed(c)
    assertEquals(
      typedTxInfo(lti),
      Left(
        ConwayContextError.BabbageContextError(
          BabbageContextError.AlonzoContextError(
            AlonzoContextError.TranslationLogicMissingInput(missing)
          )
        )
      )
    )
  }

  test("a slot the clock cannot translate: TimeTranslationPastHorizon with the clock's failure") {
    val slot = base.flat.body.validityStart.getOrElse(base.flat.body.ttl.get)
    val c = base.copy(posix = s => Left(TranslationError.NoClockSample(s)))
    assertEquals(sameTxInfo(c, c.major), Left(TranslationError.NoClockSample(slot)))
    val (_, _, lti) = typed(c)
    assertEquals(
      typedTxInfo(lti),
      Left(
        ConwayContextError.BabbageContextError(
          BabbageContextError.AlonzoContextError(
            AlonzoContextError.TimeTranslationPastHorizon(TranslationError.NoClockSample(slot))
          )
        )
      )
    )
  }

  test("an end alone is open at its end, unlike Alonzo's") {
    val (_, _, lti) = typed(base)
    val ttl = base.flat.body.ttl.get
    val range = ConwayTxInfo.transValidityInterval[Conway](
      lti.ltiSlotToPOSIXTime,
      ValidityInterval(None, Some(ttl))
    )
    assertEquals(
      range.map(_.ivTo).toOption,
      base
        .posix(ttl)
        .map(t => PV1.UpperBound(PV1.Extended.Finite(PV1.POSIXTime(t)), false))
        .toOption
    )
    assertEquals(range.map(_.ivFrom), Right(PV1.LowerBound(PV1.Extended.NegInf, true)))
  }

  test("an inline datum that does not read as data: the node's Undecodable, as the flat path's") {
    val in = base.flat.body.inputs.sorted.head
    val bad =
      base.spent(in).copy(datum = xyz.kd5ujc.scarab.ledger.Datum.Inline(Bytes(Vector(0xff.toByte))))
    val c = base.copy(spent = base.spent.updated(in, bad))
    assertEquals(
      sameTxInfo(c, c.major),
      Left(TranslationError.Undecodable(xyz.kd5ujc.scarab.ledger.DataSite.InlineDatum))
    )
  }

  test(
    "a Byron output whose inline datum does not read as data: ByronTxOutInContext first, as the flat path's"
  ) {
    val in = base.flat.body.inputs.sorted.head
    val byron = xyz.kd5ujc.scarab.ledger.Address
      .decode(
        xyz.kd5ujc.scarab.ledger.byron.ByronAddress.make(
          xyz.kd5ujc.scarab.ledger.byron.ByronAddress.VerKey,
          Bytes(Vector.fill(64)(2.toByte)),
          None
        )
      )
      .fold(e => fail(e.toString), identity)
    val bad = base
      .spent(in)
      .copy(
        address = byron,
        datum = xyz.kd5ujc.scarab.ledger.Datum.Inline(Bytes(Vector(0xff.toByte)))
      )
    val c = base.copy(spent = base.spent.updated(in, bad))
    assert(
      sameTxInfo(c, c.major) match
        case Left(TranslationError.ByronTxOutInContext(_)) => true
        case _                                             => false
    )
  }

  test(
    "a Byron output whose stored inline datum does not read as data: V1 asks for the datum first, V2 for the address"
  ) {
    // the reference's V1 asks `isSJust (txOut ^. dataTxOutL)` first, whose strict `SJust` forces
    // `binaryDataToData` (`Conway/TxInfo.hs:314-318`); V2 forces its datum only for an address that
    // is not Byron (`Babbage/TxInfo.hs:142-156`), both cardano-ledger f649f975
    val in = base.flat.body.inputs.sorted.head
    val byron = xyz.kd5ujc.scarab.ledger.Address
      .decode(
        xyz.kd5ujc.scarab.ledger.byron.ByronAddress.make(
          xyz.kd5ujc.scarab.ledger.byron.ByronAddress.VerKey,
          Bytes(Vector.fill(64)(2.toByte)),
          None
        )
      )
      .fold(e => fail(e.toString), identity)
    val bad = base
      .spent(in)
      .copy(
        address = byron,
        datum = xyz.kd5ujc.scarab.ledger.Datum.Inline(Bytes(Vector(0xff.toByte)))
      )
    val out = ConwayTxOut.fromStored(bad).fold(e => fail(e.toString), identity)
    val source = xyz.kd5ujc.scarab.ledger.TxOutSource.TxOutFromInput(in)
    assertEquals(
      ConwayTxInfo.transTxOutV1[Conway](source, out)(using EraTxOut.conway),
      Left(
        ConwayContextError.BabbageContextError(
          BabbageContextError.AlonzoContextError(
            AlonzoContextError.Undecodable(
              xyz.kd5ujc.scarab.ledger.DataSite.InlineDatum,
              PlutusData.decode(Bytes(Vector(0xff.toByte))).fold(identity, d => fail(s"data: $d"))
            )
          )
        )
      )
    )
    assertEquals(
      BabbageTxInfo.transTxOutV2[Conway](source, out)(using EraTxOut.conway),
      Left(
        ConwayContextError.BabbageContextError(BabbageContextError.ByronTxOutInContext(source))
      )
    )
  }

  test(
    "a spending datum: the output's inline datum is read first, its failure winning over a datum the witnesses resolve"
  ) {
    // `strictMaybeToMaybe (txOut ^. dataTxOutL) <|> txOutDataFromWits` (`Babbage/UTxO.hs:96-102`,
    // cardano-ledger f649f975): the strict `SJust` of the inline datum is forced before the
    // witnesses are asked. An output has an inline datum or a datum hash, never both, so the order is
    // bound through an output class whose `data` fails while its `dataHash` names a datum the
    // witnesses hold: the generic function, given the same output, transaction and UTxO set
    val found = cases.iterator.flatMap { c =>
      val (tx, utxo, _) = typed(c)
      val datums = t.witsOf.datsTxWits(t.wits[TxLevel.Top](tx)).datums
      c.spent.collectFirst {
        case (
              in,
              xyz.kd5ujc.scarab.ledger.TxOut(_, _, _, xyz.kd5ujc.scarab.ledger.Datum.Hash(h), _, _)
            ) if datums.contains(h) =>
          (tx, utxo, in, datums(h))
      }
    }
    val (tx, utxo, in, witnessDatum) =
      found.nextOption().getOrElse(fail("no case spends an output whose datum the witnesses hold"))
    val purpose = ConwayPlutusPurpose.ConwaySpending[AsItem](AsItem(in))
    val refusal = PlutusData
      .decode(Bytes(Vector(0xff.toByte)))
      .swap
      .getOrElse(fail("0xff read as data"))
    val conwayOut = EraTxOut.conway
    // Conway's outputs, but an inline datum that fails to read wherever `data` is asked
    val failingData: BabbageEraTxOut[Conway] = new BabbageEraTxOut[Conway]:
      def scripts = conwayOut.scripts
      def valueOps = conwayOut.valueOps
      def address(out: TxOut[Conway]) = conwayOut.address(out)
      def value(out: TxOut[Conway]) = conwayOut.value(out)
      def raw(out: TxOut[Conway]) = conwayOut.raw(out)
      def dataHash(out: TxOut[Conway]) = conwayOut.dataHash(out)
      def datum(out: TxOut[Conway]) = conwayOut.datum(out)
      def data(out: TxOut[Conway]) = Left(refusal)
      def referenceScript(out: TxOut[Conway]) = conwayOut.referenceScript(out)
    def spendingDatum(o: BabbageEraTxOut[Conway]) =
      BabbageUTxO.getBabbageSpendingDatum[Conway, TxLevel.Top](utxo, tx, purpose)(using
        t,
        t.bodyOf.top,
        o,
        summon
      )
    // the witnesses resolve the output's datum hash: Conway's instance and the era's member give it
    assertEquals(spendingDatum(conwayOut), Right(Some(witnessDatum)))
    assertEquals(
      u.getSpendingDatum[TxLevel.Top](utxo, tx, purpose)(using u.txOf.bodyOf.top),
      Right(Some(witnessDatum))
    )
    // with the same fallback at hand, the inline datum's failure comes first
    assertEquals(spendingDatum(failingData), Left(refusal))
  }

  test(
    "a redeemer that does not read as data: both decoders refuse it, so the era's witness set holds every redeemer as data"
  ) {
    val parts = rawWitnesses(
      base,
      5 -> CborValue.Map(redeemerEntries(base).map { (k, v) =>
        v.value match
          case CborValue.Arr(Vector(_, units)) =>
            k -> node(arr(CborValue.Text("not data"), units.value))
          case other => fail(s"not a redeemer: $other")
      })
    )
    val notData = PlutusData.DecodeError.UnexpectedItem(CborValue.Text("not data"))
    val refused = xyz.kd5ujc.scarab.ledger.LedgerDecodeError
      .PlutusData(xyz.kd5ujc.scarab.ledger.DecodeField.Redeemer, notData)
    assertEquals(FlatTx.fromBlock(parts, Era.Conway).map(_ => ()), Left(refused))
    assertEquals(
      ConwayTx.decode(parts, StateLayout.Node11_1).map(_ => ()),
      Left(EraTxError.Decode(refused))
    )
  }

  test(
    "a witness datum that does not read as data: both decoders refuse it, so the era's witness set holds every datum as data"
  ) {
    val parts = rawWitnesses(base, 4 -> arr(CborValue.Text("not data")))
    val notData = PlutusData.DecodeError.UnexpectedItem(CborValue.Text("not data"))
    val refused = xyz.kd5ujc.scarab.ledger.LedgerDecodeError
      .PlutusData(xyz.kd5ujc.scarab.ledger.DecodeField.Datums, notData)
    assertEquals(FlatTx.fromBlock(parts, Era.Conway).map(_ => ()), Left(refused))
    assertEquals(
      ConwayTx.decode(parts, StateLayout.Node11_1).map(_ => ()),
      Left(EraTxError.Decode(refused))
    )
  }

  test(
    "the era's witness set holds each datum and redeemer decoded, with the bytes it arrived as"
  ) {
    val (tx, _, _) = typed(base)
    val w = t.witsOf
    val datums = w.datsTxWits(t.wits[TxLevel.Top](tx)).datums
    val redeemers = w.rdmrsTxWits(t.wits[TxLevel.Top](tx)).entries.values.map(_._1)
    assert(redeemers.nonEmpty)
    (datums.values ++ redeemers).foreach(d =>
      assertEquals(PlutusData.decode(d.originalBytes), Right(d.getPlutusData))
    )
    datums.foreach((hash, d) => assertEquals(d.hashData, hash))
  }

  // The base's redeemers as a map from pointer to data and units, whichever form they arrived in.
  private def redeemerEntries(c: Case): Vector[(Node, Node)] =
    val redeemers = cbor(c.parts.witnesses).value match
      case CborValue.Map(kvs) =>
        kvs.find((k, _) => k.value == uint(5)).map(_._2.value).getOrElse(fail("no redeemers"))
      case other => fail(s"not witnesses: $other")
    redeemers match
      case CborValue.Map(kvs) => kvs
      case CborValue.Arr(rs) =>
        rs.map(r =>
          r.value match
            case CborValue.Arr(Vector(t, i, d, u)) =>
              node(arr(t.value, i.value)) -> node(arr(d.value, u.value))
            case other => fail(s"not a redeemer: $other")
        )
      case other => fail(s"not redeemers: $other")

  // The transaction's parts with the witness fields `fields` replaced or added.
  private def rawWitnesses(c: Case, fields: (Int, CborValue)*): Block.Transaction =
    val keys = fields.map(_._1).toSet
    val entries = cbor(c.parts.witnesses).value match
      case CborValue.Map(kvs) =>
        kvs.filterNot((k, _) =>
          k.value match
            case CborValue.UInt(n) => keys.contains(n.toInt)
            case _                 => false
        ) ++ fields.map((k, v) => (node(CborValue.UInt(BigInt(k))), node(v)))
      case other => fail(s"not witnesses: $other")
    val witnesses = Cbor.encode(CborValue.Map(entries)).fold(e => fail(e.toString), identity)
    c.parts.copy(witnesses = witnesses)

  // A body with the fields `fields` replaced or added, in a transaction with the base's witnesses.
  private def withBody(c: Case, name: String, fields: (Int, CborValue)*): Case =
    val keys = fields.map(_._1).toSet
    val entries = cbor(c.parts.body).value match
      case CborValue.Map(kvs) =>
        kvs.filterNot((k, _) =>
          k.value match
            case CborValue.UInt(n) => keys.contains(n.toInt)
            case _                 => false
        ) ++ fields.map((k, v) => (node(CborValue.UInt(BigInt(k))), node(v)))
      case other => fail(s"not a body: $other")
    val body = Cbor.encode(CborValue.Map(entries)).fold(e => fail(e.toString), identity)
    val parts = c.parts.copy(body = body)
    val flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(s"$name: $e"), identity)
    c.copy(name = name, parts = parts, flat = flat)

  private def node(v: CborValue): Node = Node(v, Bytes.empty)
  private def arr(xs: CborValue*): CborValue = CborValue.Arr(xs.toVector.map(node))
  private def uint(n: BigInt): CborValue = CborValue.UInt(n)
  private def bytes(fill: Int, size: Int): CborValue =
    CborValue.ByteString(Bytes(Vector.fill(size)(fill.toByte)))
  private def keyCred(fill: Int): CborValue = arr(uint(0), bytes(fill, 28))
  private def scriptCred(fill: Int): CborValue = arr(uint(1), bytes(fill, 28))
  private def account(fill: Int): CborValue =
    CborValue.ByteString(Bytes(Vector(0xe0.toByte)) ++ Bytes(Vector.fill(28)(fill.toByte)))
  private def anchor: CborValue = arr(CborValue.Text("https://a"), bytes(9, 32))
  private def proposal(action: CborValue): CborValue = arr(uint(100), account(3), action, anchor)

  /** The flat transaction's inputs, written as the body's `TxIn` encoding. */
  private def inputs(ins: Vector[TxIn]): CborValue =
    CborValue.Arr(ins.map(in => node(arr(CborValue.ByteString(in.id), uint(in.index)))))

  test("a reference input that is also an input: refused from version 11, translated before") {
    val common = base.flat.body.inputs.sorted.take(2)
    val c0 = withBody(base, "overlap", 18 -> inputs(common))
    val c = c0.copy(references = common.map(in => in -> base.spent(in)).toMap)
    assert(sameTxInfo(c, 10).isRight)
    assertEquals(
      sameTxInfo(c, 11),
      Left(
        TranslationError.ReferenceInputsNotDisjointFromInputs(
          cats.data.NonEmptyVector.fromVectorUnsafe(common)
        )
      )
    )
  }

  test("the governance actions the corpus lacks, and committee members of both kinds in order") {
    val prev = arr(bytes(7, 32), uint(1))
    val c = withBody(
      base,
      "actions",
      20 -> CborValue.Arr(
        Vector(
          proposal(arr(uint(1), prev, arr(uint(11), uint(0)))),
          proposal(arr(uint(1), CborValue.Null, arr(uint(10), uint(3)))),
          proposal(arr(uint(3), prev)),
          proposal(
            arr(
              uint(4),
              CborValue.Null,
              arr(keyCred(1), scriptCred(2), keyCred(0), scriptCred(1)),
              CborValue.Map(
                Vector(
                  node(keyCred(5)) -> node(uint(9)),
                  node(scriptCred(6)) -> node(uint(8)),
                  node(keyCred(4)) -> node(uint(7))
                )
              ),
              CborValue.Tag(30, node(arr(uint(2), uint(3))))
            )
          ),
          proposal(arr(uint(6)))
        ).map(node)
      )
    )
    assert(sameTxInfo(c, 10).isRight)
    val (_, _, lti) = typed(c, 10)
    val info = typedTxInfo(lti).fold(e => fail(e.toString), identity)
    info.txInfoProposalProcedures(3).ppGovernanceAction match
      case PV3.GovernanceAction.UpdateCommittee(_, removed, added, quorum) =>
        // a script's credential sorts before a key's (`Credential.hs:98-101`, cardano-ledger
        // f649f975), and each kind by its hash
        assertEquals(
          removed.map(_.credential).map {
            case PV1.Credential.ScriptCredential(h) => ("script", h.getScriptHash.value.head.toInt)
            case PV1.Credential.PubKeyCredential(h) => ("key", h.getPubKeyHash.value.head.toInt)
          },
          Vector(("script", 1), ("script", 2), ("key", 0), ("key", 1))
        )
        assertEquals(added.toList.map(_._2), Vector(BigInt(8), BigInt(7), BigInt(9)))
        assertEquals(quorum, PV3.Rational(2, 3))
      case other => fail(s"not an update of the committee: $other")
  }

  test("the certificates the corpus lacks, a deposit and a refund left out at version 9 alone") {
    val pool = bytes(4, 28)
    val drepKey = arr(uint(0), bytes(5, 28))
    val c = withBody(
      base,
      "certificates",
      4 -> CborValue.Arr(
        Vector(
          arr(uint(0), keyCred(1)),
          arr(uint(1), scriptCred(1)),
          arr(uint(7), keyCred(2), uint(2000000)),
          arr(uint(8), scriptCred(2), uint(2000000)),
          arr(uint(2), keyCred(3), pool),
          arr(uint(9), keyCred(3), drepKey),
          arr(uint(9), keyCred(3), arr(uint(1), bytes(6, 28))),
          arr(uint(9), keyCred(3), arr(uint(2))),
          arr(uint(9), keyCred(3), arr(uint(3))),
          arr(uint(10), keyCred(3), pool, drepKey),
          arr(uint(11), keyCred(4), pool, uint(2000000)),
          arr(uint(12), keyCred(4), drepKey, uint(2000000)),
          arr(uint(13), keyCred(4), pool, drepKey, uint(2000000)),
          arr(uint(4), pool, uint(300)),
          arr(uint(14), keyCred(7), scriptCred(8)),
          arr(uint(15), keyCred(7), CborValue.Null),
          arr(uint(16), keyCred(9), uint(500), CborValue.Null),
          arr(uint(17), scriptCred(9), uint(500)),
          arr(uint(18), keyCred(9), anchor)
        ).map(node)
      )
    )
    val infos = Vector(9, 10).map(sameTxInfo(c, _))
    assert(infos.forall(_.isRight), infos)
    assertNotEquals(infos(0), infos(1))
    val (_, _, lti) = typed(c, 9)
    val certs = typedTxInfo(lti).fold(e => fail(e.toString), identity).txInfoTxCerts
    assertEquals(certs.size, 19)
    assertEquals(
      certs.take(4).map {
        case PV3.TxCert.TxCertRegStaking(_, d)   => d
        case PV3.TxCert.TxCertUnRegStaking(_, r) => r
        case other                               => fail(other.toString)
      },
      Vector(None, None, None, None)
    )
  }

  test("withdrawals and mints of several entries are in the ledger's order") {
    val c = withBody(
      base,
      "orders",
      5 -> CborValue.Map(
        Vector(
          node(
            CborValue.ByteString(Bytes(Vector(0xf0.toByte)) ++ Bytes(Vector.fill(28)(1.toByte)))
          ) -> node(uint(1)),
          node(account(2)) -> node(uint(2)),
          node(
            CborValue.ByteString(Bytes(Vector(0xe0.toByte)) ++ Bytes(Vector.fill(28)(1.toByte)))
          ) -> node(uint(3))
        )
      ),
      9 -> CborValue.Map(
        Vector(
          node(bytes(9, 28)) -> node(
            CborValue.Map(
              Vector(node(bytes(2, 3)) -> node(uint(1)), node(bytes(1, 1)) -> node(uint(2)))
            )
          ),
          node(bytes(3, 28)) -> node(
            CborValue.Map(Vector(node(bytes(5, 1)) -> node(CborValue.NInt(BigInt(-4)))))
          )
        )
      )
    )
    assert(sameTxInfo(c, 10).isRight)
    val (_, _, lti) = typed(c, 10)
    val info = typedTxInfo(lti).fold(e => fail(e.toString), identity)
    // a script account before a key account, then by hash
    assertEquals(
      info.txInfoWdrl.toList.map(_._2.getLovelace),
      Vector(BigInt(1), BigInt(3), BigInt(2))
    )
    assertEquals(
      info.txInfoMint.unMintValue.toList.map((p, names) =>
        (p.unCurrencySymbol.value.head.toInt, names.toList.map(_._2))
      ),
      Vector((3, Vector(BigInt(-4))), (9, Vector(BigInt(2), BigInt(1))))
    )
  }

  /** The Conway transactions of the recorded blocks: they come without the outputs they spend, so
    * every input and reference input is given one stand-in output, a key's at a Shelley address,
    * and slot `s` the time `s` seconds; what is compared is the rest of the transaction
    * (certificates, votes, proposals, withdrawals, mint, redeemers and datums), each read by both
    * paths.
    */
  private lazy val recordedBlocks: Vector[Case] =
    val txs = EraCorpus
      .shelleyBlocks(
        Vector(
          "fixtures/eras/blocks.hex",
          "fixtures/preview/conway.hex",
          "fixtures/preview/conway-governance.hex"
        )
      )
      .filter(_.era == Era.Conway)
      .flatMap(_.transactions)
      .map(t => t -> FlatTx.fromBlock(t, Era.Conway).fold(e => fail(e.toString), identity))
    val standIn = txs.iterator
      .flatMap(_._2.body.outputs)
      .find(o =>
        o.address.kind match
          case xyz.kd5ujc.scarab.ledger.Address.Kind
                .Shelley(_, xyz.kd5ujc.scarab.ledger.Credential.Key(_), _, _) =>
            o.datum == xyz.kd5ujc.scarab.ledger.Datum.NoDatum && o.scriptRef.isEmpty
          case _ => false
      )
      .getOrElse(fail("no stand-in output"))
    txs.zipWithIndex.map { case ((parts, flat), i) =>
      Case(
        s"recorded block transaction $i",
        parts,
        flat,
        flat.body.inputs.map(_ -> standIn).toMap,
        flat.body.referenceInputs.map(_ -> standIn).toMap,
        10,
        Map.empty,
        slot => Right(slot * 1000),
        Json.Null
      )
    }

  test("the typed V3 TxInfo is the flat one on every recorded Conway transaction, at 9 to 11") {
    val outcomes = recordedBlocks.flatMap(c => Vector(9, 10, 11).map(sameTxInfo(c, _)))
    assertEquals(recordedBlocks.size, RecordedBlockCount)
    assert(outcomes.forall(_.isRight), outcomes.collect { case Left(e) => e })
    val bodies = recordedBlocks.map(_.flat.body)
    // what the corpus exercises: every action, votes, certificates, withdrawals, mints and redeemers
    assertEquals(
      bodies.flatMap(_.proposals.map(_.action.ordinal)).toSet,
      (0 until 7).toSet,
      "the seven actions"
    )
    assert(bodies.exists(_.votes.nonEmpty))
    assert(bodies.exists(_.certificates.nonEmpty))
    assert(bodies.exists(_.withdrawals.nonEmpty))
    assert(bodies.exists(_.mint.nonEmpty))
    assert(recordedBlocks.exists(_.flat.witnesses.redeemers.nonEmpty))
  }

  test("a pointer past each container's end names nothing") {
    val (tx, _, _) = typed(base)
    val body = t.body[TxLevel.Top](tx)
    (0 to 5).foreach { tag =>
      assertEquals(
        t.bodyOf.redeemerPointerInverse[TxLevel.Top](body, ptr(tag, BigInt(4294967295L))),
        None
      )
    }
    assert(t.bodyOf.redeemerPointerInverse[TxLevel.Top](body, ptr(0, 0)).isDefined)
  }

  // -------------------------------------------------------------------------------------------
  // Plutus V1 and V2, and the era's memoised information

  private val ctx = EraPlutusContext.conway

  /** A failure in a form both paths give it in: the flat path keeps the votes in the body's order
    * and names a purpose with its index, the reference a map of the votes and a purpose by its
    * item.
    */
  private def normFlat(e: TranslationError): Any = e match
    case TranslationError.VotingProceduresFieldNotSupported(votes) =>
      ("votes", votes.map((voter, vs) => voter -> vs.toMap).toMap)
    case TranslationError.PlutusPurposeNotSupported(
          xyz.kd5ujc.scarab.ledger.Purpose.Voting(_, voter)
        ) =>
      ("purpose", voter)
    case TranslationError.PlutusPurposeNotSupported(
          xyz.kd5ujc.scarab.ledger.Purpose.Proposing(_, proposal)
        ) =>
      ("purpose", proposal)
    case other => other

  private def normTyped(e: ConwayContextError[Conway]): Any = e match
    case ConwayContextError.VotingProceduresFieldNotSupported(votes) =>
      ("votes", votes.unVotingProcedures.map((voter, vs) => voter -> vs.toMap).toMap)
    case ConwayContextError.ProposalProceduresFieldNotSupported(proposals) =>
      TranslationError.ProposalProceduresFieldNotSupported(proposals.toVector.map(_.read))
    case ConwayContextError.TreasuryDonationFieldNotSupported(coin) =>
      TranslationError.TreasuryDonationFieldNotSupported(coin.value)
    case ConwayContextError.CurrentTreasuryFieldNotSupported(coin) =>
      TranslationError.CurrentTreasuryFieldNotSupported(coin.value)
    case ConwayContextError.CertificateNotSupported[Conway](cert) =>
      TranslationError.CertificateNotSupported(conway.ConwayTxCert.toStored(cert))
    case ConwayContextError.PlutusPurposeNotSupported(purpose) =>
      purpose match
        case ConwayPlutusPurpose.ConwayVoting(AsItem(voter))       => ("purpose", voter)
        case ConwayPlutusPurpose.ConwayProposing(AsItem(proposal)) => ("purpose", proposal.read)
        case other => fail(s"refused purpose $other")
    case other => asFlat(other)

  /** The typed information of `language` through the era's memoised result, as data. */
  private def typedLegacyTxInfo(
      lti: LedgerTxInfo[TxLevel.Top, Conway],
      language: PlutusLanguage
  ): Either[ConwayContextError[Conway], PlutusData] =
    val result = ctx.mkTxInfoResult(lti)
    language match
      case PlutusLanguage.V1 =>
        ctx
          .lookupTxInfoResult[Language.PlutusV1](result)
          .unPlutusTxInfoResult
          .map(ToData[PV1.TxInfo].toBuiltinData)
      case PlutusLanguage.V2 =>
        ctx
          .lookupTxInfoResult[Language.PlutusV2](result)
          .unPlutusTxInfoResult
          .map(ToData[PV2.TxInfo].toBuiltinData)
      case other => fail(s"not a legacy language: $other")

  /** The typed V1 or V2 `TxInfo` of `c` at `major` against the flat one. */
  private def sameLegacyTxInfo(
      c: Case,
      major: Int,
      language: PlutusLanguage
  ): Either[TranslationError, PlutusData] =
    val (_, _, lti) = typed(c, major)
    val flat = ScriptContexts.txInfo(language, c.flat, flatEnv(c, major))
    assertEquals(
      typedLegacyTxInfo(lti, language).left.map(normTyped),
      flat.left.map(normFlat),
      s"${c.name} at version $major, $language"
    )
    flat

  test(
    "the typed V1 and V2 TxInfos are the flat ones on every Conway row, at its version and 9 to 11"
  ) {
    val outcomes = (cases ++ recordedBlocks).flatMap(c =>
      for
        major <- (Vector(c.major) ++ Vector(9, 10, 11)).distinct
        language <- Vector(PlutusLanguage.V1, PlutusLanguage.V2)
      yield sameLegacyTxInfo(c, major, language)
    )
    assert(outcomes.exists(_.isRight))
    // the refusals the corpus and the recorded blocks reach, each by its case
    val kinds = outcomes.collect { case Left(e) => legacyRefusal(e) }.toSet
    assertEquals(kinds, LegacyRefusal.values.toSet, kinds)
  }

  /** Every refusal a V1 or V2 context has in Conway but `PlutusPurposeNotSupported`, which the
    * guard on votes and proposals reaches first for a voting or proposing purpose, as in the
    * reference.
    */
  private enum LegacyRefusal:
    case Votes, Proposals, Donation, CurrentTreasury, Certificate, InlineDatum, Byron, Pointer

  private def legacyRefusal(e: TranslationError): LegacyRefusal = e match
    case TranslationError.VotingProceduresFieldNotSupported(_)   => LegacyRefusal.Votes
    case TranslationError.ProposalProceduresFieldNotSupported(_) => LegacyRefusal.Proposals
    case TranslationError.TreasuryDonationFieldNotSupported(_)   => LegacyRefusal.Donation
    case TranslationError.CurrentTreasuryFieldNotSupported(_)    => LegacyRefusal.CurrentTreasury
    case TranslationError.CertificateNotSupported(_)             => LegacyRefusal.Certificate
    case TranslationError.InlineDatumsNotSupported(_)            => LegacyRefusal.InlineDatum
    case TranslationError.ByronTxOutInContext(_)                 => LegacyRefusal.Byron
    case TranslationError.RedeemerPointerPointsToNothing(_, _)   => LegacyRefusal.Pointer
    case other => fail(s"no V1 or V2 context in Conway is refused with $other")

  /** The typed arguments of the V1 or V2 script of the redeemer at `pointer`, each serialised. */
  private def typedLegacyArgs(
      c: Case,
      pointer: ConwayPlutusPurpose[AsIx],
      language: PlutusLanguage
  ): Vector[Bytes] =
    val (tx, _, lti) = typed(c)
    val body = t.body[TxLevel.Top](tx)
    val purpose = t.bodyOf
      .redeemerPointerInverse[TxLevel.Top](body, pointer)
      .getOrElse(fail(s"${c.name}: $pointer names nothing"))
    val redeemer = t.witsOf.rdmrsTxWits(t.wits[TxLevel.Top](tx)).entries(pointer)._1
    val asPurpose = ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsPurpose](
      [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsPurpose(a)
    )(purpose)
    val result = ctx.mkTxInfoResult(lti)
    val args: Either[ConwayContextError[Conway], Vector[PlutusData]] = language match
      case PlutusLanguage.V1 =>
        val t1 = EraPlutusTxInfo.conwayPlutusV1
        for
          info <- PlutusContext
            .mkPlutusTxInfoFromResult[Language.PlutusV1, Conway](
              asPurpose,
              ctx.lookupTxInfoResult[Language.PlutusV1](result)
            )
          a <- t1.toPlutusArgs(lti, info, purpose, redeemer)
        yield LegacyPlutusArgs.legacyPlutusArgsToData(a.unPlutusV1Args)
      case PlutusLanguage.V2 =>
        val t2 = EraPlutusTxInfo.conwayPlutusV2
        for
          info <- PlutusContext
            .mkPlutusTxInfoFromResult[Language.PlutusV2, Conway](
              asPurpose,
              ctx.lookupTxInfoResult[Language.PlutusV2](result)
            )
          a <- t2.toPlutusArgs(lti, info, purpose, redeemer)
        yield LegacyPlutusArgs.legacyPlutusArgsToData(a.unPlutusV2Args)
      case other => fail(s"not a legacy language: $other")
    args.fold(e => fail(s"${c.name}: $e"), _.map(DataSerialisation.serialise))

  /** The language and the arguments' bytes of a reference request (`PlutusWithContext`). */
  private def referenceArgs(script: Json): (BigInt, Vector[Bytes]) =
    val encoding = hex(
      script.hcursor.downField("contextCbor").focus.flatMap(_.asString).getOrElse(fail("digested"))
    )
    cbor(encoding).value match
      case CborValue.Arr(Vector(_, s, arguments, _, _)) =>
        val language = s.value match
          case CborValue.Arr(Vector(l, _)) =>
            l.value match
              case CborValue.UInt(n) => n
              case other             => fail(s"not a language: $other")
          case other => fail(s"not a script: $other")
        val args = arguments.value match
          case CborValue.Arr(items) => items.map(_.original)
          case _                    => Vector(arguments.original)
        (language, args)
      case other => fail(s"not a PlutusWithContext: $other")

  test("each Plutus V1 and V2 argument the reference collected is the typed one, byte for byte") {
    val matched = cases.flatMap { c =>
      if !field[Boolean](c.oracle, "collected") || c.name.startsWith("errors/") then Vector.empty
      else
        val requests = Phase2
          .collect(
            c.flat,
            TestLedger.params(c.major).copy(costModels = c.costModels),
            (c.spent.values ++ c.references.values)
              .flatMap(_.referenceScript)
              .map(s => s.hash -> s)
              .toMap ++ c.flat.witnesses.scriptsByHash,
            c.spent,
            c.references,
            c.posix
          )(using ShelleyBasedEra.SConway)
          .fold(e => fail(s"${c.name}: $e"), identity)
        val references = field[Vector[Json]](c.oracle, "scripts")
        assertEquals(requests.size, references.size, c.name)
        requests.zip(references).flatMap { case ((redeemer, request), reference) =>
          val (language, expected) = referenceArgs(reference)
          Option.when(language < BigInt(PlutusLanguage.V3.id)) {
            assertEquals(BigInt(request.language.id), language, c.name)
            val ours = typedLegacyArgs(c, ptr(redeemer.tag, redeemer.index), request.language)
            val at = s"${c.name}: ${redeemer.tag}.${redeemer.index}"
            assertEquals(ours, request.args.map(DataSerialisation.serialise), s"$at: flat")
            assertEquals(ours, expected, s"$at: reference")
            c.name
          }
        }
    }
    // the V1 and V2 requests of the Conway rows, 14 recorded, 78 of the matrix and 49 of the
    // script results: a filter that loses one fails here
    assertEquals(matched.size, 14 + 78 + 49, matched.distinct)
  }

  test("Conway supports Plutus V1 to V3, each looked up as its own translation gives it") {
    assertEquals(
      PlutusLanguage.values.toVector.map(l => ctx.mkSupportedLanguage(l).map(_.language)),
      Vector(Some(PlutusLanguage.V1), Some(PlutusLanguage.V2), Some(PlutusLanguage.V3), None)
    )
    val supported = PlutusLanguage.values.toVector.flatMap(ctx.mkSupportedLanguage).reverse
    assertEquals(
      supported.sorted.map(_.language),
      Vector(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3)
    )
    assertEquals(
      ctx.mkSupportedLanguage(PlutusLanguage.V2),
      ctx.mkSupportedLanguage(PlutusLanguage.V2)
    )
    val (_, _, lti) = typed(base)
    val result = ctx.mkTxInfoResult(lti)
    assertEquals(
      ctx.lookupTxInfoResult[Language.PlutusV3](result).unPlutusTxInfoResult,
      v3.toPlutusTxInfo(lti).unPlutusTxInfoResult
    )
    assertEquals(
      ctx.lookupTxInfoResult[Language.PlutusV1](result).unPlutusTxInfoResult,
      EraPlutusTxInfo.conwayPlutusV1.toPlutusTxInfo(lti).unPlutusTxInfoResult
    )
  }

  // ---------------------------------------------------------------------------------------------
  // The scripts of a transaction with their contexts (`Alonzo/Plutus/Evaluate.hs`)

  private def flatParams(c: Case): xyz.kd5ujc.scarab.ledger.ProtocolParams =
    TestLedger.params(c.major).copy(costModels = c.costModels)

  private def eraParams(c: Case): PParams[Conway] =
    PParams[Conway](
      ConwayPParams.fromStored(flatParams(c)).fold(e => fail(s"${c.name}: $e"), identity)
    )

  private def flatScripts(c: Case): Map[Bytes, xyz.kd5ujc.scarab.ledger.Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(s => s.hash -> s)
      .toMap ++ c.flat.witnesses.scriptsByHash

  private def eraCollect(
      c: Case,
      pp: PParams[Conway]
  ): Either[cats.data.NonEmptyList[CollectError[Conway]], List[PlutusWithContext]] =
    val (tx, utxo, _) = typed(c)
    AlonzoPlutusEvaluate.collectPlutusScriptsWithContext[Conway](c.posix, pp, tx, utxo)

  /** Each needed purpose's item form with its tag and index, as the flat purpose names it. */
  private def neededIndex(c: Case): Map[ConwayPlutusPurpose[AsItem], (Int, BigInt)] =
    val (tx, utxo, _) = typed(c)
    u.getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](tx))(using u.txOf.bodyOf.top)
      .unAlonzoScriptsNeeded
      .map { (purpose, _) =>
        val item = EraScript.conway.hoistPlutusPurpose[AsIxItem, AsItem](
          [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
        )(purpose)
        val ix = EraScript.conway
          .hoistPlutusPurpose[
            AsIxItem,
            AsIx
          ]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(
            purpose
          )
        item -> (ix.tag, ix.ix.value)
      }
      .toMap

  /** The outcomes of both collections in one form: a failure by its kind, a redeemer's purpose by
    * its tag and index and a translation's failure as [[normTyped]] and [[normFlat]] give them.
    */
  private enum Collected:
    case NoRedeemer(tag: Int, ix: BigInt)
    case NoCostModel(language: PlutusLanguage)
    case BadTranslation(failure: Any)

  private def eraCollected(c: Case)(e: CollectError[Conway]): Collected = e match
    case CollectError.NoRedeemer(purpose) =>
      val (tag, ix) = neededIndex(c).getOrElse(purpose, fail(s"${c.name}: $purpose is not needed"))
      Collected.NoRedeemer(tag, ix)
    case CollectError.NoCostModel(language)   => Collected.NoCostModel(language)
    case CollectError.BadTranslation(failure) => Collected.BadTranslation(normTyped(failure))
    case CollectError.NoWitness(hash)         => fail(s"${c.name}: NoWitness ${hash.hex}")

  private def flatCollected(c: Case)(e: xyz.kd5ujc.scarab.ledger.CollectError): Collected = e match
    case xyz.kd5ujc.scarab.ledger.CollectError.NoRedeemer(purpose) =>
      Collected.NoRedeemer(purpose.tag, BigInt(purpose.index))
    case xyz.kd5ujc.scarab.ledger.CollectError.NoCostModel(language) =>
      Collected.NoCostModel(language)
    case xyz.kd5ujc.scarab.ledger.CollectError.BadTranslation(failure) =>
      Collected.BadTranslation(normFlat(failure))
    case xyz.kd5ujc.scarab.ledger.CollectError.NoWitness(hash) =>
      fail(s"${c.name}: NoWitness ${hash.hex}")

  /** Both collections of `c` with the parameters `flat` (the era's read from them): the era's
    * scripts with their contexts as the requests the evaluator takes, equal to the flat path's in
    * order, or the same failures in the same order.
    */
  private def sameCollection(
      c: Case,
      flat: xyz.kd5ujc.scarab.ledger.ProtocolParams
  ): Either[List[Collected], List[xyz.kd5ujc.scarab.plutus.EvalRequest]] =
    val pp =
      PParams[Conway](ConwayPParams.fromStored(flat).fold(e => fail(s"${c.name}: $e"), identity))
    val era = eraCollect(c, pp).fold(
      es => Left(es.toList.map(eraCollected(c))),
      pwcs => Right(pwcs.map(PlutusWithContext.evalRequest))
    )
    val reference = Phase2
      .collect[Conway](c.flat, flat, flatScripts(c), c.spent, c.references, c.posix)(using
        ShelleyBasedEra.SConway
      )
      .fold(es => Left(es.toVector.toList.map(flatCollected(c))), rs => Right(rs.toList.map(_._2)))
    assertEquals(era, reference, c.name)
    era

  test("every Conway case's scripts with their contexts are the flat path's requests or failures") {
    val outcomes = (cases ++ recordedBlocks).map(c => sameCollection(c, flatParams(c)))
    assert(outcomes.exists(_.exists(_.nonEmpty)), "no script was collected")
    val failures = outcomes.collect { case Left(es) => es }.flatten
    // the failures the corpus reaches, each by its kind: a redeemer missing, a cost model missing
    // (the recorded blocks have none) and a translation's failure
    assertEquals(
      failures.map(_.productPrefix).toSet,
      Set("NoRedeemer", "NoCostModel", "BadTranslation"),
      failures
    )
  }

  /** A case whose scripts are collected, with its Plutus V3 request. */
  private lazy val collectedV3: Case =
    cases
      .find(c =>
        eraCollect(c, eraParams(c)).exists(
          _.exists(_.plutusLanguage.language == PlutusLanguage.V3)
        )
      )
      .getOrElse(fail("no case collects a Plutus V3 script"))

  test("a language whose tag the cost models lack: NoCostModel; an unknown tag is no language's") {
    val c = collectedV3
    val v3Model = c.costModels.getOrElse(PlutusLanguage.V3.id, fail("no V3 model"))
    // the V3 model under an unknown tag only
    val models = c.costModels - PlutusLanguage.V3.id + (7 -> v3Model)
    val eraModels = CostModels.from(models).fold(e => fail(e.toString), identity)
    // no language's model is the one under the unknown tag
    assertEquals(CostModel.lookupCostModel[Language.PlutusV3](eraModels), None)
    assertEquals(
      Vector(
        CostModel.lookupCostModel[Language.PlutusV1](eraModels).map(_.cmLanguage),
        CostModel.lookupCostModel[Language.PlutusV2](eraModels).map(_.cmLanguage),
        CostModel.lookupCostModel[Language.PlutusV4](eraModels).map(_.cmLanguage)
      ).flatten.toSet,
      models.keySet.flatMap(tag => PlutusLanguage.values.find(_.id == tag))
    )
    // a known language's model is its parameters, each the Int64 the parameters hold
    assertEquals(
      CostModel
        .lookupCostModel[Language.PlutusV3](
          CostModels.from(c.costModels).fold(e => fail(e.toString), identity)
        )
        .map(_.cmValues),
      Some(v3Model.map(_.toLong))
    )
    val outcome = sameCollection(c, flatParams(c).copy(costModels = models))
    assert(
      outcome.left.exists(_.contains(Collected.NoCostModel(PlutusLanguage.V3))),
      outcome
    )
    eraCollect(c, eraParams(c.copy(costModels = models))) match
      case Left(es) =>
        assert(es.toList.contains(CollectError.NoCostModel[Conway](PlutusLanguage.V3)), es)
      case Right(pwcs) => fail(s"collected without a V3 model: $pwcs")
  }

  test(
    "a Plutus script's runnable: its hash is the script's, and it is equal by language and hash"
  ) {
    val scripts = (cases ++ recordedBlocks).flatMap { c =>
      val (tx, _, _) = typed(c)
      t.witsOf.scriptTxWits(t.wits[TxLevel.Top](tx)).values
    }
    val plutus = scripts.flatMap(s => EraScript.conway.toPlutusScript(s).map(s -> _))
    assert(plutus.nonEmpty)
    val v = Version.lit(10)
    plutus.foreach { (script, p) =>
      val runnable = ctx.mkSupportedPlutusRunnable(v, p)
      assertEquals(
        runnable.plutusRunnable.plutusRunnableScriptHash,
        EraScript.conway.hashScript(script)
      )
      assertEquals(runnable.language, AlonzoScripts.plutusScriptLanguage[Conway](p))
      assertEquals(ctx.mkSupportedPlutusRunnable(Version.lit(9), p), runnable)
    }
    val (script, p) = plutus.head
    val other = p match
      case ConwayPlutusScript.V1(b) => ConwayPlutusScript.V2(Plutus(b.binary))
      case ConwayPlutusScript.V2(b) => ConwayPlutusScript.V3(Plutus(b.binary))
      case ConwayPlutusScript.V3(b) => ConwayPlutusScript.V1(Plutus(b.binary))
    // the same binary in another language is another script
    assertNotEquals(ctx.mkSupportedPlutusRunnable(v, other), ctx.mkSupportedPlutusRunnable(v, p))
    assertNotEquals(
      ctx.mkSupportedPlutusRunnable(v, other).plutusRunnable.plutusRunnableScriptHash,
      EraScript.conway.hashScript(script)
    )
  }

  test("the scripts needed and provided: a provided Plutus script, the cache's runnable kept") {
    val c = collectedV3
    val (tx, utxo, lti) = typed(c)
    val provided = u.getScriptsProvided[TxLevel.Top](utxo, tx)(using u.txOf.bodyOf.top)
    val needed =
      u.getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](tx))(using u.txOf.bodyOf.top)
    val (cache, used) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose[Conway](
      lti.ltiProtVer,
      provided,
      needed,
      Map.empty
    )
    // every needed purpose whose script is a provided Plutus script, in the order needed
    assertEquals(
      used.map((purpose, r) => purpose -> r.plutusRunnable.plutusRunnableScriptHash),
      needed.unAlonzoScriptsNeeded.toList.filter((_, hash) =>
        provided.unScriptsProvided.get(hash).exists(EraScript.conway.toPlutusScript(_).isDefined)
      )
    )
    assertEquals(
      cache.keySet,
      provided.unScriptsProvided
        .filter((_, s) => EraScript.conway.toPlutusScript(s).isDefined)
        .keySet
    )
    // a needed script's cached runnable is the one used, and kept
    val (_, usedRunnable) = used.headOption.getOrElse(fail(s"${c.name}: no script used"))
    val hash = usedRunnable.plutusRunnable.plutusRunnableScriptHash
    val cached = SupportedPlutusRunnable(
      EraPlutusTxInfo.conwayPlutusV1,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes.empty), hash)
    )
    val (again, usedAgain) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose[Conway](
      lti.ltiProtVer,
      provided,
      needed,
      Map(hash -> cached)
    )
    assert(again(hash) eq cached)
    val usesOfHash = usedAgain.filter((_, r) => r.plutusRunnable.plutusRunnableScriptHash == hash)
    assert(usesOfHash.nonEmpty, usedAgain)
    assert(usesOfHash.forall((_, r) => r eq cached), usesOfHash)
    assertEquals(usedAgain.size, used.size)
    // a script the cache holds and the transaction does not provide does not meet its need
    val (cacheOnly, usedCacheOnly) =
      AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose[Conway](
        lti.ltiProtVer,
        ScriptsProvided[Conway](provided.unScriptsProvided - hash),
        needed,
        Map(hash -> cached)
      )
    assert(usedCacheOnly.forall((_, r) => r.plutusRunnable.plutusRunnableScriptHash != hash))
    assertEquals(
      usedCacheOnly,
      used.filter((_, r) => r.plutusRunnable.plutusRunnableScriptHash != hash)
    )
    // kept in the returned cache all the same
    assert(cacheOnly(hash) eq cached)
  }

  test(
    "the information's script hashes by purpose are its scripts used's, at the top level alone"
  ) {
    val c = collectedV3
    val (tx, utxo, lti) = typed(c)
    val (_, used) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose[Conway](
      lti.ltiProtVer,
      u.getScriptsProvided[TxLevel.Top](utxo, tx)(using u.txOf.bodyOf.top),
      u.getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](tx))(using u.txOf.bodyOf.top),
      Map.empty
    )
    assert(used.nonEmpty, c.name)
    val withUsed = lti.copy[TxLevel.Top, Conway](ltiScriptsUsed = used)
    // `toScriptHashByPurpose`: each runnable's hash by its purpose's index
    assertEquals(
      withUsed.ltiScriptHashesUsed,
      used.map { (purpose, r) =>
        ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsIx](
          [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a)
        )(purpose) -> r.plutusRunnable.plutusRunnableScriptHash
      }.toMap
    )
    assertEquals(lti.ltiScriptHashesUsed, Map.empty[ConwayPlutusPurpose[AsIx], Bytes])
    // the scripts with their contexts are those of the information's scripts used
    val costModels = PParams
      .lensHKD[Conway]
      .andThen(summon[AlonzoEraPParams[Conway]].hkdCostModelsL[Identity])
      .get(eraParams(c))
    assertEquals(
      AlonzoPlutusEvaluate
        .scriptsWithContextFromLedgerTxInfo[Conway, TxLevel.Top](withUsed, costModels),
      AlonzoPlutusEvaluate.collectPlutusScriptsWithContext[Conway](c.posix, eraParams(c), tx, utxo)
    )
    assertEquals(
      AlonzoPlutusEvaluate.scriptsWithContextFromLedgerTxInfo[Conway, TxLevel.Top](lti, costModels),
      Right(Nil)
    )
    // Conway's level is the top level: a sub-transaction's information is Dijkstra's alone
    assertEquals(LedgerTxInfo.toSTxLevel(lti)(using t.bodyOf), TopOnly.Top)
    assertNotEquals(
      compileErrors(
        "val l: LedgerLevelTxInfo[TxLevel.Sub, Conway] = LedgerLevelTxInfo.LedgerSubTxInfo(xyz.kd5ujc.scarab.ledger.state.TxIx.enumerate(Vector(())).head._2)"
      ),
      ""
    )
    assertEquals(
      compileErrors(
        "val l: LedgerLevelTxInfo[TxLevel.Sub, xyz.kd5ujc.scarab.ledger.state.Dijkstra] = LedgerLevelTxInfo.LedgerSubTxInfo(xyz.kd5ujc.scarab.ledger.state.TxIx.enumerate(Vector(())).head._2)"
      ),
      ""
    )
  }

  test("an unsupported certificate or purpose is Alonzo's, put back as Conway's own error") {
    val inj = summon[Inject[AlonzoContextError[Conway], ContextError[Conway]]]
    val voter = xyz.kd5ujc.scarab.ledger.Voter.Pool(Bytes.fromArray(Array.fill[Byte](28)(4)))
    val ix = Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("index"))
    val voting = ConwayPlutusPurpose.ConwayVoting[AsIxItem](AsIxItem(ix, voter))
    val refused = ConwayPlutusPurpose.ConwayVoting[AsItem](AsItem(voter))
    assertEquals(
      inj.inject(AlonzoContextError.PlutusPurposeNotSupported[Conway](refused)),
      ConwayContextError.PlutusPurposeNotSupported[Conway](refused)
    )
    // the V1 and V2 purposes of Alonzo's translation refuse a vote with it
    assertEquals(
      AlonzoTxInfo.transPlutusPurpose[Language.PlutusV1, Conway](protVer(10), voting),
      Left(ConwayContextError.PlutusPurposeNotSupported[Conway](refused))
    )
    assertEquals(
      AlonzoTxInfo.transPlutusPurpose[Language.PlutusV2, Conway](protVer(10), voting),
      Left(ConwayContextError.PlutusPurposeNotSupported[Conway](refused))
    )
    // a DRep's registration, which the V1 and V2 forms lack
    val c = withBody(
      base,
      "a DRep registration",
      4 -> arr(arr(uint(16), keyCred(9), uint(500), CborValue.Null))
    )
    val (tx, _, _) = typed(c)
    val cert = t.bodyOf.certs[TxLevel.Top](t.body[TxLevel.Top](tx)) match
      case Vector(only) => only
      case other        => fail(s"certificates $other")
    assertEquals(
      inj.inject(AlonzoContextError.CertificateNotSupported[Conway](cert)),
      ConwayContextError.CertificateNotSupported[Conway](cert)
    )
    assertEquals(
      ConwayTxInfo.transTxCertV1V2[Conway](cert),
      Left(ConwayContextError.CertificateNotSupported[Conway](cert))
    )
    // the rest through Babbage's, as at f649f975
    val in = base.spent.keys.head
    assertEquals(
      inj.inject(AlonzoContextError.TranslationLogicMissingInput[Conway](in)),
      ConwayContextError.BabbageContextError[Conway](
        BabbageContextError.AlonzoContextError(
          AlonzoContextError.TranslationLogicMissingInput[Conway](in)
        )
      )
    )
  }

  test(
    "a language's class has its own instances alone, and a cost model is made only by its lookup"
  ) {
    // a Plutus V1 language that names Plutus V3, which would make V1's model of V3's parameters
    val forged = compileErrors(
      "new xyz.kd5ujc.scarab.ledger.era.PlutusLanguage[Language.PlutusV1] { def language = xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3; def plutusArgsToData(args: PlutusV1Args) = Vector.empty }"
    )
    assert(forged.contains("sealed"), forged)
    val built = compileErrors("new CostModel[Language.PlutusV1](Vector.empty[Long])")
    assert(built.contains("cannot be accessed"), built)
    assertEquals(
      compileErrors("CostModel.lookupCostModel[Language.PlutusV1](CostModels.empty)"),
      ""
    )
  }

  test("a runnable is equal by its binary and its hash, a script with its context by every field") {
    val binary = Plutus[Language.PlutusV1](Bytes(Vector(1.toByte)))
    val hash = PlutusRunnable.hashPlutusScript(binary)
    val runnable = PlutusRunnable.decodePlutusRunnable(Version.lit(10), binary)
    assertEquals(runnable, PlutusRunnable[Language.PlutusV1](binary, hash))
    assertEquals(runnable, PlutusRunnable.decodePlutusRunnable(Version.lit(9), binary))
    assertNotEquals(runnable, runnable.withScriptHash(Bytes(Vector.fill(28)(0.toByte))))
    assertNotEquals(
      runnable,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes(Vector(2.toByte))), hash)
    )
    val c = collectedV3
    val pwc: PlutusWithContext = eraCollect(c, eraParams(c))
      .fold(es => fail(s"${c.name}: $es"), _.headOption.getOrElse(fail(s"${c.name}: none")))
    given xyz.kd5ujc.scarab.ledger.era.PlutusLanguage[pwc.L] = pwc.plutusLanguage
    val same = PlutusWithContext[pwc.L](
      pwc.pwcProtocolVersion,
      pwc.pwcScript,
      pwc.pwcArgs,
      pwc.pwcExUnits,
      pwc.pwcCostModel
    )
    assertEquals(same, pwc)
    val otherVersion =
      Version
        .mkVersion(if pwc.pwcProtocolVersion.value == 9 then 10 else 9)
        .getOrElse(fail("version"))
    val otherModel = CostModel
      .lookupCostModel[pwc.L](
        CostModels
          .from(Map(pwc.pwcCostModel.cmLanguage.id -> Vector(BigInt(1))))
          .fold(e => fail(e.toString), identity)
      )
      .getOrElse(fail("no model"))
    val differing = Vector(
      PlutusWithContext[pwc.L](
        otherVersion,
        pwc.pwcScript,
        pwc.pwcArgs,
        pwc.pwcExUnits,
        pwc.pwcCostModel
      ),
      PlutusWithContext[pwc.L](
        pwc.pwcProtocolVersion,
        pwc.pwcScript.withScriptHash(Bytes(Vector.fill(28)(0.toByte))),
        pwc.pwcArgs,
        pwc.pwcExUnits,
        pwc.pwcCostModel
      ),
      PlutusWithContext[pwc.L](
        pwc.pwcProtocolVersion,
        pwc.pwcScript,
        pwc.pwcArgs,
        pwc.pwcExUnits + xyz.kd5ujc.scarab.plutus.ExUnits(1, 1),
        pwc.pwcCostModel
      ),
      PlutusWithContext[pwc.L](
        pwc.pwcProtocolVersion,
        pwc.pwcScript,
        pwc.pwcArgs,
        pwc.pwcExUnits,
        otherModel
      )
    )
    differing.foreach(d => assertNotEquals(d, pwc))
    // a pair of one language that differs in its arguments alone: Plutus V3 contexts of the same
    // transaction information whose redeemers differ
    val (_, _, lti) = typed(c)
    val txInfo = typedTxInfo(lti).fold(e => fail(s"${c.name}: $e"), identity)
    def args(redeemer: BigInt): PlutusV3Args =
      PlutusV3Args(
        PV3.ScriptContext(
          txInfo,
          PV1.Redeemer(PlutusData.I(redeemer)),
          PV3.ScriptInfo.MintingScript(PV1.CurrencySymbol(Bytes.empty))
        )
      )
    val v3Runnable =
      PlutusRunnable.decodePlutusRunnable(
        Version.lit(10),
        Plutus[Language.PlutusV3](Bytes(Vector(1.toByte)))
      )
    val v3Model = CostModel
      .lookupCostModel[Language.PlutusV3](
        CostModels.from(c.costModels).fold(e => fail(e.toString), identity)
      )
      .getOrElse(fail("no V3 model"))
    def withArgs(redeemer: BigInt) = PlutusWithContext[Language.PlutusV3](
      Version.lit(10),
      v3Runnable,
      args(redeemer),
      xyz.kd5ujc.scarab.plutus.ExUnits(1, 1),
      v3Model
    )
    assertEquals(withArgs(1), withArgs(1))
    assertNotEquals(withArgs(1), withArgs(2))
  }

  // ---------------------------------------------------------------------------------------------
  // The annotated transaction and its UTXOW predicates (`Alonzo.hs`, `Shelley/Rules/Utxow.hs`,
  // `Babbage/Rules/Utxow.hs`)

  private val decoder = TestLedger.env.decoder
  private val top = t.bodyOf.top

  private def annotated(
      c: Case,
      cache: Map[Bytes, SupportedPlutusRunnable[Conway]] = Map.empty
  ): (ConwayTx, UTxO[Conway], AlonzoStAnnTx[Conway]) =
    val (tx, utxo, _) = typed(c)
    (tx, utxo, AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](c.posix, eraParams(c), utxo, cache, tx))

  test("the annotated transaction holds what its members compute, on every Conway case") {
    (cases ++ recordedBlocks).foreach { c =>
      val pp = eraParams(c)
      val (tx, utxo, st) = annotated(c)
      val provided = u.getScriptsProvided[TxLevel.Top](utxo, tx)(using u.txOf.bodyOf.top)
      val needed =
        u.getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](tx))(using u.txOf.bodyOf.top)
      val (cache, used) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose[Conway](
        protVer(c.major),
        provided,
        needed,
        Map.empty
      )
      assert(t.txStAnnTx[TxLevel.Top](st)(using top) eq tx, c.name)
      assertEquals(t.cacheStAnnTx[TxLevel.Top](st)(using top), cache, c.name)
      assertEquals(u.scriptsProvidedStAnnTx[TxLevel.Top](st)(using u.txOf.bodyOf.top), provided)
      assertEquals(u.scriptsNeededStAnnTx[TxLevel.Top](st)(using u.txOf.bodyOf.top), needed)
      assertEquals(
        u.plutusLanguagesUsedStAnnTx[TxLevel.Top](st)(using u.txOf.bodyOf.top),
        used.map((_, r) => r.language).toSet,
        c.name
      )
      assertEquals(
        u.plutusScriptsWithContextStAnnTx[TxLevel.Top](st)(using u.txOf.bodyOf.top),
        eraCollect(c, pp),
        c.name
      )
    }
  }

  test("an annotated transaction keeps a cached runnable, and adds the scripts it provides") {
    val c = collectedV3
    val (_, _, plain) = annotated(c)
    val (hash, _) = plain.asatPlutusRunnableCache.head
    val earlier = Bytes(Vector.fill(28)(7.toByte))
    val cached = SupportedPlutusRunnable(
      EraPlutusTxInfo.conwayPlutusV1,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes.empty), hash)
    )
    val other = SupportedPlutusRunnable(
      EraPlutusTxInfo.conwayPlutusV1,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes.empty), earlier)
    )
    val (_, _, st) = annotated(c, Map(hash -> cached, earlier -> other))
    val kept = t.cacheStAnnTx[TxLevel.Top](st)(using top)
    assert(kept(hash) eq cached)
    assert(kept(earlier) eq other)
    assertEquals(kept.keySet, plain.asatPlutusRunnableCache.keySet + earlier)
  }

  /** `c` with the auxiliary data `aux` and the body's hash of it `auxHash`, each none to leave it
    * out.
    */
  private def withAuxiliary(
      c: Case,
      name: String,
      aux: Option[CborValue],
      auxHash: Option[Bytes]
  ): Case =
    val entries = cbor(c.parts.body).value match
      case CborValue.Map(kvs) =>
        kvs.filterNot((k, _) => k.value == uint(7)) ++
          auxHash.map(h => (node(uint(7)), node(CborValue.ByteString(h))))
      case other => fail(s"not a body: $other")
    val body = Cbor.encode(CborValue.Map(entries)).fold(e => fail(e.toString), identity)
    val auxiliary = aux.map(a => Cbor.encode(a).fold(e => fail(e.toString), identity))
    val parts = c.parts.copy(body = body, auxiliaryData = auxiliary)
    val flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(s"$name: $e"), identity)
    c.copy(name = name, parts = parts, flat = flat)

  private def sameMetadata(
      c: Case
  ): Either[xyz.kd5ujc.scarab.ledger.LedgerDefect, RuleTest[PredicateFailure]] =
    val (era, flat) = metadataBoth(c, decoder)
    assertEquals(era, flat, c.name)
    era

  /** The era's `validateMetadata` and the flat check of `c` with `scripts` as the decoder. */
  private def metadataBoth(c: Case, scripts: ScriptDecoder[Id]) =
    val (_, _, st) = annotated(c)
    val era =
      rules.ShelleyUtxow.validateMetadata[Conway, TxLevel.Top](scripts, eraParams(c), st)(using
        t,
        top
      )
    val flat = xyz.kd5ujc.scarab.ledger.TxChecks
      .auxiliaryChecks[Conway](flatParams(c), c.flat, TestLedger.env.copy(decoder = scripts))(using
        ShelleyBasedEra.SConway
      )
    (era, flat)

  test("validateMetadata gives the flat check's verdict, on every Conway case and each failure") {
    val corpus = (cases ++ recordedBlocks).map(sameMetadata)
    assert(corpus.forall(_.isRight))
    val c = collectedV3
    // metadata `{0: "x"}`, then the same with a Plutus V1 script that is not one (`h'00'`), in
    // Alonzo's form (tag 259)
    val metadata = CborValue.Map(
      Vector(
        node(uint(0)) -> node(CborValue.Map(Vector(node(uint(0)) -> node(CborValue.Text("x")))))
      )
    )
    val plain = CborValue.Tag(259, node(metadata))
    val malformed = CborValue.Tag(
      259,
      node(
        CborValue.Map(
          metadata match
            case CborValue.Map(kvs) =>
              kvs :+ (node(uint(2)) -> node(arr(CborValue.ByteString(Bytes(Vector(0.toByte))))))
            case _ => fail("not a map")
        )
      )
    )
    def hashOf(v: CborValue): Bytes =
      xyz.kd5ujc.scarab.crypto.Blake2b.hash256
        .hash(Cbor.encode(v).fold(e => fail(e.toString), identity))
    val wrong = Bytes(Vector.fill(32)(9.toByte))
    def failures(name: String, aux: Option[CborValue], hash: Option[Bytes]) =
      sameMetadata(withAuxiliary(c, name, aux, hash))
        .fold(e => fail(s"$name: $e"), RuleTest.failures)
        .map(_.toChain.toVector)
        .getOrElse(Vector.empty)
    import xyz.kd5ujc.scarab.ledger.UtxowFailure as F
    def u(f: F) = PredicateFailure.utxow(f)
    assertEquals(failures("well formed", Some(plain), Some(hashOf(plain))), Vector.empty)
    assertEquals(failures("none", None, None), Vector.empty)
    assertEquals(
      failures("no hash", Some(plain), None),
      Vector(u(F.MissingTxBodyMetadataHash(hashOf(plain))))
    )
    assertEquals(failures("no data", None, Some(wrong)), Vector(u(F.MissingTxMetadata(wrong))))
    assertEquals(
      failures("another hash", Some(plain), Some(wrong)),
      Vector(u(F.ConflictingMetadataHash(xyz.kd5ujc.scarab.ledger.Mismatch(wrong, hashOf(plain)))))
    )
    assertEquals(
      failures("a malformed script", Some(malformed), Some(hashOf(malformed))),
      Vector(u(F.InvalidMetadata))
    )
    assertEquals(
      failures("both", Some(malformed), Some(wrong)),
      Vector(
        u(F.ConflictingMetadataHash(xyz.kd5ujc.scarab.ledger.Mismatch(wrong, hashOf(malformed)))),
        u(F.InvalidMetadata)
      )
    )
  }

  private def sameWellFormed(
      c: Case,
      scripts: ScriptDecoder[Id] = decoder,
      major: Int = -1,
      cache: Map[Bytes, SupportedPlutusRunnable[Conway]] = Map.empty
  ): Either[xyz.kd5ujc.scarab.ledger.LedgerDefect, RuleTest[PredicateFailure]] =
    val (era, flat) = wellFormedBoth(c, scripts, major, cache)
    assertEquals(era, flat, c.name)
    era

  /** The era's `validateScriptsWellFormed` and the flat check of `c` with `scripts` as the decoder,
    * at `major` (the case's if none), the annotated transaction's cache `cache` added.
    */
  private def wellFormedBoth(
      c: Case,
      scripts: ScriptDecoder[Id],
      major: Int,
      cache: Map[Bytes, SupportedPlutusRunnable[Conway]]
  ) =
    val at = if major < 0 then c.major else major
    val flatPp = TestLedger.params(at).copy(costModels = c.costModels)
    val pp =
      PParams[Conway](ConwayPParams.fromStored(flatPp).fold(e => fail(s"${c.name}: $e"), identity))
    val (_, _, st) = annotated(c, cache)
    val era = rules.BabbageUtxow.validateScriptsWellFormed[Conway](scripts, pp, st)
    val flat = xyz.kd5ujc.scarab.ledger.TxChecks
      .scriptsWellFormed[Conway](flatPp, c.flat, TestLedger.env.copy(decoder = scripts))(using
        ShelleyBasedEra.SConway
      )
    (era, flat)

  test("validateScriptsWellFormed gives the flat check's verdict, on every Conway case") {
    val corpus = (cases ++ recordedBlocks).map(c => sameWellFormed(c))
    assert(corpus.forall(_.exists(_.isValid)))
    // a Plutus V1 witness script that is not one (`h'00'`)
    val c = collectedV3
    val parts = rawWitnesses(c, 3 -> arr(CborValue.ByteString(Bytes(Vector(0.toByte)))))
    val flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(e.toString), identity)
    val bad = c.copy(name = "a malformed witness script", parts = parts, flat = flat)
    val hash = xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(Bytes(Vector(1.toByte, 0.toByte)))
    assertEquals(
      sameWellFormed(bad).map(RuleTest.failures).map(_.map(_.toChain.toVector)),
      Right(
        Some(
          Vector(
            PredicateFailure.utxow(
              xyz.kd5ujc.scarab.ledger.UtxowFailure.MalformedScriptWitnesses(Set(hash))
            )
          )
        )
      )
    )
  }

  // ---------------------------------------------------------------------------------------------
  // The two predicates where the decoder gives no verdict, on crafted scripts and on the
  // reference's recorded verdict

  private def plutusHash(language: PlutusLanguage, binary: Bytes): Bytes =
    xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(Bytes(Vector((language.id + 1).toByte)) ++ binary)

  /** The decoder, giving no verdict for the scripts whose hashes are `hashes`. */
  private def unavailableFor(hashes: Set[Bytes]): ScriptDecoder[Id] = (language, major, script) =>
    if hashes(plutusHash(language, script)) then
      Left(ScriptDecodeFailure.Unavailable(BackendFailure.StackExhausted))
    else decoder.validate(language, major, script)

  private def unavailableAt(c: Case, hash: Bytes) =
    Left(
      xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable(
        c.flat.body.id,
        xyz.kd5ujc.scarab.ledger.ScriptLocation.Phase1(hash),
        EvalFailure.Unsupported(BackendFailure.StackExhausted)
      )
    )

  private def failuresOf(test: RuleTest[PredicateFailure]): Vector[PredicateFailure] =
    RuleTest.failures(test).map(_.toChain.toVector).getOrElse(Vector.empty)

  private def utxow(f: xyz.kd5ujc.scarab.ledger.UtxowFailure) = PredicateFailure.utxow(f)

  /** Alonzo's auxiliary data (tag 259) with metadata `{0: "x"}` and `scripts`, each language's list
    * under its key (2 to 4), in the order given.
    */
  private def alonzoAux(scripts: Vector[(PlutusLanguage, Vector[Bytes])]): CborValue =
    val metadata = node(uint(0)) -> node(
      CborValue.Map(Vector(node(uint(0)) -> node(CborValue.Text("x"))))
    )
    CborValue.Tag(
      259,
      node(
        CborValue.Map(
          metadata +: scripts.map((l, bs) =>
            node(uint(l.id + 2)) -> node(CborValue.Arr(bs.map(b => node(CborValue.ByteString(b)))))
          )
        )
      )
    )

  private def encoded(v: CborValue): Bytes = Cbor.encode(v).fold(e => fail(e.toString), identity)

  test(
    "validateMetadata: data without its hash fails so before a script is read, as the reference"
  ) {
    // the flat check reads the auxiliary scripts first, so where the decoder gives no verdict for
    // one it has the defect; the reference's order gives the predicate's failure
    val c = collectedV3
    val script = Bytes(Vector(0x41.toByte, 0.toByte))
    val aux = alonzoAux(Vector(PlutusLanguage.V1 -> Vector(script)))
    val noHash = withAuxiliary(c, "no hash", Some(aux), None)
    val hash = plutusHash(PlutusLanguage.V1, script)
    val (era, flat) = metadataBoth(noHash, unavailableFor(Set(hash)))
    assertEquals(
      era.map(failuresOf),
      Right(
        Vector(
          utxow(
            xyz.kd5ujc.scarab.ledger.UtxowFailure.MissingTxBodyMetadataHash(
              xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(encoded(aux))
            )
          )
        )
      )
    )
    assertEquals(flat, unavailableAt(noHash, hash))
  }

  /** A binary of `first` and one of `second` (two-byte programs that are not valid ones), the
    * first's hash the larger, so that the order of their hashes picks the second.
    */
  private def largerFirst(first: PlutusLanguage, second: PlutusLanguage): (Bytes, Bytes) =
    val candidates = (1 to 16).toVector.map(i => Bytes(Vector(0x41.toByte, i.toByte)))
    (for
      a <- candidates
      b <- candidates
      if a != b && plutusHash(first, a).compareTo(plutusHash(second, b)) > 0
    yield (a, b)).headOption.getOrElse(fail("no pair"))

  test("the first script the decoder gives no verdict for is the flat check's, in arrival order") {
    val c = collectedV3
    // auxiliary data whose Plutus V2 field is encoded before its Plutus V1 field: the V2 script
    // arrives first, though the languages' order and the hashes' order both pick the V1 one
    val (auxV2, auxV1) = largerFirst(PlutusLanguage.V2, PlutusLanguage.V1)
    val aux = alonzoAux(
      Vector(PlutusLanguage.V2 -> Vector(auxV2), PlutusLanguage.V1 -> Vector(auxV1))
    )
    val withAux = withAuxiliary(
      c,
      "a Plutus V2 field before a Plutus V1 field",
      Some(aux),
      Some(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(encoded(aux)))
    )
    val firstAux = plutusHash(PlutusLanguage.V2, auxV2)
    val (eraAux, flatAux) =
      metadataBoth(withAux, unavailableFor(Set(firstAux, plutusHash(PlutusLanguage.V1, auxV1))))
    assertEquals(eraAux, flatAux)
    assertEquals(eraAux, unavailableAt(withAux, firstAux))
    // a witness set whose Plutus V2 scripts are encoded before its Plutus V1 scripts: both
    // decoders read the languages in their fixed order, V1 first, though the encoding and the
    // hashes' order both pick the V2 one
    val (witV1, witV2) = largerFirst(PlutusLanguage.V1, PlutusLanguage.V2)
    val parts = rawWitnesses(
      c,
      6 -> arr(CborValue.ByteString(witV2)),
      3 -> arr(CborValue.ByteString(witV1))
    )
    val withWits = c.copy(
      name = "Plutus V2 witnesses before Plutus V1 ones",
      parts = parts,
      flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(e.toString), identity)
    )
    val firstWit = plutusHash(PlutusLanguage.V1, witV1)
    assertEquals(
      sameWellFormed(withWits, unavailableFor(Set(firstWit, plutusHash(PlutusLanguage.V2, witV2)))),
      unavailableAt(withWits, firstWit)
    )
    // an ordinary output's reference script and the collateral return's: the output's first,
    // though the hashes' order picks the collateral return's
    val (output, collateral) = largerFirst(PlutusLanguage.V2, PlutusLanguage.V2)
    val withBoth = withBody(
      withOutputs(c, "an output's", Vector(outputWithScript(c, 2, output))),
      "an output's and the collateral return's reference scripts",
      16 -> outputWithScript(c, 2, collateral)
    )
    val firstRef = plutusHash(PlutusLanguage.V2, output)
    assertEquals(
      sameWellFormed(
        withBoth,
        unavailableFor(Set(firstRef, plutusHash(PlutusLanguage.V2, collateral)))
      ),
      unavailableAt(withBoth, firstRef)
    )
  }

  test("within one list, and among the outputs, the first script to arrive is the one named") {
    val c = collectedV3
    // two auxiliary scripts of one language, the first of the larger hash
    val (aux1, aux2) = largerFirst(PlutusLanguage.V1, PlutusLanguage.V1)
    val aux = alonzoAux(Vector(PlutusLanguage.V1 -> Vector(aux1, aux2)))
    val withAux = withAuxiliary(
      c,
      "two auxiliary scripts of one language",
      Some(aux),
      Some(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(encoded(aux)))
    )
    val auxFirst = plutusHash(PlutusLanguage.V1, aux1)
    val (eraAux, flatAux) =
      metadataBoth(withAux, unavailableFor(Set(auxFirst, plutusHash(PlutusLanguage.V1, aux2))))
    assertEquals(eraAux, flatAux)
    assertEquals(eraAux, unavailableAt(withAux, auxFirst))
    // two witness scripts of one language
    val (wit1, wit2) = largerFirst(PlutusLanguage.V2, PlutusLanguage.V2)
    val parts =
      rawWitnesses(c, 6 -> arr(CborValue.ByteString(wit1), CborValue.ByteString(wit2)))
    val withWits = c.copy(
      name = "two witness scripts of one language",
      parts = parts,
      flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(e.toString), identity)
    )
    val witFirst = plutusHash(PlutusLanguage.V2, wit1)
    assertEquals(
      sameWellFormed(withWits, unavailableFor(Set(witFirst, plutusHash(PlutusLanguage.V2, wit2)))),
      unavailableAt(withWits, witFirst)
    )
    // two ordinary outputs with reference scripts
    val (out1, out2) = largerFirst(PlutusLanguage.V2, PlutusLanguage.V2)
    val withOuts = withOutputs(
      c,
      "two outputs with reference scripts",
      Vector(outputWithScript(c, 2, out1), outputWithScript(c, 2, out2))
    )
    val outFirst = plutusHash(PlutusLanguage.V2, out1)
    assertEquals(
      sameWellFormed(withOuts, unavailableFor(Set(outFirst, plutusHash(PlutusLanguage.V2, out2)))),
      unavailableAt(withOuts, outFirst)
    )
  }

  /** The outputs of `c`'s body. */
  private def bodyOutputs(c: Case): Vector[Node] =
    cbor(c.parts.body).value match
      case CborValue.Map(kvs) =>
        kvs.collectFirst { case (k, v) if k.value == uint(1) => v.value } match
          case Some(CborValue.Arr(outs)) => outs
          case other                     => fail(s"not outputs: $other")
      case other => fail(s"not a body: $other")

  /** An output at the address of `c` 's first output, of two ada, with a reference script of the
    * language `language` (1 to 3 for Plutus V1 to V3) whose binary is `binary`.
    */
  private def outputWithScript(c: Case, language: Int, binary: Bytes): CborValue =
    val address = bodyOutputs(c).headOption.map(_.value) match
      case Some(CborValue.Map(fs)) =>
        fs.collectFirst { case (k, v) if k.value == uint(0) => v.value }
          .getOrElse(fail("no address"))
      case Some(CborValue.Arr(xs)) => xs.headOption.map(_.value).getOrElse(fail("no address"))
      case other                   => fail(s"not an output: $other")
    val script = encoded(arr(uint(language), CborValue.ByteString(binary)))
    CborValue.Map(
      Vector(
        node(uint(0)) -> node(address),
        node(uint(1)) -> node(uint(2000000)),
        node(uint(3)) -> node(CborValue.Tag(24, node(CborValue.ByteString(script))))
      )
    )

  private def withOutputs(c: Case, name: String, extra: Vector[CborValue]): Case =
    withBody(c, name, 1 -> CborValue.Arr(bodyOutputs(c) ++ extra.map(node)))

  test(
    "validateScriptsWellFormed: malformed reference scripts, alone, on the collateral return, with a witness"
  ) {
    val c = collectedV3
    val bad = Bytes(Vector(0.toByte))
    val badV2 = plutusHash(PlutusLanguage.V2, bad)
    val badV1 = plutusHash(PlutusLanguage.V1, bad)
    import xyz.kd5ujc.scarab.ledger.UtxowFailure as F
    def failures(x: Case) =
      sameWellFormed(x).fold(e => fail(s"${x.name}: $e"), failuresOf)
    // on an ordinary output
    assertEquals(
      failures(withOutputs(c, "an output's", Vector(outputWithScript(c, 2, bad)))),
      Vector(utxow(F.MalformedReferenceScripts(Set(badV2))))
    )
    // on the collateral return
    assertEquals(
      failures(withBody(c, "the collateral return's", 16 -> outputWithScript(c, 2, bad))),
      Vector(utxow(F.MalformedReferenceScripts(Set(badV2))))
    )
    // with a malformed witness script: the witnesses' failure, then the reference scripts'
    val both = withOutputs(c, "both", Vector(outputWithScript(c, 2, bad)))
    val bothParts = rawWitnesses(both, 3 -> arr(CborValue.ByteString(bad)))
    val withBoth = both.copy(
      parts = bothParts,
      flat = FlatTx.fromBlock(bothParts, Era.Conway).fold(e => fail(e.toString), identity)
    )
    assertEquals(
      failures(withBoth),
      Vector(
        utxow(F.MalformedScriptWitnesses(Set(badV1))),
        utxow(F.MalformedReferenceScripts(Set(badV2)))
      )
    )
  }

  /** A case one of whose witness scripts is a Plutus V3 script, with the hashes of those scripts.
    */
  private lazy val v3Witnesses: (Case, Set[Bytes]) =
    cases.iterator
      .map { c =>
        val (tx, _, _) = typed(c)
        val scripts = t.witsOf.scriptTxWits(t.wits[TxLevel.Top](tx)(using top))
        c -> scripts.collect {
          case (h, s)
              if EraScript.conway
                .toPlutusScript(s)
                .exists(AlonzoScripts.plutusScriptLanguage[Conway](_) == PlutusLanguage.V3) =>
            h
        }.toSet
      }
      .find(_._2.nonEmpty)
      .getOrElse(fail("no case has a Plutus V3 witness script"))

  test(
    "a Plutus V3 script is malformed at protocol 8 and well formed from 9, as the flat check says"
  ) {
    val (c, v3) = v3Witnesses
    // the annotation built at the major it is validated at, as the reference's cache assumes
    val at8 = sameWellFormed(c.copy(major = 8)).fold(e => fail(e.toString), failuresOf)
    assertEquals(
      at8,
      Vector(utxow(xyz.kd5ujc.scarab.ledger.UtxowFailure.MalformedScriptWitnesses(v3)))
    )
    assertEquals(sameWellFormed(c.copy(major = 9)).map(failuresOf), Right(Vector.empty))
  }

  test("a cache hit is validated by the cached runnable's binary") {
    val (c, v3) = v3Witnesses
    val hash = v3.head
    val other = Bytes(Vector(0x42.toByte, 7.toByte))
    // the decoder gives no verdict for `other` in Plutus V3 alone
    val scripts: ScriptDecoder[Id] = (language, major, script) =>
      if language == PlutusLanguage.V3 && script == other then
        Left(ScriptDecodeFailure.Unavailable(BackendFailure.StackExhausted))
      else decoder.validate(language, major, script)
    val cached = SupportedPlutusRunnable(
      EraPlutusTxInfo.conwayPlutusV3,
      PlutusRunnable[Language.PlutusV3](Plutus(other), hash)
    )
    val (era, _) = wellFormedBoth(c, scripts, -1, Map(hash -> cached))
    assertEquals(era, unavailableAt(c, hash))
    val (uncached, _) = wellFormedBoth(c, scripts, -1, Map.empty)
    assertEquals(uncached.map(failuresOf), Right(Vector.empty))
  }

  test("the reference's recorded verdict: a malformed witness and reference script, both refused") {
    val line = Files
      .readAllLines(Path.of("fixtures/reference/refusals/conway-devnet/cases/cases.jsonl"))
      .toArray
      .toVector
      .map(_.toString)
      .map(l => parse(l).fold(e => fail(e.message), identity))
      .find(field[String](_, "label") == "utxow-malformed-witness-and-reference-script")
      .getOrElse(fail("no recorded case"))
    // the item is the era's index and the transaction, wrapped (`[6, 24(bytes)]`)
    val txBytes = cbor(hex(field[String](line, "item"))).value match
      case CborValue.Arr(Vector(_, wrapped)) =>
        wrapped.value match
          case CborValue.Tag(g, inner) if g == 24 =>
            inner.value match
              case CborValue.ByteString(b) => b
              case other                   => fail(s"not bytes: $other")
          case other => fail(s"not wrapped: $other")
      case other => fail(s"not an item: $other")
    val parts = cbor(txBytes).value match
      case CborValue.Arr(Vector(body, witnesses, flag, auxiliary)) =>
        Block.Transaction(
          body.original,
          witnesses.original,
          Option.unless(auxiliary.value == CborValue.Null)(auxiliary.original),
          flag.value == CborValue.Bool(true)
        )
      case other => fail(s"not a transaction: $other")
    val flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(e.toString), identity)
    val c = Case(
      "recorded malformed scripts",
      parts,
      flat,
      Map.empty,
      Map.empty,
      10,
      Map.empty,
      slot => Right(slot),
      Json.Null
    )
    // the reference's failures, each `[1, [tag, 258([hash])]]`: their tags and hashes
    val recorded = cbor(hex(field[String](line, "reason"))).value match
      case CborValue.Arr(Vector(one)) =>
        one.value match
          case CborValue.Arr(Vector(_, failures)) =>
            failures.value match
              case CborValue.Arr(fs) =>
                fs.map(_.value).collect { case CborValue.Arr(Vector(_, f)) =>
                  f.value match
                    case CborValue.Arr(Vector(tag, set)) =>
                      val hashes = set.value match
                        case CborValue.Tag(_, list) =>
                          list.value match
                            case CborValue.Arr(hs) =>
                              hs.map(_.value).collect { case CborValue.ByteString(h) => h }.toSet
                            case other => fail(s"not hashes: $other")
                        case other => fail(s"not a set: $other")
                      tag.value -> hashes
                    case other => fail(s"not a failure: $other")
                }
              case other => fail(s"not failures: $other")
          case other => fail(s"not an error: $other")
      case other => fail(s"not a reason: $other")
    val byTag = recorded.toMap
    import xyz.kd5ujc.scarab.ledger.UtxowFailure as F
    assertEquals(
      sameWellFormed(c).map(failuresOf),
      Right(
        Vector(
          utxow(F.MalformedScriptWitnesses(byTag.getOrElse(uint(16), fail("no 16")))),
          utxow(F.MalformedReferenceScripts(byTag.getOrElse(uint(17), fail("no 17"))))
        )
      )
    )
  }

  test("the reference's 210 Conway observations of auxiliary data, on the era types") {
    // `fixtures/reference/auxiliary-scripts`: the reference's decoder and `validateTxAuxData` at
    // ConwayEra, protocols 9 to 11; each input is put in a Conway transaction with its hash
    val rows =
      parse(Files.readString(Path.of("fixtures/reference/auxiliary-scripts/auxiliary.json")))
        .fold(e => fail(e.message), identity)
        .asArray
        .getOrElse(fail("not an array"))
        .filter(field[String](_, "era") == "Conway")
    assertEquals(rows.size, 210)
    val base = collectedV3
    val outcomes = rows.map { row =>
      val name = field[String](row, "name")
      val major = field[Int](row, "protocolMajor")
      val bytes = hex(field[String](row, "auxiliary"))
      val oracle = field[Json](row, "oracle")
      val decoded = field[Boolean](oracle, "decoded")
      val hash = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(bytes)
      val entries = cbor(base.parts.body).value match
        case CborValue.Map(kvs) =>
          kvs.filterNot((k, _) => k.value == uint(7)) :+ (node(uint(7)) -> node(
            CborValue.ByteString(hash)
          ))
        case other => fail(s"not a body: $other")
      val parts = base.parts.copy(
        body = encoded(CborValue.Map(entries)),
        auxiliaryData = Some(bytes)
      )
      assertEquals(ConwayTx.decode(parts, StateLayout.Node11_1).isRight, decoded, name)
      if decoded then
        val c = base.copy(
          name = name,
          parts = parts,
          flat = FlatTx.fromBlock(parts, Era.Conway).fold(e => fail(s"$name: $e"), identity),
          major = major
        )
        val (tx, _, st) = annotated(c)
        val aux = t.auxData[TxLevel.Top](tx)(using top).getOrElse(fail(s"$name: no data"))
        val valid = field[Boolean](oracle, "valid")
        assertEquals(EraTxAuxData.hashTxAuxData[Conway](aux)(using t.auxDataOf), hash, name)
        assertEquals(
          t.auxDataOf.validateTxAuxData(decoder, Map.empty, protVer(major), aux),
          Right(valid),
          name
        )
        assertEquals(
          rules.ShelleyUtxow
            .validateMetadata[Conway, TxLevel.Top](decoder, eraParams(c), st)(using t, top)
            .map(failuresOf),
          Right(
            if valid then Vector.empty
            else Vector(utxow(xyz.kd5ujc.scarab.ledger.UtxowFailure.InvalidMetadata))
          ),
          name
        )
        Some(valid)
      else None
    }
    // every outcome the corpus has: refused by the decoder, valid, and with an invalid script
    assertEquals(outcomes.toSet, Set(None, Some(true), Some(false)))
  }

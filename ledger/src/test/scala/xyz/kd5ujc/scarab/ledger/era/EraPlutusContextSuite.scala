// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  PlutusCase,
  Phase2,
  ScriptContexts,
  TestLedger,
  TranslationError,
  Tx as FlatTx,
  TxIn,
  TxOut as FlatTxOut,
  Unsigned,
  testByteStrings
}
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayContextError,
  ConwayPlutusPurpose,
  ConwayTx,
  ConwayTxInfo,
  ConwayTxOut
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage}
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
  private def typed(c: Case, major: Int = -1): (ConwayTx, UTxO[Conway], LedgerTxInfo[Conway]) =
    val tx = ConwayTx.decode(c.parts).fold(e => fail(s"${c.name}: $e"), identity)
    val utxo = UTxO[Conway]((c.spent ++ c.references).map { (in, out) =>
      in -> ConwayTxOut.fromStored(out).fold(e => fail(s"${c.name}: $e"), identity)
    })
    val pv = protVer(if major < 0 then c.major else major)
    (tx, utxo, LedgerTxInfo(pv, c.posix, utxo, tx))

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
            case AlonzoContextError.Undecodable(site) => TranslationError.Undecodable(site)
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
      lti: LedgerTxInfo[Conway]
  ): Either[ConwayContextError[Conway], PV3.TxInfo] =
    v3.toPlutusTxInfo(lti)
      .unPlutusTxInfoResult
      .flatMap(_(ConwayPlutusPurpose.ConwaySpending(AsPurpose())))

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
    val (tx, utxo, lti) = typed(c)
    val body = t.body[TxLevel.Top](tx)
    val purpose = t.bodyOf
      .redeemerPointerInverse[TxLevel.Top](body, pointer)
      .getOrElse(fail(s"${c.name}: $pointer names nothing"))
    val redeemer = t.witsOf.rdmrsTxWits(t.wits[TxLevel.Top](tx)).entries(pointer)._1
    val datum = u.getSpendingDatum[TxLevel.Top](
      utxo,
      tx,
      ConwayPlutusPurpose.hoistPlutusPurpose[
        AsIxItem,
        AsItem
      ]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a))(
        purpose
      )
    )(using u.txOf.bodyOf.top)
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
      a <- v3.toPlutusArgs(lti.ltiProtVer, info, purpose, datum, redeemer)
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
    val range = ConwayTxInfo.transValidityInterval[Conway, ConwayContextError[Conway]](
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
    "a redeemer and a spending datum that do not read as data: the redeemer's, as the flat path's"
  ) {
    val (tx, _, lti) = typed(base)
    val body = t.body[TxLevel.Top](tx)
    val redeemers = t.witsOf.rdmrsTxWits(t.wits[TxLevel.Top](tx)).entries
    val (pointer, (redeemer, _)) = redeemers.head
    val purpose = t.bodyOf
      .redeemerPointerInverse[TxLevel.Top](body, pointer)
      .getOrElse(fail(s"$pointer names nothing"))
    val info = PlutusContext
      .mkPlutusTxInfoFromResult[Language.PlutusV3, Conway](
        ConwayPlutusPurpose.hoistPlutusPurpose[
          AsIxItem,
          AsPurpose
        ]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsPurpose(a))(purpose),
        v3.toPlutusTxInfo(lti)
      )
      .fold(e => fail(e.toString), identity)
    val bad = Bytes(Vector(0xff.toByte))
    def args(datum: Bytes, rdmr: Bytes): Either[TranslationError, Unit] =
      ConwayTxInfo
        .toPlutusV3Args(lti.ltiProtVer, info, purpose, Some(datum), rdmr)(using v3)
        .left
        .map(asFlat)
        .map(_ => ())
    assertEquals(
      args(bad, bad),
      Left(TranslationError.Undecodable(xyz.kd5ujc.scarab.ledger.DataSite.Redeemer))
    )
    assertEquals(
      args(bad, redeemer),
      Left(TranslationError.Undecodable(xyz.kd5ujc.scarab.ledger.DataSite.Datum))
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
    "a redeemer that does not read as data: both decoders refuse it, so no translation reaches its Undecodable"
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
    assertEquals(ConwayTx.decode(parts).map(_ => ()), Left(EraTxError.Decode(refused)))
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

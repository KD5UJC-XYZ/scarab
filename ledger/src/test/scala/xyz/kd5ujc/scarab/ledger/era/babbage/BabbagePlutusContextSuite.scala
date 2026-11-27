// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ReferenceRequests,
  CollectError as FlatCollectError,
  Datum as FlatDatum,
  GenDelegPair,
  Phase2,
  PlutusCase,
  Purpose,
  Redeemer as FlatRedeemer,
  ScriptContexts,
  ScriptKind,
  TestLedger,
  TranslationError,
  Tx as FlatTx,
  TxChecks,
  TxIn,
  TxOut as FlatTxOut,
  Unsigned,
  Utxo,
  testByteStrings
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.StAnnTx as FlatStAnnTx
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Empty,
  Genesis,
  KeyHash,
  ShelleyBasedEra,
  ShelleyCertState
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusData, PlutusLanguage}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Babbage's Plutus side on the era types (`EraPlutusTxInfo 'PlutusV1` and `'PlutusV2 BabbageEra`,
  * `EraPlutusContext BabbageEra`, `EraUTxO` and `AlonzoEraUTxO BabbageEra`, `mkStAnnTx`) against
  * the flat path the node runs for Babbage (`ScriptContexts`, `Phase2.collect`, the flat `StAnnTx`
  * and `TxChecks`) and, where it recorded them, the reference's contexts byte for byte.
  *
  * The cases are every Babbage transaction the repository holds with what it spends: the Babbage
  * rows of the recorded contexts (`fixtures/reference/recorded-contexts`) and of the context matrix
  * (`fixtures/reference/context-matrix`, whose retained rows are the v1-context corpus's Babbage
  * transactions), each with the outputs it spends and references; and, with one stand-in output (a
  * key's at a Shelley address) for each input and reference input, the Babbage transactions of the
  * Plutus admission corpora, of the refusal corpora and of the recorded blocks. A stand-in case
  * shows no spending script or datum of its own; `BabbagePlutusContextRefusalsSuite` (node) reads
  * the refusal corpora against the UTxO set each was refused on.
  */
class BabbagePlutusContextSuite extends munit.FunSuite:
  private val t = EraTx.babbage
  private given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
  private val u = EraUTxO.babbage
  private val s = EraScript.babbage
  private val ctx = EraPlutusContext.babbage
  private given ShelleyBasedEra[Babbage] = ShelleyBasedEra.SBabbage

  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: ${e.getMessage}"), identity)

  private def hex(text: String): Bytes = Bytes.fromHex(text).fold(e => fail(e.toString), identity)

  private def cbor(bytes: Bytes): Node = Cbor.decode(bytes).fold(e => fail(e.toString), identity)

  private def json(path: String): Json =
    parse(Files.readString(Path.of(path))).fold(e => fail(e.message), identity)

  private def babbageRows(path: String): Vector[Json] =
    json(path).asArray
      .getOrElse(fail(s"$path is not an array"))
      .filter(field[String](_, "era") == "Babbage")

  /** One Babbage case: the transaction read both ways, what it spends, references and gives as
    * collateral, its version, cost models and clock, the reference's oracle, and whether its
    * outputs are stand-ins.
    */
  private final case class Case(
      name: String,
      era: BabbageTx,
      flat: FlatTx,
      spent: Map[TxIn, FlatTxOut],
      references: Map[TxIn, FlatTxOut],
      collateral: Map[TxIn, FlatTxOut],
      major: Int,
      costModels: Map[Int, Vector[BigInt]],
      posix: BigInt => Either[TranslationError, BigInt],
      oracle: Json,
      standIn: Boolean
  ):
    def known: Map[TxIn, FlatTxOut] = spent ++ references ++ collateral

  private def readParts(parts: Block.Transaction, what: String): (BabbageTx, FlatTx) =
    (
      BabbageTx.decode(parts, StateLayout.Node11_1).fold(e => fail(s"$what: $e"), identity),
      FlatTx.fromBlock(parts, Era.Babbage).fold(e => fail(s"$what: $e"), identity)
    )

  private def readWhole(raw: Bytes, what: String): (BabbageTx, FlatTx) =
    (
      BabbageTx.fromEnvelope(raw, StateLayout.Node11_1).fold(e => fail(s"$what: $e"), identity),
      FlatTx.fromEnvelope(raw, Era.Babbage).fold(e => fail(s"$what: $e"), identity)
    )

  private def recorded(row: Json): Case =
    val c = PlutusCase.load(Path.of(field[String](field[Json](row, "provenance"), "sourcePath")))
    val w = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val name = field[String](row, "name")
    val (era, flat) =
      readParts(Block.Transaction(c.tx.body.raw, w, c.tx.auxiliary, c.tx.isValid), name)
    Case(
      name,
      era,
      flat,
      c.spent,
      c.references,
      c.collateral,
      c.protocolMajor,
      c.costModels,
      c.posix,
      field[Json](row, "oracle"),
      standIn = false
    )

  private def derived(row: Json): Case =
    val name = "matrix/" + field[String](row, "name")
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
    val (era, flat) = readParts(parts, name)
    val known = field[Vector[Json]](row, "utxo").map { entry =>
      TxIn.decode(cbor(hex(field[String](entry, "input")))).fold(e => fail(e.toString), identity) ->
        FlatTxOut
          .fromBytes(hex(field[String](entry, "output")))
          .fold(e => fail(e.toString), identity)
    }.toMap
    val start = BigInt(field[Int](row, "systemStartSeconds")) * 1000
    val length = BigInt(field[Int](row, "slotLengthMilliseconds"))
    Case(
      name,
      era,
      flat,
      known.filter((in, _) => flat.body.inputs.contains(in)),
      known.filter((in, _) => flat.body.referenceInputs.contains(in)),
      known.filter((in, _) => flat.body.collateral.contains(in)),
      field[Int](row, "protocolMajor"),
      field[Map[String, Vector[String]]](row, "costModels")
        .map((language, values) => language.toInt -> values.map(BigInt(_))),
      slot => Right(start + slot * length),
      field[Json](row, "oracle"),
      standIn = false
    )

  private lazy val oracleCases: Vector[Case] =
    babbageRows("fixtures/reference/recorded-contexts/contexts.json").map(recorded) ++
      babbageRows("fixtures/reference/context-matrix/matrix.json").map(derived)

  // ---- the transactions without the outputs they spend

  private def blockTxs: Vector[(String, Block.Transaction)] =
    Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/chain-dep/blocks.hex",
      "fixtures/preview/babbage-mint.hex",
      "fixtures/preview/empty-redeemers.hex",
      "fixtures/preview/deeply-nested-datum.hex"
    ).flatMap(f =>
      EraCorpus
        .shelleyBlocks(Vector(f))
        .filter(_.era == Era.Babbage)
        .flatMap(_.transactions)
        .zipWithIndex
        .map((tx, i) => s"$f transaction $i" -> tx)
    )

  private def refusalTxs: Vector[(String, Bytes)] =
    Files
      .list(Path.of("fixtures/reference/refusals"))
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.startsWith("babbage"))
      .toVector
      .sorted
      .flatMap { d =>
        Files
          .readAllLines(Path.of(s"fixtures/reference/refusals/$d/cases/cases.jsonl"))
          .asScala
          .toVector
          .filter(_.trim.nonEmpty)
          .zipWithIndex
          .map { (line, i) =>
            val j = parse(line).fold(e => fail(e.message), identity)
            val raw = cbor(hex(field[String](j, "item"))).value match
              case CborValue.Arr(Vector(_, tagged)) =>
                tagged.value match
                  case CborValue.Tag(_, inner) =>
                    inner.value match
                      case CborValue.ByteString(b) => b
                      case other                   => fail(s"not a byte string: $other")
                  case other => fail(s"not tagged: $other")
              case other => fail(s"not a submitted transaction: $other")
            s"$d case $i" -> raw
          }
      }

  private def admissionTxs: Vector[(String, Bytes)] =
    Vector("admissions", "rebased-admissions")
      .flatMap(f =>
        field[Vector[Json]](json(s"fixtures/reference/plutus-admission/$f.json"), "observations")
          .filter(j => field[String](j, "era") == "babbage")
          .map(j => hex(field[String](j, "tx")))
      )
      .distinct
      .zipWithIndex
      .map((raw, i) => s"admission $i" -> raw)

  /** The stand-in output of every input and reference input: a key's at a Shelley address, with no
    * datum and no script.
    */
  private lazy val standIn: FlatTxOut =
    oracleCases.iterator
      .flatMap(_.flat.body.outputs)
      .find(o =>
        o.address.kind match
          case xyz.kd5ujc.scarab.ledger.Address.Kind
                .Shelley(_, xyz.kd5ujc.scarab.ledger.Credential.Key(_), _, _) =>
            o.datum == FlatDatum.NoDatum && o.scriptRef.isEmpty
          case _ => false
      )
      .getOrElse(fail("no stand-in output"))

  private def withStandIns(name: String, read: (BabbageTx, FlatTx)): Case =
    val (era, flat) = read
    Case(
      name,
      era,
      flat,
      flat.body.inputs.map(_ -> standIn).toMap,
      flat.body.referenceInputs.map(_ -> standIn).toMap,
      flat.body.collateral.map(_ -> standIn).toMap,
      8,
      standInCostModels,
      slot => Right(slot * 1000),
      Json.Null,
      standIn = true
    )

  /** The cost models of a stand-in case: Plutus V1's and V2's of the matrix rows at version 8, each
    * of which names one, so that its scripts' contexts are built.
    */
  private lazy val standInCostModels: Map[Int, Vector[BigInt]] =
    val models = oracleCases
      .filter(c => c.name.startsWith("matrix/") && c.major == 8)
      .flatMap(_.costModels)
      .toMap
    assertEquals(models.keySet, Set(0, 1))
    models

  private lazy val standInCases: Vector[Case] =
    blockTxs.map((n, p) => withStandIns(n, readParts(p, n))) ++
      refusalTxs.map((n, raw) => withStandIns(n, readWhole(raw, n))) ++
      admissionTxs.map((n, raw) => withStandIns(n, readWhole(raw, n)))

  private lazy val cases: Vector[Case] = oracleCases ++ standInCases

  test("the corpus: every Babbage transaction the repository holds, with or without its outputs") {
    assertEquals(cases.size, cases.map(_.name).distinct.size)
    // 26 rows of the recorded contexts and 54 of the matrix (34 collected, 20 refused), 13 block
    // transactions, 32 of the refusal corpora and 32 of the admission corpora
    assertEquals(
      (oracleCases.size, oracleCases.count(c => field[Boolean](c.oracle, "collected"))),
      (26 + 54, 26 + 34)
    )
    assertEquals(standInCases.size, 13 + 32 + 32)
    // the matrix retains every Babbage transaction of the v1-context corpus whole
    val v1Context = Vector("babbage-pv7", "babbage-pv8").flatMap(f =>
      json(s"fixtures/reference/v1-context/$f.json").asArray
        .getOrElse(fail(f))
        .map(field[String](_, "name"))
    )
    val retained = babbageRows("fixtures/reference/context-matrix/matrix.json").collect {
      case r
          if field[String](field[Json](r, "provenance"), "kind") ==
            "retained-original-reference-transaction" =>
        field[String](field[Json](r, "provenance"), "sourceCase")
    }
    assertEquals(retained.sorted, v1Context.sorted)
    assertEquals(v1Context.size, 22)
  }

  // ---------------------------------------------------------------------------------------------
  // The transaction's information

  private def protVer(major: Int): ProtVer =
    ProtVer(
      Version.mkVersion(major).getOrElse(fail(s"version $major")),
      Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
    )

  private def utxoOf(c: Case): UTxO[Babbage] =
    BabbageEraUTxO.fromStored(c.known).fold(e => fail(s"${c.name}: $e"), identity)

  private def lti(c: Case, major: Int): LedgerTxInfo[TxLevel.Top, Babbage] =
    LedgerTxInfo[TxLevel.Top, Babbage](
      protVer(major),
      c.posix,
      utxoOf(c),
      c.era,
      Nil,
      LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
    )

  private def env(c: Case, major: Int): ScriptContexts.Environment =
    ScriptContexts.Environment(ShelleyBasedEra.SBabbage, major, c.spent, c.references, c.posix)

  /** The flat failure a typed one is, case by case. */
  private def asFlat(e: BabbageContextError[Babbage]): TranslationError = e match
    case BabbageContextError.AlonzoContextError(a) =>
      a match
        case AlonzoContextError.TranslationLogicMissingInput(in) =>
          TranslationError.UnresolvedInput(in)
        case AlonzoContextError.TimeTranslationPastHorizon(cause) => cause
        case AlonzoContextError.Undecodable(site, _) => TranslationError.Undecodable(site)
        case AlonzoContextError.CertificateNotSupported(cert) =>
          TranslationError.CertificateNotSupported(BabbageTestKit.flatCert(cert))
        case AlonzoContextError.PlutusPurposeNotSupported(_) |
            AlonzoContextError.ImpossibleContextError(_) =>
          fail(s"no Babbage translation fails with $a")
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

  /** The typed information of `language` through the era's memoised result, as data. */
  private def typedTxInfo(
      l: LedgerTxInfo[TxLevel.Top, Babbage],
      language: PlutusLanguage
  ): Either[BabbageContextError[Babbage], PlutusData] =
    val result = ctx.mkTxInfoResult(l)
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
      case other => fail(s"not a language of Babbage: $other")

  /** The typed V1 or V2 `TxInfo` of `c` at `major` against the flat one: equal data, or equal
    * failures.
    */
  private def sameTxInfo(
      c: Case,
      major: Int,
      language: PlutusLanguage
  ): Either[TranslationError, PlutusData] =
    val flat = ScriptContexts.txInfo(language, c.flat, env(c, major))
    assertEquals(
      typedTxInfo(lti(c, major), language).left.map(asFlat),
      flat,
      s"${c.name} at $major, $language"
    )
    flat

  test(
    "the typed V1 and V2 TxInfos are the flat ones on every case, at its version and at 7 and 8"
  ) {
    val outcomes = cases.flatMap(c =>
      for
        major <- (Vector(c.major) ++ Vector(7, 8)).distinct
        language <- Vector(PlutusLanguage.V1, PlutusLanguage.V2)
      yield sameTxInfo(c, major, language)
    )
    assertEquals(
      outcomes.size,
      2 * 2 * cases.size + 2 * cases.count(c => c.major != 7 && c.major != 8)
    )
    assert(outcomes.exists(_.isRight))
    // the refusals the cases reach, each by its case
    val reached = outcomes.collect { case Left(e) => e.productPrefix }.toSet
    assertEquals(
      reached,
      Set(
        "ReferenceInputsNotSupported",
        "ReferenceScriptsNotSupported",
        "InlineDatumsNotSupported",
        "ByronTxOutInContext"
      ),
      reached
    )
  }

  // ---------------------------------------------------------------------------------------------
  // The scripts of a transaction with their contexts

  private def flatParams(c: Case): xyz.kd5ujc.scarab.ledger.ProtocolParams =
    TestLedger.params(c.major).copy(costModels = c.costModels)

  private def eraParams(c: Case): PParams[Babbage] =
    PParams[Babbage](
      BabbagePParams.fromStored(flatParams(c)).fold(e => fail(s"${c.name}: $e"), identity)
    )

  private def flatScripts(c: Case): Map[Bytes, xyz.kd5ujc.scarab.ledger.Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(sc => sc.hash -> sc)
      .toMap ++ c.flat.witnesses.scriptsByHash

  private def eraCollect(
      c: Case
  ): Either[cats.data.NonEmptyList[CollectError[Babbage]], List[PlutusWithContext]] =
    AlonzoPlutusEvaluate
      .collectPlutusScriptsWithContext[Babbage](c.posix, eraParams(c), c.era, utxoOf(c))

  private def asIx(p: AlonzoPlutusPurpose[AsIxItem, Babbage]): AlonzoPlutusPurpose[AsIx, Babbage] =
    s.hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(p)

  private def asItem(
      p: AlonzoPlutusPurpose[AsIxItem, Babbage]
  ): AlonzoPlutusPurpose[AsItem, Babbage] =
    s.hoistPlutusPurpose[
      AsIxItem,
      AsItem
    ]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a))(p)

  private def scriptsNeeded(c: Case): AlonzoScriptsNeeded[Babbage] =
    u.getScriptsNeeded[TxLevel.Top](utxoOf(c), t.body[TxLevel.Top](c.era))

  /** Both collections' outcomes in one form: a failure by its kind, a redeemer's purpose by its tag
    * and index, a translation's failure as the flat one.
    */
  private enum Collected:
    case NoRedeemer(tag: Int, ix: BigInt)
    case NoWitness(hash: Bytes)
    case NoCostModel(language: PlutusLanguage)
    case BadTranslation(failure: TranslationError)

  private def eraCollected(c: Case)(e: CollectError[Babbage]): Collected = e match
    case CollectError.NoRedeemer(purpose) =>
      val index = scriptsNeeded(c).unAlonzoScriptsNeeded.map(_._1).find(p => asItem(p) == purpose)
      val ix = asIx(index.getOrElse(fail(s"${c.name}: $purpose is not needed")))
      Collected.NoRedeemer(ix.tag, ix.ix.value)
    case CollectError.NoWitness(hash)         => Collected.NoWitness(hash)
    case CollectError.NoCostModel(language)   => Collected.NoCostModel(language)
    case CollectError.BadTranslation(failure) => Collected.BadTranslation(asFlat(failure))

  private def flatCollected(e: FlatCollectError): Collected = e match
    case FlatCollectError.NoRedeemer(purpose) =>
      Collected.NoRedeemer(purpose.tag, BigInt(purpose.index))
    case FlatCollectError.NoWitness(hash)         => Collected.NoWitness(hash)
    case FlatCollectError.NoCostModel(language)   => Collected.NoCostModel(language)
    case FlatCollectError.BadTranslation(failure) => Collected.BadTranslation(failure)

  /** Both collections of `c`: the era's scripts with their contexts as the evaluator's requests,
    * equal to the flat path's in order, or the same failures in the same order.
    */
  private def sameCollection(
      c: Case
  ): Either[List[Collected], List[xyz.kd5ujc.scarab.plutus.EvalRequest]] =
    val era = eraCollect(c).fold(
      es => Left(es.toList.map(eraCollected(c))),
      pwcs => Right(pwcs.map(PlutusWithContext.evalRequest))
    )
    val flat = Phase2
      .collect[Babbage](c.flat, flatParams(c), flatScripts(c), c.spent, c.references, c.posix)
      .fold(es => Left(es.toVector.toList.map(flatCollected)), rs => Right(rs.toList.map(_._2)))
    assertEquals(era, flat, c.name)
    era

  test("every case's scripts with their contexts are the flat path's requests or failures") {
    val outcomes = cases.map(sameCollection)
    val requests = outcomes.collect { case Right(rs) => rs }.flatten
    val failures = outcomes.collect { case Left(es) => es }.flatten
    // the requests of each language, the stand-in cases' among them, and the failures the cases
    // reach, each by its kind
    assertEquals(
      (
        requests.groupMapReduce(_.language)(_ => 1)(_ + _),
        failures.groupMapReduce(_.productPrefix)(_ => 1)(_ + _)
      ),
      (Map(PlutusLanguage.V1 -> 37, PlutusLanguage.V2 -> 223), Map("BadTranslation" -> 20))
    )
    // 191 of the recorded contexts, 46 of the matrix and 23 of the stand-in cases
    assertEquals(
      cases.filter(_.standIn).map(sameCollection).collect { case Right(rs) => rs.size }.sum,
      23
    )

  }

  test("each Plutus V1 and V2 request the reference collected is the era's, every field of it") {
    val matched = oracleCases.filter(c => field[Boolean](c.oracle, "collected")).flatMap { c =>
      val ours = eraCollect(c).fold(e => fail(s"${c.name}: $e"), identity)
      val references = field[Vector[Json]](c.oracle, "scripts")
      assertEquals(ours.size, references.size, c.name)
      ours.zip(references).zipWithIndex.map { case ((pwc, script), i) =>
        val request = PlutusWithContext.evalRequest(pwc)
        val theirs = ReferenceRequests.reference(script)
        // the script, the arguments, the budget and the cost model of a whole request; each of them
        // by its SHA-256 of a request kept as digests
        ReferenceRequests.sameRequest(request, theirs, s"${c.name} request $i")
        val form = theirs match
          case ReferenceRequests.Reference.Whole(_) =>
            if c.name.startsWith("matrix/") then "matrix" else "whole"
          case ReferenceRequests.Reference.Digested(_) => "digests"
        (form, request.language)
      }
    }
    // every request the reference collected of the Babbage rows: of the recorded contexts 158 kept as
    // digests and 33 whole, and 46 of the matrix, whole
    assertEquals(
      matched.groupMapReduce(_._1)(_ => 1)(_ + _),
      Map("digests" -> 158, "whole" -> 33, "matrix" -> 46)
    )
    assertEquals(matched.map(_._2).toSet, Set(PlutusLanguage.V1, PlutusLanguage.V2))
  }

  test(
    "each context the reference refused to collect is refused alike, with the same typed cause"
  ) {
    val refused = oracleCases.filterNot(c => field[Boolean](c.oracle, "collected")).map { c =>
      val errors = sameCollection(c).fold(identity, rs => fail(s"${c.name}: collected $rs"))
      assertEquals(
        errors.map(_.productPrefix),
        field[Vector[String]](c.oracle, "failureKinds").toList,
        c.name
      )
      errors
    }
    assertEquals(refused.size, 20)
    // the reference's causes, each the typed one (`BabbagePlutusContextRefusalsSuite` holds the
    // era's to the reference's bytes)
    assertEquals(
      refused.flatten.collect { case Collected.BadTranslation(f) => f.productPrefix }.toSet,
      Set(
        "ReferenceInputsNotSupported",
        "ReferenceScriptsNotSupported",
        "InlineDatumsNotSupported",
        "ByronTxOutInContext"
      )
    )
  }

  // ---------------------------------------------------------------------------------------------
  // The UTXOW members and the annotated transaction

  /** A purpose by its tag, index and script hash. */
  private def eraNeeded(c: Case): Vector[(Int, BigInt, Bytes)] =
    scriptsNeeded(c).unAlonzoScriptsNeeded.map((p, h) => (asIx(p).tag, asIx(p).ix.value, h))

  private def flatStAnnTx(c: Case): FlatStAnnTx = FlatStAnnTx(c.flat, Utxo(c.known))

  private def flatWitsNeeded(c: Case, genDelegs: Map[Bytes, GenDelegPair]): Set[Bytes] =
    BabbageTestKit.flatWitsNeeded(c.flat, c.known, genDelegs)

  private def certState(genDelegs: Map[KeyHash[Genesis], GenDelegPair]): ShelleyCertState =
    val empty: ShelleyCertState = Empty.certState[Babbage]
    empty.copy(dState = empty.dState.copy(genDelegs = empty.dState.genDelegs ++ genDelegs))

  /** The flat spending datum of an input: the leading argument of a spending script's arguments. */
  private def flatSpendingDatum(
      c: Case,
      index: Int,
      in: TxIn,
      out: FlatTxOut
  ): Either[TranslationError, Option[PlutusData]] =
    ScriptContexts
      .arguments(
        PlutusLanguage.V2,
        PlutusData.I(0),
        c.flat,
        FlatRedeemer(0, BigInt(index), Bytes(Vector(0.toByte)), ExUnits.zero),
        Purpose.Spending(index, in, out)
      )
      .map(args => Option.when(args.size == 3)(args.head))

  test(
    "scripts needed and provided, supplemental datums, spending datums and witnesses are the flat path's"
  ) {
    val counts = cases.map { c =>
      val utxo = utxoOf(c)
      val body = t.body[TxLevel.Top](c.era)
      val ann = flatStAnnTx(c)
      // the scripts needed, in order with each purpose's index
      val needed = eraNeeded(c)
      assertEquals(needed, ann.needed[Babbage].map((p, h) => (p.tag, BigInt(p.index), h)), c.name)
      assertEquals(u.getScriptsHashesNeeded(scriptsNeeded(c)), needed.map(_._3).toSet, c.name)
      // the scripts provided, by hash and kind
      val provided = u.getScriptsProvided[TxLevel.Top](utxo, c.era).unScriptsProvided
      assertEquals(
        provided.map((h, sc) => h -> s.scriptPrefixTag(sc).toInt),
        ann.scripts.map((h, sc) => h -> (if sc.kind == ScriptKind.Native then 0 else sc.kind.tag)),
        c.name
      )
      assert(provided.forall((h, sc) => s.hashScript(sc) == h), c.name)
      // the supplemental datums
      assertEquals(
        u.getSupplementalDataHashes[TxLevel.Top](utxo, body),
        TxChecks.supplementalDatumHashes[Babbage](c.flat.body, ann.referenced),
        c.name
      )
      // each spent input's datum
      val datums = c.flat.body.inputs.sorted.zipWithIndex.flatMap { (in, index) =>
        c.spent.get(in).map { out =>
          val ours = u
            .getSpendingDatum[TxLevel.Top](
              utxo,
              c.era,
              AlonzoPlutusPurpose.AlonzoSpending(AsItem(in))
            )
            .map(_.map(_.getPlutusData))
          // both read the datum, and the same one
          val flat = flatSpendingDatum(c, index, in, out)
          assert(ours.isRight && flat.isRight, s"${c.name}: $ours, $flat")
          assertEquals(ours.toOption, flat.toOption, c.name)
          ours.toOption.flatten.isDefined
        }
      }
      // the keys that must sign, no genesis delegation in force
      assertEquals(
        u.getWitsVKeyNeeded[TxLevel.Top](certState(Map.empty), utxo, body),
        flatWitsNeeded(c, Map.empty),
        c.name
      )
      (needed.size, if c.standIn then 0 else datums.count(identity))
    }
    // what the cases exercise: scripts needed and the spending datums of the cases with their outputs
    assertEquals((counts.map(_._1).sum, counts.map(_._2).sum), (282, 194))
  }

  test("the annotated transaction holds what its members compute, on every case") {
    cases.foreach { c =>
      val utxo = utxoOf(c)
      val st = BabbageEraUTxO.mkStAnnTx(c.posix, eraParams(c), utxo, Map.empty, c.era)
      val provided = u.getScriptsProvided[TxLevel.Top](utxo, c.era)
      val (cache, used) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose[Babbage](
        protVer(c.major),
        provided,
        scriptsNeeded(c),
        Map.empty
      )
      assert(t.txStAnnTx[TxLevel.Top](st) eq c.era, c.name)
      assertEquals(t.cacheStAnnTx[TxLevel.Top](st), cache, c.name)
      assertEquals(u.scriptsProvidedStAnnTx[TxLevel.Top](st), provided, c.name)
      assertEquals(u.scriptsNeededStAnnTx[TxLevel.Top](st), scriptsNeeded(c), c.name)
      assertEquals(
        u.plutusLanguagesUsedStAnnTx[TxLevel.Top](st),
        used.map((_, r) => r.language).toSet,
        c.name
      )
      assertEquals(u.plutusScriptsWithContextStAnnTx[TxLevel.Top](st), eraCollect(c), c.name)
    }
  }

  test(
    "an annotated transaction keeps the runnables of a warm cache, and adds the scripts it provides"
  ) {
    val c = oracleCases
      .find(c =>
        field[Boolean](c.oracle, "collected") && eraCollect(c).exists(
          _.exists(_.plutusLanguage.language == PlutusLanguage.V1)
        )
      )
      .getOrElse(fail("no collected case with a Plutus V1 script"))
    val utxo = utxoOf(c)
    val cold = BabbageEraUTxO.mkStAnnTx(c.posix, eraParams(c), utxo, Map.empty, c.era)
    val (hash, _) = cold.asatPlutusRunnableCache
      .find((_, r) => r.language == PlutusLanguage.V1)
      .getOrElse(fail("no Plutus V1 runnable"))
    val earlier = Bytes(Vector.fill(28)(7.toByte))
    // a cached runnable of the same hash with another binary, which the contexts must then carry
    val cached = SupportedPlutusRunnable(
      EraPlutusTxInfo.babbagePlutusV1,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes(Vector(1.toByte))), hash)
    )
    val other = SupportedPlutusRunnable(
      EraPlutusTxInfo.babbagePlutusV1,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes.empty), earlier)
    )
    val warm =
      BabbageEraUTxO.mkStAnnTx(
        c.posix,
        eraParams(c),
        utxo,
        Map(hash -> cached, earlier -> other),
        c.era
      )
    val kept = t.cacheStAnnTx[TxLevel.Top](warm)
    assert(kept(hash) eq cached)
    assert(kept(earlier) eq other)
    assertEquals(kept.keySet, cold.asatPlutusRunnableCache.keySet + earlier)
    val scripts = warm.asatPlutusScriptsWithContext.fold(e => fail(e.toString), identity)
    assert(
      scripts.exists(_.pwcScript.plutusRunnableBinary.binary == Bytes(Vector(1.toByte))),
      "the contexts carry the cached runnable"
    )
  }

  test("Babbage supports Plutus V1 and V2, each looked up as its own translation gives it") {
    assertEquals(
      PlutusLanguage.values.toVector.map(l => ctx.mkSupportedLanguage(l).map(_.language)),
      Vector(Some(PlutusLanguage.V1), Some(PlutusLanguage.V2), None, None)
    )
    val l = lti(oracleCases.head, 8)
    val result = ctx.mkTxInfoResult(l)
    assertEquals(
      ctx.lookupTxInfoResult[Language.PlutusV1](result).unPlutusTxInfoResult,
      EraPlutusTxInfo.babbagePlutusV1.toPlutusTxInfo(l).unPlutusTxInfoResult
    )
    assertEquals(
      ctx.lookupTxInfoResult[Language.PlutusV2](result).unPlutusTxInfoResult,
      EraPlutusTxInfo.babbagePlutusV2.toPlutusTxInfo(l).unPlutusTxInfoResult
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Crafted cases, for what the corpus does not reach and where Babbage is not Conway, each against
  // the flat path

  private val key0 = TestLedger.txIn(0)

  /** A case of the crafted body `b` and witness set `wits`, its inputs spending `out`. */
  private def crafted(
      name: String,
      b: xyz.kd5ujc.scarab.ledger.TxBody,
      wits: Bytes = BabbageTestKit.witnessSet(Vector.empty),
      posix: BigInt => Either[TranslationError, BigInt] = slot => Right(slot * 1000)
  ): Case =
    val built = BabbageTestKit.encode(b, wits)
    Case(
      name,
      built.tx,
      built.flat,
      built.flat.body.inputs.map(_ -> standIn).toMap,
      built.flat.body.referenceInputs.map(_ -> standIn).toMap,
      Map.empty,
      8,
      Map.empty,
      posix,
      Json.Null,
      standIn = true
    )

  private def plain: xyz.kd5ujc.scarab.ledger.TxBody =
    BabbageTestKit.flatBody(Vector(key0), Vector(standIn), BigInt(200000))

  private def v1Info(c: Case): PV1.TxInfo =
    ctx
      .lookupTxInfoResult[Language.PlutusV1](ctx.mkTxInfoResult(lti(c, 8)))
      .unPlutusTxInfoResult
      .fold(e => fail(s"${c.name}: $e"), identity)

  test("an end alone is closed at its end, as Alonzo's and unlike Conway's") {
    val c = crafted("ttl alone", plain.copy(ttl = Some(BigInt(50))))
    Vector(PlutusLanguage.V1, PlutusLanguage.V2).foreach(l => assert(sameTxInfo(c, 8, l).isRight))
    assertEquals(
      v1Info(c).txInfoValidRange,
      PV1.Interval(
        PV1.LowerBound(PV1.Extended.NegInf, true),
        PV1.UpperBound(PV1.Extended.Finite(PV1.POSIXTime(BigInt(50000))), true)
      )
    )
    val both =
      crafted("ttl and start", plain.copy(ttl = Some(BigInt(50)), validityStart = Some(BigInt(5))))
    Vector(PlutusLanguage.V1, PlutusLanguage.V2).foreach(l =>
      assert(sameTxInfo(both, 8, l).isRight)
    )
    assertEquals(v1Info(both).txInfoValidRange.ivTo.closed, false)
  }

  test("a genesis delegation and a move of rewards are DCertGenesis and DCertMir in V1 and V2") {
    val c = crafted(
      "genesis and MIR",
      plain.copy(certificates =
        Vector(
          xyz.kd5ujc.scarab.ledger.Certificate.GenesisDelegate(
            Bytes(Vector.fill(28)(1.toByte)),
            Bytes(Vector.fill(28)(2.toByte)),
            Bytes(Vector.fill(32)(3.toByte))
          ),
          xyz.kd5ujc.scarab.ledger.Certificate.MoveRewards(
            xyz.kd5ujc.scarab.ledger.Pot.Treasury,
            Left(BigInt(7))
          )
        )
      )
    )
    Vector(PlutusLanguage.V1, PlutusLanguage.V2).foreach(l => assert(sameTxInfo(c, 8, l).isRight))
    assertEquals(v1Info(c).txInfoDCert, Vector(PV1.DCert.DCertGenesis, PV1.DCert.DCertMir))
  }

  test(
    "a redeemer that names nothing, a clock that cannot translate and a missing input refuse alike"
  ) {
    val pointer = crafted(
      "pointer",
      plain,
      BabbageTestKit.witnessSet(
        Vector.empty,
        redeemers = Vector(FlatRedeemer(1, BigInt(0), Bytes(Vector(0.toByte)), ExUnits.zero))
      )
    )
    assertEquals(
      sameTxInfo(pointer, 8, PlutusLanguage.V2),
      Left(TranslationError.RedeemerPointerPointsToNothing(1, BigInt(0)))
    )
    val clock = crafted(
      "clock",
      plain.copy(validityStart = Some(BigInt(9))),
      posix = slot => Left(TranslationError.NoClockSample(slot))
    )
    Vector(PlutusLanguage.V1, PlutusLanguage.V2).foreach(l =>
      assertEquals(sameTxInfo(clock, 8, l), Left(TranslationError.NoClockSample(BigInt(9))))
    )
    val missing = crafted("missing", plain).copy(spent = Map.empty)
    Vector(PlutusLanguage.V1, PlutusLanguage.V2).foreach(l =>
      assertEquals(sameTxInfo(missing, 8, l), Left(TranslationError.UnresolvedInput(key0)))
    )
  }

  test("reference inputs refuse a V1 context before a clock that cannot translate a slot") {
    val c = crafted(
      "reference input and clock",
      plain.copy(validityStart = Some(BigInt(9)), referenceInputs = Vector(TestLedger.txIn(1))),
      posix = slot => Left(TranslationError.NoClockSample(slot))
    )
    assertEquals(
      sameTxInfo(c, 8, PlutusLanguage.V1),
      Left(TranslationError.ReferenceInputsNotSupported(Set(TestLedger.txIn(1))))
    )
    assertEquals(
      sameTxInfo(c, 8, PlutusLanguage.V2),
      Left(TranslationError.NoClockSample(BigInt(9)))
    )
  }

  test("the rewarding purpose's builder and reader are the withdrawing purpose's") {
    val item = AsIx[Unsigned[Unsigned.Width.W32], xyz.kd5ujc.scarab.ledger.state.AccountAddress](
      Unsigned.wrapping[Unsigned.Width.W32](BigInt(3))
    )
    assertEquals(s.mkRewardingPurpose(item), s.mkWithdrawingPurpose(item))
    assertEquals(s.toRewardingPurpose(s.mkWithdrawingPurpose(item)), Some(item))
    assertEquals(
      s.toRewardingPurpose(
        s.mkMintingPurpose(AsIx[Unsigned[Unsigned.Width.W32], Bytes](item.ix))
      ),
      None
    )
  }

  test("a certificate repeated with a script witness takes the index of its first occurrence") {
    val script = xyz.kd5ujc.scarab.ledger.Credential.Script(Bytes(Vector.fill(28)(9.toByte)))
    val key = xyz.kd5ujc.scarab.ledger.Credential.Key(Bytes(Vector.fill(28)(8.toByte)))
    val unreg = xyz.kd5ujc.scarab.ledger.Certificate.Unregister(script, None)
    val c = crafted(
      "repeated certificate",
      plain.copy(certificates =
        Vector(unreg, xyz.kd5ujc.scarab.ledger.Certificate.Register(key, None), unreg)
      )
    )
    val hash = Bytes(Vector.fill(28)(9.toByte))
    assertEquals(eraNeeded(c), Vector((2, BigInt(0), hash), (2, BigInt(0), hash)))
    assertEquals(
      eraNeeded(c),
      flatStAnnTx(c).needed[Babbage].map((p, h) => (p.tag, BigInt(p.index), h))
    )
  }

  test("the genesis delegates of a proposed update must sign, as the flat path asks") {
    val genesis = Bytes(Vector.fill(28)(4.toByte))
    val delegate = Bytes(Vector.fill(28)(5.toByte))
    val proposed = xyz.kd5ujc.scarab.ledger.Update(
      Map(genesis -> xyz.kd5ujc.scarab.ledger.ParamUpdate(Map(0 -> Bytes(Vector(0x01.toByte))))),
      BigInt(3)
    )
    val c = crafted("update", plain.copy(update = Some(proposed)))
    val pair = GenDelegPair(delegate, Bytes(Vector.fill(32)(6.toByte)))
    val genesisKey = KeyHash.fromBytes[Genesis](genesis).fold(e => fail(e.toString), identity)
    val ours = u.getWitsVKeyNeeded[TxLevel.Top](
      certState(Map(genesisKey -> pair)),
      utxoOf(c),
      t.body[TxLevel.Top](c.era)
    )
    assert(ours.contains(delegate))
    assertEquals(ours, flatWitsNeeded(c, Map(genesis -> pair)))
    // a genesis key with no delegation needs no signature
    assertEquals(
      u.getWitsVKeyNeeded[TxLevel.Top](certState(Map.empty), utxoOf(c), t.body[TxLevel.Top](c.era)),
      flatWitsNeeded(c, Map.empty)
    )
  }

  test("a Plutus script needed with no redeemer: NoRedeemer for the same purpose") {
    val withRedeemers = oracleCases
      .find(c => field[Boolean](c.oracle, "collected") && c.flat.witnesses.redeemers.nonEmpty)
      .getOrElse(fail("no collected case with redeemers"))
    val rawWits = withRedeemers.flat.witnesses.raw.getOrElse(fail("no witness bytes"))
    val stripped = cbor(rawWits).value match
      case CborValue.Map(kvs) =>
        Cbor
          .encode(
            CborValue.Map(kvs.filterNot((k, _) => k.value == CborValue.UInt(5))),
            Cbor.Limits.Unbounded
          )
          .fold(e => fail(e.toString), identity)
      case other => fail(s"not a witness set: $other")
    val built = BabbageTestKit.read(
      Block.Transaction(withRedeemers.flat.body.raw, stripped, withRedeemers.flat.auxiliary, true)
    )
    val c = withRedeemers.copy(name = "no redeemer", era = built.tx, flat = built.flat)
    val errors = sameCollection(c).fold(identity, rs => fail(s"collected $rs"))
    assert(errors.forall(_.productPrefix == "NoRedeemer"), errors)
    assert(errors.nonEmpty)
  }

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Block,
  ReferenceRequests,
  CollectError as FlatCollectError,
  Datum as FlatDatum,
  GenDelegPair,
  Phase2,
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
import xyz.kd5ujc.scarab.ledger.era.alonzo.{AlonzoEraUTxO, AlonzoPParams, AlonzoTx}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit
import xyz.kd5ujc.scarab.ledger.rules.{Convert, ShelleyCertLens, ShelleyCertOps, ShelleyDeposits}
import xyz.kd5ujc.scarab.ledger.rules.StAnnTx as FlatStAnnTx
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  CompactCoin,
  Credential,
  Empty,
  PoolBlsKey,
  Sorted,
  StakePool,
  Staking,
  Genesis,
  KeyHash,
  ShelleyBasedEra,
  ShelleyCertState
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusData, PlutusLanguage}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Alonzo's Plutus side on the era types (`EraPlutusTxInfo 'PlutusV1 AlonzoEra`, `EraPlutusContext
  * AlonzoEra`, `EraUTxO` and `AlonzoEraUTxO AlonzoEra`, `mkStAnnTx`) against the flat path the node
  * runs for Alonzo (`ScriptContexts`, `Phase2.collect`, the flat `StAnnTx` and `TxChecks`, with the
  * era `SAlonzo`) and the reference's requests of the context matrix field by field.
  *
  * The cases are every Alonzo transaction the repository holds: the Alonzo rows of the context
  * matrix (`fixtures/reference/context-matrix`, whose retained rows are the v1-context corpus's
  * Alonzo transactions), each with the outputs it spends and gives as collateral; and, with one
  * stand-in output (a key's at a Shelley address, with no datum) for each input, the Alonzo
  * transactions of the recorded blocks, the Preview ImmutableDB chunks, the refusal corpora and the
  * Plutus admission corpora. A stand-in case shows no spending script or datum of its own;
  * `AlonzoPlutusContextRefusalsSuite` (node) reads the refusal corpora against the UTxO set each
  * was refused on, and the matrix's requests against the reference's bytes.
  */
class AlonzoPlutusContextSuite extends munit.FunSuite:
  private val t = EraTx.alonzo
  private given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
  private val u = EraUTxO.alonzo
  private val s = EraScript.alonzo
  private val ctx = EraPlutusContext.alonzo
  private given ShelleyBasedEra[Alonzo] = ShelleyBasedEra.SAlonzo

  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: ${e.getMessage}"), identity)

  private def hex(text: String): Bytes = Bytes.fromHex(text).fold(e => fail(e.toString), identity)

  private def cbor(bytes: Bytes): Node = Cbor.decode(bytes).fold(e => fail(e.toString), identity)

  private def json(path: String): Json =
    parse(Files.readString(Path.of(path))).fold(e => fail(e.message), identity)

  private def alonzoRows(path: String): Vector[Json] =
    json(path).asArray
      .getOrElse(fail(s"$path is not an array"))
      .filter(field[String](_, "era") == "Alonzo")

  /** One Alonzo case: the transaction read both ways, what it spends and gives as collateral, its
    * version, cost models and clock, the reference's oracle, and whether its outputs are stand-ins.
    */
  private final case class Case(
      name: String,
      era: AlonzoTx,
      flat: FlatTx,
      spent: Map[TxIn, FlatTxOut],
      collateral: Map[TxIn, FlatTxOut],
      major: Int,
      costModels: Map[Int, Vector[BigInt]],
      posix: BigInt => Either[TranslationError, BigInt],
      oracle: Json,
      standIn: Boolean
  ):
    def known: Map[TxIn, FlatTxOut] = spent ++ collateral

  private def readParts(parts: Block.Transaction, what: String): (AlonzoTx, FlatTx) =
    (
      AlonzoTx.decode(parts, StateLayout.Node11_1).fold(e => fail(s"$what: $e"), identity),
      FlatTx.fromBlock(parts, Era.Alonzo).fold(e => fail(s"$what: $e"), identity)
    )

  private def readWhole(raw: Bytes, what: String): (AlonzoTx, FlatTx) =
    (
      AlonzoTx.fromEnvelope(raw, StateLayout.Node11_1).fold(e => fail(s"$what: $e"), identity),
      FlatTx.fromEnvelope(raw, Era.Alonzo).fold(e => fail(s"$what: $e"), identity)
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
      known.filter((in, _) => flat.body.collateral.contains(in)),
      field[Int](row, "protocolMajor"),
      field[Map[String, Vector[String]]](row, "costModels")
        .map((language, values) => language.toInt -> values.map(BigInt(_))),
      slot => Right(start + slot * length),
      field[Json](row, "oracle"),
      standIn = false
    )

  private lazy val oracleCases: Vector[Case] =
    alonzoRows("fixtures/reference/context-matrix/matrix.json").map(derived)

  // ---- the transactions without the outputs they spend

  private def blockTxs: Vector[(String, Block.Transaction)] =
    Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/chain-dep/blocks.hex",
      "fixtures/preview/alonzo-first-20.hex",
      "fixtures/preview/alonzo-txs.hex"
    ).flatMap(f =>
      EraCorpus
        .shelleyBlocks(Vector(f))
        .filter(_.era == Era.Alonzo)
        .flatMap(_.transactions)
        .zipWithIndex
        .map((tx, i) => s"$f transaction $i" -> tx)
    ) ++ AlonzoTestKit.immutablePreviewTxs.zipWithIndex.map((tx, i) =>
      s"fixtures/haskell-immutable/preview transaction $i" -> tx
    )

  private def refusalTxs: Vector[(String, Bytes)] =
    Files
      .list(Path.of("fixtures/reference/refusals"))
      .iterator()
      .asScala
      .map(_.getFileName.toString)
      .filter(_.startsWith("alonzo"))
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
          .filter(j => field[String](j, "era") == "alonzo")
          .map(j => hex(field[String](j, "tx")))
      )
      .distinct
      .zipWithIndex
      .map((raw, i) => s"admission $i" -> raw)

  private def isKeyShelley(o: FlatTxOut): Boolean = o.address.kind match
    case Address.Kind.Shelley(_, xyz.kd5ujc.scarab.ledger.Credential.Key(_), _, _) => true
    case Address.Kind.Shelley(_, _, _, _) | Address.Kind.Byron(_)                  => false

  /** The stand-in output of every input: a key's at a Shelley address, with no datum. */
  private lazy val standIn: FlatTxOut =
    oracleCases.iterator
      .flatMap(_.flat.body.outputs)
      .find(o => isKeyShelley(o) && o.datum == FlatDatum.NoDatum)
      .getOrElse(fail("no stand-in output"))

  /** An output at a Byron address, of the matrix's `output-byron` rows. */
  private lazy val byronOut: FlatTxOut =
    oracleCases.iterator
      .flatMap(_.flat.body.outputs)
      .find(o =>
        o.address.kind match
          case Address.Kind.Byron(_)            => true
          case Address.Kind.Shelley(_, _, _, _) => false
      )
      .getOrElse(fail("no Byron output"))

  private def withStandIns(name: String, read: (AlonzoTx, FlatTx)): Case =
    val (era, flat) = read
    Case(
      name,
      era,
      flat,
      flat.body.inputs.map(_ -> standIn).toMap,
      flat.body.collateral.map(_ -> standIn).toMap,
      6,
      standInCostModels,
      slot => Right(slot * 1000),
      Json.Null,
      standIn = true
    )

  /** The cost models of a stand-in case: Plutus V1's of the matrix rows at version 6, so that its
    * scripts' contexts are built.
    */
  private lazy val standInCostModels: Map[Int, Vector[BigInt]] =
    val models = oracleCases.filter(_.major == 6).flatMap(_.costModels).toMap
    assertEquals(models.keySet, Set(0))
    models

  private lazy val standInCases: Vector[Case] =
    blockTxs.map((n, p) => withStandIns(n, readParts(p, n))) ++
      refusalTxs.map((n, raw) => withStandIns(n, readWhole(raw, n))) ++
      admissionTxs.map((n, raw) => withStandIns(n, readWhole(raw, n)))

  private lazy val cases: Vector[Case] = oracleCases ++ standInCases

  test("the corpus: every Alonzo transaction the repository holds, with or without its outputs") {
    assertEquals(cases.size, cases.map(_.name).distinct.size)
    // the matrix's 20 Alonzo rows, every one collected; the 10 transactions of the blocks and the
    // ImmutableDB chunks, the 13 of the refusal corpora (1 + 4 + 8) and the 16 distinct ones of the
    // admission corpora
    assertEquals(
      (oracleCases.size, oracleCases.count(c => field[Boolean](c.oracle, "collected"))),
      (20, 20)
    )
    assertEquals(
      (blockTxs.size, refusalTxs.size, admissionTxs.size),
      (10, 1 + 4 + 8, 16)
    )
    // the matrix retains every Alonzo transaction of the v1-context corpus whole
    val v1Context = Vector("alonzo-pv5", "alonzo-pv6").flatMap(f =>
      json(s"fixtures/reference/v1-context/$f.json").asArray
        .getOrElse(fail(f))
        .map(field[String](_, "name"))
    )
    val retained = alonzoRows("fixtures/reference/context-matrix/matrix.json").collect {
      case r
          if field[String](field[Json](r, "provenance"), "kind") ==
            "retained-original-reference-transaction" =>
        field[String](field[Json](r, "provenance"), "sourceCase")
    }
    assertEquals(retained.sorted, v1Context.sorted)
    assertEquals(v1Context.size, 10)
  }

  // ---------------------------------------------------------------------------------------------
  // The transaction's information

  private def protVer(major: Int): ProtVer =
    ProtVer(
      Version.mkVersion(major).getOrElse(fail(s"version $major")),
      Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
    )

  private def utxoOf(c: Case): UTxO[Alonzo] =
    AlonzoEraUTxO.fromStored(c.known).fold(e => fail(s"${c.name}: $e"), identity)

  private def lti(c: Case, major: Int): LedgerTxInfo[TxLevel.Top, Alonzo] =
    LedgerTxInfo[TxLevel.Top, Alonzo](
      protVer(major),
      c.posix,
      utxoOf(c),
      c.era,
      Nil,
      LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
    )

  private def env(c: Case, major: Int): ScriptContexts.Environment =
    ScriptContexts.Environment(ShelleyBasedEra.SAlonzo, major, c.spent, Map.empty, c.posix)

  private def asFlat(e: AlonzoContextError[Alonzo]): TranslationError =
    AlonzoTestKit.flatContextError(e)

  /** The typed V1 information through the era's memoised result, as data. */
  private def typedTxInfo(
      l: LedgerTxInfo[TxLevel.Top, Alonzo]
  ): Either[AlonzoContextError[Alonzo], PlutusData] =
    ctx
      .lookupTxInfoResult[Language.PlutusV1](ctx.mkTxInfoResult(l))
      .unPlutusTxInfoResult
      .map(ToData[PV1.TxInfo].toBuiltinData)

  /** The typed V1 `TxInfo` of `c` at `major` against the flat one: equal data, or equal failures.
    */
  private def sameTxInfo(c: Case, major: Int): Either[TranslationError, PlutusData] =
    val flat = ScriptContexts.txInfo(PlutusLanguage.V1, c.flat, env(c, major))
    assertEquals(typedTxInfo(lti(c, major)).left.map(asFlat), flat, s"${c.name} at $major")
    flat

  test("the typed V1 TxInfos are the flat ones on every case, at its version and at 5 and 6") {
    val outcomes = cases.flatMap(c =>
      (Vector(c.major) ++ Vector(5, 6)).distinct.map(major => sameTxInfo(c, major))
    )
    assertEquals(outcomes.size, 2 * cases.size)
    // no case is refused: an input or output at a Byron address is left out, not refused
    assertEquals(outcomes.count(_.isRight), outcomes.size)
  }

  // ---------------------------------------------------------------------------------------------
  // The scripts of a transaction with their contexts

  private def flatParams(c: Case): xyz.kd5ujc.scarab.ledger.ProtocolParams =
    TestLedger.params(c.major).copy(costModels = c.costModels)

  private def eraParams(c: Case): PParams[Alonzo] =
    PParams[Alonzo](
      AlonzoPParams.fromStored(flatParams(c)).fold(e => fail(s"${c.name}: $e"), identity)
    )

  private def eraCollect(
      c: Case
  ): Either[cats.data.NonEmptyList[CollectError[Alonzo]], List[PlutusWithContext]] =
    AlonzoPlutusEvaluate
      .collectPlutusScriptsWithContext[Alonzo](c.posix, eraParams(c), c.era, utxoOf(c))

  private def asIx(p: AlonzoPlutusPurpose[AsIxItem, Alonzo]): AlonzoPlutusPurpose[AsIx, Alonzo] =
    s.hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(p)

  private def asItem(
      p: AlonzoPlutusPurpose[AsIxItem, Alonzo]
  ): AlonzoPlutusPurpose[AsItem, Alonzo] =
    s.hoistPlutusPurpose[
      AsIxItem,
      AsItem
    ]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a))(p)

  private def scriptsNeeded(c: Case): AlonzoScriptsNeeded[Alonzo] =
    u.getScriptsNeeded[TxLevel.Top](utxoOf(c), t.body[TxLevel.Top](c.era))

  /** Both collections' outcomes in one form: a failure by its kind, a redeemer's purpose by its tag
    * and index, a translation's failure as the flat one.
    */
  private enum Collected:
    case NoRedeemer(tag: Int, ix: BigInt)
    case NoWitness(hash: Bytes)
    case NoCostModel(language: PlutusLanguage)
    case BadTranslation(failure: TranslationError)

  private def eraCollected(c: Case)(e: CollectError[Alonzo]): Collected = e match
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
      .collect[Alonzo](
        c.flat,
        flatParams(c),
        c.flat.witnesses.scriptsByHash,
        c.spent,
        Map.empty,
        c.posix
      )
      .fold(es => Left(es.toVector.toList.map(flatCollected)), rs => Right(rs.toList.map(_._2)))
    assertEquals(era, flat, c.name)
    era

  test("every case's scripts with their contexts are the flat path's requests or failures") {
    val outcomes = cases.map(sameCollection)
    val requests = outcomes.collect { case Right(rs) => rs }.flatten
    val failures = outcomes.collect { case Left(es) => es }.flatten
    // the requests, all of Plutus V1, and no failure: 26 of the matrix and 11 of the stand-in cases
    assertEquals(
      (
        requests.groupMapReduce(_.language)(_ => 1)(_ + _),
        failures.groupMapReduce(_.productPrefix)(_ => 1)(_ + _)
      ),
      (Map(PlutusLanguage.V1 -> 37), Map.empty[String, Int])
    )
    assertEquals(
      cases.filter(_.standIn).map(sameCollection).collect { case Right(rs) => rs.size }.sum,
      11
    )
  }

  test("each Plutus V1 request the reference collected is the era's, every field of it") {
    val matched = oracleCases.flatMap { c =>
      val ours = eraCollect(c).fold(e => fail(s"${c.name}: $e"), identity)
      val references = field[Vector[Json]](c.oracle, "scripts")
      assertEquals(ours.size, references.size, c.name)
      ours.zip(references).zipWithIndex.map { case ((pwc, script), i) =>
        val request = PlutusWithContext.evalRequest(pwc)
        val theirs = ReferenceRequests.reference(script)
        theirs match
          case ReferenceRequests.Reference.Whole(_) => ()
          case ReferenceRequests.Reference.Digested(_) =>
            fail(s"${c.name}: the matrix keeps its requests whole")
        // the version, the script, the arguments, the budget and the cost model
        ReferenceRequests.sameRequest(request, theirs, s"${c.name} request $i")
        request.language
      }
    }
    // the 28 requests of the 20 rows: one each, and four of each all-purposes row
    assertEquals(matched.size, 18 + 2 * 4)
    assertEquals(matched.toSet, Set(PlutusLanguage.V1))
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
    val empty: ShelleyCertState = Empty.certState[Alonzo]
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
        PlutusLanguage.V1,
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
      assertEquals(needed, ann.needed[Alonzo].map((p, h) => (p.tag, BigInt(p.index), h)), c.name)
      assertEquals(u.getScriptsHashesNeeded(scriptsNeeded(c)), needed.map(_._3).toSet, c.name)
      // the scripts provided, by hash and kind: the witnesses' alone
      val provided = u.getScriptsProvided[TxLevel.Top](utxo, c.era).unScriptsProvided
      assertEquals(
        provided.map((h, sc) => h -> s.scriptPrefixTag(sc).toInt),
        ann.scripts.map((h, sc) => h -> (if sc.kind == ScriptKind.Native then 0 else sc.kind.tag)),
        c.name
      )
      assert(provided.forall((h, sc) => s.hashScript(sc) == h), c.name)
      // the supplemental datums: the body's outputs' alone
      assertEquals(
        u.getSupplementalDataHashes[TxLevel.Top](utxo, body),
        TxChecks.supplementalDatumHashes[Alonzo](c.flat.body, ann.referenced),
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
      (
        needed.size,
        if c.standIn then 0 else datums.count(identity),
        u.getSupplementalDataHashes[TxLevel.Top](utxo, body).size
      )
    }
    // what the cases exercise: scripts needed and the spending datums of the cases with their
    // outputs; no recorded Alonzo body has an output with a datum hash, so the supplemental datums
    // are the crafted case's below
    assertEquals(
      (counts.map(_._1).sum, counts.map(_._2).sum, counts.map(_._3).sum),
      (37, 4, 0)
    )
  }

  test("the value consumed and produced and the minimum fee are the flat UTXO's, on every case") {
    val balances = cases.map(c =>
      AlonzoTestKit.sameBalance(
        c.era,
        c.flat,
        c.known,
        flatParams(c),
        Empty.certState[Alonzo],
        c.name
      )
    )
    // what the cases reach: 59 compared, 10 of them balanced (a stand-in case is not, as its inputs
    // spend stand-ins), 25 that mint or burn assets and 4 whose certificates take deposits
    assertEquals(
      (
        balances.size,
        balances.count((consumed, produced, _) => consumed == produced),
        cases.count(_.flat.body.mint.nonEmpty),
        cases.count(c =>
          ShelleyDeposits.of(flatParams(c), Empty.certState[Alonzo], c.flat.body)._1 > 0
        )
      ),
      (59, 10, 25, 4)
    )
  }

  test("the annotated transaction holds what its members compute, on every case") {
    cases.foreach { c =>
      val utxo = utxoOf(c)
      val st = AlonzoEraUTxO.mkStAnnTx(c.posix, eraParams(c), utxo, Map.empty, c.era)
      val provided = u.getScriptsProvided[TxLevel.Top](utxo, c.era)
      val (cache, used) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose[Alonzo](
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
    val c = oracleCases.head
    val utxo = utxoOf(c)
    val cold = AlonzoEraUTxO.mkStAnnTx(c.posix, eraParams(c), utxo, Map.empty, c.era)
    val (hash, _) = cold.asatPlutusRunnableCache.headOption.getOrElse(fail("no runnable"))
    val earlier = Bytes(Vector.fill(28)(7.toByte))
    // a cached runnable of the same hash with another binary, which the contexts must then carry
    val cached = SupportedPlutusRunnable(
      EraPlutusTxInfo.alonzoPlutusV1,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes(Vector(1.toByte))), hash)
    )
    val other = SupportedPlutusRunnable(
      EraPlutusTxInfo.alonzoPlutusV1,
      PlutusRunnable[Language.PlutusV1](Plutus(Bytes.empty), earlier)
    )
    val warm = AlonzoEraUTxO.mkStAnnTx(
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

  test("Alonzo supports Plutus V1 alone, looked up as its translation gives it") {
    assertEquals(
      PlutusLanguage.values.toVector.map(l => ctx.mkSupportedLanguage(l).map(_.language)),
      Vector(Some(PlutusLanguage.V1), None, None, None)
    )
    val l = lti(oracleCases.head, 6)
    assertEquals(
      ctx.lookupTxInfoResult[Language.PlutusV1](ctx.mkTxInfoResult(l)).unPlutusTxInfoResult,
      EraPlutusTxInfo.alonzoPlutusV1.toPlutusTxInfo(l).unPlutusTxInfoResult
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Crafted cases, for what the corpus does not reach and where Alonzo is not Babbage, each against
  // the flat path

  private val key0 = TestLedger.txIn(0)

  /** A case of the crafted body `b` and witness set `wits`, its inputs spending `spentBy`'s. */
  private def crafted(
      name: String,
      b: xyz.kd5ujc.scarab.ledger.TxBody,
      wits: Bytes = BabbageTestKit.witnessSet(Vector.empty),
      posix: BigInt => Either[TranslationError, BigInt] = slot => Right(slot * 1000),
      spentBy: TxIn => FlatTxOut = _ => standIn
  ): Case =
    val built = AlonzoTestKit.encode(b, wits)
    Case(
      name,
      built.tx,
      built.flat,
      built.flat.body.inputs.map(in => in -> spentBy(in)).toMap,
      Map.empty,
      6,
      Map.empty,
      posix,
      Json.Null,
      standIn = true
    )

  private def plain: xyz.kd5ujc.scarab.ledger.TxBody =
    BabbageTestKit.flatBody(Vector(key0), Vector(standIn), BigInt(200000))

  private def v1Info(c: Case): PV1.TxInfo =
    ctx
      .lookupTxInfoResult[Language.PlutusV1](ctx.mkTxInfoResult(lti(c, 6)))
      .unPlutusTxInfoResult
      .fold(e => fail(s"${c.name}: $e"), identity)

  test("an input and an output at a Byron address are left out of the V1 context, not refused") {
    val key1 = TestLedger.txIn(1)
    val c = crafted(
      "Byron input and output",
      BabbageTestKit.flatBody(Vector(key0, key1), Vector(byronOut, standIn, byronOut), BigInt(9)),
      spentBy = in => if in == key0 then byronOut else standIn
    )
    assert(sameTxInfo(c, 6).isRight)
    val info = v1Info(c)
    assertEquals(info.txInfoInputs.map(_.txInInfoOutRef), Vector(PlutusTxInfos.transTxIn(key1)))
    assertEquals(info.txInfoOutputs.size, 1)
  }

  /** The stand-in output with the datum hash `h`, in Alonzo's list form. */
  private def withDataHash(h: Bytes): FlatTxOut =
    val address = standIn.address.raw
    assert(address.size >= 24 && address.size < 256)
    FlatTxOut
      .fromBytes(hex(f"83${"58"}${address.size}%02x${address.hex}1a000f42405820${h.hex}"))
      .fold(e => fail(e.toString), identity)

  test("the supplemental datums are the datum hashes of every output of the body") {
    val hashes = Vector(1, 2).map(b => Bytes(Vector.fill(32)(b.toByte)))
    val c = crafted(
      "outputs with datum hashes",
      BabbageTestKit.flatBody(Vector(key0), hashes.map(withDataHash) :+ standIn, BigInt(9))
    )
    val supplemental =
      u.getSupplementalDataHashes[TxLevel.Top](utxoOf(c), t.body[TxLevel.Top](c.era))
    assertEquals(supplemental, hashes.toSet)
    assertEquals(
      supplemental,
      TxChecks.supplementalDatumHashes[Alonzo](c.flat.body, flatStAnnTx(c).referenced)
    )
  }

  test(
    "a refund of a registered credential and a pool registered again count as the flat UTXO counts them"
  ) {
    val staker = Bytes(Vector.fill(28)(11.toByte))
    val operator = Bytes(Vector.fill(28)(12.toByte))
    val fresh = Bytes(Vector.fill(28)(13.toByte))
    def pool(id: Bytes) = xyz.kd5ujc.scarab.ledger.PoolParams(
      id,
      Bytes(Vector.fill(32)(14.toByte)),
      1000,
      340,
      xyz.kd5ujc.scarab.codec.Rational(1, 10),
      xyz.kd5ujc.scarab.ledger.RewardAccount(0, xyz.kd5ujc.scarab.ledger.Credential.Key(staker)),
      Set(staker),
      Bytes.empty,
      None
    )
    def ok[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
    val stakeCredential =
      ok(Convert.credential[Staking](xyz.kd5ujc.scarab.ledger.Credential.Key(staker)))
    val registered = ShelleyCertOps.register(
      Empty.certState[Alonzo],
      stakeCredential,
      xyz.kd5ujc.scarab.ledger.Ptr(1, 0, 0),
      CompactCoin.fromUnsigned(2000000L)
    )
    val withPool = ShelleyCertLens.pools.modify(
      _.updated(
        ok(Convert.keyHash[StakePool](operator)),
        ok(
          Convert.poolState(
            pool(operator),
            PoolBlsKey.Node11_1,
            CompactCoin.fromUnsigned(0L),
            Sorted.set(Seq.empty[Credential[Staking]])
          )
        )
      )
    )(registered)
    val c = crafted(
      "refund and pools",
      plain.copy(certificates =
        Vector(
          xyz.kd5ujc.scarab.ledger.Certificate.Unregister(
            xyz.kd5ujc.scarab.ledger.Credential.Key(staker),
            None
          ),
          xyz.kd5ujc.scarab.ledger.Certificate.RegisterPool(pool(operator)),
          xyz.kd5ujc.scarab.ledger.Certificate.RegisterPool(pool(fresh))
        )
      )
    )
    val pp = flatParams(c)
    val (deposits, refunds) = ShelleyDeposits.of(pp, withPool, c.flat.body)
    // the state's deposit refunded, and one pool deposit, the other pool being registered already
    assertEquals((deposits, refunds), (pp.poolDeposit, BigInt(2000000)))
    AlonzoTestKit.sameBalance(c.era, c.flat, c.known, pp, withPool, c.name)
  }

  test("an end alone is closed at its end, as Alonzo's is") {
    val c = crafted("ttl alone", plain.copy(ttl = Some(BigInt(50))))
    assert(sameTxInfo(c, 6).isRight)
    assertEquals(
      v1Info(c).txInfoValidRange,
      PV1.Interval(
        PV1.LowerBound(PV1.Extended.NegInf, true),
        PV1.UpperBound(PV1.Extended.Finite(PV1.POSIXTime(BigInt(50000))), true)
      )
    )
    val both =
      crafted("ttl and start", plain.copy(ttl = Some(BigInt(50)), validityStart = Some(BigInt(5))))
    assert(sameTxInfo(both, 6).isRight)
    assertEquals(v1Info(both).txInfoValidRange.ivTo.closed, false)
  }

  test("a genesis delegation and a move of rewards are DCertGenesis and DCertMir") {
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
    assert(sameTxInfo(c, 6).isRight)
    assertEquals(v1Info(c).txInfoDCert, Vector(PV1.DCert.DCertGenesis, PV1.DCert.DCertMir))
  }

  test("a clock that cannot translate a slot refuses before a missing input, and each alone") {
    val noClock: BigInt => Either[TranslationError, BigInt] =
      slot => Left(TranslationError.NoClockSample(slot))
    val clock = crafted("clock", plain.copy(validityStart = Some(BigInt(9))), posix = noClock)
    assertEquals(sameTxInfo(clock, 6), Left(TranslationError.NoClockSample(BigInt(9))))
    val missing = crafted("missing", plain).copy(spent = Map.empty)
    assertEquals(sameTxInfo(missing, 6), Left(TranslationError.UnresolvedInput(key0)))
    val both = clock.copy(name = "clock and missing", spent = Map.empty)
    assertEquals(sameTxInfo(both, 6), Left(TranslationError.NoClockSample(BigInt(9))))
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
      flatStAnnTx(c).needed[Alonzo].map((p, h) => (p.tag, BigInt(p.index), h))
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
    assertEquals(
      u.getWitsVKeyNeeded[TxLevel.Top](certState(Map.empty), utxoOf(c), t.body[TxLevel.Top](c.era)),
      flatWitsNeeded(c, Map.empty)
    )
  }

  test("a Plutus script needed with no redeemer: NoRedeemer for the same purpose") {
    val withRedeemers = oracleCases
      .find(_.flat.witnesses.redeemers.nonEmpty)
      .getOrElse(fail("no case with redeemers"))
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
    val built = AlonzoTestKit.read(
      Block.Transaction(withRedeemers.flat.body.raw, stripped, withRedeemers.flat.auxiliary, true)
    )
    val c = withRedeemers.copy(name = "no redeemer", era = built.tx, flat = built.flat)
    val errors = sameCollection(c).fold(identity, rs => fail(s"collected $rs"))
    assert(errors.forall(_.productPrefix == "NoRedeemer"), errors)
    assert(errors.nonEmpty)
  }

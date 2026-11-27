// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CollectError as FlatCollectError,
  GenDelegPair,
  Phase2,
  ScriptContexts,
  TestLedger,
  TranslationError,
  Tx as FlatTx,
  TxIn,
  TxOut as FlatTxOut,
  Unsigned,
  Utxo,
  testByteStrings
}
import xyz.kd5ujc.scarab.ledger.ContextReferenceCases.{field, hex}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.babbage.{
  BabbageEraUTxO,
  BabbagePParams,
  BabbageTestKit,
  BabbageTx
}
import xyz.kd5ujc.scarab.ledger.rules.StAnnTx as FlatStAnnTx
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Empty,
  Genesis,
  KeyHash,
  NewEpochState,
  ShelleyBasedEra,
  ShelleyCertState
}
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.*
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Babbage's Plutus side on the era types against the flat path and the reference, where the ledger
  * module's `BabbagePlutusContextSuite` cannot reach:
  *   - every Babbage case of the recorded refusal corpora, with the UTxO set and the state each was
  *     refused on, at its parameters' protocol version and cost models: the V1 and V2 `TxInfo` s,
  *     the scripts with their contexts, the scripts needed and the keys that must sign (the state's
  *     genesis delegations among them) are the flat path's;
  *   - every Babbage row of the context matrix (`fixtures/reference/context-matrix`): a collected
  *     row's requests encode to the reference's `PlutusWithContext` bytes whole, and a refused
  *     row's failures, the era's typed causes, to the reference's `CollectError` bytes.
  * The corpora carry no era history, so slot `s` is the time `s` seconds for both paths.
  */
class BabbagePlutusContextRefusalsSuite extends FunSuite:
  private given ShelleyBasedEra[Babbage] = ShelleyBasedEra.SBabbage
  private val t = EraTx.babbage
  private given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
  private val u = EraUTxO.babbage
  private val ctx = EraPlutusContext.babbage

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def protVer(major: Int): ProtVer =
    ProtVer(
      Version.mkVersion(major).getOrElse(fail(s"version $major")),
      Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
    )

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

  /** One case: the transaction both ways, the outputs the set holds of it, its parameters, the
    * genesis delegations in force and its clock.
    */
  private final case class Seen(
      label: String,
      flat: FlatTx,
      era: BabbageTx,
      known: Map[TxIn, FlatTxOut],
      params: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      genDelegs: Map[Bytes, GenDelegPair],
      posix: BigInt => Either[TranslationError, BigInt]
  ):
    def major: Int = params.protocolMajor.toInt
    def spent: Map[TxIn, FlatTxOut] = known.filter((in, _) => flat.body.inputs.contains(in))
    def references: Map[TxIn, FlatTxOut] =
      known.filter((in, _) => flat.body.referenceInputs.contains(in))
    lazy val utxo: UTxO[Babbage] = right(s"$label UTxO")(BabbageEraUTxO.fromStored(known))
    def pp: PParams[Babbage] =
      PParams[Babbage](right(s"$label parameters")(BabbagePParams.fromStored(params)))
    def lti: LedgerTxInfo[TxLevel.Top, Babbage] =
      LedgerTxInfo[TxLevel.Top, Babbage](
        protVer(major),
        posix,
        utxo,
        era,
        Nil,
        LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
      )
    def env: ScriptContexts.Environment =
      ScriptContexts.Environment(ShelleyBasedEra.SBabbage, major, spent, references, posix)
    def scripts: Map[Bytes, xyz.kd5ujc.scarab.ledger.Script] =
      (spent.values ++ references.values).flatMap(_.referenceScript).map(s => s.hash -> s).toMap ++
        flat.witnesses.scriptsByHash

  private val clock: BigInt => Either[TranslationError, BigInt] = slot => Right(slot * 1000)

  private lazy val refused: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Babbage).flatMap { c =>
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes: NewEpochState[Babbage] = right(s"$what state")(
          codecs.newEpochState[Babbage].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val stored = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        Seen(
          what,
          right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Babbage)),
          right(s"$what typed")(BabbageTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          stored,
          nes.es.ls.utxo.gov.curPParams,
          nes.es.ls.cert.dState.genDelegs.map((g, pair) => g.bytes -> pair),
          clock
        )
      }
    }

  private enum Collected:
    case NoRedeemer(tag: Int, ix: BigInt)
    case NoWitness(hash: Bytes)
    case NoCostModel(language: PlutusLanguage)
    case BadTranslation(failure: TranslationError)

  private def asIx(p: AlonzoPlutusPurpose[AsIxItem, Babbage]): AlonzoPlutusPurpose[AsIx, Babbage] =
    EraScript.babbage
      .hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(
        p
      )

  private def needed(s: Seen): Vector[(AlonzoPlutusPurpose[AsIxItem, Babbage], Bytes)] =
    u.getScriptsNeeded[TxLevel.Top](s.utxo, t.body[TxLevel.Top](s.era)).unAlonzoScriptsNeeded

  private def sameCollection(
      s: Seen
  ): Either[List[Collected], List[xyz.kd5ujc.scarab.plutus.EvalRequest]] =
    val era = AlonzoPlutusEvaluate
      .collectPlutusScriptsWithContext[Babbage](s.posix, s.pp, s.era, s.utxo)
      .fold(
        es =>
          Left(es.toList.map {
            case CollectError.NoRedeemer(purpose) =>
              val p = needed(s)
                .map(_._1)
                .find(p =>
                  EraScript.babbage.hoistPlutusPurpose[AsIxItem, AsItem](
                    [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
                  )(p) == purpose
                )
              val ix = asIx(p.getOrElse(fail(s"${s.label}: $purpose is not needed")))
              Collected.NoRedeemer(ix.tag, ix.ix.value)
            case CollectError.NoWitness(hash)         => Collected.NoWitness(hash)
            case CollectError.NoCostModel(language)   => Collected.NoCostModel(language)
            case CollectError.BadTranslation(failure) => Collected.BadTranslation(asFlat(failure))
          }),
        pwcs => Right(pwcs.map(PlutusWithContext.evalRequest))
      )
    val flat = Phase2
      .collect[Babbage](s.flat, s.params, s.scripts, s.spent, s.references, s.posix)
      .fold(
        es =>
          Left(es.toVector.toList.map {
            case FlatCollectError.NoRedeemer(p)     => Collected.NoRedeemer(p.tag, BigInt(p.index))
            case FlatCollectError.NoWitness(h)      => Collected.NoWitness(h)
            case FlatCollectError.NoCostModel(l)    => Collected.NoCostModel(l)
            case FlatCollectError.BadTranslation(f) => Collected.BadTranslation(f)
          }),
        rs => Right(rs.toList.map(_._2))
      )
    assertEquals(era, flat, s.label)
    era

  private def flatWitsNeeded(s: Seen): Set[Bytes] =
    BabbageTestKit.flatWitsNeeded(s.flat, s.known, s.genDelegs)

  test(
    "every Babbage case of the refusal corpora: TxInfos, contexts, scripts and keys are the flat path's"
  ) {
    // the Babbage cases of the corpora: a loader that loses one fails here
    assertEquals(refused.size, 32)
    val outcomes = refused.map { s =>
      val result = ctx.mkTxInfoResult(s.lti)
      val v1 = ctx
        .lookupTxInfoResult[Language.PlutusV1](result)
        .unPlutusTxInfoResult
        .left
        .map(asFlat)
        .map(ToData[PV1.TxInfo].toBuiltinData)
      val v2 = ctx
        .lookupTxInfoResult[Language.PlutusV2](result)
        .unPlutusTxInfoResult
        .left
        .map(asFlat)
        .map(ToData[PV2.TxInfo].toBuiltinData)
      assertEquals(v1, ScriptContexts.txInfo(PlutusLanguage.V1, s.flat, s.env), s.label)
      assertEquals(v2, ScriptContexts.txInfo(PlutusLanguage.V2, s.flat, s.env), s.label)
      val collected = sameCollection(s)
      val ann = FlatStAnnTx(s.flat, Utxo(s.known))
      assertEquals(
        needed(s).map((p, h) => (asIx(p).tag, asIx(p).ix.value, h)),
        ann.needed[Babbage].map((p, h) => (p.tag, BigInt(p.index), h)),
        s.label
      )
      val empty: ShelleyCertState = Empty.certState[Babbage]
      val certState = empty.copy(dState =
        empty.dState.copy(genDelegs =
          empty.dState.genDelegs ++ s.genDelegs.map((g, pair) =>
            right("genesis key")(KeyHash.fromBytes[Genesis](g)) -> pair
          )
        )
      )
      assertEquals(
        u.getWitsVKeyNeeded[TxLevel.Top](certState, s.utxo, t.body[TxLevel.Top](s.era)),
        flatWitsNeeded(s),
        s.label
      )
      (Vector(v1, v2), collected, needed(s).size)
    }
    // what the cases reach: 64 TxInfos, 59 built and five refused; seven requests and one redeemer
    // missing; nine scripts needed
    val infos = outcomes.flatMap(_._1)
    assertEquals(infos.count(_.isRight), 59)
    assertEquals(
      infos.collect { case Left(e) => e.productPrefix }.groupMapReduce(identity)(_ => 1)(_ + _),
      Map("ReferenceScriptsNotSupported" -> 1, "ByronTxOutInContext" -> 2, "UnresolvedInput" -> 2)
    )
    assertEquals(outcomes.map(_._2).collect { case Right(rs) => rs.size }.sum, 7)
    assertEquals(
      outcomes.map(_._2).collect { case Left(es) => es.map(_.productPrefix) }.flatten,
      Vector("NoRedeemer")
    )
    assertEquals(outcomes.map(_._3).sum, 9)
    // the genesis delegations are in force, and a case proposes an update they must sign
    assert(refused.exists(s => s.genDelegs.nonEmpty && s.flat.body.update.isDefined))
  }

  // ---- the context matrix's Babbage rows, against the reference's bytes

  private def matrixRow(row: Json): Seen =
    val name = field[String](row, "name")
    val parts = Cbor
      .decode(hex(field[String](row, "transaction")))
      .fold(e => fail(e.toString), identity)
      .value match
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
    val known = field[Vector[Json]](row, "utxo").map { entry =>
      right("input")(
        TxIn.decode(right("input")(Cbor.decode(hex(field[String](entry, "input")))))
      ) ->
        right("output")(FlatTxOut.fromBytes(hex(field[String](entry, "output"))))
    }.toMap
    val start = BigInt(field[Int](row, "systemStartSeconds")) * 1000
    val length = BigInt(field[Int](row, "slotLengthMilliseconds"))
    Seen(
      name,
      right(name)(FlatTx.fromBlock(parts, Era.Babbage)),
      right(name)(BabbageTx.decode(parts, StateLayout.Node11_1)),
      known,
      TestLedger
        .params(field[Int](row, "protocolMajor"))
        .copy(costModels =
          field[Map[String, Vector[String]]](row, "costModels")
            .map((language, values) => language.toInt -> values.map(BigInt(_)))
        ),
      Map.empty,
      slot => Right(start + slot * length)
    )

  test(
    "each Babbage row of the context matrix: the reference's request bytes, or its CollectError bytes"
  ) {
    val rows = parse(Files.readString(Path.of("fixtures/reference/context-matrix/matrix.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .filter(field[String](_, "era") == "Babbage")
    val outcomes = rows.map { row =>
      val s = matrixRow(row)
      val oracle = field[Json](row, "oracle")
      AlonzoPlutusEvaluate
        .collectPlutusScriptsWithContext[Babbage](s.posix, s.pp, s.era, s.utxo) match
        case Right(pwcs) =>
          val expected = field[Vector[Json]](oracle, "scripts")
          assert(field[Boolean](oracle, "collected"), s.label)
          assertEquals(pwcs.size, expected.size, s.label)
          pwcs
            .zip(expected)
            .foreach((pwc, reference) =>
              assertEquals(
                PlutusFailureCbor.context(PlutusWithContext.evalRequest(pwc)),
                hex(field[String](reference, "contextCbor")),
                s.label
              )
            )
          true
        case Left(errors) =>
          assert(!field[Boolean](oracle, "collected"), s.label)
          // each failure the era's typed cause, a translation's as the flat failure the encoder takes
          val flat = errors.toList.map {
            case CollectError.BadTranslation(failure) =>
              FlatCollectError.BadTranslation(asFlat(failure))
            case other => fail(s"${s.label}: $other")
          }
          val reference = CborOut.build(w =>
            w.array(2).uint(1).raw(hex(field[String](oracle, "collectErrorsCbor")))
          )
          assertEquals(
            ContextFailureCbor.utxos(
              ContextFailureCbor.ContextEra.Babbage,
              NonEmptyVector.fromVector(flat.toVector).getOrElse(fail("no failure"))
            ),
            Some(reference),
            s.label
          )
          false
    }
    // 34 rows collected and 20 refused
    assertEquals((outcomes.count(identity), outcomes.count(!_)), (34, 20))
  }

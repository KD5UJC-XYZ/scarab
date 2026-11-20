// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.NonEmptyList
import io.circe.Json
import java.time.Instant
import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{Credential as FlatCredential, RewardAccount, TxIn, Voter}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoPlutusEvaluate,
  AsIx,
  AsIxItem,
  BabbageContextError,
  CollectError,
  ContextError,
  CostModel,
  CostModels,
  EraPlutusContext,
  EraPlutusTxInfo,
  EraScript,
  EraTx,
  EraTxBody,
  EraUTxO,
  Language,
  LedgerLevelTxInfo,
  LedgerTxInfo,
  PlutusLanguage as EraLanguage,
  PlutusRunnable,
  PlutusTxInfoResult,
  PlutusWithContext,
  ProtVer,
  SupportedLanguage,
  SupportedPlutusRunnable,
  TxLevel,
  UTxO,
  Version,
  AlonzoContextError,
  BothLevels
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayContextError
import xyz.kd5ujc.scarab.ledger.state.{Credential, Dijkstra, TxIx}
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

import DijkstraCorpus.*

/** Dijkstra's Plutus V1 to V3 script contexts on the era types (`EraPlutusTxInfo 'PlutusV1` to
  * `'PlutusV3` of `DijkstraEra`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:333-569`, cardano-ledger 9df9f98c)
  * against the Leios build's own contexts, byte for byte.
  *
  * Each transaction is annotated as the reference's `mkDijkstraStAnnTopTx` and
  * `mkDijkstraStAnnSubTx` do it (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:110-219`): the
  * scripts needed of each level resolved against the batch's scripts provided
  * (`resolveNeededPlutusScriptsWithPurpose`), each sub-transaction's information at its index and
  * memoised, and the top level's information holding them by id. The scripts are collected with
  * Alonzo's collection of the whole level (`scriptsWithContextFromLedgerTxInfoWithResult`), the
  * era's languages through a context of this suite ([[context]]): Dijkstra's `EraPlutusContext`
  * instance comes with its Plutus V4 translation (#85), so a V4 script's runnable is made here with
  * a translation that fails the test if anything asks it for one. A level that also uses a V4
  * script is compared script by script (`mkPlutusWithContext`), its V1 to V3 contexts alone; its V4
  * contexts, and the refusal of a level that runs a V4 script alone, are not compared until
  * Dijkstra's Plutus V4 contexts (CNF-2), and the suite counts and names them.
  *
  * The chain's 200 transactions (`fixtures/reference/leios-testnet-chain-contexts`): each of the
  * 239 Plutus V1 to V3 contexts the reference collected is the typed one, the language, the script,
  * the arguments' bytes, the execution units and the cost model, 238 through their level's
  * collection; none is at a sub-transaction's level. The refusals
  * (`fixtures/reference/leios-testnet-context-refusals`): every level of the 16 cases whose V1 to
  * V3 scripts the reference refuses or collects is compared, a refusal as the typed `CollectError`
  * s with every field the reference's `Show` records (`parseShown`, [[ours]]); the 2 cases refused
  * for a V4 context wait for V4's translation.
  */
class DijkstraPlutusContextSuite extends FunSuite:
  private val txs = EraTx.dijkstra
  private val bodies = EraTxBody.dijkstra
  private val utxos = EraUTxO.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  // ---- the era's script contexts, with a placeholder for Plutus V4's translation

  /** Plutus V4's translation at Dijkstra, which comes with Dijkstra's Plutus V4 contexts (#85): a
    * runnable script needs one, and nothing here may ask it for anything.
    */
  private val v4Placeholder: EraPlutusTxInfo[Language.PlutusV4, Dijkstra] =
    new EraPlutusTxInfo[Language.PlutusV4, Dijkstra]:
      private def absent = fail("Plutus V4's translation at Dijkstra was asked for")
      override def plutusLanguage: EraLanguage[Language.PlutusV4] = EraLanguage.v4
      override def toPlutusTxCert(pv: ProtVer, txCert: DijkstraTxCert) = absent
      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          purpose: DijkstraPlutusPurpose[AsIxItem]
      ) = absent
      override def toPlutusTxInfo[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra]
      ): PlutusTxInfoResult[Language.PlutusV4, Dijkstra] = absent
      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          txInfo: xyz.kd5ujc.scarab.ledger.era.PV4.TxInfo,
          purpose: DijkstraPlutusPurpose[AsIxItem],
          redeemerData: xyz.kd5ujc.scarab.ledger.era.Data
      ) = absent
      override def toPlutusTxInInfo(utxo: UTxO[Dijkstra], txIn: TxIn) = absent
      override def lookupTxInfoResult(
          result: DijkstraTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV4, Dijkstra] = absent

  /** Dijkstra's script contexts (`instance EraPlutusContext DijkstraEra`, `TxInfo.hs:299-331`,
    * cardano-ledger 9df9f98c) with Plutus V1 to V3's translations and V4's placeholder.
    */
  private given context: EraPlutusContext[Dijkstra] = new EraPlutusContext[Dijkstra]:
    override def mkSupportedLanguage(lang: PlutusLanguage): Option[SupportedLanguage[Dijkstra]] =
      lang match
        case PlutusLanguage.V1 => Some(SupportedLanguage(EraPlutusTxInfo.dijkstraPlutusV1))
        case PlutusLanguage.V2 => Some(SupportedLanguage(EraPlutusTxInfo.dijkstraPlutusV2))
        case PlutusLanguage.V3 => Some(SupportedLanguage(EraPlutusTxInfo.dijkstraPlutusV3))
        case PlutusLanguage.V4 => Some(SupportedLanguage(v4Placeholder))
    override def mkSupportedPlutusRunnable(
        v: Version,
        plutusScript: DijkstraPlutusScript
    ): SupportedPlutusRunnable[Dijkstra] = plutusScript match
      case DijkstraPlutusScript.V1(p) =>
        SupportedPlutusRunnable(
          EraPlutusTxInfo.dijkstraPlutusV1,
          PlutusRunnable.decodePlutusRunnable(v, p)
        )
      case DijkstraPlutusScript.V2(p) =>
        SupportedPlutusRunnable(
          EraPlutusTxInfo.dijkstraPlutusV2,
          PlutusRunnable.decodePlutusRunnable(v, p)
        )
      case DijkstraPlutusScript.V3(p) =>
        SupportedPlutusRunnable(
          EraPlutusTxInfo.dijkstraPlutusV3,
          PlutusRunnable.decodePlutusRunnable(v, p)
        )
      case DijkstraPlutusScript.V4(p) =>
        SupportedPlutusRunnable(v4Placeholder, PlutusRunnable.decodePlutusRunnable(v, p))
    override def mkTxInfoResult[T <: TxLevel](
        lti: LedgerTxInfo[T, Dijkstra]
    ): DijkstraTxInfoResult =
      DijkstraTxInfoResult(
        EraPlutusTxInfo.dijkstraPlutusV1.toPlutusTxInfo(lti),
        EraPlutusTxInfo.dijkstraPlutusV2.toPlutusTxInfo(lti),
        EraPlutusTxInfo.dijkstraPlutusV3.toPlutusTxInfo(lti),
        v4Placeholder.toPlutusTxInfo(lti)
      )

  // ---- the environment of both fixtures

  /** The languages by the name the reference gives them (`PlutusV1` to `PlutusV4`). */
  private val languageNames: Map[String, PlutusLanguage] =
    PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap

  private final case class Environment(
      protVer: ProtVer,
      clock: BigInt => Either[xyz.kd5ujc.scarab.ledger.TranslationError, BigInt],
      costModels: CostModels
  )

  /** The protocol version, the slot to POSIX milliseconds of the oracle's epoch information (the
    * system start and slots of `slotLength` seconds, `fixedEpochInfo`) and the cost models of a
    * fixture's `cases.json`.
    */
  private def environment(j: Json): Environment =
    val c = j.hcursor
    def num(name: String): BigInt = c.get[BigInt](name).fold(e => fail(e.message), identity)
    val Vector(major, minor) =
      c.get[Vector[Int]]("protocolVersion").fold(e => fail(e.message), identity): @unchecked
    val start = BigInt(Instant.parse(str(j, "systemStart")).getEpochSecond)
    val length = num("slotLength")
    val models = c
      .get[Map[String, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)
      .map((name, values) => languageNames.getOrElse(name, fail(name)).id -> values)
    Environment(
      ProtVer(
        Version.mkVersion(major).getOrElse(fail(s"version $major")),
        Unsigned.from[Unsigned.Width.W32](BigInt(minor)).getOrElse(fail("minor"))
      ),
      slot => Right((start + slot * length) * 1000),
      CostModels.from(models).fold(e => fail(e.toString), identity)
    )

  // ---- a transaction annotated as the reference annotates it

  private type Used = List[(DijkstraPlutusPurpose[AsIxItem], SupportedPlutusRunnable[Dijkstra])]

  /** A level of a transaction: its information, memoised information and scripts used. */
  private final case class Level[T <: TxLevel](
      lti: LedgerTxInfo[T, Dijkstra],
      result: DijkstraTxInfoResult
  ):
    def used: Used = lti.ltiScriptsUsed

  /** The top level and each sub-transaction's, as `mkDijkstraStAnnTopTx` and `mkDijkstraStAnnSubTx`
    * make them (`Dijkstra.hs:110-219`, cardano-ledger 9df9f98c).
    */
  private def annotate(
      env: Environment,
      tx: DijkstraTx[TxLevel.Top],
      utxo: UTxO[Dijkstra]
  ): (Level[TxLevel.Top], Vector[Level[TxLevel.Sub]]) =
    val body = txs.body[TxLevel.Top](tx)
    val provided = utxos.getScriptsProvided[TxLevel.Top](utxo, tx)
    val (cache, used) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose(
      env.protVer,
      provided,
      utxos.getScriptsNeeded[TxLevel.Top](utxo, body),
      Map.empty
    )
    val subTxs = bodies.subTransactions(body).entries
    val subs = TxIx.enumerate(subTxs.map(_._2)).map { (sub, ix) =>
      given BothLevels[TxLevel.Sub] = BothLevels.Sub
      val (_, subUsed) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose(
        env.protVer,
        provided,
        utxos.getScriptsNeeded[TxLevel.Sub](utxo, txs.body[TxLevel.Sub](sub)),
        cache
      )
      val lti = LedgerTxInfo(
        env.protVer,
        env.clock,
        utxo,
        sub,
        subUsed,
        LedgerLevelTxInfo.LedgerSubTxInfo(ix)
      )
      Level[TxLevel.Sub](lti, context.mkTxInfoResult(lti))
    }
    val lti = LedgerTxInfo(
      env.protVer,
      env.clock,
      utxo,
      tx,
      used,
      LedgerLevelTxInfo.LedgerTopTxInfo(
        subTxs.zip(subs).map((s, l) => s._1 -> l.result).toMap
      )
    )
    (Level(lti, context.mkTxInfoResult(lti)), subs)

  private def txIn(i: DijkstraTxIn): TxIn = TxIn(i.id, BigInt(i.index))

  /** A case's transaction and the UTxO set it was recorded with. */
  private def read(c: Json): (DijkstraTx[TxLevel.Top], UTxO[Dijkstra]) =
    val tx = DijkstraTx.inBlock(node(hex(str(c, "bytes")))).fold(e => fail(e.toString), identity)
    val utxo = c.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map { (i, o) =>
        txIn(DijkstraTxIn.decode(node(hex(i))).fold(e => fail(e.toString), identity)) ->
          DijkstraTxOut.decode(node(hex(o))).fold(e => fail(e.toString), identity)
      }
    (tx, UTxO[Dijkstra](utxo.toMap))

  // ---- the reference's records

  /** A context the reference collected, read from its `PlutusWithContext` encoding: the protocol
    * version, the script's language and bytes, the arguments' bytes, the units and the cost model.
    */
  private final case class Reference(
      major: BigInt,
      language: BigInt,
      script: Bytes,
      args: Vector[Bytes],
      units: (BigInt, BigInt),
      costModel: Vector[BigInt]
  )

  private def reference(encoding: String): Reference =
    def uint(n: xyz.kd5ujc.scarab.codec.Node): BigInt = n.value match
      case CborValue.UInt(x) => x
      case CborValue.NInt(x) => x
      case other             => fail(s"not an integer: $other")
    def items(n: xyz.kd5ujc.scarab.codec.Node) = n.value match
      case CborValue.Arr(xs) => xs
      case other             => fail(s"not an array: $other")
    items(node(hex(encoding))) match
      case Vector(pv, s, arguments, units, costModel) =>
        val (language, script) = items(s) match
          case Vector(l, b) =>
            b.value match
              case CborValue.ByteString(bytes) => (uint(l), bytes)
              case other                       => fail(s"not a script: $other")
          case other => fail(s"not a script: $other")
        val args = arguments.value match
          case CborValue.Arr(xs) => xs.map(_.original)
          case _                 => Vector(arguments.original)
        val Vector(mem, steps) = items(units).map(uint): @unchecked
        Reference(uint(pv), language, script, args, (mem, steps), items(costModel).map(uint))
      case other => fail(s"not a PlutusWithContext: $other")

  /** A level of the reference's answer: the contexts it collected, or the record of the failure
    * (`Show`), which [[parseShown]] reads.
    */
  private enum RecordedLevel:
    case Collected(contexts: Vector[Reference])
    case Refused(record: String)

  private def recordedLevel(level: Json): RecordedLevel =
    level.hcursor.get[String]("failure") match
      case Right(failure) => RecordedLevel.Refused(failure)
      case Left(_) =>
        RecordedLevel.Collected(
          level.hcursor
            .get[Vector[String]]("contexts")
            .fold(e => fail(e.message), identity)
            .map(reference)
        )

  // ---- the comparisons

  /** Our context of a script used, compared with the reference's. */
  private def same(at: String, pwc: PlutusWithContext, ref: Reference): Unit =
    val request = PlutusWithContext.evalRequest(pwc)
    assertEquals(BigInt(request.protocolMajor), ref.major, at)
    assertEquals(BigInt(request.language.id), ref.language, at)
    assertEquals(request.script, ref.script, at)
    assertEquals(request.args.map(DataSerialisation.serialise), ref.args, s"$at: arguments")
    assertEquals((request.budget.mem, request.budget.steps), ref.units, at)
    assertEquals(request.costModel.map(BigInt(_)), ref.costModel, at)

  private def asIx(p: DijkstraPlutusPurpose[AsIxItem]): DijkstraPlutusPurpose[AsIx] =
    EraScript.dijkstra.hoistPlutusPurpose[AsIxItem, AsIx](
      [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a)
    )(p)

  /** The context of one script used, made as the collection makes it (`mkPlutusWithContext`), with
    * its redeemer and its language's cost model.
    */
  private def contextOf[T <: TxLevel](
      at: String,
      env: Environment,
      level: Level[T],
      purpose: DijkstraPlutusPurpose[AsIxItem],
      script: SupportedPlutusRunnable[Dijkstra]
  )(using BothLevels[T]): PlutusWithContext =
    val (redeemer, units) = txs.witsOf
      .rdmrsTxWits(txs.wits[T](level.lti.ltiTx))
      .entries
      .getOrElse(asIx(purpose), fail(s"$at: no redeemer"))
    val costModel = CostModel
      .lookupCostModel[script.L](env.costModels)(using script.plutusTxInfo.plutusLanguage)
      .getOrElse(fail(s"$at: no cost model"))
    AlonzoPlutusEvaluate
      .mkPlutusWithContext(script)(purpose, level.lti, level.result, redeemer, units, costModel)
      .fold(e => fail(s"$at: $e"), identity)

  /** The level's V1 to V3 contexts compared with the reference's, script by script, in the order
    * the collection gives them (each in front of the ones before it); the V4 ones counted.
    */
  private def byScript[T <: TxLevel](
      at: String,
      env: Environment,
      level: Level[T],
      recorded: Vector[Reference]
  )(using BothLevels[T]): (Int, Int) =
    assertEquals(recorded.size, level.used.size, at)
    level.used.reverse.zip(recorded).zipWithIndex.foldLeft((0, 0)) {
      case ((compared, v4), (((purpose, script), ref), k)) =>
        val here = s"$at, script $k"
        assertEquals(BigInt(script.language.id), ref.language, here)
        assertEquals(script.plutusRunnable.plutusRunnableBinary.binary, ref.script, here)
        if script.language == PlutusLanguage.V4 then (compared, v4 + 1)
        else
          same(here, contextOf(here, env, level, purpose, script), ref)
          (compared + 1, v4)
    }

  /** What a level compared: its V1 to V3 contexts through the whole level's collection, or script
    * by script on a level that also uses a Plutus V4 script; the V4 contexts not compared; its
    * refusal; and a refusal not compared, of a level that runs a Plutus V4 script alone.
    */
  private final case class Compared(
      collected: scala.Int,
      perScript: scala.Int,
      v4: scala.Int,
      refusals: scala.Int,
      deferred: Option[String]
  )

  /** A level compared with the reference's record. A level that uses no Plutus V4 script is
    * collected whole (`scriptsWithContextFromLedgerTxInfoWithResult`, as `mkDijkstraStAnnTopTx` and
    * `mkDijkstraStAnnSubTx` collect it, `Dijkstra.hs:110-219`, cardano-ledger 9df9f98c): its
    * contexts in order, or its failure, every field of which is the record's. A level that also
    * uses one is compared script by script, its V1 to V3 contexts alone; its refusal is not
    * compared, and must be of a level that runs no V1 to V3 script.
    */
  private def compareLevel[T <: TxLevel](
      at: String,
      env: Environment,
      level: Level[T],
      recorded: Json
  )(using BothLevels[T]): Compared =
    val legacy = level.used.count(_._2.language != PlutusLanguage.V4)
    val usesV4 = level.used.size > legacy
    (recordedLevel(recorded), usesV4) match
      case (RecordedLevel.Collected(refs), true) =>
        val (compared, v4) = byScript(at, env, level, refs)
        Compared(0, compared, v4, 0, None)
      case (RecordedLevel.Refused(failure), true) =>
        assertEquals(legacy, 0, s"$at: a deferred refusal of a level that runs a V1 to V3 script")
        Compared(0, 0, 0, 0, Some(failure))
      case (record, false) =>
        val collection = AlonzoPlutusEvaluate.scriptsWithContextFromLedgerTxInfoWithResult(
          level.lti,
          level.result,
          env.costModels
        )
        (record, collection) match
          case (RecordedLevel.Collected(refs), Right(pwcs)) =>
            assertEquals(pwcs.size, refs.size, at)
            pwcs.zip(refs).zipWithIndex.foreach { case ((pwc, ref), k) =>
              same(s"$at, script $k", pwc, ref)
            }
            Compared(pwcs.size, 0, 0, 0, None)
          case (RecordedLevel.Refused(failure), Left(es)) =>
            assertEquals(ours(at, es), parseShown(at, failure), at)
            Compared(0, 0, 0, 1, None)
          case other => fail(s"$at: $other")

  // ---- the chain's transactions

  private lazy val chain = json("fixtures/reference/leios-testnet-chain-contexts/cases.json")
  private lazy val chainResults =
    json("fixtures/reference/leios-testnet-chain-contexts/results.json").asArray
      .getOrElse(fail("results.json is not an array"))
      .map(r => str(r, "name") -> r)
      .toMap

  private def levels(result: Json): (Json, Vector[Json]) =
    val r = result.hcursor.downField("result")
    (
      r.downField("top").focus.getOrElse(fail("no top level")),
      r.get[Vector[Json]]("subtxs").fold(e => fail(e.message), identity)
    )

  test("each Plutus V1 to V3 context recorded on the Leios testnet's chain is the typed one") {
    val env = environment(chain)
    val compared = chainContextCases.flatMap { c =>
      val name = str(c, "name")
      val (tx, utxo) = read(c)
      val (top, subs) = annotate(env, tx, utxo)
      val (recordedTop, recordedSubs) = levels(chainResults.getOrElse(name, fail(name)))
      assertEquals(recordedSubs.size, subs.size, name)
      val subCompared = subs.zip(recordedSubs).zipWithIndex.map { case ((level, recorded), i) =>
        given BothLevels[TxLevel.Sub] = BothLevels.Sub
        compareLevel(s"$name, sub-transaction $i", env, level, recorded)
      }
      // the chain's ledger runs no V1 to V3 script in a sub-transaction
      assertEquals(subCompared.map(l => l.collected + l.perScript).sum, 0, name)
      compareLevel(name, env, top, recordedTop) +: subCompared
    }
    assertEquals(chainContextCases.size, 200)
    // every level collected, none refused
    assertEquals(compared.map(_.refusals).sum, 0)
    assertEquals(compared.flatMap(_.deferred), Vector())
    // 68 PlutusV1, 54 PlutusV2 and 117 PlutusV3 contexts compared: 238 through the collection of
    // their whole level, and one script by script on a level that also runs a PlutusV4 script; the
    // 34 PlutusV4 ones wait for Dijkstra's Plutus V4 translation (#85, CNF-2)
    assertEquals(compared.map(_.collected).sum, 238)
    assertEquals(compared.map(_.perScript).sum, 1)
    assertEquals(compared.map(_.v4).sum, 34)
  }

  // ---- the refusals, as the reference's `Show` gives them

  /** A value as Haskell's derived `Show` presents it: a constructor with its arguments, a record
    * with its fields, a string, an integer, a list, a tuple and a non-empty list (`x :| xs`).
    */
  private enum Shown:
    case Con(name: String, args: List[Shown])
    case Rec(name: String, fields: List[(String, Shown)])
    case Str(value: String)
    case Int(value: BigInt)
    case Lst(items: List[Shown])
    case Tup(items: List[Shown])
    case NonEmpty(head: Shown, tail: Shown)

  private enum Token:
    case Open, Close, OpenList, CloseList, OpenRec, CloseRec, Comma, Equals, Cons
    case Ident(name: String)
    case Text(value: String)
    case Num(value: BigInt)

  /** The tokens of a record of the reference's `Show`, none of whose strings has an escape. */
  private def tokens(at: String, text: String): List[Token] =
    @tailrec def go(i: scala.Int, acc: List[Token]): List[Token] =
      if i >= text.length then acc.reverse
      else
        text(i) match
          case ' '                             => go(i + 1, acc)
          case '('                             => go(i + 1, Token.Open :: acc)
          case ')'                             => go(i + 1, Token.Close :: acc)
          case '['                             => go(i + 1, Token.OpenList :: acc)
          case ']'                             => go(i + 1, Token.CloseList :: acc)
          case '{'                             => go(i + 1, Token.OpenRec :: acc)
          case '}'                             => go(i + 1, Token.CloseRec :: acc)
          case ','                             => go(i + 1, Token.Comma :: acc)
          case '='                             => go(i + 1, Token.Equals :: acc)
          case ':' if text.startsWith(":|", i) => go(i + 2, Token.Cons :: acc)
          case '"' =>
            val end = text.indexOf('"', i + 1)
            assert(end > i && !text.substring(i + 1, end).contains('\\'), s"$at: a string at $i")
            go(end + 1, Token.Text(text.substring(i + 1, end)) :: acc)
          case c if c.isDigit =>
            val end = text.indexWhere(!_.isDigit, i) match
              case -1 => text.length
              case e  => e
            go(end, Token.Num(BigInt(text.substring(i, end))) :: acc)
          case c if c.isLetter =>
            val end =
              text.indexWhere(ch => !(ch.isLetterOrDigit || ch == '_' || ch == '\''), i) match
                case -1 => text.length
                case e  => e
            go(end, Token.Ident(text.substring(i, end)) :: acc)
          case other => fail(s"$at: '$other' at $i")
    go(0, Nil)

  /** The reference's record read as [[Shown]]: an application of a constructor to its arguments
    * binds tighter than `:|`, and a record's braces tighter than an application. The record nests a
    * handful of levels deep, so the descent is bounded by the fixture.
    */
  private def parseShown(at: String, text: String): Shown =
    def expr(ts: List[Token]): (Shown, List[Token]) =
      val (x, rest) = app(ts)
      rest match
        case Token.Cons :: more =>
          val (xs, rest2) = expr(more)
          (Shown.NonEmpty(x, xs), rest2)
        case _ => (x, rest)
    def app(ts: List[Token]): (Shown, List[Token]) =
      atom(ts) match
        case (Shown.Con(name, Nil), rest) =>
          @tailrec def args(r: List[Token], acc: List[Shown]): (List[Shown], List[Token]) =
            r match
              case (Token.Open | Token.OpenList | Token.Text(_) | Token.Num(_) |
                  Token.Ident(_)) :: _ =>
                val (a, r2) = atom(r)
                args(r2, a :: acc)
              case _ => (acc.reverse, r)
          val (as, rest2) = args(rest, Nil)
          (Shown.Con(name, as), rest2)
        case other => other
    def items(ts: List[Token], close: Token): (List[Shown], List[Token]) =
      @tailrec def go(r: List[Token], acc: List[Shown]): (List[Shown], List[Token]) = r match
        case `close` :: rest if acc.isEmpty => (Nil, rest)
        case _ =>
          val (x, r2) = expr(r)
          r2 match
            case Token.Comma :: r3 => go(r3, x :: acc)
            case `close` :: r3     => ((x :: acc).reverse, r3)
            case other             => fail(s"$at: $other")
      go(ts, Nil)
    def fields(ts: List[Token]): (List[(String, Shown)], List[Token]) =
      @tailrec def go(
          r: List[Token],
          acc: List[(String, Shown)]
      ): (List[(String, Shown)], List[Token]) =
        r match
          case Token.Ident(name) :: Token.Equals :: rest =>
            val (x, r2) = expr(rest)
            r2 match
              case Token.Comma :: r3    => go(r3, (name -> x) :: acc)
              case Token.CloseRec :: r3 => (((name -> x) :: acc).reverse, r3)
              case other                => fail(s"$at: $other")
          case other => fail(s"$at: $other")
      go(ts, Nil)
    def atom(ts: List[Token]): (Shown, List[Token]) = ts match
      case Token.Open :: rest =>
        items(rest, Token.Close) match
          case (List(x), r) => (x, r)
          case (xs, r)      => (Shown.Tup(xs), r)
      case Token.OpenList :: rest =>
        val (xs, r) = items(rest, Token.CloseList)
        (Shown.Lst(xs), r)
      case Token.Text(s) :: rest => (Shown.Str(s), rest)
      case Token.Num(n) :: rest  => (Shown.Int(n), rest)
      case Token.Ident(name) :: Token.OpenRec :: rest =>
        val (fs, r) = fields(rest)
        (Shown.Rec(name, fs), r)
      case Token.Ident(name) :: rest => (Shown.Con(name, Nil), rest)
      case other                     => fail(s"$at: $other")
    expr(tokens(at, text)) match
      case (x, Nil)  => x
      case (_, rest) => fail(s"$at: left over $rest")

  // Our typed values as the reference's `Show` presents its counterparts: every field, by the
  // reference's constructor and field names. Only the payloads the fixture's refusals have are
  // written; another fails the test.

  private def con(name: String, args: Shown*): Shown = Shown.Con(name, args.toList)
  private def rec(name: String, fields: (String, Shown)*): Shown = Shown.Rec(name, fields.toList)
  private def hexOf(b: Bytes): Shown = Shown.Str(b.hex)
  private def coin(c: xyz.kd5ujc.scarab.ledger.state.Coin): Shown = con("Coin", Shown.Int(c.value))
  private def safeHash(b: Bytes): Shown = con("SafeHash", hexOf(b))
  private def txIdShown(b: Bytes): Shown = rec("TxId", "unTxId" -> safeHash(b))
  private def fromList(entries: List[Shown]): Shown = con("fromList", Shown.Lst(entries))
  private def maybe[A](o: Option[A])(f: A => Shown): Shown =
    o.fold(con("SNothing"))(a => con("SJust", f(a)))

  private def keyHash(b: Bytes): Shown = rec("KeyHash", "unKeyHash" -> hexOf(b))

  private def flatCredential(c: FlatCredential): Shown = c match
    case FlatCredential.Key(h)    => con("KeyHashObj", keyHash(h))
    case FlatCredential.Script(h) => con("ScriptHashObj", con("ScriptHash", hexOf(h)))

  private def credential[R <: xyz.kd5ujc.scarab.ledger.state.KeyRole](c: Credential[R]): Shown =
    c match
      case Credential.KeyHashObj(h)    => con("KeyHashObj", keyHash(h.bytes))
      case Credential.ScriptHashObj(h) => con("ScriptHashObj", con("ScriptHash", hexOf(h.bytes)))

  private def accountAddress(a: RewardAccount): Shown =
    rec(
      "AccountAddress",
      "aaNetworkId" -> con(if a.network == 1 then "Mainnet" else "Testnet"),
      "aaId" -> rec("AccountId", "unAccountId" -> flatCredential(a.credential))
    )

  private def anchor(a: xyz.kd5ujc.scarab.ledger.Anchor): Shown =
    rec(
      "Anchor",
      "anchorUrl" -> rec("Url", "urlToText" -> Shown.Str(a.url)),
      "anchorDataHash" -> safeHash(a.hash)
    )

  private def interval(i: AccountBalanceInterval): Shown = i match
    case AccountBalanceInterval.AccountBalanceLowerBound(l) =>
      con("AccountBalanceLowerBound", coin(l.unInclusive))
    case AccountBalanceInterval.AccountBalanceUpperBound(u) =>
      con("AccountBalanceUpperBound", coin(u.unExclusive))
    case AccountBalanceInterval.AccountBalanceBothBounds(l, u) =>
      con("AccountBalanceBothBounds", coin(l.unInclusive), coin(u.unExclusive))
    case AccountBalanceInterval.AccountBalanceExact(c) => con("AccountBalanceExact", coin(c))

  private def voter(v: Voter): Shown = v match
    case Voter.CommitteeHot(c) => con("CommitteeVoter", flatCredential(c))
    case Voter.DRepVoter(c)    => con("DRepVoter", flatCredential(c))
    case Voter.Pool(h)         => con("StakePoolVoter", keyHash(h))

  private def vote(v: xyz.kd5ujc.scarab.ledger.Vote): Shown = v match
    case xyz.kd5ujc.scarab.ledger.Vote.No      => con("VoteNo")
    case xyz.kd5ujc.scarab.ledger.Vote.Yes     => con("VoteYes")
    case xyz.kd5ujc.scarab.ledger.Vote.Abstain => con("Abstain")

  private def govActionId(id: xyz.kd5ujc.scarab.ledger.GovActionId): Shown =
    rec(
      "GovActionId",
      "gaidTxId" -> txIdShown(id.txId),
      "gaidGovActionIx" -> rec("GovActionIx", "unGovActionIx" -> Shown.Int(id.index))
    )

  private def proposal(
      at: String,
      p: xyz.kd5ujc.scarab.ledger.era.ProposalProcedure[Dijkstra]
  ): Shown =
    rec(
      "ProposalProcedure",
      "pProcDeposit" -> coin(p.pProcDeposit),
      "pProcReturnAddr" -> accountAddress(p.pProcReturnAddr),
      "pProcGovAction" -> (p.pProcGovAction match
        case xyz.kd5ujc.scarab.ledger.era.GovAction.InfoAction() => con("InfoAction")
        case other => fail(s"$at: an action the fixture does not have: $other")),
      "pProcAnchor" -> anchor(p.pProcAnchor)
    )

  /** Our failure of a level as the reference's `Show (NonEmpty (CollectError era))` presents it. */
  private def ours(at: String, es: NonEmptyList[CollectError[Dijkstra]]): Shown =
    def other(x: Any): Shown = fail(s"$at: a refusal the fixture does not have: $x")
    def alonzo(a: AlonzoContextError[Dijkstra]): Shown = a match
      case AlonzoContextError.CertificateNotSupported(cert) =>
        con(
          "CertificateNotSupported",
          cert match
            case DijkstraTxCert.DijkstraTxCertGov(
                  xyz.kd5ujc.scarab.ledger.era.ConwayGovCert.ConwayRegDRep(c, deposit, a)
                ) =>
              con(
                "DijkstraTxCertGov",
                con("ConwayRegDRep", credential(c), coin(deposit), maybe(a)(anchor))
              )
            case x => other(x)
        )
      case x => other(x)
    def babbage(b: BabbageContextError[Dijkstra]): Shown = b match
      case BabbageContextError.AlonzoContextError(a) => con("AlonzoContextError", alonzo(a))
      case x                                         => other(x)
    def conway(c: ConwayContextError[Dijkstra]): Shown = c match
      case ConwayContextError.BabbageContextError(b) => con("BabbageContextError", babbage(b))
      case ConwayContextError.VotingProceduresFieldNotSupported(vps) =>
        con(
          "VotingProceduresFieldNotSupported",
          rec(
            "VotingProcedures",
            "unVotingProcedures" -> fromList(vps.unVotingProcedures.toList.map { (v, votes) =>
              Shown.Tup(
                List(
                  voter(v),
                  fromList(votes.toList.map { (id, p) =>
                    Shown.Tup(
                      List(
                        govActionId(id),
                        rec(
                          "VotingProcedure",
                          "vProcVote" -> vote(p.vote),
                          "vProcAnchor" -> maybe(p.anchor)(anchor)
                        )
                      )
                    )
                  })
                )
              )
            })
          )
        )
      case ConwayContextError.ProposalProceduresFieldNotSupported(ps) =>
        val inOrder = ps.toVector.toList.map(proposal(at, _))
        // the set's members in the sequence's order, which is the set's for the fixture's one
        // proposal; a case of several would need the procedures' `Ord`
        assertEquals(inOrder.size, 1, s"$at: proposals")
        con(
          "ProposalProceduresFieldNotSupported",
          rec(
            "OSet",
            "osSSeq" -> rec("StrictSeq", "fromStrict" -> fromList(inOrder)),
            "osSet" -> fromList(inOrder)
          )
        )
      case ConwayContextError.TreasuryDonationFieldNotSupported(c) =>
        con("TreasuryDonationFieldNotSupported", coin(c))
      case ConwayContextError.CurrentTreasuryFieldNotSupported(c) =>
        con("CurrentTreasuryFieldNotSupported", coin(c))
      case x => other(x)
    def dijkstra(d: ContextError[Dijkstra]): Shown = d match
      case DijkstraContextError.ConwayContextError(c) => con("ConwayContextError", conway(c))
      case DijkstraContextError.DirectDepositsNotSupported(dd) =>
        con(
          "DirectDepositsNotSupported",
          rec(
            "DirectDeposits",
            "unDirectDeposits" -> fromList(
              dd.toList.sortBy(_._1).map((a, c) => Shown.Tup(List(accountAddress(a), coin(c))))
            )
          )
        )
      case DijkstraContextError.AccountBalanceIntervalsNotSupported(abi) =>
        con(
          "AccountBalanceIntervalsNotSupported",
          fromList(
            abi.toList.sortBy(_._1).map((a, i) => Shown.Tup(List(accountAddress(a), interval(i))))
          )
        )
      case DijkstraContextError.RequiredTopLevelGuardsNotSupported(guards) =>
        con(
          "RequiredTopLevelGuardsNotSupported",
          con("NonEmptySet", fromList(guards.toSortedSet.toList.map(credential)))
        )
      case DijkstraContextError.GuardScriptHashesNotSupported(hashes) =>
        con(
          "GuardScriptHashesNotSupported",
          Shown.NonEmpty(
            con("ScriptHash", hexOf(hashes.head.bytes)),
            Shown.Lst(hashes.tail.map(h => con("ScriptHash", hexOf(h.bytes))))
          )
        )
      case DijkstraContextError.UnsupportedScriptInSubTx(language, txId) =>
        val name = language match
          case PlutusLanguage.V1 => "PlutusV1"
          case PlutusLanguage.V2 => "PlutusV2"
          case PlutusLanguage.V3 => "PlutusV3"
          case PlutusLanguage.V4 => "PlutusV4"
        con("UnsupportedScriptInSubTx", con(name), txIdShown(txId))
      case x => other(x)
    def collect(e: CollectError[Dijkstra]): Shown = e match
      case CollectError.BadTranslation(d) => con("BadTranslation", dijkstra(d))
      case x                              => other(x)
    Shown.NonEmpty(collect(es.head), Shown.Lst(es.tail.map(collect)))

  private lazy val refusals = json("fixtures/reference/leios-testnet-context-refusals/cases.json")
  private lazy val refusalResults =
    json("fixtures/reference/leios-testnet-context-refusals/results.json").asArray
      .getOrElse(fail("results.json is not an array"))
      .map(r => str(r, "name") -> r)
      .toMap

  test("each Plutus V1 to V3 refusal and context of the refusals fixture is the typed one") {
    val env = environment(refusals)
    val cases = refusals.hcursor.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    val compared = cases.map { c =>
      val name = str(c, "name")
      val (tx, utxo) = read(c)
      val (top, subs) = annotate(env, tx, utxo)
      val (recordedTop, recordedSubs) = levels(refusalResults.getOrElse(name, fail(name)))
      assertEquals(recordedSubs.size, subs.size, name)
      val levelsCompared = compareLevel(name, env, top, recordedTop) +:
        subs.zip(recordedSubs).zipWithIndex.map { case ((level, recorded), i) =>
          given BothLevels[TxLevel.Sub] = BothLevels.Sub
          compareLevel(s"$name, sub-transaction $i", env, level, recorded)
        }
      name -> levelsCompared
    }
    assertEquals(cases.size, 18)
    // the cases refused for a Plutus V4 context wait for Dijkstra's V4 translation (#85)
    assertEquals(
      compared.flatMap((name, ls) => ls.flatMap(_.deferred).map(_ => name)),
      Vector(
        "V4 guard whose sub-transaction fails",
        "V4 with the redeemer of a guard whose script is not provided"
      )
    )
    // the V1 to V3 contexts of the six cases the reference collects them in, and the V4 one of
    // the four languages' minting
    assertEquals(compared.map(_._2.map(_.collected).sum).sum, 3 + 1 + 1)
    assertEquals(compared.map(_._2.map(_.perScript).sum).sum, 3)
    assertEquals(compared.map(_._2.map(_.v4).sum).sum, 1)
    // Dijkstra's guard of each of its four fields, Conway's guard of each of its four, a DRep's
    // registration under V1 and a V3 script in a sub-transaction
    assertEquals(compared.map(_._2.map(_.refusals).sum).sum, 4 + 4 + 1 + 1)
  }

  test(
    "a body's outputs are translated up to the last transaction index, as the reference zips them"
  ) {
    // `zipWithM ... [minBound ..]` over `TxIx`, a `Word16` (`TxInfo.hs:346-350, 406-410, 450-454`,
    // `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:925-928`, cardano-ledger 9df9f98c):
    // of 65,537 outputs the first 65,536 are translated
    import xyz.kd5ujc.scarab.codec.{Cbor, Node}
    val env = environment(refusals)
    val c = refusals.hcursor
      .get[Vector[Json]]("cases")
      .fold(e => fail(e.message), identity)
      .find(str(_, "name") == "V1 to V4 minting in one transaction")
      .getOrElse(fail("no minting case"))
    val raw = node(hex(str(c, "bytes")))
    def items(n: Node) = n.value match
      case CborValue.Arr(xs) => xs
      case other             => fail(s"not an array: $other")
    val widened = items(raw) match
      case body +: rest =>
        val fields = body.value match
          case CborValue.Map(fs) => fs
          case other             => fail(s"not a body: $other")
        val wide = fields.map {
          case (k, v) if k.value == CborValue.UInt(1) =>
            k -> Node(CborValue.Arr(Vector.fill(65537)(items(v).head)), Bytes.empty)
          case kv => kv
        }
        CborValue.Arr(Node(CborValue.Map(wide), Bytes.empty) +: rest)
      case other => fail(s"not a transaction: $other")
    val bytes = Cbor
      .encode(widened, xyz.kd5ujc.scarab.ledger.dijkstra.V12.nestedLimits)
      .fold(e => fail(e.toString), identity)
    val tx = DijkstraTx.inBlock(node(bytes)).fold(e => fail(e.toString), identity)
    assertEquals(bodies.outputs[TxLevel.Top](txs.body[TxLevel.Top](tx)).size, 65537)
    val (_, utxo) = read(c)
    val (top, _) = annotate(env, tx, utxo)
    def size[L <: Language](t: EraPlutusTxInfo[L, Dijkstra])(
        outputs: xyz.kd5ujc.scarab.ledger.era.PlutusTxInfo[L] => scala.Int
    ) =
      t.toPlutusTxInfo(top.lti).unPlutusTxInfoResult.fold(e => fail(e.toString), outputs)
    assertEquals(size(EraPlutusTxInfo.dijkstraPlutusV1)(_.txInfoOutputs.size), 65536)
    assertEquals(size(EraPlutusTxInfo.dijkstraPlutusV2)(_.txInfoOutputs.size), 65536)
    assertEquals(size(EraPlutusTxInfo.dijkstraPlutusV3)(_.txInfoOutputs.size), 65536)
  }

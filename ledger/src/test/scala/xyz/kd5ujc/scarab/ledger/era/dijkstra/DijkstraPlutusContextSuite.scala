// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.NonEmptyList
import io.circe.Json
import java.time.Instant
import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Credential as FlatCredential,
  RewardAccount,
  TxIn,
  TxOutSource,
  Voter
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  AsItem,
  AsIx,
  BabbageContextError,
  BothLevels,
  CollectError,
  ContextError,
  CostModels,
  EraPParams,
  EraPlutusTxInfo,
  EraTx,
  EraTxBody,
  EraUTxO,
  Identity,
  ImpossibleContext,
  Language,
  LedgerLevelTxInfo,
  LedgerTxInfo,
  PParams,
  PlutusWithContext,
  ProtVer,
  TxLevel,
  UTxO,
  Version
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayContextError
import xyz.kd5ujc.scarab.ledger.state.{Credential, Dijkstra}
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

import DijkstraCorpus.*

/** Dijkstra's script contexts on the era types (`EraPlutusTxInfo 'PlutusV1` to `'PlutusV4` and
  * `EraPlutusContext` of `DijkstraEra`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs`,
  * cardano-ledger 9df9f98c) against the Leios build's own contexts, byte for byte, and its
  * refusals, every field.
  *
  * Each transaction is annotated by the node's makers of the reference's (`mkDijkstraStAnnTopTx`
  * and `mkDijkstraStAnnSubTx`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:108-219`), with
  * the fixture's protocol version, clock and cost models, and each level's scripts with their
  * contexts, which the makers collect (`scriptsWithContextFromLedgerTxInfo` and
  * `scriptsWithContextFromLedgerTxInfoWithResult`), are compared with the level the reference
  * recorded: its contexts in order (the language, script, arguments' bytes, units and cost model),
  * or its failure as the typed `CollectError` s with every field its `Show` records (`parseShown`,
  * [[ours]]).
  *
  * The chain's 200 transactions (`fixtures/reference/leios-testnet-chain-contexts`): all 273
  * contexts (68 Plutus V1, 54 V2, 117 V3 and 34 V4, 18 of them in sub-transactions). The refusals
  * (`fixtures/reference/leios-testnet-context-refusals`): every level of the 22 cases, 13 contexts
  * and 12 refusals; four of the contexts are guards whose view of the whole transaction folds the
  * levels' votes and current treasury amounts. The hand-written V4 cases
  * (`fixtures/reference/leios-testnet-contexts`): every level of the 11 cases, 14 contexts and 7
  * refusals. A body of 65,537 outputs has 65,536 in each language's information.
  */
class DijkstraPlutusContextSuite extends FunSuite:
  private val txs = EraTx.dijkstra
  private val bodies = EraTxBody.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  // ---- the environment of a fixture

  /** The languages by the name the reference gives them (`PlutusV1` to `PlutusV4`). */
  private val languageNames: Map[String, PlutusLanguage] =
    PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap

  private final case class Environment(
      clock: BigInt => Either[xyz.kd5ujc.scarab.ledger.TranslationError, BigInt],
      pp: PParams[Dijkstra]
  ):
    def protVer: ProtVer = EraPParams.dijkstra.ppProtocolVersionL.get(pp)

  /** The parameters of a fixture: its protocol version and cost models, the only ones a context
    * reads.
    */
  private def params(major: Int, minor: Int, models: Map[Int, Vector[BigInt]]): PParams[Dijkstra] =
    val p = EraPParams.dijkstra
    val pv = ProtVer(
      Version.mkVersion(major).getOrElse(fail(s"version $major")),
      Unsigned.from[Unsigned.Width.W32](BigInt(minor)).getOrElse(fail("minor"))
    )
    val costModels = CostModels.from(models).fold(e => fail(e.toString), identity)
    PParams
      .lensHKD[Dijkstra]
      .andThen(p.hkdCostModelsL[Identity])
      .replace(costModels)(p.ppProtocolVersionL.replace(pv)(p.emptyPParams))

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
    Environment(slot => Right((start + slot * length) * 1000), params(major, minor, models))

  /** The environment of `leios-testnet-contexts`, as its provenance gives it: protocol version
    * 12.0, slots of one second from the POSIX epoch and a Plutus V4 cost model of 251 zeros.
    */
  private val contextsEnvironment: Environment =
    Environment(
      slot => Right(slot * 1000),
      params(12, 0, Map(PlutusLanguage.V4.id -> Vector.fill(251)(BigInt(0))))
    )

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

  /** A case annotated by the makers, its top level's collection and each sub-transaction's. */
  private def annotate(env: Environment, c: Json): Vector[Collection] =
    val (tx, utxo) = read(c)
    val top = DijkstraStAnnTx.mkDijkstraStAnnTopTx(env.clock, env.pp, utxo, Map.empty, tx)
    top.dsattPlutusScriptsWithContext.value +: top.dsattSubTransactions.toVector.map {
      case sub: DijkstraStAnnTx.SubTx => sub.dsastPlutusScriptsWithContext.value
    }

  private type Collection = Either[NonEmptyList[CollectError[Dijkstra]], List[PlutusWithContext]]

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
        // the legacy arguments of Plutus V1 and V2 are a list; a Plutus V3 or V4 script's argument
        // is its context alone, the data itself (`PlutusArgs`,
        // `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:558, 583`, cardano-ledger
        // 9df9f98c), whose own list encoding in V4 is not a list of arguments
        val args =
          if language >= BigInt(PlutusLanguage.V3.id) then Vector(arguments.original)
          else
            arguments.value match
              case CborValue.Arr(xs) => xs.map(_.original)
              case other             => fail(s"not the legacy arguments: $other")
        val Vector(mem, steps) = items(units).map(uint): @unchecked
        Reference(uint(pv), language, script, args, (mem, steps), items(costModel).map(uint))
      case other => fail(s"not a PlutusWithContext: $other")

  /** A level of the reference's answer: the contexts it collected, or the record of the failure
    * (`Show`), which [[parseShown]] reads. A level not collected without a failure of its own is
    * refused with the case's (`leios-testnet-contexts` records it once, for the case).
    */
  private enum RecordedLevel:
    case Collected(contexts: Vector[Reference])
    case Refused(record: String)

  private def recordedLevel(level: Json, caseFailure: Option[String]): RecordedLevel =
    val c = level.hcursor
    c.get[Option[String]]("failure").toOption.flatten match
      case Some(failure) => RecordedLevel.Refused(failure)
      case None =>
        if c.get[Boolean]("collected").fold(e => fail(e.message), identity) then
          RecordedLevel.Collected(
            c.get[Vector[String]]("contexts").fold(e => fail(e.message), identity).map(reference)
          )
        else RecordedLevel.Refused(caseFailure.getOrElse(fail("a level refused with no record")))

  /** The levels of a recorded case, the top level first. */
  private def levels(result: Json): Vector[RecordedLevel] =
    val r = result.hcursor.downField("result")
    val caseFailure = r.get[Option[String]]("failure").toOption.flatten
    (r.downField("top").focus.getOrElse(fail("no top level")) +:
      r.get[Vector[Json]]("subtxs").fold(e => fail(e.message), identity))
      .map(recordedLevel(_, caseFailure))

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

  /** What a level compared: its contexts by language, or its refusal. */
  private final case class Compared(contexts: Vector[PlutusLanguage], refusals: scala.Int)

  /** A level's collection compared with the reference's record: its contexts in order, or its
    * failure, every field of which is the record's.
    */
  private def compareLevel(at: String, ours0: Collection, recorded: RecordedLevel): Compared =
    (recorded, ours0) match
      case (RecordedLevel.Collected(refs), Right(pwcs)) =>
        assertEquals(pwcs.size, refs.size, at)
        pwcs.zip(refs).zipWithIndex.foreach { case ((pwc, ref), k) =>
          same(s"$at, script $k", pwc, ref)
        }
        Compared(pwcs.toVector.map(_.plutusLanguage.language), 0)
      case (RecordedLevel.Refused(failure), Left(es)) =>
        assertEquals(ours(at, es), parseShown(at, failure), at)
        Compared(Vector.empty, 1)
      case other => fail(s"$at: $other")

  /** Every level of every case of a fixture compared, by case. */
  private def compareFixture(
      env: Environment,
      cases: Vector[Json],
      results: Map[String, Json]
  ): Vector[(String, Vector[Compared])] =
    cases.map { c =>
      val name = str(c, "name")
      val ourLevels = annotate(env, c)
      val recordedLevels = levels(results.getOrElse(name, fail(name)))
      assertEquals(ourLevels.size, recordedLevels.size, name)
      name -> ourLevels.zip(recordedLevels).zipWithIndex.map { case ((o, r), i) =>
        compareLevel(if i == 0 then name else s"$name, sub-transaction ${i - 1}", o, r)
      }
    }

  private def byLanguage(compared: Vector[Compared]): Map[PlutusLanguage, scala.Int] =
    compared.flatMap(_.contexts).groupMapReduce(identity)(_ => 1)(_ + _)

  private def results(path: String): Map[String, Json] =
    json(path).asArray
      .getOrElse(fail(s"$path is not an array"))
      .map(r => str(r, "name") -> r)
      .toMap

  // ---- the chain's transactions

  private lazy val chain = json("fixtures/reference/leios-testnet-chain-contexts/cases.json")

  test("each script context recorded on the Leios testnet's chain is the typed one") {
    val compared = compareFixture(
      environment(chain),
      chainContextCases,
      results("fixtures/reference/leios-testnet-chain-contexts/results.json")
    )
    assertEquals(compared.size, 200)
    val all = compared.flatMap(_._2)
    // every level collected, none refused
    assertEquals(all.map(_.refusals).sum, 0)
    assertEquals(
      byLanguage(all),
      Map(
        PlutusLanguage.V1 -> 68,
        PlutusLanguage.V2 -> 54,
        PlutusLanguage.V3 -> 117,
        PlutusLanguage.V4 -> 34
      )
    )
    // 18 of the Plutus V4 contexts in sub-transactions, where no V1 to V3 script runs
    assertEquals(byLanguage(compared.flatMap(_._2.drop(1))), Map(PlutusLanguage.V4 -> 18))
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

  private def txInShown(in: TxIn): Shown =
    con("TxIn", txIdShown(in.id), rec("TxIx", "unTxIx" -> Shown.Int(in.index)))

  private def txOutSource(source: TxOutSource): Shown = source match
    case TxOutSource.TxOutFromInput(in) => con("TxOutFromInput", txInShown(in))
    case TxOutSource.TxOutFromOutput(ix) =>
      con("TxOutFromOutput", rec("TxIx", "unTxIx" -> Shown.Int(ix)))

  /** A purpose by its index, its constructor the reference's (`DijkstraPlutusPurpose AsIx`). */
  private def purposeAsIx(p: DijkstraPlutusPurpose[AsIx]): Shown =
    val name = p match
      case DijkstraPlutusPurpose.DijkstraSpending(_)    => "DijkstraSpending"
      case DijkstraPlutusPurpose.DijkstraMinting(_)     => "DijkstraMinting"
      case DijkstraPlutusPurpose.DijkstraCertifying(_)  => "DijkstraCertifying"
      case DijkstraPlutusPurpose.DijkstraWithdrawing(_) => "DijkstraWithdrawing"
      case DijkstraPlutusPurpose.DijkstraVoting(_)      => "DijkstraVoting"
      case DijkstraPlutusPurpose.DijkstraProposing(_)   => "DijkstraProposing"
      case DijkstraPlutusPurpose.DijkstraGuarding(_)    => "DijkstraGuarding"
    con(name, rec("AsIx", "unAsIx" -> Shown.Int(p.ix.value)))

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
      case AlonzoContextError.TranslationLogicMissingInput(txIn) =>
        con("TranslationLogicMissingInput", txInShown(txIn))
      case AlonzoContextError.ImpossibleContextError(
            ImpossibleContext.MissingTxInfoResult(txId)
          ) =>
        con("ImpossibleContextError", Shown.Str(s"Missing TxInfoResult for ${txId.hex}"))
      case x => other(x)
    def babbage(b: BabbageContextError[Dijkstra]): Shown = b match
      case BabbageContextError.AlonzoContextError(a) => con("AlonzoContextError", alonzo(a))
      case BabbageContextError.ByronTxOutInContext(source) =>
        con("ByronTxOutInContext", txOutSource(source))
      case BabbageContextError.RedeemerPointerPointsToNothing(ptr) =>
        con("RedeemerPointerPointsToNothing", purposeAsIx(ptr))
      case x => other(x)
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
      case ConwayContextError.ReferenceInputsNotDisjointFromInputs(common) =>
        con(
          "ReferenceInputsNotDisjointFromInputs",
          Shown.NonEmpty(txInShown(common.head), Shown.Lst(common.tail.toList.map(txInShown)))
        )
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
      case DijkstraContextError.SubTxContextError(txId, error) =>
        con("SubTxContextError", txIdShown(txId), dijkstra(error))
      case DijkstraContextError.PointerPresentInOutput(source) =>
        con("PointerPresentInOutput", txOutSource(source))
      case DijkstraContextError.ScriptHashNotFoundForPurpose(purpose) =>
        con("ScriptHashNotFoundForPurpose", purposeAsIx(purpose))
    def collect(e: CollectError[Dijkstra]): Shown = e match
      case CollectError.BadTranslation(d) => con("BadTranslation", dijkstra(d))
      case CollectError.NoRedeemer(purpose) =>
        (purpose: DijkstraPlutusPurpose[AsItem]) match
          case DijkstraPlutusPurpose.DijkstraMinting(AsItem(policy)) =>
            con(
              "NoRedeemer",
              con(
                "DijkstraMinting",
                rec(
                  "AsItem",
                  "unAsItem" -> rec("PolicyID", "policyID" -> con("ScriptHash", hexOf(policy)))
                )
              )
            )
          case x => other(x)
      case x => other(x)
    Shown.NonEmpty(collect(es.head), Shown.Lst(es.tail.map(collect)))

  private lazy val refusals = json("fixtures/reference/leios-testnet-context-refusals/cases.json")

  private def refusalCases: Vector[Json] =
    refusals.hcursor.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)

  test("each refusal and context of the refusals fixture is the typed one") {
    val compared = compareFixture(
      environment(refusals),
      refusalCases,
      results("fixtures/reference/leios-testnet-context-refusals/results.json")
    )
    assertEquals(compared.size, 22)
    val all = compared.flatMap(_._2)
    assertEquals(
      byLanguage(all),
      Map(
        PlutusLanguage.V1 -> 2,
        PlutusLanguage.V2 -> 2,
        PlutusLanguage.V3 -> 4,
        PlutusLanguage.V4 -> 5
      )
    )
    // Dijkstra's guard of each of its four fields, Conway's guard of each of its four, a DRep's
    // registration under V1, a V3 script in a sub-transaction (at its level), and the two Plutus V4
    // refusals: a guard's sub-transaction that fails and a guard's redeemer with no script
    assertEquals(all.map(_.refusals).sum, 4 + 4 + 1 + 1 + 2)
    assertEquals(
      compared.collect {
        case (name, ls) if name.startsWith("V4 ") && ls.exists(_.refusals > 0) => name
      },
      Vector(
        "V4 guard whose sub-transaction fails",
        "V4 with the redeemer of a guard whose script is not provided"
      )
    )
    // the guards whose view of the whole transaction folds the levels' votes, the sub-transactions'
    // in order then the top level's, by the right-biased union of `VotingProcedures`, and takes the
    // first current treasury amount present (`transGuardingTopTxInfo`, `TxInfo.hs:868-869, 878,
    // 913-916`, cardano-ledger 9df9f98c): each top level collected with the guard's context, whose
    // arguments are compared byte for byte, and each sub-transaction with none
    val guard = Vector(PlutusLanguage.V4)
    assertEquals(
      compared.collect {
        case (name, ls) if name.startsWith("V4 guard with ") => name -> ls.map(_.contexts)
      },
      Vector(
        "V4 guard with one voter's votes on an action at three levels" ->
          Vector(guard, Vector.empty, Vector.empty),
        "V4 guard with votes at two levels by other voters and on other actions" ->
          Vector(guard, Vector.empty),
        "V4 guard with the current treasury in a sub-transaction only" ->
          Vector(guard, Vector.empty, Vector.empty),
        "V4 guard with the current treasury at two levels" ->
          Vector(guard, Vector.empty, Vector.empty)
      )
    )
  }

  test("each Plutus V4 context and refusal of the hand-written V4 cases is the typed one") {
    val compared = compareFixture(
      contextsEnvironment,
      contextCases,
      results("fixtures/reference/leios-testnet-contexts/results.json")
    )
    assertEquals(compared.size, 11)
    val all = compared.flatMap(_._2)
    assertEquals(byLanguage(all), Map(PlutusLanguage.V4 -> 14))
    assertEquals(byLanguage(compared.flatMap(_._2.drop(1))), Map(PlutusLanguage.V4 -> 4))
    // a pointer and a Byron output, an unknown input, a reference input that is an input, a
    // redeemer naming nothing, a script without its redeemer and a key withdrawal's redeemer
    assertEquals(all.map(_.refusals).sum, 7)
  }

  test(
    "the makers' languages used and legacy mode are those of the contexts the reference collected"
  ) {
    // `dsattPlutusLanguagesUsed`, `dsastPlutusLanguagesUsed` and `dsattPlutusLegacyMode`
    // (`Dijkstra.hs:156-157, 163, 211-212`, cardano-ledger 9df9f98c), of the scripts used: on a level the
    // reference collected, the languages of its contexts
    val fixtures = Vector(
      (
        environment(chain),
        chainContextCases,
        results("fixtures/reference/leios-testnet-chain-contexts/results.json")
      ),
      (
        environment(refusals),
        refusalCases,
        results("fixtures/reference/leios-testnet-context-refusals/results.json")
      ),
      (
        contextsEnvironment,
        contextCases,
        results("fixtures/reference/leios-testnet-contexts/results.json")
      )
    )
    def language(id: BigInt): PlutusLanguage =
      PlutusLanguage.values.find(l => BigInt(l.id) == id).getOrElse(fail(s"language $id"))
    val checked = fixtures.flatMap { (env, cases, recorded) =>
      cases.flatMap { c =>
        val name = str(c, "name")
        val (tx, utxo) = read(c)
        val top = DijkstraStAnnTx.mkDijkstraStAnnTopTx(env.clock, env.pp, utxo, Map.empty, tx)
        val ours = (top.dsattPlutusLanguagesUsed, Some(top.dsattPlutusLegacyMode)) +:
          top.dsattSubTransactions.toVector.map { case sub: DijkstraStAnnTx.SubTx =>
            (sub.dsastPlutusLanguagesUsed, None)
          }
        ours.zip(levels(recorded.getOrElse(name, fail(name)))).collect {
          case ((used, mode), RecordedLevel.Collected(refs)) =>
            val languages = refs.map(r => language(r.language)).toSet
            assertEquals(used, languages, name)
            mode.foreach(m =>
              assertEquals(
                m,
                if languages.exists(_.id <= PlutusLanguage.V3.id) then PlutusLegacyMode.Legacy
                else PlutusLegacyMode.NotLegacy,
                name
              )
            )
            mode.size
        }
      }
    }
    // the legacy mode of every top level the reference collected: the 200 of the chain, the 11 of the
    // refusals whose top level is not refused and the 4 hand-written cases it collected
    assertEquals(checked.sum, 200 + 11 + 4)
  }

  test("the makers collect a level's contexts only when they are read, and once") {
    // the collections are lazy fields of `DijkstraStAnnTx` (`Tx.hs:535-557`, cardano-ledger
    // 9df9f98c), read by `plutusScriptsWithContextDijkstraStAnnTx` after phase 1
    // (`Dijkstra/UTxO.hs:258-276`): annotating the guarded case and reading every other member
    // runs no translation, so a clock that fails the test is not read until a collection is
    val c = contextCases.find(str(_, "name") == "guarded").getOrElse(fail("no guarded case"))
    val (tx, utxo) = read(c)
    val untimed = contextsEnvironment.copy(clock = slot => fail(s"the clock was read at $slot"))
    val top = DijkstraStAnnTx.mkDijkstraStAnnTopTx(untimed.clock, untimed.pp, utxo, Map.empty, tx)
    val subs = top.dsattSubTransactions.collect { case sub: DijkstraStAnnTx.SubTx => sub }
    assertEquals(subs.size, 2)
    assertEquals(top.dsattPlutusLegacyMode, PlutusLegacyMode.NotLegacy)
    assertEquals(top.dsattPlutusLanguagesUsed, Set(PlutusLanguage.V4))
    assertEquals(subs.map(_.dsastPlutusLanguagesUsed), List(Set(PlutusLanguage.V4), Set.empty))
    assert(top.dsattScriptsNeeded.unAlonzoScriptsNeeded.nonEmpty)
    assertEquals(
      subs.map(_.dsastScriptsHashesNeeded),
      subs.map(sub => EraUTxO.dijkstra.getScriptsHashesNeeded(sub.dsastScriptsNeeded))
    )
    // a collection read runs the translation, which reads the clock for the guard's interval
    assert(
      intercept[munit.FailException](top.dsattPlutusScriptsWithContext.value).getMessage
        .contains("the clock was read")
    )
    // with a clock, each collection is made once and kept
    val timed =
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(
        contextsEnvironment.clock,
        contextsEnvironment.pp,
        utxo,
        Map.empty,
        tx
      )
    assert(timed.dsattPlutusScriptsWithContext.value eq timed.dsattPlutusScriptsWithContext.value)
  }

  test("a sub-transaction's information missing from its top level's is ImpossibleContextError") {
    // `transGuardingTopTxInfo` (`TxInfo.hs:844-855`, cardano-ledger 9df9f98c): the guard's view of
    // the whole transaction looks up each sub-transaction's memoised information, which the makers
    // always give; a top level's information without it fails, wrapped as the sub-transaction's
    val env = contextsEnvironment
    val c = contextCases.find(str(_, "name") == "guarded").getOrElse(fail("no guarded case"))
    val (tx, utxo) = read(c)
    val utxos = xyz.kd5ujc.scarab.ledger.era.EraUTxO.dijkstra
    val (_, used) = xyz.kd5ujc.scarab.ledger.era.AlonzoPlutusEvaluate
      .resolveNeededPlutusScriptsWithPurpose(
        env.protVer,
        utxos.getScriptsProvided[TxLevel.Top](utxo, tx),
        utxos.getScriptsNeeded[TxLevel.Top](utxo, txs.body[TxLevel.Top](tx)),
        Map.empty
      )
    val lti = LedgerTxInfo(
      env.protVer,
      env.clock,
      utxo,
      tx,
      used,
      LedgerLevelTxInfo.LedgerTopTxInfo[Dijkstra](Map.empty)
    )
    val firstSub = bodies.subTransactions(txs.body[TxLevel.Top](tx)).entries match
      case (_, sub) +: _ =>
        given BothLevels[TxLevel.Sub] = BothLevels.Sub
        bodies.id[TxLevel.Sub](txs.body[TxLevel.Sub](sub))
      case other => fail(s"no sub-transaction: $other")
    val costModels =
      PParams.lensHKD[Dijkstra].andThen(EraPParams.dijkstra.hkdCostModelsL[Identity]).get(env.pp)
    val impossible: ContextError[Dijkstra] =
      xyz.kd5ujc.scarab.ledger.era.Inject.dijkstraAlonzo.inject(
        AlonzoContextError.ImpossibleContextError(ImpossibleContext.MissingTxInfoResult(firstSub))
      )
    assertEquals(
      xyz.kd5ujc.scarab.ledger.era.AlonzoPlutusEvaluate
        .scriptsWithContextFromLedgerTxInfo(lti, costModels),
      Left(
        NonEmptyList.one(
          CollectError.BadTranslation[Dijkstra](
            DijkstraContextError.SubTxContextError[Dijkstra](firstSub, impossible)
          )
        )
      )
    )
  }

  test(
    "a body's outputs are translated up to the last transaction index, as the reference zips them"
  ) {
    // `zipWithM ... [minBound ..]` over `TxIx`, a `Word16` (`TxInfo.hs:346-350, 406-410, 450-454,
    // 591-595`, `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:925-928`, cardano-ledger
    // 9df9f98c): of 65,537 outputs the first 65,536 are translated
    import xyz.kd5ujc.scarab.codec.{Cbor, Node}
    val env = environment(refusals)
    val c = refusalCases
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
            items(v) match
              case first +: _ =>
                k -> Node(CborValue.Arr(Vector.fill(65537)(first)), Bytes.empty)
              case other => fail(s"no outputs: $other")
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
    val utxos = xyz.kd5ujc.scarab.ledger.era.EraUTxO.dijkstra
    val (_, used) = xyz.kd5ujc.scarab.ledger.era.AlonzoPlutusEvaluate
      .resolveNeededPlutusScriptsWithPurpose(
        env.protVer,
        utxos.getScriptsProvided[TxLevel.Top](utxo, tx),
        utxos.getScriptsNeeded[TxLevel.Top](utxo, txs.body[TxLevel.Top](tx)),
        Map.empty
      )
    val lti = LedgerTxInfo(
      env.protVer,
      env.clock,
      utxo,
      tx,
      used,
      LedgerLevelTxInfo.LedgerTopTxInfo[Dijkstra](Map.empty)
    )
    def size[L <: Language](t: EraPlutusTxInfo[L, Dijkstra])(
        outputs: xyz.kd5ujc.scarab.ledger.era.PlutusTxInfo[L] => scala.Int
    ) =
      t.toPlutusTxInfo(lti).unPlutusTxInfoResult.fold(e => fail(e.toString), outputs)
    assertEquals(size(EraPlutusTxInfo.dijkstraPlutusV1)(_.txInfoOutputs.size), 65536)
    assertEquals(size(EraPlutusTxInfo.dijkstraPlutusV2)(_.txInfoOutputs.size), 65536)
    assertEquals(size(EraPlutusTxInfo.dijkstraPlutusV3)(_.txInfoOutputs.size), 65536)
    assertEquals(size(EraPlutusTxInfo.dijkstraPlutusV4)(_.txInfoOutputs.size), 65536)
  }

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{Chain, NonEmptyList, NonEmptySet}
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.{Horizon, TxEnv, TxIn, Unsigned, Utxo, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Credential,
  Dijkstra,
  EpochNo,
  Guard,
  KeyHash,
  Network,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, ScarabEvaluator}

/** Dijkstra's SUBUTXOW and SUBUTXO on the era types (`DijkstraSubUtxow`, `DijkstraSubUtxo`,
  * cardano-ledger 9df9f98c `Rules/SubUtxow.hs`, `Rules/SubUtxo.hs`), against what the reference
  * recorded where the flat rules cannot judge (they halt on a sub-transaction):
  *   - the 28 batches of the public Leios testnet's chain (`fixtures/reference/
  *     leios-testnet-chain-contexts`, recorded with the outputs they spend; the testnet's ledger
  *     accepted each), 38 sub-transactions: SUBUTXOW admits each sub-transaction, threaded through
  *     the batch's in order as SUBLEDGERS threads them, each telling the outputs it spends and
  *     creates; then Dijkstra's UTXOW admits the top level on the threaded state, and the batch
  *     leaves every level's inputs spent and outputs created; each sub-transaction's instant stake
  *     is moved with its outputs, and the fees, deposits and donation are left as they were. A
  *     component check: the certificate state is empty, not the chain's, and SUBENTITIES and
  *     SUBGOV, which SUBLEDGER runs before SUBUTXOW, are not run (D7c's second part); no
  *     sub-transaction of the chain registers a pool, so the pool state after the sub-transactions
  *     is the one before;
  *   - a sub-transaction's inputs judged against the threaded state, not the set before the
  *     transaction; a top level flagged invalid leaving the state as it is, its predicates still
  *     run; a sub-transaction's donation added to the pot, re-encoded with one, the fees and
  *     deposits left as they were; the order of the failures, SUBUTXO's block before SUBUTXOW's own
  *     at the root, and those on the signal skipped under `NonStatic`;
  *   - the injections, which the reference leaves partial (`error "Impossible"`,
  *     `SubUtxo.hs:304-329`, `SubUtxow.hs:337-364`): on every constructor the rules report, each
  *     equals the reference's composition through Conway's and Dijkstra's failures, and the
  *     constructors the reference calls impossible, and UTXOW's `UtxoFailure`, which no predicate
  *     of SUBUTXOW reports, have no injection: it does not compile.
  *
  * Incomplete (CNF-2), not run here:
  *   - the batches as the reference judges them: SUBLEDGER runs SUBENTITIES and SUBGOV before
  *     SUBUTXOW, and gives SUBUTXOW the certificate state it was given, from before them
  *     (`SubLedger.hs:248, 307`), the state SUBLEDGERS threads from the earlier sub-transactions;
  *     the chain's own certificate state is not recorded;
  *   - any refusal of SUBUTXOW or SUBUTXO by the reference: no recording holds one, so the refusals
  *     here are derived from the rules on crafted states and parameters, and the constructors on
  *     built values;
  *   - the context refusals (`fixtures/reference/leios-testnet-context-refusals`): they record each
  *     level's collection, not a verdict of the rules; the one refused in a sub-transaction is
  *     UTXOS's refusal at the top level (`DijkstraUtxowSuite`), and SUBUTXOW collects nothing;
  *   - the forecast of a validity interval's end, a sub-transaction's validity interval, network
  *     and auxiliary data: no sub-transaction of the chain has one (its donation is crafted here);
  *   - the comparison of the failures with the reference's bytes: the era failures have no encoder
  *     yet (#796), and the flat failures have no form for them.
  */
class DijkstraSubUtxowSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private given cats.Order[TxIn] = cats.Order.fromOrdering
  private val bodies = EraTxBody.dijkstra
  private val txs = EraTx.dijkstra
  private val utxos = EraUTxO.dijkstra

  private val fixture = json("fixtures/reference/leios-testnet-chain-contexts/cases.json")

  /** The testnet's parameters as its genesis files give them, as `DijkstraUtxowSuite` reads them.
    */
  private lazy val stored: xyz.kd5ujc.scarab.ledger.ProtocolParams =
    def genesis(era: String) = json(s"fixtures/leios/testnet/config/$era-genesis.json")
    xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture.genesis.upgrade(
      xyz.kd5ujc.scarab.ledger.ProtocolParams
        .fromGenesis(genesis("shelley"), genesis("alonzo"), Some(genesis("conway")))
        .fold(e => fail(e.toString), identity)
    )

  private def withCostModels(models: Map[Int, Vector[BigInt]]): PParams[Dijkstra] =
    PParams
      .lensHKD[Dijkstra]
      .andThen(EraPParams.dijkstra.hkdCostModelsL[Identity])
      .replace(CostModels.from(models).fold(e => fail(e.toString), identity))(era(stored))

  /** The recorded cost models, by language. */
  private lazy val models: Map[Int, Vector[BigInt]] =
    val languages = PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap
    fixture.hcursor
      .get[Map[String, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)
      .map((name, values) => languages.getOrElse(name, fail(name)).id -> values)

  /** The testnet's parameters with the cost models the fixture records in force on the chain. */
  private lazy val pp: PParams[Dijkstra] = withCostModels(models)

  private lazy val systemStart: BigInt =
    BigInt(Instant.parse(str(fixture, "systemStart")).getEpochSecond)

  private lazy val slotLength: BigInt =
    fixture.hcursor.get[BigInt]("slotLength").fold(e => fail(e.message), identity)

  private def clock(slot: BigInt): Either[xyz.kd5ujc.scarab.ledger.TranslationError, BigInt] =
    Right((systemStart + slot * slotLength) * 1000)

  /** An era history that ends far after the chain, as `DijkstraUtxowSuite`'s. */
  private lazy val history: Horizon =
    val epochSize = fixture.hcursor.get[BigInt]("epochSize").fold(e => fail(e.message), identity)
    val params = Horizon.Params(epochSize, slotLength * 1000, BigInt(10).pow(9), BigInt(10).pow(9))
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(BigInt(0))))

  private def context(validation: TxEnv.Validation, network: Network = Network.Testnet) =
    DijkstraUtxoContext(
      network,
      xyz.kd5ujc.scarab.ledger.SystemStart(Instant.ofEpochSecond(systemStart.toLong)),
      history,
      validation
    )

  private def read(c: io.circe.Json): (DijkstraTx[TxLevel.Top], UTxO[Dijkstra]) =
    val tx = DijkstraTx.inBlock(node(hex(str(c, "bytes")))).fold(e => fail(e.toString), identity)
    val utxo = c.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map { (i, o) =>
        txIn(DijkstraTxIn.decode(node(hex(i))).fold(e => fail(e.toString), identity)) ->
          DijkstraTxOut.decode(node(hex(o))).fold(e => fail(e.toString), identity)
      }
    (tx, UTxO[Dijkstra](utxo.toMap))

  private lazy val start = xyz.kd5ujc.scarab.ledger.state.Build
    .dijkstra(EpochNo.fromBigInt(0).fold(e => fail(e.toString), identity), stored)
    .es
    .ls

  /** The state holding `utxo`, with its instant stake, and the pots `fees`, `deposited` and
    * `donation`.
    */
  private def storedState(
      utxo: UTxO[Dijkstra],
      fees: Coin = Coin(0),
      deposited: Coin = Coin(0),
      donation: Coin = Coin(0)
  ): UTxOState[Dijkstra] =
    start.utxo.copy[Dijkstra](
      utxo = UtxoTables(Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)),
      instantStake = EraStake.dijkstra
        .addInstantStake(utxo, start.utxo.instantStake)
        .fold(e => fail(e.toString), identity),
      fees = fees,
      deposited = deposited,
      donation = donation
    )

  private def slotOf(c: io.circe.Json): BigInt =
    c.hcursor.get[BigInt]("slot").fold(e => fail(e.message), identity)

  private lazy val batches: Vector[io.circe.Json] =
    chainContextCases.filter(c => bodies.subTransactions(read(c)._1.body).toVector.nonEmpty)

  /** A batch read and annotated, as `mkDijkstraStAnnTopTx` makes it with an empty cache. */
  private final case class Batch(
      name: String,
      tx: DijkstraTx[TxLevel.Top],
      utxo: UTxO[Dijkstra],
      top: DijkstraStAnnTx.TopTx,
      subs: List[DijkstraStAnnTx[TxLevel.Sub]]
  )

  private def batch(c: io.circe.Json): Batch =
    val (tx, utxo) = read(c)
    val top = DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, pp, utxo, Map.empty, tx)
    Batch(str(c, "name"), tx, utxo, top, utxos.subTransactionsStAnnTx(top))

  private def subEnv(b: Batch, slot: BigInt, params: PParams[Dijkstra] = pp) =
    SubUtxoEnv[Dijkstra](slot, params, start.cert, b.utxo, txs.isValid(b.tx))

  private def subBody(sub: DijkstraStAnnTx[TxLevel.Sub]): TxBody[TxLevel.Sub, Dijkstra] =
    txs.body[TxLevel.Sub](txs.txStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub))(using
      BothLevels.Sub
    )

  private def subUtxow(
      env: SubUtxoEnv[Dijkstra],
      sub: DijkstraStAnnTx[TxLevel.Sub],
      state: UTxOState[Dijkstra],
      validation: TxEnv.Validation = TxEnv.Validation.All,
      network: Network = Network.Testnet
  ) =
    DijkstraUtxoRun.Sub.run(
      env,
      sub,
      context(validation, network),
      state,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  /** `updateUTxOAndInstantStake` 's stake (`Shelley/Rules/Utxo.hs:602-628`, cardano-ledger
    * f649f975): the outputs created added to the state's, then those spent taken out.
    */
  private def movedStake(
      state: UTxOState[Dijkstra],
      consumed: UTxO[Dijkstra],
      created: UTxO[Dijkstra]
  ) =
    EraStake.dijkstra
      .addInstantStake(created, state.instantStake)
      .flatMap(EraStake.dijkstra.deleteInstantStake(consumed, _))
      .fold(e => fail(e.toString), identity)

  /** The outputs of the stored set at `ins`, read into the era. */
  private def outputsAt(state: UTxOState[Dijkstra], ins: Set[TxIn]): UTxO[Dijkstra] =
    UTxO[Dijkstra](
      ins.toVector
        .flatMap(in => state.utxo.utxo.get(in).map(in -> _))
        .map((in, o) =>
          in -> StoredTxOut.dijkstra.fromStored(o).fold(e => fail(s"$in: $e"), identity)
        )
        .toMap
    )

  /** Why a batch was not admitted: a sub-transaction's judgment, or the top level's. */
  private enum Refused:
    case SubTransaction(
        batch: String,
        judged: Either[DijkstraUtxoRun.Sub.SDefect, Either[
          cats.data.NonEmptyChain[DijkstraUtxoRun.Sub.SRec],
          DijkstraUtxoRun.Sub.SS
        ]]
    )
    case TopLevel(
        batch: String,
        judged: Either[DijkstraUtxoRun.Defect, Either[
          cats.data.NonEmptyChain[DijkstraUtxoRun.Rec],
          DijkstraUtxoRun.S
        ]]
    )

  /** SUBUTXOW over the batch's sub-transactions in order, from the batch's recorded outputs, each
    * admitted with its `TxUTxODiff`, then UTXOW on the threaded state; the state UTXOW leaves, or
    * why the batch was not admitted.
    */
  private def judgeBatch(b: Batch, slot: BigInt): Either[Refused, UTxOState[Dijkstra]] =
    val env = subEnv(b, slot)
    val threaded =
      b.subs.foldLeft[Either[Refused, UTxOState[Dijkstra]]](Right(storedState(b.utxo))) {
        (done, sub) =>
          done.flatMap { state =>
            val body = subBody(sub)
            subUtxow(env, sub, state) match
              case Right(Right((next, events))) =>
                val consumed =
                  outputsAt(state, bodies.inputs[TxLevel.Sub](body)(using BothLevels.Sub))
                val created =
                  UTxOs.txouts[Dijkstra, TxLevel.Sub](body)(using bodies, BothLevels.Sub)
                assertEquals(
                  events,
                  Chain.one(
                    DijkstraSubUtxowEvent.SubUtxo[Dijkstra](
                      DijkstraSubUtxoEvent.TxUTxODiff(consumed, created)
                    )
                  ),
                  b.name
                )
                // the instant stake moved with the outputs, the pots left as they were: no deposit,
                // no fee, and no donation on the chain's sub-transactions
                assertEquals(next.instantStake, movedStake(state, consumed, created), b.name)
                assertEquals(
                  (next.fees, next.deposited, next.donation),
                  (state.fees, state.deposited, state.donation),
                  b.name
                )
                Right(next)
              case other => Left(Refused.SubTransaction(b.name, other))
          }
      }
    threaded.flatMap { state =>
      DijkstraUtxoRun.run(
        DijkstraUtxoEnv[Dijkstra](slot, pp, start.cert.pState, start.cert, b.utxo),
        b.top,
        context(TxEnv.Validation.All),
        state,
        ScarabEvaluator.default,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder
      ) match
        case Right(Right((next, _))) => Right(next)
        case other                   => Left(Refused.TopLevel(b.name, other))
    }

  test(
    "SUBUTXOW admits every sub-transaction of the chain's batches, threaded, a component check"
  ) {
    assertEquals(batches.size, 28)
    val all = batches.map(c => c -> batch(c))
    assertEquals(all.map(_._2.subs.size).sum, 38)
    // no sub-transaction registers or retires a pool, so the pool state after the sub-transactions
    // is the one before, which UTXOW is given here
    val subCerts = all.flatMap((_, b) =>
      b.subs.flatMap(sub => bodies.certs[TxLevel.Sub](subBody(sub))(using BothLevels.Sub).toVector)
    )
    assertEquals(subCerts.size, 2)
    assert(
      subCerts.forall {
        case DijkstraTxCert.DijkstraTxCertPool(_) => false
        case _                                    => true
      },
      subCerts.toString
    )
    // every batch is admitted at the slot the fixture records for it, an endorser block's at the
    // certifying ranking block's
    val refused = all.flatMap { (c, b) =>
      judgeBatch(b, slotOf(c)) match
        case Right(after) =>
          // every level's inputs spent and outputs created
          val levels = bodies.inputs[TxLevel.Top](b.tx.body) ++ b.subs.flatMap(sub =>
            bodies.inputs[TxLevel.Sub](subBody(sub))(using BothLevels.Sub)
          )
          val created = UTxOs.txouts[Dijkstra, TxLevel.Top](b.tx.body).unUTxO.keySet ++ b.subs
            .flatMap(sub =>
              UTxOs
                .txouts[Dijkstra, TxLevel.Sub](subBody(sub))(using bodies, BothLevels.Sub)
                .unUTxO
                .keySet
            )
          assertEquals(levels.filter(after.utxo.utxo.contains), Set.empty[TxIn], b.name)
          assertEquals(created.filterNot(after.utxo.utxo.contains), Set.empty[TxIn], b.name)
          None
        case Left(why) => Some(why)
    }
    assertEquals(refused, Vector.empty[Refused])
  }

  private lazy val first: (io.circe.Json, Batch) =
    val c = batches.headOption.getOrElse(fail("no batch"))
    c -> batch(c)

  test("a sub-transaction's inputs are judged against the threaded state, not the set before") {
    val (c, b) = first
    val sub = b.subs.headOption.getOrElse(fail("no sub-transaction"))
    val env = subEnv(b, slotOf(c))
    val inputs = bodies.inputs[TxLevel.Sub](subBody(sub))(using BothLevels.Sub)
    val once = subUtxow(env, sub, storedState(b.utxo)) match
      case Right(Right((next, _))) => next
      case other                   => fail(s"$other")
    // the same sub-transaction again: its inputs are still in the set before the transaction, which
    // every other lookup reads, but no longer in the threaded state
    assertEquals(
      subUtxow(env, sub, once).map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector[SubUtxowPredFailure[Dijkstra]](
            DijkstraSubUtxowPredFailure.SubUtxoFailure(
              DijkstraSubUtxoPredFailure.SubBadInputsUTxO(
                NonEmptySet
                  .fromSet(scala.collection.immutable.SortedSet.from(inputs))
                  .getOrElse(fail("no input"))
              )
            )
          )
        )
      )
    )
  }

  test("a top level flagged invalid leaves the state as it is, its predicates still run") {
    val (c, b) = first
    val sub = b.subs.headOption.getOrElse(fail("no sub-transaction"))
    val before = storedState(b.utxo)
    val env = subEnv(b, slotOf(c)).copy(topTxIsPhase2Valid = IsPhase2Valid.Phase2Invalid)
    assertEquals(subUtxow(env, sub, before), Right(Right((before, Chain.empty))))
    // the predicates run before the flag is read (`SubUtxo.hs:232-255`, cardano-ledger 9df9f98c):
    // on the state its inputs were spent from, the sub-transaction is refused even so
    val spent = subUtxow(subEnv(b, slotOf(c)), sub, before) match
      case Right(Right((next, _))) => next
      case other                   => fail(s"$other")
    val inputs = bodies.inputs[TxLevel.Sub](subBody(sub))(using BothLevels.Sub)
    assertEquals(
      subUtxow(env, sub, spent).map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector[SubUtxowPredFailure[Dijkstra]](
            DijkstraSubUtxowPredFailure.SubUtxoFailure(
              DijkstraSubUtxoPredFailure.SubBadInputsUTxO(
                NonEmptySet
                  .fromSet(scala.collection.immutable.SortedSet.from(inputs))
                  .getOrElse(fail("no input"))
              )
            )
          )
        )
      )
    )
  }

  test("the instant stake moves with a sub-transaction's outputs") {
    // each sub-transaction of the chain alone, from its batch's recorded outputs: the stake after
    // is the stake before with its outputs' added and its spent outputs' taken out; for six it
    // changes
    val changed = batches.flatMap { c =>
      val b = batch(c)
      b.subs.flatMap { sub =>
        val before = storedState(b.utxo)
        val body = subBody(sub)
        subUtxow(subEnv(b, slotOf(c)), sub, before) match
          case Right(Right((after, _))) =>
            val consumed =
              outputsAt(before, bodies.inputs[TxLevel.Sub](body)(using BothLevels.Sub))
            val created = UTxOs.txouts[Dijkstra, TxLevel.Sub](body)(using bodies, BothLevels.Sub)
            assertEquals(after.instantStake, movedStake(before, consumed, created), b.name)
            Option.when(after.instantStake != before.instantStake)(b.name)
          case other => fail(s"${b.name}: $other")
      }
    }
    assertEquals(changed.size, 6, changed.toString)
  }

  /** The bytes of `c` 's transaction with a treasury donation of `amount` (body field 22) in its
    * first sub-transaction's body (field 23), the rest as it was read, encoded again.
    */
  private def withSubDonation(c: io.circe.Json, amount: BigInt): Bytes =
    def leaf(v: Value): Node = Node(v, Bytes.empty)
    val donation = leaf(Value.UInt(22)) -> leaf(Value.UInt(amount))
    def donated(subTx: Node): Node = subTx.value match
      case Value.Arr(subBody +: rest) =>
        subBody.value match
          case Value.Map(fields) => leaf(Value.Arr(leaf(Value.Map(fields :+ donation)) +: rest))
          case other             => fail(s"not a body: $other")
      case other => fail(s"not a sub-transaction: $other")
    def firstDonated(subTxs: Node): Node = subTxs.value match
      case Value.Tag(tag, inner)    => leaf(Value.Tag(tag, firstDonated(inner)))
      case Value.Arr(first +: rest) => leaf(Value.Arr(donated(first) +: rest))
      case other                    => fail(s"not the sub-transactions: $other")
    node(hex(str(c, "bytes"))).value match
      case Value.Arr(txBody +: rest) =>
        txBody.value match
          case Value.Map(fields) =>
            val withDonation = fields.map {
              case (k, v) if k.value == Value.UInt(23) => k -> firstDonated(v)
              case field                               => field
            }
            Cbor
              .encode(Value.Arr(leaf(Value.Map(withDonation)) +: rest))
              .fold(e => fail(s"$e"), identity)
          case other => fail(s"not a body: $other")
      case other => fail(s"not a transaction: $other")

  test("a sub-transaction's donation is added to the pot, and the fees and deposits are not") {
    // the first batch whose first sub-transaction has no redeemers (the witnesses are encoded again,
    // and the integrity hash is over their bytes as they arrived), that sub-transaction with a
    // donation, judged under `NonStatic`: its signature, over the body as it was, is a static check
    val (c, recorded) = batches.iterator
      .map(c => c -> batch(c))
      .find((_, b) =>
        b.subs.headOption.exists(sub =>
          txs.witsOf
            .rdmrsTxWits(
              txs.wits[TxLevel.Sub](txs.txStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub))(using
                BothLevels.Sub
              )
            )
            .entries
            .isEmpty
        )
      )
      .getOrElse(fail("no sub-transaction without redeemers"))
    val tx = DijkstraTx
      .inBlock(node(withSubDonation(c, BigInt(5000000))))
      .fold(e => fail(e.toString), identity)
    val top = DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, pp, recorded.utxo, Map.empty, tx)
    val b = recorded.copy(tx = tx, top = top, subs = utxos.subTransactionsStAnnTx(top))
    val sub = b.subs.headOption.getOrElse(fail("no sub-transaction"))
    assertEquals(
      bodies.treasuryDonation[TxLevel.Sub](subBody(sub))(using BothLevels.Sub),
      Coin(BigInt(5000000))
    )
    val before = storedState(b.utxo, Coin(11), Coin(13), Coin(7))
    subUtxow(subEnv(b, slotOf(c)), sub, before, TxEnv.Validation.NonStatic) match
      case Right(Right((after, _))) =>
        assertEquals(
          (after.fees, after.deposited, after.donation),
          (Coin(11), Coin(13), Coin(BigInt(5000007)))
        )
      case other => fail(s"$other")
    // flagged invalid at the top level, nothing is added
    val invalid = subEnv(b, slotOf(c)).copy(topTxIsPhase2Valid = IsPhase2Valid.Phase2Invalid)
    assertEquals(
      subUtxow(invalid, sub, before, TxEnv.Validation.NonStatic).map(_.map(_._1.donation)),
      Right(Right(Coin(7)))
    )
  }

  test("the failures in the reference's order, and those on the signal skipped under NonStatic") {
    // a sub-transaction with redeemers, judged under other cost models (its script integrity hash
    // then differs), a second time (its inputs spent) and on the other network (its outputs'). The
    // reference's list at the root (`Judgment.flatten`): SUBUTXOW's own failures and SUBUTXO's block
    // reversed, so the block first, its failures in the order SUBUTXO recorded them
    val (c, b) = batches.iterator
      .map(c => c -> batch(c))
      .find((_, b) =>
        b.subs.exists(sub =>
          txs.witsOf
            .rdmrsTxWits(
              txs.wits[TxLevel.Sub](txs.txStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub))(using
                BothLevels.Sub
              )
            )
            .entries
            .nonEmpty
        )
      )
      .getOrElse(fail("no sub-transaction with redeemers"))
    val sub = b.subs
      .find(sub =>
        txs.witsOf
          .rdmrsTxWits(
            txs.wits[TxLevel.Sub](txs.txStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub))(using
              BothLevels.Sub
            )
          )
          .entries
          .nonEmpty
      )
      .getOrElse(fail("no sub-transaction with redeemers"))
    val other = withCostModels(models.view.mapValues(_.map(_ + 1)).toMap)
    val env = subEnv(b, slotOf(c), other)
    val spent = subUtxow(subEnv(b, slotOf(c)), sub, storedState(b.utxo)) match
      case Right(Right((next, _))) => next
      case judged                  => fail(s"$judged")
    def judged(validation: TxEnv.Validation) =
      subUtxow(env, sub, spent, validation, Network.Mainnet).map(_.left.map(_.toChain.toVector))
    // the integrity hash the body holds against the hash under the other cost models, and the bytes
    // that one is over
    val subTx = txs.txStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub)
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Dijkstra, TxLevel.Sub](
        other,
        subTx,
        utxos.plutusLanguagesUsedStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub)
      )(using txs, EraPParams.dijkstra, txs.witsOf, BothLevels.Sub)
    val mismatch: SubUtxowPredFailure[Dijkstra] =
      DijkstraSubUtxowPredFailure.SubScriptIntegrityHashMismatch[Dijkstra](
        Mismatch(
          bodies.scriptIntegrityHash[TxLevel.Sub](subBody(sub))(using BothLevels.Sub),
          integrity.map(ScriptIntegrity.hashScriptIntegrity(_))
        ),
        integrity.map(ScriptIntegrity.originalBytes(_))
      )
    mismatch match
      case DijkstraSubUtxowPredFailure.SubScriptIntegrityHashMismatch(m, _) =>
        assert(m.supplied != m.expected)
      case other => fail(s"$other")
    judged(TxEnv.Validation.All) match
      case Right(
            Left(
              Vector(
                DijkstraSubUtxowPredFailure.SubUtxoFailure(
                  DijkstraSubUtxoPredFailure.SubBadInputsUTxO(_)
                ),
                DijkstraSubUtxowPredFailure.SubUtxoFailure(
                  DijkstraSubUtxoPredFailure.SubWrongNetwork(Network.Mainnet, _)
                ),
                integrityFailure
              )
            )
          ) =>
        assertEquals(integrityFailure, mismatch)
      case other => fail(s"${b.name}: $other")
    judged(TxEnv.Validation.NonStatic) match
      case Right(
            Left(
              Vector(
                DijkstraSubUtxowPredFailure.SubUtxoFailure(
                  DijkstraSubUtxoPredFailure.SubBadInputsUTxO(_)
                ),
                integrityFailure
              )
            )
          ) =>
        assertEquals(integrityFailure, mismatch)
      case other => fail(s"${b.name}: $other")
  }

  // ---- the injections, on built values of every constructor the rules report

  private lazy val sample: (TxIn, TxOut[Dijkstra]) =
    first._2.utxo.unUTxO.headOption.getOrElse(fail("no output"))

  private lazy val shelley: Vector[DijkstraSubUtxo.ShelleyFailure[Dijkstra]] =
    val (in, out) = sample
    val o = EraTxOut.dijkstra
    Vector[DijkstraSubUtxo.ShelleyFailure[Dijkstra]](
      new ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra](NonEmptySet.one(in)),
      new ShelleyUtxoPredFailure.InputSetEmptyUTxO[Dijkstra](),
      new ShelleyUtxoPredFailure.WrongNetwork[Dijkstra](
        Network.Testnet,
        NonEmptySet.one(o.address(out))(using
          cats.Order.by[xyz.kd5ujc.scarab.ledger.Address, Bytes](_.raw)(using
            cats.Order.fromOrdering
          )
        )
      ),
      new ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig[Dijkstra](NonEmptyList.one(out))
    )

  private lazy val allegra: Vector[DijkstraSubUtxo.AllegraFailure[Dijkstra]] = Vector(
    new AllegraUtxoPredFailure.OutsideValidityIntervalUTxO[Dijkstra](
      ValidityInterval(Some(BigInt(3)), Some(BigInt(4))),
      BigInt(9)
    )
  )

  private lazy val alonzo: Vector[DijkstraSubUtxo.AlonzoFailure[Dijkstra]] =
    val (_, out) = sample
    Vector[DijkstraSubUtxo.AlonzoFailure[Dijkstra]](
      new AlonzoUtxoPredFailure.OutsideForecast[Dijkstra](BigInt(7)),
      new AlonzoUtxoPredFailure.OutputTooBigUTxO[Dijkstra](NonEmptyList.one((5, 4, out))),
      new AlonzoUtxoPredFailure.WrongNetworkInTxBody[Dijkstra](
        Mismatch(Network.Mainnet, Network.Testnet)
      )
    )

  private lazy val babbage: Vector[DijkstraSubUtxo.BabbageFailure[Dijkstra]] =
    val (_, out) = sample
    Vector(
      new BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO[Dijkstra](
        NonEmptyList.one((out, Coin(8)))
      )
    )

  /** The reference's `dijkstraUtxoToDijkstraSubUtxoPredFailure` (`SubUtxo.hs:304-329`,
    * cardano-ledger 9df9f98c) written out again, case for case, none where it calls the constructor
    * impossible: the table the rule's own function is compared with.
    */
  private def throughDijkstra(
      d: DijkstraUtxoPredFailure[Dijkstra]
  ): Option[DijkstraSubUtxoPredFailure[Dijkstra]] =
    import DijkstraUtxoPredFailure as U
    import DijkstraSubUtxoPredFailure as S
    d match
      case U.UtxosFailure(_)                     => None
      case U.BadInputsUTxO(x)                    => Some(S.SubBadInputsUTxO(x))
      case U.OutsideValidityIntervalUTxO(vi, s)  => Some(S.SubOutsideValidityIntervalUTxO(vi, s))
      case U.MaxTxSizeUTxO(m)                    => Some(S.SubMaxTxSizeUTxO(m))
      case U.InputSetEmptyUTxO()                 => Some(S.SubInputSetEmptyUTxO())
      case U.FeeTooSmallUTxO(_)                  => None
      case U.ValueNotConservedUTxO(_)            => None
      case U.WrongNetwork(x, y)                  => Some(S.SubWrongNetwork(x, y))
      case U.OutputBootAddrAttrsTooBig(xs)       => Some(S.SubOutputBootAddrAttrsTooBig(xs))
      case U.OutputTooBigUTxO(xs)                => Some(S.SubOutputTooBigUTxO(xs))
      case U.InsufficientCollateral(_, _)        => None
      case U.ScriptsNotPaidUTxO(_)               => None
      case U.ExUnitsTooBigUTxO(_)                => None
      case U.CollateralContainsNonADA(_)         => None
      case U.WrongNetworkInTxBody(m)             => Some(S.SubWrongNetworkInTxBody(m))
      case U.OutsideForecast(sno)                => Some(S.SubOutsideForecast(sno))
      case U.TooManyCollateralInputs(_)          => None
      case U.NoCollateralInputs()                => None
      case U.IncorrectTotalCollateralField(_, _) => None
      case U.BabbageOutputTooSmallUTxO(outs)     => Some(S.SubBabbageOutputTooSmallUTxO(outs))
      case U.BabbageNonDisjointRefInputs(_)      => None
      case U.PtrPresentInCollateralReturn(_)     => None
      case U.ValueNotConservedInLegacyMode(_)    => None

  /** The reference's composition through Conway's failure: `conwayToDijkstraUtxoPredFailure`, then
    * [[throughDijkstra]], none where either calls the constructor impossible.
    */
  private def throughConway(
      c: ConwayUtxoPredFailure[Dijkstra]
  ): Option[DijkstraSubUtxoPredFailure[Dijkstra]] = c match
    case ConwayUtxoPredFailure.WrongNetworkWithdrawal(_, _) => None
    case ConwayUtxoPredFailure.OutputTooSmallUTxO(_)        => None
    case f: DijkstraUtxo.ConwayFailure[Dijkstra] =>
      throughDijkstra(DijkstraUtxo.conwayToDijkstraUtxoPredFailure(f))

  test("each injection into SUBUTXO equals the reference's composition") {
    shelley.foreach(f =>
      assertEquals(
        Some(
          summon[InjectSubUtxoFailure[DijkstraSubUtxo.ShelleyFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConway(
          ConwayUtxo.allegraToConwayUtxoPredFailure[Dijkstra](
            AllegraUtxo.shelleyToAllegraUtxoPredFailure11_2(f)
          )
        ),
        f.toString
      )
    )
    allegra.foreach(f =>
      assertEquals(
        Some(
          summon[InjectSubUtxoFailure[DijkstraSubUtxo.AllegraFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConway(ConwayUtxo.allegraToConwayUtxoPredFailure[Dijkstra](f)),
        f.toString
      )
    )
    alonzo.foreach(f =>
      assertEquals(
        Some(
          summon[InjectSubUtxoFailure[DijkstraSubUtxo.AlonzoFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConway(ConwayUtxo.alonzoToConwayUtxoPredFailure(f)),
        f.toString
      )
    )
    babbage.foreach(f =>
      assertEquals(
        Some(
          summon[InjectSubUtxoFailure[DijkstraSubUtxo.BabbageFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConway(ConwayUtxo.babbageToConwayUtxoPredFailure(f)),
        f.toString
      )
    )
    // Dijkstra's UTXO and Conway's, on each constructor they map, the size's among them
    val dijkstra = (shelley.map(DijkstraUtxo.shelleyToDijkstraUtxoPredFailure(_)) ++
      allegra.map(DijkstraUtxo.allegraToDijkstraUtxoPredFailure(_)) ++
      alonzo.map(DijkstraUtxo.alonzoToDijkstraUtxoPredFailure(_)) ++
      babbage.map(DijkstraUtxo.babbageToDijkstraUtxoPredFailure(_))) :+
      DijkstraUtxoPredFailure.MaxTxSizeUTxO[Dijkstra](Mismatch(BigInt(3), BigInt(2)))
    dijkstra.foreach {
      case f: DijkstraSubUtxo.UtxoFailure[Dijkstra] =>
        assertEquals(
          Some(
            summon[InjectSubUtxoFailure[DijkstraSubUtxo.UtxoFailure[Dijkstra], Dijkstra]]
              .injectFailure(f)
          ),
          throughDijkstra(f),
          f.toString
        )
      case f => fail(s"not mapped: $f")
    }
    assertEquals(dijkstra.size, 10)
    // Conway's, on the constructors whose image the reference maps, the size's among them
    val conway = (shelley.map(f =>
      ConwayUtxo.allegraToConwayUtxoPredFailure[Dijkstra](
        AllegraUtxo.shelleyToAllegraUtxoPredFailure11_2(f)
      )
    ) ++ allegra.map(ConwayUtxo.allegraToConwayUtxoPredFailure[Dijkstra](_)) ++
      alonzo.map(ConwayUtxo.alonzoToConwayUtxoPredFailure(_)) ++
      babbage.map(ConwayUtxo.babbageToConwayUtxoPredFailure(_))) :+
      ConwayUtxoPredFailure.MaxTxSizeUTxO[Dijkstra](Mismatch(BigInt(3), BigInt(2)))
    conway.foreach {
      case f: DijkstraSubUtxo.ConwayFailure[Dijkstra] =>
        assertEquals(
          Some(
            summon[InjectSubUtxoFailure[DijkstraSubUtxo.ConwayFailure[Dijkstra], Dijkstra]]
              .injectFailure(f)
          ),
          throughConway(f),
          f.toString
        )
      case f => fail(s"not mapped: $f")
    }
    assertEquals(conway.size, 10)
  }

  private val keys =
    NonEmptySet.one(Bytes(Vector.fill(28)(1.toByte)))(using cats.Order.fromOrdering)

  private lazy val guards: NonEmptySet[Credential[Guard]] =
    NonEmptySet.one[Credential[Guard]](
      Credential.KeyHashObj[Guard](
        KeyHash
          .fromBytes[Guard](Bytes(Vector.fill(28)(2.toByte)))
          .fold(e => fail(e.toString), identity)
      )
    )(using cats.Order[Credential[Guard]])

  private val ix = AsIx[Unsigned[Unsigned.Width.W32], Bytes](
    Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("0"))
  )

  private lazy val shelleyW: Vector[DijkstraSubUtxow.ShelleyFailure[Dijkstra]] =
    Vector[DijkstraSubUtxow.ShelleyFailure[Dijkstra]](
      new ShelleyUtxowPredFailure.InvalidWitnessesUTXOW[Dijkstra](NonEmptyList.one(keys.head)),
      new ShelleyUtxowPredFailure.MissingVKeyWitnessesUTXOW[Dijkstra](keys),
      new ShelleyUtxowPredFailure.ScriptWitnessNotValidatingUTXOW[Dijkstra](keys),
      new ShelleyUtxowPredFailure.MissingTxBodyMetadataHash[Dijkstra](keys.head),
      new ShelleyUtxowPredFailure.MissingTxMetadata[Dijkstra](keys.head),
      new ShelleyUtxowPredFailure.ConflictingMetadataHash[Dijkstra](Mismatch(keys.head, keys.head)),
      new ShelleyUtxowPredFailure.InvalidMetadata[Dijkstra]()
    )

  private lazy val alonzoW: Vector[DijkstraSubUtxow.AlonzoFailure[Dijkstra]] =
    val (in, _) = sample
    Vector[DijkstraSubUtxow.AlonzoFailure[Dijkstra]](
      new AlonzoUtxowPredFailure.UnspendableUTxONoDatumHash[Dijkstra](NonEmptySet.one(in)),
      new AlonzoUtxowPredFailure.MissingRequiredDatums[Dijkstra](keys, Set.empty),
      new AlonzoUtxowPredFailure.NotAllowedSupplementalDatums[Dijkstra](keys, Set.empty),
      new AlonzoUtxowPredFailure.ExtraRedeemers[Dijkstra](
        NonEmptyList.one(DijkstraPlutusPurpose.DijkstraMinting(ix))
      ),
      new AlonzoUtxowPredFailure.MissingRedeemers[Dijkstra](
        NonEmptyList.one((DijkstraPlutusPurpose.DijkstraMinting(AsItem(keys.head)), keys.head))
      ),
      new AlonzoUtxowPredFailure.PPViewHashesDontMatch[Dijkstra](Mismatch(None, Some(keys.head))),
      new AlonzoUtxowPredFailure.ScriptIntegrityHashMismatch[Dijkstra](
        Mismatch(Some(keys.head), None),
        None
      )
    )

  private lazy val babbageW: Vector[DijkstraSubUtxow.BabbageFailure[Dijkstra]] =
    Vector[DijkstraSubUtxow.BabbageFailure[Dijkstra]](
      new BabbageUtxowPredFailure.MalformedScriptWitnesses[Dijkstra](keys),
      new BabbageUtxowPredFailure.MalformedReferenceScripts[Dijkstra](keys)
    )

  /** The reference's `dijkstraUtxowToDijkstraSubUtxowPredFailure` (`SubUtxow.hs:337-364`,
    * cardano-ledger 9df9f98c) written out again, case for case, none where it calls the constructor
    * impossible or where its `UtxoFailure` 's payload goes through the injection into SUBUTXO.
    */
  private def throughDijkstraW(
      d: DijkstraUtxowPredFailure[Dijkstra]
  ): Option[DijkstraSubUtxowPredFailure[Dijkstra]] =
    import DijkstraUtxowPredFailure as U
    import DijkstraSubUtxowPredFailure as S
    d match
      case U.UtxoFailure(_)                       => None
      case U.InvalidWitnessesUTXOW(ks)            => Some(S.SubInvalidWitnessesUTXOW(ks))
      case U.MissingVKeyWitnessesUTXOW(ks)        => Some(S.SubMissingVKeyWitnessesUTXOW(ks))
      case U.MissingScriptWitnessesUTXOW(_)       => None
      case U.ScriptWitnessNotValidatingUTXOW(hs)  => Some(S.SubScriptWitnessNotValidatingUTXOW(hs))
      case U.MissingTxBodyMetadataHash(dh)        => Some(S.SubMissingTxBodyMetadataHash(dh))
      case U.MissingTxMetadata(dh)                => Some(S.SubMissingTxMetadata(dh))
      case U.ConflictingMetadataHash(mm)          => Some(S.SubConflictingMetadataHash(mm))
      case U.InvalidMetadata()                    => Some(S.SubInvalidMetadata())
      case U.ExtraneousScriptWitnessesUTXOW(_)    => None
      case U.MissingRedeemers(pps)                => Some(S.SubMissingRedeemers(pps))
      case U.MissingRequiredDatums(h1, h2)        => Some(S.SubMissingRequiredDatums(h1, h2))
      case U.NotAllowedSupplementalDatums(h1, h2) => Some(S.SubNotAllowedSupplementalDatums(h1, h2))
      case U.PPViewHashesDontMatch(mm)            => Some(S.SubPPViewHashesDontMatch(mm))
      case U.UnspendableUTxONoDatumHash(txs)      => Some(S.SubUnspendableUTxONoDatumHash(txs))
      case U.ExtraRedeemers(pps)                  => Some(S.SubExtraRedeemers(pps))
      case U.MalformedScriptWitnesses(hs)         => Some(S.SubMalformedScriptWitnesses(hs))
      case U.MalformedReferenceScripts(hs)        => Some(S.SubMalformedReferenceScripts(hs))
      case U.ScriptIntegrityHashMismatch(mm, f)   => Some(S.SubScriptIntegrityHashMismatch(mm, f))
      case U.MissingRequiredGuards(_)             => None
      case U.MalformedGuardDatums(x)              => Some(S.SubMalformedGuardDatums(x))

  private def throughConwayW(
      c: ConwayUtxowPredFailure[Dijkstra]
  ): Option[DijkstraSubUtxowPredFailure[Dijkstra]] =
    throughDijkstraW(DijkstraUtxow.conwayToDijkstraUtxowPredFailure(c))

  test("each injection into SUBUTXOW equals the reference's composition") {
    shelleyW.foreach(f =>
      assertEquals(
        Some(
          summon[InjectSubUtxowFailure[DijkstraSubUtxow.ShelleyFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConwayW(ConwayUtxow.shelleyToConwayUtxowPredFailure[Dijkstra](f)),
        f.toString
      )
    )
    alonzoW.foreach(f =>
      assertEquals(
        Some(
          summon[InjectSubUtxowFailure[DijkstraSubUtxow.AlonzoFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConwayW(ConwayUtxow.alonzoToConwayUtxowPredFailure[Dijkstra](f)),
        f.toString
      )
    )
    babbageW.foreach(f =>
      assertEquals(
        Some(
          summon[InjectSubUtxowFailure[DijkstraSubUtxow.BabbageFailure[Dijkstra], Dijkstra]]
            .injectFailure(f)
        ),
        throughConwayW(ConwayUtxow.babbageToConwayUtxowPredFailure[Dijkstra](f)),
        f.toString
      )
    )
    // Dijkstra's UTXOW and Conway's, on each constructor they map that SUBUTXOW reaches
    val conway = (shelleyW.map(ConwayUtxow.shelleyToConwayUtxowPredFailure[Dijkstra](_)) ++
      alonzoW.map(ConwayUtxow.alonzoToConwayUtxowPredFailure[Dijkstra](_)) ++
      babbageW.map(ConwayUtxow.babbageToConwayUtxowPredFailure[Dijkstra](_)))
    conway.foreach {
      case f: DijkstraSubUtxow.ConwayFailure[Dijkstra] =>
        assertEquals(
          Some(
            summon[InjectSubUtxowFailure[DijkstraSubUtxow.ConwayFailure[Dijkstra], Dijkstra]]
              .injectFailure(f)
          ),
          throughConwayW(f),
          f.toString
        )
      case f => fail(s"not mapped: $f")
    }
    val dijkstra = conway.map(DijkstraUtxow.conwayToDijkstraUtxowPredFailure(_)) :+
      DijkstraUtxowPredFailure.MalformedGuardDatums[Dijkstra](guards)
    dijkstra.foreach {
      case f: DijkstraSubUtxow.UtxowFailure[Dijkstra] =>
        assertEquals(
          Some(
            summon[InjectSubUtxowFailure[DijkstraSubUtxow.UtxowFailure[Dijkstra], Dijkstra]]
              .injectFailure(f)
          ),
          throughDijkstraW(f),
          f.toString
        )
      case f => fail(s"not mapped: $f")
    }
    assertEquals((conway.size, dijkstra.size), (16, 17))
    // SUBUTXO's failures are wrapped as they are
    val inner = DijkstraSubUtxoPredFailure.SubInputSetEmptyUTxO[Dijkstra]()
    assertEquals(
      summon[InjectSubUtxowFailure[DijkstraSubUtxoPredFailure[Dijkstra], Dijkstra]]
        .injectFailure(inner),
      DijkstraSubUtxowPredFailure.SubUtxoFailure[Dijkstra](inner)
    )
  }

  test("the constructors SUBUTXO and SUBUTXOW cannot reach have no injection") {
    // the controls: a constructor each rule reports has one, in the same form
    assertEquals(
      compileErrors(
        "summon[InjectSubUtxoFailure[ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra], Dijkstra]]"
      ),
      ""
    )
    assertEquals(
      compileErrors(
        "summon[InjectSubUtxowFailure[DijkstraUtxowPredFailure.MalformedGuardDatums[Dijkstra], Dijkstra]]"
      ),
      ""
    )
    // SUBUTXO: what the reference calls impossible, and whole types
    assert(
      compileErrors(
        "summon[InjectSubUtxoFailure[DijkstraUtxoPredFailure.FeeTooSmallUTxO[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxoFailure[DijkstraUtxoPredFailure.UtxosFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxoFailure[ConwayUtxoPredFailure.UtxosFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxoFailure[ShelleyUtxoPredFailure.FeeTooSmallUTxO[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxoFailure[AlonzoUtxoPredFailure.NoCollateralInputs[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxoFailure[DijkstraUtxoPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxoFailure[ConwayUtxoPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    // SUBUTXOW: what the reference calls impossible, UTXOW's `UtxoFailure`, and whole types
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[DijkstraUtxowPredFailure.MissingScriptWitnessesUTXOW[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[DijkstraUtxowPredFailure.ExtraneousScriptWitnessesUTXOW[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[DijkstraUtxowPredFailure.MissingRequiredGuards[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[DijkstraUtxowPredFailure.UtxoFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[ShelleyUtxowPredFailure.MissingScriptWitnessesUTXOW[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[ConwayUtxowPredFailure.ExtraneousScriptWitnessesUTXOW[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[DijkstraUtxowPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubUtxowFailure[ShelleyUtxowPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
  }

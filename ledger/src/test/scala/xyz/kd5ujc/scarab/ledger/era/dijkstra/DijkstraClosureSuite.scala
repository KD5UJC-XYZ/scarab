// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  ChainAccountState,
  Coin,
  Dijkstra,
  EpochNo,
  Network,
  NewEpochState,
  SlotNo,
  TxIx,
  UtxoTables,
  Word64
}
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator
import xyz.kd5ujc.scarab.protocol.{EbHash, LeiosTxHash, ReferenceBuild}

/** The application of a certified endorser block's closure (`era.rules.DijkstraClosure`;
  * `applyLeiosClosure` and `resolveLeiosClosure`, ouroboros-consensus 2416493b
  * `Shelley/Ledger/Leios.hs:167-252`), on the endorser blocks' transactions the public testnet's
  * chain recorded (`fixtures/reference/leios-testnet-chain-contexts`):
  *   - the closure's lookup: none is the typed defect `Missing`, the first transaction that does
  *     not decode, trailing bytes among them, `TxUndecodable` with its hash, and a whole closure
  *     each transaction in order, as the block decoder reads it;
  *   - the environment: the slot of the tip of the state before the certifying block, zero at the
  *     origin, no epoch and the least index for every transaction (`mkMempoolEnv`);
  *   - each of the 111 endorser-block transactions applied as a closure to a state holding only the
  *     outputs it spends, with no predicate checked and no script evaluated, though the context
  *     given validates every check: no failure, its inputs spent and its outputs created; the
  *     sample's five transactions of one endorser block, a prefix of its closure, the same as each
  *     applied in turn with the ledger state and the proposals handed on, and their bytes the sum
  *     of their sizes;
  *   - the closure's order on built transactions: a chain transaction's proposal, an output paid
  *     and then spent by the next, from a state holding only the outputs from outside the closure,
  *     and the reverse order leaving the output; a transaction flagged invalid (crafted: every
  *     recorded one is flagged valid) spends its collateral and creates its return, not its body;
  *   - the four whose validity interval ends before the slot of the ranking block that certifies
  *     their endorser block apply at the endorser block's slot, the tip of the state before that
  *     block; the control: LEDGER with every check refuses each at the ranking block's slot as
  *     outside its validity interval, and not at the endorser block's;
  *   - the slot's epoch is the one the closure is applied in: a proposal the closure makes expires
  *     by the epoch of the tip's slot, not the next epoch's.
  *
  * Incomplete (CNF-2):
  *   - no recording holds a whole closure with the state before its certifying block, so the state
  *     after one is checked against its parts, not against the reference's; that is the offline
  *     replay's (#54);
  *   - a whole endorser block's closure: the fixture is a sample of transactions with redeemers and
  *     records no endorser block's transaction list; its five of the endorser block at slot 300692
  *     are the first five of that block's transactions (39, as the recorded endorser block lists
  *     them, which no fixture holds).
  */
class DijkstraClosureSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val tx = EraTx.dijkstra
  private val bodies = EraTxBody.dijkstra

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private val fixture = json("fixtures/reference/leios-testnet-chain-contexts/cases.json")
  private lazy val epochSize: BigInt =
    fixture.hcursor.get[BigInt]("epochSize").fold(e => fail(e.message), identity)

  private lazy val globals: Globals =
    flat.TestLedger.globals.copy(
      ledger = flat.TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c),
      geometry = flat.EpochGeometry
        .checked(
          Instant.EPOCH,
          1000L,
          epochSize.toLong,
          flat.TestGeometry.k(2),
          flat.TestGeometry.f("0.5")
        )
        .fold(e => fail(e.message), identity)
    )

  private lazy val history: flat.Horizon =
    val params = flat.Horizon.Params(epochSize, 1000, BigInt(10).pow(9), BigInt(10).pow(9))
    flat.Horizon
      .History(Vector.empty, flat.Horizon.Edge(0, 0, 0), params, Some(params))
      .at(flat.Horizon.Transition.Unknown(Some(BigInt(0))))

  private def context(validation: flat.TxEnv.Validation) =
    DijkstraUtxoContext(Network.Testnet, 0, history, validation)

  private lazy val start =
    NewEpochState.empty[Dijkstra](EpochNo.fromUnsigned(0L), testnetStored).es.ls

  private val noProposals: Proposals[Dijkstra] =
    Proposals.empty[Dijkstra](GovRelation[Option](None, None, None, None))

  private val account = ChainAccountState(Coin(0), Coin(0))

  /** An endorser block's recorded transaction, the slots it is recorded at and its outputs. */
  private final case class Recorded(
      name: String,
      slot: BigInt,
      ebSlot: BigInt,
      raw: Bytes,
      tx: DijkstraTx[TxLevel.Top],
      utxo: UTxO[Dijkstra]
  )

  private lazy val endorsed: Vector[Recorded] =
    chainContextCases.filter(c => str(c, "source") == "eb").map { c =>
      val raw = hex(str(c, "bytes"))
      def num(name: String): BigInt =
        c.hcursor.get[BigInt](name).fold(e => fail(e.message), identity)
      Recorded(
        str(c, "name"),
        num("slot"),
        num("ebSlot"),
        raw,
        ok(DijkstraTx.inBlock(node(raw))),
        UTxO[Dijkstra](
          c.hcursor
            .get[Vector[(String, String)]]("utxo")
            .fold(e => fail(e.message), identity)
            .map((i, o) =>
              txIn(ok(DijkstraTxIn.decode(node(hex(i))))) -> ok(
                DijkstraTxOut.decode(node(hex(o)))
              )
            )
            .toMap
        )
      )
    }

  private def holding(utxo: UTxO[Dijkstra]) =
    start.copy[Dijkstra](utxo =
      start.utxo.copy[Dijkstra](
        utxo = UtxoTables(
          flat.Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)
        ),
        instantStake = ok(EraStake.dijkstra.addInstantStake(utxo, start.utxo.instantStake))
      )
    )

  /** An evaluator that fails the test when a script is evaluated. */
  private val refusing: xyz.kd5ujc.scarab.plutus.ScriptEvaluator[cats.Id] =
    request => fail(s"a script was evaluated: $request")

  private def applied(
      txs: Vector[DijkstraTx[TxLevel.Top]],
      utxo: UTxO[Dijkstra],
      tip: BigInt
  ) =
    DijkstraBbodyRun.closure(
      globals,
      DijkstraClosure.mkMempoolEnv(tip, testnetPParams, account),
      txs,
      context(flat.TxEnv.Validation.All),
      holding(utxo),
      noProposals,
      refusing,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      slot => Right(slot * 1000)
    )

  // ---- the closure's lookup ---------------------------------------------------------------------------

  private val eb = EbHash.fromBytes(Bytes.fromArray(Array.fill(32)(7)))
  private def txHash(n: Int) = LeiosTxHash.fromBytes(Bytes.fromArray(Array.fill(32)(n.toByte)))

  test("a closure the store does not hold is the typed defect, never an empty closure") {
    assertEquals(
      DijkstraClosure.resolveLeiosClosure(eb, None),
      Left(LeiosClosureDefect.Missing(eb))
    )
    // a complete closure of no transaction is one
    assertEquals(DijkstraClosure.resolveLeiosClosure(eb, Some(Vector.empty)), Right(Vector.empty))
  }

  test("a closure's transactions read in its order, the first that does not decode the defect") {
    val txs = endorsed.take(3)
    val stored = txs.zipWithIndex.map((r, i) => txHash(i) -> r.raw)
    val read = ok(DijkstraClosure.resolveLeiosClosure(eb, Some(stored)))
    assertEquals(read.map(_._1), stored.map(_._1))
    assertEquals(
      read.map((_, t) => bodies.id[TxLevel.Top](tx.body(t))),
      txs.map(r => bodies.id[TxLevel.Top](tx.body(r.tx)))
    )
    // a transaction as a client submits it, without the block form's flag, is not one; nor are bytes
    // with another item after the transaction (`decodeFullAnnotator`); the first stops the rest
    val truncated = txs(1).raw.slice(0, txs(1).raw.size - 1)
    val trailing = txs(2).raw ++ Bytes.fromArray(Array(0xf6.toByte))
    // the cause is kept: the truncated one ends before its flag, the other has a byte after it
    Vector(
      truncated -> xyz.kd5ujc.scarab.codec.Cbor.Error.UnexpectedEnd,
      trailing -> xyz.kd5ujc.scarab.codec.Cbor.Error.TrailingBytes
    ).foreach { (bad, cause) =>
      val broken = stored.updated(1, txHash(1) -> bad).updated(2, txHash(2) -> bad)
      assertEquals(
        DijkstraClosure.resolveLeiosClosure(eb, Some(broken)),
        Left(
          LeiosClosureDefect.TxUndecodable(
            eb,
            txHash(1),
            EraTxError.Decode(flat.LedgerDecodeError.Syntax(cause))
          )
        )
      )
    }
  }

  test(
    "a closure's transaction reads as the build reads one alone, and as the block decoder reads it"
  ) {
    // each transaction case of the Leios build's corpus (`decodeDijkstraTopTxInBlock` alone at
    // version 12, the testnet's own and synthetic ones): the build's verdict, and an accepted one
    // the value the era's reader of a block's transaction gives
    val txCases = leiosCorpus.filter(c => str(c, "kind") == "tx")
    assert(txCases.size > 500, s"${txCases.size}")
    txCases.foreach { c =>
      val raw = hex(str(c, "bytes"))
      val accepted = c.hcursor.get[Boolean]("accepted").fold(e => fail(e.message), identity)
      DijkstraClosure.deserializeTxForLeios(raw) match
        case Right(t) =>
          assert(accepted, str(c, "name"))
          assertEquals(t, ok(DijkstraTx.inBlock(node(raw))), str(c, "name"))
        case Left(_) => assert(!accepted, str(c, "name"))
    }
    assert(txCases.exists(c => c.hcursor.get[Boolean]("accepted").contains(false)))
    // and every transaction of the chain's sample, endorser blocks' and ranking blocks'
    chainContextCases.foreach { c =>
      val raw = hex(str(c, "bytes"))
      assertEquals(
        ok(DijkstraClosure.deserializeTxForLeios(raw)),
        ok(DijkstraTx.inBlock(node(raw)))
      )
    }
  }

  // ---- the environment ------------------------------------------------------------------------------

  test(
    "every transaction of a closure is applied at the tip's slot, with no epoch and the least index"
  ) {
    assertEquals(DijkstraClosure.tipSlot(None), BigInt(0))
    assertEquals(DijkstraClosure.tipSlot(Some(SlotNo.fromUnsigned(300692L))), BigInt(300692))
    val env = DijkstraClosure.mkMempoolEnv(BigInt(300692), testnetPParams, account)
    assertEquals(env.ledgerSlotNo, BigInt(300692))
    assertEquals(env.ledgerEpochNo, None)
    assertEquals(env.ledgerIx.index, TxIx.minBound.index)
    assertEquals(env.ledgerIx.index, 0)
    assertEquals(env.ledgerAccount, account)
  }

  // ---- application ----------------------------------------------------------------------------------

  test(
    "each endorser-block transaction applies as a closure with no predicate checked, as its parts say"
  ) {
    assertEquals(endorsed.size, 111)
    // the context given validates every check, and the closure is applied without them: UTXOS
    // evaluates no script (`when2Phase`, labelled static), though most of these have redeemers
    assert(
      endorsed.count(r => tx.witsOf.rdmrsTxWits(tx.wits(r.tx)).entries.nonEmpty) > 50
    )
    // the control: LEDGER with every check reaches the evaluator on some of them (on a state with
    // no certificate state, those whose other checks pass)
    def evaluated(r: Recorded): Boolean =
      try
        DijkstraLedgerRun.ledger(
          globals,
          LedgerEnv[Dijkstra](r.ebSlot, None, TxIx.minBound, testnetPParams, account),
          DijkstraStAnnTx.mkDijkstraStAnnTopTx(
            slot => Right(slot * 1000),
            testnetPParams,
            r.utxo,
            Map.empty,
            r.tx
          ),
          context(flat.TxEnv.Validation.All),
          holding(r.utxo),
          noProposals,
          refusing,
          flat.Verdicts.Direct,
          ScarabEvaluator.defaultDecoder
        )
        false
      catch case _: munit.FailException => true
    assert(endorsed.exists(evaluated))
    endorsed.foreach { r =>
      // a state holding only the outputs the transaction spends: under `ValidateNone` neither the
      // empty certificate state nor the missing proposals' accounts nor the slot refuse anything
      val (after, _, _) = applied(Vector(r.tx), r.utxo, r.ebSlot) match
        case Right(Right(after)) => after
        case other               => fail(s"${r.name}: $other")
      val left = after.ls.utxo.utxo.utxo
      val body = tx.body(r.tx)
      bodies
        .inputs[TxLevel.Top](body)
        .foreach(in => assert(left.get(in).isEmpty, s"${r.name}: $in"))
      val created = UTxOs.txouts[Dijkstra, TxLevel.Top](body).unUTxO
      assert(created.nonEmpty, r.name)
      created.foreach((in, out) =>
        assertEquals(left.get(in), Some(StoredTxOut.dijkstra.toStored(out)), r.name)
      )
    }
  }

  /** The closure `txs` applied at `tip` to `state` and `proposals` with the node's evaluator: the
    * ledger state and the proposals after.
    */
  private def appliedFrom(
      txs: Vector[DijkstraTx[TxLevel.Top]],
      state: xyz.kd5ujc.scarab.ledger.state.LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      tip: BigInt
  ): (xyz.kd5ujc.scarab.ledger.state.LedgerState[Dijkstra], Proposals[Dijkstra]) =
    DijkstraBbodyRun.closure(
      globals,
      DijkstraClosure.mkMempoolEnv(tip, testnetPParams, account),
      txs,
      context(flat.TxEnv.Validation.All),
      state,
      proposals,
      ScarabEvaluator.default,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      slot => Right(slot * 1000)
    ) match
      case Right(Right((after, p, _))) => (after.ls, p)
      case other                       => fail(s"$other")

  /** The closure `txs` applied one transaction at a time, the ledger state and the proposals each
    * leaves handed to the next.
    */
  private def oneByOne(
      txs: Vector[DijkstraTx[TxLevel.Top]],
      state: xyz.kd5ujc.scarab.ledger.state.LedgerState[Dijkstra],
      tip: BigInt
  ) =
    txs.foldLeft((state, noProposals))((s, t) => appliedFrom(Vector(t), s._1, s._2, tip))

  test(
    "the sample's five transactions of an endorser block, a prefix of its closure, apply in turn"
  ) {
    // the endorser block of the sample with the most transactions (at slot 300692, certified at
    // 300749): the sample holds the first five of its transactions, a prefix of its closure; no
    // fixture records the whole (CNF-2)
    val (_, block) = endorsed.groupBy(r => (r.ebSlot, r.slot)).maxBy(_._2.size)
    assertEquals(block.size, 5)
    assertEquals((block.head.ebSlot, block.head.slot), (BigInt(300692), BigInt(300749)))
    val utxo = UTxO[Dijkstra](block.flatMap(_.utxo.unUTxO).toMap)
    val tip = block.head.ebSlot
    assertEquals(
      appliedFrom(block.map(_.tx), holding(utxo), noProposals, tip),
      oneByOne(block.map(_.tx), holding(utxo), tip)
    )
    assertEquals(
      DijkstraClosure.closureTxBytes(block.map(_.tx)),
      Word64.fromUnsigned(block.map(r => tx.sizeTxF[TxLevel.Top](r.tx)).sum)
    )
    assertEquals(DijkstraClosure.closureTxBytes(Vector.empty), Word64.fromUnsigned(0L))
  }

  private val B = DijkstraImpBatch
  private lazy val payer = flat.TestLedger.key()
  private def paid(coin: BigInt) = B.out(B.keyAddress(payer), coin)

  test(
    "a closure is applied in its order: an output a transaction creates is there for the next to spend"
  ) {
    // a chain transaction that makes a proposal, then a transaction that pays an output, then one
    // that spends it; the state holds only the outputs from outside the closure
    val (proposing, proposingUtxo) = proposingCase
    val external = B.fresh(0x71)
    val producerBody =
      B.body(
        0 -> B.set(external.node),
        1 -> B.arr(paid(5000000), paid(3000000)),
        2 -> B.uint(2000000)
      )
    val producer = B.topTx(producerBody, B.witnesses(producerBody, Vector(payer)))
    val intermediate = B.In(B.id(producerBody), 0)
    val spenderBody =
      B.body(0 -> B.set(intermediate.node), 1 -> B.arr(paid(4000000)), 2 -> B.uint(1000000))
    val spender = B.topTx(spenderBody, B.witnesses(spenderBody, Vector(payer)))
    val seeded = UTxO[Dijkstra](
      proposingUtxo.unUTxO ++ B.utxo(external -> paid(10000000)).unUTxO
    )
    val tip = BigInt(300692)
    def keyOf(in: B.In) = B.utxo(in -> paid(0)).unUTxO.keys.head
    def held(ls: xyz.kd5ujc.scarab.ledger.state.LedgerState[Dijkstra], in: B.In) =
      ls.utxo.utxo.utxo.get(keyOf(in)).isDefined
    // the producer alone creates the intermediate output
    val (afterProducer, _) = appliedFrom(Vector(producer), holding(seeded), noProposals, tip)
    assert(held(afterProducer, intermediate))
    assert(!held(afterProducer, external))
    // the three in order: the intermediate output created, then spent; the spender's output there
    val closure = Vector(proposing, producer, spender)
    val (after, proposals) = appliedFrom(closure, holding(seeded), noProposals, tip)
    assert(!held(after, external))
    assert(!held(after, intermediate))
    assert(held(after, B.In(B.id(spenderBody), 0)))
    assert(Proposals.proposalsActionsMap(proposals).nonEmpty)
    // the same as each applied in turn, the ledger state and the proposals both handed on
    assertEquals((after, proposals), oneByOne(closure, holding(seeded), tip))
    // the control: in the reverse order the spender finds nothing to spend, and the intermediate
    // output the producer creates after it is left
    val (reversed, _) = appliedFrom(closure.reverse, holding(seeded), noProposals, tip)
    assert(held(reversed, intermediate))
    assertNotEquals(reversed, after)
  }

  test("a closure's transaction flagged invalid spends its collateral and creates its return") {
    // all the recorded ones are flagged valid; this one is crafted: its body's input stays, its
    // output is not created, the collateral is spent and the return created at the index after the
    // outputs (`collOuts`)
    val (input, collateral) = (B.fresh(0x72), B.fresh(0x73))
    val body = B.body(
      0 -> B.set(input.node),
      1 -> B.arr(paid(4000000)),
      2 -> B.uint(1000000),
      13 -> B.set(collateral.node),
      16 -> paid(3000000)
    )
    val invalid = B.topTx(body, B.witnesses(body, Vector(payer)), valid = false)
    val utxo = B.utxo(input -> paid(5000000), collateral -> paid(4000000))
    val (after, _) = appliedFrom(Vector(invalid), holding(utxo), noProposals, BigInt(300692))
    val left = after.utxo.utxo.utxo
    def keyOf(in: B.In) = B.utxo(in -> paid(0)).unUTxO.keys.head
    assert(left.get(keyOf(input)).isDefined)
    assert(left.get(keyOf(collateral)).isEmpty)
    assert(left.get(keyOf(B.In(B.id(body), 0))).isEmpty)
    assertEquals(
      left.get(keyOf(B.In(B.id(body), 1))),
      Some(StoredTxOut.dijkstra.toStored(B.utxo(B.fresh(0) -> paid(3000000)).unUTxO.values.head))
    )
  }

  // ---- the regression: the slot of the state before the certifying block -----------------------------

  private def outsideValidity(f: LedgerPredFailure[Dijkstra]): Boolean = f match
    case DijkstraLedgerPredFailure.DijkstraUtxowFailure(
          DijkstraUtxowPredFailure.UtxoFailure(
            DijkstraUtxoPredFailure.OutsideValidityIntervalUTxO(_, _)
          )
        ) =>
      true
    case _ => false

  test("the four transactions whose validity ends before their certifying block's slot apply") {
    val late =
      endorsed.filter(r => bodies.vldt[TxLevel.Top](r.tx.body).invalidHereafter.exists(_ <= r.slot))
    assertEquals(late.size, 4)
    late.foreach { r =>
      // applied at the endorser block's slot, the tip of the state before the certifying block
      applied(Vector(r.tx), r.utxo, r.ebSlot) match
        case Right(Right(_)) => ()
        case other           => fail(s"${r.name}: $other")
      // the control: LEDGER with every check refuses it as outside its validity interval at the
      // certifying block's slot, which validating at that slot would split from the chain on, and
      // not at the endorser block's
      def judged(at: BigInt) =
        DijkstraLedgerRun.ledger(
          globals,
          LedgerEnv[Dijkstra](at, None, TxIx.minBound, testnetPParams, account),
          DijkstraStAnnTx.mkDijkstraStAnnTopTx(
            slot => Right(slot * 1000),
            testnetPParams,
            r.utxo,
            Map.empty,
            r.tx
          ),
          context(flat.TxEnv.Validation.All),
          holding(r.utxo),
          noProposals,
          ScarabEvaluator.default,
          flat.Verdicts.Direct,
          ScarabEvaluator.defaultDecoder
        ) match
          case Right(Left(fs)) => fs.toChain.toVector
          case Right(Right(_)) => Vector.empty
          case Left(defect)    => fail(s"${r.name}: $defect")
      assert(judged(r.slot).exists(outsideValidity), r.name)
      assert(!judged(r.ebSlot).exists(outsideValidity), r.name)
    }
  }

  /** The sample's first transaction that makes a proposal, with the outputs it spends. */
  private lazy val proposingCase: (DijkstraTx[TxLevel.Top], UTxO[Dijkstra]) =
    val (t, c) = chainContextCases
      .map(c => ok(DijkstraTx.inBlock(node(hex(str(c, "bytes"))))) -> c)
      .find((t, _) => bodies.proposalProcedures[TxLevel.Top](t.body).toVector.nonEmpty)
      .getOrElse(fail("no proposal in the sample"))
    val utxo = UTxO[Dijkstra](
      c.hcursor
        .get[Vector[(String, String)]]("utxo")
        .fold(e => fail(e.message), identity)
        .map((i, o) =>
          txIn(ok(DijkstraTxIn.decode(node(hex(i))))) -> ok(DijkstraTxOut.decode(node(hex(o))))
        )
        .toMap
    )
    (t, utxo)

  // ---- the slot's epoch ---------------------------------------------------------------------------

  test(
    "a proposal of a closure expires by the epoch of the tip's slot, not the certifying block's"
  ) {
    val (t, utxo) = proposingCase
    // the tip in the last slot of an epoch, and the certifying block in the first of the next: the
    // closure is applied in the tip's epoch (`Leios.hs:237`, ouroboros-consensus 2416493b), the
    // block after its tick
    val epoch = BigInt(5)
    val lastSlot = globals.firstSlot(epoch + 1) - 1
    def expiries(at: BigInt) = applied(Vector(t), utxo, at) match
      case Right(Right((_, proposals, _))) =>
        Proposals.proposalsActionsMap(proposals).values.map(_.gasExpiresAfter.toBigInt).toSet
      case other => fail(s"$other")
    // the parameters are the stored ones read into the era (`testnetPParams`)
    val lifetime = testnetStored.govActionLifetime
    assertEquals(expiries(lastSlot), Set(epoch + lifetime))
    assertEquals(expiries(lastSlot + 1), Set(epoch + 1 + lifetime))
  }

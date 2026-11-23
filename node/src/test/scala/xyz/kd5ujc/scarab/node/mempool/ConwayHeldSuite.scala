// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.data.{NonEmptyChain, NonEmptySet}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{GovActionId, LedgerDefect, LedgerFailure, TxDefect, TxIn}
import xyz.kd5ujc.scarab.ledger.era.{
  EraTxError,
  IsPhase2Valid,
  Mismatch,
  PParams,
  ProposalsReadError,
  ProposalsWriteError
}
import xyz.kd5ujc.scarab.ledger.era.Relation.RelLTEQ
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayLedgerPredFailure,
  ConwayMempoolRun,
  ConwayRuleDefect,
  GovDefect,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.state.{ChainAccountState, Coin, VocabError}
import xyz.kd5ujc.scarab.network.{LocalTxSubmissionServer, NetworkError}
import xyz.kd5ujc.scarab.node.forge.BlockBuilder
import xyz.kd5ujc.scarab.node.metrics.RejectionLabel
import xyz.kd5ujc.scarab.node.ntc.ConwayFailureCbor
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** A held Conway transaction in the era's types ([[Held.InConway]], [[ConwayRead]]) and the pool's
  * answers on Conway's rules ([[Rejection.ConwayRefused]], [[Rejection.ConwayUnjudged]]), each
  * placed by kind: what every reader of a held transaction takes of it, the forger's selection
  * among them, and the route of every defect of Conway's rules.
  */
class ConwayHeldSuite extends FunSuite:
  import TxKit.*

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  /** TxKit's slot, in its state's epoch (5: 1000-slot epochs). */
  private val slot: BigInt = 5001

  private val (raw, id) = pay(1)
  private val parsed = right("parts")(MempoolState.parseParts(raw))
  private val read = right("Conway's reading")(
    ConwayRead.of(EraTx(parsed.parts, parsed.tx), StateLayout.Node11_1)
  )

  /** `read` held as if validated in TxKit's ledger (`unsafeMakeValidatedTx`, which the reference
    * keeps for tests).
    */
  private def admitted(ledger: MempoolLedger): ConwayAdmitted =
    val judging = ConwayPoolEnv.of(env, slot).judging
    val pp = PParams[Conway](right("parameters")(ConwayPParams.fromStored(ledger.params)))
    val menv =
      ConwayMempoolRun.mkMempoolEnv(slot, pp, ChainAccountState(ledger.treasury, Coin.zero))
    ConwayAdmitted(
      read,
      right("held")(ConwayMempoolRun.unsafeMakeValidatedTx(judging, menv, ledger.ls, read.tx))
    )

  private type Conway = xyz.kd5ujc.scarab.ledger.state.Conway

  test("a Conway reading holds the parts, the flat transaction and the era's, one flag for all") {
    assertEquals(read.id, id)
    assertEquals(read.flat, parsed.tx)
    assertEquals(read.raw, raw)
    assertEquals(read.isValid, IsPhase2Valid.Phase2Valid)
    val flipped = read.withIsValid(IsPhase2Valid.Phase2Invalid)
    assertEquals(flipped.isValid, IsPhase2Valid.Phase2Invalid)
    assertEquals(flipped.parts.isValid, false)
    assertEquals(flipped.flat.isValid, false)
    assertEquals(flipped.id, id)
    // the flag is the only byte of the relayed form that changes
    assertEquals(flipped.raw, EraInjection.envelope(parsed.parts.copy(isValid = false), Era.Conway))
  }

  test("every reader takes of a held Conway transaction what it took of the flat one") {
    val ledger = TxKit.ledger(2)
    val conway =
      Entry(Held.InConway(admitted(ledger)), 1L, 7L, scala.concurrent.duration.Duration.Zero)
    val flat = Entry(Era.Conway, parsed.parts, parsed.tx, 1L, 7L)
    assertEquals(conway.era, Era.Conway)
    assertEquals(conway.id, flat.id)
    assertEquals(conway.raw, flat.raw)
    assertEquals(conway.size, flat.size)
    assertEquals(conway.leiosTx, None)
    // the measure: `sizeTxF` and the redeemers' units of the era's transaction, as of the flat one
    assertEquals(Capacity.measure(conway), Capacity.measure(flat))
    assertEquals(
      MempoolState.readSet(Vector(conway.held.in)),
      MempoolState.readSet(Vector(flat.held.in))
    )
    assertEquals(
      LocalMonitor.wrapTx(conway.era, conway.raw),
      LocalMonitor.wrapTx(flat.era, flat.raw)
    )
  }

  test("the forger takes a held Conway transaction into its block: none falls out of every block") {
    val ledger = TxKit.ledger(2)
    val entry =
      Entry(Held.InConway(admitted(ledger)), 1L, 0L, scala.concurrent.duration.Duration.Zero)
    val limit = BlockBuilder.Limits(65536, xyz.kd5ujc.scarab.plutus.ExUnits(14000000, 10000000000L))
    val selection = BlockBuilder.select(
      ledger,
      slot,
      5,
      env.geometry,
      env.globals,
      Vector(entry),
      limit
    )(env.decoder, env.evaluator)
    assertEquals(selection.chosen.map(_._1.id), Vector(id))
    assertEquals(selection.withheld, Vector.empty)
    assertEquals(BlockBuilder.piece(entry).map(_.body), Right(parsed.parts.body))
  }

  private val pot: VocabError =
    VocabError.OutOfRange(VocabError.Kind.CompactCoin, BigInt(1) << 64)
  private val stored: UtxoStateDefect.StoredOutput =
    UtxoStateDefect.StoredOutput(TxIn(Bytes.empty, 0), EraTxError.OtherLevel)
  private val overflow: GovDefect.ProposalIndexOverflow = GovDefect.ProposalIndexOverflow(65536)
  private val action = GovActionId(Bytes.fromArray(Array.fill(32)(5.toByte)), 0)
  private val unread = ProposalsReadError.Unattached(action)
  private val unwritten = ProposalsWriteError.UnstorableUpdate(action, NonEmptySet.of(BigInt(34)))

  test("each defect of Conway's rules comes to its rejection, by kind") {
    val ledgerDefects = Vector[LedgerDefect](
      LedgerFailure.Vocab(pot),
      LedgerFailure.StateOutOfRange(id, pot),
      LedgerFailure.Undecodable(id, TxDefect.DelegationWithoutDelegatee),
      LedgerFailure.NativeScriptUnread(id, Bytes.empty)
    )
    ledgerDefects.foreach(d =>
      assertEquals(ConwayUnjudged.rejection(id, ConwayRuleDefect.Ledger(d)), Rejection.Ledger(d))
    )
    val cases = Vector[(ConwayRuleDefect, Rejection)](
      ConwayRuleDefect.UtxoState(UtxoStateDefect.Vocab(pot)) ->
        Rejection.Ledger(LedgerFailure.StateOutOfRange(id, pot)),
      ConwayRuleDefect.Gov(GovDefect.Vocab(pot)) ->
        Rejection.Ledger(LedgerFailure.StateOutOfRange(id, pot)),
      ConwayRuleDefect.UtxoState(stored) ->
        Rejection.ConwayUnjudged(Some(id), ConwayUnjudged.UtxoState(stored)),
      ConwayRuleDefect.Gov(overflow) ->
        Rejection.ConwayUnjudged(Some(id), ConwayUnjudged.Gov(overflow)),
      ConwayRuleDefect.ProposalsRead(unread) ->
        Rejection.ConwayUnjudged(Some(id), ConwayUnjudged.ProposalsRead(unread)),
      ConwayRuleDefect.ProposalsWrite(unwritten) ->
        Rejection.ConwayUnjudged(Some(id), ConwayUnjudged.ProposalsWrite(unwritten))
    )
    cases.foreach((defect, rejection) =>
      assertEquals(ConwayUnjudged.rejection(id, defect), rejection, defect)
    )
  }

  /** The node's own defects with no counterpart in the flat rules: each is no verdict. */
  private val unjudged: Vector[ConwayUnjudged] = Vector(
    ConwayUnjudged.UtxoState(stored),
    ConwayUnjudged.Gov(overflow),
    ConwayUnjudged.ProposalsRead(unread),
    ConwayUnjudged.ProposalsWrite(unwritten),
    ConwayUnjudged.Projection(EraTxError.ScriptsReadAgain)
  )

  test("no verdict on a Conway transaction: no reply, the client's connection ends, by kind") {
    unjudged.foreach { cause =>
      val rejection = Rejection.ConwayUnjudged(Some(id), cause)
      assertEquals(
        LocalSubmission.response(Refused(rejection, Some(Era.Conway))),
        LocalTxSubmissionServer.Response.Aborted(cause),
        cause
      )
      assertEquals(RejectionLabel.of(rejection), RejectionLabel.NoVerdict, cause)
    }
  }

  test("no verdict on a peer's Conway transaction charges nothing; a disagreeing reading closes") {
    unjudged.foreach { cause =>
      val expected = cause match
        case ConwayUnjudged.Projection(_) =>
          Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(cause)))
        case ConwayUnjudged.Unavailable(_) | ConwayUnjudged.UtxoState(_) | ConwayUnjudged.Gov(_) |
            ConwayUnjudged.ProposalsRead(_) | ConwayUnjudged.ProposalsWrite(_) =>
          None
      assertEquals(
        Mempool.peerConnectionEnd(Rejection.ConwayUnjudged(Some(id), cause)),
        expected,
        cause
      )
    }
  }

  test("Conway's refusal is sent in the era's encoding, and charges nothing") {
    val failures = NonEmptyChain.one(
      ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[Conway](
        Mismatch[RelLTEQ, Long](204801L, 204800L)
      )
    )
    val rejection = Rejection.ConwayRefused(id, failures)
    val bytes = right("encoding")(ConwayFailureCbor.applyTxError(failures.toNonEmptyList))
    assertEquals(LocalSubmission.reasonItem(rejection, Some(Era.Conway)), Right(bytes))
    assertEquals(
      LocalSubmission.response(Refused(rejection, Some(Era.Conway))),
      LocalTxSubmissionServer.Response.Rejected(bytes)
    )
    assertEquals(Mempool.peerConnectionEnd(rejection), None)
    assertEquals(RejectionLabel.of(rejection), RejectionLabel.Ledger)
  }

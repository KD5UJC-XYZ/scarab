// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Stateful, Tell}
import monocle.Lens
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  GovActionId,
  PredicateFailure,
  Proposal,
  Proposals as StoredProposals,
  TestLedger,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayProcedures,
  ConwayProposals,
  ConwayTx,
  ConwayTxCert
}
import xyz.kd5ujc.scarab.protocol.StateLayout
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayGov,
  ConwayGovPredFailure,
  GovActionNotRead,
  GovEnv,
  GovEvent,
  GovPredFailure,
  GovSignal,
  GovDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Judgment, RuleT, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Network}

/** Conway's GOV on the era types (`ConwayGov.conwayGovTransition`) run in one judgment, and the
  * forms its failures and state are compared in with the flat GOV's, for the suites that compare
  * the two (`ConwayGovChecksSuite` and `UpstreamGovernanceFunctionalSuite` in the node).
  */
object EraGovRun:
  type Rec = GovPredFailure[Conway]
  type Events = Chain[GovEvent[Conway]]
  type S = (Proposals[Conway], Events)
  type F[A] = RuleT[Rec, GovDefect, S, A]
  import RuleT.given

  private given Stateful[F, Proposals[Conway]] =
    summon[Stateful[F, S]].focus(Lens[S, Proposals[Conway]](_._1)(p => s => (p, s._2)))

  private given Tell[F, Events] = new Tell[F, Events]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Events): F[Unit] = summon[Stateful[F, S]].modify((p, e) => (p, e ++ events))

  /** The signal of a Conway transaction: its votes, proposals and certificates. */
  def signalOf(tx: ConwayTx): GovSignal[Conway] =
    val t = EraTx.conway
    val b = EraTxBody.conway
    val body = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    GovSignal[Conway](
      b.votingProcedures[TxLevel.Top](body)(using b.top),
      b.proposalProcedures[TxLevel.Top](body)(using b.top),
      b.certs[TxLevel.Top](body)(using b.top)
    )

  /** The signal of a GOV judgment recorded apart from any transaction: votes, proposals and
    * certificates as the flat decoders read them, each made Conway's as Conway's decoder makes it
    * from what the flat decoder read.
    */
  def signalFromFlat(
      votes: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])],
      proposals: Vector[Proposal],
      certificates: Vector[Certificate]
  ): Either[EraTxError, GovSignal[Conway]] =
    (
      proposals.traverse(ConwayProcedures.proposal),
      certificates.traverse(ConwayTxCert.fromFlat(_, StateLayout.Node11_1))
    ).mapN((ps, cs) => GovSignal[Conway](ConwayProcedures.votes(votes), OSet.decoded(ps), cs))

  /** The node's record of the proposals read into Conway's, as the suites have it. */
  def proposals(stored: StoredProposals): Either[ProposalsReadError, Proposals[Conway]] =
    ConwayProposals.fromStored(stored)

  /** Conway's proposals as the node records them. */
  def toStored(ps: Proposals[Conway]): StoredProposals = ConwayProposals.toStored(ps)

  /** The transition from `state`, every failure accumulated: a defect, the failures in the
    * reference's order, or the proposals after with GOV's events. The reference reports the root's
    * failures reversed and those one level down in order, by the parity of the depth
    * (`Judgment.flatten`): `depth` runs GOV that many levels down. A submitted transaction's GOV is
    * two levels down, under MEMPOOL and LEDGER (`Conway/Rules/Mempool.hs:138`,
    * `Conway/Rules/Ledger.hs:409-420`, cardano-ledger f649f975).
    */
  def run(
      env: GovEnv[Conway],
      signal: GovSignal[Conway],
      netId: Network,
      state: Proposals[Conway],
      depth: Int = 0
  ): Either[GovDefect, Either[NonEmptyChain[Rec], S]] =
    val transition = ConwayGov.conwayGovTransition[F, Conway](env, signal, netId)
    val nested = (0 until depth).foldLeft(transition)((fa, _) => summon[Judge[F, Rec]].nest(fa))
    Judgment
      .judge(nested, RuleT.Policy.Accumulate)(TestLedger.globals, (state, Chain.empty))
      .map(_.map(_._1))

  /** A failure in the node's flat form, its action read from the signal's proposals. */
  def stored(
      signal: GovSignal[Conway],
      failure: ConwayGovPredFailure[Conway]
  ): Either[GovActionNotRead[Conway], PredicateFailure] =
    ConwayGovPredFailure.toStored(
      failure,
      ConwayGovPredFailure.readOf(signal.gsProposalProcedures.toVector)
    )

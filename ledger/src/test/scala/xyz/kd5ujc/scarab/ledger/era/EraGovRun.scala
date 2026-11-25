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
  GovFailure,
  PredicateFailure,
  PrevIds,
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
  DijkstraGovPredFailure,
  DijkstraSUBGOV,
  GovActionNotRead,
  GovEnv,
  GovEvent,
  GovPredFailure,
  GovSignal,
  GovDefect,
  InjectGov,
  SubGovEvent,
  SubGovPredFailure
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraPending
import xyz.kd5ujc.scarab.ledger.rules.{DijkstraConway, Judge, Judgment, RuleT, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra, EraTag, Network}

/** Conway's GOV on the era types (`ConwayGov.conwayGovTransition`) run in one judgment, and the
  * forms its failures and state are compared in with the flat GOV's, for the suites that compare
  * the two (`ConwayGovChecksSuite` in the node), and for those that hold it to the reference's
  * judgments (`UpstreamGovernanceFunctionalSuite` in the node); and Dijkstra's, the same transition
  * (`DijkstraGovSuite`, and `DijkstraRulesOracleSuite` in the node).
  */
object EraGovRun:
  type Rec = GovPredFailure[Conway]
  type Events = Chain[GovEvent[Conway]]
  type S = (Proposals[Conway], Events)

  /** A GOV judgment of era `E`: the proposals with the events told so far. */
  private final class Run[E <: EraTag]:
    type Events = Chain[GovEvent[E]]
    type F[A] = RuleT[GovPredFailure[E], GovDefect, (Proposals[E], Events), A]
    import RuleT.given

    given Stateful[F, Proposals[E]] =
      summon[Stateful[F, (Proposals[E], Events)]]
        .focus(Lens[(Proposals[E], Events), Proposals[E]](_._1)(p => s => (p, s._2)))

    given Tell[F, Events] = new Tell[F, Events]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Events): F[Unit] =
        summon[Stateful[F, (Proposals[E], Events)]].modify((p, e) => (p, e ++ events))

    /** Conway's transition at `E` from `state`, `depth` levels below the root. */
    def run(
        env: GovEnv[E],
        signal: GovSignal[E],
        netId: Network,
        state: Proposals[E],
        depth: Int
    )(using
        ConwayEraPParams[E],
        ConwayEraCertState[E],
        ConwayEraTxCert[E],
        InjectGov[E]
    ): Either[GovDefect, Either[NonEmptyChain[GovPredFailure[E]], (Proposals[E], Events)]] =
      val transition = ConwayGov.conwayGovTransition[F, E](env, signal, netId)
      val nested =
        (0 until depth).foldLeft(transition)((fa, _) =>
          summon[Judge[F, GovPredFailure[E]]].nest(fa)
        )
      Judgment
        .judge(nested, RuleT.Policy.Accumulate)(TestLedger.globals, (state, Chain.empty))
        .map(_.map(_._1))

  private val conwayRun = Run[Conway]
  private val dijkstraRun = Run[Dijkstra]

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
    conwayRun.run(env, signal, netId, state, depth)

  /** A failure in the node's flat form, its action read from the signal's proposals. */
  def stored(
      signal: GovSignal[Conway],
      failure: ConwayGovPredFailure[Conway]
  ): Either[GovActionNotRead[Conway], PredicateFailure] =
    ConwayGovPredFailure.toStored(
      failure,
      ConwayGovPredFailure.readOf(signal.gsProposalProcedures.toVector)
    )

  // ---- Dijkstra's GOV: Conway's transition at Dijkstra (`Dijkstra/Rules/Gov.hs:199-220`,
  // cardano-ledger 9df9f98c)

  /** Dijkstra's GOV from `state`, `depth` levels below the root, every failure accumulated: a
    * defect, the failures in the reference's order, or the proposals after with GOV's events. The
    * reference's oracle applies it at the root (`DijkstraRulesOracleSuite`).
    */
  def dijkstra(
      env: GovEnv[Dijkstra],
      signal: GovSignal[Dijkstra],
      netId: Network,
      state: Proposals[Dijkstra],
      depth: Int = 0
  ): Either[GovDefect, Either[
    NonEmptyChain[GovPredFailure[Dijkstra]],
    (
        Proposals[Dijkstra],
        Chain[GovEvent[Dijkstra]]
    )
  ]] =
    dijkstraRun.run(env, signal, netId, state, depth)

  /** Dijkstra's SUBGOV (`DijkstraSUBGOV.subGovTransition`) from `state` at the root, every failure
    * accumulated, as [[dijkstra]] runs GOV.
    */
  def subGov(
      env: GovEnv[Dijkstra],
      signal: GovSignal[Dijkstra],
      netId: Network,
      state: Proposals[Dijkstra]
  ): Either[GovDefect, Either[
    NonEmptyChain[SubGovPredFailure[Dijkstra]],
    (Proposals[Dijkstra], Chain[SubGovEvent[Dijkstra]])
  ]] =
    type Events = Chain[SubGovEvent[Dijkstra]]
    type F[A] = RuleT[SubGovPredFailure[Dijkstra], GovDefect, (Proposals[Dijkstra], Events), A]
    import RuleT.given
    given Stateful[F, Proposals[Dijkstra]] =
      summon[Stateful[F, (Proposals[Dijkstra], Events)]]
        .focus(Lens[(Proposals[Dijkstra], Events), Proposals[Dijkstra]](_._1)(p => s => (p, s._2)))
    given Tell[F, Events] = new Tell[F, Events]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Events): F[Unit] =
        summon[Stateful[F, (Proposals[Dijkstra], Events)]].modify((p, e) => (p, e ++ events))
    Judgment
      .judge(DijkstraSUBGOV.subGovTransition[F](env, signal, netId), RuleT.Policy.Accumulate)(
        TestLedger.globals,
        (state, Chain.empty)
      )
      .map(_.map(_._1))

  /** A Dijkstra failure in the flat form the flat Dijkstra GOV gives, its action read from the
    * signal's proposals.
    */
  def dijkstraStored(
      signal: GovSignal[Dijkstra],
      failure: DijkstraGovPredFailure[Dijkstra]
  ): Either[GovActionNotRead[Dijkstra], GovFailure] =
    DijkstraGovPredFailure.toStored(
      failure,
      DijkstraGovPredFailure.readOf(signal.gsProposalProcedures.toVector)
    )

  /** No proposals, with no root of any purpose: the reference's default store (`def`). */
  def dijkstraEmpty: Proposals[Dijkstra] =
    Proposals.empty[Dijkstra](GovRelation[Option](None, None, None, None))

  /** Dijkstra's proposals in the form the flat Dijkstra GOV keeps them, the node's record of
    * Conway's: each proposal as the flat rule reads the one it was read as
    * (`DijkstraConway.proposal`), one way. A parameter change has no such form: the flat rule halts
    * on it.
    */
  def dijkstraToStored(ps: Proposals[Dijkstra]): Either[DijkstraPending, StoredProposals] =
    val r = Proposals.toPrevGovActionIds(ps.pRoots)
    ps.pProps.toVector
      .traverse(gas =>
        DijkstraConway
          .proposal(gas.gasProposalProcedure.read)
          .map(xyz.kd5ujc.scarab.ledger.era.conway.ConwayProposals.storedWith(gas, _))
      )
      .map(actions =>
        StoredProposals(
          actions,
          PrevIds(
            r.grPParamUpdate.map(_.unGovPurposeId),
            r.grHardFork.map(_.unGovPurposeId),
            r.grCommittee.map(_.unGovPurposeId),
            r.grConstitution.map(_.unGovPurposeId)
          )
        )
      )

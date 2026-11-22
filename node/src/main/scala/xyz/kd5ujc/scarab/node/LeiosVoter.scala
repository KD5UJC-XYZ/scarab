// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Clock}
import cats.mtl.Raise
import cats.syntax.all.*
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.leios.{
  EbClosureVerdict,
  LeiosVoteTiming,
  LeiosVotingDefect,
  LeiosVotingEnv,
  SelectionView,
  TipCommittee
}
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.crypto.bls.{RbHash, Vote}
import xyz.kd5ujc.scarab.consensus.leios.AddVoteResult
import xyz.kd5ujc.scarab.ledger.{LeiosCertificationDefect, StorageError}
import xyz.kd5ujc.scarab.ledger.era.rules.LeiosClosureDefect
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, TickFailure}
import xyz.kd5ujc.scarab.ledger.rules.contramap
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, SlotNo}
import xyz.kd5ujc.scarab.node.mempool.{DijkstraPoolEnv, MempoolEnv, Rejection}
import xyz.kd5ujc.scarab.node.read.TxClock
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{EbHash, Era, LeiosTxHash, LeiosWireError}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32
import xyz.kd5ujc.scarab.storage.UtxoReadView

/** A failure of the node's voting, never a verdict on an endorser block or a vote: each ends the
  * voting thread and stops the node, as an exception does the reference's, which the node kernel
  * forks linked to the node (`forkLinkedThread`, `NodeKernel.hs:661-671` at ouroboros-consensus
  * 2416493b).
  */
enum LeiosVotingFault:
  /** Signing a vote failed ([[LeiosVotingDefect]]). */
  case Vote(cause: LeiosVotingDefect)

  /** The vote state reached no answer on a vote of ours ([[LeiosVotesDefect]]). */
  case Votes(cause: LeiosVotesDefect)

  /** The selection's committee could not be computed. */
  case Committee(cause: LeiosCertificationDefect)

  /** The announcing block's state could not be ticked to the endorser block's slot. */
  case Tick(slot: BigInt, cause: TickFailure)

  /** A Leios period of the selection's parameters is not the `Milliseconds32` it is. */
  case Timing(cause: LeiosWireError)

  /** The store of the closure or of the stored outputs failed. */
  case Storage(cause: StorageError)

  def message: String = this match
    case Vote(cause)      => s"Leios voting: $cause"
    case Votes(cause)     => s"Leios vote state: $cause"
    case Committee(cause) => s"Leios committee: $cause"
    case Tick(slot, cause) =>
      s"Leios voting: cannot tick the announcing state to slot $slot: $cause"
    case Timing(cause)  => s"Leios vote timing: $cause"
    case Storage(cause) => s"Leios voting: ${cause.message}"

  /** How the node stops on it. */
  def fatal: Fatal = this match
    case Storage(cause) => Fatal.Storage(cause)
    case other          => Fatal.LeiosVoting(other)

/** The selection a closure is checked against: the selected chain's state at its tip, whose ledger
  * is Dijkstra's (`nes`), the only era a block announces an endorser block in.
  */
final case class VotingTip(state: MirrorState, nes: NewEpochState[Dijkstra])

/** The node's Leios voter: [[LeiosVotingRun]] 's capabilities over the node's selection, its
  * endorser-block store, its stored outputs, its mempool's environment and its vote state.
  */
object LeiosVoter:
  type Env[F[_]] = LeiosVotingEnv[F, VotingTip, Rejection, LeiosClosureDefect]

  /** The time since the system start `systemStart` reads, by `Clock[F]`. */
  def sinceStart[F[_]: Clock: cats.Monad](systemStart: F[Instant]): F[FiniteDuration] =
    (systemStart, Clock[F].realTimeInstant).mapN((start, now) =>
      JDuration.between(start, now).toNanos.nanos
    )

  /** The view of the selected chain `chain` reads (the forker at the volatile tip,
    * `LeiosVoting.hs:324-341` at 2416493b): its tip's chain-dependent state and hash, its committee
    * (`committee`) and its ledger state; none where the tip's ledger is not Dijkstra's, whose tip
    * announces no endorser block (`tipAnnouncerFor` on a chain-dependent state with no
    * announcement).
    */
  def selection[F[_]: Async](
      chain: F[VolatileChain[MirrorState]],
      committee: MirrorState => F[Option[TipCommittee]]
  ): F[Option[SelectionView[VotingTip]]] =
    chain.flatMap { c =>
      val state = c.state
      dijkstraOf(state).traverse(nes =>
        committee(state).map(tipCommittee =>
          SelectionView(
            state.protocol.dep,
            c.tipRef.flatMap(b => RbHash.fromBytes(b.hash)),
            tipCommittee,
            VotingTip(state, nes)
          )
        )
      )
    }

  /** `getLeiosVoteTiming` of the selection's ledger state (`Shelley/Ledger/Ledger.hs:975`,
    * `LeiosDemoTypes.hs:1512-1513, 2815-2826`, ouroboros-consensus 2416493b): the Dijkstra
    * parameters' periods, the default in every other era.
    */
  def timing[F[_]: Async](chain: F[VolatileChain[MirrorState]])(using
      Raise[F, LeiosVotingFault]
  ): F[LeiosVoteTiming] =
    chain.flatMap(c =>
      dijkstraOf(c.state) match
        case None => Async[F].pure(LeiosVoteTiming.default)
        case Some(nes) =>
          val pp = EraLedger.curPParams(nes.es.ls.utxo.gov)
          (Word32.from(pp.leiosAnnouncementPeriodLength), Word32.from(pp.leiosVotePeriodLength))
            .mapN((announcement, vote) => LeiosVoteTiming.of(announcement.toLong, vote.toLong))
            .fold(e => Raise[F, LeiosVotingFault].raise(LeiosVotingFault.Timing(e)), Async[F].pure)
    )

  /** The closure check of the endorser block at `point` on the tip `tip` (`validateEbClosure`,
    * [[LeiosClosureCheck]]): the tip's state ticked to the block's slot, then the closure from
    * `lookup` applied to it at that slot, reapplying what `cache` holds validated, with the
    * mempool's environment and the clock of a transaction of that slot on the tip's state.
    */
  def validateClosure[F[_]: Async](
      lookup: EbHash => F[Option[Vector[(LeiosTxHash, Bytes)]]],
      utxoStore: Option[UtxoReadView[F]],
      cache: LeiosTxCache[F],
      poolEnv: F[MempoolEnv],
      txClock: TxClock[MirrorState]
  )(point: xyz.kd5ujc.scarab.protocol.EbPoint, tip: VotingTip)(using
      fault: Raise[F, LeiosVotingFault]
  ): F[EbClosureVerdict[Rejection, LeiosClosureDefect]] =
    val slot = point.slot.toBigInt
    given Raise[F, StorageError] = fault.contramap(LeiosVotingFault.Storage(_))
    for
      env <- poolEnv
      at <- SlotNo
        .fromBigInt(slot)
        .leftMap(v => TickFailure.Vocab(v))
        .flatMap(LeiosClosureCheck.ticked(env.rules, tip.nes, _))
        .fold(e => fault.raise(LeiosVotingFault.Tick(slot, e)), Async[F].pure)
      verdict <- LeiosClosureCheck.validateEbClosure(
        lookup,
        utxoStore,
        cache,
        point,
        at,
        DijkstraPoolEnv.of(env, slot).copy(clock = txClock.at(tip.state, Era.Dijkstra, slot))
      )
    yield verdict

  /** The voter's capabilities: the clock since `systemStart`, the [[selection]], the closure check
    * `validate`, and the vote state `votes` (`addVote`, whose vote, once counted, its subscribers,
    * the LeiosNotify servers among them, relay). A valid closure is traced with what was reapplied
    * and applied and how long the check took, by the monotonic clock from before it began
    * (`TraceLeiosEbValidated`, `LeiosVoting.hs:353-372` at ouroboros-consensus 2416493b).
    */
  def env[F[_]: Async](
      systemStart: F[Instant],
      chain: F[VolatileChain[MirrorState]],
      committee: MirrorState => F[Option[TipCommittee]],
      validate: (xyz.kd5ujc.scarab.protocol.EbPoint, VotingTip) => F[
        EbClosureVerdict[Rejection, LeiosClosureDefect]
      ],
      votes: LeiosVotes[F],
      trace: LeiosKernelEvent[Rejection, LeiosClosureDefect] => F[Unit]
  )(using fault: Raise[F, LeiosVotingFault]): Env[F] =
    new LeiosVotingEnv[F, VotingTip, Rejection, LeiosClosureDefect]:
      def now: F[FiniteDuration] = sinceStart[F](systemStart)
      def selection: F[Option[SelectionView[VotingTip]]] = LeiosVoter.selection(chain, committee)
      def validateClosure(
          point: xyz.kd5ujc.scarab.protocol.EbPoint,
          ledger: VotingTip
      ): F[EbClosureVerdict[Rejection, LeiosClosureDefect]] =
        Clock[F].monotonic.flatMap(start =>
          validate(point, ledger).flatTap {
            case EbClosureVerdict.Valid(reapplied, applied) =>
              Clock[F].monotonic.flatMap(end =>
                trace(LeiosKernelEvent.EbValidated(point, reapplied, applied, end - start))
              )
            case EbClosureVerdict.Invalid(_) | EbClosureVerdict.Unreadable(_) => Async[F].unit
          }
        )
      def addVote(vote: Vote): F[AddVoteResult] =
        votes.add(vote)(using fault.contramap(LeiosVotingFault.Votes(_)))

  /** The Dijkstra ledger state of `state`, none in another era. */
  private def dijkstraOf(state: MirrorState): Option[NewEpochState[Dijkstra]] =
    MirrorStep.nesOf(state).flatMap(MirrorDijkstra.inDijkstra)

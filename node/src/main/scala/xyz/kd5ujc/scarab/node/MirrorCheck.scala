// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{
  MirrorExtras,
  ConsensusMirrorEnv,
  StateMirror,
  StateMirrorError
}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.state.Gap
import xyz.kd5ujc.scarab.protocol.Era

/** `follow --mirror-check`: a debug check that the ledger-state mirror holds on the live state.
  *
  * At the first block and at every epoch boundary the state is turned into the mirror and back
  * (`toMirror`, `fromMirror`, [[StateMirror]]); the check holds when the state comes back as the
  * mirror normalises it (the retraction), nothing was left without a place in the mirror (the
  * residue is empty) and the mirror refused nothing. The result is only logged: the check reads the
  * state and never changes it, and no rule reads the mirror.
  */
object MirrorCheck:
  /** The step of the conversion that refused. */
  enum Stage:
    case ToMirror, FromMirror

  /** What the check of one state found. */
  enum Outcome:
    /** The state went to the mirror and came back. `ok` tells whether it came back as it was. */
    case Checked(era: Era, gaps: Set[Gap], residueEmpty: Boolean, retracts: Boolean)

    /** The conversion refused the state. */
    case Refused(era: Era, stage: Stage, error: StateMirrorError)

    /** The conversion failed with an exception (a defect, not a refusal). */
    case Crashed(era: Era, message: String)

    def era: Era

    def ok: Boolean = this match
      case Checked(_, _, residueEmpty, retracts) => residueEmpty && retracts
      case _                                     => false

  /** The state as a comparison sees it: the UTxO is a handle over a RocksDB-backed set, compared by
    * what the mirror may not change (its totals and stake maps) and not entry by entry.
    */
  private def view(s: NodeState) =
    (
      s.copy(ledger = s.ledger.copy(utxo = node.Utxo.empty)),
      s.ledger.utxo.count,
      s.ledger.utxo.coin,
      s.ledger.utxo.stake,
      s.ledger.utxo.ptrStake,
      s.ledger.utxo.stamp,
      s.ledger.utxo.flushed,
      s.ledger.utxo.diff.size
    )

  /** Whether two states are the same as the mirror sees them. */
  def sameState(a: NodeState, b: NodeState): Boolean = view(a) == view(b)

  /** The check of the state `s` after the block `anchor`. Total: refusals are values. */
  def check(env: ConsensusMirrorEnv, anchor: Option[BlockRef], s: NodeState): Outcome =
    val era = StateMirror.eraOf(env, s)
    MirrorRun.run(env)(_.toMirror(anchor, s)) match
      case Left(e) => Outcome.Refused(era, Stage.ToMirror, e)
      case Right(mirrored) =>
        MirrorRun.run(env)(_.fromMirror(mirrored.value)) match
          case Left(e) => Outcome.Refused(era, Stage.FromMirror, e)
          case Right((back, restored)) =>
            val residueEmpty = mirrored.value.extras match
              case MirrorExtras.InShelley(ledger, _) => ledger.residue.isEmpty
              case _: MirrorExtras.InByron           => true
            val retracts =
              back == anchor && sameState(restored, StateMirror.normalised(env, s))
            Outcome.Checked(era, mirrored.gaps, residueEmpty, retracts)

  /** The hook the follower runs after each block when the check is on: it checks the state at the
    * first block and at the first block of every epoch, and reports each result to `report` with
    * the block, the epoch and the milliseconds the check took. Nothing it does can fail the
    * follower: an exception is a [[Outcome.Crashed]].
    */
  final class Checker[F[_]: Sync] private (
      env: F[ConsensusMirrorEnv],
      lastEpoch: Ref[F, Option[BigInt]],
      report: (Header, BigInt, Outcome, Long) => F[Unit]
  ):
    def afterBlock(header: Header, s: NodeState): F[Unit] =
      val epoch = s.ledger.epoch
      lastEpoch.getAndSet(Some(epoch)).map(!_.contains(epoch)).flatMap { due =>
        if !due then Sync[F].unit
        else
          for
            mirrorEnv <- env
            started <- Sync[F].delay(System.nanoTime())
            anchor = Some(BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary))
            outcome <- Sync[F]
              .blocking(check(mirrorEnv, anchor, s))
              .handleError(t =>
                Outcome.Crashed(
                  StateMirror.eraOf(mirrorEnv, s),
                  Option(t.getMessage).getOrElse(t.toString)
                )
              )
            took <- Sync[F].delay((System.nanoTime() - started) / 1000000)
            _ <- report(header, epoch, outcome, took)
          yield ()
      }

  object Checker:
    def create[F[_]: Sync](
        env: F[ConsensusMirrorEnv],
        report: (Header, BigInt, Outcome, Long) => F[Unit]
    ): F[Checker[F]] =
      Ref.of[F, Option[BigInt]](None).map(new Checker[F](env, _, report))

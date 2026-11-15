// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.network.selection.{Churn, ChurnMode, GovernorState}

/** The reference's counter/target STM transaction. Publishing counters and changing a proposal
  * share one cell, so a churn step samples its before-counter at its target-write boundary. The
  * churn mode is in the same cell: a proposal and the mode it is computed with are written together
  * (`updateTargets`, `Churn.hs` 241-251), and the governor reads them together
  * ([[PeerGovernor.ChurnWritten]]).
  */
private[node] final class ChurnTargets[F[_]: Concurrent] private (
    state: SignallingRef[F, ChurnTargets.Snapshot]
):
  def counters: F[GovernorState.Counters] = state.get.map(_.counters)
  def requested: F[Churn.Proposal] = state.get.map(_.requested)
  def publish(counters: GovernorState.Counters): F[Unit] = state.update(_.copy(counters = counters))

  def change(generation: BigInt, mode: ChurnMode)(
      f: Churn.Proposal => Churn.Proposal
  ): F[(GovernorState.Counters, Churn.Proposal)] =
    state.modify { before =>
      val proposal = f(before.requested)
      (
        before.copy(requested = proposal, generation = generation, mode = mode),
        (before.counters, proposal)
      )
    }

  /** The churn mode alone, as a round begins (`Churn.hs` 565). */
  def setMode(mode: ChurnMode): F[Unit] = state.update(_.copy(mode = mode))

  def written: F[PeerGovernor.ChurnWritten] = state.get.map { current =>
    PeerGovernor.ChurnWritten(
      current.mode,
      current.requested.validated.toOption.map(PeerGovernor.RequestedTargets(_, current.generation))
    )
  }

  def awaitCounters(predicate: GovernorState.Counters => Boolean): F[GovernorState.Counters] =
    state.discrete
      .map(_.counters)
      .filter(predicate)
      .take(1)
      .compile
      .last
      .flatMap(_.fold(Concurrent[F].never[GovernorState.Counters])(Concurrent[F].pure))

private[node] object ChurnTargets:
  private final case class Snapshot(
      counters: GovernorState.Counters,
      requested: Churn.Proposal,
      generation: BigInt,
      mode: ChurnMode
  )

  def create[F[_]: Concurrent](
      counters: GovernorState.Counters,
      requested: Churn.Proposal
  ): F[ChurnTargets[F]] =
    SignallingRef[F, Snapshot](Snapshot(counters, requested, BigInt(0), ChurnMode.Initial))
      .map(new ChurnTargets(_))

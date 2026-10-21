// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.network.selection.{Churn, GovernorState}

/** The reference's counter/target STM transaction. Publishing counters and changing a proposal
  * share one cell, so a churn step samples its before-counter at its target-write boundary.
  */
private[node] final class ChurnTargets[F[_]: Concurrent] private (
    state: SignallingRef[F, ChurnTargets.Snapshot]
):
  def counters: F[GovernorState.Counters] = state.get.map(_.counters)
  def requested: F[Churn.Proposal] = state.get.map(_.requested)
  def publish(counters: GovernorState.Counters): F[Unit] = state.update(_.copy(counters = counters))

  def change(generation: BigInt)(
      f: Churn.Proposal => Churn.Proposal
  ): F[(GovernorState.Counters, Churn.Proposal)] =
    state.modify { before =>
      val proposal = f(before.requested)
      (before.copy(requested = proposal, generation = generation), (before.counters, proposal))
    }

  def accepted: F[Option[PeerGovernor.RequestedTargets]] = state.get.map { current =>
    current.requested.validated.toOption.map(PeerGovernor.RequestedTargets(_, current.generation))
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
      generation: BigInt
  )

  def create[F[_]: Concurrent](
      counters: GovernorState.Counters,
      requested: Churn.Proposal
  ): F[ChurnTargets[F]] =
    SignallingRef[F, Snapshot](Snapshot(counters, requested, BigInt(0))).map(new ChurnTargets(_))

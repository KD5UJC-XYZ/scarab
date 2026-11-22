// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef

/** `awaitImmTipCanForecastNow` (`LeiosDemoLogic.hs` 2012-2045 at ouroboros-consensus 2416493b) as
  * one signal the node's LeiosNotify clients share: whether the ledger view forecast from the
  * immutable tip reaches the current slot, so that a fresh reply can be judged. The reference's
  * transaction retries until it does, reading the published slot (`CurrentSlotUnknown` retries) and
  * the immutable tip's ledger; here the signal is read again each time either moves.
  */
final class LeiosForecastGate[F[_]] private (open: SignallingRef[F, Boolean])(using F: Async[F]):
  /** Wait until the immutable tip can forecast the current slot. */
  def ready: F[Unit] = open.waitUntil(identity)

  /** The gate kept for the life of the resource from the slot `time` publishes and the immutable
    * tip's slots, `immutable`; `forecasts` says whether the immutable tip as it stands forecasts to
    * a slot. An exception in the fiber that keeps it goes to `defect`, which stops the node.
    */
  def kept(
      time: BlockchainTime[F],
      immutable: Stream[F, BigInt],
      forecasts: BigInt => F[Boolean],
      defect: Throwable => F[Unit]
  ): Resource[F, Unit] =
    val read: F[Unit] =
      time.currentSlot.flatMap(_.fold(F.pure(false))(forecasts)).flatMap(open.set)
    time.published.void
      .merge(immutable.void)
      .evalMap(_ => read)
      .compile
      .drain
      .handleErrorWith(defect)
      .background
      .void

object LeiosForecastGate:
  /** The gate, closed until it is first kept ([[kept]]). */
  def create[F[_]: Async]: F[LeiosForecastGate[F]] =
    SignallingRef.of[F, Boolean](false).map(new LeiosForecastGate(_))

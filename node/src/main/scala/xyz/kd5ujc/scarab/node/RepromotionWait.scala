// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Temporal
import scala.concurrent.duration.FiniteDuration

/** Interpret a normal mini-protocol return's delay without changing its policy value. */
trait RepromotionWait[F[_]]:
  def delay(duration: FiniteDuration): F[Unit]

object RepromotionWait:
  given [F[_]: Temporal]: RepromotionWait[F] with
    def delay(duration: FiniteDuration): F[Unit] = Temporal[F].sleep(duration)

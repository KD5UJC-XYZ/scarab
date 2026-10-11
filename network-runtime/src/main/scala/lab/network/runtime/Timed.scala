// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import scala.concurrent.duration.*

private[runtime] object Timed:
  /** Run `fa` under a per-state deadline; expiry raises [[NetworkError.Timeout]]. */
  def within[F[_]: Temporal, A](limit: FiniteDuration, protocol: Int, state: String)(fa: F[A])(using
      Raise[F, NetworkError]
  ): F[A] =
    fa.timeoutTo(limit, NetworkError.Timeout(protocol, state).raise[F, A])

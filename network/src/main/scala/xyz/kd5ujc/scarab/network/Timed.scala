// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import scala.concurrent.duration.*

private[network] object Timed:
  /** Run `fa` under a per-state deadline; expiry raises [[NetworkError.Timeout]]. */
  def within[F[_]: Temporal, A](limit: FiniteDuration, protocol: Int, state: String)(fa: F[A])(using
      Raise[F, NetworkError]
  ): F[A] =
    fa.timeoutTo(limit, NetworkError.Timeout(protocol, state).raise[F, A])

  def within[F[_]: Temporal, A](limit: ProtocolDeadline, protocol: Int, state: String)(fa: F[A])(
      using Raise[F, NetworkError]
  ): F[A] = limit match
    case ProtocolDeadline.After(delay) => within(delay, protocol, state)(fa)
    case ProtocolDeadline.Unbounded    => fa

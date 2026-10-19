// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Resource, Temporal}
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{Compute, FollowerEvent, FollowerEvents, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{NodeError, PeerError}
import scala.concurrent.duration.*

/** A test driver for [[Follower.session]]: runs the sessions of one scripted peer one after the
  * other, starting another after each [[PeerError]], up to `retry.maxAttempts` consecutive failures
  * (a session that changed the tip resets the count). The node's own driver is the peer governor
  * ([[PeerGovernor]]); the follower suites use this to script a peer without a network.
  */
object SessionDriver:
  final case class Retry(maxAttempts: Int, delay: FiniteDuration)

  def supervise[F[_]: Temporal, S](
      follower: Follower[F, S],
      connect: Resource[F, Peer[F]],
      events: FollowerEvents[F],
      retry: Retry,
      compute: Option[Compute[F]] = None
  )(using chain: Stateful[F, VolatileChain[S]], handle: Handle[F, NodeError]): F[Unit] =
    def loop(attempt: Int): F[Unit] =
      chain.get.map(_.tip).flatMap { before =>
        handle
          .attempt(
            connect
              .use(peer => compute.fold(follower.session(peer))(_.within(follower.session(peer))))
          )
          .flatMap {
            case Right(_) => Temporal[F].unit
            case Left(error: PeerError) =>
              chain.get.map(_.tip).flatMap { after =>
                val next = if after != before then 1 else attempt + 1
                if next > retry.maxAttempts then handle.raise[NodeError, Unit](error)
                else
                  events.emit(FollowerEvent.Reconnecting(error, next)) *> Temporal[F].sleep(
                    retry.delay
                  ) *> loop(next)
              }
            case Left(other) => handle.raise(other)
          }
      }
    loop(1)

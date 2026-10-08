// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Resource}
import cats.syntax.all.*

/** Explicit short-range client. The existing BF engine remains the only application driver. A
  * closed routed bearer adds one initiator KeepAlive worker, never a second raw reader.
  */
final class KeepAliveBlockFetchSession[F[_]: Async] private (
    session: BlockFetchSession[F],
    routed: KeepAliveTransport[F]
):
  def negotiate(data: Handshake.Data): F[Unit] = session.negotiate(data) *> routed.activate
  def request(range: CardanoBlockFetch.InclusiveRange): F[Unit] = session.request(range)
  def receive: F[BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]] = session.receive
  // Freeze immediately at BatchDone, before the caller verifies byte pins.
  def freeze: F[Unit] = routed.freeze
  def finish: F[Unit] = routed.freeze *> routed.finishBounded(
    routed.requireBlockFetchDrained *> routed.finishKeepAlive *> session.finish *> routed.barrier
  )
  def bounded[A](work: F[A]): F[A] = session.bounded(work)
  def close: F[Unit] = routed.close

object KeepAliveBlockFetchSession:
  final class OutgoingBudgetExceeded(message: String) extends IllegalStateException(message)
  val Profile = "ntn14-blockfetch-keepalive-short-v1"
  type Policy = KeepAliveTransport.Policy
  val Policy = KeepAliveTransport.Policy
  def resource[F[_]: Async](
      transport: ByteTransport[F],
      config: BlockFetchSession.Config = BlockFetchSession.Config(),
      policy: Policy = Policy()
  ): Resource[F, KeepAliveBlockFetchSession[F]] =
    resourceWithCookies(
      transport,
      config,
      policy,
      Async[F].delay(
        KeepAlive.Cookie
          .from(java.util.concurrent.ThreadLocalRandom.current().nextInt(65536))
          .toOption
          .get
      )
    )

  private[network] def resourceWithCookies[F[_]: Async](
      transport: ByteTransport[F],
      config: BlockFetchSession.Config,
      policy: Policy,
      cookie: F[KeepAlive.Cookie]
  ): Resource[F, KeepAliveBlockFetchSession[F]] =
    KeepAliveTransport.resource(transport, config, policy, cookie).flatMap { routed =>
      BlockFetchSession
        .resource(routed.blockFetch, config)
        .map(new KeepAliveBlockFetchSession(_, routed))
    }

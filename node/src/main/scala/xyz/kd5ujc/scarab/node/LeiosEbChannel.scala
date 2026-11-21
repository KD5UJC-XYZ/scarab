// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref, Resource, Unique}
import cats.effect.std.Queue
import cats.syntax.all.*
import xyz.kd5ujc.scarab.storage.LeiosEbNotification

/** The store's notifications of what its writes acquired, to each of its subscribers: the
  * reference's notification channel (`subscribeEbNotifications`, `Storage/LeiosDB/API.hs:57`, and
  * `atomically (dupTChan notificationChan)`, `Impl/SQLite.hs:189` at ouroboros-consensus 2416493b).
  * A subscriber has the notifications published after it subscribed, in order, each on a queue of
  * its own that no write waits for, as a duplicated `TChan` is unbounded.
  */
private[node] final class LeiosEbChannel[F[_]] private (
    subscribers: Ref[F, Map[Unique.Token, Queue[F, LeiosEbNotification]]]
)(using F: Async[F]):
  /** Offer `notifications` to every subscriber, in order. */
  def publish(notifications: Vector[LeiosEbNotification]): F[Unit] =
    subscribers.get.flatMap(_.values.toVector.traverse_(q => notifications.traverse_(q.offer)))

  /** A subscription for the life of the resource: the notifications published from now on. */
  def subscribe: Resource[F, Queue[F, LeiosEbNotification]] =
    Resource
      .make(
        (F.unique, Queue.unbounded[F, LeiosEbNotification]).flatMapN((token, queue) =>
          subscribers.update(_.updated(token, queue)).as(token -> queue)
        )
      )((token, _) => subscribers.update(_ - token))
      .map(_._2)

private[node] object LeiosEbChannel:
  def create[F[_]: Async]: F[LeiosEbChannel[F]] =
    Ref
      .of[F, Map[Unique.Token, Queue[F, LeiosEbNotification]]](Map.empty)
      .map(new LeiosEbChannel(_))

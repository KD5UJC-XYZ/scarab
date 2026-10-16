// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.Apply
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, FollowerEvents}
import xyz.kd5ujc.scarab.network.{ConnectionEvent, ConnectionEvents}
import xyz.kd5ujc.scarab.node.{InboundEvent, InboundEvents}
import xyz.kd5ujc.scarab.node.forge.{ForgeEvent, ForgeEvents}
import xyz.kd5ujc.scarab.node.mempool.{MempoolEvent, MempoolEvents}
import xyz.kd5ujc.scarab.storage.{StorageEvent, StorageEvents}

/** An event sink: a trait `S[F]` whose `emit` takes events of type `E` (`FollowerEvents`,
  * `ForgeEvents`, ...). Having the instance lets any two sinks of one trait run side by side
  * ([[alongside]]), so the JSON log and the metrics both listen to the one stream of events and no
  * call site knows about either.
  */
trait EventSink[S[_[_]], E]:
  def emit[F[_]](sink: S[F], event: E): F[Unit]
  def of[F[_]](handle: E => F[Unit]): S[F]

object EventSink:
  given EventSink[FollowerEvents, FollowerEvent] with
    def emit[F[_]](sink: FollowerEvents[F], event: FollowerEvent) = sink.emit(event)
    def of[F[_]](handle: FollowerEvent => F[Unit]) = new FollowerEvents[F]:
      def emit(event: FollowerEvent) = handle(event)

  given EventSink[ForgeEvents, ForgeEvent] with
    def emit[F[_]](sink: ForgeEvents[F], event: ForgeEvent) = sink.emit(event)
    def of[F[_]](handle: ForgeEvent => F[Unit]) = new ForgeEvents[F]:
      def emit(event: ForgeEvent) = handle(event)

  given EventSink[MempoolEvents, MempoolEvent] with
    def emit[F[_]](sink: MempoolEvents[F], event: MempoolEvent) = sink.emit(event)
    def of[F[_]](handle: MempoolEvent => F[Unit]) = new MempoolEvents[F]:
      def emit(event: MempoolEvent) = handle(event)

  given EventSink[StorageEvents, StorageEvent] with
    def emit[F[_]](sink: StorageEvents[F], event: StorageEvent) = sink.emit(event)
    def of[F[_]](handle: StorageEvent => F[Unit]) = new StorageEvents[F]:
      def emit(event: StorageEvent) = handle(event)

  given EventSink[ConnectionEvents, ConnectionEvent] with
    def emit[F[_]](sink: ConnectionEvents[F], event: ConnectionEvent) = sink.emit(event)
    def of[F[_]](handle: ConnectionEvent => F[Unit]) = new ConnectionEvents[F]:
      def emit(event: ConnectionEvent) = handle(event)

  /** The inbound governor reports the remote with the event. */
  given EventSink[InboundEvents, (String, InboundEvent)] with
    def emit[F[_]](sink: InboundEvents[F], event: (String, InboundEvent)) =
      sink.emit(event._1, event._2)
    def of[F[_]](handle: ((String, InboundEvent)) => F[Unit]) = new InboundEvents[F]:
      def emit(remote: String, event: InboundEvent) = handle((remote, event))

  extension [S[_[_]], F[_]](first: S[F])
    /** Both sinks receive every event, `first` before `second`. */
    def alongside[E](second: S[F])(using sink: EventSink[S, E], F: Apply[F]): S[F] =
      sink.of[F](event => sink.emit(first, event) *> sink.emit(second, event))

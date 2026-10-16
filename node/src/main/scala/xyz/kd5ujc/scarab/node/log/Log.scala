// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import cats.{Applicative, Monad}
import cats.mtl.Tell
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, FollowerEvents, SchedulingStats}
import xyz.kd5ujc.scarab.node.InboundEvents
import xyz.kd5ujc.scarab.node.log.Traced.{FollowerLine, Scheduled}
import xyz.kd5ujc.scarab.node.mempool.{MempoolEvent, MempoolEvents}
import xyz.kd5ujc.scarab.node.metrics.EventSink

/** The log, as a capability: cats-mtl's `Tell` of [[Line]]s, the write side of a writer. A `Line`
  * is built only in this package, from an event through its [[Traced]] instance, so what reaches
  * the log is always a typed event with a kind, a namespace and a severity. The interpreter that
  * routes the lines to the sinks is [[LogRouter]]; a test can collect them with any `Tell`, such as
  * a `WriterT`.
  */
type Log[F[_]] = Tell[F, Line]

object Log:
  def apply[F[_]](using log: Log[F]): Log[F] = log

  /** A log that drops every line. */
  def none[F[_]: Applicative]: Log[F] = new Tell[F, Line]:
    def functor = Applicative[F]
    def tell(line: Line): F[Unit] = Applicative[F].unit

  /** Any of the node's event sinks (`StorageEvents`, `ForgeEvents`, `ConnectionEvents`, ...) as the
    * log of their events.
    */
  def sink[S[_[_]], E, F[_]](using sink: EventSink[S, E], traced: Traced[E], log: Log[F]): S[F] =
    sink.of[F](log.emit(_))

  /** The follower's events. With `sched`, the lines that close an epoch carry the scheduling
    * measurements of that epoch (`maxTimerLatenessMs`, `longestStepMs`, ...); `Immutable` (one per
    * block, for the metrics) has no line.
    */
  def follower[F[_]: Monad](sched: Option[SchedulingStats[F]])(using
      log: Log[F]
  ): FollowerEvents[F] =
    case e: (FollowerEvent.EpochValidated | FollowerEvent.ByronEpochValidated |
          FollowerEvent.LedgerEpoch) =>
      sched.traverse(_.window).flatMap(report => log.emit(Scheduled[FollowerLine](e, report)))
    case FollowerEvent.Immutable(_) => Monad[F].unit
    case e: FollowerLine            => log.emit[FollowerLine](e)

  /** The mempool's events; a resynchronisation that dropped nothing (one per block) has no line. */
  def mempool[F[_]: Applicative](using log: Log[F]): MempoolEvents[F] =
    case MempoolEvent.Synced(_, _, 0) => Applicative[F].unit
    case e                            => log.emit(e)

  /** The inbound governor's events, each with the connection's remote address. */
  def inbound[F[_]](using log: Log[F]): InboundEvents[F] =
    (remote, event) => log.emit[NodeEvent](NodeEvent.Inbound(remote, event))

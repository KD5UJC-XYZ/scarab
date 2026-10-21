// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Consensus 82ecba329 ChainSync/Client/HistoricityCheck.hs 137–180. Times are picoseconds relative
  * to system start, including era-aware slot translation from the validated header gate.
  */
object NodeGenesisHistory:
  val DefaultCutoffPicos: BigInt = BigInt(133200) * 1000000000000L
  final case class HeaderTime(point: Point, slotTimePicos: BigInt)
  enum Message:
    case AwaitReply, RollBackward

  def judge(
      message: Message,
      header: HeaderTime,
      phase: Gsm.Phase,
      arrivalTimePicos: BigInt,
      cutoffPicos: BigInt = DefaultCutoffPicos
  ): Either[PeerError.GenesisProblem, Unit] =
    if phase == Gsm.Phase.CaughtUp || arrivalTimePicos - header.slotTimePicos <= cutoffPicos then
      Right(())
    else
      Left(message match
        case Message.AwaitReply =>
          PeerError.GenesisProblem
            .HistoricalAwait(header.point, header.slotTimePicos, arrivalTimePicos, cutoffPicos)
        case Message.RollBackward =>
          PeerError.GenesisProblem
            .HistoricalRollback(header.point, header.slotTimePicos, arrivalTimePicos, cutoffPicos))

  final class Check[F[_]: Monad] private[NodeGenesisHistory] (
      enabled: Boolean,
      phase: F[Gsm.Phase],
      systemTime: F[BigInt],
      cutoffPicos: BigInt
  ):
    def awaitReply(tip: HeaderTime)(using Raise[F, PeerError]): F[Unit] =
      check(Message.AwaitReply, Some(tip))

    /** Native rewind returns the oldest state removed, not the state at the rollback target. A
      * zero-depth rollback has no removed state and performs no historicity check.
      */
    def rollBackward(oldestRewound: Option[HeaderTime])(using Raise[F, PeerError]): F[Unit] =
      check(Message.RollBackward, oldestRewound)
    private def check(message: Message, header: Option[HeaderTime])(using
        raise: Raise[F, PeerError]
    ): F[Unit] =
      if !enabled then Monad[F].unit
      else
        header.traverse_ { time =>
          phase.flatMap {
            case Gsm.Phase.CaughtUp => Monad[F].unit
            case current =>
              systemTime.flatMap { arrival =>
                judge(message, time, current, arrival, cutoffPicos)
                  .fold(problem => raise.raise(PeerError.Genesis(problem)), _ => Monad[F].unit)
              }
          }
        }

  def runtime[F[_]: Monad](
      enabled: Boolean,
      phase: F[Gsm.Phase],
      systemTime: F[BigInt],
      cutoffPicos: BigInt = DefaultCutoffPicos
  ): Check[F] = new Check(enabled, phase, systemTime, cutoffPicos)

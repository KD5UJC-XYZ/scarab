// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import cats.effect.syntax.all.*
import lab.network.ChainSync
import scala.concurrent.duration.*

/** Internal ephemeral driver. The caller exclusively owns the runtime and pull source during run.
  * Compaction is an availability decision, not finality. No persistence or network adapter exists.
  */
private[lab] object EphemeralStreaming:
  enum Event:
    case Block(value: SequenceInput.Block)
    case Rollback(point: ChainSync.Point)
  final case class Limits(
      retained: Int = 8,
      maxEvents: Long = 256,
      maxBlocks: Long = 128,
      maxBytes: Long = 64L * 1024 * 1024,
      duration: FiniteDuration = 60.seconds
  ):
    def valid: Boolean = retained >= 1 && retained <= 8 && maxEvents > 0 && maxEvents <= 4096 &&
      maxBlocks > 0 && maxBlocks <= maxEvents && maxBytes > 0 &&
      maxBytes <= 256L * 1024 * 1024 && duration > Duration.Zero && duration <= 120.seconds
  final case class Counters(
      events: Long = 0,
      blocks: Long = 0,
      bytes: Long = 0,
      acceptedBlocks: Long = 0,
      rollbacks: Long = 0,
      compactions: Long = 0
  )
  enum Stop:
    case End, EventLimit, BlockLimit, ByteLimit, Deadline
    case Rejected(failure: CoherentSequence.Failure)
  final case class Report(
      snapshot: CoherentSequence.Snapshot,
      counters: Counters,
      elapsed: FiniteDuration,
      stop: Stop
  )

  /** Limits count delivered attempts, including invalid blocks and rollback requests. One event may
    * be pulled to discover a byte/block limit, but is never applied if it exceeds that limit. The
    * final snapshot reports any prior compaction even when the next block is rejected. External
    * cancellation propagates; a short masked publication keeps accounting coherent. The elapsed
    * limit is cooperative: synchronous work can finish after it, but an overdue delivered event or
    * prepared candidate is not admitted. This is not a hard wall-clock bound. Byte limits count
    * logical input payload, not aggregate JVM heap consumption.
    */
  def run[F[_]: Async](
      runtime: CoherentDriver[F],
      limits: Limits
  )(next: F[Option[Event]]): F[Report] =
    val F = Async[F]
    if runtime == null || limits == null || !limits.valid || limits.retained > runtime.maxBlocks
    then F.raiseError(new IllegalArgumentException("bounded streaming runtime/limits required"))
    else
      for
        started <- F.monotonic
        counts <- Ref.of[F, Counters](Counters())
        stop <-
          def expired: F[Boolean] = F.monotonic.map(now => now - started >= limits.duration)
          def compact(
              s: CoherentSequence.Snapshot
          ): F[CoherentSequence.Result[CoherentSequence.Snapshot]] =
            if s.state.acquisition.size < limits.retained then F.pure(Right(s))
            else
              // Candidates are tip-first; keep at most retained-1 before the incoming block.
              val through = s.state.acquisition.candidates(limits.retained - 1)
              F.uncancelable { _ =>
                runtime.advanceAnchor(s.fence, through).flatTap {
                  case Right(_) => counts.update(c => c.copy(compactions = c.compactions + 1))
                  case Left(_)  => F.unit
                }
              }
          def prepare(
              s: CoherentSequence.Snapshot,
              b: SequenceInput.Block
          ): F[CoherentSequence.Result[CoherentSequence.Candidate]] =
            if s.state.syntheticRewards.isEmpty then runtime.prepare(b)
            else if s.state.stake.exists(st => b.header.slot / st.context.epochLength == st.epoch)
            then runtime.prepareSyntheticBlock(s.fence, b)
            else
              runtime.prepareSyntheticSuccessor(s.fence, b.header.hash, b.header.slot).flatMap {
                case Left(error)    => F.pure(Left(error))
                case Right(preview) => runtime.prepareSyntheticSuccessorBlock(s.fence, preview, b)
              }
          def applyEvent(event: Event): F[Option[Stop]] = event match
            case Event.Rollback(point) =>
              runtime.snapshot.flatMap { s =>
                F.uncancelable { _ =>
                  runtime.rollbackTo(s.fence, point).flatMap {
                    case Left(error) => F.pure(Some(Stop.Rejected(error)))
                    case Right(_) =>
                      counts.update(c => c.copy(rollbacks = c.rollbacks + 1)).as(None)
                  }
                }
              }
            case Event.Block(block) =>
              runtime.snapshot.flatMap(compact).flatMap {
                case Left(error) => F.pure(Some(Stop.Rejected(error)))
                case Right(s) =>
                  prepare(s, block).flatMap {
                    case Left(error) => F.pure(Some(Stop.Rejected(error)))
                    case Right(candidate) =>
                      F.uncancelable { _ =>
                        expired.flatMap { overdue =>
                          if overdue then F.pure(Some(Stop.Deadline))
                          else
                            runtime.publish(candidate).flatMap {
                              case Left(error) => F.pure(Some(Stop.Rejected(error)))
                              case Right(_) =>
                                counts
                                  .update(c => c.copy(acceptedBlocks = c.acceptedBlocks + 1))
                                  .as(None)
                            }
                        }
                      }
                  }
              }
          def loop: F[Stop] = F.defer {
            counts.get.flatMap { before =>
              if before.events >= limits.maxEvents then F.pure(Stop.EventLimit)
              else
                next.flatMap {
                  case None => F.pure(Stop.End)
                  case Some(event) =>
                    val block = event match
                      case Event.Block(_)    => 1L
                      case Event.Rollback(_) => 0L
                    val bytes = event match
                      case Event.Block(b) =>
                        b.transactionMemos.foldLeft(
                          b.original.envelope.size.toLong + b.original.block.size
                        ) { (n, memo) =>
                          Math.addExact(n, memo.size.toLong)
                        }
                      case Event.Rollback(_) => 64L
                    // Counters never decrease, including when the event is refused.
                    val after = before.copy(
                      events = before.events + 1,
                      blocks = before.blocks + block,
                      bytes = Math.addExact(before.bytes, bytes)
                    )
                    counts.set(after) *> expired.flatMap { overdue =>
                      if overdue then F.pure(Stop.Deadline)
                      else if after.blocks > limits.maxBlocks then F.pure(Stop.BlockLimit)
                      else if after.bytes > limits.maxBytes then F.pure(Stop.ByteLimit)
                      else
                        applyEvent(event).flatMap {
                          case Some(reason) => F.pure(reason)
                          case None         => F.cede *> loop
                        }
                    }
                }
            }
          }
          loop.timeoutTo(limits.duration, F.pure(Stop.Deadline))
        ended <- F.monotonic
        current <- runtime.snapshot
        totals <- counts.get
      yield Report(current, totals, ended - started, stop)

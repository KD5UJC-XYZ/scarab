// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.network.ChainSync
import scala.concurrent.duration.*

/** Bounded research network pull adapter. No preloaded block list, reconnect, or public admission.
  */
private[lab] object PlutusSameEpochFollow:
  type Observation = NetworkPublicationObservation
  private val Observation = NetworkPublicationObservation
  final case class Outcome(
      snapshot: CoherentSequence.Snapshot,
      driver: Option[EphemeralStreaming.Report],
      reason: String,
      inclusionReached: Boolean,
      observations: Vector[Observation],
      networkEvents: Long,
      networkBytes: Long,
      peerOpens: Int,
      peerCloses: Int,
      initialIntersectionConfirmations: Int
  )
  private def networkPoint(p: Point): ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.from(p.slot).toOption.get, p.hash)
  private def sha(b: Bytes): Bytes = Bytes.fromArray(
    java.security.MessageDigest.getInstance("SHA-256").digest(b.toArray)
  )

  /** record fires after fetch, and again only after verified publication. Times are monotonic
    * offsets from this invocation; applied is the first post-publication observation, not an
    * internal publication instant. The second observation has applied=Some; first has None.
    * Resource allocation/intersection share the same deadline as network follow. External
    * cancellation propagates; peer release is guaranteed by Resource.
    */
  /** Additional completion condition is observed only after a verified network publication. */
  def runWhen[F[_]: Async](
      runtime: CoherentDriver[F],
      peer: Resource[F, BoundedChainFollower.Peer[F]],
      initial: Point,
      epochLength: BigInt,
      limits: EphemeralStreaming.Limits
  )(ready: F[Boolean])(record: Observation => F[Unit]): F[Outcome] =
    run(runtime, peer, initial, epochLength, limits, "inclusionApplied")(ready)(record)

  /** Service completion is a published-block budget, never an expected transaction. */
  def runUntil[F[_]: Async](
      runtime: CoherentDriver[F],
      peer: Resource[F, BoundedChainFollower.Peer[F]],
      initial: Point,
      epochLength: BigInt,
      limits: EphemeralStreaming.Limits
  )(budgetReached: F[Boolean])(record: Observation => F[Unit]): F[Outcome] =
    run(runtime, peer, initial, epochLength, limits, "blockLimit")(budgetReached)(record)

  private final class EpochRefused
      extends IllegalArgumentException(
        "announcement does not extend applied fullpoint within initial epoch"
      )

  private def run[F[_]: Async](
      runtime: CoherentDriver[F],
      peer: Resource[F, BoundedChainFollower.Peer[F]],
      initial: Point,
      epochLength: BigInt,
      limits: EphemeralStreaming.Limits,
      completionReason: String
  )(ready: F[Boolean])(record: Observation => F[Unit]): F[Outcome] =
    val F = Async[F]
    def invalid(message: String): F[Unit] = F.raiseError(new IllegalArgumentException(message))
    def checked[A](e: Either[?, A]): F[A] =
      F.fromEither(e.leftMap(x => new IllegalArgumentException(x.toString)))
    for
      _ <- F.raiseUnless(
        runtime != null && initial != null && initial.hash.size == 32 && initial.slot >= 0 &&
          initial.slot <= BigInt("18446744073709551615") && initial.blockNo >= 0 &&
          epochLength > 0 && initial.slot < epochLength && limits != null && limits.valid && limits.retained == 8 &&
          limits.maxEvents <= 4096 && limits.maxBlocks <= 128
      )(new IllegalArgumentException("bounded epoch-zero initial state and limits required"))
      before <- runtime.snapshot
      _ <- F.raiseUnless(
        before.state.certificates.state.tip == initial &&
          before.state.acquisition.tip == networkPoint(initial) &&
          before.state.ledger.environment.epoch == 0 &&
          before.state.syntheticBoundary.isEmpty && before.state.syntheticRewards.isEmpty &&
          before.state.stake.nonEmpty && before.state.ledger.environment.plutus.nonEmpty
      )(new IllegalArgumentException("runtime does not match initial same-epoch Plutus state"))
      started <- F.monotonic
      observations <- Ref.of[F, Vector[Observation]](Vector.empty)
      events <- Ref.of[F, Long](0L)
      bytes <- Ref.of[F, Long](0L)
      opens <- Ref.of[F, Int](0)
      closes <- Ref.of[F, Int](0)
      reached <- Ref.of[F, Boolean](false)
      initialRollbackAllowed <- Ref.of[F, Boolean](true)
      initialConfirmations <- Ref.of[F, Int](0)
      result <- {
        def elapsed = F.monotonic.map(_ - started)
        def charge(n: Long): F[Unit] = bytes
          .modify { old =>
            if n > limits.maxBytes - old then (old, false) else (old + n, true)
          }
          .flatMap(ok => F.raiseUnless(ok)(new IllegalArgumentException("network byte limit")))
        def observeApplication: F[Unit] = (observations.get, runtime.snapshot).tupled.flatMap {
          (all, snapshot) =>
            all.lastOption.filter(_.applied.isEmpty) match
              case None => F.unit
              case Some(last)
                  if snapshot.state.certificates.state.tip == last.announced &&
                    snapshot.state.acquisition.tip == networkPoint(last.announced) =>
                elapsed.flatMap { now =>
                  val updated = last.copy(applied = Some(now))
                  observations.update(xs => xs.updated(xs.size - 1, updated)) *> record(updated)
                }
              case Some(_) => F.unit
        }
        val owned = peer.evalTap(_ => opens.update(_ + 1)).onFinalize(closes.update(_ + 1))
        val session = owned.use { p =>
          def next: F[Option[EphemeralStreaming.Event]] = F.defer {
            observeApplication *> (runtime.snapshot, ready).tupled.flatMap {
              (snapshot, completed) =>
                val state = snapshot.state
                if completed && state.ledger.environment.epoch == 0 &&
                  state.syntheticBoundary.isEmpty &&
                  state.certificates.state.tip.slot / epochLength == 0
                then
                  observations.get.flatMap { xs =>
                    if xs.lastOption.exists(o =>
                        o.applied.nonEmpty && o.announced == state.certificates.state.tip
                      )
                    then reached.set(true).as(None)
                    else invalid("inclusion has no published network observation").as(None)
                  }
                else
                  events
                    .modify(n => if n < limits.maxEvents then (n + 1, true) else (n, false))
                    .flatMap(ok =>
                      F.raiseUnless(ok)(new IllegalArgumentException("network event limit"))
                    ) *>
                    p.next.flatMap {
                      case BoundedChainFollower.Event.Await           => F.cede *> next
                      case BoundedChainFollower.Event.Backward(point) =>
                        // A reference peer may confirm its selected intersection with the first
                        // RollBackward. This exact, once-only no-op changes no runtime state.
                        initialRollbackAllowed.getAndSet(false).flatMap { allowed =>
                          if allowed && point == networkPoint(initial) &&
                            state.certificates.state.tip == initial &&
                            state.acquisition.tip == networkPoint(initial)
                          then initialConfirmations.update(_ + 1) *> F.cede *> next
                          else
                            val received = point match
                              case ChainSync.Point.Origin => "origin"
                              case ChainSync.Point.Block(slot, hash) =>
                                s"slot=${slot.value},hash=${hash.hex.take(64)}"
                            val applied = state.certificates.state.tip
                            invalid(
                              (s"rollback unsupported in monotonic live profile; received=($received); " +
                                s"applied=(slot=${applied.slot},blockNo=${applied.blockNo},hash=${applied.hash.hex.take(64)})")
                                .take(512)
                            ).as(None)
                        }
                      case BoundedChainFollower.Event.Forward(envelope) =>
                        for
                          _ <- initialRollbackAllowed.set(false)
                          arrival <- elapsed
                          _ <- F.raiseUnless(envelope.size > 0 && envelope.size <= 65535)(
                            new IllegalArgumentException("header envelope bound")
                          )
                          _ <- charge(envelope.size.toLong)
                          header <- checked(ReferenceCaptureCommand.header(envelope))
                          previous = state.certificates.state.tip
                          _ <- F.raiseWhen(header.slot >= epochLength)(new EpochRefused)
                          _ <- F.raiseUnless(
                            header.parent == previous.hash && header.slot > previous.slot &&
                              header.blockNo == previous.blockNo + 1 && header.slot < epochLength
                          )(
                            new IllegalArgumentException(
                              "announcement does not extend applied fullpoint within initial epoch"
                            )
                          )
                          announced = Point(header.hash, header.slot, header.blockNo)
                          raw <- p.fetch(networkPoint(announced))
                          fetched <- elapsed
                          _ <- F.raiseUnless(raw.size > 0 && raw.size <= 1048576)(
                            new IllegalArgumentException("block size bound")
                          )
                          _ <- charge(raw.size.toLong)
                          _ <- checked(
                            ReferenceCaptureCommand.compare(header, raw, state.acquisition.tip)
                          )
                          original = BoundedChainFollower.Original(envelope, raw)
                          block <- checked(SequenceInput.block(original))
                          all <- observations.get
                          _ <- F.raiseUnless(all.size < limits.maxBlocks)(
                            new IllegalArgumentException("network block limit")
                          )
                          observation = Observation(
                            all.size,
                            announced,
                            original,
                            sha(envelope),
                            sha(raw),
                            arrival,
                            fetched,
                            None
                          )
                          _ <- observations.update(_ :+ observation)
                          _ <- record(observation)
                        yield Some(EphemeralStreaming.Event.Block(block))
                    }
            }
          }
          p.intersect(Vector(networkPoint(initial))).flatMap { found =>
            F.raiseUnless(found == networkPoint(initial))(
              new IllegalArgumentException("foreign intersection")
            ) *> elapsed.flatMap { spent =>
              val remaining = limits.duration - spent
              if remaining <= Duration.Zero then
                F.raiseError(new java.util.concurrent.TimeoutException("deadline"))
              else
                EphemeralStreaming
                  .run(runtime, limits.copy(duration = remaining))(next)
                  .flatTap(_ => observeApplication)
            }
          }
        }
        session.timeout(limits.duration).attempt
      }
      snapshot <- runtime.snapshot
      all <- observations.get
      n <- events.get
      b <- bytes.get
      o <- opens.get
      c <- closes.get
      hit <- reached.get
      confirmations <- initialConfirmations.get
      report = result.toOption
      reason = result.fold(
        e =>
          if e.isInstanceOf[java.util.concurrent.TimeoutException] then "deadline"
          else if completionReason == "blockLimit" && e.isInstanceOf[EpochRefused] then
            "epochBoundaryRefused"
          else s"failure:${e.getMessage}",
        r =>
          if hit && r.stop == EphemeralStreaming.Stop.End then completionReason
          else r.stop.toString
      )
    yield Outcome(
      snapshot,
      report,
      reason,
      hit && report.exists(_.stop == EphemeralStreaming.Stop.End),
      all,
      n,
      b,
      o,
      c,
      confirmations
    )

// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.{Async, Clock, Ref, Resource}
import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.node.{Anchor, Block, BlockRef, BlockRules, ChainError, StorageError}
import scala.concurrent.duration.*

/** Observable storage milestones, for the JSON log. */
enum StorageEvent:
  case ImmutableOpened(report: ImmutableStore.OpenReport)
  case SnapshotSkipped(name: String, error: StorageError)
  case SnapshotRestored(name: String, anchor: Option[BlockRef])
  case SnapshotWritten(name: String, anchor: Option[BlockRef], bytes: Long, millis: Long)
  case SnapshotFailed(reason: String)
  case ReplayStarted(from: Option[BlockRef], tip: Option[BlockRef])
  case ReplayProgress(at: BlockRef, replayed: Long, millis: Long)
  case ReplayFinished(at: Option[BlockRef], replayed: Long, millis: Long)

trait StorageEvents[F[_]]:
  def emit(event: StorageEvent): F[Unit]

/** What the follower tells the storage layer; the follower itself stays storage-agnostic. */
trait ChainPersistence[F[_], S]:
  /** A block was applied. `immutable` are the blocks that just aged past `k` (oldest first) and
    * `anchor` the chain's new immutable boundary.
    */
  def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S]): F[Unit]

  /** The volatile chain now ends at `to`. */
  def rolledBack(to: ChainSync.Point): F[Unit]

object ChainPersistence:
  def none[F[_]: cats.Applicative, S]: ChainPersistence[F, S] = new ChainPersistence[F, S]:
    def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S]) =
      cats.Applicative[F].unit
    def rolledBack(to: ChainSync.Point) = cats.Applicative[F].unit

/** The ChainDB: an [[ImmutableStore]] and [[LedgerSnapshots]] under one directory. The volatile
  * part (the last `k` blocks) stays in memory and is re-fetched from the peer after a restart.
  *
  * Layout: `<dir>/immutable/` and `<dir>/snapshots/`.
  */
object ChainStorage:
  final case class Config(
      /** A snapshot is taken once the immutable tip is this many slots past the previous one. */
      snapshotEverySlots: BigInt,
      keepSnapshots: Int = 3,
      chunkSlots: Long = 21600,
      byronEpochSlots: BigInt = 21600,
      /** Replay progress is reported every this many blocks. */
      progressEvery: Int = 20000
  )

  /** The node state recovered from disk, ready to anchor a [[lab.node.VolatileChain]]. */
  final case class Recovered[F[_], S](
      anchor: Option[BlockRef],
      state: S,
      persistence: ChainPersistence[F, S],
      store: ImmutableStore[F]
  )

  def open[F[_]: Async, S](
      dir: Path,
      config: Config,
      rules: BlockRules[F, S],
      initial: S,
      events: StorageEvents[F]
  )(using
      Codec[StateSnapshot[S]],
      Raise[F, StorageError],
      Raise[F, ChainError]
  ): Resource[F, Recovered[F, S]] =
    val F = Async[F]
    for
      opened <- FileImmutableStore.open[F](dir / "immutable", config.chunkSlots)
      (store, report) = opened
      _ <- Resource.eval(events.emit(StorageEvent.ImmutableOpened(report)))
      snapshots <- Resource.eval(
        FileLedgerSnapshots.open[F, S](dir / "snapshots", config.keepSnapshots)
      )
      supervisor <- Supervisor[F](await = false)
      restored <- Resource.eval(
        snapshots.restore(anchor =>
          anchor.fold(F.pure(true))(a => store.get(a.point).map(_.isDefined))
        )
      )
      _ <- Resource.eval(
        restored.skipped.traverse_(s => events.emit(StorageEvent.SnapshotSkipped(s.name, s.error)))
      )
      start = restored.snapshot.fold((Option.empty[BlockRef], initial))((_, s) =>
        (s.anchor, s.state)
      )
      _ <- Resource.eval(
        restored.snapshot.traverse_((name, s) =>
          events.emit(StorageEvent.SnapshotRestored(name, s.anchor))
        )
      )
      pending <- Resource.eval(Ref.of[F, Vector[(BlockRef, Bytes)]](Vector.empty))
      lastSlot <- Resource.eval(Ref.of[F, BigInt](start._1.fold(BigInt(0))(_.slot)))
      busy <- Resource.eval(Ref.of[F, Boolean](false))
      persistence = new Persistence[F, S](
        store,
        snapshots,
        supervisor,
        events,
        config,
        pending,
        lastSlot,
        busy
      )
      end <- Resource.eval(store.tip)
      _ <- Resource.eval(events.emit(StorageEvent.ReplayStarted(start._1, end)))
      began <- Resource.eval(Clock[F].monotonic)
      replayed <- Resource.eval(
        replay(store, rules, persistence, config, events, start._1, start._2, began)
      )
    yield Recovered(replayed._1, replayed._2, persistence, store)

  /** Re-apply the stored blocks after `from`, with no network. */
  private def replay[F[_]: Async, S](
      store: ImmutableStore[F],
      rules: BlockRules[F, S],
      persistence: Persistence[F, S],
      config: Config,
      events: StorageEvents[F],
      from: Option[BlockRef],
      state: S,
      began: FiniteDuration
  )(using chainRaise: Raise[F, ChainError]): F[(Option[BlockRef], S)] =
    val F = Async[F]
    final case class Acc(anchor: Option[BlockRef], state: S, count: Long)
    def elapsed: F[Long] = Clock[F].monotonic.map(now => (now - began).toMillis)
    store
      .stream(from.fold[ChainSync.Point](ChainSync.Point.Origin)(_.point))
      .evalScan(Acc(from, state, 0L)) { (acc, stored) =>
        for
          block <- Block.decode(stored.raw, config.byronEpochSlots) match
            case Right(b) => F.pure(b)
            case Left(e)  => e.raise[F, Block]
          next <- rules(acc.state, block.header, stored.raw)
          _ <- persistence.advanced(stored.ref, next)
          count = acc.count + 1
          _ <- F.whenA(count % config.progressEvery == 0)(
            elapsed.flatMap(ms => events.emit(StorageEvent.ReplayProgress(stored.ref, count, ms)))
          )
        yield Acc(Some(stored.ref), next, count)
      }
      .compile
      .last
      .flatMap { last =>
        val acc = last.getOrElse(Acc(from, state, 0L))
        elapsed
          .flatMap(ms => events.emit(StorageEvent.ReplayFinished(acc.anchor, acc.count, ms)))
          .as((acc.anchor, acc.state))
      }

  private final class Persistence[F[_]: Async, S](
      store: ImmutableStore[F],
      snapshots: LedgerSnapshots[F, S],
      supervisor: Supervisor[F],
      events: StorageEvents[F],
      config: Config,
      pending: Ref[F, Vector[(BlockRef, Bytes)]],
      lastSlot: Ref[F, BigInt],
      busy: Ref[F, Boolean]
  )(using Raise[F, StorageError])
      extends ChainPersistence[F, S]:
    private val F = Async[F]

    def extended(
        ref: BlockRef,
        raw: Bytes,
        immutable: Vector[BlockRef],
        anchor: Anchor[S]
    ): F[Unit] =
      pending.update(_ :+ (ref -> raw)) *>
        F.whenA(immutable.nonEmpty) {
          for
            batch <- pending.modify(p => (p.drop(immutable.size), p.take(immutable.size)))
            _ <- F.unlessA(batch.map(_._1) == immutable)(
              StorageError
                .OutOfOrder("volatile blocks do not match the new immutable ones")
                .raise[F, Unit]
            )
            _ <- batch.traverse_((r, b) => store.append(r, b))
            _ <- anchor.block.traverse_(advanced(_, anchor.state))
          yield ()
        }

    def rolledBack(to: ChainSync.Point): F[Unit] =
      pending.update { p =>
        val index = p.indexWhere(_._1.point == to)
        if index >= 0 then p.take(index + 1) else Vector.empty
      }

    /** The immutable tip is now `ref`, with `state` after it: snapshot when the policy says so. */
    def advanced(ref: BlockRef, state: S): F[Unit] =
      lastSlot.get.flatMap { last =>
        F.whenA(ref.slot - last >= config.snapshotEverySlots) {
          busy.getAndSet(true).flatMap {
            case true => F.unit
            case false =>
              lastSlot.set(ref.slot) *> supervisor
                .supervise(snapshot(ref, state).guarantee(busy.set(false)))
                .void
          }
        }
      }

    private def snapshot(ref: BlockRef, state: S): F[Unit] =
      val began = Clock[F].monotonic
      (for
        t0 <- began
        _ <- store.sync
        written <- snapshots.write(StateSnapshot(Some(ref), state))
        t1 <- Clock[F].monotonic
        _ <- events.emit(
          StorageEvent
            .SnapshotWritten(written.name, written.anchor, written.bytes, (t1 - t0).toMillis)
        )
      yield ()).attempt.flatMap {
        case Right(_) => F.unit
        case Left(e)  => events.emit(StorageEvent.SnapshotFailed(String.valueOf(e)))
      }

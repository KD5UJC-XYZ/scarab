// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Clock, Ref, Resource}
import cats.effect.std.{Mutex, Supervisor}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.Files
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ChainError,
  NetworkIdentity,
  FirstImmutableProblem,
  NodeError,
  StorageError,
  StorageStructureError
}
import xyz.kd5ujc.scarab.consensus.Compute
import xyz.kd5ujc.scarab.consensus.SelectView
import scala.concurrent.duration.*

/** Observable storage milestones, for the JSON log. */
enum StorageEvent:
  case ImmutableOpened(report: ImmutableStore.OpenReport)

  /** Files of one kind were rewritten from an older format version (a database written by an older
    * build, opened for the first time by this one): see [[Migrate]].
    */
  case FormatMigrated(migrated: Migrated)
  case SnapshotSkipped(name: String, error: StorageError)
  case SnapshotRestored(name: String, anchor: Option[BlockRef])
  case SnapshotWritten(name: String, anchor: Option[BlockRef], bytes: Long, millis: Long)
  case SnapshotFailed(failure: SnapshotFailure)

  /** A snapshot that was not written, or an old one not brought to the current format, because the
    * format refused the state (`name` is the file; `anchor` the block of a write, none for a
    * migration). It is an optimisation skipped, not an error.
    */
  case SnapshotRefused(name: String, anchor: Option[BlockRef], refusal: SnapshotRefusal)
  case ReplayStarted(from: Option[BlockRef], tip: Option[BlockRef])
  case ReplayProgress(at: BlockRef, replayed: Long, millis: Long)
  case ReplayFinished(at: Option[BlockRef], replayed: Long, millis: Long)
  case NetworkRecorded(identity: NetworkIdentity, adopted: Boolean)
  case VolatileOpened(report: VolatileStore.OpenReport)

  /** The volatile chain was rebuilt from the log: `resumed` blocks applied on top of the immutable
    * state, `dropped` logged blocks after the immutable tip that were not (they do not apply, or
    * there were more than `k`) and will be fetched again.
    */
  case VolatileResumed(resumed: Int, dropped: Int, millis: Long)

  /** The shutdown snapshot: `written` is false when the last snapshot already was at the immutable
    * tip (or there was nothing to snapshot).
    */
  case ShutdownSnapshot(anchor: Option[BlockRef], written: Boolean)

trait StorageEvents[F[_]]:
  def emit(event: StorageEvent): F[Unit]

/** What the follower tells the storage layer; the follower itself stays storage-agnostic. */
trait ChainPersistence[F[_], S]:
  /** A block was applied. `immutable` are the blocks that just aged past `k` (oldest first) and
    * `anchor` the chain's new immutable boundary.
    */
  def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S])(using
      cats.mtl.Raise[F, StorageError],
      cats.mtl.Raise[F, ChainError]
  ): F[Unit]

  /** The volatile chain now ends at `to`. */
  def rolledBack(
      to: ChainSync.Point
  )(using cats.mtl.Raise[F, StorageError], cats.mtl.Raise[F, ChainError]): F[Unit]

object ChainPersistence:
  /** No block storage, but the backing store still follows the immutable tip. */
  def backingOnly[F[_]: cats.Monad, S](backing: StateBacking[F, S]): ChainPersistence[F, S] =
    new ChainPersistence[F, S]:
      def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S])(using
          cats.mtl.Raise[F, StorageError],
          cats.mtl.Raise[F, ChainError]
      ) =
        if immutable.isEmpty then cats.Monad[F].unit else backing.advance(anchor.state)
      def rolledBack(
          to: ChainSync.Point
      )(using cats.mtl.Raise[F, StorageError], cats.mtl.Raise[F, ChainError]) = cats.Monad[F].unit

  def none[F[_]: cats.Applicative, S]: ChainPersistence[F, S] = new ChainPersistence[F, S]:
    def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S])(using
        cats.mtl.Raise[F, StorageError],
        cats.mtl.Raise[F, ChainError]
    ) =
      cats.Applicative[F].unit
    def rolledBack(
        to: ChainSync.Point
    )(using cats.mtl.Raise[F, StorageError], cats.mtl.Raise[F, ChainError]) =
      cats.Applicative[F].unit

/** The ChainDB: an [[ImmutableStore]], [[LedgerSnapshots]] and a [[VolatileStore]] under one
  * directory, and the network identity the database was created for ([[NetworkIdentityFile]]).
  *
  * The volatile part (the last `k` blocks) lives in memory, with a log of its updates on disk:
  * after a restart [[resume]] applies the logged blocks on top of the immutable state, so the node
  * continues at its previous tip. A graceful stop (the resource is released) writes a snapshot at
  * the immutable tip when the state is writable. The next start restores it and replays the
  * retained volatile suffix; without a usable snapshot it also replays immutable blocks.
  *
  * Layout: `<dir>/network-identity`, `<dir>/immutable/`, `<dir>/snapshots/`, `<dir>/volatile/`.
  */
object ChainStorage:
  /** Stored blocks are prepared this many at a time during replay. */
  private val ReplayBatch = 64

  /** Blocks that become immutable between two writes of the backing store (see
    * `Persistence.advanceBacking`), except at a snapshot, which always brings it to its anchor.
    */
  private val AdvanceEvery = 128

  /** @param snapshotPolicy
    *   When a snapshot is taken (the immutable tip also gets one on a graceful stop).
    * @param byronEpochSlots
    *   10k of the Byron genesis, to place Byron blocks on the slot axis when blocks are decoded.
    * @param progressEvery
    *   Replay progress is reported every this many blocks.
    * @param identity
    *   The network the database must belong to; none: not recorded or checked (tests).
    * @param adoptLegacyNetworkIdentity
    *   Explicit operator acknowledgement for adopting incomplete or missing identity.
    * @param volatileFileBlocks
    *   Blocks per volatile log file.
    * @param volatileCollectBlocks
    *   Blocks that become immutable between two forced syncs of the immutable store, each followed
    *   by a collection of the log files wholly below the immutable tip. The sync is what makes
    *   collecting safe, and it is a disk flush on the apply path, so it is rare: the log keeps up
    *   to this many blocks beyond the `k` it needs.
    */
  final case class Config(
      snapshotPolicy: SnapshotPolicy,
      keepSnapshots: Int = SnapshotPolicy.ReferenceKeep,
      chunkSlots: Long = FileImmutableStore.DefaultChunkSlots,
      byronEpochSlots: BigInt,
      progressEvery: Int = 20000,
      identity: Option[NetworkIdentity] = None,
      adoptLegacyNetworkIdentity: Boolean = false,
      volatileFileBlocks: Int = 512,
      volatileCollectBlocks: Int = 4096
  )

  /** The node state recovered from disk, ready to anchor a
    * [[xyz.kd5ujc.scarab.consensus.VolatileChain]].
    *
    * @param volatile
    *   The chain the volatile log describes, not yet validated (see [[resume]]).
    * @param adopt
    *   Make `blocks` (a prefix of the logged chain that was validated) the persistence's pending
    *   blocks, and cut the log back to them.
    */
  final case class Recovered[F[_], S](
      anchor: Option[BlockRef],
      state: S,
      persistence: ChainPersistence[F, S],
      store: ImmutableStore[F],
      volatile: Vector[(BlockRef, Bytes)],
      adopt: Vector[(BlockRef, Bytes)] => F[Unit]
  )

  /** A volatile block rebuilt by [[resume]]. */
  final case class Resumed[S](ref: BlockRef, raw: Bytes, state: S, view: SelectView)

  /** Read-only identity and format checks, reusable before the backing store is acquired. Database
    * ownership must already be held by callers that open an on-disk database.
    */
  def preflight[F[_]: Async](
      dir: Path,
      identity: Option[NetworkIdentity],
      confirmLegacy: Boolean = false,
      byronEpochSlots: BigInt = BigInt(0),
      chunkSlots: Long = FileImmutableStore.DefaultChunkSlots
  )(using Raise[F, StorageError]): F[NetworkIdentityFile.Admission] =
    val F = Async[F]
    def populated: F[Boolean] =
      AtomicFile.guard[F, Boolean](dir.toString) {
        val root = dir.toNioPath
        if !Files.exists(root) then false
        else
          val stream = Files.walk(root)
          try stream.skip(1).findAny().isPresent
          finally stream.close()
      }
    def validateFirstPredecessor(expected: NetworkIdentity): F[Unit] =
      FileImmutableStore.firstBlockReadOnly[F](dir / "immutable").flatMap {
        case None => F.unit
        case Some(raw) =>
          Block.decode(raw, byronEpochSlots) match
            case Left(error) =>
              StorageError.FirstImmutable(FirstImmutableProblem.BlockDecode(error)).raise[F, Unit]
            case Right(block) if block.header.prevHash == expected.byronGenesis => F.unit
            case Right(block) =>
              def show(h: Option[Bytes]) = h.fold("no predecessor (genesis)")(_.hex)
              StorageError
                .ForeignChain(show(block.header.prevHash), show(expected.byronGenesis))
                .raise[F, Unit]
      }
    for
      hasData <- populated
      admission <- identity.fold(F.pure(NetworkIdentityFile.Admission.Unchanged))(
        NetworkIdentityFile.preflight[F](dir, _, hasData, confirmLegacy)
      )
      _ <- admission match
        case NetworkIdentityFile.Admission.AdoptMissing =>
          identity.traverse_(validateFirstPredecessor)
        case _ => F.unit
      _ <- SnapshotMigration.preflight[F](dir / "snapshots")
      _ <- F.whenA(admission != NetworkIdentityFile.Admission.Unchanged)(
        FileImmutableStore.preflightReadOnly[F](dir / "immutable", chunkSlots)
      )
    yield admission

  /** @param prepare
    *   Called with each batch of stored blocks just before they are applied (verification that does
    *   not depend on the state can run ahead, in parallel).
    * @param compute
    *   The CPU pool the replay runs on (D8); without one, on the calling fiber's pool.
    * @param snapshotMigration
    *   Brings the snapshot files of an older format to the current one, before they are opened
    *   (once the identity of the database is checked): [[SnapshotMigration.toCurrent]].
    */
  def open[F[_]: Async, S](
      dir: Path,
      config: Config,
      rules: BlockRules[F, S],
      initial: S,
      events: StorageEvents[F],
      backing: StateBacking[F, S],
      prepare: Vector[Bytes] => F[Unit],
      compute: Option[Compute[F]] = None,
      snapshotMigration: Option[SnapshotMigration[F]] = None
  )(using
      SnapshotFormat[F, S],
      Raise[F, StorageError],
      Raise[F, ChainError],
      Raise[F, xyz.kd5ujc.scarab.ledger.ValidationError]
  ): Resource[F, Recovered[F, S]] =
    val F = Async[F]
    for
      // Complete read-only compatibility checks before identity admission or opening a store.
      admission <- Resource.eval(
        preflight(
          dir,
          config.identity,
          config.adoptLegacyNetworkIdentity,
          config.byronEpochSlots,
          config.chunkSlots
        )
      )
      _ <- Resource.eval(config.identity.traverse_(NetworkIdentityFile.admit[F](dir, _, admission)))
      migratedIdentity <- Resource.eval(NetworkIdentityFile.migrate[F](dir))
      _ <- Resource.eval(
        migratedIdentity.traverse_(m => events.emit(StorageEvent.FormatMigrated(m)))
      )
      opened <- FileImmutableStore.open[F](dir / "immutable", config.chunkSlots)
      (store, report) = opened
      _ <- Resource.eval(
        report.migrated.traverse_(m => events.emit(StorageEvent.FormatMigrated(m)))
      )
      _ <- Resource.eval(events.emit(StorageEvent.ImmutableOpened(report)))
      _ <- Resource.eval(
        config.identity.traverse_(identity =>
          F.whenA(admission != NetworkIdentityFile.Admission.Unchanged)(
            events.emit(
              StorageEvent.NetworkRecorded(
                identity,
                adopted = admission match
                  case NetworkIdentityFile.Admission.AdoptLegacy(_, _) |
                      NetworkIdentityFile.Admission.AdoptMissing =>
                    true
                  case _ => false
              )
            )
          )
        )
      )
      volatileOpened <- VolatileStore.open[F](dir / "volatile", config.volatileFileBlocks)
      (volatile, logged) = volatileOpened
      _ <- Resource.eval(events.emit(StorageEvent.VolatileOpened(logged.report)))
      migratedSnapshots <- Resource.eval(
        snapshotMigration.fold(F.pure(SnapshotMigration.Report.empty))(_.run(dir / "snapshots"))
      )
      _ <- Resource.eval(
        migratedSnapshots.migrated.traverse_(m => events.emit(StorageEvent.FormatMigrated(m))) *>
          migratedSnapshots.refused.traverse_(r =>
            events.emit(StorageEvent.SnapshotRefused(r.name, None, r.refusal))
          )
      )
      snapshots <- Resource.eval(
        FileLedgerSnapshots.open[F, S](dir / "snapshots", config.keepSnapshots)
      )
      supervisor <- Supervisor[F](await = false)
      restored <- Resource.eval(
        snapshots.restore(anchor =>
          for
            stored <- anchor.fold(F.pure(true))(a => store.get(a.point).map(_.isDefined))
            backed <- if stored then backing.available(anchor) else F.pure(false)
          yield stored && backed
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
      _ <- Resource.eval(
        restored.snapshot.fold(backing.reset)((_, s) => backing.restore(s.anchor).void)
      )
      pending <- Resource.eval(Ref.of[F, Vector[(BlockRef, Bytes)]](Vector.empty))
      lastSlot <- Resource.eval(Ref.of[F, BigInt](start._1.fold(BigInt(0))(_.slot)))
      previousSlot <- Resource.eval(Ref.of[F, BigInt](start._1.fold(BigInt(0))(_.slot)))
      lastTaken <- Resource.eval(Ref.of[F, Option[FiniteDuration]](None))
      busy <- Resource.eval(Ref.of[F, Boolean](false))
      latest <- Resource.eval(Ref.of[F, Option[(BlockRef, S)]](None))
      written <- Resource.eval(Ref.of[F, Option[BlockRef]](start._1))
      sinceCollect <- Resource.eval(Ref.of[F, Int](0))
      snapshotLock <- Resource.eval(Mutex[F])
      advancedAt <- Resource.eval(Ref.of[F, BigInt](BigInt(-1)))
      persistence = new Persistence[F, S](
        store,
        snapshots,
        volatile,
        supervisor,
        events,
        config,
        pending,
        lastSlot,
        previousSlot,
        lastTaken,
        busy,
        latest,
        written,
        sinceCollect,
        snapshotLock,
        backing,
        advancedAt
      )
      // Released before the stores: a graceful stop leaves a snapshot at the immutable tip.
      _ <- Resource.onFinalize(persistence.shutdownSnapshot)
      end <- Resource.eval(store.tip)
      _ <- Resource.eval(events.emit(StorageEvent.ReplayStarted(start._1, end)))
      began <- Resource.eval(Clock[F].monotonic)
      replayed <- Resource.eval(
        compute.fold(identity[F[(Option[BlockRef], S)]])(c => c.within[(Option[BlockRef], S)])(
          replay(store, rules, persistence, config, events, prepare, start._1, start._2, began)
        )
      )
    yield Recovered(
      replayed._1,
      replayed._2,
      persistence,
      store,
      logged.chain,
      persistence.adopt
    )

  /** Rebuild the volatile chain from the log: apply the logged blocks after the immutable tip, in
    * order, on top of the recovered state. Blocks that do not connect or that the rules refuse end
    * the chain there (the rest is fetched again, and the log is cut back); a log with more than `k`
    * blocks after the immutable tip (the immutable store lost an unforced tail) resumes the first
    * `k`. The result is the volatile chain the node continues from.
    */
  def resume[F[_]: Async, S](
      recovered: Recovered[F, S],
      k: Int,
      origin: Option[Bytes],
      rules: BlockRules[F, S],
      byronEpochSlots: BigInt,
      events: StorageEvents[F],
      prepare: Vector[Bytes] => F[Unit],
      compute: Option[Compute[F]] = None
  )(using handle: Handle[F, NodeError]): F[Vector[Resumed[S]]] =
    val F = Async[F]
    val anchor = recovered.anchor
    val after = anchor.fold(recovered.volatile) { a =>
      val i = recovered.volatile.indexWhere(_._1.hash == a.hash)
      if i >= 0 then recovered.volatile.drop(i + 1)
      else recovered.volatile.dropWhile(_._1.slot <= a.slot)
    }
    val candidates = after.take(k)
    val start = VolatileChain[S](k, Anchor(anchor, recovered.state), Vector.empty, origin)
    def go(
        chain: VolatileChain[S],
        todo: List[(BlockRef, Bytes)],
        done: Vector[Resumed[S]]
    ): F[Vector[Resumed[S]]] = todo match
      case Nil => F.pure(done)
      case (ref, raw) :: rest =>
        Block.decode(raw, byronEpochSlots) match
          case Left(_) => F.pure(done)
          case Right(block) =>
            val header = block.header
            if header.hash != ref.hash then F.pure(done)
            else
              handle.attempt(rules(chain.state, header, raw)).flatMap {
                case Left(_) => F.pure(done)
                case Right(next) =>
                  chain.extend(header, next) match
                    case Left(_) => F.pure(done)
                    case Right((grown, _)) =>
                      F.cede *> go(
                        grown,
                        rest,
                        done :+ Resumed(ref, raw, next, SelectView.of(header))
                      )
              }
    for
      began <- Clock[F].monotonic
      _ <- prepare(candidates.map(_._2))
      resumed <- compute.fold(go(start, candidates.toList, Vector.empty))(
        _.within(go(start, candidates.toList, Vector.empty))
      )
      _ <- recovered.adopt(resumed.map(r => r.ref -> r.raw))
      dropped = after.size - resumed.size
      _ <- F.whenA(dropped > 0)(
        recovered.persistence.rolledBack(resumed.lastOption.fold(start.anchor.point)(_.ref.point))
      )
      done <- Clock[F].monotonic
      _ <- events.emit(StorageEvent.VolatileResumed(resumed.size, dropped, (done - began).toMillis))
    yield resumed

  /** Re-apply the stored blocks after `from`, with no network. */
  private def replay[F[_]: Async, S](
      store: ImmutableStore[F],
      rules: BlockRules[F, S],
      persistence: Persistence[F, S],
      config: Config,
      events: StorageEvents[F],
      prepare: Vector[Bytes] => F[Unit],
      from: Option[BlockRef],
      state: S,
      began: FiniteDuration
  )(using
      chainRaise: Raise[F, ChainError],
      storageRaise: Raise[F, StorageError],
      validationRaise: Raise[F, xyz.kd5ujc.scarab.ledger.ValidationError]
  ): F[(Option[BlockRef], S)] =
    val F = Async[F]
    final case class Acc(anchor: Option[BlockRef], state: S, count: Long)
    def elapsed: F[Long] = Clock[F].monotonic.map(now => (now - began).toMillis)
    store
      .stream(from.fold[ChainSync.Point](ChainSync.Point.Origin)(_.point))
      // Warm the next batch (signature checks in parallel) while the current one is applied.
      .chunkN(ReplayBatch)
      .evalMap(chunk => prepare(chunk.toVector.map(_.raw)).as(chunk))
      .prefetchN(2)
      .flatMap(fs2.Stream.chunk)
      .evalScan(Acc(from, state, 0L)) { (acc, stored) =>
        for
          block <- Block.decode(stored.raw, config.byronEpochSlots) match
            case Right(b) => F.pure(b)
            case Left(e)  => e.raise[F, Block]
          next <- rules(acc.state, block.header, stored.raw)
          _ <- persistence.advanced(stored.ref, next)
          // Replay is one long loop: let the other fibers (timers, the query server) run.
          _ <- F.cede
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

  /** @param previousSlot
    *   The slot of the immutable tip before the block being advanced to.
    * @param lastTaken
    *   When (monotonic) the previous snapshot was requested; none yet since the start.
    * @param latest
    *   The immutable tip and the state after it, as the backing store stands.
    * @param written
    *   The anchor of the newest snapshot on disk.
    * @param sinceCollect
    *   Blocks that became immutable since the volatile log was last collected.
    * @param advancedAt
    *   Block number of the immutable tip the backing store was last brought to.
    */
  private final class Persistence[F[_]: Async, S](
      store: ImmutableStore[F],
      snapshots: LedgerSnapshots[F, S],
      volatile: VolatileStore[F],
      supervisor: Supervisor[F],
      events: StorageEvents[F],
      config: Config,
      pending: Ref[F, Vector[(BlockRef, Bytes)]],
      lastSlot: Ref[F, BigInt],
      previousSlot: Ref[F, BigInt],
      lastTaken: Ref[F, Option[FiniteDuration]],
      busy: Ref[F, Boolean],
      latest: Ref[F, Option[(BlockRef, S)]],
      written: Ref[F, Option[BlockRef]],
      sinceCollect: Ref[F, Int],
      snapshotLock: Mutex[F],
      backing: StateBacking[F, S],
      advancedAt: Ref[F, BigInt]
  ) extends ChainPersistence[F, S]:
    private val F = Async[F]

    /** Log the block, keep it pending, and move aged blocks into the immutable and backing stores.
      * Cancellation is masked across this sequence so a graceful stop cannot interrupt a block's
      * storage transition. Storage errors still propagate; this is not a multi-store transaction.
      */
    def extended(
        ref: BlockRef,
        raw: Bytes,
        immutable: Vector[BlockRef],
        anchor: Anchor[S]
    )(using cats.mtl.Raise[F, StorageError], cats.mtl.Raise[F, ChainError]): F[Unit] =
      F.uncancelable { _ =>
        volatile.append(ref, raw) *>
          pending.update(_ :+ (ref -> raw)) *>
          F.whenA(immutable.nonEmpty) {
            for
              batch <- pending.modify(p => (p.drop(immutable.size), p.take(immutable.size)))
              _ <- F.unlessA(batch.map(_._1) == immutable)(
                StorageError
                  .OutOfOrder(StorageStructureError.VolatileImmutableMismatch)
                  .raise[F, Unit]
              )
              _ <- batch.traverse_((r, b) => store.append(r, b))
              _ <- anchor.block.traverse_(advanced(_, anchor.state))
              _ <- collect(batch.size, anchor.block)
            yield ()
          }
      }

    /** The volatile log keeps blocks that are already immutable until a file is wholly below the
      * immutable tip. Collecting forces the immutable store first: the log is what protects a block
      * the immutable store has not yet made durable.
      */
    private def collect(added: Int, tip: Option[BlockRef])(using
        cats.mtl.Raise[F, StorageError]
    ): F[Unit] =
      sinceCollect.updateAndGet(_ + added).flatMap { n =>
        F.whenA(n >= config.volatileCollectBlocks) {
          tip.traverse_(t => store.sync *> volatile.gc(t.slot)) *> sinceCollect.set(0)
        }
      }

    def rolledBack(
        to: ChainSync.Point
    )(using cats.mtl.Raise[F, StorageError], cats.mtl.Raise[F, ChainError]): F[Unit] =
      F.uncancelable { _ =>
        volatile.rollback(to) *> pending.update { p =>
          val index = p.indexWhere(_._1.point == to)
          if index >= 0 then p.take(index + 1) else Vector.empty
        }
      }

    /** The logged chain after the immutable tip (validated by the caller) becomes pending. */
    def adopt(blocks: Vector[(BlockRef, Bytes)]): F[Unit] = pending.set(blocks)

    /** The immutable tip is now `ref`, with `state` after it: bring the backing store to it and
      * snapshot when the policy says so. The backing checkpoint is taken here, before the snapshot
      * is written in the background, because the store keeps moving with the chain.
      */
    def advanced(ref: BlockRef, state: S)(using cats.mtl.Raise[F, StorageError]): F[Unit] =
      (lastSlot.get, previousSlot.getAndSet(ref.slot), lastTaken.get, Clock[F].monotonic).flatMapN {
        (last, previous, taken, now) =>
          val snapshotDue = config.snapshotPolicy.due(
            SnapshotPolicy.Facts(previous, ref.slot, last, taken.map(now - _))
          )
          F.uncancelable(_ =>
            advanceBacking(ref, state, snapshotDue) *> latest.set(Some(ref -> state))
          ) *>
            afterAdvance(ref, state, snapshotDue)
      }

    /** Bring the backing store to the immutable tip, but not for every block: writing walks the
      * diff of the state, which holds the changes of the last `k` blocks, so the store follows in
      * steps of [[ChainStorage.AdvanceEvery]] blocks (and at every snapshot, whose checkpoint must
      * be at its anchor). The diff keeps what the store lacks, so the states stay complete.
      */
    private def advanceBacking(ref: BlockRef, state: S, force: Boolean)(using
        cats.mtl.Raise[F, StorageError]
    ): F[Unit] =
      advancedAt.get.flatMap { at =>
        F.whenA(force || at.signum < 0 || ref.blockNo - at >= ChainStorage.AdvanceEvery)(
          backing.advance(state) *> advancedAt.set(ref.blockNo)
        )
      }

    private def afterAdvance(ref: BlockRef, state: S, snapshotDue: Boolean)(using
        cats.mtl.Raise[F, StorageError]
    ): F[Unit] =
      F.whenA(snapshotDue) {
        busy.getAndSet(true).flatMap {
          case true => F.unit
          case false =>
            (for
              _ <- lastSlot.set(ref.slot)
              _ <- Clock[F].monotonic.flatMap(now => lastTaken.set(Some(now)))
              settled <- backing.settled(state)
              _ <- backing.checkpoint(ref)
              _ <- supervisor.supervise(
                SnapshotFailure
                  .capture {
                    snapshotLock.lock.surround(snapshot(ref, settled))
                  }
                  .guarantee(busy.set(false))
                  .flatMap(reportSnapshot)
              )
            yield ()).onError(_ => busy.set(false))
        }
      }

    /** Write under `snapshotLock`, using the capability of this snapshot worker/finalizer. */
    private def snapshot(ref: BlockRef, state: S)(using Raise[F, StorageError]): F[Boolean] =
      val began = Clock[F].monotonic
      for
        t0 <- began
        _ <- store.sync
        outcome <- snapshots.write(StateSnapshot(Some(ref), state))
        done <- outcome.fold(
          refusal =>
            events
              .emit(
                StorageEvent.SnapshotRefused(FileLedgerSnapshots.nameOf(ref), Some(ref), refusal)
              )
              .as(false),
          written0 =>
            for
              _ <- written.set(Some(ref))
              names <- snapshots.list
              _ <- backing.retain(names.map(_.stripSuffix(".bin")).toSet)
              t1 <- Clock[F].monotonic
              _ <- events.emit(
                StorageEvent.SnapshotWritten(
                  written0.name,
                  written0.anchor,
                  written0.bytes,
                  (t1 - t0).toMillis
                )
              )
            yield true
        )
      yield done

    private def reportSnapshot[A](result: Either[SnapshotFailure, A]): F[Unit] =
      result.fold(error => events.emit(StorageEvent.SnapshotFailed(error)), _ => F.unit)

    /** On release: wait for a snapshot in flight, then snapshot the immutable tip unless the newest
      * snapshot already is there. Storage failures and defects are reported as values; a snapshot
      * that cannot be written only costs the next start a replay.
      */
    def shutdownSnapshot: F[Unit] =
      SnapshotFailure
        .capture {
          val idle = (F.sleep(20.millis) *> busy.get).iterateUntil(b => !b)
          val body = snapshotLock.lock.surround {
            (latest.get, written.get).flatMapN {
              case (Some((ref, state)), done) if !done.contains(ref) =>
                for
                  _ <- backing.advance(state)
                  settled <- backing.settled(state)
                  _ <- backing.checkpoint(ref)
                  ok <- snapshot(ref, settled)
                  _ <- events.emit(StorageEvent.ShutdownSnapshot(Some(ref), ok))
                yield ()
              case (_, done) => events.emit(StorageEvent.ShutdownSnapshot(done, false))
            }
          }
          idle.timeoutTo(2.minutes, F.pure(true)) *> body
        }
        .flatMap(reportSnapshot)

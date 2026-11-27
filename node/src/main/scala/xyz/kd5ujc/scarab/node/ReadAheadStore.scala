// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import fs2.io.file.Path
import scala.annotation.tailrec
import scala.collection.immutable.{SortedSet, TreeMap}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.network.Fibers
import xyz.kd5ujc.scarab.storage.{UtxoPinnedReadView, UtxoStore}

/** The UTxO store with the read sets of queued blocks read ahead (#1484): while the worker applies
  * one block, the store reads of the blocks waiting behind it in the add-block queue are issued, so
  * that their waits on the disk overlap the rules instead of following them. The block path then
  * takes from what was read ahead and reads only the rest ([[readFor]]); every other read (the
  * mempool, the forger, the local state queries and their pinned views) goes to the store directly.
  *
  * Where the reads are issued: each block admitted to the queue asks for its read ahead ([[ahead]],
  * from `BlockSelection` 's admission). The request is queued (at most [[Requests]], further ones
  * dropped), and [[Readers]] fibers each take one, decode the block and read the inputs the
  * selected chain's tip does not decide ([[ReadAheadStore.inputs]]), several blocks' reads in
  * flight at once so the disk can queue them. Nothing waits for a read ahead: a block whose reads
  * have not landed when the worker reaches it reads what is missing itself, as without one.
  *
  * What is kept: the entries read, in a buffer bounded in entries and in the bytes of the outputs
  * ([[ReadAheadStore.Bounds]]), the oldest dropped first; entries the store does not hold are not
  * kept (an absent result is never given as absent: the key is read again). Each entry is tagged
  * with the store's stamp read before it (`at`), so the values are of the store at `at` or later.
  *
  * Why a value read ahead is the store's value when it is used. The store moves forward only by
  * writing the changes of the chain's immutable blocks, each carrying the stamp of its block
  * (`UtxoStore.advance`), and a state's diff holds the newest change of every key changed by its
  * chain after its trim point (`utxo.flushed`, [[Utxo.settled]]). The block path reads only keys
  * the state's diff does not decide (`utxo.missing`); for such a key no block of the state's chain
  * after its trim point changed it, so the store holds the same value for it at every stamp from
  * the trim point on, up to the store's own. An entry is therefore served only where
  * `at >= utxo.flushed`; one read before the trim point moved past it (the key may have been spent,
  * flushed and trimmed since) is dropped and the key read again ([[ReadAheadStore.Buffer.take]]).
  * The argument covers every change, not only creation and deletion: also the outputs a block
  * entering Dijkstra upgrades, which are changes in the diff like any other. A block of another
  * fork reads the same store, and an entry is keyed by its input, not by any block, so nothing read
  * for one fork is confused with another's.
  *
  * The two operations that move the store other than forward, [[reset]] and [[restore]], empty the
  * buffer before and after they run and advance its generation, and a read lands only in the
  * generation it started in ([[ReadAheadStore.Buffer.put]]), so nothing read before either is kept.
  *
  * The reference reads a block's tables when it applies the block (`withValues`,
  * `forkerReadTables fo (getBlockKeySets blk)`, ouroboros-consensus 82ecba32
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/LedgerDB/Forker.hs`
  * 455-458), with nothing read ahead. Only when an entry is read changes, never the entries a block
  * is applied with: an Optimisation (REF-6), approved with #1484's plan.
  */
final class ReadAheadStore[F[_]: Async] private (
    store: UtxoStore[F],
    buffer: SignallingRef[F, ReadAheadStore.Buffer],
    requests: Option[Queue[F, F[Set[TxIn]]]],
    ended: F[Fatal]
) extends UtxoStore[F]:
  private val F = Async[F]

  /** Completes only if a reader ends, which only a defect outside the read of a request can make it
    * do: the value it ended with, for the owner to raise in its own fiber (FIB-2). A store made by
    * [[ReadAheadStore.idle]] has no readers, and this never completes.
    */
  def stopped: F[Fatal] = ended

  /** Ask for the read ahead of the keys `keys` gives, evaluated on a reader; never waits, and a
    * request the queue has no room for is dropped. A store made by [[ReadAheadStore.idle]] reads
    * nothing ahead.
    */
  def ahead(keys: F[Set[TxIn]]): F[Unit] = requests.traverse_(_.tryOffer(keys).void)

  /** Read `keys` now into the buffer (a reader's work): those it does not hold, from the store at
    * its stamp of now or later, kept in the generation this read started in.
    */
  private[node] def prefetch(keys: Set[TxIn])(using Raise[F, StorageError]): F[Unit] =
    buffer.get.flatMap { held =>
      val wanted = keys.filterNot(held.holds)
      if wanted.isEmpty then F.unit
      else
        store.flushed.flatMap(at =>
          store.read(wanted).flatMap(read => buffer.update(_.put(held.generation, at, read)))
        )
    }

  /** The buffer as it changes, for the suites. */
  private[node] def buffered: Signal[F, ReadAheadStore.Buffer] = buffer

  /** A reader's loop, in an error scope of its own: it ends only with a failure, as the value it
    * ends with. Each request is read in a scope of its own too, and its failure, or a defect in it,
    * is discarded: it is no information, as the block path reads the key itself and meets its own
    * answer.
    */
  private def reading(queue: Queue[F, F[Set[TxIn]]]): F[Either[Fatal, Nothing]] =
    Fibers.scoped[F, Fatal, Nothing](Fatal.Defect(_))(
      F.foreverM(
        queue.take.flatMap(keys =>
          Fibers
            .scoped[F, StorageError, Unit](StorageError.Io("utxo read ahead", _))(
              keys.flatMap(prefetch)
            )
            .void
        )
      )
    )

  private val invalidate: F[Unit] = buffer.update(_.cleared)

  override def readFor(utxo: Utxo, keys: SortedSet[TxIn])(using
      Raise[F, StorageError]
  ): F[Map[TxIn, TxOut]] =
    buffer.modify(_.take(keys, utxo.flushed)).flatMap { served =>
      val rest = keys.filterNot(served.contains)
      if rest.isEmpty then F.pure(served) else store.read(rest).map(served ++ _)
    }

  def read(keys: Iterable[TxIn])(using Raise[F, StorageError]): F[Map[TxIn, TxOut]] =
    store.read(keys)
  def stamp(using Raise[F, StorageError]): F[Long] = store.stamp
  def all(using Raise[F, StorageError]): F[Map[TxIn, TxOut]] = store.all
  def select(keep: TxOut => Boolean)(using Raise[F, StorageError]): F[Map[TxIn, TxOut]] =
    store.select(keep)
  def selectAddresses(addresses: Set[Bytes])(using
      Raise[F, StorageError]
  ): F[Map[TxIn, TxOut]] = store.selectAddresses(addresses)
  def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long)(using
      Raise[F, StorageError]
  ): F[Unit] = store.write(changes, stamp)
  def flushed(using Raise[F, StorageError]): F[Long] = store.flushed
  def readView: Resource[F, Either[StorageError, UtxoPinnedReadView[F]]] = store.readView
  def scan[A](zero: A)(step: (A, TxIn, TxOut) => A)(using Raise[F, StorageError]): F[A] =
    store.scan(zero)(step)
  def entries(using Raise[F, StorageError]): Stream[F, (TxIn, TxOut)] = store.entries
  def size(using Raise[F, StorageError]): F[Long] = store.size
  def checkpoint(dir: Path)(using Raise[F, StorageError]): F[Unit] = store.checkpoint(dir)
  def restore(dir: Path)(using Raise[F, StorageError]): F[Boolean] =
    invalidate *> store.restore(dir).guarantee(invalidate)
  def reset(using Raise[F, StorageError]): F[Unit] =
    invalidate *> store.reset.guarantee(invalidate)
  def memory(using Raise[F, StorageError]): F[Map[String, Long]] = store.memory

object ReadAheadStore:
  /** Reads ahead in flight at once: several, so a disk can queue and reorder them. */
  val Readers = 4

  /** Requests waiting for a reader: more than the add-block queue holds (ten, and the one being
    * added), so a request is dropped only when the readers are behind by more than the queue.
    */
  val Requests = 16

  /** The buffer's bounds: the entries it holds and their bytes, each entry charged the bytes of its
    * output's encoding (`TxOut.raw`, which the decoded output's memory is proportional to; empty
    * for an output built rather than decoded, such as a genesis entry) and [[EntryBytes]]. Ten
    * queued blocks of mainnet's Babbage era read some hundreds of stored entries each, so the
    * default bounds hold them many times over; a block can reference thousands of large outputs, so
    * the bytes are bounded too, and the buffer's memory is fixed whatever the size of the UTxO set.
    */
  final case class Bounds(entries: Int, bytes: Long)

  object Bounds:
    val Default: Bounds = Bounds(32768, 16L * 1024 * 1024)

  /** The bytes an entry is charged beyond its output's encoding: its input, its tag and its place
    * in the buffer's two maps.
    */
  val EntryBytes = 128L

  private def charge(out: TxOut): Long = out.raw.size + EntryBytes

  /** One entry read ahead: its output, the store's stamp read before it, and its place in the order
    * entries are dropped in.
    */
  private final case class Held(out: TxOut, at: Long, seq: Long)

  /** What was read ahead, by input, within its bounds, with how many entries were served and how
    * many were dropped as read before the trim point of the state that asked ([[take]]).
    */
  final case class Buffer private (
      generation: Long,
      bounds: Bounds,
      private val held: Map[TxIn, Held],
      private val order: TreeMap[Long, TxIn],
      private val next: Long,
      bytes: Long,
      served: Long,
      stale: Long
  ):
    def size: Int = held.size
    def holds(in: TxIn): Boolean = held.contains(in)

    /** `read`, read at the store's stamp `at` or later, added in `generation`: nothing when the
      * buffer has been emptied since ([[cleared]]); then the oldest entries dropped until the
      * buffer is within its bounds.
      */
    def put(generation: Long, at: Long, read: Map[TxIn, TxOut]): Buffer =
      if generation != this.generation then this
      else read.foldLeft(this) { case (b, (in, out)) => b.inserted(in, out, at) }.within

    /** The entries of `keys` held, removed from the buffer, and of those the ones read at or after
      * `flushed`, the trim point of the state they are read for: only those are the store's values
      * for that state (see [[ReadAheadStore]]).
      */
    def take(keys: Iterable[TxIn], flushed: Long): (Buffer, Map[TxIn, TxOut]) =
      keys.foldLeft((this, Map.empty[TxIn, TxOut])) { case ((b, out), in) =>
        b.held.get(in) match
          case None => (b, out)
          case Some(h) if h.at >= flushed =>
            (b.removed(in).copy(served = b.served + 1), out.updated(in, h.out))
          case Some(_) => (b.removed(in).copy(stale = b.stale + 1), out)
      }

    /** Empty, in the next generation. */
    def cleared: Buffer =
      copy(generation = generation + 1, held = Map.empty, order = TreeMap.empty, bytes = 0)

    private def inserted(in: TxIn, out: TxOut, at: Long): Buffer =
      val base = removed(in)
      base.copy(
        held = base.held.updated(in, Held(out, at, base.next)),
        order = base.order.updated(base.next, in),
        next = base.next + 1,
        bytes = base.bytes + charge(out)
      )

    private def removed(in: TxIn): Buffer =
      held
        .get(in)
        .fold(this)(h =>
          copy(held = held - in, order = order - h.seq, bytes = bytes - charge(h.out))
        )

    @tailrec private def within: Buffer =
      if held.sizeIs <= bounds.entries && bytes <= bounds.bytes then this
      else
        order.headOption match
          case None          => this
          case Some((_, in)) => removed(in).within

  object Buffer:
    def empty(bounds: Bounds): Buffer = Buffer(0, bounds, Map.empty, TreeMap.empty, 0, 0, 0, 0)

  /** `store` with [[Readers]] readers reading ahead into a buffer of `bounds`; the readers stop
    * when the resource is released, before the store is. A reader that ends gives its value to
    * [[ReadAheadStore.stopped]].
    */
  def resource[F[_]: Async](
      store: UtxoStore[F],
      bounds: Bounds = Bounds.Default
  ): Resource[F, ReadAheadStore[F]] =
    for
      buffer <- Resource.eval(SignallingRef.of[F, Buffer](Buffer.empty(bounds)))
      queue <- Resource.eval(Queue.dropping[F, F[Set[TxIn]]](Requests))
      ended <- Resource.eval(Deferred[F, Fatal])
      reads = new ReadAheadStore[F](store, buffer, Some(queue), ended.get)
      _ <- List
        .fill(Readers)(
          reads.reading(queue).flatMap(_.fold(ended.complete(_).void, never => never)).background
        )
        .sequence_
    yield reads

  /** `store` reading nothing ahead on its own ([[ahead]] does nothing): a store in memory, which
    * has no disk to wait on, and the suites, which call [[prefetch]] themselves.
    */
  def idle[F[_]: Async](
      store: UtxoStore[F],
      bounds: Bounds = Bounds.Default
  ): F[ReadAheadStore[F]] =
    SignallingRef
      .of[F, Buffer](Buffer.empty(bounds))
      .map(new ReadAheadStore[F](store, _, None, Async[F].never[Fatal]))

  /** The inputs of the block `raw` whose entries the block path will read from the store, as far as
    * the selected chain's tip `tip` says: what its transactions spend or reference, less what the
    * tip's diff decides. None while the tip is in Byron, whose state holds no UTxO handle, or when
    * the block does not decode (the rules give its verdict). The blocks between the tip and this
    * one may create some of these, which the store does not hold: they come back absent and are not
    * kept, and the block path finds them in its state's diff.
    */
  def inputs(raw: Bytes, tip: MirrorState): Set[TxIn] =
    MirrorStep
      .utxoOf(tip)
      .fold(Set.empty[TxIn])(utxo =>
        NodeRules
          .decodeShelley(raw, tip.ext.layout.stateLayout)
          .fold(Set.empty[TxIn])(p => utxo.missing(p.inputs))
      )

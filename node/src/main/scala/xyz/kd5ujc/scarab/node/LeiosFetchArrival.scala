// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Applicative, Functor}
import cats.data.NonEmptyVector
import cats.effect.Async
import cats.effect.std.Semaphore
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  BytesSize,
  ProtVerParity,
  EbFetchState,
  EbState,
  LeiosFetchLogic,
  LeiosFetchRequest,
  LeiosFetchStaticEnv,
  LeiosJobId,
  LeiosJobPool,
  LeiosTxsReplyFault
}
import xyz.kd5ujc.scarab.network.NetworkError
import xyz.kd5ujc.scarab.node.mempool.LeiosTxIndex
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosFetchFault, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32
import xyz.kd5ujc.scarab.storage.{LeiosEb, LeiosStore, LeiosStoreError}

/** What the node does with a LeiosFetch answer (`processLeiosBlock` and `processLeiosBlockTxs`,
  * `LeiosDemoLogic.hs` 849-1172 and 1395-1588 of ouroboros-consensus 2416493b), for the answers of
  * a peer, `ReceivedBlockFrom` and `ReceivedTxsFrom`, and for an endorser block the node forged,
  * `ForgedBlock` and `ForgedTxs` ([[forgedBlock]], [[forgedTxs]]). A peer's answers are checked,
  * accounted for in the fetch logic's state, written to `store` and claimed once written. `store`
  * is the node's notifying store ([[LeiosAcquired.notifying]]), so a write that completes a closure
  * grows the endorser blocks chain selection may use.
  *
  * An answer that is not what was asked for raises [[NetworkError.LeiosFetchInvalidReply]], which
  * ends the connection. The store's failure is the node's own: it goes to `fatal`, which stops the
  * node, as the reference's single writer is `link` ed to it, and the answer is never charged to
  * the peer. Each write and what is claimed once it is durable run on a fiber the node owns,
  * `settle`, as the reference settles on a thread `link` ed to the node (`link =<< async settle`):
  * the answers after it on the connection do not wait for the write, the peer answers for a body
  * once its write is queued, and a write that lands is claimed even when the peer is gone, the
  * write and its claim being one uncancelable step.
  *
  * A write is queued only on a free slot of the writer's queue, `slots`, which the node's answers
  * share, and an answer waits for one (`submitJob`, `Impl/SQLite/WriteQueue.hs` 129-178 at
  * 2416493b): while the store is slow the connection's receiver waits, so no more answers are
  * admitted, and at most [[LeiosFetchArrival.WriterQueueDepth]] writes are held. A write's slot is
  * free again once it lands. A body's write is two (its point and its body), the transactions' one.
  * The wait is cancelable and takes nothing; once a write has its slot it is the node's.
  *
  * A novel body's transactions the node's mempool holds are taken from it (`pullFromMempool`,
  * [[LeiosMempoolPull]]) and written after the body, as the reference ingests them
  * (`processLeiosBlockTxs` of `MempoolTxs`, `LeiosDemoLogic.hs` 1151-1161 and 1428-1437); only the
  * others become jobs to fetch, so a closure the mempool holds whole is acquired with its body.
  *
  * A transactions answer ingests and retires only the jobs of the pool its request was picked from
  * (an Improvement over the reference, [[xyz.kd5ujc.scarab.consensus.leios.LeiosJobPoolId]]): an
  * answer that arrives after its endorser block was pruned and acquired again with another pool
  * takes nothing and retires nothing of the new pool, and still releases what the peer had in
  * flight.
  *
  * Each answer updates the node's transaction cache ([[LeiosTxCache]]) where the reference's does.
  * Not mirrored (#54): no cross-EB fills (the cache's transaction locations), no egress credit
  * refilled at a claim (`LeiosFreshBytes`), and no arrival telemetry.
  */
final class LeiosFetchArrival[F[_], P](
    shared: LeiosFetchShared[F, P],
    store: LeiosStore[F],
    env: LeiosFetchStaticEnv,
    fatal: LeiosStoreError => F[Unit],
    settle: F[Unit] => F[Unit],
    slots: Semaphore[F],
    pull: LeiosMempoolPull[F]
)(using F: Async[F]):
  /** `processLeiosBlock` of a body `peer` sent for `request`. */
  def block(peer: P, request: LeiosFetchRequest.Block, txs: Vector[(LeiosTxHash, Word32)])(using
      Raise[F, NetworkError]
  ): F[Unit] =
    val point = request.point
    val h = point.hash
    val encoded = LeiosEb.encode(txs)
    // `encodeLeiosEbSize`, a `BytesSize`
    val sent = BytesSize.wrap(encoded.size.toLong)
    // a `Word64` sum of at most `maxTxsPerEb` 32-bit sizes, so it never wraps
    val closure = txs.foldLeft(0L)(_ + _._2.toLong)
    val tooBig = Option.when(closure > request.maxEbTxsSize.toLong)(request.maxEbTxsSize)
    val wrongLength = sent != request.offeredSize
    val received = LeiosEb.hashOf(txs)
    val duplicates = txs
      .groupMapReduce(_._1.bytes)(_ => 1)(_ + _)
      .collect { case (hash, n) if n > 1 => hash }
      .toVector
      .sortWith(_.compareTo(_) < 0)
      .map(LeiosTxHash.fromBytes)
    for
      _ <- fault(LeiosFetchFault.BodyHashMismatch(point, received))
        .unlessA(received.bytes == h.bytes)
      _ <- fault(LeiosFetchFault.DuplicateTxHashes(point, duplicates)).whenA(duplicates.nonEmpty)
      eb <- NonEmptyVector
        .fromVector(txs)
        .toRight(LeiosEb.Error.Empty)
        .flatMap(LeiosEb.from)
        .fold(e => fault[LeiosEb](LeiosFetchFault.BodyNotStorable(point, e)), F.pure)
      // the novelty check, under the state: a body too old, already held or over its closure's
      // bound is not written; one over its bound is held with nothing to fetch. A novel body's
      // transactions are looked up in the mempool there, as the reference pulls them under its lock
      // (`LeiosDemoLogic.hs` 1026-1040)
      persist <- shared.outstanding.evalModify { o =>
        val tooOld = point.slot.toBigInt < o.outstandingPrunedSlot.toBigInt
        val novel = !o.ebState.get(h).exists(_.hasBody)
        val cleaned = LeiosFetchLogic.refundEbRequest(peer, h, request.offeredSize, o)
        if tooOld || !novel || tooBig.isDefined then
          F.pure(
            (
              tooBig.fold(cleaned)(_ => cleaned.acquireEbBody(h, LeiosJobPool.empty)),
              Option.empty[LeiosFetchArrival.Pulled]
            )
          )
        else
          // the body's entries in the transaction cache, then the pull (`insertBody` and
          // `pullFromMempool`, `LeiosDemoLogic.hs` 1003-1033)
          shared.txCache.insertBody(h, eb) *>
            pull(LeiosFetchArrival.txSet(eb)).map(p =>
              (cleaned, Some(LeiosFetchArrival.pulled(eb, p)))
            )
      }
      _ <- shared.wake
      // the state is cleaned before the write is queued, as the reference's is (`LeiosDemoLogic.hs`
      // 1063-1098): the peer's bytes for the body are released even while the queue is full
      _ <- persist.traverse_(p =>
        submitted(p.writes)(landed => written(acquire(point, eb, sent, p, landed)))
      )
      // the body is accounted for: now the peer answers for what it offered
      _ <- fault(
        LeiosFetchFault.BlockWrongSize(point, word32(request.offeredSize), word32(sent))
      ).whenA(wrongLength)
      _ <- tooBig.traverse_(bound =>
        fault[Unit](LeiosFetchFault.ClosureTooBig(point, closure, word32(bound)))
      )
    yield ()

  /** `processLeiosBlockTxs` of the transactions `peer` sent for `request`. */
  def blockTxs(peer: P, request: LeiosFetchRequest.BlockTxs, txs: Vector[Bytes])(using
      Raise[F, NetworkError]
  ): F[Unit] =
    val point = request.point
    val jobs = request.jobs.toSortedMap
    // the union of the jobs' offsets, ascending: the order the peer decoded the bitmaps into
    val offsets = jobs.values.foldLeft(SortedSet.empty[Int])(_ ++ _.offsets.toSortedSet)
    val batchBytes = BytesSize.wrap(txs.foldLeft(0L)(_ + _.size.toLong))
    def job(cause: LeiosTxsReplyFault): LeiosFetchFault = cause match
      case LeiosTxsReplyFault.JobCountMismatch(j) =>
        LeiosFetchFault.JobCountMismatch(point, j.toInt)
      case LeiosTxsReplyFault.JobByteSizeMismatch(j) =>
        LeiosFetchFault.JobByteSizeMismatch(point, j.toInt)
      case LeiosTxsReplyFault.JobRootHashMismatch(j) =>
        LeiosFetchFault.JobRootHashMismatch(point, j.toInt)
    for
      _ <- fault(LeiosFetchFault.TxsCountMismatch(point, txs.size, offsets.size))
        .whenA(txs.sizeIs != offsets.size)
      aligned = SortedMap.from(offsets.toVector.zip(txs))
      // the cheap checks of every job the request covers, in the jobs' order, the first failure
      _ <- jobs.toVector.traverse_((i, j) =>
        LeiosFetchLogic
          .checkJobSize(aligned, LeiosJobId(i), j)
          .fold(e => fault[Unit](job(e)), F.pure)
      )
      // only the jobs still pending in the pool that issued the request are hashed and ingested;
      // the state is read once
      before <- shared.outstanding.get
      pending = LeiosFetchLogic.pendingJobs(request, before)
      ingested <- pending.toVector.flatTraverse((i, j) =>
        LeiosFetchLogic.ingestJob(aligned, LeiosJobId(i), j).fold(e => fault(job(e)), F.pure)
      )
      // the jobs are retired once the write is durable (`onDurable`), from the pool that issued
      // the request if it is still the one held then; the peer's bytes are released once the write
      // is queued. A job is retired even where the store dropped some of its transactions, as the
      // reference's is: its writer drops one whose bytes are not the size the body declares, or
      // that start with a zero byte (`sqlInsertTxs`, `Impl/SQLite/Insert.hs` 287-297 at 2416493b),
      // and `onDurable` (`LeiosDemoLogic.hs` 1575) retires the request's jobs whatever was dropped. A reply that passes the checks above holds at each offset the
      // bytes of the hash the body lists there, so only the body's own declarations make a drop
      _ <- submitted(1)(landed =>
        written(
          (store.writeTxs(point, ingested.map((off, _, bytes) => off -> bytes)) *> landed)
            .flatMap(_ =>
              shared.outstanding.update(LeiosFetchLogic.completeTxRequest(peer, request, _))
            )
        )
      )
      // the peer's transactions in the transaction cache, unapplied, once their write is queued
      // (`withLockedInsertUnappliedTx` of `ReceivedTxsFrom`, `LeiosDemoLogic.hs` 1561-1564)
      _ <- shared.txCache.insertUnapplied(ingested.map(_._2))
      _ <- shared.outstanding.update(LeiosFetchLogic.refundTxRequest(peer, batchBytes, _))
      _ <- shared.wake
    yield ()

  /** `processLeiosBlock` of a body the node forged (`ForgedBlock`, `LeiosDemoLogic.hs` 849-1172):
    * not checked, as the node made it; no request refunded, for no peer sent it. A body neither
    * held nor older than what the fetch logic was pruned to is written as an answer's is
    * ([[acquire]]): its point at the size of its encoding, the body with no fills, and the body
    * held with the jobs of every transaction, as the forge pulls none from the pool
    * (`noMempoolPull`). The writes take their slots of the writer's queue and are awaited, not
    * settled on a fiber: the forge must not announce what it has not stored (`ForgedBlock{} ->
    * settle`, 1144-1146). The store's failure is raised to the forger.
    */
  def forgedBlock(point: EbPoint, eb: LeiosEb)(using Raise[F, LeiosStoreError]): F[Unit] =
    for
      persist <- shared.outstanding.modify { o =>
        val tooOld = point.slot.toBigInt < o.outstandingPrunedSlot.toBigInt
        val novel = !o.ebState.get(point.hash).exists(_.hasBody)
        (o, !tooOld && novel)
      }
      _ <- shared.wake
      // the body's entries in the transaction cache (`insertBody`, `LeiosDemoLogic.hs` 1003-1012)
      _ <- shared.txCache.insertBody(point.hash, eb).whenA(persist)
      // the forge holds the whole closure, which it writes next: nothing is pulled (`noMempoolPull`)
      nothing = LeiosFetchArrival.Pulled(LeiosFetchArrival.txSet(eb), SortedMap.empty, None)
      _ <- awaited(nothing.writes)(acquire(point, eb, BytesSize.wrap(eb.encodedSize), nothing, _))
        .whenA(persist)
    yield ()

  /** `processLeiosBlockTxs` of the closure the node forged (`ForgedTxs`, `LeiosDemoLogic.hs`
    * 1412-1426 and 1530-1546): every transaction written at its offset in the body, on one slot of
    * the writer's queue and awaited; no job is retired, as the forge holds none.
    */
  def forgedTxs(point: EbPoint, closure: NonEmptyVector[(LeiosTxHash, Bytes)])(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    val txs = closure.toVector.zipWithIndex.map { case ((_, bytes), offset) => offset -> bytes }
    awaited(1)(landed => store.writeTxs(point, txs) *> landed) *> shared.wake

  /** `jobs` writes on slots of the writer's queue, waiting while it is full, then run here: each
    * slot is given back as its write lands. A store's failure stops the node, so the slots of a
    * write that failed are not given back.
    */
  private def awaited(jobs: Long)(writes: F[Unit] => F[Unit]): F[Unit] =
    F.uncancelable(poll => poll(slots.acquireN(jobs)) *> writes(slots.release))

  /** The body's writes and its claim, in the order of the reference's single writer: the point at
    * the size the body was sent at, the body with no fills, the body then held with the jobs of the
    * transactions neither the mempool nor the write holds (`settle`, `LeiosDemoLogic.hs`
    * 1099-1139), and last the transactions taken from the mempool, written at their offsets
    * (`ingestAcquiredTxs` of `MempoolTxs`, 1428-1437, which holds no job to retire), recorded in
    * the transaction cache as applied under the mempool's parity (`insertApplied`, 1570-1572) after
    * the write, as the reference records them once their write is queued. That last write is the
    * one that completes a closure the mempool held whole, so its completion reaches the store's
    * subscribers, the voting thread among them, with the body; a vote that its completion wakes
    * before the cache's update finds the transactions untagged and validates them in full, as the
    * reference's does when its writer lands the write first (its TODO at 1560 would record them
    * before the write).
    */
  private def acquire(
      point: EbPoint,
      eb: LeiosEb,
      sent: BytesSize,
      pulled: LeiosFetchArrival.Pulled,
      landed: F[Unit]
  )(using Raise[F, LeiosStoreError]): F[Unit] =
    for
      _ <- store.writeEbPoint(point, word32(sent)) *> landed
      body <- store.writeEbBody(point, eb, Vector.empty) <* landed
      filled = body.filled.toSet
      pool = LeiosJobPool.of(
        env.maxJobBytesSize,
        env.maxJobTxCount,
        pulled.missed.filterNot((offset, _) => filled(offset))
      )
      _ <- shared.outstanding.update(_.acquireEbBody(point.hash, pool))
      _ <- shared.wake
      // the cache's update follows the write, as the reference's follows its queueing
      // (`LeiosDemoLogic.hs` 1527-1572)
      _ <- (store.writeTxs(point, pulled.ingest.view.mapValues(_._2).toVector) *> landed *>
        shared.wake *>
        pulled.parity.traverse_(
          shared.txCache.insertApplied(pulled.ingest.values.map(_._1), _)
        ))
        .whenA(pulled.ingest.nonEmpty)
    yield ()

  /** `jobs` writes queued, each on a slot of the writer's queue, waiting while it is full
    * (`submitJob`): a cancelled wait takes no slot. Once queued, `settle` runs on the node's fiber,
    * given what frees one slot, which it runs as each write lands.
    */
  private def submitted(jobs: Long)(settling: F[Unit] => F[Unit]): F[Unit] =
    F.uncancelable(poll => poll(slots.acquireN(jobs)) *> settle(settling(slots.release)))

  /** A write and its claim as one uncancelable step; the store's failure stops the node, outside
    * the mask, and this fiber ends with it.
    */
  private def written(write: Raise[F, LeiosStoreError] ?=> F[Unit]): F[Unit] =
    F.uncancelable(_ =>
      Handle
        .allow[LeiosStoreError](write.map(_.asRight[LeiosStoreError]))
        .rescue(e => F.pure(e.asLeft[Unit]))
    ).flatMap(_.fold(fatal, F.pure))

  private def fault[A](f: LeiosFetchFault)(using Raise[F, NetworkError]): F[A] =
    NetworkError.LeiosFetchInvalidReply(f).raise[F, A]

  private def word32(size: BytesSize): Word32 = Word32.wrap(size.toLong)

object LeiosFetchArrival:
  /** What the mempool gave a novel body: the transactions still to fetch, by offset, the hashes and
    * bytes of the others at theirs, and the parity they were validated under (`missedBoth` and the
    * ingest of `mbMempoolTxs`, `LeiosDemoLogic.hs` 1026-1040).
    */
  final case class Pulled(
      missed: SortedMap[Int, (LeiosTxHash, BytesSize)],
      ingest: SortedMap[Int, (LeiosTxHash, Bytes)],
      parity: Option[ProtVerParity]
  ):
    /** The writes it takes on the writer's queue: the point, the body and, if any, the mempool's
      * transactions.
      */
    def writes: Long = if ingest.isEmpty then 2 else 3

  /** The body's transactions by offset (`fullTxSet`, `LeiosDemoLogic.hs` 1028-1029). */
  def txSet(eb: LeiosEb): SortedMap[Int, (LeiosTxHash, BytesSize)] =
    SortedMap.from(eb.transactions.toVector.zipWithIndex.map { case ((hash, size), offset) =>
      offset -> (hash, BytesSize.wrap(size.toLong))
    })

  /** The pull's result for `eb`: the offsets it found, with their bytes, are ingested; the rest is
    * missed (`IntMap.mapMaybeWithKey` over `fullTxSet` less `notInMempool`, 1034-1037).
    */
  def pulled(eb: LeiosEb, found: LeiosMempoolPull.Found): Pulled =
    Pulled(
      found.missed,
      txSet(eb).flatMap { case (offset, (hash, _)) =>
        Option
          .unless(found.missed.contains(offset))(hash)
          .flatMap(found.hits.get)
          .map(offset -> (hash, _))
      },
      found.parity
    )

  /** `writerQueueDepth` (`Impl/SQLite/WriteQueue.hs` 214-231 at ouroboros-consensus 2416493b): a
    * slot for each of 20 upstream peers' fetch clients, the forge and 4 maintenance schedulers, and
    * 2 to spare.
    */
  val WriterQueueDepth: Long = 27

/** `processLeiosBlock` 's pull from the node's mempool (`pullFromMempool`, `LeiosDemoLogic.hs`
  * 864-870 of ouroboros-consensus 2416493b): of a body's transactions by offset, those still to
  * fetch from peers, the bytes, by hash, of those the mempool holds, and the parity of the protocol
  * version they were validated under.
  */
trait LeiosMempoolPull[F[_]]:
  def apply(misses: SortedMap[Int, (LeiosTxHash, BytesSize)]): F[LeiosMempoolPull.Found]

object LeiosMempoolPull:
  /** What a pull found: the misses still to fetch, the hits' bytes by hash, and their parity. */
  final case class Found(
      missed: SortedMap[Int, (LeiosTxHash, BytesSize)],
      hits: Map[LeiosTxHash, Bytes],
      parity: Option[ProtVerParity]
  )

  /** `mkMempoolPull` (`LeiosDemoLogic.hs` 1186-1200): the pull from the index `readIndex` reads,
    * the mempool's `getLeiosTxIndex` (`Ouroboros/Consensus/Network/NodeToNode.hs` 950-952), read
    * once a pull.
    */
  def of[F[_]: Functor](readIndex: F[LeiosTxIndex]): LeiosMempoolPull[F] =
    misses =>
      readIndex.map { index =>
        val (missed, hits) = LeiosFetchLogic.mempoolPull(index.txs, misses)
        Found(missed, hits, index.parity)
      }

  /** `noMempoolPull` (`LeiosDemoLogic.hs` 1174-1178): every miss kept, nothing found. */
  def none[F[_]: Applicative]: LeiosMempoolPull[F] =
    misses => Applicative[F].pure(Found(misses, Map.empty, None))

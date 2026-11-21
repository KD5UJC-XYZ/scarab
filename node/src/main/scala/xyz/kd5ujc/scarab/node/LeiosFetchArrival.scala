// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.Async
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  BytesSize,
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
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosFetchFault, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32
import xyz.kd5ujc.scarab.storage.{LeiosEb, LeiosStore, LeiosStoreError}

/** What the node does with a LeiosFetch answer (`processLeiosBlock` and `processLeiosBlockTxs`,
  * `LeiosDemoLogic.hs` 849-1172 and 1395-1588 of ouroboros-consensus 2416493b), for the answers of
  * a peer, `ReceivedBlockFrom` and `ReceivedTxsFrom`: checked, accounted for in the fetch logic's
  * state, written to `store` and claimed once written. `store` is the node's notifying store
  * ([[LeiosAcquired.notifying]]), so a write that completes a closure grows the endorser blocks
  * chain selection may use.
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
  * Not mirrored (#54): no cross-EB fills (the `LeiosTxCache`), no transactions pulled from the
  * mempool, no egress credit refilled at a claim (`LeiosFreshBytes`), and no arrival telemetry.
  */
final class LeiosFetchArrival[F[_], P](
    shared: LeiosFetchShared[F, P],
    store: LeiosStore[F],
    env: LeiosFetchStaticEnv,
    fatal: LeiosStoreError => F[Unit],
    settle: F[Unit] => F[Unit]
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
      // bound is not written; one over its bound is held with nothing to fetch
      persist <- shared.outstanding.modify { o =>
        val tooOld = point.slot.toBigInt < o.outstandingPrunedSlot.toBigInt
        val novel = !o.ebState.get(h).exists(_.hasBody)
        val cleaned = LeiosFetchLogic.refundEbRequest(peer, h, request.offeredSize, o)
        if tooOld || !novel || tooBig.isDefined then
          (tooBig.fold(cleaned)(_ => cleaned.acquireEbBody(h, LeiosJobPool.empty)), false)
        else (cleaned, true)
      }
      _ <- shared.wake
      _ <- settle(written(acquire(point, eb, sent))).whenA(persist)
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
      // only the jobs still pending are hashed and ingested; the state is read once
      before <- shared.outstanding.get
      pending = before.ebState.get(point.hash) match
        case Some(EbState(_, _, EbFetchState.BodyAcquired(pool))) => pool.restrictToPending(jobs)
        case Some(EbState(_, _, EbFetchState.NoBody | EbFetchState.BodyImminent)) | None =>
          SortedMap.empty[Int, xyz.kd5ujc.scarab.consensus.leios.LeiosJob]
      ingested <- pending.toVector.flatTraverse((i, j) =>
        LeiosFetchLogic.ingestJob(aligned, LeiosJobId(i), j).fold(e => fault(job(e)), F.pure)
      )
      // the jobs are retired once the write is durable (`onDurable`); the peer's bytes are
      // released once the write is queued
      _ <- settle(
        written(
          store
            .writeTxs(point, ingested.map((off, _, bytes) => off -> bytes))
            .flatMap(_ =>
              shared.outstanding.update(LeiosFetchLogic.completeTxRequest(peer, request, _))
            )
        )
      )
      _ <- shared.outstanding.update(LeiosFetchLogic.refundTxRequest(peer, batchBytes, _))
      _ <- shared.wake
    yield ()

  /** The body's write and its claim: the point at the size the body was sent at, the body with no
    * fills, and then the body held with the jobs of the transactions the write still misses.
    */
  private def acquire(point: EbPoint, eb: LeiosEb, sent: BytesSize)(using
      Raise[F, LeiosStoreError]
  ): F[Unit] =
    for
      _ <- store.writeEbPoint(point, word32(sent))
      body <- store.writeEbBody(point, eb, Vector.empty)
      filled = body.filled.toSet
      misses = SortedMap.from(
        eb.transactions.toVector.zipWithIndex.collect {
          case ((hash, size), offset) if !filled(offset) =>
            offset -> (hash, BytesSize.wrap(size.toLong))
        }
      )
      pool = LeiosJobPool.of(env.maxJobBytesSize, env.maxJobTxCount, misses)
      _ <- shared.outstanding.update(_.acquireEbBody(point.hash, pool))
      _ <- shared.wake
    yield ()

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

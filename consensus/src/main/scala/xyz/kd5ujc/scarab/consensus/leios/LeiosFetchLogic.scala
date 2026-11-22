// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.Order
import cats.data.{NonEmptyMap, NonEmptySet, NonEmptyVector}
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

import LeiosOrders.given

/** Why an answer to a transactions request does not keep a job's commitment (the `Left` s of
  * `checkJobSize` and `ingestJob`, `LeiosDemoLogic.hs` 1356-1391 of ouroboros-consensus 2416493b,
  * which the reference raises with `error` and which cost the peer its connection).
  */
enum LeiosTxsReplyFault extends DomainError:
  /** The answer holds another number of the job's transactions than the job covers. */
  case JobCountMismatch(job: LeiosJobId)

  /** The job's transactions are of another total size than the job's. */
  case JobByteSizeMismatch(job: LeiosJobId)

  /** The hashes of the job's transactions are not the ones the job commits to. */
  case JobRootHashMismatch(job: LeiosJobId)

/** What one pass of the decision gives (`leiosFetchLogicIteration`'s result): the new state, the
  * requests to send to each peer and each peer's offers to drop.
  */
final case class LeiosFetchDecision[P](
    outstanding: LeiosOutstanding[P],
    requests: SortedMap[P, NonEmptyVector[LeiosFetchRequest]],
    drops: SortedMap[P, NonEmptySet[EbPoint]]
)

/** The Leios fetch decision and its bookkeeping, pure (`LeiosDemoLogic.hs` 347-720 and 1204-1393 at
  * ouroboros-consensus 2416493b).
  */
object LeiosFetchLogic:
  private val Word64Modulus = BigInt(1) << 64

  /** One pass over the peers' offers (`leiosFetchLogicIteration`, `LeiosDemoLogic.hs` 347-393):
    * each peer in turn, in the peers' order, is given the bodies and closure jobs of the endorser
    * blocks some election fetches, freshest first, until its budget is spent.
    * `forecastMaxEbTxsSize` is the closure's bound forecast from the immutable tip to a slot, for
    * an offer that brought none. `currentSlot` is unknown while the node syncs, and then the oldest
    * are fetched first.
    */
  def iteration[P: Order, E](
      env: LeiosFetchStaticEnv,
      forecastMaxEbTxsSize: Word64 => Either[E, BytesSize],
      currentSlot: Option[Word64],
      offerings: collection.Map[P, collection.Map[EbPoint, PeerOffer]],
      bigLedgerPeers: Map[P, IsBigLedgerPeer],
      outstanding: LeiosOutstanding[P]
  ): LeiosFetchDecision[P] =
    given Ordering[P] = Order[P].toOrdering
    // the peers in their `Order`, as the reference's `Map (PeerId pid)` folds them, whatever order
    // the given map keeps
    offerings.toVector
      .sortBy(_._1)
      .foldLeft(LeiosFetchDecision(outstanding, SortedMap.empty, SortedMap.empty)) {
        case (LeiosFetchDecision(acc, reqs, drops), (peer, offers)) =>
          val isBig = bigLedgerPeers.getOrElse(peer, IsBigLedgerPeer.NotBigLedgerPeer)
          val walked = assignPeer(env, forecastMaxEbTxsSize, currentSlot, isBig, peer, offers, acc)
          LeiosFetchDecision(
            walked.outstanding,
            NonEmptyVector.fromVector(walked.requests).fold(reqs)(reqs.updated(peer, _)),
            NonEmptySet.fromSet(walked.drops).fold(drops)(drops.updated(peer, _))
          )
      }

  /** `peerBudget` (`LeiosDemoLogic.hs` 402-410): the peer's cap less what it has in flight. */
  def peerBudget[P](
      env: LeiosFetchStaticEnv,
      isBig: IsBigLedgerPeer,
      acc: LeiosOutstanding[P],
      peer: P
  ): Long =
    val cap = isBig match
      case IsBigLedgerPeer.BigLedgerPeer    => env.maxRequestedBytesSizePerBigLedgerPeer
      case IsBigLedgerPeer.NotBigLedgerPeer => env.maxRequestedBytesSizePerPeer
    cap.toLong - acc.requestedBytesSizePerPeer.getOrElse(peer, BytesSize.zero).toLong

  /** `fetchPriorityTiers` (`LeiosDemoLogic.hs` 429-440): the high tier oldest first, the low tier
    * freshest first. While the slot is unknown every offer is high; else the low tier is the prefix
    * of points whose slot `a` has `a + l < s`, the sum a `Word64` modulo 2^64^ as the reference's.
    * That predicate is antitone in the slot except past `2^64 - l`, which no validated header's
    * slot reaches, so the prefix is what `Map.spanAntitone` gives.
    */
  def priorityTiers[V](
      currentSlot: Option[Word64],
      l: BigInt,
      offers: collection.Map[EbPoint, V]
  ): (Vector[(EbPoint, V)], Vector[(EbPoint, V)]) =
    // in the reference's point order (`LeiosPoint`'s `Ord`), whatever order the given map keeps
    val ascending = offers.toVector.sortBy(_._1)(using ebPointOrdering)
    currentSlot match
      case None => (ascending, Vector.empty)
      case Some(s) =>
        val (stale, fresh) =
          ascending.span((p, _) => (p.slot.toBigInt + l).mod(Word64Modulus) < s.toBigInt)
        (fresh, stale.reverse)

  /** A peer's walk: the state, its requests in the order decided and the offers to drop. */
  private final case class Walk[P](
      outstanding: LeiosOutstanding[P],
      requests: Vector[LeiosFetchRequest],
      drops: SortedSet[EbPoint]
  )

  /** `assignPeer` (`LeiosDemoLogic.hs` 452-526): the peer's offers of focused endorser blocks, the
    * high tier and then the low, each classified until the peer's budget is spent. An offer of an
    * endorser block no election focuses is skipped, not dropped.
    */
  private def assignPeer[P: Order, E](
      env: LeiosFetchStaticEnv,
      forecast: Word64 => Either[E, BytesSize],
      currentSlot: Option[Word64],
      isBig: IsBigLedgerPeer,
      peer: P,
      offers: collection.Map[EbPoint, PeerOffer],
      acc: LeiosOutstanding[P]
  ): Walk[P] =
    val focused = offers.filter((point, _) => acc.isFocused(point.hash))
    val (high, low) = priorityTiers(currentSlot, env.fetchPriorityWindowSlots, focused)
    def go(st: Walk[P], tier: Vector[(EbPoint, PeerOffer)]): Walk[P] =
      tier
        .foldLeft[Either[Walk[P], Walk[P]]](Right(st)) {
          case (Left(done), _) => Left(done)
          case (Right(walk), (point, offer)) =>
            if peerBudget(env, isBig, walk.outstanding, peer) <= 0 then Left(walk)
            else Right(classify(env, forecast, isBig, peer, point, offer, walk))
        }
        .merge
    go(go(Walk(acc, Vector.empty, SortedSet.empty), high), low)

  private def classify[P: Order, E](
      env: LeiosFetchStaticEnv,
      forecast: Word64 => Either[E, BytesSize],
      isBig: IsBigLedgerPeer,
      peer: P,
      point: EbPoint,
      offer: PeerOffer,
      walk: Walk[P]
  ): Walk[P] =
    val h = point.hash
    def pruneThisOffer = walk.copy(drops = walk.drops + point)
    walk.outstanding.ebState.get(h) match
      case None => pruneThisOffer
      case Some(EbState(slot, _, fetch)) =>
        (fetch, offer.closure) match
          case (EbFetchState.BodyImminent, _) => pruneThisOffer
          case (EbFetchState.NoBody, WhetherTxsClosureOffered.NotOffered) =>
            val (acc, reqs) =
              assignBody(forecast, peer, h, slot, offer, walk.outstanding, walk.requests)
            Walk(acc, reqs, walk.drops + point)
          case (EbFetchState.NoBody, WhetherTxsClosureOffered.Offered) =>
            val (acc, reqs) =
              assignBody(forecast, peer, h, slot, offer, walk.outstanding, walk.requests)
            Walk(acc, reqs, walk.drops)
          case (EbFetchState.BodyAcquired(_, _), WhetherTxsClosureOffered.NotOffered) =>
            pruneThisOffer
          case (EbFetchState.BodyAcquired(pool, _), WhetherTxsClosureOffered.Offered) =>
            if pool.isEmpty then pruneThisOffer
            else
              val (acc, reqs, exhausted) =
                assignClosure(env, isBig, peer, h, walk.outstanding, walk.requests)
              Walk(acc, reqs, if exhausted then walk.drops + point else walk.drops)

  /** `assignBody` (`LeiosDemoLogic.hs` 537-580): ask the peer for the body at the size it offered
    * it, unless it has been asked already, with the closure's bound from the offer or else the
    * forecast; nothing while the forecast cannot reach the slot or the peer offered only the
    * closure. The point asked for is at the tracked slot, the greatest it was announced at.
    */
  private def assignBody[P: Order, E](
      forecast: Word64 => Either[E, BytesSize],
      peer: P,
      h: EbHash,
      slot: Word64,
      offer: PeerOffer,
      acc: LeiosOutstanding[P],
      reqs: Vector[LeiosFetchRequest]
  ): (LeiosOutstanding[P], Vector[LeiosFetchRequest]) =
    if acc.requestedEbPeers.get(h).exists(_.contains(peer)) then (acc, reqs)
    else
      val bound = offer.maxEbTxsSize.fold(forecast(slot))(Right(_))
      (bound, offer.offeredBody) match
        case (Left(_), _) => (acc, reqs)
        case (_, None)    => (acc, reqs)
        case (Right(maxEbTxsSize), Some(size)) =>
          val next = acc.copy(
            requestedEbPeers = acc.requestedEbPeers.updated(
              h,
              acc.requestedEbPeers.get(h).fold(NonEmptySet.one(peer))(_.add(peer))
            ),
            requestedBytesSizePerPeer = acc.requestedBytesSizePerPeer.updated(
              peer,
              acc.requestedBytesSizePerPeer.getOrElse(peer, BytesSize.zero) + size
            )
          )
          (next, reqs :+ LeiosFetchRequest.Block(EbPoint(slot, h), size, maxEbTxsSize))

  /** `assignClosure` (`LeiosDemoLogic.hs` 589-644): give the peer the least-requested jobs it does
    * not have in flight until its budget is spent or none is left, and whether none is left. The
    * requests name the pool the jobs were picked from, and only the peer's jobs in flight from that
    * pool are excluded: what it owes a pool since replaced does not hide the new pool's jobs of the
    * same numbers (an Improvement, [[LeiosJobPoolId]]).
    */
  private def assignClosure[P](
      env: LeiosFetchStaticEnv,
      isBig: IsBigLedgerPeer,
      peer: P,
      h: EbHash,
      acc: LeiosOutstanding[P],
      reqs: Vector[LeiosFetchRequest]
  ): (LeiosOutstanding[P], Vector[LeiosFetchRequest], Boolean) =
    acc.ebState.get(h) match
      case Some(EbState(slot, onset, EbFetchState.BodyAcquired(pool, id))) =>
        val key = LeiosJobPoolOf(h, id)
        val inflight = acc.requestedJobsPerPeer
          .get(peer)
          .flatMap(_.get(key))
          .fold(SortedSet.empty[Int])(_.toSortedSet)
        val (picked, pool2, prng2, exhausted) =
          pickJobs(acc.prng, inflight, pool, peerBudget(env, isBig, acc, peer))
        NonEmptyVector.fromVector(picked) match
          case None => (acc, reqs, exhausted)
          case Some(nePicked) =>
            val ids = NonEmptySet.of(nePicked.head._1.toInt, nePicked.tail.map(_._1.toInt)*)
            val perPeer =
              acc.requestedJobsPerPeer.getOrElse(peer, Map.empty[LeiosJobPoolOf, NonEmptySet[Int]])
            val next = acc.copy(
              ebState =
                acc.ebState.updated(h, EbState(slot, onset, EbFetchState.BodyAcquired(pool2, id))),
              requestedJobsPerPeer = acc.requestedJobsPerPeer.updated(
                peer,
                perPeer.updated(key, perPeer.get(key).fold(ids)(_ | ids))
              ),
              requestedBytesSizePerPeer = acc.requestedBytesSizePerPeer.updated(
                peer,
                acc.requestedBytesSizePerPeer.getOrElse(peer, BytesSize.zero) +
                  nePicked.foldLeft(BytesSize.zero)(_ + _._2.bytes)
              ),
              prng = prng2
            )
            (next, reqs ++ batchTxsRequests(env, EbPoint(slot, h), id, nePicked), exhausted)
      case Some(EbState(_, _, EbFetchState.NoBody | EbFetchState.BodyImminent)) | None =>
        (acc, reqs, false)

  /** `pickJobs` (`LeiosDemoLogic.hs` 652-671): least-requested jobs not excluded until the budget
    * is spent or none is left, and whether none is left. The generator is the one returned by the
    * last draw.
    */
  def pickJobs(
      prng: StdGen,
      inflight: SortedSet[Int],
      pool: LeiosJobPool,
      budget: Long
  ): (Vector[(LeiosJobId, LeiosJob)], LeiosJobPool, StdGen, Boolean) =
    @scala.annotation.tailrec
    def go(
        g: StdGen,
        excluded: SortedSet[Int],
        p: LeiosJobPool,
        left: Long,
        acc: Vector[(LeiosJobId, LeiosJob)]
    ): (Vector[(LeiosJobId, LeiosJob)], LeiosJobPool, StdGen, Boolean) =
      if left <= 0 then (acc, p, g, false)
      else
        p.pickLeastRequestedExcept(g, excluded) match
          case None => (acc, p, g, true)
          case Some((id, job, p2, g2)) =>
            go(g2, excluded + id.toInt, p2, left - job.bytes.toLong, acc :+ (id -> job))
    go(prng, inflight, pool, budget, Vector.empty)

  /** `batchTxsRequests` (`LeiosDemoLogic.hs` 678-699): the picked jobs, in order, cut into requests
    * of at most `maxRequestBytesSize`; a job above it forms a request of its own. Each request
    * names `pool`, the pool the jobs were picked from.
    */
  def batchTxsRequests(
      env: LeiosFetchStaticEnv,
      point: EbPoint,
      pool: LeiosJobPoolId,
      picked: NonEmptyVector[(LeiosJobId, LeiosJob)]
  ): Vector[LeiosFetchRequest] =
    val cap = env.maxRequestBytesSize.toLong
    def flush(batch: NonEmptyVector[(LeiosJobId, LeiosJob)]): LeiosFetchRequest =
      LeiosFetchRequest.BlockTxs(
        point,
        pool,
        NonEmptyMap.of(
          batch.head._1.toInt -> batch.head._2,
          batch.tail.map((i, j) => i.toInt -> j)*
        )
      )
    val (done, current, _) =
      picked.tail.foldLeft(
        (
          Vector.empty[LeiosFetchRequest],
          NonEmptyVector.one(picked.head),
          picked.head._2.bytes.toLong
        )
      ) { case ((done, batch, bytes), j) =>
        if bytes + j._2.bytes.toLong > cap then
          (done :+ flush(batch), NonEmptyVector.one(j), j._2.bytes.toLong)
        else (done, batch :+ j, bytes + j._2.bytes.toLong)
      }
    done :+ flush(current)

  /** `offsetsToBitmap` (`LeiosDemoLogic.hs` 702-712): the offsets as the wire's windows, ascending,
    * each a 64-bit mask whose most significant bit is the window's first offset. The window is
    * `fromIntegral` into a `Word16`.
    */
  def offsetsToBitmap(offsets: SortedSet[Int]): Vector[(Int, Long)] =
    offsets
      .foldLeft(SortedMap.empty[Int, Long]) { (chunks, off) =>
        val q = Math.floorDiv(off, 64)
        val r = Math.floorMod(off, 64)
        chunks.updated(q, chunks.getOrElse(q, 0L) | (1L << (63 - r)))
      }
      .toVector
      .map((q, bm) => (q & 0xffff, bm))

  /** `popLeftmostOffset` (`LeiosDemoLogic.hs` 312-329): the most significant set bit's offset in
    * the window, and the mask without it.
    */
  def popLeftmostOffset(w: Long): Option[(Int, Long)] =
    if w == 0L then None
    else
      val zs = java.lang.Long.numberOfLeadingZeros(w)
      Some((zs, w & ~(1L << (63 - zs))))

  /** `bitmapOffsets` (`LeiosDemoLogic.hs` 1339-1346): the offsets of the wire's windows in their
    * order, the inverse of [[offsetsToBitmap]].
    */
  def bitmapOffsets(bitmaps: Vector[(Int, Long)]): Vector[Int] =
    bitmaps.flatMap { (idx, bitmap) =>
      Vector.unfold(bitmap)(popLeftmostOffset).map(i => 64 * idx + i)
    }

  /** `removePeerFromOutstanding` (`LeiosDemoLogic.hs` 1209-1243): the peer is gone: its budget, its
    * body requests and its jobs in flight are released, so others may be asked for them. Its jobs
    * in flight are released in the pool they were picked from only: a pool since replaced has no
    * request of the peer's for its jobs of the same numbers (an Improvement, [[LeiosJobPoolId]]).
    */
  def removePeer[P](peer: P, o: LeiosOutstanding[P]): LeiosOutstanding[P] =
    val held = o.requestedJobsPerPeer.getOrElse(peer, Map.empty[LeiosJobPoolOf, NonEmptySet[Int]])
    o.copy(
      requestedBytesSizePerPeer = o.requestedBytesSizePerPeer - peer,
      requestedEbPeers = o.requestedEbPeers.flatMap((h, peers) =>
        NonEmptySet.fromSet(peers.toSortedSet - peer).map(h -> _)
      ),
      requestedJobsPerPeer = o.requestedJobsPerPeer - peer,
      ebState = held.foldLeft(o.ebState) { case (states, (LeiosJobPoolOf(h, pool), ids)) =>
        states
          .get(h)
          .fold(states)(st =>
            states.updated(
              h,
              onPoolOf(pool, st)(p => ids.foldLeft(p)((q, i) => q.unpick(LeiosJobId(i))))
            )
          )
      }
    )

  /** `refundEbRequest` (`LeiosDemoLogic.hs` 1245-1272): a body arrived; the peer's bytes for it and
    * its request are released, if the peer is still tracked (a peer removed in bulk was refunded
    * already).
    */
  def refundEbRequest[P](
      peer: P,
      h: EbHash,
      size: BytesSize,
      o: LeiosOutstanding[P]
  ): LeiosOutstanding[P] =
    if !o.requestedBytesSizePerPeer.contains(peer) then o
    else
      o.copy(
        requestedBytesSizePerPeer = refunded(o.requestedBytesSizePerPeer, peer, size),
        requestedEbPeers = o.requestedEbPeers.get(h).fold(o.requestedEbPeers) { peers =>
          NonEmptySet
            .fromSet(peers.toSortedSet - peer)
            .fold(o.requestedEbPeers - h)(o.requestedEbPeers.updated(h, _))
        }
      )

  /** `refundTxRequest` (`LeiosDemoLogic.hs` 1274-1291): transactions arrived; their bytes are
    * released, if the peer is still tracked.
    */
  def refundTxRequest[P](peer: P, size: BytesSize, o: LeiosOutstanding[P]): LeiosOutstanding[P] =
    if !o.requestedBytesSizePerPeer.contains(peer) then o
    else o.copy(requestedBytesSizePerPeer = refunded(o.requestedBytesSizePerPeer, peer, size))

  /** `completeTxRequest` (`LeiosDemoLogic.hs` 1293-1333): the request's jobs are fetched, gone from
    * the pool for every peer and from the peer's jobs in flight. Only the pool that issued the
    * request loses them, and only the peer's jobs in flight from that pool are released: one
    * installed since, after a prune, numbers other jobs with the same ids, and keeps them and the
    * peer's requests for them (an Improvement, [[LeiosJobPoolId]]).
    */
  def completeTxRequest[P](
      peer: P,
      request: LeiosFetchRequest.BlockTxs,
      o: LeiosOutstanding[P]
  ): LeiosOutstanding[P] =
    val ids = request.jobs.keys
    val h = request.point.hash
    val key = LeiosJobPoolOf(h, request.pool)
    o.copy(
      ebState = o.ebState.get(h).fold(o.ebState) { st =>
        o.ebState.updated(
          h,
          onPoolOf(request.pool, st)(p => ids.foldLeft(p)((q, i) => q.complete(LeiosJobId(i))))
        )
      },
      requestedJobsPerPeer =
        o.requestedJobsPerPeer.get(peer).fold(o.requestedJobsPerPeer) { perPeer =>
          val inner = perPeer.get(key).fold(perPeer) { held =>
            NonEmptySet
              .fromSet(held.toSortedSet -- ids.toSortedSet)
              .fold(perPeer - key)(perPeer.updated(key, _))
          }
          if inner.isEmpty then o.requestedJobsPerPeer - peer
          else o.requestedJobsPerPeer.updated(peer, inner)
        }
    )

  /** The `pendingJobs` of `processLeiosBlockTxs` (`LeiosDemoLogic.hs` 1469-1478): the request's
    * jobs still in the pool, which alone are hashed and ingested; none where the body is not held.
    * Only the pool that issued the request answers for them: where another is held, installed after
    * a prune, none is pending (an Improvement, [[LeiosJobPoolId]]).
    */
  def pendingJobs[P](
      request: LeiosFetchRequest.BlockTxs,
      o: LeiosOutstanding[P]
  ): SortedMap[Int, LeiosJob] =
    o.ebState.get(request.point.hash).map(_.fetch) match
      case Some(EbFetchState.BodyAcquired(jobs, pool)) if pool == request.pool =>
        jobs.restrictToPending(request.jobs.toSortedMap)
      case Some(EbFetchState.BodyAcquired(_, _) | EbFetchState.NoBody | EbFetchState.BodyImminent) |
          None =>
        SortedMap.empty

  /** `checkJobSize` (`LeiosDemoLogic.hs` 1349-1369): the answer's transactions for the job, by
    * offset, are as many as the job covers and their sizes sum to the job's, the sum a `Word32` as
    * the reference's. No hashing, so that a redundant answer costs no CPU.
    */
  def checkJobSize(
      aligned: SortedMap[Int, Bytes],
      id: LeiosJobId,
      job: LeiosJob
  ): Either[LeiosTxsReplyFault, Unit] =
    val sub = job.offsets.toSortedSet.toVector.flatMap(aligned.get)
    if sub.sizeIs != job.offsets.length then Left(LeiosTxsReplyFault.JobCountMismatch(id))
    else if BytesSize.wrap(sub.iterator.map(_.size.toLong).sum) != job.bytes then
      Left(LeiosTxsReplyFault.JobByteSizeMismatch(id))
    else Right(())

  /** `ingestJob` (`LeiosDemoLogic.hs` 1371-1391): the job's transactions, ascending by offset, each
    * with its hash, if their hashes are the ones the job commits to. Only the first answer for a
    * job is hashed.
    */
  def ingestJob(
      aligned: SortedMap[Int, Bytes],
      id: LeiosJobId,
      job: LeiosJob
  ): Either[LeiosTxsReplyFault, Vector[(Int, LeiosTxHash, Bytes)]] =
    val hashed = job.offsets.toSortedSet.toVector.flatMap(off =>
      aligned.get(off).map(bs => (off, LeiosTxHash.fromBytes(Blake2b.hash256.hash(bs)), bs))
    )
    if JobRootHash.of(hashed.map(_._2)) != job.root then
      Left(LeiosTxsReplyFault.JobRootHashMismatch(id))
    else Right(hashed)

  /** The pull of `mkMempoolPull` (`LeiosDemoLogic.hs` 1186-1200), from the mempool's index read
    * once: the misses whose hash the index does not hold, still to fetch from peers, and the bytes
    * of those it does, by hash. The index holds only transactions it has bytes for, so every miss
    * it holds is a hit.
    */
  def mempoolPull(
      index: collection.Map[LeiosTxHash, Bytes],
      misses: SortedMap[Int, (LeiosTxHash, BytesSize)]
  ): (SortedMap[Int, (LeiosTxHash, BytesSize)], Map[LeiosTxHash, Bytes]) =
    val hits = misses.valuesIterator.flatMap((h, _) => index.get(h).map(h -> _)).toMap
    (misses.filterNot((_, missed) => hits.contains(missed._1)), hits)

  private def onPool(st: EbState)(f: LeiosJobPool => LeiosJobPool): EbState = st.fetch match
    case EbFetchState.BodyAcquired(jobs, pool) =>
      st.copy(fetch = EbFetchState.BodyAcquired(f(jobs), pool))
    case EbFetchState.NoBody | EbFetchState.BodyImminent => st

  /** [[onPool]] where the held pool is `issuer`; any other is left as it is. */
  private def onPoolOf(issuer: LeiosJobPoolId, st: EbState)(
      f: LeiosJobPool => LeiosJobPool
  ): EbState = st.fetch match
    case EbFetchState.BodyAcquired(_, pool) if pool == issuer => onPool(st)(f)
    case EbFetchState.BodyAcquired(_, _) | EbFetchState.NoBody | EbFetchState.BodyImminent => st

  private def refunded[P](
      m: SortedMap[P, BytesSize],
      peer: P,
      size: BytesSize
  ): SortedMap[P, BytesSize] =
    m.get(peer).fold(m) { x =>
      val left = x - size
      if left == BytesSize.zero then m - peer else m.updated(peer, left)
    }

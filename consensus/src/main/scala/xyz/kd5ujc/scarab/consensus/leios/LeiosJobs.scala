// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.data.NonEmptySet
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.LeiosTxHash

/** A job's commitment to the transactions it covers: the Blake2b-256 of their hashes concatenated
  * in ascending offset order (`JobRootHash`, `jobRootHashOfTxHashes`, `LeiosDemoTypes/LeiosJobs.hs`
  * 48-57 of ouroboros-consensus 2416493b). It lets an answer be checked against the job without the
  * endorser block's body.
  */
opaque type JobRootHash = Bytes

object JobRootHash:
  def of(hashes: Iterable[LeiosTxHash]): JobRootHash =
    Blake2b.hash256.hash(Bytes.wrap(hashes.iterator.flatMap(_.bytes.toArray).toArray))

  given CanEqual[JobRootHash, JobRootHash] = CanEqual.derived

/** The id of a job in its pool: 0-based, stable for the pool's lifetime (`LeiosJobId`,
  * `LeiosJobs.hs` 72-73 at 2416493b). Another pool of the same endorser block numbers its own jobs
  * from 0 again, so an id means a job only together with its pool's [[LeiosJobPoolId]].
  */
opaque type LeiosJobId = Int

object LeiosJobId:
  def apply(id: Int): LeiosJobId = id
  extension (id: LeiosJobId) def toInt: Int = id

/** Which pool of jobs the fetch logic installed for an endorser block's body: each pool takes the
  * next, from a count that no prune resets ([[LeiosOutstanding.nextJobPool]]). A transactions
  * request names the pool its jobs were picked from, and its reply ingests and retires only that
  * pool's jobs.
  *
  * An Improvement over the reference, approved by the owner on 2026-11-22
  * (https://github.com/KD5UJC-XYZ/scarab/issues/54#issuecomment-7076220269): the reference's
  * request carries the jobs' ids alone (`LeiosBlockTxsRequest`, `LeiosDemoTypes.hs` 343-353 at
  * 2416493b). Once an endorser block is pruned while a request for it is in flight, announced again
  * and its body acquired with other mempool hits, the new pool numbers other offsets from 0, and
  * the late reply ingests the old job's offsets and retires the new pool's jobs of the same
  * numbers, whose transactions are then never fetched (`restrictToPending` and `completeTxRequest`,
  * `LeiosDemoLogic.hs` 1469-1478 and 1299-1333).
  */
opaque type LeiosJobPoolId = BigInt

object LeiosJobPoolId:
  /** The id the first pool takes. */
  val first: LeiosJobPoolId = BigInt(0)

  extension (id: LeiosJobPoolId) private[leios] def next: LeiosJobPoolId = id + 1

  given CanEqual[LeiosJobPoolId, LeiosJobPoolId] = CanEqual.derived

/** A unit of transaction-fetch work: the body offsets one `MsgLeiosBlockTxsRequest` fetches, the
  * size on the wire of those transactions and the commitment the answer is checked against
  * (`LeiosJob`, `LeiosJobs.hs` 63-68 at 2416493b). A job covers at least one transaction.
  */
final case class LeiosJob(offsets: NonEmptySet[Int], bytes: BytesSize, root: JobRootHash)
    derives CanEqual

/** A job with how many peers have a request for it in flight (`LeiosJobState`, `LeiosJobs.hs`
  * 80-81).
  */
final case class LeiosJobState(job: LeiosJob, multiplicity: Int) derives CanEqual

/** The jobs of one acquired endorser block not yet fetched, with the reverse index by multiplicity
  * that gives a least-requested job at once (`LeiosJobPool`, `LeiosJobs.hs` 89-95 at 2416493b). The
  * index is the exact inverse of the multiplicities: every job is in the bucket of its own.
  */
final case class LeiosJobPool private (
    jobs: SortedMap[Int, LeiosJobState],
    byMultiplicity: SortedMap[Int, NonEmptySet[Int]]
) derives CanEqual:
  import LeiosJobPool.*

  /** `nullLeiosJobPool`: no job is left, the whole closure is fetched. */
  def isEmpty: Boolean = jobs.isEmpty

  /** `lookupJob`. */
  def lookup(id: LeiosJobId): Option[LeiosJob] = jobs.get(id.toInt).map(_.job)

  /** `restrictToPending`: the entries of `m` whose job is still in the pool. */
  def restrictToPending[A](m: SortedMap[Int, A]): SortedMap[Int, A] =
    m.filter((k, _) => jobs.contains(k))

  /** `pickLeastRequestedJobExcept` (`LeiosJobs.hs` 160-205 at 2416493b): a job of the lowest
    * multiplicity that is not `excluded`, drawn uniformly from those with the generator, with one
    * more request recorded for it; nothing, and no draw, if every job is excluded.
    */
  def pickLeastRequestedExcept(
      prng: StdGen,
      excluded: SortedSet[Int]
  ): Option[(LeiosJobId, LeiosJob, LeiosJobPool, StdGen)] =
    byMultiplicity.iterator
      .map((m, bucket) => (m, bucket.toSortedSet.diff(excluded)))
      .find((_, eligible) => eligible.nonEmpty)
      .flatMap { (m, eligible) =>
        val (i, next) = prng.uniformR(0L, eligible.size.toLong - 1L)
        eligible.iterator.drop(i.toInt).nextOption().flatMap { id =>
          jobs.get(id).map { state =>
            val pool = LeiosJobPool(
              jobs.updated(id, LeiosJobState(state.job, m + 1)),
              bucketInsert(m + 1, id, bucketDelete(m, id, byMultiplicity))
            )
            (LeiosJobId(id), state.job, pool, next)
          }
        }
      }

  /** `unpickJob` (`LeiosJobs.hs` 210-233): one request fewer in flight for the job, floored at
    * zero; nothing if the job has been completed.
    */
  def unpick(id: LeiosJobId): LeiosJobPool =
    jobs.get(id.toInt).fold(this) { state =>
      val m = state.multiplicity
      val decremented = math.max(0, m - 1)
      LeiosJobPool(
        jobs.updated(id.toInt, LeiosJobState(state.job, decremented)),
        bucketInsert(decremented, id.toInt, bucketDelete(m, id.toInt, byMultiplicity))
      )
    }

  /** `completeJob` (`LeiosJobs.hs` 236-249): the job is fetched, gone from the pool. */
  def complete(id: LeiosJobId): LeiosJobPool =
    jobs.get(id.toInt).fold(this) { state =>
      LeiosJobPool(jobs - id.toInt, bucketDelete(state.multiplicity, id.toInt, byMultiplicity))
    }

object LeiosJobPool:
  /** `emptyLeiosJobPool`. */
  val empty: LeiosJobPool = LeiosJobPool(SortedMap.empty, SortedMap.empty)

  /** `mkLeiosJobPool` (`LeiosJobs.hs` 105-129 at 2416493b): the missing transactions, by offset,
    * partitioned greedily in offset order. A job grows while it holds fewer than `maxJobTxCount`
    * transactions and the next one keeps it within `maxJobBytes` (the sum a `Word32`, modulo 2^32^
    * as the reference's), and always holds at least one. The misses are walked in ascending numeric
    * offset order, as `IntMap.toAscList` walks them, whatever order the given map keeps.
    */
  def of(
      maxJobBytes: BytesSize,
      maxJobTxCount: Int,
      misses: collection.Map[Int, (LeiosTxHash, BytesSize)]
  ): LeiosJobPool =
    final case class Growing(
        offsets: NonEmptySet[Int],
        bytes: BytesSize,
        hashes: Vector[LeiosTxHash]
    )
    def flush(g: Growing): LeiosJob = LeiosJob(g.offsets, g.bytes, JobRootHash.of(g.hashes))
    val (done, last) =
      misses.toVector
        .sortBy(_._1)(using Ordering.Int)
        .foldLeft((Vector.empty[LeiosJob], Option.empty[Growing])) {
          case ((done, None), (off, (h, sz))) =>
            (done, Some(Growing(NonEmptySet.one(off), sz, Vector(h))))
          case ((done, Some(g)), (off, (h, sz))) =>
            if g.hashes.sizeIs < maxJobTxCount && (g.bytes + sz) <= maxJobBytes then
              (done, Some(Growing(g.offsets.add(off), g.bytes + sz, g.hashes :+ h)))
            else (done :+ flush(g), Some(Growing(NonEmptySet.one(off), sz, Vector(h))))
        }
    val all = done ++ last.map(flush)
    val ids = all.indices
    LeiosJobPool(
      SortedMap.from(ids.map(i => i -> LeiosJobState(all(i), 0))),
      NonEmptySet
        .fromSet(SortedSet.from(ids))
        .fold(SortedMap.empty[Int, NonEmptySet[Int]])(s => SortedMap(0 -> s))
    )

  private def bucketDelete(
      m: Int,
      id: Int,
      buckets: SortedMap[Int, NonEmptySet[Int]]
  ): SortedMap[Int, NonEmptySet[Int]] =
    buckets.get(m).fold(buckets) { bucket =>
      NonEmptySet
        .fromSet(bucket.toSortedSet - id)
        .fold(buckets - m)(rest => buckets.updated(m, rest))
    }

  private def bucketInsert(
      m: Int,
      id: Int,
      buckets: SortedMap[Int, NonEmptySet[Int]]
  ): SortedMap[Int, NonEmptySet[Int]] =
    buckets.updated(m, buckets.get(m).fold(NonEmptySet.one(id))(_.add(id)))

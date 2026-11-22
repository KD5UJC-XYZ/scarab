// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.RbHash
import xyz.kd5ujc.scarab.protocol.{EbHash, LeiosTxHash}

/** The LeiosTxCache's index (`LeiosTxCache/Reference.hs` at ouroboros-consensus 2416493b): what a
  * lookup answers in each state of a transaction, and its pruning, by count and by slot, cascading
  * from an announcement through its body to the transactions no other held body references.
  */
class LeiosTxCacheIndexSuite extends FunSuite:
  /** A body: the transactions it lists. */
  private final case class Body(txs: Vector[LeiosTxHash])
  private given ReferencesTxsByHash[Body] = _.txs

  private type Index = LeiosTxCacheIndex[Unit, ProtVerParity, Body]

  private def bytes(tag: Int, n: Int): Bytes = Bytes(
    Vector.fill(32)(((tag * 31 + n) & 0xff).toByte)
  )
  private def rb(n: Int): RbHash = RbHash.fromBytes(bytes(1, n)).getOrElse(fail("32 bytes"))
  private def eb(n: Int): EbHash = EbHash.fromBytes(bytes(2, n))
  private def tx(n: Int): LeiosTxHash = LeiosTxHash.fromBytes(bytes(3, n))

  private val empty: Index = LeiosTxCacheIndex.empty

  private def announced(i: Index, slot: Int, n: Int): Index =
    i.insertAnnouncement(BigInt(slot), rb(n), eb(n))._1

  test("protVerParity: the major version modulo 2") {
    assertEquals(ProtVerParity.of(12), ProtVerParity.Even)
    assertEquals(ProtVerParity.of(11), ProtVerParity.Odd)
  }

  test("a transaction is tracked only once a held body references it, and answers by its state") {
    val i0 = announced(empty, 10, 1)
    // no body yet: an applied insert is a no-op, and the lookup misses
    assertEquals(i0.insertAppliedTx(tx(1), ProtVerParity.Even).lookupTx(tx(1)), None)
    val (i1, summary) = i0.insertBody(eb(1), Body(Vector(tx(1), tx(2))))
    assertEquals(summary, Some(InsertBodySummary(2, 0, 0, 0, 2)))
    // referenced but not inserted: a miss
    assertEquals(i1.lookupTx(tx(1)), None)
    val (i2, prior) = i1.insertUnappliedTx(tx(1), ())
    assertEquals(prior, TxArrivalPrior.WasNotYetInserted)
    assertEquals(i2.lookupTx(tx(1)), Some(Left(())))
    val i3 = i2.insertAppliedTx(tx(1), ProtVerParity.Even).insertAppliedTx(tx(2), ProtVerParity.Odd)
    assertEquals(i3.lookupTx(tx(1)), Some(Right(ProtVerParity.Even)))
    assertEquals(i3.lookupTx(tx(2)), Some(Right(ProtVerParity.Odd)))
    assertEquals(i3.insertUnappliedTx(tx(1), ())._2, TxArrivalPrior.WasAlreadyHeld)
    assertEquals(empty.insertUnappliedTx(tx(9), ())._2, TxArrivalPrior.WasUntracked)
    // a body without an announcement, or held already, is not inserted
    assertEquals(i3.insertBody(eb(7), Body(Vector(tx(7))))._2, None)
    assertEquals(i3.insertBody(eb(1), Body(Vector(tx(1))))._2, None)
    // a second body over the same transactions counts what they were
    val i4 = announced(i3, 11, 2)
    assertEquals(
      i4.insertBody(eb(2), Body(Vector(tx(1), tx(3))))._2,
      Some(InsertBodySummary(2, 1, 1, 1, 3))
    )
  }

  test(
    "past maxAnnouncementCount the oldest announcement is evicted, cascading to its transactions"
  ) {
    val n = LeiosTxCacheIndex.MaxAnnouncementCount
    // announcement k at slot k, body k listing its own transaction k and the shared transaction 0
    val full = (0 until n).foldLeft(empty) { (i, k) =>
      announced(i, k, k)
        .insertBody(eb(k), Body(Vector(tx(k + 1), tx(0))))
        ._1
        .insertAppliedTx(tx(k + 1), ProtVerParity.Even)
        .insertAppliedTx(tx(0), ProtVerParity.Even)
    }
    assertEquals(full.announcementCount, n)
    assertEquals(full.lookupTx(tx(1)), Some(Right(ProtVerParity.Even)))
    val (next, ebs, txs) = full.insertAnnouncement(BigInt(n), rb(n), eb(n))
    assertEquals(next.announcementCount, n)
    // the least slot's announcement went, its body and its own transaction with it
    assertEquals(ebs, Set(eb(0)))
    assertEquals(txs, Set(tx(1)))
    assertEquals(next.lookupTx(tx(1)), None)
    assertEquals(next.lookupBody(eb(0)), None)
    // the shared transaction is still referenced by the other bodies
    assertEquals(next.lookupTx(tx(0)), Some(Right(ProtVerParity.Even)))
    assertEquals(next.lookupTx(tx(2)), Some(Right(ProtVerParity.Even)))
  }

  test("evictOlderThan prunes the announcements strictly older, and refuses older ones after") {
    val i = (1 to 4).foldLeft(empty)((acc, k) =>
      announced(acc, k * 10, k).insertBody(eb(k), Body(Vector(tx(k))))._1
    )
    val (pruned, ebs, txs) = i.evictOlderThan(BigInt(30))
    assertEquals(ebs, Set(eb(1), eb(2)))
    assertEquals(txs, Set(tx(1), tx(2)))
    assertEquals(pruned.announcementCount, 2)
    assertEquals(pruned.prunedSlot, BigInt(30))
    // an announcement older than the boundary is a no-op; the boundary never moves back
    assertEquals(announced(pruned, 20, 9).announcementCount, 2)
    assertEquals(pruned.evictOlderThan(BigInt(5))._1.prunedSlot, BigInt(30))
    assertEquals(announced(pruned, 30, 9).announcementCount, 3)
  }

  test("two announcements of one body keep it until both are evicted") {
    val i = announced(announced(empty, 10, 1), 11, 1)
      .insertBody(eb(1), Body(Vector(tx(1))))
      ._1
      .insertAppliedTx(tx(1), ProtVerParity.Odd)
    val (once, ebs1, _) = i.evictOlderThan(BigInt(11))
    assertEquals(ebs1, Set.empty)
    assertEquals(once.lookupTx(tx(1)), Some(Right(ProtVerParity.Odd)))
    val (twice, ebs2, txs2) = once.evictOlderThan(BigInt(12))
    assertEquals((ebs2, txs2), (Set(eb(1)), Set(tx(1))))
    assertEquals(twice.lookupTx(tx(1)), None)
  }

  /** `n` distinct transaction hashes with one `hashCode`: `Bytes.hashCode` is the polynomial of
    * base 31 over the signed bytes, so a pair of adjacent bytes `(k, 124 - 31 k)` contributes the
    * same for every `k` in `0..7`; sixteen pairs give `8^16` such hashes.
    */
  private def colliding(n: Int): Vector[LeiosTxHash] =
    Vector.tabulate(n) { i =>
      val digits = Vector.tabulate(16)(p => (i >> (3 * p)) & 7)
      LeiosTxHash.fromBytes(
        Bytes(digits.flatMap(k => Vector(k.toByte, (124 - 31 * k).toByte)))
      )
    }

  /** An ordering of hashes by their bytes that counts its comparisons in `count`. */
  private def counting[H](
      count: java.util.concurrent.atomic.AtomicLong,
      bytes: H => Bytes
  ): Ordering[H] =
    (a, b) =>
      count.incrementAndGet()
      bytes(a).compareTo(bytes(b))

  test(
    "references a producer chose with one hashCode: insertion, lookup and eviction stay bounded"
  ) {
    val n = 4096
    val refs = colliding(n)
    assertEquals(refs.map(_.bytes.hashCode).distinct.size, 1)
    assertEquals(refs.distinct.size, n)
    val comparisons = new java.util.concurrent.atomic.AtomicLong(0)
    val start: Index =
      LeiosTxCacheIndex.ordered(counting(comparisons, _.bytes), counting(comparisons, _.bytes))
    val (held, summary) = announced(start, 10, 1).insertBody(eb(1), Body(refs))
    assertEquals(summary.map(_.cacheTxCount), Some(n))
    val tagged = refs.foldLeft(held)(_.insertAppliedTx(_, ProtVerParity.Even))
    assertEquals(refs.map(tagged.lookupTx).distinct, Vector(Some(Right(ProtVerParity.Even))))
    val (evicted, ebs, txs) = tagged.evictOlderThan(BigInt(11))
    assertEquals(ebs.toVector, Vector(eb(1)))
    assertEquals(txs.size, n)
    assertEquals(evicted.txState.size, 0)
    // eight map operations a reference over the four passes (insert: two lookups and an update;
    // tag: a lookup and an update; look up; evict: a lookup and a removal), each at most the depth
    // of a red-black tree, 2 log2 n + 2 comparisons: 8 n (2 log2 n + 2), about 0.9 million for
    // 4,096, where keys of one hash in a hash map cost O(n^2), some 8 million
    val log2 = 32 - Integer.numberOfLeadingZeros(n)
    val bound = 8L * n * (2L * log2 + 2)
    assert(comparisons.get <= bound, clue((comparisons.get, bound)))
    // and the maps are the ordered ones: every reference was placed by comparison, none hashed
    assert(comparisons.get >= 4L * n, clue(comparisons.get))
  }

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

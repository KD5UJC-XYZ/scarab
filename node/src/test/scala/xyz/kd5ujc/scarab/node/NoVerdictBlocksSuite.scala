// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockNoVerdict, NoVerdictRetry}
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.ledger.{
  ChainError,
  LedgerFailure,
  ProviderDefect,
  ScriptLocation,
  VerificationDefect
}
import xyz.kd5ujc.scarab.ledger.state.VocabError
import xyz.kd5ujc.scarab.plutus.{BackendFailure, EvalFailure, ExUnits}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The schedule of a block without a verdict, and which causes are judged again (issue 693). */
class NoVerdictBlocksSuite extends FunSuite:
  import NoVerdictBlocks.{Entries, Schedule}

  private def hash(n: Int): Bytes = Bytes.wrap(Array.fill(32)(n.toByte))
  private val point = ChainSync.Point.Block(ChainSync.UInt64.saturating(10L), hash(9))
  private val passing = BlockNoVerdict.Defect(new OutOfMemoryError("Java heap space"))

  private def evaluation(why: BackendFailure): BlockNoVerdict =
    BlockNoVerdict.Evaluation(
      point,
      LedgerFailure.Unavailable(
        hash(1),
        ScriptLocation.Phase1(hash(2)),
        EvalFailure.Unsupported(why)
      )
    )

  test("the wait is 1 s, doubled after each attempt, at most 30 s") {
    assertEquals(
      (1 to 8).map(NoVerdictBlocks.delay).toVector,
      Vector(1, 2, 4, 8, 16, 30, 30, 30).map(_.seconds)
    )
    assertEquals(NoVerdictBlocks.delay(Int.MaxValue), 30.seconds)
  }

  test("whether a cause is judged again: a total match on the cause") {
    import BlockNoVerdict.Recovery.{Deterministic, Passing}
    val cases = List(
      evaluation(BackendFailure.Defect(new OutOfMemoryError("heap"))) -> Passing,
      evaluation(
        BackendFailure.Defect(new java.util.concurrent.RejectedExecutionException("pool"))
      ) -> Passing,
      evaluation(BackendFailure.NoResult) -> Passing,
      evaluation(BackendFailure.StackExhausted) -> Deterministic,
      evaluation(BackendFailure.BudgetOutOfRange(ExUnits(BigInt(2).pow(64), 0))) -> Deterministic,
      evaluation(
        BackendFailure.BuiltinUnavailable(
          xyz.kd5ujc.scarab.uplc.DefaultFun.Bls12_381_G1_hashToGroup
        )
      ) -> Deterministic,
      evaluation(
        BackendFailure.CostingUnmirrored(xyz.kd5ujc.scarab.uplc.DefaultFun.Bls12_381_G1_hashToGroup)
      ) -> Deterministic,
      BlockNoVerdict.Verification(
        VerificationDefect(
          point,
          VerificationDefect.Site.Header,
          ProviderDefect.VrfVerifier(Vrf.Result.InternalFailure(new IllegalStateException("x")))
        )
      ) -> Passing,
      BlockNoVerdict.StateOutOfRange(
        ChainError.StateOutOfRange(
          BigInt(10),
          VocabError.OutOfRange(VocabError.Kind.CompactCoin, BigInt(-1))
        )
      ) -> Deterministic,
      passing -> Passing
    )
    cases.foreach((cause, expected) => assertEquals(cause.recovery, expected, cause.message))
  }

  test("attempts count on from the earlier ones; the latest cause decides the schedule") {
    val (first, (n1, r1)) = Entries.empty.recorded(hash(1), BigInt(5), passing, 10.seconds)
    val (second, (n2, r2)) = first.recorded(hash(1), BigInt(5), passing, 12.seconds)
    val (third, (n3, r3)) =
      second.recorded(hash(1), BigInt(5), evaluation(BackendFailure.StackExhausted), 20.seconds)
    assertEquals((n1, r1), (1, NoVerdictRetry.After(1.second)))
    assertEquals((n2, r2), (2, NoVerdictRetry.After(2.seconds)))
    assertEquals((n3, r3), (3, NoVerdictRetry.Never))
    assertEquals(first.byHash.get(hash(1)).map(_.schedule), Some(Schedule.At(11.seconds)))
    assertEquals(second.byHash.get(hash(1)).map(_.schedule), Some(Schedule.At(14.seconds)))
    assertEquals(third.byHash.get(hash(1)).map(_.schedule), Some(Schedule.Once))
    assertEquals(third.nextAt, None)
  }

  /** No forest: every block stands alone. */
  private val alone: Bytes => Option[Bytes] = _ => None

  test("one due block is released at a time, the one due first; the rest stay excluded") {
    val entries = (1 to 5).foldLeft(Entries.empty)((e, n) =>
      e.recorded(hash(n), BigInt(100 - n), passing, (10 - n).seconds)._1
    )
    // all due at 20 s; hash(5) is due first (at 6 s)
    val released = entries.released(20.seconds, alone)
    assertEquals(released.map(_.due), Some(hash(5)))
    assertEquals(released.map(_.entries.keySet), Some(Set(hash(5))))
    assertEquals(entries.excluded(released), (1 to 4).map(hash).toSet)
    // nothing is due before the first next attempt, and nothing is released then
    assertEquals(entries.released(5.seconds, alone), None)
    assertEquals(entries.excluded(None), (1 to 5).map(hash).toSet)
    // once judged again without a verdict, the released block is excluded again
    val (after, _) = entries.recorded(hash(5), BigInt(95), passing, 20.seconds)
    assertEquals(after.excluded(released), (1 to 5).map(hash).toSet)
    assertEquals(after.released(20.seconds, alone).map(_.due), Some(hash(4)))
  }

  test("a released block brings the withheld blocks of its chain, before and after it") {
    // a chain 1 <- 2 <- 3 <- 4 and a branch 2 <- 5; 2 and 4 are withheld, 6 on another chain
    val parents =
      Map(hash(2) -> hash(1), hash(3) -> hash(2), hash(4) -> hash(3), hash(5) -> hash(2))
    val entries = List(4 -> 0, 2 -> 5, 6 -> 7).foldLeft(Entries.empty) { case (e, (n, at)) =>
      e.recorded(hash(n), BigInt(n), passing, at.seconds)._1
    }
    // 4 is due first, and needs 2 before it
    val fromAfter = entries.released(2.seconds, parents.get)
    assertEquals(fromAfter.map(_.due), Some(hash(4)))
    assertEquals(fromAfter.map(_.entries.keySet), Some(Set(hash(4), hash(2))))
    assertEquals(entries.excluded(fromAfter), Set(hash(6)))
    // 2 is due first, and its candidate is preferred only with 4 after it
    val early = entries.recorded(hash(4), BigInt(4), passing, 10.seconds)._1
    val fromBefore = early.released(7.seconds, parents.get)
    assertEquals(fromBefore.map(_.due), Some(hash(2)))
    assertEquals(fromBefore.map(_.entries.keySet), Some(Set(hash(2), hash(4))))
  }

  test("a released block not reached waits its delay again, uncounted; an attempted one is kept") {
    val (entries, _) = Entries.empty.recorded(hash(1), BigInt(5), passing, 0.seconds)
    val released = entries.released(2.seconds, alone)
    assertEquals(released.map(_.due), Some(hash(1)))
    released.foreach { r =>
      val deferred = entries.deferred(r, 3.seconds)
      assertEquals(deferred.byHash.get(hash(1)).map(_.attempts), Some(1))
      assertEquals(deferred.byHash.get(hash(1)).map(_.schedule), Some(Schedule.At(4.seconds)))
      val (attempted, _) = entries.recorded(hash(1), BigInt(5), passing, 2.seconds)
      assertEquals(attempted.deferred(r, 3.seconds), attempted)
      assertEquals(entries.removed(List(hash(1))).deferred(r, 3.seconds).byHash, Map.empty)
    }
  }

  test("a verdict and pruning at the immutable cutoff end a schedule") {
    val entries = List(1 -> 5, 2 -> 6, 3 -> 7).foldLeft(Entries.empty) { case (e, (n, slot)) =>
      e.recorded(hash(n), BigInt(slot), passing, 0.seconds)._1
    }
    assertEquals(entries.removed(List(hash(2))).byHash.keySet, Set(hash(1), hash(3)))
    assertEquals(entries.pruned(BigInt(6)).byHash.keySet, Set(hash(2), hash(3)))
    // nothing removed is no change
    assertEquals(entries.removed(List(hash(4))).revision, entries.revision)
    assertEquals(entries.pruned(BigInt(0)).revision, entries.revision)
  }

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
        BackendFailure.BuiltinUnavailable(scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup)
      ) -> Deterministic,
      evaluation(
        BackendFailure.CostingUnmirrored(scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup)
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

  test("one due block is released at a time, the one due first; the rest stay excluded") {
    val entries = (1 to 5).foldLeft(Entries.empty)((e, n) =>
      e.recorded(hash(n), BigInt(100 - n), passing, (10 - n).seconds)._1
    )
    // all due at 20 s; hash(5) is due first (at 6 s)
    assertEquals(entries.due(20.seconds), Some(hash(5)))
    assertEquals(entries.excluded(20.seconds, Some(hash(5))), (1 to 4).map(hash).toSet)
    // nothing is due before the first next attempt, and nothing is released then
    assertEquals(entries.due(5.seconds), None)
    assertEquals(entries.excluded(5.seconds, Some(hash(5))), (1 to 5).map(hash).toSet)
    // once judged again without a verdict, the released block is excluded at the same time
    val (after, _) = entries.recorded(hash(5), BigInt(95), passing, 20.seconds)
    assertEquals(after.excluded(20.seconds, Some(hash(5))), (1 to 5).map(hash).toSet)
    assertEquals(after.due(20.seconds), Some(hash(4)))
  }

  test("a released block not reached waits its delay again, uncounted; an attempted one is kept") {
    val (entries, _) = Entries.empty.recorded(hash(1), BigInt(5), passing, 0.seconds)
    val deferred = entries.deferred(hash(1), 2.seconds, 3.seconds)
    assertEquals(deferred.byHash.get(hash(1)).map(_.attempts), Some(1))
    assertEquals(deferred.byHash.get(hash(1)).map(_.schedule), Some(Schedule.At(4.seconds)))
    val (attempted, _) = entries.recorded(hash(1), BigInt(5), passing, 2.seconds)
    assertEquals(attempted.deferred(hash(1), 2.seconds, 3.seconds), attempted)
    assertEquals(
      entries.removed(List(hash(1))).deferred(hash(1), 2.seconds, 3.seconds).byHash,
      Map.empty
    )
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

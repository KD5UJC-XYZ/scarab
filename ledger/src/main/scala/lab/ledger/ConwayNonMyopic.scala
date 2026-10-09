// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import scala.util.control.NonFatal

/** Restricted finite binary32 update algebra. No pow/log generator or runtime admission. */
object ConwayNonMyopic:
  val Profile = "conway-pv9-supplied-non-myopic-finite-v1"
  val Samples = 100
  val MaxPools = 4096
  private val MaxCoin = (BigInt(1) << 64) - 1
  private val Decay = java.lang.Float.intBitsToFloat(0x3f666666) // native Float 0.9

  final class Likelihood private[ConwayNonMyopic] (val rawBits: Vector[Int]):
    def hex: Vector[String] = rawBits.map(b => f"$b%08x")

  final class State private[ConwayNonMyopic] (
      val likelihoods: Map[Bytes, Likelihood],
      val rewardPot: BigInt,
      val id: Bytes
  ):
    def orderedPools: Vector[Bytes] = likelihoods.keys.toVector.sortBy(_.hex)

  final class Completed private[ConwayNonMyopic] (
      val frozenHistory: State,
      val expectedPoolDomain: Set[Bytes],
      val suppliedLikelihoods: Map[Bytes, Likelihood],
      val after: State
  ):
    val nativeParityValidated = false
    val likelihoodGenerationImplemented = false
    val published = false

  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def width(b: Bytes, n: Int) = b != null && b.value != null && b.size == n
  private def poolDomain(ps: Set[Bytes]): Unit =
    require(ps != null && ps.size <= MaxPools && ps.forall(width(_, 28)), "pool domain bounds")
  private def finite(f: Float): Float =
    require(java.lang.Float.isFinite(f), "nonfinite binary32 result unsupported")
    f
  private def float(b: Int): Float = java.lang.Float.intBitsToFloat(b)
  private def bits(f: Float): Int = java.lang.Float.floatToRawIntBits(finite(f))
  private def validateMap(m: Map[Bytes, Likelihood]): Unit =
    require(m != null && m.size <= MaxPools, "likelihood map bound")
    poolDomain(m.keySet)
    require(m.values.forall(_ != null), "null likelihood")

  /** Preserves signed zeros and raw stored representation; no implicit normalization. */
  def likelihood(rawBits: Vector[Int]): Either[String, Likelihood] = checked {
    require(rawBits != null && rawBits.size == Samples, "exactly 100 log weights required")
    require(
      rawBits.forall(b => java.lang.Float.isFinite(float(b))),
      "nonfinite log weight unsupported"
    )
    new Likelihood(rawBits)
  }

  def state(likelihoods: Map[Bytes, Likelihood], rewardPot: BigInt): Either[String, State] =
    checked {
      validateMap(likelihoods)
      require(rewardPot != null && rewardPot >= 0 && rewardPot <= MaxCoin, "reward pot bounds")
      val rows = likelihoods.toVector
        .sortBy(_._1.hex)
        .map((pool, weights) => pool.hex + ":" + weights.hex.mkString)
        .mkString("|")
      val id =
        Blake2b.hash256.hash(Bytes.fromArray(s"$Profile\n$rewardPot\n$rows".getBytes("UTF-8")))
      new State(likelihoods, rewardPot, id)
    }

  /** Native normalization subtracts the first minimum Float; it does not exponentiate. */
  def normalize(value: Likelihood): Either[String, Likelihood] = checked {
    require(value != null, "null likelihood")
    val fs = value.rawBits.map(float)
    val minimum = fs.tail.foldLeft(fs.head)((a, b) => if a <= b then a else b)
    new Likelihood(fs.map(f => bits(f - minimum)))
  }

  /** Native Eq compares normalized Float values, unlike raw storage identity. */
  def equivalentModuloOffset(a: Likelihood, b: Likelihood): Either[String, Boolean] = checked {
    val x = get(normalize(a)).rawBits
    val y = get(normalize(b)).rawBits
    x.zip(y).forall((i, j) => float(i) == float(j))
  }

  /** updateNonMyopic maps ONLY over the new domain. Old-only pools are dropped. Float multiply,
    * addition and subtraction are separate rounding operations; no fused operation. Inputs are
    * supplied log weights, not evidence that native leaderProbability generated them.
    */
  def completeSupplied(
      history: State,
      expectedHistoryId: Bytes,
      rewardPot: BigInt,
      expectedPoolDomain: Set[Bytes],
      supplied: Map[Bytes, Likelihood]
  ): Either[String, Completed] = checked {
    require(
      history != null && width(expectedHistoryId, 32) && history.id == expectedHistoryId,
      "frozen non-myopic history identity mismatch"
    )
    poolDomain(expectedPoolDomain)
    validateMap(supplied)
    require(
      supplied.keySet == expectedPoolDomain,
      "new likelihood domain must include every go pool"
    )
    val updated = supplied.toVector
      .sortBy(_._1.hex)
      .map { (pool, now) =>
        val old = history.likelihoods
          .get(pool)
          .map(_.rawBits)
          .getOrElse(Vector.fill(Samples)(0))
        val sums = old.zip(now.rawBits).map { (a, b) =>
          val decayed = finite(Decay * float(a))
          bits(decayed + float(b))
        }
        pool -> get(normalize(new Likelihood(sums)))
      }
      .toMap
    new Completed(history, expectedPoolDomain, supplied, get(state(updated, rewardPot)))
  }

  /** Native startStep likelihood generation is deliberately unavailable for nonempty go. Empty go
    * is exact for any supplied history/pot; it is not inferred from zero rewards.
    */
  def generateForFrozen(
      frozen: ConwayEpochBoundary.Frozen,
      expectedFrozenId: Bytes
  ): Either[String, Map[Bytes, Likelihood]] = checked {
    require(
      frozen != null && width(expectedFrozenId, 32) && frozen.id == expectedFrozenId,
      "frozen reward identity mismatch"
    )
    require(
      frozen.go.pools.isEmpty,
      "native binary64 pow/log likelihood generation is not yet JVM-conformant"
    )
    Map.empty
  }

  /** Binds the all-pool domain and reward pot to an existing checked allocation. */
  def completeFrozen(
      history: State,
      expectedHistoryId: Bytes,
      frozen: ConwayEpochBoundary.Frozen,
      expectedFrozenId: Bytes,
      allocation: ConwayRewardStart.Allocation,
      supplied: Map[Bytes, Likelihood]
  ): Either[String, Completed] = checked {
    require(
      frozen != null && width(expectedFrozenId, 32) && frozen.id == expectedFrozenId &&
        allocation != null && allocation.frozenId == frozen.id,
      "non-myopic allocation binding"
    )
    get(
      completeSupplied(
        history,
        expectedHistoryId,
        allocation.rewardPot,
        frozen.go.pools.keySet,
        supplied
      )
    )
  }

  /** Application REPLACES current non-myopic state with the completed frozen update. An absent old
    * reward update preserves current state. Caller still owns epoch/phase admission.
    */
  def applyAtBoundary(
      current: State,
      expectedCurrentId: Bytes,
      completed: Option[Completed]
  ): Either[String, State] = checked {
    require(
      current != null && width(expectedCurrentId, 32) && current.id == expectedCurrentId,
      "current non-myopic identity mismatch"
    )
    require(completed != null && completed.forall(_ != null), "invalid completion option")
    completed.fold(current)(_.after)
  }

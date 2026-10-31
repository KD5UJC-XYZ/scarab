// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.*

/** The timing constants of the governor's policy (`simplePeerSelectionPolicy`,
  * `Ouroboros/Network/Diffusion/Policies.hs` 99-124, and the failure delays of
  * `Governor/EstablishedPeers.hs` 450-466 and `Governor/Monitor.hs` 145-161), including
  * peer-sharing request budgets and retry delays used by [[PeerShare]].
  *
  * @param maxConnectionRetries
  *   Prior failures before another transient failure forgets the peer, unless
  *   [[Origin.unforgettable]] protects it (`policyMaxConnectionRetries`).
  * @param clearFailCountDelay
  *   How long a peer stays hot before its fail count is reset (`policyClearFailCountDelay`).
  * @param baseColdRetry
  *   The base of the retry delay after a failed connect: `5 * 2^min(failCount - 1, 5)` seconds plus
  *   a fuzz in (-2, 2) (`baseColdPeerRetryDiffTime`, `maxColdPeerRetryBackoff`).
  * @param repromoteErrorDelay
  *   The delay before a peer is promoted again after its activation failed (`repromoteErrorDelay`,
  *   `Policies.hs` 82), plus a fuzz in (-2, 2).
  * @param warmReconnectFuzz
  *   The fuzz added to the delay of a connection that went back to warm, and to cold, without the
  *   governor's doing (`Monitor.hs` 145-161): uniform in (0.1, 10) and (0.1, 4) seconds.
  * @param maxPublicRootBackoff
  *   The longest the request for public roots backs off (`2^min(n, 8)` seconds).
  * @param demotionTimeout
  *   The wait for a connection to be released after a demotion (`c_PEER_DEMOTION_TIMEOUT`,
  *   `Types.hs` 268-274), which the effect loop enforces.
  */
final case class Policy(
    maxConnectionRetries: Int = 5,
    clearFailCountDelay: FiniteDuration = 120.seconds,
    baseColdRetry: FiniteDuration = 5.seconds,
    maxColdRetryBackoff: Int = 5,
    repromoteErrorDelay: FiniteDuration = 10.seconds,
    warmReconnectFuzz: (FiniteDuration, FiniteDuration) = (100.millis, 10.seconds),
    coldReconnectFuzz: (FiniteDuration, FiniteDuration) = (100.millis, 4.seconds),
    maxPublicRootBackoff: Int = 8,
    demotionTimeout: FiniteDuration = 10.minutes,
    peerShareActivationDelay: FiniteDuration = 300.seconds,
    peerShareRetryTime: FiniteDuration = 900.seconds,
    peerShareFailureRetryTime: FiniteDuration = 3600.seconds,
    peerShareBatchWaitTime: FiniteDuration = 3.seconds,
    peerShareOverallTimeout: FiniteDuration = 10.seconds,
    maxPeerShareRequests: Int = 2,
    inboundPeersRetryDelay: FiniteDuration = 60.seconds,
    maxInboundPeers: Int = 10
)

/** How the governor ranks hot peers for demotion: the lowest score goes first. The score is
  * `upstreamyness + fetchynessBlocks` (`hotDemotionPolicy`, `Policies.hs` 127-147), ties broken by
  * the slot at which the peer joined the board, then by a random number ([[PeerMetrics.hotScores]]
  * gives the scores; a peer the metrics do not know scores [[HotScore.Unknown]], below all that are
  * known; without metrics every peer scores alike and the random number decides).
  */
final case class HotScore(value: Int, joinedAt: Option[Long])

object HotScore:
  val Unknown: HotScore = HotScore(0, None)
  given Ordering[HotScore] = Ordering.by(s => (s.value, s.joinedAt))

/** The pick policies, pure over an explicit generator. Each draws one random 32-bit number per
  * candidate, in the order of the candidates, scales it, and takes the `n` smallest (`addRand` and
  * the policies of `Policies.hs` 127-217).
  */
object Picks:
  private def choose[A](
      candidates: SortedSet[A],
      n: Int,
      rng: Rng
  )(select: List[(A, Long)] => List[A]): (List[A], Rng) =
    if n <= 0 || candidates.isEmpty then (Nil, rng)
    else
      val (drawn, next) = candidates.toList.foldLeft((List.empty[(A, Long)], rng)) {
        case ((acc, g), peer) =>
          val (r, g2) = g.nextWord32
          ((peer, r) :: acc, g2)
      }
      (select(drawn.reverse), next)

  private def ranked[A, R](drawn: List[(A, Long)], n: Int)(rank: (A, Long) => R)(using
      ordA: Ordering[A],
      ordR: Ordering[R]
  ): List[A] =
    drawn
      .sortBy((peer, r) => (rank(peer, r), peer))(using Ordering.Tuple2(using ordR, ordA))
      .take(n.max(0))
      .map(_._1)

  // Native policies can be compared with identical Word32 inputs independently of the generator.
  // These selectors are also used by the governor's ordinary random-draw path.
  private[selection] def promotionWords[A: Ordering](drawn: List[(A, Long)], n: Int): List[A] =
    ranked(drawn, n)((_, r) => r)

  private[selection] def warmPromotionWords[A: Ordering](
      drawn: List[(A, Long)],
      n: Int,
      tepid: A => Boolean
  ): List[A] = ranked(drawn, n)((peer, r) => if tepid(peer) then r else r / 4)

  private[selection] def warmDemotionWords[A: Ordering](
      drawn: List[(A, Long)],
      n: Int,
      tepid: A => Boolean
  ): List[A] = ranked(drawn, n)((peer, r) => if tepid(peer) then r / 2 else r)

  private[selection] def forgetWords[A: Ordering](
      drawn: List[(A, Long)],
      n: Int,
      failCount: A => Int
  ): List[A] = ranked(drawn, n)((peer, r) => r / (failCount(peer).max(0) + 1L))

  private[selection] def hotDemotionWords[A: Ordering](
      drawn: List[(A, Long)],
      n: Int,
      score: A => HotScore
  ): List[A] = ranked(drawn, n)((peer, r) => (score(peer), r))

  private[selection] def hotDemotionScores[A](
      available: Iterable[A],
      score: A => Option[HotScore]
  ): Map[A, Option[Int]] = available.iterator.map(peer => peer -> score(peer).map(_.value)).toMap

  /** Cold to warm and peers to ask: uniformly at random. */
  def promotion[A: Ordering](candidates: SortedSet[A], n: Int, rng: Rng): (List[A], Rng) =
    choose(candidates, n, rng)(promotionWords(_, n))

  /** Warm to hot: favour peers which have not just been demoted. The native `warmPromotionPolicy` /
    * `promoteWeight` at ouroboros-network `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`,
    * `Diffusion/Policies.hs`, quarters non-tepid Word32 draws; tepid peers keep their original
    * draw.
    */
  def warmPromotion[A: Ordering](
      candidates: SortedSet[A],
      n: Int,
      rng: Rng,
      tepid: A => Boolean
  ): (List[A], Rng) =
    choose(candidates, n, rng)(warmPromotionWords(_, n, tepid))

  /** Hot to warm: the lowest score first, the random number breaks ties. */
  def hotDemotion[A: Ordering](
      candidates: SortedSet[A],
      n: Int,
      rng: Rng,
      score: A => HotScore = (_: A) => HotScore.Unknown
  ): (List[A], Rng) =
    choose(candidates, n, rng)(hotDemotionWords(_, n, score))

  /** Warm to cold: at random, a peer that was hot is twice as likely (`tepidWeight`). */
  def warmDemotion[A: Ordering](
      candidates: SortedSet[A],
      n: Int,
      rng: Rng,
      tepid: A => Boolean
  ): (List[A], Rng) =
    choose(candidates, n, rng)(warmDemotionWords(_, n, tepid))

  /** Forget a cold peer: at random, a peer with failures is more likely (`failWeight`). */
  def forget[A: Ordering](
      candidates: SortedSet[A],
      n: Int,
      rng: Rng,
      failCount: A => Int
  ): (List[A], Rng) =
    choose(candidates, n, rng)(forgetWords(_, n, failCount))

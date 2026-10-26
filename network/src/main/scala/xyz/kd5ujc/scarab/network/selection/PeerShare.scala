// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3
import xyz.kd5ujc.scarab.protocol.PeerSharing

/** The server side of peer sharing as a pure function: which known peers may be told to others and
  * which of them one answer names. The reference keeps the same split: the governor publishes the
  * set of peers available to share (`getPeerSharingResponsePeers`,
  * `PS/State/KnownPeers.hs:580-592`, `toPublicState`, `Governor/Types.hs:854-862`) and the server
  * draws from it (`computePeerSharingPeers`, `ON/PeerSharing.hs:203-237`).
  *
  * The server needs only the state of the known peers, never a connection, so the answer is a pure
  * function of the known peers, the salt and the request, and a test needs no network.
  */
object PeerShare:
  /** A request has no answer when the protocol controller has not registered yet. These are
    * discovery outcomes, never consensus verdicts (`PeerSharingNotRegisteredYet`).
    */
  enum Result[A]:
    case Peers(peers: Vector[A])
    case NotRegistered()
    case Failed(error: xyz.kd5ujc.scarab.network.NetworkError)
    case TimedOut()

  final case class Batch[A](pending: Set[A], remaining: Int, salt: Long)

  /** Peer-sharing bookkeeping, separate from connection temperatures. A batch retains the remaining
    * objective between the 3-second and 10-second completions.
    */
  final case class Governor[A](
      enabled: Boolean = false,
      inboundRetryAt: Time = Time.Zero,
      nextBatch: Long = 0L,
      batches: Map[Long, Batch[A]] = Map.empty[Long, Batch[A]]
  ):
    def inFlight: Int = batches.valuesIterator.map(_.pending.size).sum

  /** `KnownPeers.belowTarget` (2fb69829, Governor/KnownPeers.hs:96-237). */
  private[selection] def belowTarget[A: Ordering](
      st: GovernorState[A],
      now: Time,
      policy: Policy,
      inbound: Map[A, Boolean]
  ): PeerSelection.Guard[A] =
    import PeerSelection.Guard.*
    val sharing = st.sharing
    val view = st.view(now)
    val objective = st.targets.known - view.known.size
    val capacity = policy.maxPeerShareRequests - sharing.inFlight
    val eligible = st.peers.collect {
      case (p, r)
          if r.status.established && r.status != Status.Cooling &&
            r.shareAt.exists(_ <= now) =>
        p
    }.toSet
    val unknownInbound = inbound.keySet -- st.peers.keySet
    val (coin, afterCoin) = st.rng.nextLong
    if !sharing.enabled || st.sensitivity.sensitive || objective <= 0 then Skip(None)
    else if sharing.inFlight == 0 && now >= sharing.inboundRetryAt &&
      ((coin & 1L) == 0L || eligible.isEmpty) && unknownInbound.nonEmpty
    then
      val (picked, next) = Picks.promotion(
        scala.collection.immutable.SortedSet.from(unknownInbound),
        objective.min(policy.maxInboundPeers),
        afterCoin
      )
      val peers = picked.foldLeft(st.peers) { (m, p) =>
        m.updated(
          p,
          PeerRecord
            .cold(Origin.Other)
            .copy(advertise = Some(true), everConnected = true, sharing = inbound.get(p))
        )
      }
      Go(
        Decision(
          st.copy(
            peers = peers,
            rng = next,
            sharing = sharing.copy(inboundRetryAt = now + policy.inboundPeersRetryDelay)
          ),
          Nil,
          List(Trace.PickInbound(picked))
        )
      )
    else if capacity > 0 && eligible.nonEmpty then
      val (picked, afterPick) = Picks.promotion(
        scala.collection.immutable.SortedSet.from(eligible),
        capacity,
        afterCoin
      )
      val amount = PeerSharing.Amount.saturating((objective / picked.size).max(8).min(255))
      val (salt, next) = afterPick.nextLong
      val id = sharing.nextBatch
      val peers = picked.foldLeft(st.peers)((m, p) =>
        m.updated(p, m(p).copy(shareAt = Some(now + policy.peerShareRetryTime)))
      )
      Go(
        Decision(
          st.copy(
            peers = peers,
            rng = next,
            sharing = sharing.copy(
              nextBatch = id + 1,
              batches = sharing.batches.updated(id, Batch(picked.toSet, objective, salt))
            )
          ),
          List(Job.Share(id, amount, picked)),
          List(Trace.ShareRequests(amount.value, picked))
        )
      )
    else
      val nextShare = Option
        .when(capacity > 0)(
          st.peers.valuesIterator
            .filter(r => r.status.established && r.status != Status.Cooling)
            .flatMap(_.shareAt)
            .filter(_ > now)
            .minOption
        )
        .flatten
      val nextInbound = Option.when(
        sharing.inFlight == 0 && unknownInbound.nonEmpty &&
          sharing.inboundRetryAt > now
      )(sharing.inboundRetryAt)
      Skip((nextShare.toList ++ nextInbound.toList).minOption)

  /** Filter against the state at completion, limit the whole batch objective, and retain failures
    * as discovery results. Existing peer metadata must never be overwritten by a duplicate answer
    * (`KnownPeers.hs:316-445`).
    */
  private[selection] def completed[A: Ordering](
      st: GovernorState[A],
      id: Long,
      results: List[(A, Result[A])],
      now: Time,
      policy: Policy
  ): Decision[A] =
    st.sharing.batches.get(id).fold(Decision(st, Nil, Nil)) { batch =>
      val applicable = results.filter((peer, _) => batch.pending(peer))
      val bigLedger = st.peers.collect { case (peer, record) if record.origin.big => peer }.toSet
      val candidates = applicable.flatMap {
        case (_, Result.Peers(peers)) => peers.filterNot(p => st.peers.contains(p) || bigLedger(p))
        case _                        => Vector.empty[A]
      }.distinct
      // Socket hashes differ from Haskell's platform-dependent SockAddr hash. Only deterministic
      // salted ordering is required; the same explicit departure applies to the server policy.
      val added = candidates
        .sortBy(p =>
          scala.util.hashing.MurmurHash3
            .stringHash(p.toString, (batch.salt ^ (batch.salt >>> 32)).toInt)
        )
        .take(batch.remaining.max(0))
      val retried = applicable.foldLeft(st.peers) {
        case (m, (peer, Result.Failed(_) | Result.TimedOut())) =>
          m.get(peer)
            .fold(m)(r =>
              m.updated(peer, r.copy(shareAt = Some(now + policy.peerShareFailureRetryTime)))
            )
        case (m, _) => m
      }
      val peers = added.foldLeft(retried)((m, p) =>
        m.updated(p, PeerRecord.cold(Origin.Other).copy(advertise = Some(true)))
      )
      val remaining = batch.copy(
        pending = batch.pending -- applicable.map(_._1),
        remaining = batch.remaining - added.size
      )
      val batches =
        if remaining.pending.isEmpty then st.sharing.batches - id
        else st.sharing.batches.updated(id, remaining)
      Decision(
        st.copy(peers = peers, sharing = st.sharing.copy(batches = batches)),
        Nil,
        List(Trace.ShareResults(applicable, added))
      )
    }

  /** Time between two changes of the salt (`ps_POLICY_PEER_SHARE_STICKY_TIME`,
    * `PeerSharing.hs:168-169`). Within it the same peers are shared with every asker, so asking
    * again brings nothing new and the network cannot be mapped quickly.
    */
  val StickyTime: FiniteDuration = 823.seconds

  /** The most peers one answer holds (`ps_POLICY_PEER_SHARE_MAX_PEERS`, `PeerSharing.hs:173-174`).
    */
  val MaxPeers: PeerSharing.Amount = PeerSharing.Amount.saturating(10)

  /** What the server needs to know of a known peer to decide whether it is told to others.
    * @param advertise
    *   The peer may be advertised (`DoAdvertisePeer`): the topology said so, or the governor
    *   records it for a peer it learnt of.
    * @param everConnected
    *   A connection to it succeeded at some time (`knownSuccessfulConnection`).
    * @param failCount
    *   Failures since the last success.
    */
  final case class Candidate[A](
      peer: A,
      advertise: Boolean,
      everConnected: Boolean,
      failCount: Int
  )

  /** The peers available to share: advertised, connected successfully at some time, and with no
    * failure since (`KnownPeers.hs:580-592`: `DoAdvertisePeer`, `knownSuccessfulConnection`, a fail
    * count of 0). Duplicates collapse; the order is the order of `A`.
    */
  def shareable[A: Ordering](known: Iterable[Candidate[A]]): Vector[A] =
    known.iterator
      .collect { case c if c.advertise && c.everConnected && c.failCount == 0 => c.peer }
      .toVector
      .distinct
      .sorted

  /** A hash of a peer under a salt: the order in which the salt ranks the peers (`hashWithSalt`).
    * Deviation approved by the owner on 2026-10-25: MurmurHash3 stands in for `hashWithSalt`;
    * trade-off: the reference's order is keyed by a secret random per-node salt, so peers cannot
    * observe the hash function, and mirroring it would mean reimplementing version- and
    * platform-dependent `hashable` internals.
    */
  trait SaltedHash[A]:
    def apply(salt: Long, peer: A): Int

  object SaltedHash:
    given SaltedHash[PeerSharing.Address] with
      def apply(salt: Long, peer: PeerSharing.Address): Int =
        val seed = (salt ^ (salt >>> 32)).toInt
        MurmurHash3.bytesHash(peer.toBytes.toArray, seed)

  /** The answer to a request: the shareable peers ordered by their hash under `salt`, cut to the
    * smaller of the amount asked for and `limit` ([[MaxPeers]]; `PeerSharing.hs:229-234`). The
    * order of `shareable` breaks a tie, so the answer is a function of its arguments.
    */
  def answer[A: SaltedHash](
      salt: Long,
      amount: PeerSharing.Amount,
      shareable: Vector[A],
      limit: PeerSharing.Amount = MaxPeers
  ): Vector[A] =
    val hash = summon[SaltedHash[A]]
    shareable.sortBy(hash(salt, _)).take(math.min(amount.value, limit.value))

  /** The salt and when it changes: the generator whose next draw is the salt, and the time from
    * which a request draws afresh. Initially the salt is due at once (`PeerSharing.hs:189`).
    */
  final case class Salting(rng: Rng, reSaltAt: Time):
    /** The salt for a request at `now`, and the state after it. When the salt is due the generator
      * advances and the salt is held for [[StickyTime]]; otherwise it is the draw the generator
      * would make now, without advancing (`PeerSharing.hs:219-227`). As in the reference, the
      * request that re-salts is answered with the draw it made, and the requests after it, up to
      * the next re-salt, with the first draw of the advanced generator. So the salt of an interval
      * is one value, and the request that opens the next interval still reads it.
      */
    def at(now: Time): (Long, Salting) =
      val (salt, advanced) = rng.nextLong
      if reSaltAt <= now then (salt, Salting(advanced, now + StickyTime))
      else (salt, this)

  object Salting:
    def start(rng: Rng): Salting = Salting(rng, Duration.Zero)

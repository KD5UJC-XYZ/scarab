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
  /** Time between two changes of the salt (`ps_POLICY_PEER_SHARE_STICKY_TIME`,
    * `PeerSharing.hs:168-169`). Within it the same peers are shared with every asker, so asking
    * again brings nothing new and the network cannot be mapped quickly.
    */
  val StickyTime: FiniteDuration = 823.seconds

  /** The most peers one answer holds (`ps_POLICY_PEER_SHARE_MAX_PEERS`, `PeerSharing.hs:173-174`).
    */
  val MaxPeers: PeerSharing.Amount = PeerSharing.Amount.saturating(10)

  /** What the server needs to know of a known peer to decide whether it is told to others. */
  final case class Candidate[A](
      peer: A,
      /** The peer may be advertised (`DoAdvertisePeer`): the topology said so, or the governor
        * records it for a peer it learnt of.
        */
      advertise: Boolean,
      /** A connection to it succeeded at some time (`knownSuccessfulConnection`). */
      everConnected: Boolean,
      /** Failures since the last success. */
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

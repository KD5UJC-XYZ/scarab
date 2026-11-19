// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import scala.concurrent.duration.*

/** The inbound governor's duplex peers, fresh or mature: `matureDuplexPeers` and `freshDuplexPeers`
  * of its `State` (`InboundGovernor/State.hs` 98-108 at ouroboros-network `c45735a5`, which
  * cardano-node 11.1.3 links; 96-106 at `4646039f`, which 11.2.1 links, and at `2f1c154f`, the
  * Leios build's). The peer of a duplex connection is fresh from the time a step of the inbound
  * governor registers the connection, and becomes mature at its first step
  * [[DuplexPeers.MatureDelay]] or more later. Only the mature peers are public
  * (`inboundDuplexPeers`, `mkPublicState`, `State.hs` 74-83): they are the inbound peers the
  * outbound governor may add to its known peers (`readInboundPeers`, `Diffusion.hs` 277-282 and
  * 651-654; `KnownPeers.hs` 96-141), so an address no other source made known is learnt from a
  * duplex connection only once it is mature. A peer is its address, not its connection, as in the
  * reference.
  *
  * @param mature
  *   the mature peers, each with its data
  * @param fresh
  *   the fresh peers, each with the time it was registered at and its data: the reference keeps
  *   them in an `OrdPSQ` by that time, which only [[maturedPeers]] reads in time order
  */
final case class DuplexPeers[A, V](mature: Map[A, V], fresh: Map[A, (FiniteDuration, V)]):

  /** A duplex connection's `NewConnection`, handled at time `at` (`InboundGovernor.hs` 385-391 at
    * `c45735a5`, 373-378 at `4646039f`, 374-377 at `2f1c154f`): its peer is fresh from `at`.
    * `OrdPSQ.insert` replaces the time of a peer that another connection of the same address made
    * fresh, and leaves a mature peer mature.
    */
  def registered(peer: A, at: FiniteDuration, data: V): DuplexPeers[A, V] =
    copy(fresh = fresh.updated(peer, (at, data)))

  /** `unregisterConnection` (`State.hs` 208-222 at `c45735a5`, 201-215 at `4646039f` and
    * `2f1c154f`): the peer is neither fresh nor mature any more, whichever connection of its
    * address registered it.
    */
  def unregistered(peer: A): DuplexPeers[A, V] = DuplexPeers(mature - peer, fresh - peer)

  /** `maturedPeers` (`InboundGovernor.hs` 765-772 at `c45735a5`, 832-839 at `4646039f`, 796-803 at
    * `2f1c154f`): at `time`, the peers fresh since `time - MatureDelay` or earlier (`atMostView`),
    * and the fresh peers left.
    */
  def maturedPeers(time: FiniteDuration): (Map[A, V], Map[A, (FiniteDuration, V)]) =
    val (due, rest) = fresh.partition { case (_, (since, _)) =>
      since <= time - DuplexPeers.MatureDelay
    }
    (due.map { case (peer, (_, data)) => peer -> data }, rest)

  /** The `MaturedDuplexPeers` event (`InboundGovernor.hs` 547-552 at `c45735a5`, 590-598 at
    * `4646039f`, 574-582 at `2f1c154f`): the newly mature peers join the mature ones, their data
    * replacing that of a peer already mature (`<>` is left-biased), and `rest` are the fresh ones.
    */
  def matured(newlyMature: Map[A, V], rest: Map[A, (FiniteDuration, V)]): DuplexPeers[A, V] =
    DuplexPeers(mature ++ newlyMature, rest)

object DuplexPeers:
  /** `inboundMaturePeerDelay` (`InboundGovernor.hs` 84-85 at `c45735a5`, 85-86 at `4646039f` and
    * `2f1c154f`).
    */
  val MatureDelay: FiniteDuration = 15.minutes

  /** `inactionTimeout`, 31.415927 s: the inbound governor takes a step at the latest this long
    * after the last one began, so that fresh peers mature (`InboundGovernor.hs` 88-92 at
    * `c45735a5`, 89-93 at `4646039f` and `2f1c154f`).
    */
  val InactionTimeout: FiniteDuration = 31415927.microseconds

  def empty[A, V]: DuplexPeers[A, V] = DuplexPeers(Map.empty, Map.empty)

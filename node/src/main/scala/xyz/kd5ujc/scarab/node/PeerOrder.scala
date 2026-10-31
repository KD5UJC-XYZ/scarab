// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.network.ConnectionId
import xyz.kd5ujc.scarab.protocol.ConnectionIdentityError

/** The order in which the reference's maps keyed by peer hold a node-to-node connection's sessions:
  * its `ConnectionId`, ordered by the remote address and then the local one (`instance Ord
  * (ConnectionId addr)`, `framework/lib/Ouroboros/Network/ConnectionId.hs:41-44`, ouroboros-network
  * 2fb69829). The block fetch registry (`getFetchClientRegistry`,
  * `Ouroboros/Consensus/NodeKernel.hs:178`, ouroboros-consensus 82ecba32) and the ChainSync handles
  * are such maps, so the order of their peers does not depend on when each session registered. A
  * session without a TCP socket (an in-memory bearer of a test or a model) has no `ConnectionId`
  * and is ordered by a number of its own, before every TCP connection.
  */
enum PeerOrder:
  case Model(value: Long)
  case Tcp(connection: ConnectionId)

object PeerOrder:
  def apply(value: Long): PeerOrder = Model(value)

  /** The order of the sessions of a connection whose bearer reports `connection`: its
    * `ConnectionId` when it has one. A bearer that is not a TCP socket reports none, and every
    * [[ConnectionIdentityError]] describes an endpoint that a socket the kernel accepted or
    * connected cannot have, so neither occurs on the wire; such a session keeps the order it
    * registered in. Genesis, whose density comparison needs the key, refuses both
    * ([[GenesisRuntime.controls]]).
    */
  def of(connection: Option[Either[ConnectionIdentityError, ConnectionId]]): Option[PeerOrder] =
    connection.flatMap(_.toOption).map(Tcp(_))

  given Ordering[PeerOrder] with
    def compare(a: PeerOrder, b: PeerOrder): Int = (a, b) match
      case (Model(x), Model(y)) => x.compare(y)
      case (Tcp(x), Tcp(y))     => summon[Ordering[ConnectionId]].compare(x, y)
      case (_: Model, _: Tcp)   => -1
      case (_: Tcp, _: Model)   => 1

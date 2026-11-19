// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.network.selection.{ConsensusMode, Targets}

/** How many connections the node may dial and still use at once (`MaxOutboundConnections`,
  * `--max-outbound`). The reference has no such limit: its connection manager reserves a connection
  * to any peer it has none to (`acquireOutboundConnectionImpl`, ouroboros-network `c45735a5`,
  * `ConnectionManager/Core.hs` 1390-1418), and the governor's targets bound how many it asks for.
  * The limit is a Deviation the owner approved, opt-in and off by default.
  *
  * The connection manager counts a connection from the reservation that dials it until the outbound
  * side lets it go ([[Connection.releaseOutbound]]) or the reservation's resource is released,
  * whichever comes first, whatever the connection's state: one that the peer keeps using after we
  * let it go, or that a prune leaves in an outbound state, does not count, and neither does the
  * reuse of a peer's connection, which dials nothing. Connections to local roots are left out, as
  * the governor classifies the peer: a dial reads the classification when it reserves its
  * connection, and again before it is refused at the limit ([[OutboundLimit.Dial]]), and the
  * connection of a peer that becomes a local root once its dial has reserved gives its place back
  * (`Job.Exempt`, [[Connection.exempt]]).
  */
enum OutboundLimit:
  /** No limit, as in the reference. */
  case Unlimited

  /** At most `connections` dialled connections in outbound use; a reservation beyond them is
    * refused with `ConnectionError.OutboundLimit`.
    */
  case AtMost(connections: OutboundLimit.Connections)

object OutboundLimit:
  /** A positive number of connections. */
  opaque type Connections = Int

  object Connections:
    def from(n: Int): Either[NotPositive, Connections] = Either.cond(n > 0, n, NotPositive(n))

    extension (connections: Connections) def value: Int = connections

  /** A limit of `value` connections, which is not positive. */
  final case class NotPositive(value: Int) extends DomainError

  /** What the limit makes of a dial, as the governor classifies the peer when the dial reserves its
    * connection (`GovernorState.dialOf`): the reservation takes a place or none from it, and its
    * release gives back what it took, whatever becomes of the peer's origin meanwhile.
    */
  enum Dial:
    /** Any peer but a local root: an ordinary or a big ledger peer. It takes a place, and is
      * refused when the places are all taken.
      */
    case Counted

    /** A local root, the `--peer` group included: never counted and never refused by the limit.
      */
    case LocalRoot

  /** The threshold the start-up check holds a limit to: the governor's largest established target
    * and its largest established target of big ledger peers, over the targets it can work towards.
    * It is what the counted dials in use come to when the governor is at its targets, not a bound
    * at every instant (below).
    *
    *   - The ordinary and the big ledger peers are dialled towards their own targets
    *     (`connectOther`, `BigLedgerGuards.connect`), each while its established and connecting
    *     peers are below it. The public roots, the bootstrap peers, the ledger peers and the peers
    *     learnt from others are ordinary peers.
    *   - The local roots are left out ([[Dial.LocalRoot]]); counted as established peers, they only
    *     make the governor dial fewer others.
    *   - In Genesis mode the targets switch between the deadline and the sync ones with the ledger
    *     judgement (`Churn.targetsFor`). Across a switch, the dials towards one kind's new target
    *     can overlap with the closing of the other kind's excess, so the larger of each kind's two
    *     targets is taken. In Praos mode only the deadline targets apply. Churn only lowers them.
    *
    * A dial's place is taken after the governor counts the peer as connecting, and given back when
    * the connection is let go or ends. The counted dials in use can exceed the threshold for a
    * while: after a reload or an answer for big ledger peers moves connected peers from one
    * population to another, and while a connection the governor no longer counts is still being
    * closed, until the surplus connections close. A limit set exactly at the threshold can then
    * refuse a few counted dials, each a failed connection to its peer, so a limit leaves room above
    * it.
    */
  final case class Needed(established: Int, establishedBig: Int):
    /** Exact: each target is at most `Targets.EstablishedCap`. */
    def total: Int = established + establishedBig

  object Needed:
    /** What `deadline` and `sync` need in `mode`. */
    def of(deadline: Targets, sync: Targets, mode: ConsensusMode): Needed =
      mode match
        case ConsensusMode.PraosMode =>
          Needed(deadline.established, deadline.establishedBig)
        case ConsensusMode.GenesisMode =>
          Needed(
            deadline.established.max(sync.established),
            deadline.establishedBig.max(sync.establishedBig)
          )

  /** A limit below what the governor needs at once, which would refuse its own dials. */
  final case class BelowTargets(limit: Connections, needed: Needed) extends DomainError

  extension (limit: OutboundLimit)
    /** The limit, if it leaves room for what the governor needs at once. */
    def fits(needed: Needed): Either[BelowTargets, OutboundLimit] = limit match
      case Unlimited => Right(limit)
      case AtMost(connections) =>
        Either.cond(
          connections.value >= needed.total,
          limit,
          BelowTargets(connections, needed)
        )

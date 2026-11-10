// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Monoid
import cats.syntax.all.*

/** What the inbound governor knows of the peer's side of a connection, as it traces and counts it
  * (`RemoteSt`, `InboundGovernor/State.hs` 218-256 and 299-312, ouroboros-network `2fb69829`).
  */
enum RemoteSt:
  /** `RemoteWarm`: the peer runs responders of ours, none of them hot. */
  case Warm

  /** `RemoteHot`: the peer runs a hot responder of ours (ChainSync, BlockFetch, TxSubmission2). */
  case Hot

  /** `RemoteIdle`: none runs and the protocol idle timer is armed; a new connection starts here. */
  case Idle

  /** `RemoteCold`: the idle timer expired and the connection is kept because our outbound side uses
    * it.
    */
  case Cold

/** The inbound governor's connections by remote state (`Counters`, `InboundGovernor/State.hs`
  * 113-145, ouroboros-network `2fb69829`).
  */
final case class InboundGovernorCounters(cold: Int, idle: Int, warm: Int, hot: Int)

object InboundGovernorCounters:
  val empty: InboundGovernorCounters = InboundGovernorCounters(0, 0, 0, 0)

  given Monoid[InboundGovernorCounters] =
    Monoid.instance(
      empty,
      (a, b) =>
        InboundGovernorCounters(a.cold + b.cold, a.idle + b.idle, a.warm + b.warm, a.hot + b.hot)
    )

  /** `counters` (`InboundGovernor/State.hs` 136-145): one for each tracked connection, in the count
    * of its remote state.
    */
  def of(states: Iterable[RemoteSt]): InboundGovernorCounters =
    states.toList.foldMap {
      case RemoteSt.Cold => InboundGovernorCounters(1, 0, 0, 0)
      case RemoteSt.Idle => InboundGovernorCounters(0, 1, 0, 0)
      case RemoteSt.Warm => InboundGovernorCounters(0, 0, 1, 0)
      case RemoteSt.Hot  => InboundGovernorCounters(0, 0, 0, 1)
    }

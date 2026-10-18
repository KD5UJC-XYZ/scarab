// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.protocol.NodeToClientVersion

/** The supported query contract of cardano-node 11.1.3: `blockQueryIsSupportedOnVersion`,
  * `Shelley/Ledger/Query.hs:541-593` at ouroboros-consensus
  * 82ecba329d7d054340bf707d44fe6e9ac27cec40. The reference's client encoder enforces this table;
  * Scarab enforces it at the session boundary too so it never answers a constructor outside the
  * agreed contract (`Shelley/Node/Serialisation.hs:368-373` leaves the reference decoder ungated).
  */
object QueryVersion:
  def supports(version: NodeToClientVersion, query: NtcQuery): Boolean = query match
    case NtcQuery.IfCurrent(_, inner) => ledger(version, inner)
    case _                            => true

  @tailrec private def ledger(version: NodeToClientVersion, query: LedgerQuery): Boolean =
    import LedgerQuery.*
    val v = version.number
    query match
      case GetCBOR(inner)                         => ledger(version, inner)
      case GetProposedPParamsUpdates              => v < 20
      case GetPoolDistr(_) | GetStakeDistribution => v < 21
      case GetProposals(_) | GetRatifyState       => v >= 17
      case GetFuturePParams                       => v >= 18
      case GetLedgerPeerSnapshotLegacy            => v >= 19
      case QueryStakePoolDefaultVote(_)           => v >= 20
      case GetPoolDistr2(_) | GetStakeDistribution2 | GetMaxMajorProtocolVersion => v >= 21
      case GetLedgerPeerSnapshot(_) | GetDRepDelegations(_)                      => v >= 23
      case Unsupported(_)                                                        => false
      case _                                                                     => true

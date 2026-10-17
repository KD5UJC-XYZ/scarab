// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*

/** The governance state of the ledger (`ConwayGovState`). The pulser is kept in its completed form:
  * ratification is a deterministic function of the snapshot taken at the epoch boundary, so the
  * snapshot and the ratify state are computed together when the boundary is crossed.
  */
final case class GovState(
    proposals: Proposals,
    committee: Option[Committee],
    constitution: Constitution,
    pulsed: Option[(PulsingSnapshot, RatifyState)]
)

object GovState:
  val noAnchor: Anchor = Anchor("", Bytes(Vector.fill(32)(0.toByte)))
  val empty: GovState =
    GovState(Proposals.empty, None, Constitution(noAnchor, None), None)

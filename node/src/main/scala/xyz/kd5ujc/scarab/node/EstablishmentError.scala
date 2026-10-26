// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.PeerError

/** Original acquisition failures at the PeerStateActions boundary (2fb69829,
  * PeerSelection/PeerStateActions.hs:780–866), before the governor applies its retry policy.
  */
enum EstablishmentError extends DomainError:
  case Failed(cause: PeerError)
  case Defect(cause: Throwable)
  case Superseded

  def message: String = this match
    case Failed(cause) => cause.message
    case Defect(cause) => s"connection establishment defect: ${cause.getMessage}"
    case Superseded    => "another outbound connection was established first"

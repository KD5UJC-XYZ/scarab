// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PredicateFailure
import xyz.kd5ujc.scarab.protocol.Era

/** The flat rules' refusal in the encoding of the ledger's era `era`: the flat rules' Conway
  * encoder in Conway ([[PredicateFailureCbor]]), the era's own before it
  * ([[PreConwayFailureCbor]]), none where the era has no encoding of a failure. The node's
  * submission no longer writes flat failures under Conway, whose pool refuses in the era's types
  * (#85); the suites that hold the flat rules' answers to the reference's write them so.
  */
object FlatRefusal:
  def applyTxError(
      era: Era,
      failures: Vector[PredicateFailure]
  ): Either[RefusalPresentationError, Option[Bytes]] = era match
    case Era.Conway => PredicateFailureCbor.applyTxError(failures).map(Some(_))
    case other      => PreConwayFailureCbor.applyTxError(other, failures)

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PredicateFailure
import xyz.kd5ujc.scarab.protocol.Era

/** The flat rules' refusal in the encoding of the ledger's era `era`, for the eras before Conway:
  * the era's own ([[PreConwayFailureCbor]]), none where the era has no encoding of a failure, and
  * none in Conway and after, whose refusals are their era rules' in their era's types
  * ([[ConwayFailureCbor]], #85). The suites that hold the flat rules' answers to the reference's
  * write them so.
  */
object FlatRefusal:
  def applyTxError(
      era: Era,
      failures: Vector[PredicateFailure]
  ): Either[RefusalPresentationError, Option[Bytes]] =
    PreConwayFailureCbor.applyTxError(era, failures)

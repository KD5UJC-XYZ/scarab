// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{EnvelopeError, Header}

/** Operator trust inputs: HeaderValidation.hs:418–428 at consensus 82ecba329. */
final case class HeaderCheckpoints(entries: Map[BigInt, Bytes]):
  def validate(header: Header): Either[EnvelopeError, Unit] =
    entries.get(header.blockNo) match
      case Some(expected) if expected != header.hash =>
        Left(EnvelopeError.CheckpointMismatch(header.blockNo, expected, header.hash))
      case _ => Right(())

object HeaderCheckpoints:
  val empty: HeaderCheckpoints = HeaderCheckpoints(Map.empty)

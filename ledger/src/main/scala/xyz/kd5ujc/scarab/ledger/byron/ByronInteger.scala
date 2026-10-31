// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.codec.{Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, DecodeSite, LedgerDecodeError}

/** Byron decodeInteger delegates to cborg's ConsumeInteger (ledger f649f975, Decoder.hs:1523).
  * cborg 0.2.10 accepts definite noncanonical magnitudes, but its integer token parser requires the
  * one-byte tag2/tag3 header and a definite bytestring (Read.hs:1758–1850,2645–2646).
  */
private[byron] object ByronInteger:
  def decode(node: Node, context: DecodeSite): Either[LedgerDecodeError, BigInt] = node.value match
    case Value.UInt(value) => Right(value)
    case Value.NInt(value) => Right(value)
    case Value.Tag(tag, inner) if tag == 2 || tag == 3 =>
      for
        _ <- Either.cond(
          node.original.headOption.forall(_ == (0xc0 + tag.toInt).toByte),
          (),
          LedgerDecodeError.Expected(context, LedgerDecodeError.Shape.Integer, node.value)
        )
        _ <- Either.cond(
          !inner.isIndefinite,
          (),
          LedgerDecodeError.IndefiniteLength(context)
        )
        value <- CborRead.bytes(inner, context)
        magnitude = if value.size == 0 then BigInt(0) else BigInt(1, value.toArray)
      yield if tag == 2 then magnitude else -magnitude - 1
    case _ => Left(LedgerDecodeError.Expected(context, LedgerDecodeError.Shape.Integer, node.value))

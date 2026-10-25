// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.{Eq, Show}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Node}

enum Nonce:
  case Neutral
  case Hash(bytes: Bytes)

  /** `⭒`: Neutral is the identity; otherwise the hash of the concatenation. */
  def combine(other: Nonce): Nonce = (this, other) match
    case (Neutral, b)       => b
    case (a, Neutral)       => a
    case (Hash(a), Hash(b)) => Hash(Blake2b.hash256.hash(Bytes(a.value ++ b.value)))

  def render: Option[String] = this match
    case Neutral => None
    case Hash(b) => Some(b.hex)

object Nonce:
  given Eq[Nonce] = Eq.fromUniversalEquals

  /** `[0] / [1, hash]`: the extra entropy of a parameter update (field 13). `DecCBOR Nonce`
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:579-595`, cardano-ledger f649f975)
    * reads the hash as a `Hash`, a byte string read in the form `ByteStrings` gives.
    */
  private[ledger] def decode(n: Node)(using ByteStrings): Either[LedgerDecodeError, Nonce] =
    CborRead.array(n, DecodeField.Nonce).flatMap { xs =>
      xs.headOption
        .toRight(LedgerDecodeError.Empty(DecodeField.Nonce))
        .flatMap(CborRead.uint(_, DecodeField.NonceTag))
        .flatMap {
          case t if t == 0 => Right(Neutral)
          case t if t == 1 && xs.size == 2 =>
            CborRead.bytesN(xs(1), 32, DecodeField.Nonce).map(Hash(_))
          case t => Left(LedgerDecodeError.TaggedArity(DecodeField.Nonce, t, xs.size))
        }
    }

  given Show[Nonce] = Show.show {
    case Neutral => "Neutral"
    case Hash(b) => s"Hash(${b.hex})"
  }

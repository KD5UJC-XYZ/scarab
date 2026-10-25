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

  /** How many fields a tag 0 (neutral) nonce is read with.
    *
    *   - `Exact`: one, as the reference reads it.
    *   - `Stored`: any number, for an update kept by a build that did not check the length. It read
    *     `[0, 0]` as `Neutral` and kept the original bytes, and a persisted value is never
    *     reinterpreted or refused by a later build (ADR 0006), so applying a stored update goes on
    *     reading what that build accepted.
    */
  enum NeutralFields:
    case Exact
    case Stored

  /** `[0] / [1, hash]`: the extra entropy of a parameter update (field 13). `DecCBOR Nonce`
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:579-595`, cardano-ledger f649f975)
    * is a `Summands` decode below decoder version 12, which checks the declared length of the array
    * against the tag (an indefinite array must break right after the fields): tag 0 has one
    * element, tag 1 two. It reads the hash as a `Hash`, a byte string read in the form
    * `ByteStrings` gives.
    */
  private[ledger] def decode(n: Node, neutral: NeutralFields)(using
      ByteStrings
  ): Either[LedgerDecodeError, Nonce] =
    CborRead.array(n, DecodeField.Nonce).flatMap { xs =>
      xs.headOption
        .toRight(LedgerDecodeError.Empty(DecodeField.Nonce))
        .flatMap(CborRead.uint(_, DecodeField.NonceTag))
        .flatMap {
          case t if t == 0 && (xs.size == 1 || neutral == NeutralFields.Stored) => Right(Neutral)
          case t if t == 1 && xs.size == 2 =>
            CborRead.bytesN(xs(1), 32, DecodeField.Nonce).map(Hash(_))
          case t => Left(LedgerDecodeError.TaggedArity(DecodeField.Nonce, t, xs.size))
        }
    }

  given Show[Nonce] = Show.show {
    case Neutral => "Neutral"
    case Hash(b) => s"Hash(${b.hex})"
  }

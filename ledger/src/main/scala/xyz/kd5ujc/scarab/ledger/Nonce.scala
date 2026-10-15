// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

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

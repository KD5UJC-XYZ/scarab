// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b

/** A key witness (`WitVKey`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Keys/WitVKey.hs:40-47`): an Ed25519 key and its
  * signature of the body's hash.
  */
final case class WitVKey(vkey: Bytes, signature: Bytes):
  /** The key's hash (`witVKeyHash`). */
  lazy val keyHash: Bytes = Blake2b.hash224.hash(vkey)

object WitVKey:
  /** `Ord (WitVKey kr)` (`Keys/WitVKey.hs:58-68`, cardano-ledger f649f975): the key's hash, then
    * the hash of the signature (`hashTxBodySignature`, `Hashes.hs:193-196`, cardano-ledger
    * f649f975).
    */
  given ordering: Ordering[WitVKey] = (a, b) =>
    val keys = a.keyHash.compareTo(b.keyHash)
    if keys != 0 then keys
    else Blake2b.hash256.hash(a.signature).compareTo(Blake2b.hash256.hash(b.signature))

  given CanEqual[WitVKey, WitVKey] = CanEqual.derived

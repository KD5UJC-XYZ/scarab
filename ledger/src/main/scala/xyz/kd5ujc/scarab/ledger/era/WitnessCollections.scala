// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The datums of a witness set with the bytes they arrived as (`newtype TxDats era = MkTxDats
  * (MemoBytes (TxDatsRaw era))`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:297-298, 359-360`): the script integrity
  * hash is over those bytes, not over the map.
  *
  * @param raw
  *   the encoding of the witness set's datums (key 4), none when it has no key 4
  * @param datums
  *   by hash, each the bytes of its data as they arrived
  */
final class TxDats private[era] (val raw: Option[Bytes], val datums: SortedMap[Bytes, Bytes]):
  // Not a case class: its derived `Mirror` would make one from any datums, whatever its bytes.
  private def fields: Any = (raw, datums)
  override def equals(that: Any): Boolean = that match
    case o: TxDats => fields == o.fields
    case _         => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"TxDats($fields)"

object TxDats:
  given CanEqual[TxDats, TxDats] = CanEqual.derived

/** The redeemers of a witness set with the bytes they arrived as (`newtype Redeemers era =
  * MkRedeemers (MemoBytes (RedeemersRaw era))`, `Alonzo/TxWits.hs:144-147, 178-179`, cardano-ledger
  * f649f975), keyed by the era's purpose `P`.
  *
  * @param raw
  *   the encoding of the witness set's redeemers (key 5), none when it has no key 5
  * @param entries
  *   per purpose, the redeemer's data as it arrived and its units
  */
final class Redeemers[P] private[era] (
    val raw: Option[Bytes],
    val entries: SortedMap[P, (Bytes, ExUnits)]
):
  // Not a case class: its derived `Mirror` would make one from any entries, whatever its bytes.
  private def fields: Any = (raw, entries)
  override def equals(that: Any): Boolean = that match
    case o: Redeemers[?] => fields == o.fields
    case _               => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"Redeemers($fields)"

object Redeemers:
  given [P](using CanEqual[P, P]): CanEqual[Redeemers[P], Redeemers[P]] = CanEqual.derived

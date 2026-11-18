// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusData}

/** Plutus data with the bytes it arrived as (`newtype Data era = MkData (MemoBytes (PlutusData
  * era))`, `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:111`, cardano-ledger
  * f649f975): a witness datum or a redeemer's data, decoded once, when the witness set is, so that
  * what reads it cannot fail. Its hash is over the bytes it arrived as. Made only by
  * [[Data.decode]].
  */
final class Data private (val originalBytes: Bytes, val getPlutusData: PlutusData):
  // Not a case class: its derived `Mirror` would make one from any data, whatever its bytes. Equal
  // as the reference's `MemoBytes` are, by the bytes and the value.
  private def fields: Any = (originalBytes, getPlutusData)
  override def equals(that: Any): Boolean = that match
    case o: Data => fields == o.fields
    case _       => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"Data($fields)"

  /** `hashData` (`Plutus/Data.hs:259-260`, cardano-ledger f649f975): the hash of the bytes it
    * arrived as.
    */
  def hashData: Bytes = Blake2b.hash256.hash(originalBytes)

object Data:
  given CanEqual[Data, Data] = CanEqual.derived

  /** The data of `raw`, one item of Plutus data with nothing after it, as the reference's decoder
    * of `Data era` reads it (`Plutus/Data.hs:99-100, 121-125`, cardano-ledger f649f975).
    */
  def decode(raw: Bytes): Either[PlutusData.DecodeError, Data] =
    PlutusData.decode(raw).map(Data(raw, _))

/** The datums of a witness set with the bytes they arrived as (`newtype TxDats era = MkTxDats
  * (MemoBytes (TxDatsRaw era))`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:297-298, 359-360`): the script integrity
  * hash is over those bytes, not over the map.
  *
  * @param raw
  *   the encoding of the witness set's datums (key 4), none when it has no key 4
  * @param datums
  *   by hash, each its data
  */
final class TxDats private[era] (val raw: Option[Bytes], val datums: SortedMap[Bytes, Data]):
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
  *   per purpose, the redeemer's data and its units
  */
final class Redeemers[P] private[era] (
    val raw: Option[Bytes],
    val entries: SortedMap[P, (Data, ExUnits)]
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

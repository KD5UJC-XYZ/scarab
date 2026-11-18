// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.PlutusData

/** An inline datum as the bytes of its data (`newtype BinaryData era = BinaryData ShortByteString`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:211-212`, cardano-ledger f649f975).
  * The reference hides its constructor: it is made checked, its bytes read as data
  * ([[BinaryData.makeBinaryData]]), where an output is read from a block or a transaction (its
  * `DecCBOR`, `:220-223`), and taken unread where an output is read from the store (its `MemPack`,
  * derived from the newtype's, `:212`), as [[BinaryData.stored]] takes it.
  */
opaque type BinaryData = Bytes

object BinaryData:
  /** `makeBinaryData` (`Plutus/Data.hs:227-233`, cardano-ledger f649f975): the bytes, when they
    * read as one item of data with nothing after it.
    */
  def makeBinaryData(raw: Bytes): Either[PlutusData.DecodeError, BinaryData] =
    Data.decode(raw).map(_ => raw)

  /** The bytes of an inline datum taken unread, as the reference's `MemPack` instance takes a
    * stored one (`Plutus/Data.hs:212`, cardano-ledger f649f975). Only
    * [[BabbageTxOutRep.fromStored]] can take them so, as it alone makes the evidence this asks for
    * ([[BabbageTxOutRep.StoreRead]]); its callers are trusted to pass an output read from the
    * store.
    */
  private[era] def stored(raw: Bytes)(using BabbageTxOutRep.StoreRead): BinaryData = raw

  /** `binaryDataToData` (`Plutus/Data.hs:244-249`, cardano-ledger f649f975): the data the bytes
    * hold. The reference raises `error` where they do not read as data, which only bytes taken
    * unread from the store can reach; the failure is returned here.
    */
  def binaryDataToData(binaryData: BinaryData): Either[PlutusData.DecodeError, Data] =
    Data.decode(binaryData)

  extension (binaryData: BinaryData)
    /** The bytes of the data (`SafeToHash`, `originalBytes`). */
    def bytes: Bytes = binaryData

  given CanEqual[BinaryData, BinaryData] = CanEqual.derived

/** The datum of an output (`data Datum era`, `Plutus/Data.hs:272-275`, cardano-ledger f649f975):
  * none, the hash of a datum, or an inline datum.
  */
enum Datum:
  case NoDatum
  case DatumHash(dataHash: Bytes)
  case Datum(binaryData: BinaryData)

object Datum:
  // in this object `Datum` is the case of an inline datum; the type is named in full
  private type Era = xyz.kd5ujc.scarab.ledger.era.Datum

  given CanEqual[Era, Era] = CanEqual.derived

  /** The datum of an output its era's decoder read from a block or a transaction: an inline datum
    * read as data (`DecCBOR (Datum era)`, `Plutus/Data.hs:303-310`, cardano-ledger f649f975,
    * through `makeBinaryData`), refused as the decoder refuses it.
    */
  private[era] def decoded(
      datum: xyz.kd5ujc.scarab.ledger.Datum
  ): Either[xyz.kd5ujc.scarab.ledger.LedgerDecodeError, Era] = datum match
    case xyz.kd5ujc.scarab.ledger.Datum.NoDatum => Right(NoDatum)
    case xyz.kd5ujc.scarab.ledger.Datum.Hash(h) => Right(DatumHash(h))
    case xyz.kd5ujc.scarab.ledger.Datum.Inline(raw) =>
      BinaryData
        .makeBinaryData(raw)
        .map(Datum(_))
        .left
        .map(
          xyz.kd5ujc.scarab.ledger.LedgerDecodeError.PlutusData(
            xyz.kd5ujc.scarab.ledger.DecodeField.InlineDatum,
            _
          )
        )

  /** The datum of an output read from the store, an inline datum taken unread
    * ([[BinaryData.stored]]), through [[BabbageTxOutRep.fromStored]] alone
    * ([[BabbageTxOutRep.StoreRead]]).
    */
  private[era] def stored(datum: xyz.kd5ujc.scarab.ledger.Datum)(using
      BabbageTxOutRep.StoreRead
  ): Era = datum match
    case xyz.kd5ujc.scarab.ledger.Datum.NoDatum     => NoDatum
    case xyz.kd5ujc.scarab.ledger.Datum.Hash(h)     => DatumHash(h)
    case xyz.kd5ujc.scarab.ledger.Datum.Inline(raw) => Datum(BinaryData.stored(raw))

  /** The datum in the form the store keeps it, the inverse of [[decoded]] and [[stored]]. */
  private[era] def toStored(datum: Era): xyz.kd5ujc.scarab.ledger.Datum = datum match
    case NoDatum      => xyz.kd5ujc.scarab.ledger.Datum.NoDatum
    case DatumHash(h) => xyz.kd5ujc.scarab.ledger.Datum.Hash(h)
    case Datum(b)     => xyz.kd5ujc.scarab.ledger.Datum.Inline(b.bytes)

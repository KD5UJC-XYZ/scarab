// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  DecodeField,
  FieldKeys,
  LedgerDecodeError
}

/** The output the reference shares from Babbage on (`BabbageTxOut era` for Babbage, Conway and
  * Dijkstra, cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:133-158`): an address, a multi-asset
  * value, a datum and an optional reference script, with the bytes it arrived as. Parameterised by
  * what differs between the eras, the script `S`: [[babbage.BabbageScript]] behind Babbage's
  * output, [[conway.ConwayScript]] behind Conway's. Each era's output is an opaque type over it, so
  * the representation is shared and the types are not.
  *
  * Two outputs are equal when their address, value, datum and reference script are, as the
  * reference's derived `Eq` compares its compact fields (`Babbage/TxOut.hs:318-320`, cardano-ledger
  * f649f975): the bytes an output arrived as, and its value's size, are kept beside them and are
  * not compared. An inline datum and a script compare by their own bytes, which the reference keeps
  * (`BinaryData`, a script's `MemoBytes`).
  *
  * The datum is the era's ([[Datum]]): an inline datum read as data where the output was read from
  * a block or a transaction ([[fromDecoded]]), and taken unread where it was read from the store
  * ([[fromStored]]), as the reference's `DecCBOR` and `MemPack` of `BinaryData` take it.
  *
  * @param valueSize
  *   the size in bytes of the value as it arrived, as the flat output keeps it
  * @param scriptRef
  *   the reference script, the era's script with its bytes
  */
final case class BabbageTxOutRep[S](
    raw: Bytes,
    address: Address,
    value: MultiAssetValue,
    valueSize: Int,
    datum: Datum,
    scriptRef: Option[S]
):
  override def equals(that: Any): Boolean = that match
    case o: BabbageTxOutRep[?] =>
      address == o.address && value == o.value && datum == o.datum && scriptRef == o.scriptRef
    case _ => false
  override def hashCode(): Int = (address, value, datum, scriptRef).hashCode

object BabbageTxOutRep:
  // The read below is of `raw`, which this output was already decoded from under the policy of its
  // decoder: it takes any form and any key that decode took (as the flat output's own re-reads,
  // `TxOut.referenceScript`).
  private given ByteStrings = ByteStrings.Chunked

  /** The evidence an inline datum's unchecked construction asks for ([[BinaryData.stored]],
    * [[Datum.stored]]). Its constructor is this object's, which makes one in [[fromStored]] alone,
    * so no other code takes bytes as data unread. That the output `fromStored` is given was read
    * from the store is not a type: its callers, each era's `fromStored` (`private[ledger]`), are
    * trusted to pass one, as the reference trusts what its `MemPack` reads; in the node's code they
    * are reached from `ConwayUTxO.fromStored` alone. A store that reads the era's outputs itself
    * would make it a type (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6989194084).
    */
  final class StoreRead private[BabbageTxOutRep] ()

  /** An output its era's decoder read from a block or a transaction, as the representation of an
    * era whose script `decodeScript` reads: its inline datum read as data ([[Datum.decoded]], the
    * reference's `DecCBOR`) and its reference script as [[fromStored]] reads it.
    */
  private[era] def fromDecoded[S](out: xyz.kd5ujc.scarab.ledger.TxOut)(
      decodeScript: Node => ByteStrings ?=> Either[LedgerDecodeError, S]
  ): Either[LedgerDecodeError, BabbageTxOutRep[S]] =
    Datum.decoded(out.datum).flatMap(of(out, _)(decodeScript))

  /** A stored output as the representation of an era whose script `decodeScript` reads: its inline
    * datum taken unread ([[Datum.stored]], the reference's `MemPack`), and its reference script
    * read again from its bytes, in the era's script type (`#6.24(bytes .cbor script)`, field 3 of
    * the map form). The flat output keeps the hash of a reference script and not its bytes, so the
    * era's script is read from the output's encoding, as the flat output's `referenceScript` reads
    * it.
    */
  private[era] def fromStored[S](out: xyz.kd5ujc.scarab.ledger.TxOut)(
      decodeScript: Node => ByteStrings ?=> Either[LedgerDecodeError, S]
  ): Either[LedgerDecodeError, BabbageTxOutRep[S]] =
    given StoreRead = StoreRead()
    of(out, Datum.stored(out.datum))(decodeScript)

  private def of[S](out: xyz.kd5ujc.scarab.ledger.TxOut, datum: Datum)(
      decodeScript: Node => ByteStrings ?=> Either[LedgerDecodeError, S]
  ): Either[LedgerDecodeError, BabbageTxOutRep[S]] =
    val script =
      if out.scriptRef.isEmpty then Right(None)
      else
        for
          node <- xyz.kd5ujc.scarab.ledger.TxOut.node(out.raw)
          f <- CborRead.fields(node, DecodeField.Output, FieldKeys.Stored)
          field <- f
            .get(3)
            .toRight(
              LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.ReferenceScript)
            )
          inner <- field.value match
            case codec.Value.Tag(t, i) if t == 24 =>
              CborRead.bytes(i, DecodeField.ReferenceScript)
            // every other shape, as the flat reader refuses it (`Script.decodeWrappedWithBytes`)
            case codec.Value.Tag(_, _) | codec.Value.UInt(_) | codec.Value.NInt(_) |
                codec.Value.ByteString(_) | codec.Value.Text(_) | codec.Value.Arr(_) |
                codec.Value.Map(_) | codec.Value.Bool(_) | codec.Value.Null |
                codec.Value.Simple(_) | codec.Value.Float(_, _) =>
              Left(LedgerDecodeError.ExpectedTag(DecodeField.ReferenceScript, 24, field.value))
          scriptNode <- CborRead.decode(inner)
          s <- decodeScript(scriptNode)
        yield Some(s)
    script.map(s =>
      BabbageTxOutRep(
        out.raw,
        out.address,
        MultiAssetValue.of(out.value),
        out.valueSize,
        datum,
        s
      )
    )

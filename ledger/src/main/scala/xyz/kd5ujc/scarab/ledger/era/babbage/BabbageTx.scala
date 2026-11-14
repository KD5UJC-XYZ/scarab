// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum, Script, TxIn, Update}
import xyz.kd5ujc.scarab.ledger.era.{
  MultiAssetValue,
  Val,
  BabbageEraTxOut,
  BabbageTxOutRep,
  EraTxError,
  ShelleyEraTxBody,
  TopOnly,
  TxBody,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.Babbage
import xyz.kd5ujc.scarab.protocol.Era

/** A Babbage output: `BabbageTxOut BabbageEra` (cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:133-158`). Only this package and its
  * decoder make one.
  */
opaque type BabbageTxOut = BabbageTxOutRep

object BabbageTxOut:
  /** The stored output read as a Babbage output (`ejectHardForkTxOut` of the current era,
    * ouroboros-consensus 82ecba329 `Cardano/Ledger.hs:137-162`).
    */
  private[ledger] def fromStored(out: xyz.kd5ujc.scarab.ledger.TxOut): BabbageTxOut =
    BabbageTxOutRep.of(out)

  extension (out: BabbageTxOut)
    /** The shared representation, read only: the translation into the next era reads it. */
    def rep: BabbageTxOutRep = out

  val instance: BabbageEraTxOut[Babbage] = new BabbageEraTxOut[Babbage]:
    type EraValue = MultiAssetValue
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: BabbageTxOut): Address = out.address
    override def value(out: BabbageTxOut): MultiAssetValue = MultiAssetValue.of(out.value)
    override def raw(out: BabbageTxOut): Bytes = out.raw
    override def datum(out: BabbageTxOut): Datum = out.datum
    override def referenceScript(out: BabbageTxOut): Option[Script] = out.scriptRef

/** A Babbage transaction body: `TxBody l BabbageEra` (`BabbageTxBodyRaw`,
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxBody.hs`). Spike: the fields the spike's rules
  * read.
  */
final case class BabbageTxBody private (
    raw: Bytes,
    inputs: Vector[TxIn],
    outputs: Vector[BabbageTxOut],
    fee: BigInt,
    update: Option[Update]
):
  lazy val id: Bytes = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)

object BabbageTxBody:
  /** A Babbage body from its bytes: the era's decoder, its outputs Babbage outputs. */
  def decode(raw: Bytes): Either[EraTxError, BabbageTxBody] =
    xyz.kd5ujc.scarab.ledger.TxBody
      .decode(raw, Era.Babbage)
      .leftMap(EraTxError.Decode(_))
      .map(b =>
        BabbageTxBody(b.raw, b.inputs, b.outputs.map(BabbageTxOut.fromStored), b.fee, b.update)
      )

  val instance: ShelleyEraTxBody[Babbage] = new ShelleyEraTxBody[Babbage]:
    type Level[L <: TxLevel] = TopOnly[L]
    override def top: TopOnly[TxLevel.Top] = TopOnly.Top
    override def id[L <: TxLevel](body: TxBody[L, Babbage])(using l: TopOnly[L]): Bytes =
      l match
        case TopOnly.Top => body.id
    override def inputs[L <: TxLevel](body: TxBody[L, Babbage])(using l: TopOnly[L]): Vector[TxIn] =
      l match
        case TopOnly.Top => body.inputs
    override def outputs[L <: TxLevel](body: TxBody[L, Babbage])(using
        l: TopOnly[L]
    ): Vector[BabbageTxOut] =
      l match
        case TopOnly.Top => body.outputs
    override def fee(body: BabbageTxBody): BigInt = body.fee
    override def update(body: BabbageTxBody): Option[Update] = body.update

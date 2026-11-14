// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum, Script, TxIn, Value}
import xyz.kd5ujc.scarab.ledger.era.{
  BabbageEraTxOut,
  BabbageTxOutRep,
  ConwayEraTxBody,
  EraTxError,
  TopOnly,
  TxBody,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxOut
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.Era

/** A Conway output: `BabbageTxOut ConwayEra`, the representation Babbage's has (cardano-ledger
  * f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/TxOut.hs`), a type of its own. Only this
  * package, its decoder and the translation from Babbage make one.
  */
opaque type ConwayTxOut = BabbageTxOutRep

object ConwayTxOut:
  /** The stored output read as a Conway output (`ejectHardForkTxOut`, ouroboros-consensus 82ecba329
    * `Cardano/Ledger.hs:137-162`).
    */
  def fromStored(out: xyz.kd5ujc.scarab.ledger.TxOut): ConwayTxOut = BabbageTxOutRep.of(out)

  /** A Babbage output carried into Conway (`upgradeTxOut`, `Conway/TxOut.hs:68-81`): the same
    * address, value, datum and reference script, and the same bytes.
    */
  def translate(out: BabbageTxOut): ConwayTxOut = out.rep

  val instance: BabbageEraTxOut[Conway] = new BabbageEraTxOut[Conway]:
    override def address(out: ConwayTxOut): Address = out.address
    override def value(out: ConwayTxOut): Value = out.value
    override def raw(out: ConwayTxOut): Bytes = out.raw
    override def datum(out: ConwayTxOut): Datum = out.datum
    override def referenceScript(out: ConwayTxOut): Option[Script] = out.scriptRef

/** A Conway transaction body: `TxBody l ConwayEra` (`ConwayTxBodyRaw`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs`). Spike: the fields the spike's rules
  * read.
  */
final case class ConwayTxBody private (
    raw: Bytes,
    inputs: Vector[TxIn],
    outputs: Vector[ConwayTxOut],
    fee: BigInt,
    currentTreasury: Option[BigInt]
):
  lazy val id: Bytes = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)

object ConwayTxBody:
  /** A Conway body from its bytes: the era's decoder, its outputs Conway outputs. */
  def decode(raw: Bytes): Either[EraTxError, ConwayTxBody] =
    xyz.kd5ujc.scarab.ledger.TxBody
      .decode(raw, Era.Conway)
      .leftMap(EraTxError.Decode(_))
      .map(b =>
        ConwayTxBody(
          b.raw,
          b.inputs,
          b.outputs.map(ConwayTxOut.fromStored),
          b.fee,
          b.currentTreasury
        )
      )

  val instance: ConwayEraTxBody[Conway] = new ConwayEraTxBody[Conway]:
    type Level[L <: TxLevel] = TopOnly[L]
    override def top: TopOnly[TxLevel.Top] = TopOnly.Top
    override def id[L <: TxLevel](body: TxBody[L, Conway])(using l: TopOnly[L]): Bytes =
      l match
        case TopOnly.Top => body.id
    override def inputs[L <: TxLevel](body: TxBody[L, Conway])(using l: TopOnly[L]): Vector[TxIn] =
      l match
        case TopOnly.Top => body.inputs
    override def outputs[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Vector[ConwayTxOut] =
      l match
        case TopOnly.Top => body.outputs
    override def fee(body: ConwayTxBody): BigInt = body.fee
    override def currentTreasury[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Option[BigInt] =
      l match
        case TopOnly.Top => body.currentTreasury

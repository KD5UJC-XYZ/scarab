// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Datum, Script, Value}

/** The output the reference shares from Babbage on (`BabbageTxOut era` for Babbage, Conway and
  * Dijkstra, cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:133-158`): an address, a multi-asset
  * value, a datum and an optional reference script, with the bytes it arrived as. Each era's output
  * is an opaque type over it ([[babbage.BabbageTxOut]], [[conway.ConwayTxOut]]), so the
  * representation is shared and the types are not.
  */
final case class BabbageTxOutRep(
    raw: Bytes,
    address: Address,
    value: Value,
    datum: Datum,
    scriptRef: Option[Script]
)

object BabbageTxOutRep:
  /** The representation of a decoded output (the flat output of the decoders and the store). */
  def of(out: xyz.kd5ujc.scarab.ledger.TxOut): BabbageTxOutRep =
    BabbageTxOutRep(out.raw, out.address, out.value, out.datum, out.scriptRef)

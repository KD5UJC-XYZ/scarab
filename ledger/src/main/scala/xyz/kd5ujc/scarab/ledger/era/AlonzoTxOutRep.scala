// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Address

/** Alonzo's output (`AlonzoTxOut era`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxOut.hs:144`): an address, a multi-asset value and
  * an optional datum hash, with the bytes it arrived as. Only Alonzo's output is one; it has no
  * parameter, as no other era shares it.
  *
  * Two outputs are equal when their address, value and datum hash are, as the reference's derived
  * `Eq` compares its fields (`Alonzo/TxOut.hs:198`, cardano-ledger f649f975): the bytes an output
  * arrived as, and its value's size, are kept beside them and are not compared.
  *
  * @param valueSize
  *   the size in bytes of the value as it arrived, as the flat output keeps it
  * @param dataHash
  *   the datum hash, if the output has one
  */
final case class AlonzoTxOutRep(
    raw: Bytes,
    address: Address,
    value: MultiAssetValue,
    valueSize: Int,
    dataHash: Option[Bytes]
):
  override def equals(that: Any): Boolean = that match
    case o: AlonzoTxOutRep =>
      address == o.address && value == o.value && dataHash == o.dataHash
    case _ => false
  override def hashCode(): Int = (address, value, dataHash).hashCode

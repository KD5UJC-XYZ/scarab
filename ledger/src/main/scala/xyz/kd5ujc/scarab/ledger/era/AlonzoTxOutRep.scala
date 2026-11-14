// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Address

/** Alonzo's output (`AlonzoTxOut era`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxOut.hs:144`): an address, a multi-asset value and
  * an optional datum hash, with the bytes it arrived as. Only Alonzo's output is one; it has no
  * parameter, as no other era shares it.
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
)

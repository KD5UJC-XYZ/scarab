// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Address

/** The output the reference shares from Shelley to Mary (`ShelleyTxOut era`, cardano-ledger
  * f649f975 `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxOut.hs:56-59`): an address and a value,
  * with the bytes it arrived as. Parameterised by what differs between the eras, the value: `V =`
  * [[AdaValue]] behind the Shelley and Allegra outputs, [[MultiAssetValue]] behind Mary's.
  *
  * Two outputs are equal when their address and value are, as the reference's derived `Eq` compares
  * its compact fields (`Shelley/TxOut.hs:124`, cardano-ledger f649f975): the bytes an output
  * arrived as, and its value's size, are kept beside them and are not compared.
  *
  * @param valueSize
  *   the size in bytes of the value as it arrived, as the flat output keeps it
  */
final case class ShelleyTxOutRep[V](raw: Bytes, address: Address, value: V, valueSize: Int):
  override def equals(that: Any): Boolean = that match
    case o: ShelleyTxOutRep[?] => address == o.address && value == o.value
    case _                     => false
  override def hashCode(): Int = (address, value).hashCode

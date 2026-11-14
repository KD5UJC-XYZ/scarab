// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Address

/** The output the reference shares from Shelley to Mary (`ShelleyTxOut era`, cardano-ledger
  * f649f975 `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxOut.hs:56-59`): an address and a value,
  * with the bytes it arrived as. Parameterised by what differs between the eras, the value: `V =`
  * [[AdaValue]] behind the Shelley and Allegra outputs, [[MultiAssetValue]] behind Mary's.
  *
  * @param valueSize
  *   the size in bytes of the value as it arrived, as the flat output keeps it
  */
final case class ShelleyTxOutRep[V](raw: Bytes, address: Address, value: V, valueSize: Int)

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.plutus.PlutusData

/** Plutus `Data` where a transaction carries it, read as the reference's decoders read it when they
  * decode the transaction (#721): a datum of the witness set and a redeemer's data are `Data era`,
  * whose decoder is the `Serialise` instance of Plutus's `Data` (`decCBOR = fromPlainDecoder
  * Cborg.decode`, `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:97-125`,
  * cardano-ledger f649f975; `decodeData`, `PlutusCore/Data.hs:209-265`, plutus 507cab8c), and an
  * inline datum is `BinaryData`, its bytes decoded as `Data` in full (`makeBinaryData`,
  * `Plutus/Data.hs:217-239`). Anything else fails the decoder, and with it the transaction and its
  * block. The original bytes are kept as read; only the verdict is taken here.
  */
private[ledger] object Data:
  /** The item `n`, read as `Data`. */
  def read(n: Node, site: DecodeSite): Either[LedgerDecodeError, Unit] =
    PlutusData.fromNode(n).bimap(LedgerDecodeError.PlutusData(site, _), _ => ())

  /** The bytes `raw`, read as one item of `Data` with nothing after it. */
  def readBytes(raw: Bytes, site: DecodeSite): Either[LedgerDecodeError, Unit] =
    PlutusData.decode(raw).bimap(LedgerDecodeError.PlutusData(site, _), _ => ())

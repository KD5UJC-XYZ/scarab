// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.DecodeField

import V12.Result

/** A bootstrap witness, its fields as read. */
final case class DijkstraBootstrap(
    key: Bytes,
    signature: Bytes,
    chainCode: Bytes,
    attributes: Bytes
)

/** The readers of a Dijkstra witness set's key witnesses that Dijkstra's era decoder
  * ([[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxWits]]) reads them with (`AlonzoTxWitsRaw`
  * from version 12, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:620-700`, cardano-ledger
  * 9df9f98c): keys 0 and 2, non-empty sets, the tag 258 allowed, no witness twice under its `Ord`
  * (a vkey witness by its key's and signature's hashes, `Keys/WitVKey.hs:70-81`; a bootstrap
  * witness by the hash of its key, chain code and attributes, its signature not compared,
  * `Keys/Bootstrap.hs:115-116, 159-191`).
  */
object DijkstraWitnessReaders:
  /** `WitVKey` (`Keys/WitVKey.hs:106-109`, cardano-ledger 9df9f98c): `decodeRecordNamed` of a
    * 32-byte Ed25519 key and a 64-byte signature, each `decodeFixedSized`.
    */
  private[ledger] def vkeyWitnesses(n: Node): Result[Vector[(Bytes, Bytes)]] =
    V12.nonEmptySet(n, DecodeField.VkeyWitnesses)(w =>
      V12
        .record(w, 2, DecodeField.VkeyWitness)
        .flatMap(xs =>
          (
            V12.bytesN(xs(0), 32, DecodeField.Vkey),
            V12.bytesN(xs(1), 64, DecodeField.Signature)
          ).tupled
        )
    )(identity)

  /** `BootstrapWitness` (`Keys/Bootstrap.hs:79-112`, cardano-ledger 9df9f98c): `decodeRecordNamed`
    * of the key, the signature, a chain code of exactly 32 bytes from version 12, and the
    * attributes.
    */
  private[ledger] def bootstrapWitnesses(n: Node): Result[Vector[DijkstraBootstrap]] =
    V12.nonEmptySet(n, DecodeField.BootstrapWitnesses)(w =>
      V12
        .record(w, 4, DecodeField.BootstrapWitness)
        .flatMap(xs =>
          (
            V12.bytesN(xs(0), 32, DecodeField.BootstrapVkey),
            V12.bytesN(xs(1), 64, DecodeField.BootstrapSignature),
            V12.bytesN(xs(2), 32, DecodeField.ChainCode),
            V12.bytes(xs(3), DecodeField.Attributes)
          ).mapN(DijkstraBootstrap(_, _, _, _))
        )
    )(b => (b.key, b.chainCode, b.attributes))

end DijkstraWitnessReaders

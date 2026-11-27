// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import scala.collection.immutable.TreeMap
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{
  ByteStrings,
  CborRead,
  DecodeField,
  DecoderVersion,
  LedgerDecodeError,
  Update as StoredUpdate
}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, EraTag, Genesis, KeyHash, Sorted}

/** The updates of the parameters the genesis delegates propose (`newtype ProposedPPUpdates era`,
  * cardano-ledger f649f975 `eras/shelley/impl/src/Cardano/Ledger/Shelley/PParams.hs:270-272`), by
  * the key hash of each delegate. Equal as the reference derives `Eq` (`:274`), by the updates'
  * values.
  */
final case class ProposedPPUpdates[E <: EraTag](updates: Map[KeyHash[Genesis], PParamsUpdate[E]])

/** An update proposal of the eras before Conway (`data Update era = Update !(ProposedPPUpdates era)
  * !EpochNo`, `Shelley/PParams.hs:247-249`, cardano-ledger f649f975): the proposed updates and the
  * epoch they are for. Equal as the reference derives `Eq` (`:251`).
  */
final case class Update[E <: EraTag](proposals: ProposedPPUpdates[E], epoch: EpochNo)

/** An update proposal read from bytes, with the form the node keeps it in
  * ([[xyz.kd5ujc.scarab.ledger.Update]]): each proposed field's value as the bytes it was read from
  * ([[CborRead.kept]]), which a proposal is stored by (ADR 0006), so that a field written in a form
  * other than the canonical one is stored as it came. The reference keeps the values only and
  * writes them canonically; the node's stored form keeps the bytes, so the typed update is kept
  * beside them and both come from one read. Only [[KeptUpdate.decode]] and
  * [[KeptUpdate.fromStored]] make one, so the bytes are always those the values were read from.
  * Each delegate's update is also kept with its own bytes ([[proposed]]), the form the governance
  * state keeps a proposal in once PPUP records it; it is made in the same read, and two updates are
  * equal by their values and stored form alone.
  */
final case class KeptUpdate[E <: EraTag] private (update: Update[E], stored: StoredUpdate)(
    val proposed: KeptProposedPPUpdates[E]
)

object KeptUpdate:
  private def genesisKeyHash(n: Node)(using
      ByteStrings
  ): Either[LedgerDecodeError, (Bytes, KeyHash[Genesis])] =
    CborRead
      .bytes(n, DecodeField.GenesisKeyHash)
      .flatMap(b =>
        KeyHash
          .fromBytes[Genesis](b)
          .bimap(_ => LedgerDecodeError.ByteLength(DecodeField.GenesisKeyHash, 28, b.size), b -> _)
      )

  private def epochNo(n: BigInt): Either[LedgerDecodeError, EpochNo] =
    EpochNo
      .fromBigInt(n)
      .leftMap(_ =>
        LedgerDecodeError.NumberOutOfRange(DecodeField.UpdateEpoch, n, 0, (BigInt(1) << 64) - 1)
      )

  /** The decoder version of era `E` 's blocks, `eraProtVerLow` (`eraDecoder`, [[DecoderVersion]]),
    * which an update of the era is read at.
    */
  private def at[E <: EraTag](using era: Era[E]): DecodeAt =
    DecodeAt(DecoderVersion.ofVersion(era.eraProtVerLow), DecodeField.ParameterUpdate)

  /** `DecCBOR (Update era)` (`decode $ RecD Update <! From <! From`, `Shelley/PParams.hs:263-267`,
    * cardano-ledger f649f975), the body's key 6 in the eras up to Babbage (`updateTxBodyL`,
    * `Shelley/TxBody.hs:97-100`), whose parameters an update can set the protocol version of
    * ([[EraPParamsToBabbage]]): a record of two, the proposed updates, a map of the delegates' key
    * hashes to `DecCBOR (PParamsUpdate era)` ([[PParamsUpdate.decodePParamsUpdate]]), and the
    * epoch, a `Word64`, read at the era's decoder version, below 9 at every such era. A key hash
    * proposed twice keeps its last update, as the map's decoder below version 9 does. Each update's
    * fields are stored with the bytes of their values.
    */
  def decode[E <: EraTag](n: Node)(using
      pp: EraPParamsToBabbage[E],
      era: Era[E]
  ): Either[LedgerDecodeError, KeptUpdate[E]] =
    given DecodeAt = at[E]
    given ByteStrings = ByteStrings.of(summon[DecodeAt].version)
    for
      xs <- CborRead.record(n, 2, DecodeField.Update)
      kvs <- CborRead.map(xs(0), DecodeField.ProposedUpdates)
      proposals <- kvs.traverse((k, v) =>
        for
          (raw, hash) <- genesisKeyHash(k)
          kept <- KeptPParamsUpdate.decode[E](v)
        yield (raw, hash, kept)
      )
      epoch <- CborRead.uint(xs(1), DecodeField.UpdateEpoch)
      typed <- epochNo(epoch)
    yield KeptUpdate(
      Update(ProposedPPUpdates(proposals.map((_, h, k) => h -> k.update).toMap), typed),
      StoredUpdate(TreeMap.from(proposals.map((raw, _, k) => raw -> k.stored)), epoch)
    )(KeptProposedPPUpdates(Sorted.map(proposals.map((_, h, k) => h -> k))))

  /** The update the node keeps, read into the era at its decoder version: each stored field's bytes
    * read by its field, as [[decode]] reads a value. A stored form [[decode]] did not give may be
    * refused: a key that is not one of the era's, or a value an earlier build kept unread (ADR
    * 0006) that its field does not read. The stored form is kept as it is.
    */
  def fromStored[E <: EraTag](u: StoredUpdate)(using
      pp: EraPParamsToBabbage[E],
      era: Era[E]
  ): Either[LedgerDecodeError, KeptUpdate[E]] =
    for
      proposals <- u.proposals.toVector.traverse((raw, fields) =>
        for
          hash <- KeyHash
            .fromBytes[Genesis](raw)
            .leftMap(_ => LedgerDecodeError.ByteLength(DecodeField.GenesisKeyHash, 28, raw.size))
          kept <- KeptPParamsUpdate.fromStored[E](fields)
        yield hash -> kept
      )
      epoch <- epochNo(u.epoch)
    yield KeptUpdate(
      Update(ProposedPPUpdates(proposals.map((h, k) => h -> k.update).toMap), epoch),
      u
    )(KeptProposedPPUpdates(Sorted.map(proposals)))

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import scala.collection.immutable.TreeMap
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{
  ByteStrings,
  CborRead,
  DecodeField,
  LedgerDecodeError,
  ParamUpdate,
  Update as StoredUpdate
}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, EraTag, Genesis, KeyHash}

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
  */
final case class KeptUpdate[E <: EraTag] private (update: Update[E], stored: StoredUpdate)

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

  /** The stored form of an update's fields: each tag, one of the era's update keys, with the bytes
    * of its value.
    */
  private def stored(fields: Vector[(BigInt, Node)]): Either[LedgerDecodeError, ParamUpdate] =
    fields
      .traverse((tag, v) =>
        Either
          .cond(tag.isValidInt, tag.toInt -> CborRead.kept(v), tag)
          .leftMap(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, _))
      )
      .map(fs => ParamUpdate(fs.toMap))

  /** `DecCBOR (Update era)` (`decode $ RecD Update <! From <! From`, `Shelley/PParams.hs:263-267`,
    * cardano-ledger f649f975), the body's key 6 in the eras before Conway: a record of two, the
    * proposed updates, a map of the delegates' key hashes to `DecCBOR (PParamsUpdate era)`
    * ([[PParamsUpdate.decodePParamsUpdate]]), and the epoch, a `Word64`. A key hash proposed twice
    * keeps its last update, as the map's decoder below version 9 does. Each update's fields are
    * stored with the bytes of their values.
    */
  def decode[E <: EraTag](n: Node)(using
      era: EraPParams[E],
      at: DecodeAt
  ): Either[LedgerDecodeError, KeptUpdate[E]] =
    given ByteStrings = ByteStrings.of(at.version)
    for
      xs <- CborRead.record(n, 2, DecodeField.Update)
      kvs <- CborRead.map(xs(0), DecodeField.ProposedUpdates)
      proposals <- kvs.traverse((k, v) =>
        for
          (raw, hash) <- genesisKeyHash(k)
          (update, fields) <- PParamsUpdate.decodeFields[E](v)
          kept <- stored(fields)
        yield (raw, hash, update, kept)
      )
      epoch <- CborRead.uint(xs(1), DecodeField.UpdateEpoch)
      typed <- epochNo(epoch)
    yield KeptUpdate(
      Update(ProposedPPUpdates(proposals.map((_, h, u, _) => h -> u).toMap), typed),
      StoredUpdate(TreeMap.from(proposals.map((raw, _, _, kept) => raw -> kept)), epoch)
    )

  /** The update the node keeps, read into the era: each stored field's bytes read by its field, as
    * [[decode]] reads a value. A stored form [[decode]] did not give may be refused: a key that is
    * not one of the era's, or a value an earlier build kept unread (ADR 0006) that its field does
    * not read. The stored form is kept as it is.
    */
  def fromStored[E <: EraTag](u: StoredUpdate)(using
      era: EraPParams[E],
      at: DecodeAt
  ): Either[LedgerDecodeError, KeptUpdate[E]] =
    for
      proposals <- u.proposals.toVector.traverse((raw, fields) =>
        for
          hash <- KeyHash
            .fromBytes[Genesis](raw)
            .leftMap(_ => LedgerDecodeError.ByteLength(DecodeField.GenesisKeyHash, 28, raw.size))
          (update, _) <- PParamsUpdate.readFields[E](
            fields.fields.toVector
              .sortBy(_._1)
              .map((k, bytes) => CborRead.decode(bytes).map(BigInt(k) -> _))
          )
        yield hash -> update
      )
      epoch <- epochNo(u.epoch)
    yield KeptUpdate(Update(ProposedPPUpdates(proposals.toMap), epoch), u)

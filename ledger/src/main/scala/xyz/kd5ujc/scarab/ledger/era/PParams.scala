// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.codec.Node
import xyz.kd5ujc.scarab.ledger.{CborRead, DecodeField, DecodeSite, LedgerDecodeError, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W64
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Conway, Dijkstra, EraTag}

/** The protocol-parameter record of era `E` over `F` (`type PParamsHKD (f :: Type -> Type) era`,
  * cardano-ledger f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:376`): one
  * case per era that has its record.
  */
type PParamsHKD[F[_], E <: EraTag] = E match
  case Babbage  => babbage.BabbagePParams[F]
  case Conway   => conway.ConwayPParams[F]
  case Dijkstra => dijkstra.DijkstraPParams[F]

/** The protocol parameters of era `E` (`newtype PParams era = PParams (PParamsHKD Identity era)`,
  * `Core/PParams.hs:166`, cardano-ledger f649f975).
  */
opaque type PParams[E <: EraTag] = PParamsHKD[Identity, E]

object PParams:
  def apply[E <: EraTag](hkd: PParamsHKD[Identity, E]): PParams[E] = hkd

  /** `ppLensHKD` (`Core/PParams.hs:507-508`, cardano-ledger f649f975). */
  def lensHKD[E <: EraTag]: Lens[PParams[E], PParamsHKD[Identity, E]] =
    Lens[PParams[E], PParamsHKD[Identity, E]](p => p)(h => _ => h)

  extension [E <: EraTag](p: PParams[E]) def hkd: PParamsHKD[Identity, E] = p

  /** `instance EraPParams era => EncCBOR (PParams era)` (`Core/PParams.hs:204-209`, cardano-ledger
    * 9df9f98c, and f649f975 `:198-203`): a definite list of the era's parameters in the order of
    * its descriptions (`eraPParams`), each its value's `encCBOR`.
    */
  def encodePParams[E <: EraTag](pp: PParams[E])(using era: EraPParams[E]): Encoding =
    Encoding.encodeListLen(era.eraPParams.size) |+|
      era.eraPParams.foldMap(d => d.encCBOR.encCBOR(d.ppLens.get(pp)))

  /** [[encodePParams]]. */
  given encCBOR[E <: EraTag](using EraPParams[E]): EncCBOR[PParams[E]] = encodePParams(_)

  /** `instance EraPParams era => DecCBOR (PParams era)` (`Core/PParams.hs:211-219`, cardano-ledger
    * 9df9f98c, and f649f975 `:205-213`): `decodeRecordNamed` of as many items as the era has
    * descriptions, a definite or an indefinite list, each item read by its description's `decCBOR`
    * and set by its lens, from the empty parameters, and then the list's length checked
    * ([[DecCBOR.recordNamed]]). A refusal of an item names its position.
    */
  def decodePParams[E <: EraTag](n: Node)(using
      era: EraPParams[E],
      at: DecodeAt
  ): Either[LedgerDecodeError, PParams[E]] =
    DecCBOR.recordNamed(n, era.eraPParams.size, at.site)(
      era.eraPParams.zipWithIndex.foldLeftM[DecCBOR.RecordRead, PParams[E]](era.emptyPParams) {
        case (acc, (d, i)) =>
          DecCBOR
            .field(d.decCBOR.decCBOR(_)(using at.at(DecodeSite.Index(i))))
            .map(d.ppLens.replace(_)(acc))
      }
    )

  /** [[decodePParams]]. */
  given decCBOR[E <: EraTag](using EraPParams[E]): DecCBOR[PParams[E]] with
    def decCBOR(n: Node)(using DecodeAt): Either[LedgerDecodeError, PParams[E]] = decodePParams(n)

/** A refusal of the era types' parameter codecs that names more than the node's flat refusals do,
  * carried by [[LedgerDecodeError.PParamsCodec]].
  */
enum PParamsDecodeError:
  /** A key an update holds twice, with the key: from version 12 `decodeSparseKeyed` 's "Duplicate
    * field key" (`Decoding/Decoder.hs:1276-1285`, cardano-ledger 9df9f98c), below it `applyField`
    * 's `duplicateKey` (`Decoding/Coders.hs:565-571, 634-639`, cardano-ledger f649f975).
    */
  case DuplicateKey(site: DecodeSite, key: BigInt)

  /** A record of an indefinite list that ends before its field `field` (counted from 0): the
    * field's reader, run by `decodeRecordNamed` (`decodeListLikeT`, `Decoding/Decoder.hs:682-700`,
    * cardano-ledger 9df9f98c, and f649f975 `:691-709`), meets the list's break and refuses it.
    */
  case Break(site: DecodeSite, field: Int)

/** An update of the protocol parameters of era `E` (`newtype PParamsUpdate era = PParamsUpdate
  * (PParamsHKD StrictMaybe era)`, `Core/PParams.hs:224`, cardano-ledger f649f975).
  */
opaque type PParamsUpdate[E <: EraTag] = PParamsHKD[StrictMaybe, E]

object PParamsUpdate:
  def apply[E <: EraTag](hkd: PParamsHKD[StrictMaybe, E]): PParamsUpdate[E] = hkd

  /** `ppuLensHKD` (`Core/PParams.hs:510-511`, cardano-ledger f649f975). */
  def lensHKD[E <: EraTag]: Lens[PParamsUpdate[E], PParamsHKD[StrictMaybe, E]] =
    Lens[PParamsUpdate[E], PParamsHKD[StrictMaybe, E]](p => p)(h => _ => h)

  extension [E <: EraTag](p: PParamsUpdate[E]) def hkd: PParamsHKD[StrictMaybe, E] = p

  /** `instance EraPParams era => EncCBOR (PParamsUpdate era)` (`Core/PParams.hs:250-262`,
    * cardano-ledger 9df9f98c, and f649f975 `:244-255`): a definite map of the fields the update
    * sets, in the order of the era's descriptions (`eraPParams`), each under its tag (`encodeWord`)
    * and written by the update's era codec or else its value's `encCBOR`.
    */
  def encodePParamsUpdate[E <: EraTag](ppu: PParamsUpdate[E])(using era: EraPParams[E]): Encoding =
    val fields = era.eraPParams.flatMap(d =>
      d.ppUpdate.flatMap(u =>
        u.ppuLens
          .get(ppu)
          .map(v =>
            Encoding.encodeWord(u.ppuTag) |+|
              u.ppuEraCodec.fold(d.encCBOR.encCBOR(v))(_.eraCodecEncoder(v))
          )
      )
    )
    Encoding.encodeMapLen(fields.size) |+| fields.combineAll

  /** [[encodePParamsUpdate]]. */
  given encCBOR[E <: EraTag](using EraPParams[E]): EncCBOR[PParamsUpdate[E]] =
    encodePParamsUpdate(_)

  /** A field an update's decoder reads: its value's reader and where it sets it (`Field`, the
    * `mkField` of `Core/PParams.hs:291-300`, cardano-ledger 9df9f98c).
    */
  private final case class UpdateField[E <: EraTag, T](u: PParamUpdate[E, T], dec: DecCBOR[T]):
    def read(acc: PParamsUpdate[E], n: Node)(using
        at: DecodeAt
    ): Either[LedgerDecodeError, PParamsUpdate[E]] =
      dec.decCBOR(n).map(v => u.ppuLens.replace(Some(v))(acc))

  /** The fields of the era's updates by tag (`updateFieldMap`, `Core/PParams.hs:283-290`,
    * cardano-ledger 9df9f98c): each description with an update, read by its update's era codec or
    * else its value's `decCBOR`.
    */
  private def updateFields[E <: EraTag](era: EraPParams[E]): Map[BigInt, UpdateField[E, ?]] =
    era.eraPParams
      .flatMap(d =>
        d.ppUpdate.map(u =>
          u.ppuTag.value -> UpdateField(u, u.ppuEraCodec.fold(d.decCBOR)(_.eraCodecDecoder))
        )
      )
      .toMap

  /** `instance EraPParams era => DecCBOR (PParamsUpdate era)` (`Core/PParams.hs:264-301`,
    * cardano-ledger 9df9f98c, and f649f975 `:257-293`): from version 12 `decodeSparseKeyed` with no
    * required key (`Binary/Decoding/Decoder.hs:1228-1287`): a definite or an indefinite map whose
    * entries are read in order, each key by `decodeWord`, a key read before refused by the key
    * ([[PParamsDecodeError.DuplicateKey]]), then a key of no field, then the value read by its
    * field. Below 12 the `SparseKeyed` of `Coders`, read in the same order, its keys by
    * `decodeWord` as the node's decoder of a Conway update reads them
    * ([[xyz.kd5ujc.scarab.ledger.ConwayParamUpdate.decode]]). An empty update decodes; GOV refuses
    * it (`ppuWellFormed`).
    */
  def decodePParamsUpdate[E <: EraTag](n: Node)(using
      era: EraPParams[E],
      at: DecodeAt
  ): Either[LedgerDecodeError, PParamsUpdate[E]] =
    decodeEntries(n, ())((_, _, _) => ()).map(_._1)

  /** [[decodePParamsUpdate]], with the node each field's value was read from, by tag: the bytes the
    * node keeps a pre-Conway update by are those values' ([[xyz.kd5ujc.scarab.ledger.ParamUpdate]],
    * [[KeptUpdate]]).
    */
  private[era] def decodeFields[E <: EraTag](n: Node)(using
      era: EraPParams[E],
      at: DecodeAt
  ): Either[LedgerDecodeError, (PParamsUpdate[E], Vector[(BigInt, Node)])] =
    decodeEntries(n, Vector.empty[(BigInt, Node)])((read, tag, v) => read :+ (tag -> v))

  /** The entries of an update's map, each key read as it is reached ([[readFields]]). */
  private def decodeEntries[E <: EraTag, K](n: Node, kept: K)(keep: (K, BigInt, Node) => K)(using
      era: EraPParams[E],
      at: DecodeAt
  ): Either[LedgerDecodeError, (PParamsUpdate[E], K)] =
    val from12 = at.version.atLeast(12)
    val entries = if from12 then V12.entries(n, at.site) else CborRead.map(n, at.site)
    def key(k: Node): Either[LedgerDecodeError, BigInt] =
      if from12 then V12.word64(k, at.site / DecodeSite.Qualifier.Key)
      else Unsigned.read[W64](k, DecodeField.ParameterIndex).map(_.value)
    entries.flatMap(readFields(_, kept)((k, v) => key(k).map(_ -> v), keep))

  /** The update of the fields `entries` give, in order, each read by `entry` into a tag and the
    * node of its value when it is reached: a tag read before refused by the tag
    * ([[PParamsDecodeError.DuplicateKey]]), then a tag of no field, then the value read by its
    * field, and `keep` given each field read. The first entry that fails is the refusal, and no
    * later one is read, as the reference's decoders read the map in order.
    */
  private[era] def readFields[E <: EraTag, A, K](entries: Vector[A], kept: K)(
      entry: A => Either[LedgerDecodeError, (BigInt, Node)],
      keep: (K, BigInt, Node) => K
  )(using era: EraPParams[E], at: DecodeAt): Either[LedgerDecodeError, (PParamsUpdate[E], K)] =
    val fields = updateFields(era)
    entries
      .foldLeftM((Set.empty[BigInt], era.emptyPParamsUpdate, kept)) { case ((seen, acc, k), a) =>
        for
          (tag, v) <- entry(a)
          _ <- Either.cond(
            !seen.contains(tag),
            (),
            LedgerDecodeError.PParamsCodec(PParamsDecodeError.DuplicateKey(at.site, tag))
          )
          field <- fields.get(tag).toRight(LedgerDecodeError.UnknownKey(at.site, tag))
          next <- field.read(acc, v)
        yield (seen + tag, next, keep(k, tag, v))
      }
      .map((_, u, k) => (u, k))

  /** [[decodePParamsUpdate]]. */
  given decCBOR[E <: EraTag](using EraPParams[E]): DecCBOR[PParamsUpdate[E]] with
    def decCBOR(n: Node)(using DecodeAt): Either[LedgerDecodeError, PParamsUpdate[E]] =
      decodePParamsUpdate(n)

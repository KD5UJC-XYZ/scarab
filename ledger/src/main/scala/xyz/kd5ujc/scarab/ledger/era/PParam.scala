// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import monocle.Lens
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W64
import xyz.kd5ujc.scarab.ledger.state.EraTag

/** One protocol parameter of era `E` and what serialises it (`data PParam era`, cardano-ledger
  * 9df9f98c `libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:828-837`): its name, the
  * key of its JSON; its lens into the parameters; its update, if an update can set it; how its
  * value is shown as Plutus data; and its value's CBOR, which the parameters' and an update's
  * codecs fold over ([[PParams.encCBOR]], [[PParamsUpdate.encCBOR]]). The type of the value is the
  * description's own, as the reference's constructor hides it.
  *
  * At f649f975 the description also packs an era's decoder of an update's value (`ppEraDecoder`,
  * `Core/PParams.hs:787-799`, cardano-ledger f649f975); no description of Conway's has one, and the
  * Leios build's pin moves it into the update with an encoder (`ppuEraCodec`), which is the form
  * here. The value's JSON is not here yet (#85).
  */
sealed trait PParam[E <: EraTag]:
  type T
  def ppName: String
  def ppLens: Lens[PParams[E], T]
  def ppUpdate: Option[PParamUpdate[E, T]]
  def plutusData: ToPlutusData[T]
  def encCBOR: EncCBOR[T]
  def decCBOR: DecCBOR[T]

object PParam:
  /** `PParam` (`Core/PParams.hs:828-837`, cardano-ledger 9df9f98c). */
  def apply[E <: EraTag, A](
      name: String,
      lens: Lens[PParams[E], A],
      update: Option[PParamUpdate[E, A]]
  )(using t: ToPlutusData[A], e: EncCBOR[A], d: DecCBOR[A]): PParam[E] =
    new PParam[E]:
      type T = A
      val ppName: String = name
      val ppLens: Lens[PParams[E], A] = lens
      val ppUpdate: Option[PParamUpdate[E, A]] = update
      val plutusData: ToPlutusData[A] = t
      val encCBOR: EncCBOR[A] = e
      val decCBOR: DecCBOR[A] = d

  /** A parameter that is a field of the record and that an update sets, as the reference describes
    * one (`ppTxFeePerByte`, `Shelley/PParams.hs:338-345`, cardano-ledger f649f975): the field's
    * lens at `Identity` under `ppLensHKD` and at `StrictMaybe` under `ppuLensHKD`
    * (`Core/PParams.hs:507-511`), the update's tag, and the update's era codec, which only
    * Dijkstra's Peras bootstrap round has (`ppuEraCodec`, cardano-ledger 9df9f98c
    * `Dijkstra/PParams.hs:1064-1081`).
    */
  private[era] inline def field[E <: EraTag, A: ToPlutusData: EncCBOR: DecCBOR](
      name: String,
      inline tag: Long,
      identity: Lens[PParamsHKD[Identity, E], A],
      strictMaybe: Lens[PParamsHKD[StrictMaybe, E], StrictMaybe[A]],
      eraCodec: Option[EraCodec[E, A]] = None
  ): PParam[E] =
    PParam[E, A](
      name,
      PParams.lensHKD[E].andThen(identity),
      Some(
        PParamUpdate[E, A](
          Unsigned.lit[W64](tag),
          PParamsUpdate.lensHKD[E].andThen(strictMaybe),
          eraCodec
        )
      )
    )

/** How an update sets a parameter (`data PParamUpdate era t`, `Core/PParams.hs:839-848`,
  * cardano-ledger 9df9f98c): the parameter's tag, its key in the update's CBOR and Plutus data; the
  * lens into the update; and the writer and reader of its value in an update where they are not the
  * value's own (`ppuEraCodec`). At f649f975 the update has no codec (`Core/PParams.hs:801-805`).
  */
final case class PParamUpdate[E <: EraTag, T](
    ppuTag: Unsigned[W64],
    ppuLens: Lens[PParamsUpdate[E], StrictMaybe[T]],
    ppuEraCodec: Option[EraCodec[E, T]]
)

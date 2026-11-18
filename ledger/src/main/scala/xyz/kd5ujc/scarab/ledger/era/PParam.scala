// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import monocle.Lens
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W64
import xyz.kd5ujc.scarab.ledger.state.EraTag

/** One protocol parameter of era `E` and what serialises it (`data PParam era`, cardano-ledger
  * f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:787-799`): its name, the
  * key of its JSON; its lens into the parameters; its update, if an update can set it; and how its
  * value is shown as Plutus data. The type of the value is the description's own, as the
  * reference's constructor hides it.
  *
  * The reference's constructor also packs the value's CBOR and JSON instances and, at f649f975, an
  * era's decoder (`ppEraDecoder`), which the Leios build's pin moves into the update
  * (`ppuEraCodec`, cardano-ledger 9df9f98c). Those are not here yet (#85).
  */
sealed trait PParam[E <: EraTag]:
  type T
  def ppName: String
  def ppLens: Lens[PParams[E], T]
  def ppUpdate: Option[PParamUpdate[E, T]]
  def plutusData: ToPlutusData[T]

object PParam:
  /** `PParam` (`Core/PParams.hs:787-799`, cardano-ledger f649f975). */
  def apply[E <: EraTag, A](
      name: String,
      lens: Lens[PParams[E], A],
      update: Option[PParamUpdate[E, A]]
  )(using t: ToPlutusData[A]): PParam[E] =
    new PParam[E]:
      type T = A
      val ppName: String = name
      val ppLens: Lens[PParams[E], A] = lens
      val ppUpdate: Option[PParamUpdate[E, A]] = update
      val plutusData: ToPlutusData[A] = t

  /** A parameter that is a field of the record and that an update sets, as the reference describes
    * one (`ppTxFeePerByte`, `Shelley/PParams.hs:338-345`, cardano-ledger f649f975): the field's
    * lens at `Identity` under `ppLensHKD` and at `StrictMaybe` under `ppuLensHKD`
    * (`Core/PParams.hs:507-511`), and the update's tag.
    */
  private[era] inline def field[E <: EraTag, A: ToPlutusData](
      name: String,
      inline tag: Long,
      identity: Lens[PParamsHKD[Identity, E], A],
      strictMaybe: Lens[PParamsHKD[StrictMaybe, E], StrictMaybe[A]]
  ): PParam[E] =
    PParam[E, A](
      name,
      PParams.lensHKD[E].andThen(identity),
      Some(
        PParamUpdate[E, A](Unsigned.lit[W64](tag), PParamsUpdate.lensHKD[E].andThen(strictMaybe))
      )
    )

/** How an update sets a parameter (`data PParamUpdate era t`, `Core/PParams.hs:801-805`,
  * cardano-ledger f649f975): the parameter's tag, its key in the update's CBOR and Plutus data, and
  * the lens into the update.
  */
final case class PParamUpdate[E <: EraTag, T](
    ppuTag: Unsigned[W64],
    ppuLens: Lens[PParamsUpdate[E], StrictMaybe[T]]
)

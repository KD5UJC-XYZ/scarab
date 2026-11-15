// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import monocle.Lens
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}

/** The protocol-parameter record of era `E` over `F` (`type PParamsHKD (f :: Type -> Type) era`,
  * cardano-ledger f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:376`): one
  * case per era that has its record.
  */
type PParamsHKD[F[_], E <: EraTag] = E match
  case Conway => conway.ConwayPParams[F]

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

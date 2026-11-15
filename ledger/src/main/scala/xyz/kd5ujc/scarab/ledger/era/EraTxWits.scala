// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.BootstrapWitness
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}

/** The witnesses every era's witness set has (`EraTxWits`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:503-527`): key witnesses, bootstrap
  * witnesses and scripts by hash. Its superclass `EraScript era` is a member ([[scriptsOf]]).
  */
trait EraTxWits[E <: EraTag]:
  /** The scripts' class (the superclass `EraScript era`). */
  def scriptsOf: EraScript[E]

  /** `addrTxWitsL`. */
  def addrTxWits(wits: TxWits[E]): SortedSet[WitVKey]

  /** `bootAddrTxWitsL`. */
  def bootAddrTxWits(wits: TxWits[E]): Vector[BootstrapWitness]

  /** `scriptTxWitsL`: by hash. */
  def scriptTxWits(wits: TxWits[E]): SortedMap[Bytes, Script[E]]

/** The datums and redeemers of a witness set, from Alonzo (`AlonzoEraTxWits`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:480-483`, cardano-ledger f649f975), whose
  * scripts have Plutus scripts (the superclass `AlonzoEraScript era`).
  */
trait AlonzoEraTxWits[E <: EraTag] extends EraTxWits[E]:
  override def scriptsOf: AlonzoEraScript[E]

  /** `datsTxWitsL`: the datums, with the bytes they arrived as. */
  def datsTxWits(wits: TxWits[E]): TxDats

  /** `rdmrsTxWitsL`: the redeemers by the era's purpose, with the bytes they arrived as. */
  def rdmrsTxWits(wits: TxWits[E]): Redeemers[PlutusPurpose[AsIx, E]]

/** The instances of each era, the one place that lists the eras having each witness class. */
object EraTxWits:
  given conway: AlonzoEraTxWits[Conway] =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxWits.instance

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.ledger.{Metadatum, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

/** The metadata every era's auxiliary data has (`EraTxAuxData`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:467-490`). Its validation takes the
  * protocol version and comes with the rules that run it.
  */
trait EraTxAuxData[E <: EraTag]:
  /** `metadataTxAuxDataL`: by label (a `Word64`). */
  def metadata(aux: TxAuxData[E]): SortedMap[Unsigned[Unsigned.Width.W64], Metadatum]

/** The native scripts of auxiliary data, from Allegra (`AllegraEraTxAuxData`,
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/TxAuxData.hs:94-95`, cardano-ledger f649f975).
  */
trait AllegraEraTxAuxData[E <: EraTag] extends EraTxAuxData[E]:
  /** `nativeScriptsTxAuxDataL`. */
  def nativeScripts(aux: TxAuxData[E]): Vector[NativeScript[E]]

/** The Plutus scripts of auxiliary data, from Alonzo (`AlonzoEraTxAuxData`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:121-122`, cardano-ledger f649f975).
  */
trait AlonzoEraTxAuxData[E <: EraTag] extends AllegraEraTxAuxData[E]:
  /** `plutusScriptsTxAuxDataL`: per language, the scripts' bytes (`Map Language (NonEmpty
    * PlutusBinary)`).
    */
  def plutusScripts(aux: TxAuxData[E]): SortedMap[PlutusLanguage, NonEmptyVector[Bytes]]

/** The instances of each era, the one place that lists the eras having each class. */
object EraTxAuxData:
  given conway: AlonzoEraTxAuxData[Conway] =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxAuxData.instance

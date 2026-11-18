// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.SortedMap
import cats.Id
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Metadatum, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}
import xyz.kd5ujc.scarab.plutus.{BackendFailure, PlutusLanguage, ScriptDecodeFailure, ScriptDecoder}

/** The metadata every era's auxiliary data has, and its validation (`EraTxAuxData`, cardano-ledger
  * f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:467-490`).
  */
trait EraTxAuxData[E <: EraTag]:
  /** `metadataTxAuxDataL`: by label (a `Word64`). */
  def metadata(aux: TxAuxData[E]): SortedMap[Unsigned[Unsigned.Width.W64], Metadatum]

  /** The bytes the auxiliary data arrived as, which its hash is of (the superclass `SafeToHash`,
    * through `HashAnnotated`).
    */
  def originalBytes(aux: TxAuxData[E]): Bytes

  /** `validateTxAuxData` (`Core.hs:489`, cardano-ledger f649f975): whether the auxiliary data is
    * well formed at the protocol version, its Plutus scripts read by `decoder`, a capability of the
    * `plutus` module, or the script it has no verdict for ([[UnavailableScript]]), where the
    * reference's decoding is pure.
    */
  def validateTxAuxData(
      decoder: ScriptDecoder[Id],
      stAnnTxCache: StAnnTxCache[E],
      pv: ProtVer,
      aux: TxAuxData[E]
  ): Either[UnavailableScript, Boolean]

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

  /** The scripts of [[plutusScripts]] with their languages in the order they arrived: the node's
    * own, which no rule's verdict reads, only the choice of the first script a decoder gives no
    * verdict for (the reference's decoding always gives one), as the flat checks choose it.
    */
  def plutusScriptsArrival(aux: TxAuxData[E]): Vector[(PlutusLanguage, Bytes)]

/** A script the `plutus` module's decoder supplies no verdict for
  * (`ScriptDecodeFailure.Unavailable`): a defect of this implementation, not a verdict on the
  * script, which the reference's pure decoder always gives.
  */
final case class UnavailableScript(scriptHash: Bytes, cause: BackendFailure)

/** Alonzo's validation of scripts and auxiliary data
  * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs`, cardano-ledger f649f975).
  */
object AlonzoTxAuxData:
  /** Whether `binary`, a script of `language` whose hash is `scriptHash`, decodes at the protocol
    * version: the verdict of `isValidPlutusScript` and `isValidPlutusRunnable`, which the decoder
    * gives here.
    */
  private def isValidPlutus(
      decoder: ScriptDecoder[Id],
      pv: ProtVer,
      scriptHash: Bytes,
      language: PlutusLanguage,
      binary: Bytes
  ): Either[UnavailableScript, Boolean] =
    decoder.validate(language, pv.pvMajor.value, binary) match
      case Right(())                              => Right(true)
      case Left(ScriptDecodeFailure.Malformed(_)) => Right(false)
      case Left(ScriptDecodeFailure.Unavailable(reason)) =>
        Left(UnavailableScript(scriptHash, reason))

  /** `isValidScript` (`Alonzo/TxAuxData.hs:494-515`, cardano-ledger f649f975): a Plutus script is
    * valid if it decodes at the protocol version, its cached runnable's binary if the cache has its
    * hash; a native script is, once decoded. The reference's third case, neither, is not a script
    * an era has.
    */
  def isValidScript[E <: EraTag](
      decoder: ScriptDecoder[Id],
      plutusScriptsCache: Map[Bytes, SupportedPlutusRunnable[E]],
      pv: ProtVer,
      scriptHash: Bytes,
      script: Script[E]
  )(using s: AlonzoEraScript[E]): Either[UnavailableScript, Boolean] =
    s.toPlutusScript(script) match
      case Some(plutusScript) =>
        plutusScriptsCache.get(scriptHash) match
          case Some(runnable) =>
            isValidPlutus(
              decoder,
              pv,
              scriptHash,
              runnable.language,
              runnable.plutusRunnable.plutusRunnableBinary.binary
            )
          case None =>
            s.withPlutusScript(plutusScript)(
              [L <: Language] =>
                (p: Plutus[L], l: xyz.kd5ujc.scarab.ledger.era.PlutusLanguage[L]) =>
                  isValidPlutus(decoder, pv, scriptHash, l.language, p.binary)
            )
      case None => Right(true)

  /** `validateAlonzoTxAuxData` (`Alonzo/TxAuxData.hs:363-373`, cardano-ledger f649f975): every
    * script of the auxiliary data, by its hash, is valid (`isValidScript`). The native scripts are,
    * once decoded; each Plutus script of a language the era has (`getAlonzoTxAuxDataScripts`,
    * `:192-207`) is checked with its hash, the reference's script of that language made from its
    * binary (`mkBinaryPlutusScript`) taken here as the binary and its language. The scripts are
    * visited in the order they arrived ([[AlonzoEraTxAuxData.plutusScriptsArrival]]), so the first
    * a decoder gives no verdict for is the one the flat check names.
    */
  def validateAlonzoTxAuxData[E <: EraTag](
      decoder: ScriptDecoder[Id],
      plutusScriptsCache: Map[Bytes, SupportedPlutusRunnable[E]],
      pv: ProtVer,
      auxData: TxAuxData[E]
  )(using a: AlonzoEraTxAuxData[E], s: AlonzoEraScript[E]): Either[UnavailableScript, Boolean] =
    val plutus = a
      .plutusScriptsArrival(auxData)
      .filter((language, _) => language.id <= s.eraMaxLanguage.id)
      .map((language, binary) =>
        Blake2b.hash224
          .hash(Bytes(Vector((language.id + 1).toByte)) ++ binary) -> (language, binary)
      )
      .distinctBy(_._1)
    plutus
      .traverse { case (scriptHash, (language, binary)) =>
        plutusScriptsCache.get(scriptHash) match
          case Some(runnable) =>
            isValidPlutus(
              decoder,
              pv,
              scriptHash,
              runnable.language,
              runnable.plutusRunnable.plutusRunnableBinary.binary
            )
          case None => isValidPlutus(decoder, pv, scriptHash, language, binary)
      }
      .map(_.forall(identity))

/** The instances of each era, the one place that lists the eras having each class. */
object EraTxAuxData:
  /** `hashTxAuxData` (`Core.hs:499-500`, cardano-ledger f649f975): blake2b-256 of the bytes the
    * auxiliary data arrived as.
    */
  def hashTxAuxData[E <: EraTag](aux: TxAuxData[E])(using a: EraTxAuxData[E]): Bytes =
    Blake2b.hash256.hash(a.originalBytes(aux))

  given conway: AlonzoEraTxAuxData[Conway] =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxAuxData.instance

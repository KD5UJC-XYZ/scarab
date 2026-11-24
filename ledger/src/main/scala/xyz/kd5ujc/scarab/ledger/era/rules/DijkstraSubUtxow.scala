// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad}
import cats.data.{Chain, NonEmptyList, NonEmptySet}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.ListMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TxIn, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Sts, Test}
import xyz.kd5ujc.scarab.ledger.state.{Credential, Dijkstra, EraTag, Guard, UTxOState}
import xyz.kd5ujc.scarab.plutus.ScriptDecoder

// SUBUTXOW of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/SubUtxow.hs`): its failures and events, and
// its transition over one sub-transaction, which runs the predicates of Shelley's, Alonzo's and
// Babbage's UTXOW (`Utxow.scala`) and Dijkstra's guards' datums ([[DijkstraUtxow]]) on the
// sub-transaction, then embeds SUBUTXO ([[DijkstraSubUtxo]]). Its injections, which the reference
// leaves partial (`dijkstraUtxowToDijkstraSubUtxowPredFailure`, `SubUtxow.hs:337-364`), are total on
// what it reaches ([[DijkstraSubUtxow.UtxowFailure]] and the others): the scripts missing and
// extraneous and the guards required are the top level's checks, and UTXO's failures reach SUBUTXOW
// only through SUBUTXO's embedding.

/** `data DijkstraSubUtxowPredFailure era` (`Dijkstra/Rules/SubUtxow.hs:73-117`, cardano-ledger
  * 9df9f98c), its constructors in the reference's order, which is their tag (`:291-308`).
  */
enum DijkstraSubUtxowPredFailure[E <: EraTag]:
  case SubUtxoFailure(failure: SubUtxoPredFailure[E])

  /** The keys of the key and bootstrap witnesses whose signature does not verify. */
  case SubInvalidWitnessesUTXOW(keys: NonEmptyList[Bytes])
  case SubMissingVKeyWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case SubScriptWitnessNotValidatingUTXOW(hashes: NonEmptySet[Bytes])
  case SubMissingTxBodyMetadataHash(hash: Bytes)
  case SubMissingTxMetadata(hash: Bytes)
  case SubConflictingMetadataHash(mismatch: Mismatch[RelEQ, Bytes])
  case SubInvalidMetadata()

  /** The purposes with no redeemer, as items, with the hash of the script each needs. */
  case SubMissingRedeemers(purposes: NonEmptyList[(PlutusPurpose[AsItem, E], Bytes)])

  /** The datums missing, and the witnesses' datums. */
  case SubMissingRequiredDatums(missing: NonEmptySet[Bytes], received: Set[Bytes])

  /** The supplemental datums not allowed, and those allowed. */
  case SubNotAllowedSupplementalDatums(unallowed: NonEmptySet[Bytes], acceptable: Set[Bytes])
  case SubPPViewHashesDontMatch(mismatch: Mismatch[RelEQ, Option[Bytes]])
  case SubUnspendableUTxONoDatumHash(inputs: NonEmptySet[TxIn])
  case SubExtraRedeemers(purposes: NonEmptyList[PlutusPurpose[AsIx, E]])
  case SubMalformedScriptWitnesses(hashes: NonEmptySet[Bytes])
  case SubMalformedReferenceScripts(hashes: NonEmptySet[Bytes])

  /** The supplied and computed hashes, and the bytes the computed one is over. */
  case SubScriptIntegrityHashMismatch(
      mismatch: Mismatch[RelEQ, Option[Bytes]],
      expected: Option[Bytes]
  )

  /** The required guards whose datum is present where it must not be, or absent where it must be.
    */
  case SubMalformedGuardDatums(credentials: NonEmptySet[Credential[Guard]])

object DijkstraSubUtxowPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubUtxowPredFailure[E], DijkstraSubUtxowPredFailure[E]] =
    CanEqual.derived

/** `newtype DijkstraSubUtxowEvent era` (`Dijkstra/Rules/SubUtxow.hs:179`, cardano-ledger 9df9f98c):
  * SUBUTXO's events.
  */
enum DijkstraSubUtxowEvent[E <: EraTag]:
  case SubUtxo(event: SubUtxoEvent[E])

object DijkstraSubUtxowEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubUtxowEvent[E], DijkstraSubUtxowEvent[E]] =
    CanEqual.derived

/** `EraRuleFailure "SUBUTXOW" era`: Dijkstra's (`Dijkstra/Rules/SubUtxow.hs:144`, cardano-ledger
  * 9df9f98c), the one era with the rule.
  */
type SubUtxowPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubUtxowPredFailure[Dijkstra]

/** `EraRuleEvent "SUBUTXOW" era`: Dijkstra's (`Dijkstra/Rules/SubUtxow.hs:146`, cardano-ledger
  * 9df9f98c).
  */
type SubUtxowEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubUtxowEvent[Dijkstra]

/** `InjectRuleFailure "SUBUTXOW" f era` (`Core/Era.hs:120-126`, cardano-ledger 9df9f98c): how era
  * `E` reports a failure `F` of a predicate SUBUTXOW runs in its own. Contravariant in `F`, as
  * [[InjectUtxowFailure]] is; where the reference's injection is partial, the instance is over the
  * constructors the rule reaches, so every instance is total.
  */
trait InjectSubUtxowFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): SubUtxowPredFailure[E]

object InjectSubUtxowFailure:
  /** `Dijkstra/Rules/SubUtxow.hs:148`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubUtxowFailure[DijkstraSubUtxowPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubUtxowPredFailure[Dijkstra]
    ): SubUtxowPredFailure[Dijkstra] = failure

  /** `Dijkstra/Rules/SubUtxow.hs:150-151`, cardano-ledger 9df9f98c: `SubUtxoFailure`. */
  given dijkstraSubUtxo: InjectSubUtxowFailure[DijkstraSubUtxoPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubUtxoPredFailure[Dijkstra]
    ): SubUtxowPredFailure[Dijkstra] = DijkstraSubUtxowPredFailure.SubUtxoFailure(failure)

  /** `Dijkstra/Rules/SubUtxow.hs:153-154`, cardano-ledger 9df9f98c, on the constructors the
    * reference maps that SUBUTXOW reaches ([[DijkstraSubUtxow.UtxowFailure]]).
    */
  given dijkstraUtxow: InjectSubUtxowFailure[DijkstraSubUtxow.UtxowFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubUtxow.UtxowFailure[Dijkstra]
    ): SubUtxowPredFailure[Dijkstra] =
      DijkstraSubUtxow.dijkstraUtxowToDijkstraSubUtxowPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxow.hs:156-157`, cardano-ledger 9df9f98c, on the constructors the
    * reference maps that SUBUTXOW reaches ([[DijkstraSubUtxow.ConwayFailure]]).
    */
  given dijkstraConway: InjectSubUtxowFailure[DijkstraSubUtxow.ConwayFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxow.ConwayFailure[Dijkstra]
    ): SubUtxowPredFailure[Dijkstra] =
      DijkstraSubUtxow.conwayToDijkstraSubUtxowPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxow.hs:159-163`, cardano-ledger 9df9f98c, on the constructors SUBUTXOW
    * reports.
    */
  given dijkstraBabbage: InjectSubUtxowFailure[DijkstraSubUtxow.BabbageFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxow.BabbageFailure[Dijkstra]
    ): SubUtxowPredFailure[Dijkstra] =
      DijkstraSubUtxow.babbageToDijkstraSubUtxowPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxow.hs:165-169`, cardano-ledger 9df9f98c, on the constructors SUBUTXOW
    * reports.
    */
  given dijkstraAlonzo: InjectSubUtxowFailure[DijkstraSubUtxow.AlonzoFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxow.AlonzoFailure[Dijkstra]
    ): SubUtxowPredFailure[Dijkstra] =
      DijkstraSubUtxow.alonzoToDijkstraSubUtxowPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxow.hs:171-175`, cardano-ledger 9df9f98c, on the constructors SUBUTXOW
    * reports.
    */
  given dijkstraShelley: InjectSubUtxowFailure[DijkstraSubUtxow.ShelleyFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxow.ShelleyFailure[Dijkstra]
    ): SubUtxowPredFailure[Dijkstra] =
      DijkstraSubUtxow.shelleyToDijkstraSubUtxowPredFailure(failure)

/** `Embed (SUBUTXO era) (SUBUTXOW era)` (`Dijkstra/Rules/SubUtxow.hs:273-281`, cardano-ledger
  * 9df9f98c): how SUBUTXO's failures and events sit in SUBUTXOW's (`wrapFailed`, `wrapEvent`).
  */
trait EmbedSubUtxoInSubUtxow[E <: EraTag]:
  def wrapFailed(failure: SubUtxoPredFailure[E]): SubUtxowPredFailure[E]
  def wrapEvent(event: SubUtxoEvent[E]): SubUtxowEvent[E]

object EmbedSubUtxoInSubUtxow:
  /** Dijkstra's: `SubUtxoFailure` and `SubUtxo`. */
  given dijkstra: EmbedSubUtxoInSubUtxow[Dijkstra] with
    def wrapFailed(failure: SubUtxoPredFailure[Dijkstra]): SubUtxowPredFailure[Dijkstra] =
      DijkstraSubUtxowPredFailure.SubUtxoFailure(failure)
    def wrapEvent(event: SubUtxoEvent[Dijkstra]): SubUtxowEvent[Dijkstra] =
      DijkstraSubUtxowEvent.SubUtxo(event)

/** Dijkstra's functions of SUBUTXOW
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/SubUtxow.hs`, cardano-ledger 9df9f98c).
  */
object DijkstraSubUtxow:
  /** The constructors of Dijkstra's UTXOW failure that `dijkstraUtxowToDijkstraSubUtxowPredFailure`
    * maps and SUBUTXOW reaches (`Dijkstra/Rules/SubUtxow.hs:337-364`, cardano-ledger 9df9f98c): the
    * three it calls impossible (`MissingScriptWitnessesUTXOW`, `ExtraneousScriptWitnessesUTXOW`,
    * `MissingRequiredGuards`) left out, and `UtxoFailure` too, whose payload the reference injects
    * into SUBUTXO's partially and which no predicate SUBUTXOW runs reports: SUBUTXO's failures
    * reach SUBUTXOW through its embedding ([[EmbedSubUtxoInSubUtxow]]).
    */
  type UtxowFailure[E <: EraTag] = DijkstraUtxowPredFailure.InvalidWitnessesUTXOW[E] |
    DijkstraUtxowPredFailure.MissingVKeyWitnessesUTXOW[E] |
    DijkstraUtxowPredFailure.ScriptWitnessNotValidatingUTXOW[E] |
    DijkstraUtxowPredFailure.MissingTxBodyMetadataHash[E] |
    DijkstraUtxowPredFailure.MissingTxMetadata[E] |
    DijkstraUtxowPredFailure.ConflictingMetadataHash[E] |
    DijkstraUtxowPredFailure.InvalidMetadata[E] | DijkstraUtxowPredFailure.MissingRedeemers[E] |
    DijkstraUtxowPredFailure.MissingRequiredDatums[E] |
    DijkstraUtxowPredFailure.NotAllowedSupplementalDatums[E] |
    DijkstraUtxowPredFailure.PPViewHashesDontMatch[E] |
    DijkstraUtxowPredFailure.UnspendableUTxONoDatumHash[E] |
    DijkstraUtxowPredFailure.ExtraRedeemers[E] |
    DijkstraUtxowPredFailure.MalformedScriptWitnesses[E] |
    DijkstraUtxowPredFailure.MalformedReferenceScripts[E] |
    DijkstraUtxowPredFailure.ScriptIntegrityHashMismatch[E] |
    DijkstraUtxowPredFailure.MalformedGuardDatums[E]

  /** The constructors of Conway's UTXOW failure that `conwayToDijkstraUtxowPredFailure` carries
    * into [[UtxowFailure]] (`Dijkstra/Rules/SubUtxow.hs:156-157`, cardano-ledger 9df9f98c).
    */
  type ConwayFailure[E <: EraTag] = ConwayUtxowPredFailure.InvalidWitnessesUTXOW[E] |
    ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW[E] |
    ConwayUtxowPredFailure.ScriptWitnessNotValidatingUTXOW[E] |
    ConwayUtxowPredFailure.MissingTxBodyMetadataHash[E] |
    ConwayUtxowPredFailure.MissingTxMetadata[E] |
    ConwayUtxowPredFailure.ConflictingMetadataHash[E] | ConwayUtxowPredFailure.InvalidMetadata[E] |
    ConwayUtxowPredFailure.MissingRedeemers[E] | ConwayUtxowPredFailure.MissingRequiredDatums[E] |
    ConwayUtxowPredFailure.NotAllowedSupplementalDatums[E] |
    ConwayUtxowPredFailure.PPViewHashesDontMatch[E] |
    ConwayUtxowPredFailure.UnspendableUTxONoDatumHash[E] |
    ConwayUtxowPredFailure.ExtraRedeemers[E] | ConwayUtxowPredFailure.MalformedScriptWitnesses[E] |
    ConwayUtxowPredFailure.MalformedReferenceScripts[E] |
    ConwayUtxowPredFailure.ScriptIntegrityHashMismatch[E]

  /** The failures of Shelley's type that SUBUTXOW reports, each from the predicate that declares
    * it.
    */
  type ShelleyFailure[E <: EraTag] = ShelleyUtxowPredFailure.InvalidWitnessesUTXOW[E] |
    ShelleyUtxowPredFailure.MissingVKeyWitnessesUTXOW[E] |
    ShelleyUtxowPredFailure.ScriptWitnessNotValidatingUTXOW[E] | ShelleyUtxow.MetadataFailure[E]

  /** The failures of Alonzo's type that SUBUTXOW reports. */
  type AlonzoFailure[E <: EraTag] = AlonzoUtxow.DatumsFailure[E] | AlonzoUtxow.RedeemersFailure[E] |
    AlonzoUtxow.IntegrityFailure[E]

  /** The failures of Babbage's type that SUBUTXOW reports. */
  type BabbageFailure[E <: EraTag] = BabbageUtxow.WellFormedFailure[E]

  /** `dijkstraUtxowToDijkstraSubUtxowPredFailure` (`Dijkstra/Rules/SubUtxow.hs:337-364`,
    * cardano-ledger 9df9f98c), on [[UtxowFailure]].
    */
  def dijkstraUtxowToDijkstraSubUtxowPredFailure[E <: EraTag](
      failure: UtxowFailure[E]
  ): DijkstraSubUtxowPredFailure[E] = failure match
    case DijkstraUtxowPredFailure.InvalidWitnessesUTXOW(ks) =>
      DijkstraSubUtxowPredFailure.SubInvalidWitnessesUTXOW(ks)
    case DijkstraUtxowPredFailure.MissingVKeyWitnessesUTXOW(ks) =>
      DijkstraSubUtxowPredFailure.SubMissingVKeyWitnessesUTXOW(ks)
    case DijkstraUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(hs) =>
      DijkstraSubUtxowPredFailure.SubScriptWitnessNotValidatingUTXOW(hs)
    case DijkstraUtxowPredFailure.MissingTxBodyMetadataHash(dh) =>
      DijkstraSubUtxowPredFailure.SubMissingTxBodyMetadataHash(dh)
    case DijkstraUtxowPredFailure.MissingTxMetadata(dh) =>
      DijkstraSubUtxowPredFailure.SubMissingTxMetadata(dh)
    case DijkstraUtxowPredFailure.ConflictingMetadataHash(mm) =>
      DijkstraSubUtxowPredFailure.SubConflictingMetadataHash(mm)
    case DijkstraUtxowPredFailure.InvalidMetadata() =>
      DijkstraSubUtxowPredFailure.SubInvalidMetadata()
    case DijkstraUtxowPredFailure.MissingRedeemers(pps) =>
      DijkstraSubUtxowPredFailure.SubMissingRedeemers(pps)
    case DijkstraUtxowPredFailure.MissingRequiredDatums(hs1, hs2) =>
      DijkstraSubUtxowPredFailure.SubMissingRequiredDatums(hs1, hs2)
    case DijkstraUtxowPredFailure.NotAllowedSupplementalDatums(hs1, hs2) =>
      DijkstraSubUtxowPredFailure.SubNotAllowedSupplementalDatums(hs1, hs2)
    case DijkstraUtxowPredFailure.PPViewHashesDontMatch(mm) =>
      DijkstraSubUtxowPredFailure.SubPPViewHashesDontMatch(mm)
    case DijkstraUtxowPredFailure.UnspendableUTxONoDatumHash(txs) =>
      DijkstraSubUtxowPredFailure.SubUnspendableUTxONoDatumHash(txs)
    case DijkstraUtxowPredFailure.ExtraRedeemers(pps) =>
      DijkstraSubUtxowPredFailure.SubExtraRedeemers(pps)
    case DijkstraUtxowPredFailure.MalformedScriptWitnesses(hs) =>
      DijkstraSubUtxowPredFailure.SubMalformedScriptWitnesses(hs)
    case DijkstraUtxowPredFailure.MalformedReferenceScripts(hs) =>
      DijkstraSubUtxowPredFailure.SubMalformedReferenceScripts(hs)
    case DijkstraUtxowPredFailure.ScriptIntegrityHashMismatch(mm, f) =>
      DijkstraSubUtxowPredFailure.SubScriptIntegrityHashMismatch(mm, f)
    case DijkstraUtxowPredFailure.MalformedGuardDatums(x) =>
      DijkstraSubUtxowPredFailure.SubMalformedGuardDatums(x)

  /** `dijkstraUtxowToDijkstraSubUtxowPredFailure . conwayToDijkstraUtxowPredFailure`
    * (`Dijkstra/Rules/SubUtxow.hs:156-157`, cardano-ledger 9df9f98c), on [[ConwayFailure]]: each
    * passes both unchanged.
    */
  def conwayToDijkstraSubUtxowPredFailure[E <: EraTag](
      failure: ConwayFailure[E]
  ): DijkstraSubUtxowPredFailure[E] = failure match
    case ConwayUtxowPredFailure.InvalidWitnessesUTXOW(ks) =>
      DijkstraSubUtxowPredFailure.SubInvalidWitnessesUTXOW(ks)
    case ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(ks) =>
      DijkstraSubUtxowPredFailure.SubMissingVKeyWitnessesUTXOW(ks)
    case ConwayUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(hs) =>
      DijkstraSubUtxowPredFailure.SubScriptWitnessNotValidatingUTXOW(hs)
    case ConwayUtxowPredFailure.MissingTxBodyMetadataHash(dh) =>
      DijkstraSubUtxowPredFailure.SubMissingTxBodyMetadataHash(dh)
    case ConwayUtxowPredFailure.MissingTxMetadata(dh) =>
      DijkstraSubUtxowPredFailure.SubMissingTxMetadata(dh)
    case ConwayUtxowPredFailure.ConflictingMetadataHash(mm) =>
      DijkstraSubUtxowPredFailure.SubConflictingMetadataHash(mm)
    case ConwayUtxowPredFailure.InvalidMetadata() =>
      DijkstraSubUtxowPredFailure.SubInvalidMetadata()
    case ConwayUtxowPredFailure.MissingRedeemers(pps) =>
      DijkstraSubUtxowPredFailure.SubMissingRedeemers(pps)
    case ConwayUtxowPredFailure.MissingRequiredDatums(hs1, hs2) =>
      DijkstraSubUtxowPredFailure.SubMissingRequiredDatums(hs1, hs2)
    case ConwayUtxowPredFailure.NotAllowedSupplementalDatums(hs1, hs2) =>
      DijkstraSubUtxowPredFailure.SubNotAllowedSupplementalDatums(hs1, hs2)
    case ConwayUtxowPredFailure.PPViewHashesDontMatch(mm) =>
      DijkstraSubUtxowPredFailure.SubPPViewHashesDontMatch(mm)
    case ConwayUtxowPredFailure.UnspendableUTxONoDatumHash(txs) =>
      DijkstraSubUtxowPredFailure.SubUnspendableUTxONoDatumHash(txs)
    case ConwayUtxowPredFailure.ExtraRedeemers(pps) =>
      DijkstraSubUtxowPredFailure.SubExtraRedeemers(pps)
    case ConwayUtxowPredFailure.MalformedScriptWitnesses(hs) =>
      DijkstraSubUtxowPredFailure.SubMalformedScriptWitnesses(hs)
    case ConwayUtxowPredFailure.MalformedReferenceScripts(hs) =>
      DijkstraSubUtxowPredFailure.SubMalformedReferenceScripts(hs)
    case ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(mm, f) =>
      DijkstraSubUtxowPredFailure.SubScriptIntegrityHashMismatch(mm, f)

  /** `injectFailure` of `InjectRuleFailure "SUBUTXOW" ShelleyUtxowPredFailure DijkstraEra`
    * (`Dijkstra/Rules/SubUtxow.hs:171-175`, cardano-ledger 9df9f98c), on the constructors SUBUTXOW
    * reports: each passes the three injections unchanged.
    */
  def shelleyToDijkstraSubUtxowPredFailure[E <: EraTag](
      failure: ShelleyFailure[E]
  ): DijkstraSubUtxowPredFailure[E] = failure match
    case ShelleyUtxowPredFailure.InvalidWitnessesUTXOW(ks) =>
      DijkstraSubUtxowPredFailure.SubInvalidWitnessesUTXOW(ks)
    case ShelleyUtxowPredFailure.MissingVKeyWitnessesUTXOW(ks) =>
      DijkstraSubUtxowPredFailure.SubMissingVKeyWitnessesUTXOW(ks)
    case ShelleyUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(hs) =>
      DijkstraSubUtxowPredFailure.SubScriptWitnessNotValidatingUTXOW(hs)
    case ShelleyUtxowPredFailure.MissingTxBodyMetadataHash(dh) =>
      DijkstraSubUtxowPredFailure.SubMissingTxBodyMetadataHash(dh)
    case ShelleyUtxowPredFailure.MissingTxMetadata(dh) =>
      DijkstraSubUtxowPredFailure.SubMissingTxMetadata(dh)
    case ShelleyUtxowPredFailure.ConflictingMetadataHash(mm) =>
      DijkstraSubUtxowPredFailure.SubConflictingMetadataHash(mm)
    case ShelleyUtxowPredFailure.InvalidMetadata() =>
      DijkstraSubUtxowPredFailure.SubInvalidMetadata()

  /** `injectFailure` of `InjectRuleFailure "SUBUTXOW" AlonzoUtxowPredFailure DijkstraEra`
    * (`Dijkstra/Rules/SubUtxow.hs:165-169`, cardano-ledger 9df9f98c), on the constructors SUBUTXOW
    * reports.
    */
  def alonzoToDijkstraSubUtxowPredFailure[E <: EraTag](
      failure: AlonzoFailure[E]
  ): DijkstraSubUtxowPredFailure[E] = failure match
    case AlonzoUtxowPredFailure.UnspendableUTxONoDatumHash(txs) =>
      DijkstraSubUtxowPredFailure.SubUnspendableUTxONoDatumHash(txs)
    case AlonzoUtxowPredFailure.MissingRequiredDatums(hs1, hs2) =>
      DijkstraSubUtxowPredFailure.SubMissingRequiredDatums(hs1, hs2)
    case AlonzoUtxowPredFailure.NotAllowedSupplementalDatums(hs1, hs2) =>
      DijkstraSubUtxowPredFailure.SubNotAllowedSupplementalDatums(hs1, hs2)
    case AlonzoUtxowPredFailure.ExtraRedeemers(pps) =>
      DijkstraSubUtxowPredFailure.SubExtraRedeemers(pps)
    case AlonzoUtxowPredFailure.MissingRedeemers(pps) =>
      DijkstraSubUtxowPredFailure.SubMissingRedeemers(pps)
    case AlonzoUtxowPredFailure.PPViewHashesDontMatch(mm) =>
      DijkstraSubUtxowPredFailure.SubPPViewHashesDontMatch(mm)
    case AlonzoUtxowPredFailure.ScriptIntegrityHashMismatch(mm, f) =>
      DijkstraSubUtxowPredFailure.SubScriptIntegrityHashMismatch(mm, f)

  /** `injectFailure` of `InjectRuleFailure "SUBUTXOW" BabbageUtxowPredFailure DijkstraEra`
    * (`Dijkstra/Rules/SubUtxow.hs:159-163`, cardano-ledger 9df9f98c), on the constructors SUBUTXOW
    * reports.
    */
  def babbageToDijkstraSubUtxowPredFailure[E <: EraTag](
      failure: BabbageFailure[E]
  ): DijkstraSubUtxowPredFailure[E] = failure match
    case BabbageUtxowPredFailure.MalformedScriptWitnesses(hs) =>
      DijkstraSubUtxowPredFailure.SubMalformedScriptWitnesses(hs)
    case BabbageUtxowPredFailure.MalformedReferenceScripts(hs) =>
      DijkstraSubUtxowPredFailure.SubMalformedReferenceScripts(hs)

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private val u = EraUTxO.dijkstra
  private given BothLevels[TxLevel.Sub] = BothLevels.Sub

  /** `dijkstraSubUtxowTransition` (`Dijkstra/Rules/SubUtxow.hs:215-271`, cardano-ledger 9df9f98c),
    * every lookup against the UTxO set before the whole transaction (`sueOriginalUtxo`), every
    * failure recorded in the reference's order and injected into SUBUTXOW's, of the sub-transaction
    * alone:
    *   - the signatures (on the signal);
    *   - the failing needed native scripts, among the scripts the sub-transaction provides;
    *   - the keys needed, against the environment's certificate state;
    *   - the datums of its inputs;
    *   - the auxiliary data (on the signal);
    *   - the script integrity hash and the redeemers;
    *   - the witnesses' scripts and the outputs' reference scripts well formed (on the signal);
    *   - the required guards' datums;
    * then SUBUTXO as a sub-rule, its failures and events embedded in SUBUTXOW's
    * ([[EmbedSubUtxoInSubUtxow]]). The scripts missing and extraneous and the guards required are
    * the top level's (UTXOW's, over every level), and no script is evaluated here: UTXOS evaluates
    * the batch's at the top level. A signature check or a script decoding with no verdict is raised
    * as the node's `LedgerDefect`, as in Dijkstra's UTXOW; SUBUTXO raises its own.
    */
  def dijkstraSubUtxowTransition[F[_]: Monad](
      env: SubUtxoEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Sub, Dijkstra],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      state: Stateful[F, UTxOState[Dijkstra]],
      judge: Judge[F, SubUtxowPredFailure[Dijkstra]],
      events: Tell[F, Chain[DijkstraSubUtxowEvent[Dijkstra]]],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect]
  ): F[Unit] =
    val embed = EmbedSubUtxoInSubUtxow.dijkstra
    val pp = env.pp
    val tx = t.txStAnnTx[TxLevel.Sub](stAnnTx)
    val txBody = t.body[TxLevel.Sub](tx)
    val wits = t.wits[TxLevel.Sub](tx)
    lazy val witsKeyHashes = EraTxWits.keyHashWitnessesTxWits[Dijkstra](wits)(using t.witsOf)
    lazy val scriptsProvided = u.scriptsProvidedStAnnTx[TxLevel.Sub](stAnnTx)
    lazy val scriptsNeeded = u.scriptsNeededStAnnTx[TxLevel.Sub](stAnnTx)
    lazy val scriptHashesNeeded =
      u.scriptsHashesNeededStAnnTx[TxLevel.Sub](stAnnTx)(using SubOnly.Sub)
    lazy val witScripts = t.witsOf.scriptTxWits(wits)
    // the witnesses' scripts in the order they arrived, which only the choice of the first script
    // the decoder gives no verdict for reads, as in `BabbageUtxow.validateScriptsWellFormed`
    lazy val inArrival = ListMap.from(
      t.witsOf.scriptTxWitsArrival(wits).flatMap(h => witScripts.get(h).map(h -> _))
    )
    // every predicate's condition is evaluated only when the judgment checks predicates
    // (`ValidateNone`, [[Sts.validating]]), so the values above are lazy
    def test[A](t: => Test[A])(using inject: InjectSubUtxowFailure[A, Dijkstra]): F[Unit] =
      Sts.runTest[F, SubUtxowPredFailure[Dijkstra]](t.leftMap(_.map(inject.injectFailure)))
    def checked[A](t: => Either[LedgerDefect, Test[A]])(using
        InjectSubUtxowFailure[A, Dijkstra]
    ): F[Unit] =
      Sts.validating[F, SubUtxowPredFailure[Dijkstra]](
        Sts.lift[F, LedgerDefect, Test[A]](t).flatMap(test(_))
      )
    def onSignal(fa: => F[Unit]): F[Unit] = context.validation match
      case xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All       => fa
      case xyz.kd5ujc.scarab.ledger.TxEnv.Validation.NonStatic => Monad[F].unit
    given Judge[F, SubUtxoPredFailure[Dijkstra]] = judge.contramap(embed.wrapFailed)
    given Tell[F, Chain[DijkstraSubUtxoEvent[Dijkstra]]] =
      new Tell[F, Chain[DijkstraSubUtxoEvent[Dijkstra]]]:
        val functor: cats.Functor[F] = events.functor
        def tell(l: Chain[DijkstraSubUtxoEvent[Dijkstra]]): F[Unit] =
          events.tell(l.map(embed.wrapEvent))
    for
      _ <- onSignal(
        checked(ShelleyUtxow.validateVerifiedWits[Dijkstra, TxLevel.Sub](tx, verdicts))
      )
      _ <- test(
        BabbageUtxow.validateFailedBabbageScripts[Dijkstra, TxLevel.Sub](
          tx,
          scriptsProvided,
          scriptHashesNeeded
        )
      )
      _ <- test(
        ShelleyUtxow.validateNeededWitnesses[Dijkstra, TxLevel.Sub](
          witsKeyHashes,
          env.certState,
          env.originalUtxo,
          txBody
        )
      )
      _ <- test(
        AlonzoUtxow.missingRequiredDatums[Dijkstra, TxLevel.Sub](
          scriptsProvided,
          env.originalUtxo,
          tx
        )
      )
      _ <- onSignal(
        checked(ShelleyUtxow.validateMetadata[Dijkstra, TxLevel.Sub](decoder, pp, stAnnTx))
      )
      _ <- test(
        AlonzoUtxow.checkScriptIntegrityHash[Dijkstra, TxLevel.Sub](
          tx,
          pp,
          ScriptIntegrity.mkScriptIntegrity[Dijkstra, TxLevel.Sub](
            pp,
            tx,
            u.plutusLanguagesUsedStAnnTx[TxLevel.Sub](stAnnTx)
          )
        )
      )
      _ <- test(
        AlonzoUtxow
          .hasExactSetOfRedeemers[Dijkstra, TxLevel.Sub](tx, scriptsProvided, scriptsNeeded)
      )
      _ <- onSignal(
        checked(
          BabbageUtxow
            .validateScriptsWellFormedTxOuts[Dijkstra](
              decoder,
              t.cacheStAnnTx[TxLevel.Sub](stAnnTx),
              pp,
              inArrival,
              b.outputs[TxLevel.Sub](txBody)
            )(using b.outputsOf)
            .leftMap(unavailable(b.id[TxLevel.Sub](txBody)))
        )
      )
      _ <- test(DijkstraUtxow.validateGuardDatums[Dijkstra, TxLevel.Sub](scriptsProvided, txBody))
      _ <- judge.nest(DijkstraSubUtxo.dijkstraSubUtxoTransition[F](env, stAnnTx, context))
    yield ()

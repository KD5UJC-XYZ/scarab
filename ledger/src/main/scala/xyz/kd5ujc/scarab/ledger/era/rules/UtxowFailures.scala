// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Order
import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  CollectError as FlatCollectError,
  Mismatch,
  PredicateFailure,
  Purpose,
  TxIn,
  UtxoFailure as FlatUtxoFailure,
  UtxosFailure as FlatUtxosFailure,
  UtxowFailure as FlatUtxowFailure
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}

// The failures and events of UTXOW as the reference declares them (cardano-ledger f649f975, the
// `Rules/Utxow.hs` of Shelley, Alonzo, Babbage and Conway), each era's own type with the injections
// that carry one era's failures into a later era's (`InjectRuleFailure "UTXOW"`), as
// `UtxoFailures.scala` declares UTXO's. At cardano-ledger 29a7d656 and 9df9f98c the types are the
// same but for their derived `Ord` instances.

/** The order of the hash sets the failures hold: the bytes', as the reference's derived `Ord` of a
  * hash is.
  */
private[rules] given hashOrder: Order[Bytes] = Order.fromOrdering

/** The signers of `MIRInsufficientGenesisSigsUTXOW`, of the eras that have MIR certificates. Conway
  * has none, its case no value: the reference's injection into Conway calls it impossible
  * (`shelleyToConwayUtxowPredFailure`, `Conway/Rules/Utxow.hs:303-316`, cardano-ledger f649f975),
  * which this type makes so. The eras before Conway add their case, `Set (KeyHash Witness)`.
  */
type MirSigners[E <: EraTag] = E match
  case Conway => Nothing

/** `data ShelleyUtxowPredFailure era` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/
  * Utxow.hs:111-132`, cardano-ledger f649f975).
  */
enum ShelleyUtxowPredFailure[E <: EraTag]:
  /** The keys of the key and bootstrap witnesses whose signature does not verify. */
  case InvalidWitnessesUTXOW(keys: NonEmptyList[Bytes])
  case MissingVKeyWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case MissingScriptWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case ScriptWitnessNotValidatingUTXOW(hashes: NonEmptySet[Bytes])
  case UtxoFailure(failure: UtxoPredFailure[E])
  case MIRInsufficientGenesisSigsUTXOW(signers: MirSigners[E])
  case MissingTxBodyMetadataHash(hash: Bytes)
  case MissingTxMetadata(hash: Bytes)
  case ConflictingMetadataHash(mismatch: Mismatch[Bytes])
  case InvalidMetadata()
  case ExtraneousScriptWitnessesUTXOW(hashes: NonEmptySet[Bytes])

/** `data AlonzoUtxowPredFailure era` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxow.hs:
  * 89-116`, cardano-ledger f649f975).
  */
enum AlonzoUtxowPredFailure[E <: EraTag]:
  case ShelleyInAlonzoUtxowPredFailure(failure: ShelleyUtxowPredFailure[E])

  /** The purposes with no redeemer, as items, with the hash of the script each needs. */
  case MissingRedeemers(purposes: NonEmptyList[(PlutusPurpose[AsItem, E], Bytes)])

  /** The datums missing, and the witnesses' datums. */
  case MissingRequiredDatums(missing: NonEmptySet[Bytes], received: Set[Bytes])

  /** The supplemental datums not allowed, and those allowed. */
  case NotAllowedSupplementalDatums(unallowed: NonEmptySet[Bytes], acceptable: Set[Bytes])
  case PPViewHashesDontMatch(mismatch: Mismatch[Option[Bytes]])
  case UnspendableUTxONoDatumHash(inputs: NonEmptySet[TxIn])
  case ExtraRedeemers(purposes: NonEmptyList[PlutusPurpose[AsIx, E]])

  /** The supplied and computed hashes, and the bytes the computed one is over. */
  case ScriptIntegrityHashMismatch(mismatch: Mismatch[Option[Bytes]], expected: Option[Bytes])

/** `data BabbageUtxowPredFailure era` (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/
  * Utxow.hs:80-94`, cardano-ledger f649f975).
  */
enum BabbageUtxowPredFailure[E <: EraTag]:
  case AlonzoInBabbageUtxowPredFailure(failure: AlonzoUtxowPredFailure[E])
  case UtxoFailure(failure: UtxoPredFailure[E])
  case MalformedScriptWitnesses(hashes: NonEmptySet[Bytes])
  case MalformedReferenceScripts(hashes: NonEmptySet[Bytes])
  case ScriptIntegrityHashMismatch(mismatch: Mismatch[Option[Bytes]], expected: Option[Bytes])

/** `data ConwayUtxowPredFailure era` (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxow.hs:
  * 58-112`, cardano-ledger f649f975), its constructors in the reference's order, which is their tag
  * (`:227-245`).
  */
enum ConwayUtxowPredFailure[E <: EraTag]:
  case UtxoFailure(failure: UtxoPredFailure[E])
  case InvalidWitnessesUTXOW(keys: NonEmptyList[Bytes])
  case MissingVKeyWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case MissingScriptWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case ScriptWitnessNotValidatingUTXOW(hashes: NonEmptySet[Bytes])
  case MissingTxBodyMetadataHash(hash: Bytes)
  case MissingTxMetadata(hash: Bytes)
  case ConflictingMetadataHash(mismatch: Mismatch[Bytes])
  case InvalidMetadata()
  case ExtraneousScriptWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case MissingRedeemers(purposes: NonEmptyList[(PlutusPurpose[AsItem, E], Bytes)])
  case MissingRequiredDatums(missing: NonEmptySet[Bytes], received: Set[Bytes])
  case NotAllowedSupplementalDatums(unallowed: NonEmptySet[Bytes], acceptable: Set[Bytes])
  case PPViewHashesDontMatch(mismatch: Mismatch[Option[Bytes]])
  case UnspendableUTxONoDatumHash(inputs: NonEmptySet[TxIn])
  case ExtraRedeemers(purposes: NonEmptyList[PlutusPurpose[AsIx, E]])
  case MalformedScriptWitnesses(hashes: NonEmptySet[Bytes])
  case MalformedReferenceScripts(hashes: NonEmptySet[Bytes])
  case ScriptIntegrityHashMismatch(mismatch: Mismatch[Option[Bytes]], expected: Option[Bytes])

/** What a failure's flat form cannot hold in the node's vocabulary without what the transaction
  * gives: UTXOS's collection errors, and a missing redeemer's purpose, which the flat failure names
  * with its index and the output it spends where the reference keeps the item alone.
  */
enum UtxowUnprojected:
  case CollectErrors(errors: NonEmptyList[CollectError[Conway]])
  case MissingRedeemers(purposes: NonEmptyList[(ConwayPlutusPurpose[AsItem], Bytes)])

object ConwayUtxowPredFailure:
  given [E <: EraTag]: CanEqual[ConwayUtxowPredFailure[E], ConwayUtxowPredFailure[E]] =
    CanEqual.derived

  /** The failure in the node's flat form (`PredicateFailure`), as
    * [[ConwayUtxoPredFailure.toStored]] gives UTXO's: glue until the failures of the era types have
    * their own encoders (#85). A missing redeemer's purpose, of UTXOW's `MissingRedeemers` or of
    * UTXOS's `NoRedeemer`, is given its flat form by `purpose`, which the transaction's scripts
    * needed and UTxO set make (the flat form names the purpose's index and the output it spends). A
    * purpose it does not give, and a context's failure to translate (`BadTranslation`, which the
    * flat failure holds in its own vocabulary), leave the failure as it is.
    */
  def toStored(
      failure: ConwayUtxowPredFailure[Conway],
      purpose: ConwayPlutusPurpose[AsItem] => Option[Purpose]
  ): Either[UtxowUnprojected, PredicateFailure] =
    def utxow(f: FlatUtxowFailure): Either[UtxowUnprojected, PredicateFailure] =
      Right(PredicateFailure.utxow(f))
    def set[A](as: NonEmptySet[A]): Set[A] = as.toSortedSet
    failure match
      case UtxoFailure(f) =>
        ConwayUtxoPredFailure
          .toStored(f)
          .left
          .flatMap(errors =>
            errors
              .traverse {
                case CollectError.NoRedeemer(p)  => purpose(p).map(FlatCollectError.NoRedeemer(_))
                case CollectError.NoWitness(h)   => Some(FlatCollectError.NoWitness(h))
                case CollectError.NoCostModel(l) => Some(FlatCollectError.NoCostModel(l))
                case CollectError.BadTranslation(_) => None
              }
              .fold(Left(UtxowUnprojected.CollectErrors(errors)))(flat =>
                Right(
                  PredicateFailure.utxo(
                    FlatUtxoFailure.Utxos(
                      FlatUtxosFailure
                        .CollectErrors(cats.data.NonEmptyVector.of(flat.head, flat.tail*))
                    )
                  )
                )
              )
          )
      case InvalidWitnessesUTXOW(keys) =>
        utxow(FlatUtxowFailure.InvalidWitnesses(keys.toList.toVector))
      case MissingVKeyWitnessesUTXOW(hs) => utxow(FlatUtxowFailure.MissingVKeyWitnesses(set(hs)))
      case MissingScriptWitnessesUTXOW(hs) =>
        utxow(FlatUtxowFailure.MissingScriptWitnesses(set(hs)))
      case ScriptWitnessNotValidatingUTXOW(hs) =>
        utxow(FlatUtxowFailure.ScriptWitnessNotValidating(set(hs)))
      case MissingTxBodyMetadataHash(h) => utxow(FlatUtxowFailure.MissingTxBodyMetadataHash(h))
      case MissingTxMetadata(h)         => utxow(FlatUtxowFailure.MissingTxMetadata(h))
      case ConflictingMetadataHash(m)   => utxow(FlatUtxowFailure.ConflictingMetadataHash(m))
      case InvalidMetadata()            => utxow(FlatUtxowFailure.InvalidMetadata)
      case ExtraneousScriptWitnessesUTXOW(hs) =>
        utxow(FlatUtxowFailure.ExtraneousScriptWitnesses(set(hs)))
      case MissingRedeemers(ps) =>
        ps.toList
          .traverse((p, h) => purpose(p).map(_ -> h))
          .fold(Left(UtxowUnprojected.MissingRedeemers(ps)))(resolved =>
            utxow(FlatUtxowFailure.MissingRedeemers(resolved.toVector))
          )
      case MissingRequiredDatums(missing, received) =>
        utxow(FlatUtxowFailure.MissingRequiredDatums(set(missing), received))
      case NotAllowedSupplementalDatums(unallowed, acceptable) =>
        utxow(FlatUtxowFailure.NotAllowedSupplementalDatums(set(unallowed), acceptable))
      case PPViewHashesDontMatch(m) => utxow(FlatUtxowFailure.PPViewHashesDontMatch(m))
      case UnspendableUTxONoDatumHash(ins) =>
        utxow(FlatUtxowFailure.UnspendableUTxONoDatumHash(set(ins)))
      case ExtraRedeemers(ps) =>
        utxow(FlatUtxowFailure.ExtraRedeemers(ps.toList.toVector.map(p => (p.tag, p.ix.value))))
      case MalformedScriptWitnesses(hs) => utxow(FlatUtxowFailure.MalformedScriptWitnesses(set(hs)))
      case MalformedReferenceScripts(hs) =>
        utxow(FlatUtxowFailure.MalformedReferenceScripts(set(hs)))
      case ScriptIntegrityHashMismatch(m, expected) =>
        utxow(FlatUtxowFailure.ScriptIntegrityHashMismatch(m, expected))

  /** The flat form of a missing redeemer's purpose (`purpose` of [[toStored]]): the needed purpose
    * whose item it is, with its index, in its flat form (`ConwayPlutusPurpose.toStored`), its spent
    * output read from `utxo`.
    */
  def storedPurposes(
      scriptsNeeded: AlonzoScriptsNeeded[Conway],
      utxo: UTxO[Conway]
  ): ConwayPlutusPurpose[AsItem] => Option[Purpose] =
    val toAsItem = ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsItem](
      [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
    )
    val byItem = scriptsNeeded.unAlonzoScriptsNeeded.map((sp, _) => toAsItem(sp) -> sp).toMap
    p => byItem.get(p).flatMap(ConwayPlutusPurpose.toStored(_, utxo))

/** `EraRuleFailure "UTXOW" era`: Conway's is its own (`Conway/Rules/Utxow.hs:114`, cardano-ledger
  * f649f975).
  */
type UtxowPredFailure[E <: EraTag] = E match
  case Conway => ConwayUtxowPredFailure[Conway]

/** `EraRuleEvent "UTXO" era`: Conway's is Alonzo's (`Conway/Rules/Utxo.hs:148`, cardano-ledger
  * f649f975).
  */
type UtxoEvent[E <: EraTag] = E match
  case Conway => AlonzoUtxoEvent[Conway]

/** `newtype ShelleyUtxowEvent era` (`Shelley/Rules/Utxow.hs:144-146`, cardano-ledger f649f975). */
enum ShelleyUtxowEvent[E <: EraTag]:
  case UtxoEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.UtxoEvent[E])

/** `newtype AlonzoUtxowEvent era` (`Alonzo/Rules/Utxow.hs:178-180`, cardano-ledger f649f975). */
enum AlonzoUtxowEvent[E <: EraTag]:
  case WrappedShelleyEraEvent(event: ShelleyUtxowEvent[E])

/** `EraRuleEvent "UTXOW" era`: Conway's is Alonzo's (`Conway/Rules/Utxow.hs:116`, cardano-ledger
  * f649f975).
  */
type UtxowEvent[E <: EraTag] = E match
  case Conway => AlonzoUtxowEvent[Conway]

/** `InjectRuleFailure "UTXOW" f era` (`Core/Era.hs:122-125`, cardano-ledger f649f975): how era `E`
  * reports a failure `F` of a UTXOW predicate defined in an earlier era, or its own. One instance
  * per era and failure type, in the companion.
  */
trait InjectUtxowFailure[F, E <: EraTag]:
  def injectFailure(failure: F): UtxowPredFailure[E]

object InjectUtxowFailure:
  /** `Conway/Rules/Utxow.hs:118`, cardano-ledger f649f975: the identity. */
  given conway: InjectUtxowFailure[ConwayUtxowPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayUtxowPredFailure[Conway]): UtxowPredFailure[Conway] = failure

  /** `Conway/Rules/Utxow.hs:120-121`, cardano-ledger f649f975. */
  given conwayBabbage: InjectUtxowFailure[BabbageUtxowPredFailure[Conway], Conway] with
    def injectFailure(failure: BabbageUtxowPredFailure[Conway]): UtxowPredFailure[Conway] =
      ConwayUtxow.babbageToConwayUtxowPredFailure(failure)

  /** `Conway/Rules/Utxow.hs:123-124`, cardano-ledger f649f975. */
  given conwayAlonzo: InjectUtxowFailure[AlonzoUtxowPredFailure[Conway], Conway] with
    def injectFailure(failure: AlonzoUtxowPredFailure[Conway]): UtxowPredFailure[Conway] =
      ConwayUtxow.alonzoToConwayUtxowPredFailure(failure)

  /** `Conway/Rules/Utxow.hs:126-127`, cardano-ledger f649f975. */
  given conwayShelley: InjectUtxowFailure[ShelleyUtxowPredFailure[Conway], Conway] with
    def injectFailure(failure: ShelleyUtxowPredFailure[Conway]): UtxowPredFailure[Conway] =
      ConwayUtxow.shelleyToConwayUtxowPredFailure[Conway](failure)

  /** The failures of UTXO's predicates and of UTXOS, each `UtxoFailure . injectFailure`
    * (`Conway/Rules/Utxow.hs:129-145` and `:318-319`, cardano-ledger f649f975): one instance for
    * each type Conway's UTXO injects ([[InjectUtxoFailure]]), its injection into UTXO's then
    * wrapped. Shelley's UTXO failures are injected by the build's value (`UtxoContext`), so theirs
    * is [[conwayShelleyUtxo]].
    */
  given conwayUtxo[F](using inject: InjectUtxoFailure[F, Conway]): InjectUtxowFailure[F, Conway]
  with
    def injectFailure(failure: F): UtxowPredFailure[Conway] =
      ConwayUtxowPredFailure.UtxoFailure(inject.injectFailure(failure))

  /** `Conway/Rules/Utxow.hs:141-142`, cardano-ledger f649f975: Shelley's UTXO failures by the
    * build's injection into UTXO's, then wrapped.
    */
  def conwayShelleyUtxo(
      inject: InjectShelleyUtxoFailure[Conway]
  ): InjectUtxowFailure[ShelleyUtxoPredFailure[Conway], Conway] =
    new InjectUtxowFailure[ShelleyUtxoPredFailure[Conway], Conway]:
      def injectFailure(failure: ShelleyUtxoPredFailure[Conway]): UtxowPredFailure[Conway] =
        ConwayUtxowPredFailure.UtxoFailure(inject.injectFailure(failure))

/** `Embed (UTXO era) (UTXOW era)` (`Conway/Rules/Utxow.hs:201-213`, cardano-ledger f649f975): how
  * UTXO's failures and events sit in UTXOW's (`wrapFailed`, `wrapEvent`).
  */
trait EmbedUtxoInUtxow[E <: EraTag]:
  def wrapFailed(failure: UtxoPredFailure[E]): UtxowPredFailure[E]

  /** UTXO's event, Alonzo's in every era whose UTXO is typed (`EraRuleEvent "UTXO"`). */
  def wrapEvent(event: AlonzoUtxoEvent[E]): UtxowEvent[E]

object EmbedUtxoInUtxow:
  /** Conway's: `UtxoFailure`, and `WrappedShelleyEraEvent . UtxoEvent`. */
  given conway: EmbedUtxoInUtxow[Conway] with
    def wrapFailed(failure: UtxoPredFailure[Conway]): UtxowPredFailure[Conway] =
      ConwayUtxowPredFailure.UtxoFailure(failure)
    def wrapEvent(event: AlonzoUtxoEvent[Conway]): UtxowEvent[Conway] =
      AlonzoUtxowEvent.WrappedShelleyEraEvent(ShelleyUtxowEvent.UtxoEvent(event))

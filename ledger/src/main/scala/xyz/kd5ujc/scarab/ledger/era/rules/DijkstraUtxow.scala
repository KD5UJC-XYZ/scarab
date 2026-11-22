// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad}
import cats.data.{Chain, NonEmptyList, NonEmptySet}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TxIn, Verdicts}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraPurpose, DijkstraUtxowFailure}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPlutusPurpose, DijkstraUTxO, PlutusLegacyMode}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Judge, Sts, Test}
import xyz.kd5ujc.scarab.ledger.state.{Credential, Dijkstra, EraTag, Guard, UTxOState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

// UTXOW of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Utxow.hs`): its failures, the guards'
// checks and its transition, which runs the predicates of Shelley's, Alonzo's and Babbage's UTXOW
// (`Utxow.scala`) over every level of the batch where the reference aggregates them, and embeds
// Dijkstra's UTXO ([[DijkstraUtxo]]). Its injections of the earlier eras' failures are total: the
// one the reference calls impossible in Conway (the MIR quorum's) has no value at Dijkstra either
// ([[MirSigners]]).

/** `data DijkstraUtxowPredFailure era` (`Dijkstra/Rules/Utxow.hs:93-143`, cardano-ledger 9df9f98c),
  * its constructors in the reference's order, which is their tag (`:381-401`).
  */
enum DijkstraUtxowPredFailure[E <: EraTag]:
  case UtxoFailure(failure: UtxoPredFailure[E])

  /** The keys of the key and bootstrap witnesses whose signature does not verify. */
  case InvalidWitnessesUTXOW(keys: NonEmptyList[Bytes])
  case MissingVKeyWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case MissingScriptWitnessesUTXOW(hashes: NonEmptySet[Bytes])
  case ScriptWitnessNotValidatingUTXOW(hashes: NonEmptySet[Bytes])
  case MissingTxBodyMetadataHash(hash: Bytes)
  case MissingTxMetadata(hash: Bytes)
  case ConflictingMetadataHash(mismatch: Mismatch[RelEQ, Bytes])
  case InvalidMetadata()
  case ExtraneousScriptWitnessesUTXOW(hashes: NonEmptySet[Bytes])

  /** The purposes with no redeemer, as items, with the hash of the script each needs. */
  case MissingRedeemers(purposes: NonEmptyList[(PlutusPurpose[AsItem, E], Bytes)])

  /** The datums missing, and the witnesses' datums. */
  case MissingRequiredDatums(missing: NonEmptySet[Bytes], received: Set[Bytes])

  /** The supplemental datums not allowed, and those allowed. */
  case NotAllowedSupplementalDatums(unallowed: NonEmptySet[Bytes], acceptable: Set[Bytes])
  case PPViewHashesDontMatch(mismatch: Mismatch[RelEQ, Option[Bytes]])
  case UnspendableUTxONoDatumHash(inputs: NonEmptySet[TxIn])
  case ExtraRedeemers(purposes: NonEmptyList[PlutusPurpose[AsIx, E]])
  case MalformedScriptWitnesses(hashes: NonEmptySet[Bytes])
  case MalformedReferenceScripts(hashes: NonEmptySet[Bytes])

  /** The supplied and computed hashes, and the bytes the computed one is over. */
  case ScriptIntegrityHashMismatch(
      mismatch: Mismatch[RelEQ, Option[Bytes]],
      expected: Option[Bytes]
  )

  /** The guards the levels require of the top level that its guards lack. */
  case MissingRequiredGuards(credentials: NonEmptySet[Credential[Guard]])

  /** The required guards whose datum is present where it must not be, or absent where it must be.
    */
  case MalformedGuardDatums(credentials: NonEmptySet[Credential[Guard]])

/** What a failure's flat form cannot hold without what the transaction gives: UTXOS's collection
  * errors, and the purposes of missing redeemers, which the flat failure names in its own
  * vocabulary. It also holds the extra redeemers of an index past the range of `Int`, which the
  * reference's `Word32` index admits and the flat failure's `Int` cannot hold.
  */
enum DijkstraUtxowUnprojected:
  case CollectErrors(errors: NonEmptyList[CollectError[Dijkstra]])
  case MissingRedeemers(purposes: NonEmptyList[(DijkstraPlutusPurpose[AsItem], Bytes)])
  case ExtraRedeemers(purposes: NonEmptyList[DijkstraPlutusPurpose[AsIx]])

object DijkstraUtxowPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraUtxowPredFailure[E], DijkstraUtxowPredFailure[E]] =
    CanEqual.derived

  /** The failure in the node's flat form (`DijkstraUtxowFailure`), as
    * [[DijkstraUtxoPredFailure.toStored]] gives UTXO's: glue until the era failures have their own
    * encoders (#85), one-way. A missing redeemer's purpose is given its flat form by `purpose`; one
    * it does not give leaves the failure as it is, as do UTXOS's collection errors and an extra
    * redeemer whose `Word32` index the flat `Int` cannot hold: it is never narrowed.
    */
  def toStored(
      failure: DijkstraUtxowPredFailure[Dijkstra],
      purpose: DijkstraPlutusPurpose[AsItem] => Option[DijkstraPurpose]
  ): Either[DijkstraUtxowUnprojected, DijkstraUtxowFailure] =
    def ok(f: DijkstraUtxowFailure) = Right(f)
    def creds(cs: NonEmptySet[Credential[Guard]]) = cs.toSortedSet.unsorted.map(Convert.back)
    failure match
      case UtxoFailure(u) =>
        DijkstraUtxoPredFailure
          .toStored(u)
          .bimap(DijkstraUtxowUnprojected.CollectErrors(_), DijkstraUtxowFailure.UtxoFailure(_))
      case InvalidWitnessesUTXOW(ks) =>
        ok(DijkstraUtxowFailure.InvalidWitnesses(ks.toList.toVector))
      case MissingVKeyWitnessesUTXOW(hs) =>
        ok(DijkstraUtxowFailure.MissingVKeyWitnesses(hs.toSortedSet))
      case MissingScriptWitnessesUTXOW(hs) =>
        ok(DijkstraUtxowFailure.MissingScriptWitnesses(hs.toSortedSet))
      case ScriptWitnessNotValidatingUTXOW(hs) =>
        ok(DijkstraUtxowFailure.ScriptWitnessNotValidating(hs.toSortedSet))
      case MissingTxBodyMetadataHash(h) => ok(DijkstraUtxowFailure.MissingTxBodyMetadataHash(h))
      case MissingTxMetadata(h)         => ok(DijkstraUtxowFailure.MissingTxMetadata(h))
      case ConflictingMetadataHash(m) => ok(DijkstraUtxowFailure.ConflictingMetadataHash(m.stored))
      case InvalidMetadata()          => ok(DijkstraUtxowFailure.InvalidMetadata)
      case ExtraneousScriptWitnessesUTXOW(hs) =>
        ok(DijkstraUtxowFailure.ExtraneousScriptWitnesses(hs.toSortedSet))
      case MissingRedeemers(ps) =>
        ps.toList.toVector
          .traverse((p, h) => purpose(p).map(_ -> h))
          .fold(Left(DijkstraUtxowUnprojected.MissingRedeemers(ps)))(fs =>
            ok(DijkstraUtxowFailure.MissingRedeemers(fs))
          )
      case MissingRequiredDatums(m, r) =>
        ok(DijkstraUtxowFailure.MissingRequiredDatums(m.toSortedSet, r))
      case NotAllowedSupplementalDatums(u, a) =>
        ok(DijkstraUtxowFailure.NotAllowedSupplementalDatums(u.toSortedSet, a))
      case PPViewHashesDontMatch(m) => ok(DijkstraUtxowFailure.PPViewHashesDontMatch(m.stored))
      case UnspendableUTxONoDatumHash(ins) =>
        ok(DijkstraUtxowFailure.UnspendableUTxONoDatumHash(ins.toSortedSet))
      case ExtraRedeemers(ps) =>
        ps.toList.toVector
          .traverse(p => Option.when(p.ix.value.isValidInt)(p.tag -> p.ix.value.intValue))
          .fold(Left(DijkstraUtxowUnprojected.ExtraRedeemers(ps)))(is =>
            ok(DijkstraUtxowFailure.ExtraRedeemers(is))
          )
      case MalformedScriptWitnesses(hs) =>
        ok(DijkstraUtxowFailure.MalformedScriptWitnesses(hs.toSortedSet))
      case MalformedReferenceScripts(hs) =>
        ok(DijkstraUtxowFailure.MalformedReferenceScripts(hs.toSortedSet))
      case ScriptIntegrityHashMismatch(m, e) =>
        ok(DijkstraUtxowFailure.ScriptIntegrityHashMismatch(m.stored, e))
      case MissingRequiredGuards(cs) => ok(DijkstraUtxowFailure.MissingRequiredGuards(creds(cs)))
      case MalformedGuardDatums(cs)  => ok(DijkstraUtxowFailure.MalformedGuardDatums(creds(cs)))

/** Dijkstra's functions of UTXOW (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Utxow.hs`,
  * cardano-ledger 9df9f98c).
  */
object DijkstraUtxow:
  /** The order of a set of guards: the reference's derived `Ord` of a credential. */
  private val guardOrdering: Ordering[Credential[Guard]] =
    cats.Order[Credential[Guard]].toOrdering

  /** `conwayToDijkstraUtxowPredFailure` (`Dijkstra/Rules/Utxow.hs:436-459`, cardano-ledger
    * 9df9f98c): each failure as Dijkstra's own.
    */
  def conwayToDijkstraUtxowPredFailure[E <: EraTag](
      failure: ConwayUtxowPredFailure[E]
  ): DijkstraUtxowPredFailure[E] = failure match
    case ConwayUtxowPredFailure.UtxoFailure(f) => DijkstraUtxowPredFailure.UtxoFailure(f)
    case ConwayUtxowPredFailure.InvalidWitnessesUTXOW(ks) =>
      DijkstraUtxowPredFailure.InvalidWitnessesUTXOW(ks)
    case ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(ks) =>
      DijkstraUtxowPredFailure.MissingVKeyWitnessesUTXOW(ks)
    case ConwayUtxowPredFailure.MissingScriptWitnessesUTXOW(hs) =>
      DijkstraUtxowPredFailure.MissingScriptWitnessesUTXOW(hs)
    case ConwayUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(hs) =>
      DijkstraUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(hs)
    case ConwayUtxowPredFailure.MissingTxBodyMetadataHash(dh) =>
      DijkstraUtxowPredFailure.MissingTxBodyMetadataHash(dh)
    case ConwayUtxowPredFailure.MissingTxMetadata(dh) =>
      DijkstraUtxowPredFailure.MissingTxMetadata(dh)
    case ConwayUtxowPredFailure.ConflictingMetadataHash(mm) =>
      DijkstraUtxowPredFailure.ConflictingMetadataHash(mm)
    case ConwayUtxowPredFailure.InvalidMetadata() => DijkstraUtxowPredFailure.InvalidMetadata()
    case ConwayUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(hs) =>
      DijkstraUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(hs)
    case ConwayUtxowPredFailure.MissingRedeemers(pps) =>
      DijkstraUtxowPredFailure.MissingRedeemers(pps)
    case ConwayUtxowPredFailure.MissingRequiredDatums(hs1, hs2) =>
      DijkstraUtxowPredFailure.MissingRequiredDatums(hs1, hs2)
    case ConwayUtxowPredFailure.NotAllowedSupplementalDatums(hs1, hs2) =>
      DijkstraUtxowPredFailure.NotAllowedSupplementalDatums(hs1, hs2)
    case ConwayUtxowPredFailure.PPViewHashesDontMatch(mm) =>
      DijkstraUtxowPredFailure.PPViewHashesDontMatch(mm)
    case ConwayUtxowPredFailure.UnspendableUTxONoDatumHash(txs) =>
      DijkstraUtxowPredFailure.UnspendableUTxONoDatumHash(txs)
    case ConwayUtxowPredFailure.ExtraRedeemers(pps) => DijkstraUtxowPredFailure.ExtraRedeemers(pps)
    case ConwayUtxowPredFailure.MalformedScriptWitnesses(hs) =>
      DijkstraUtxowPredFailure.MalformedScriptWitnesses(hs)
    case ConwayUtxowPredFailure.MalformedReferenceScripts(hs) =>
      DijkstraUtxowPredFailure.MalformedReferenceScripts(hs)
    case ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(mm, f) =>
      DijkstraUtxowPredFailure.ScriptIntegrityHashMismatch(mm, f)

  /** `validateGuardDatums` (`Dijkstra/Rules/Utxow.hs:465-495`, cardano-ledger 9df9f98c): the
    * required top-level guards of a body of either level whose datum disagrees with the kind of
    * their credential: a key's or a native script's must have none, a Plutus script's must have
    * one; a script the transaction does not provide is not judged. SUBUTXOW runs it on a
    * sub-transaction's body.
    */
  def validateGuardDatums[E <: EraTag, L <: TxLevel](
      scriptsProvided: ScriptsProvided[E],
      txBody: TxBody[L, E]
  )(using
      b: DijkstraEraTxBody[E],
      l: b.Level[L],
      s: EraScript[E]
  ): Test[DijkstraUtxowPredFailure.MalformedGuardDatums[E]] =
    val malformed = b.requiredTopLevelGuards[L](txBody).collect {
      case (cred @ Credential.KeyHashObj(_), Some(_)) => cred
      case (cred @ Credential.ScriptHashObj(scriptHash), mbDatum)
          if scriptsProvided.unScriptsProvided
            .get(scriptHash.bytes)
            .exists(script => EraScript.isNativeScript[E](script) == mbDatum.isDefined) =>
        cred
    }
    Test.failureOnJust(NonEmptySet.fromSet(SortedSet.from(malformed)(using guardOrdering)))(
      new DijkstraUtxowPredFailure.MalformedGuardDatums[E](_)
    )

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private val u = EraUTxO.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** The guards the levels of the batch require of the top level (`requiredTopLevelGuards`, of the
    * top level and of each sub-transaction) that its guards lack
    * (`Dijkstra/Rules/Utxow.hs:312-318`, cardano-ledger 9df9f98c).
    */
  def validateRequiredGuards(
      txBody: TxBody[TxLevel.Top, Dijkstra]
  ): Test[DijkstraUtxowPredFailure.MissingRequiredGuards[Dijkstra]] =
    val requiredGuards = b.requiredTopLevelGuards[TxLevel.Top](txBody).keySet ++ b
      .subTransactions(txBody)
      .toVector
      .flatMap(sub => b.requiredTopLevelGuards[TxLevel.Sub](sub.body)(using BothLevels.Sub).keySet)
    val topLevelGuards = b.guards[TxLevel.Top](txBody).toVector.toSet
    Test.failureOnJust(
      NonEmptySet.fromSet(
        SortedSet.from(requiredGuards -- topLevelGuards)(using guardOrdering)
      )
    )(
      new DijkstraUtxowPredFailure.MissingRequiredGuards[Dijkstra](_)
    )

  /** `dijkstraUtxowTransition` (`Dijkstra/Rules/Utxow.hs:216-325`, cardano-ledger 9df9f98c), every
    * lookup against the UTxO set before the transaction (`ueOriginalUtxo`), every failure recorded
    * in the reference's order and injected into Dijkstra's:
    *   - the failing needed native scripts of the top level, among the scripts the whole batch
    *     provides;
    *   - the missing and extraneous scripts across every level: the scripts every level needs less
    *     the reference scripts of every level's inputs and reference inputs, against every level's
    *     witnesses;
    *   - the datums of the top level's inputs, the supplemental datums allowed those of the body
    *     without its sub-transactions in the legacy mode;
    *   - the top level's redeemers;
    *   - the signatures (on the signal), the keys needed against the certificate state before the
    *     transaction, the auxiliary data and the well-formed scripts (on the signal), and the
    *     script integrity hash, of the top level;
    *   - the guards the levels require of the top level (on the signal), and the required guards'
    *     datums;
    * then Dijkstra's UTXO as a sub-rule, its failures and events embedded in UTXOW's
    * ([[EmbedUtxoInUtxow]]). A signature check or a script decoding with no verdict is raised as
    * the node's `LedgerDefect`, as in Babbage's ([[BabbageUtxow.babbageUtxowTransition]]); UTXO
    * raises its own.
    */
  def dijkstraUtxowTransition[F[_]: Monad](
      env: DijkstraUtxoEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      state: Stateful[F, UTxOState[Dijkstra]],
      judge: Judge[F, UtxowPredFailure[Dijkstra]],
      events: Tell[F, Chain[UtxowEvent[Dijkstra]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect]
  ): F[Unit] =
    val embedUtxo = EmbedUtxoInUtxow.dijkstra
    val pp = env.pp
    val originalUtxo = env.originalUtxo
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)
    val txBody = t.body[TxLevel.Top](tx)
    val wits = t.wits[TxLevel.Top](tx)
    val subTxs = b.subTransactions(txBody).toVector
    val scriptsProvided = u.scriptsProvidedStAnnTx[TxLevel.Top](stAnnTx)
    val witsKeyHashes = EraTxWits.keyHashWitnessesTxWits[Dijkstra](wits)(using t.witsOf)
    val topScriptsNeeded = u.scriptsNeededStAnnTx[TxLevel.Top](stAnnTx)
    val topScriptHashesNeeded = u.getScriptsHashesNeeded(topScriptsNeeded)
    val allScriptHashesNeeded = topScriptHashesNeeded ++ u
      .subTransactionsStAnnTx(stAnnTx)
      .flatMap(sub => u.scriptsHashesNeededStAnnTx[TxLevel.Sub](sub)(using SubOnly.Sub))
    val witnessScripts = t.witsOf.scriptTxWits(wits).keySet ++ subTxs.flatMap(sub =>
      t.witsOf.scriptTxWits(t.wits[TxLevel.Sub](sub)(using BothLevels.Sub)).keySet
    )
    val allRefScriptInputs =
      b.referenceInputs[TxLevel.Top](txBody) ++ b.inputs[TxLevel.Top](txBody) ++ subTxs.flatMap {
        sub =>
          b.referenceInputs[TxLevel.Sub](sub.body)(using BothLevels.Sub) ++
            b.inputs[TxLevel.Sub](sub.body)(using BothLevels.Sub)
      }
    val refScripts =
      BabbageUTxO.getReferenceScripts(originalUtxo, allRefScriptInputs)(using b.outputsOf).keySet
    val datums = u.plutusLegacyModeStAnnTx(stAnnTx) match
      case PlutusLegacyMode.Legacy =>
        AlonzoUtxow.missingRequiredDatumsAllowing[Dijkstra, TxLevel.Top](
          scriptsProvided,
          originalUtxo,
          tx,
          DijkstraUTxO.getDijkstraSupplementalDataHashesWithoutSubTransactions(originalUtxo, txBody)
        )
      case PlutusLegacyMode.NotLegacy =>
        AlonzoUtxow.missingRequiredDatums[Dijkstra, TxLevel.Top](scriptsProvided, originalUtxo, tx)
    def test[A](t: Test[A])(using inject: InjectUtxowFailure[A, Dijkstra]): F[Unit] =
      Sts.runTest[F, UtxowPredFailure[Dijkstra]](t.leftMap(_.map(inject.injectFailure)))
    def checked[A](t: Either[LedgerDefect, Test[A]])(using
        InjectUtxowFailure[A, Dijkstra]
    ): F[Unit] =
      Sts.lift[F, LedgerDefect, Test[A]](t).flatMap(test(_))
    def onSignal(fa: => F[Unit]): F[Unit] = context.validation match
      case xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All       => fa
      case xyz.kd5ujc.scarab.ledger.TxEnv.Validation.NonStatic => Monad[F].unit
    given Judge[F, UtxoPredFailure[Dijkstra]] = judge.contramap(embedUtxo.wrapFailed)
    given Tell[F, Chain[AlonzoUtxoEvent[Dijkstra]]] = new Tell[F, Chain[AlonzoUtxoEvent[Dijkstra]]]:
      val functor: cats.Functor[F] = events.functor
      def tell(l: Chain[AlonzoUtxoEvent[Dijkstra]]): F[Unit] =
        events.tell(l.map(embedUtxo.wrapEvent))
    for
      _ <- test(
        BabbageUtxow.validateFailedBabbageScripts[Dijkstra, TxLevel.Top](
          tx,
          scriptsProvided,
          topScriptHashesNeeded
        )
      )
      _ <- test(
        BabbageUtxow.babbageMissingScripts[Dijkstra](
          pp,
          allScriptHashesNeeded,
          refScripts,
          witnessScripts
        )
      )
      _ <- test(datums)
      _ <- test(
        AlonzoUtxow.hasExactSetOfRedeemers[Dijkstra, TxLevel.Top](
          tx,
          scriptsProvided,
          topScriptsNeeded
        )
      )
      _ <- onSignal(checked(ShelleyUtxow.validateVerifiedWits[Dijkstra, TxLevel.Top](tx, verdicts)))
      _ <- test(
        ShelleyUtxow.validateNeededWitnesses[Dijkstra, TxLevel.Top](
          witsKeyHashes,
          env.originalCertState,
          originalUtxo,
          txBody
        )
      )
      _ <- onSignal(
        checked(ShelleyUtxow.validateMetadata[Dijkstra, TxLevel.Top](decoder, pp, stAnnTx))
      )
      _ <- onSignal(checked(BabbageUtxow.validateScriptsWellFormed[Dijkstra](decoder, pp, stAnnTx)))
      _ <- test(
        AlonzoUtxow.checkScriptIntegrityHash[Dijkstra, TxLevel.Top](
          tx,
          pp,
          ScriptIntegrity.mkScriptIntegrity[Dijkstra, TxLevel.Top](
            pp,
            tx,
            u.plutusLanguagesUsedStAnnTx[TxLevel.Top](stAnnTx)
          )
        )
      )
      _ <- onSignal(test(validateRequiredGuards(txBody)))
      _ <- test(validateGuardDatums[Dijkstra, TxLevel.Top](scriptsProvided, txBody))
      _ <- judge.nest(DijkstraUtxo.dijkstraUtxoTransition[F](env, stAnnTx, context))
    yield ()

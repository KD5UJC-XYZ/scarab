// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import scala.collection.immutable.{ListMap, SortedSet}
import cats.{Id, Monad}
import cats.data.{Chain, NonEmptyList, NonEmptySet}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  LedgerDefect,
  LedgerFailure,
  Mismatch,
  ScriptLocation,
  TxIn,
  Verdicts
}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Sts, Test}
import xyz.kd5ujc.scarab.ledger.state.{CertState, EraTag, UTxOState}

// The predicates of UTXOW as the reference writes them, each generic in its era and asking for the
// reference's classes (cardano-ledger f649f975, the `Rules/Utxow.hs` of each era), and the UTXOW
// transition Babbage and Conway run, which embeds UTXO, as `era/rules/Utxo.scala` writes UTXO's. A
// predicate reports the failure type of its era (`UtxowFailures.scala`), and the rule injects it
// into its own.

/** A non-empty set of hashes, if there are any. */
private def hashes(hs: Set[Bytes]): Option[NonEmptySet[Bytes]] =
  NonEmptySet.fromSet(SortedSet.from(hs))

/** The defect of a script the decoder gives no verdict for, in the transaction `txId`, as the flat
  * checks report it.
  */
private def unavailable(txId: Bytes)(u: UnavailableScript): LedgerDefect =
  LedgerFailure.Unavailable(
    txId,
    ScriptLocation.Phase1(u.scriptHash),
    EvalFailure.Unsupported(u.cause)
  )

/** Shelley's predicates of UTXOW (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxow.hs`,
  * cardano-ledger f649f975).
  */
object ShelleyUtxow:
  /** `validateVerifiedWits` (`Shelley/Rules/Utxow.hs:390-407`, cardano-ledger f649f975): the keys
    * of the key witnesses, then of the bootstrap witnesses, whose signature of the body's hash does
    * not verify. The key witnesses are in their set's order (`Ord WitVKey`), as the reference lists
    * them; the bootstrap witnesses in the order `bootAddrTxWits` gives, a sequence the decoder
    * holds in the reference's set order (`Ord BootstrapWitness`, by address root, one per root:
    * [[xyz.kd5ujc.scarab.ledger.BootstrapWitness.set]]). The node's signature checks are
    * `verdicts`; one that gives no verdict is a defect of the provider, not a failure of the
    * transaction (`LedgerFailure.Unverifiable`), as in the flat check.
    */
  def validateVerifiedWits[E <: EraTag, L <: TxLevel](tx: Tx[L, E], verdicts: Verdicts)(using
      t: EraTx[E],
      l: t.bodyOf.Level[L]
  ): Either[LedgerDefect, Test[ShelleyUtxowPredFailure[E]]] =
    val txBodyHash = t.bodyOf.id[L](t.body[L](tx))
    val wits = t.wits[L](tx)
    def failing(witnesses: Vector[(Bytes, Bytes)]) =
      witnesses.traverseFilter((vkey, signature) =>
        verdicts.witness(vkey, signature, txBodyHash).map(valid => Option.unless(valid)(vkey))
      )
    (
      failing(t.witsOf.addrTxWits(wits).toVector.map(w => (w.vkey, w.signature))),
      failing(t.witsOf.bootAddrTxWits(wits).map(w => (w.vkey, w.signature)))
    ).mapN(_ ++ _)
      .bimap(
        LedgerFailure.Unverifiable(txBodyHash, _),
        failed =>
          Test.failureOnJust(NonEmptyList.fromList(failed.toList))(
            ShelleyUtxowPredFailure.InvalidWitnessesUTXOW(_)
          )
      )

  /** `validateNeededWitnesses` (`Shelley/Rules/Utxow.hs:409-423`, cardano-ledger f649f975): the
    * hashes of the keys that must sign (`getWitsVKeyNeeded`) and that no witness provides.
    */
  def validateNeededWitnesses[E <: EraTag, L <: TxLevel](
      witsKeyHashes: Set[Bytes],
      certState: xyz.kd5ujc.scarab.ledger.state.CertState[E],
      utxo: UTxO[E],
      txBody: TxBody[L, E]
  )(using u: EraUTxO[E], l: u.txOf.bodyOf.Level[L]): Test[ShelleyUtxowPredFailure[E]] =
    val needed = u.getWitsVKeyNeeded[L](certState, utxo, txBody)
    Test.failureOnJust(hashes(needed -- witsKeyHashes))(
      ShelleyUtxowPredFailure.MissingVKeyWitnessesUTXOW(_)
    )

  /** `validateMetadata` (`Shelley/Rules/Utxow.hs:427-443`, cardano-ledger f649f975): the body's
    * hash of the auxiliary data and the data go together, and with both the hash is the data's
    * (`ConflictingMetadataHash`) and the data well formed at the parameters' protocol version
    * (`InvalidMetadata`, `validateTxAuxData` with the annotated transaction's cache), both
    * reported. A script the decoder gives no verdict for is a defect of this implementation
    * ([[UnavailableScript]]), the first in the order the scripts arrived, as in the flat check. The
    * scripts are read only where the data and its hash are both present, the reference's order; the
    * flat check reads them first, which differs only where the decoder gives no verdict: there,
    * data without its hash is this predicate's `MissingTxBodyMetadataHash` and the flat check's
    * defect.
    */
  def validateMetadata[E <: EraTag, L <: TxLevel](
      decoder: ScriptDecoder[Id],
      pp: PParams[E],
      stAnnTx: StAnnTx[L, E]
  )(using
      t: EraTx[E],
      l: t.bodyOf.Level[L],
      p: EraPParams[E]
  ): Either[LedgerDefect, Test[ShelleyUtxowPredFailure[E]]] =
    val tx = t.txStAnnTx(stAnnTx)
    val txBody = t.body(tx)
    val pv = p.ppProtocolVersionL.get(pp)
    (t.bodyOf.auxDataHash(txBody), t.auxData(tx)) match
      case (None, None) => Right(Test.pass)
      case (Some(mdh), None) =>
        Right(Test.failureUnless(false, ShelleyUtxowPredFailure.MissingTxMetadata[E](mdh)))
      case (None, Some(md)) =>
        Right(
          Test.failureUnless(
            false,
            ShelleyUtxowPredFailure.MissingTxBodyMetadataHash[E](
              EraTxAuxData.hashTxAuxData(md)(using t.auxDataOf)
            )
          )
        )
      case (Some(mdh), Some(md)) =>
        val computed = EraTxAuxData.hashTxAuxData(md)(using t.auxDataOf)
        t.auxDataOf
          .validateTxAuxData(decoder, t.cacheStAnnTx(stAnnTx), pv, md)
          .leftMap(unavailable(t.bodyOf.id(txBody)))
          .map(valid =>
            Test.all(
              Test.failureUnless(
                computed == mdh,
                ShelleyUtxowPredFailure.ConflictingMetadataHash[E](Mismatch(mdh, computed))
              ),
              Test.failureUnless(valid, ShelleyUtxowPredFailure.InvalidMetadata[E]())
            )
          )

/** Alonzo's predicates of UTXOW (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxow.hs`,
  * cardano-ledger f649f975).
  */
object AlonzoUtxow:
  /** `missingRequiredDatums` (`Alonzo/Rules/Utxow.hs:210-234`, cardano-ledger f649f975): the spent
    * Plutus outputs that need a datum and have none (`UnspendableUTxONoDatumHash`), the datums the
    * spent outputs name that the witnesses lack (`MissingRequiredDatums`, with the witnesses'
    * hashes), and the witnesses' other datums that are not supplemental
    * (`NotAllowedSupplementalDatums`, with the supplemental ones), in that order.
    */
  def missingRequiredDatums[E <: EraTag, L <: TxLevel](
      scriptsProvided: ScriptsProvided[E],
      utxo: UTxO[E],
      tx: Tx[L, E]
  )(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      u: AlonzoEraUTxO[E],
      lu: u.txOf.bodyOf.Level[L]
  ): Test[AlonzoUtxowPredFailure[E]] =
    missingRequiredDatumsAllowing[E, L](
      scriptsProvided,
      utxo,
      tx,
      u.getSupplementalDataHashes[L](utxo, t.body[L](tx))
    )

  /** [[missingRequiredDatums]] with the supplemental datums allowed given: those
    * `getSupplementalDataHashes` gives of the body, or, for Dijkstra's UTXOW in the legacy mode,
    * those of the body with its sub-transactions taken out (`Dijkstra/Rules/Utxow.hs:281-285`,
    * cardano-ledger 9df9f98c,
    * [[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraUTxO.getDijkstraSupplementalDataHashesWithoutSubTransactions]]),
    * the only part of the transaction the sub-transactions change here.
    */
  def missingRequiredDatumsAllowing[E <: EraTag, L <: TxLevel](
      scriptsProvided: ScriptsProvided[E],
      utxo: UTxO[E],
      tx: Tx[L, E],
      allowedSupplementalDataHashes: Set[Bytes]
  )(using t: AlonzoEraTx[E], l: t.bodyOf.Level[L]): Test[AlonzoUtxowPredFailure[E]] =
    val txBody = t.body[L](tx)
    val (inputHashes, txInsNoDataHash) = AlonzoUTxO.getInputDataHashesTxBody[E, L](
      utxo,
      txBody,
      scriptsProvided
    )(using t.bodyOf, l, t.bodyOf.outputsOf, t.witsOf.scriptsOf)
    val txHashes: Set[Bytes] = t.witsOf.datsTxWits(t.wits[L](tx)).datums.keySet
    val unmatchedDatumHashes = inputHashes -- txHashes
    val supplimentalDatumHashes = txHashes -- inputHashes
    val (okSupplimentalDHs, notOkSupplimentalDHs) =
      supplimentalDatumHashes.partition(allowedSupplementalDataHashes.contains)
    Test.all(
      Test.failureOnJust(
        NonEmptySet.fromSet(SortedSet.from(txInsNoDataHash)(using TxIn.given_Ordering_TxIn))
      )(
        AlonzoUtxowPredFailure.UnspendableUTxONoDatumHash(_)
      ),
      Test.failureOnJust(hashes(unmatchedDatumHashes))(
        AlonzoUtxowPredFailure.MissingRequiredDatums(_, txHashes)
      ),
      Test.failureOnJust(hashes(notOkSupplimentalDHs))(
        AlonzoUtxowPredFailure.NotAllowedSupplementalDatums(_, okSupplimentalDHs)
      )
    )

  /** `hasExactSetOfRedeemers` (`Alonzo/Rules/Utxow.hs:239-262`, cardano-ledger f649f975): the
    * redeemers are exactly those of the purposes needed whose script provided is a Plutus script.
    * The redeemers no such purpose names, in their map's order (`ExtraRedeemers`), then the
    * purposes needed that no redeemer names, as items (`toAsItem`), in the order of the scripts
    * needed with their scripts' hashes (`MissingRedeemers`). The purposes change form through the
    * script class's `hoistPlutusPurpose`, a member of `ConwayEraScript` here as the purpose
    * builders are not members of `AlonzoEraScript` yet (#85), so it serves Conway and Dijkstra.
    */
  def hasExactSetOfRedeemers[E <: EraTag, L <: TxLevel](
      tx: Tx[L, E],
      scriptsProvided: ScriptsProvided[E],
      scriptsNeeded: AlonzoScriptsNeeded[E]
  )(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      s: ConwayEraScript[E]
  ): Test[AlonzoUtxowPredFailure[E]] =
    given EraScript[E] = t.witsOf.scriptsOf
    val toAsIx = s.hoistPlutusPurpose[AsIxItem, AsIx](
      [ix, it] => (asIxItem: AsIxItem[ix, it]) => AsIxItem.toAsIx(asIxItem)
    )
    val toAsItem = s.hoistPlutusPurpose[AsIxItem, AsItem](
      [ix, it] => (asIxItem: AsIxItem[ix, it]) => AsIxItem.toAsItem(asIxItem)
    )
    val redeemersNeeded = scriptsNeeded.unAlonzoScriptsNeeded.flatMap { (sp, sh) =>
      scriptsProvided.unScriptsProvided
        .get(sh)
        .filterNot(EraScript.isNativeScript[E](_))
        .map(_ => (toAsIx(sp), (sp, sh)))
    }
    val (extraRdmrs, missingRdmrs) =
      extSymmetricDifference(
        t.witsOf.rdmrsTxWits(t.wits[L](tx)).entries.keys.toVector,
        identity,
        redeemersNeeded,
        _._1
      )
    Test.all(
      Test.failureOnJust(NonEmptyList.fromList(extraRdmrs.toList))(
        AlonzoUtxowPredFailure.ExtraRedeemers(_)
      ),
      Test.failureOnJust(
        NonEmptyList.fromList(
          missingRdmrs.toList.map((_, needed) => (toAsItem(needed._1), needed._2))
        )
      )(AlonzoUtxowPredFailure.MissingRedeemers(_))
    )

  /** `extSymmetricDifference` (`Alonzo/Rules/Utxow.hs:378-383`, cardano-ledger f649f975): the
    * elements of each list whose key the other list lacks, each list's order and duplicates kept.
    */
  def extSymmetricDifference[A, B, K](
      as: Vector[A],
      fa: A => K,
      bs: Vector[B],
      fb: B => K
  ): (Vector[A], Vector[B]) =
    val intersection = as.map(fa).toSet.intersect(bs.map(fb).toSet)
    val extraA = as.filterNot(x => intersection.contains(fa(x)))
    val extraB = bs.filterNot(x => intersection.contains(fb(x)))
    (extraA, extraB)

  /** `checkScriptIntegrityHash` (`Alonzo/Rules/Utxow.hs:266-286`, cardano-ledger f649f975): the
    * body's script integrity hash is the hash of `scriptIntegrity` (none for none). A mismatch is
    * `PPViewHashesDontMatch` below protocol version 11, and from 11 `ScriptIntegrityHashMismatch`
    * with the bytes the expected hash is over.
    */
  def checkScriptIntegrityHash[E <: EraTag, L <: TxLevel](
      tx: Tx[L, E],
      pp: PParams[E],
      scriptIntegrity: Option[ScriptIntegrity[E]]
  )(using
      t: AlonzoEraTx[E],
      p: EraPParams[E],
      era: Era[E],
      l: t.bodyOf.Level[L]
  ): Test[AlonzoUtxowPredFailure[E]] =
    val computedScriptIntegrityHash = scriptIntegrity.map(ScriptIntegrity.hashScriptIntegrity(_))
    val suppliedScriptIntegrityHash = t.bodyOf.scriptIntegrityHash[L](t.body[L](tx))
    val expectedScriptIntegrity = scriptIntegrity.map(ScriptIntegrity.originalBytes(_))
    val mismatch = Mismatch(suppliedScriptIntegrityHash, computedScriptIntegrityHash)
    Test.failureUnless(
      suppliedScriptIntegrityHash == computedScriptIntegrityHash,
      if p.ppProtocolVersionL.get(pp).pvMajor.value < 11 then
        AlonzoUtxowPredFailure.PPViewHashesDontMatch(mismatch)
      else AlonzoUtxowPredFailure.ScriptIntegrityHashMismatch(mismatch, expectedScriptIntegrity)
    )

/** Babbage's predicates of UTXOW (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxow.hs`,
  * cardano-ledger f649f975).
  */
object BabbageUtxow:
  /** `babbageMissingScripts` (`Babbage/Rules/Utxow.hs:193-208`, cardano-ledger f649f975): the
    * scripts received are exactly the needed ones less those the reference inputs and the inputs
    * provide. The extraneous first, then the missing, Shelley's failures. The parameters are
    * unused, as in the reference.
    */
  def babbageMissingScripts[E <: EraTag](
      pp: PParams[E],
      sNeeded: Set[Bytes],
      sRefs: Set[Bytes],
      sReceived: Set[Bytes]
  ): Test[ShelleyUtxowPredFailure[E]] =
    val _ = pp
    val neededNonRefs = sNeeded -- sRefs
    val missing = neededNonRefs -- sReceived
    val extra = sReceived -- neededNonRefs
    Test.all(
      Test.failureOnJust(hashes(extra))(ShelleyUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(_)),
      Test.failureOnJust(hashes(missing))(ShelleyUtxowPredFailure.MissingScriptWitnessesUTXOW(_))
    )

  /** `validateFailedBabbageScripts` (`Babbage/Rules/Utxow.hs:211-231`, cardano-ledger f649f975):
    * the needed native scripts among those the transaction can use that do not hold
    * (`validateNativeScript`), in the map's order, Shelley's `ScriptWitnessNotValidatingUTXOW`.
    */
  def validateFailedBabbageScripts[E <: EraTag, L <: TxLevel](
      tx: Tx[L, E],
      scriptsProvided: ScriptsProvided[E],
      neededHashes: Set[Bytes]
  )(using t: EraTx[E], l: t.bodyOf.Level[L]): Test[ShelleyUtxowPredFailure[E]] =
    val s = t.witsOf.scriptsOf
    val failedScripts = scriptsProvided.unScriptsProvided.filter((scriptHash, script) =>
      s.getNativeScript(script)
        .exists(native =>
          neededHashes.contains(scriptHash) && !t.validateNativeScript[L](tx, native)
        )
    )
    Test.failureOnJust(hashes(failedScripts.keySet))(
      ShelleyUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(_)
    )

  /** `validateScriptsWellFormed` (`Babbage/Rules/Utxow.hs:234-251`, cardano-ledger f649f975): the
    * scripts of the witnesses and the reference scripts of the outputs, the collateral return's
    * included, are well formed, with the annotated transaction's cache.
    */
  def validateScriptsWellFormed[E <: EraTag](
      decoder: ScriptDecoder[Id],
      pp: PParams[E],
      stAnnTx: StAnnTx[TxLevel.Top, E]
  )(using
      t: EraTx[E],
      b: BabbageEraTxBody[E],
      s: AlonzoEraScript[E],
      p: EraPParams[E],
      cache: StAnnTxCache[E] =:= Map[Bytes, SupportedPlutusRunnable[E]]
  ): Either[LedgerDefect, Test[BabbageUtxowPredFailure[E]]] =
    val top = t.bodyOf.top
    val tx = t.txStAnnTx(stAnnTx)(using top)
    val txBody = t.body(tx)(using top)
    val normalOuts = b.outputs(txBody)(using b.top)
    val returnOut = b.collateralReturn(txBody)
    val wits = t.wits(tx)(using top)
    val witScripts = t.witsOf.scriptTxWits(wits)
    // the witnesses' scripts in the order they arrived, which only the choice of the first script
    // the decoder gives no verdict for reads
    val inArrival = ListMap.from(
      t.witsOf.scriptTxWitsArrival(wits).flatMap(h => witScripts.get(h).map(h -> _))
    )
    validateScriptsWellFormedTxOuts(
      decoder,
      cache(t.cacheStAnnTx(stAnnTx)(using top)),
      pp,
      inArrival,
      normalOuts ++ returnOut.toVector
    )(using b.outputsOf, s, p).leftMap(unavailable(t.bodyOf.id(txBody)(using top)))

  /** `validateScriptsWellFormedTxOuts` (`Babbage/Rules/Utxow.hs:254-276`, cardano-ledger f649f975):
    * the witnesses' scripts that are not valid (`MalformedScriptWitnesses`), then the outputs'
    * reference scripts, by their hashes (`MalformedReferenceScripts`), both reported, each script
    * checked by `isValidScript`. The first script the decoder gives no verdict for is returned, in
    * the order the scripts are visited: the witnesses' in the map's order
    * ([[validateScriptsWellFormed]] gives the order they arrived), then the outputs' in the
    * outputs' order, the collateral return's last, as the flat check visits them; the reference's
    * decoding always gives a verdict, so it has no such choice.
    */
  def validateScriptsWellFormedTxOuts[E <: EraTag](
      decoder: ScriptDecoder[Id],
      plutusScriptsCache: Map[Bytes, SupportedPlutusRunnable[E]],
      pp: PParams[E],
      witScripts: Map[Bytes, Script[E]],
      txOuts: Iterable[TxOut[E]]
  )(using
      o: BabbageEraTxOut[E],
      s: AlonzoEraScript[E],
      p: EraPParams[E]
  ): Either[UnavailableScript, Test[BabbageUtxowPredFailure[E]]] =
    val pv = p.ppProtocolVersionL.get(pp)
    def filterInvalidScripts(
        scripts: Map[Bytes, Script[E]]
    ): Either[UnavailableScript, Set[Bytes]] =
      scripts.toVector
        .traverse((scriptHash, script) =>
          AlonzoTxAuxData
            .isValidScript(decoder, plutusScriptsCache, pv, scriptHash, script)
            .map(valid => Option.unless(valid)(scriptHash))
        )
        .map(_.flatten.toSet)
    val refScripts = ListMap.from(
      txOuts.toVector
        .flatMap(o.referenceScript)
        .map(script => s.hashScript(script) -> script)
        .distinctBy(_._1)
    )
    for
      witnesses <- filterInvalidScripts(witScripts)
      references <- filterInvalidScripts(refScripts)
    yield Test.all(
      Test.failureOnJust(hashes(witnesses))(BabbageUtxowPredFailure.MalformedScriptWitnesses(_)),
      Test.failureOnJust(hashes(references))(BabbageUtxowPredFailure.MalformedReferenceScripts(_))
    )

  /** `babbageUtxowTransition` (`Babbage/Rules/Utxow.hs:308-390`, cardano-ledger f649f975), the
    * UTXOW transition Babbage and Conway run: the failing needed native scripts, the missing and
    * extraneous scripts beside the reference scripts, the datums, the redeemers, the signatures,
    * the witnesses needed, the auxiliary data, the well-formed scripts and the script integrity
    * hash, every failure recorded in that order and injected into the era's own; then UTXO as a
    * sub-rule (`trans`), its failures and events embedded in UTXOW's ([[EmbedUtxoInUtxow]]). The
    * signatures, the auxiliary data and the well-formed scripts run only when the policy validates
    * the signal (`runTestOnSignal`).
    *
    * The UTxO set the predicates read is the outputs of the body's inputs, collateral inputs and
    * reference inputs read from the store, as UTXO's ([[ConwayUtxo.conwayUtxoTransition]]). The
    * node's signature checks are `verdicts` and its script decoder `decoder`. A signature check
    * with no verdict and a script the decoder gives no verdict for are raised as the node's defects
    * (`LedgerDefect`), as the flat rule raises them; UTXO raises its own.
    */
  def babbageUtxowTransition[F[_]: Monad, E <: EraTag](
      env: UtxoEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E],
      context: UtxoContext[E],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      e: EraUTxO[E],
      t: AlonzoEraTx[E],
      b: ConwayEraTxBody[E],
      c: EraCertState[E],
      w: AlonzoEraTxWits[E],
      pa: AlonzoEraPParams[E],
      pb: BabbageEraPParams[E],
      k: EraStake[E],
      s: StoredTxOut[E],
      v: Value[E] =:= MultiAssetValue,
      u: AlonzoEraUTxO[E],
      sc: ConwayEraScript[E],
      era: Era[E],
      needed: ScriptsNeeded[E] =:= AlonzoScriptsNeeded[E],
      cached: StAnnTxCache[E] =:= Map[Bytes, SupportedPlutusRunnable[E]],
      allegra: InjectUtxoFailure[AllegraUtxoPredFailure[E], E],
      alonzo: InjectUtxoFailure[AlonzoUtxoPredFailure[E], E],
      babbage: InjectUtxoFailure[BabbageUtxoPredFailure[E], E],
      utxos: InjectAlonzoUtxos[E],
      embedUtxos: EmbedUtxosInUtxo[E],
      shelleyW: InjectUtxowFailure[ShelleyUtxowPredFailure[E], E],
      alonzoW: InjectUtxowFailure[AlonzoUtxowPredFailure[E], E],
      babbageW: InjectUtxowFailure[BabbageUtxowPredFailure[E], E],
      embedUtxo: EmbedUtxoInUtxow[E],
      state: Stateful[F, UTxOState[E]],
      judge: Judge[F, UtxowPredFailure[E]],
      events: Tell[F, Chain[UtxowEvent[E]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect]
  ): F[Unit] =
    val top = t.bodyOf.top
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using top)
    val txBody = t.body[TxLevel.Top](tx)(using top)
    val wits = t.wits[TxLevel.Top](tx)(using top)
    val pp = env.pp
    val witsKeyHashes = EraTxWits.keyHashWitnessesTxWits[E](wits)(using t.witsOf)
    val inputs =
      b.referenceInputs[TxLevel.Top](txBody)(using b.top) ++ b.inputs[TxLevel.Top](txBody)(using
        b.top
      )
    val scriptsNeeded = u.scriptsNeededStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    val scriptsProvided = u.scriptsProvidedStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
    val scriptHashesNeeded = e.getScriptsHashesNeeded(scriptsNeeded)
    def test[A](t: Test[A])(using inject: InjectUtxowFailure[A, E]): F[Unit] =
      Sts.runTest[F, UtxowPredFailure[E]](t.leftMap(_.map(inject.injectFailure)))
    def checked[A](t: Either[LedgerDefect, Test[A]])(using InjectUtxowFailure[A, E]): F[Unit] =
      Sts.lift[F, LedgerDefect, Test[A]](t).flatMap(test(_))
    def onSignal(fa: => F[Unit]): F[Unit] = context.validation match
      case xyz.kd5ujc.scarab.ledger.TxEnv.Validation.All       => fa
      case xyz.kd5ujc.scarab.ledger.TxEnv.Validation.NonStatic => Monad[F].unit
    given Judge[F, UtxoPredFailure[E]] = judge.contramap(embedUtxo.wrapFailed)
    given Tell[F, Chain[AlonzoUtxoEvent[E]]] = new Tell[F, Chain[AlonzoUtxoEvent[E]]]:
      val functor: cats.Functor[F] = events.functor
      def tell(l: Chain[AlonzoUtxoEvent[E]]): F[Unit] = events.tell(l.map(embedUtxo.wrapEvent))
    for
      utxoState <- state.get
      utxo <- ShelleyUtxo.readStored[F, E](
        ShelleyUtxo.extractKeys(utxoState.utxo.utxo, b.allInputs(txBody))._2
      )
      _ <- test(
        validateFailedBabbageScripts[E, TxLevel.Top](tx, scriptsProvided, scriptHashesNeeded)(using
          t,
          top
        )
      )
      _ <- test(
        babbageMissingScripts(
          pp,
          scriptHashesNeeded,
          BabbageUTxO.getReferenceScripts(utxo, inputs)(using b.outputsOf).keySet,
          t.witsOf.scriptTxWits(wits).keySet
        )
      )
      _ <- test(
        AlonzoUtxow.missingRequiredDatums[E, TxLevel.Top](scriptsProvided, utxo, tx)(using
          t,
          top,
          u,
          u.txOf.bodyOf.top
        )
      )
      _ <- test(
        AlonzoUtxow.hasExactSetOfRedeemers[E, TxLevel.Top](
          tx,
          scriptsProvided,
          needed(scriptsNeeded)
        )(using t, top, sc)
      )
      _ <- onSignal(
        checked(ShelleyUtxow.validateVerifiedWits[E, TxLevel.Top](tx, verdicts)(using t, top))
      )
      _ <- test(
        ShelleyUtxow
          .validateNeededWitnesses[E, TxLevel.Top](witsKeyHashes, env.certState, utxo, txBody)(using
            e,
            e.txOf.bodyOf.top
          )
      )
      _ <- onSignal(
        checked(ShelleyUtxow.validateMetadata[E, TxLevel.Top](decoder, pp, stAnnTx)(using t, top))
      )
      _ <- onSignal(checked(validateScriptsWellFormed[E](decoder, pp, stAnnTx)))
      _ <- test(
        AlonzoUtxow.checkScriptIntegrityHash[E, TxLevel.Top](
          tx,
          pp,
          ScriptIntegrity.mkScriptIntegrity[E, TxLevel.Top](
            pp,
            tx,
            u.plutusLanguagesUsedStAnnTx[TxLevel.Top](stAnnTx)(using u.txOf.bodyOf.top)
          )(using t, pa, w, top)
        )(using t, summon[EraPParams[E]], era, top)
      )
      _ <- judge.nest(ConwayUtxo.conwayUtxoTransition[F, E](env, stAnnTx, context))
    yield ()

/** Conway's functions of UTXOW (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxow.hs`,
  * cardano-ledger f649f975): the injections of the earlier eras' failures into its own.
  */
object ConwayUtxow:
  /** `babbageToConwayUtxowPredFailure` (`Conway/Rules/Utxow.hs:278-287`, cardano-ledger f649f975).
    */
  def babbageToConwayUtxowPredFailure[E <: EraTag](
      failure: BabbageUtxowPredFailure[E]
  )(using void: MirSigners[E] =:= Nothing): ConwayUtxowPredFailure[E] = failure match
    case BabbageUtxowPredFailure.AlonzoInBabbageUtxowPredFailure(x) =>
      alonzoToConwayUtxowPredFailure(x)
    case BabbageUtxowPredFailure.UtxoFailure(x) => ConwayUtxowPredFailure.UtxoFailure(x)
    case BabbageUtxowPredFailure.MalformedScriptWitnesses(xs) =>
      ConwayUtxowPredFailure.MalformedScriptWitnesses(xs)
    case BabbageUtxowPredFailure.MalformedReferenceScripts(xs) =>
      ConwayUtxowPredFailure.MalformedReferenceScripts(xs)
    case BabbageUtxowPredFailure.ScriptIntegrityHashMismatch(x, y) =>
      ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(x, y)

  /** `alonzoToConwayUtxowPredFailure` (`Conway/Rules/Utxow.hs:289-301`, cardano-ledger f649f975).
    */
  def alonzoToConwayUtxowPredFailure[E <: EraTag](
      failure: AlonzoUtxowPredFailure[E]
  )(using void: MirSigners[E] =:= Nothing): ConwayUtxowPredFailure[E] = failure match
    case AlonzoUtxowPredFailure.ShelleyInAlonzoUtxowPredFailure(f) =>
      shelleyToConwayUtxowPredFailure(f)
    case AlonzoUtxowPredFailure.MissingRedeemers(rs) => ConwayUtxowPredFailure.MissingRedeemers(rs)
    case AlonzoUtxowPredFailure.MissingRequiredDatums(mds, rds) =>
      ConwayUtxowPredFailure.MissingRequiredDatums(mds, rds)
    case AlonzoUtxowPredFailure.NotAllowedSupplementalDatums(uds, ads) =>
      ConwayUtxowPredFailure.NotAllowedSupplementalDatums(uds, ads)
    case AlonzoUtxowPredFailure.PPViewHashesDontMatch(m) =>
      ConwayUtxowPredFailure.PPViewHashesDontMatch(m)
    case AlonzoUtxowPredFailure.UnspendableUTxONoDatumHash(ins) =>
      ConwayUtxowPredFailure.UnspendableUTxONoDatumHash(ins)
    case AlonzoUtxowPredFailure.ExtraRedeemers(xs) => ConwayUtxowPredFailure.ExtraRedeemers(xs)
    case AlonzoUtxowPredFailure.ScriptIntegrityHashMismatch(x, y) =>
      ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(x, y)

  /** `shelleyToConwayUtxowPredFailure` (`Conway/Rules/Utxow.hs:303-316`, cardano-ledger f649f975):
    * the MIR quorum's failure, which the reference calls impossible in Conway (`error`), has no
    * value where the era has no MIR certificates ([[MirSigners]]), which `void` witnesses.
    */
  def shelleyToConwayUtxowPredFailure[E <: EraTag](failure: ShelleyUtxowPredFailure[E])(using
      void: MirSigners[E] =:= Nothing
  ): ConwayUtxowPredFailure[E] = failure match
    case ShelleyUtxowPredFailure.InvalidWitnessesUTXOW(xs) =>
      ConwayUtxowPredFailure.InvalidWitnessesUTXOW(xs)
    case ShelleyUtxowPredFailure.MissingVKeyWitnessesUTXOW(xs) =>
      ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(xs)
    case ShelleyUtxowPredFailure.MissingScriptWitnessesUTXOW(xs) =>
      ConwayUtxowPredFailure.MissingScriptWitnessesUTXOW(xs)
    case ShelleyUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(xs) =>
      ConwayUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(xs)
    case ShelleyUtxowPredFailure.UtxoFailure(x) => ConwayUtxowPredFailure.UtxoFailure(x)
    case ShelleyUtxowPredFailure.MIRInsufficientGenesisSigsUTXOW(xs) => void(xs)
    case ShelleyUtxowPredFailure.MissingTxBodyMetadataHash(x) =>
      ConwayUtxowPredFailure.MissingTxBodyMetadataHash(x)
    case ShelleyUtxowPredFailure.MissingTxMetadata(x) => ConwayUtxowPredFailure.MissingTxMetadata(x)
    case ShelleyUtxowPredFailure.ConflictingMetadataHash(mm) =>
      ConwayUtxowPredFailure.ConflictingMetadataHash(mm)
    case ShelleyUtxowPredFailure.InvalidMetadata() => ConwayUtxowPredFailure.InvalidMetadata()
    case ShelleyUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(xs) =>
      ConwayUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(xs)

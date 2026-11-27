// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  GenDelegPair,
  LedgerDefect,
  LedgerFailure,
  PlutusStats,
  TxChecks,
  PredicateFailure,
  ProtocolParams,
  TxIn,
  TxOut,
  TxEnv,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.state.*

// What the flat UTXOW, UTXO and UTXOS of the eras before Conway share (`ShelleyUtxo`): UTXO's
// environment, the evaluation of the Plutus scripts and UTXOW's checks, with the instant stake of
// Conway's UTxO state, which the genesis's UTxO set starts. The shared pure predicates live in
// `TxChecks`. Conway's UTXOW, UTXO and UTXOS run on the era types only (`era/rules`, `ConwayUtxo.
// conwayUtxoTransition`, `Conway/Rules/Utxo.hs:211-244`; `ConwayUtxos.utxosTransition`,
// `Conway/Rules/Utxos.hs:207-224`; `BabbageUtxow.babbageUtxowTransition`,
// `Babbage/Rules/Utxow.hs:308-391`; cardano-ledger f649f975).

/** `UtxoEnv` (`Shelley/Rules/Utxo.hs:107`, cardano-ledger f649f975): the block-level context (the
  * slot), the parameters in force, and the certificate state *before* the transaction's
  * certificates (the refunds of deregistrations are the deposits its accounts hold, which CERTS has
  * already removed from the state that follows, `Conway/Rules/Ledger.hs:429-440`). The certificate
  * state is the era's (`UtxoEnv era` holds `CertState era`): [[ShelleyCertState]] for the eras
  * before Conway, none where the rule reads none.
  */
final case class UtxoEnv[+C](ctx: TxEnv, pp: ProtocolParams, cert: C)

/** What the instant stake of the UTxO becomes as outputs are created and spent
  * (`addConwayInstantStake`, `deleteConwayInstantStake`, `Conway/State/Stake.hs:87-120` at
  * f649f975): the lovelace uses native Word64 arithmetic, and an entry that reaches zero is
  * removed. An absent credential receives the output amount for either operation, as native
  * Map.alter specifies. An output of an address with no stake credential (an enterprise address, a
  * pointer address, a Byron address) is not in it.
  */
object InstantStakeOps:
  private def adjust(
      stake: SortedMap[Credential[Staking], CompactCoin],
      out: TxOut,
      sign: Int
  ): Either[VocabError, SortedMap[Credential[Staking], CompactCoin]] =
    out.address.kind match
      case Address.Kind.Shelley(_, _, Some(credential), _) =>
        for
          c <- Convert.credential[Staking](credential)
          amount <- CompactCoin.fromBigInt(out.value.coin)
          next = stake.get(c).fold(amount) { old =>
            val word =
              if sign > 0 then Word64.wrappingAdd(old.word, amount.word)
              else Word64.wrappingSubtract(old.word, amount.word)
            CompactCoin.fromUnsigned(word.bits)
          }
        yield if next.word.isZero then stake.removed(c) else stake.updated(c, next)
      case _ => Right(stake)

  /** The instant stake with the outputs `added` in it and the outputs `deleted` out of it. */
  def update(
      is: ConwayInstantStake,
      added: Iterable[TxOut],
      deleted: Iterable[TxOut]
  ): Either[VocabError, ConwayInstantStake] =
    val withAdded = added.foldLeft[Either[VocabError, SortedMap[Credential[Staking], CompactCoin]]](
      Right(is.credentialStake)
    )((m, out) => m.flatMap(adjust(_, out, 1)))
    deleted
      .foldLeft(withAdded)((m, out) => m.flatMap(adjust(_, out, -1)))
      .map(ConwayInstantStake(_))

/** UTXOS of the eras before Conway (`ShelleyUtxo`, with Babbage's evaluation, cardano-ledger
  * `f649f975`): the evaluation of the Plutus scripts, whose verdict must equal the block's
  * `is_valid` flag (a transaction flagged invalid must have a script that fails, one flagged valid
  * none). The state is the node's counters of what was evaluated.
  */
object Utxos:
  /** `expectScriptsToPass` (`Babbage/Rules/Utxos.hs:132-155`, cardano-ledger f649f975) and
    * `babbageEvalScriptsTxInvalid` (`Babbage/Rules/Utxos.hs:194-220`): the contexts of the scripts
    * are collected, and the scripts are evaluated only if nothing failed so far in the judgment
    * (`whenFailureFree`). The scripts needed and their contexts are the era `E` 's
    * ([[TxChecks.phase2]]).
    */
  def scripts[F[_]: Monad, E <: EraTag](env: UtxoEnv[Any], ann: StAnnTx)(using
      era: ShelleyBasedEra[E],
      st: Stateful[F, PlutusStats],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val tx = ann.tx
    for
      failing <- judge.failing
      counters <- st.get
      // evaluate: `when2Phase`: a transaction applied again collects its scripts' contexts only
      next <- Refuse.recorded[F, PlutusStats](
        TxChecks.phase2[E](
          env.pp,
          counters,
          tx,
          env.ctx,
          ann.scripts,
          ann.spentByInput,
          ann.referencedByInput,
          evaluate = !failing && env.ctx.validation == TxEnv.Validation.All
        )
      )
      _ <- next.traverse_(st.set)
    yield ()

/** UTXOW's checks of the eras before Conway (`ShelleyUtxo`; Shelley's, Alonzo's and Babbage's
  * `Rules/Utxow.hs`, cardano-ledger `f649f975`): the scripts the transaction provides are the ones
  * it needs, its datums and redeemers match the Plutus scripts, the signatures, the auxiliary data,
  * the scripts are well formed and the script integrity hash. Every check records its failures and
  * the rule goes on.
  *
  * An input the UTxO set does not hold needs nothing here (the checks read `txIns ◁ utxo`); UTXO
  * reports it.
  */
object Utxow:
  private def utxow(f: UtxowFailure): PredicateFailure = PredicateFailure.utxow(f)

  /** The checks of UTXOW of the era `E`, whatever the protocol version, in the reference's order,
    * each a [[Test]] or the defect that leaves no verdict:
    *
    *   - Shelley to Mary (`transitionRulesUTXOW`, `Shelley/Rules/Utxow.hs:286-326`, cardano-ledger
    *     f649f975): the failing native scripts, the missing and the extraneous scripts, the
    *     signatures, the needed witnesses, the auxiliary data, the MIR quorum;
    *   - Alonzo (`alonzoStyleWitness`, `Alonzo/Rules/Utxow.hs:309-374`): the same scripts, the
    *     datums and the redeemers, the signatures, the needed witnesses, the MIR quorum, the
    *     auxiliary data, the script integrity hash;
    *   - Babbage and Conway (`babbageUtxowTransition`, `Babbage/Rules/Utxow.hs:321-391`, after the
    *     MIR quorum in Babbage, `babbageUtxowMirTransition`): the failing needed native scripts,
    *     the extraneous and the missing scripts beside the reference scripts, the datums, the
    *     redeemers, the signatures, the needed witnesses, the auxiliary data, the well-formed
    *     scripts and the script integrity hash.
    *
    * @param genDelegs
    *   the genesis delegations of the certificate state before the transaction (the key hashes of
    *   the genesis delegates the update proposals and genesis delegation certificates need
    *   witnesses of; none from Conway, which has no such proposal)
    * @param mir
    *   the MIR quorum of an era before Conway (`validateMIRInsufficientGenesisSigs`)
    */
  def checks[E <: EraTag](
      env: UtxoEnv[Any],
      genDelegs: Map[Bytes, GenDelegPair],
      ann: StAnnTx,
      mir: Test[PredicateFailure]
  )(using era: ShelleyBasedEra[E]): Vector[Either[LedgerDefect, Test[PredicateFailure]]] =
    val tx = ann.tx
    val pp = env.pp
    val scripts = ann.scripts
    // the purposes that need a script, in the era's order and with its redeemer indices
    val purposes = ann.needed[E]
    val needed = purposes.map(_._2).toSet
    val received = tx.witnesses.scripts.map(_.hash).toSet
    val vkeyHashes = tx.witnesses.vkeys.map(w => env.ctx.verdicts.keyHash(w._1))
    val provided = TxChecks.providedKeyHashes(tx, vkeyHashes)
    // A native script sees the vkey witnesses only; a bootstrap root is no signer of one.
    val signers = vkeyHashes.toSet
    // The outputs the collateral inputs hold, whose key witnesses the transaction needs whether or
    // not it has redeemers: `spendableInputsTxBodyF` is the inputs and the collateral inputs from
    // Alonzo on (cardano-ledger f649f975, `Alonzo/TxBody.hs` `allInputsTxBodyF` and
    // `spendableInputsTxBodyF`; `Babbage/TxBody.hs` `babbageSpendableInputsTxBodyF`; Conway uses
    // the Babbage getter), and `getShelleyWitsVKeyNeededNoGov` (`Shelley/UTxO.hs`) takes the
    // payment key of each. An earlier era has no collateral field, so its set is empty.
    val collateral = TxChecks.Collateral(tx.body.collateral.flatMap(ann.utxo.get), BigInt(0))
    def test(t: Test[PredicateFailure]) = Right(t)
    // the needed native scripts that do not hold, of every script provided before Babbage
    def failedNative(only: Option[Set[Bytes]]): Either[LedgerDefect, Test[PredicateFailure]] =
      TxChecks.failedNativeScripts(tx, scripts, only, signers)
    // Under `runTestOnSignal` in every era, and so skipped when a transaction is applied again
    // ([[TxEnv.static]]): the signatures, the auxiliary data, the failing native scripts before
    // Babbage (`Shelley/Rules/Utxow.hs:301-316` (cardano-ledger f649f975),
    // `Alonzo/Rules/Utxow.hs:326-364`) and the well-formed scripts from Babbage
    // (`Babbage/Rules/Utxow.hs:366-379`).
    val signatures = env.ctx.staticOrDefect(TxChecks.verifiedWits(tx, env.ctx.verdicts))
    val witnesses =
      test(TxChecks.neededWitnesses(genDelegs, tx, ann.spent, collateral, provided))
    val metadata = env.ctx.staticOrDefect(TxChecks.auxiliaryChecks[E](pp, tx, env.ctx))
    if !era.atLeast(ShelleyBasedEra.SBabbage) then
      // `validateMissingScripts` (`Shelley/Rules/Utxow.hs:375-388`, cardano-ledger f649f975): the
      // scripts provided are the witnesses' before Babbage
      val missingScripts = test(
        Test.all(
          Test.failureUnless(
            needed.subsetOf(received),
            utxow(UtxowFailure.MissingScriptWitnesses(needed -- received))
          ),
          Test.failureUnless(
            received.subsetOf(needed),
            utxow(UtxowFailure.ExtraneousScriptWitnesses(received -- needed))
          )
        )
      )
      val failing = env.ctx.staticOrDefect(failedNative(None))
      TxChecks.plutusFieldsDefect[E](tx).map(Left(_)).toVector ++ (
        if !era.atLeast(ShelleyBasedEra.SAlonzo) then
          Vector(failing, missingScripts, signatures, witnesses, metadata, test(mir))
        else
          Vector(
            failing,
            missingScripts,
            test(
              TxChecks.datumChecks[E](tx, ann.spentByInput, ann.referenced, scripts)
            ),
            test(TxChecks.redeemerChecks(tx, scripts, purposes)),
            signatures,
            witnesses,
            test(mir),
            metadata,
            test(TxChecks.integrityCheck[E](pp, tx, ann.spentByInput, scripts))
          )
      )
    else
      // `babbageMissingScripts` (`Babbage/Rules/Utxow.hs:192-207`, cardano-ledger f649f975): the
      // needed scripts less the reference scripts of the inputs and reference inputs are the
      // witnesses' scripts
      val references = (ann.spent ++ ann.referenced).flatMap(_.scriptRef).map(_.hash).toSet
      val neededNonRef = needed -- references
      Vector(
        test(mir),
        failedNative(Some(needed)),
        test(
          Test.all(
            Test.failureUnless(
              received.subsetOf(neededNonRef),
              utxow(UtxowFailure.ExtraneousScriptWitnesses(received -- neededNonRef))
            ),
            Test.failureUnless(
              neededNonRef.subsetOf(received),
              utxow(UtxowFailure.MissingScriptWitnesses(neededNonRef -- received))
            )
          )
        ),
        test(TxChecks.datumChecks[E](tx, ann.spentByInput, ann.referenced, scripts)),
        test(TxChecks.redeemerChecks(tx, scripts, purposes)),
        signatures,
        witnesses,
        metadata,
        env.ctx.staticOrDefect(TxChecks.scriptsWellFormed[E](pp, tx, env.ctx)),
        test(TxChecks.integrityCheck[E](pp, tx, ann.spentByInput, scripts))
      )

  /** Records the checks in order: every failure of each, or the first defect raised. A check ends
    * in a defect or a test, never a raised refusal.
    */
  def record[F[_]: Monad](checks: Vector[Either[LedgerDefect, Test[PredicateFailure]]])(using
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    checks.traverse_(check =>
      Sts
        .lift[F, LedgerFailure, Test[PredicateFailure]](check)
        .flatMap(test => Sts.runTest[F, PredicateFailure](test))
    )

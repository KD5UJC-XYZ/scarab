// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.GenDelegPair
import xyz.kd5ujc.scarab.ledger.rules.Test

/** The checks of the transaction rules before Conway and the checks Conway shares with them (UTXO,
  * UTXOW, UTXOS): the pure predicates over a transaction and what it reads of the state, which the
  * era-indexed rules ([[xyz.kd5ujc.scarab.ledger.rules.Utxo]], `ShelleyUtxo`) call. Era behaviour
  * is selected by the protocol major version of the parameters in force: Allegra 3, Mary 4, Alonzo
  * 5, Babbage 7. (The flat state's rules that used to apply a transaction beside these were removed
  * at J2.9.)
  */
object TxChecks:
  import TxEnv.Result
  type Env = TxEnv

  /** A redeemer pointer: tag 0 spend, 1 mint, 2 certificate, 3 reward, then index. */
  private[ledger] type Pointer = (Int, BigInt)

  /** The block's `is_valid` flag must equal the outcome of evaluating every Plutus script
    * (`expectScriptsToPass`, `babbageEvalScriptsTxInvalid`, `Babbage/Rules/Utxos.hs:132-220`): the
    * contexts are collected (`CollectErrors`), then the scripts evaluated; a transaction flagged
    * invalid with no script to fail passes unexpectedly. Reads of the state: the parameters and the
    * counters it adds to.
    *
    * @param spent
    *   the outputs of the inputs the UTxO set holds, by input
    * @param references
    *   the outputs of the reference inputs the UTxO set holds, by input
    * @param evaluate
    *   whether the scripts are evaluated: they are not once the judgment has a failure
    *   (`whenFailureFree`, `Alonzo/Rules/Utxos.hs:180-182`), and then only their contexts are
    *   collected (`CollectErrors`)
    */
  private[ledger] def phase2(
      pp: ProtocolParams,
      plutus: PlutusStats,
      tx: Tx,
      env: Env,
      scripts: Map[Bytes, Script],
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      plutusPurposes: Set[Pointer],
      evaluate: Boolean
  ): Result[PlutusStats] =
    val body = tx.body
    if pp.protocolMajor < 5 then Right(plutus)
    else
      val stats =
        if plutusPurposes.isEmpty && body.scriptDataHash.isEmpty then plutus
        else plutus.copy(integrityChecked = plutus.integrityChecked + 1)
      if plutusPurposes.isEmpty then
        // no script: the evaluation of none passes (`Passes []`)
        if tx.isValid || !evaluate then Right(stats)
        else checkedPhase2(stats, tx, Right(Phase2.Outcome.Passed(Phase2.Report(Vector.empty))))
      else
        val posix = env.clock.toRight(TranslationError.NoEraHistory).map(c => c.posix)
        env.preparation match
          case Some(recording) =>
            val requests =
              Phase2.collect(tx, pp, scripts, spent, references, slot => posix.flatMap(_(slot)))
            recording.append(
              Phase2.job(env.txIndex, tx, pp, env.slot, spent, references, env.clock, requests)
            )
            requests.fold(errors => checkedPhase2(stats, tx, Left(errors)), _ => Right(stats))
          case None if !evaluate =>
            Phase2
              .collect(tx, pp, scripts, spent, references, slot => posix.flatMap(_(slot)))
              .fold(errors => checkedPhase2(stats, tx, Left(errors)), _ => Right(stats))
          case None =>
            val outcome = Phase2.evaluate(
              tx,
              pp,
              scripts,
              spent,
              references,
              slot => posix.flatMap(_(slot)),
              env.evaluator
            )
            env.observer.foreach(
              _(Phase2.Observation(tx, pp, env.slot, spent, references, env.clock, outcome))
            )
            checkedPhase2(stats, tx, outcome)

  /** The ordered phase-2 verdict check, also used when a prepared block lands its worker values. */
  private[ledger] def checkedPhase2(
      stats: PlutusStats,
      tx: Tx,
      outcome: Phase2.Collected[Phase2.Outcome]
  ): Result[PlutusStats] =
    val id = tx.body.id
    outcome.left
      .map(e =>
        LedgerFailure.Rejected(
          id,
          PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(e)))
        )
      )
      .flatMap {
        case Phase2.Outcome.Unavailable(_, redeemer, reason) =>
          Left(LedgerFailure.Unavailable(id, ScriptLocation.Phase2(redeemer), reason))
        case Phase2.Outcome.Passed(report) =>
          if tx.isValid then
            Right(
              stats.copy(succeeded = stats.succeeded + 1, scripts = stats.scripts + report.scripts)
            )
          else
            Left(
              LedgerFailure.Rejected(
                id,
                PredicateFailure.utxo(
                  UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, None))
                )
              )
            )
        case Phase2.Outcome.Failed(report, failures) =>
          if !tx.isValid then
            Right(stats.copy(failed = stats.failed + 1, scripts = stats.scripts + report.scripts))
          else
            Left(
              LedgerFailure.Rejected(
                id,
                PredicateFailure.utxo(
                  UtxoFailure.Utxos(
                    UtxosFailure.ValidationTagMismatch(
                      true,
                      Some(
                        failures.toVector
                          .map(f => PlutusFailure(f.reason, Bytes.empty, Some(f.request)))
                      )
                    )
                  )
                )
              )
            )
      }

  // ---------------------------------------------------------------------------------------------
  // UTXO

  /** The minimum ada of an output. Shelley and Allegra: the flat `minUTxOValue`
    * (`getMinCoinTxOut`); Mary: `scaledMinDeposit`, which is `minUTxOValue` for an ada-only value
    * and otherwise `max(minUTxOValue, (minUTxOValue / 27) * (27 + size v))` (`coinSize` is 0 there,
    * unlike Alonzo where the ada-only entry counts 2 words); Alonzo:
    * `coinsPerUTxOWord * (27 + size v + datum hash)`; Babbage onward: `coinsPerUTxOByte` times the
    * serialised size plus 160.
    */
  private[ledger] def minUTxO(pp: ProtocolParams, out: TxOut): BigInt =
    if pp.protocolMajor >= 7 then (160 + out.ledgerSize) * pp.coinsPerUTxOByte
    else if pp.protocolMajor >= 5 then pp.coinsPerUTxOWord * entrySize(out)
    else if pp.protocolMajor >= 4 then
      if out.value.assets.isEmpty then pp.minUTxOValue
      else
        val perWord = pp.minUTxOValue / UtxoEntrySizeWithoutVal
        pp.minUTxOValue.max(perWord * (UtxoEntrySizeWithoutVal + valueWords(out.value)))
    else pp.minUTxOValue

  /** `utxoEntrySizeWithoutVal`: 6 + 14 (output) + 7 (input) words. */
  private val UtxoEntrySizeWithoutVal = BigInt(27)

  /** `size` of a multi-asset `MaryValue` in 8-byte words: `repOverhead` (4 + 1 ada word + 1 for the
    * asset count) plus the packed representation, which stores 12 bytes per (policy, name) triple,
    * one 28-byte policy id per distinct policy and the bytes of every distinct asset name. An
    * ada-only value counts 2 words (the Alonzo entry size; Mary bypasses it).
    */
  private[ledger] def valueWords(value: Value): BigInt =
    if value.assets.isEmpty then BigInt(2)
    else
      val triples = value.assets.valuesIterator.map(_.size).sum
      val names = value.assets.valuesIterator.flatMap(_.keysIterator).toSet
      val bytes = triples * 12 + names.iterator.map(_.size).sum + value.assets.size * 28
      BigInt(6 + (bytes + 7) / 8)

  /** `utxoEntrySizeWithoutVal` (27 words) plus the value and datum hash sizes. */
  private def entrySize(out: TxOut): BigInt =
    val datum = out.datum match
      case Datum.Hash(_) => 10
      case _             => 0
    UtxoEntrySizeWithoutVal + valueWords(out.value) + datum

  /** The largest serialised value an output may carry: 4000 bytes in Allegra and Mary (hard-coded
    * in `validateOutputTooBigUTxO`), `maxValueSize` from Alonzo, no limit in Shelley.
    */
  private[ledger] def maxOutputValueSize(pp: ProtocolParams): Option[BigInt] =
    if pp.protocolMajor >= 5 then Some(pp.maxValueSize)
    else if pp.protocolMajor >= 3 then Some(BigInt(4000))
    else None

  /** The outputs of `outs` that hold a bootstrap (Byron) address, with the address. A Byron-kind
    * address has been decoded in full when its output was ([[Address.decode]]), so the decode below
    * succeeds for every output of a decoded transaction.
    */
  private[ledger] def bootstrapOutputs(outs: Vector[TxOut]): Vector[(TxOut, byron.ByronAddress)] =
    outs.flatMap(out =>
      out.address.kind match
        case Address.Kind.Byron(_) =>
          byron.ByronAddress.decode(out.address.raw).toOption.map(out -> _)
        case _ => None
    )

  /** The reference stores collateral as `Set TxIn` (`Alonzo/Rules/Utxo.hs:288-324,470-481` at
    * f649f975). Deduplicate decoded inputs, preserving the transaction's original signed bytes.
    */
  private[ledger] def collateralInputs(body: TxBody): Vector[TxIn] =
    body.collateral.toSet.toVector.sorted

  /** The collateral a transaction with redeemers (Alonzo on) pays if it fails phase 2: the outputs
    * of its collateral inputs the UTxO set holds, and their ada less the collateral return
    * (`collAdaBalance`, `Babbage/Collateral.hs:30-41`). None for a transaction without redeemers.
    */
  private[ledger] def collateral(utxo: Utxo, pp: ProtocolParams, tx: Tx): Collateral =
    if pp.protocolMajor < 5 || tx.witnesses.redeemers.isEmpty then Collateral.none
    else
      val outs = collateralInputs(tx.body).flatMap(utxo.get)
      val total = outs.foldLeft(Value.zero)(_ + _.value)
      Collateral(outs, tx.body.collateralReturn.fold(total)(r => total - r.value).coin)

  /** Collateral outputs and the ada they pay as fees when the transaction fails phase 2. */
  private[ledger] final case class Collateral(outputs: Vector[TxOut], fee: BigInt)
  private[ledger] object Collateral:
    val none: Collateral = Collateral(Vector.empty, 0)

  // ---------------------------------------------------------------------------------------------
  // UTXOW

  /** Scripts the transaction can use: its witnesses and the reference scripts of spent and
    * reference outputs (Babbage).
    */
  private[ledger] def providedScripts(wits: Witnesses, outs: Vector[TxOut]): Map[Bytes, Script] =
    outs.flatMap(_.scriptRef).map(s => s.hash -> s).toMap ++ wits.scriptsByHash

  /** Script hashes the transaction must provide, with the redeemer pointers that use each.
    *
    * @param spent
    *   the outputs of the inputs the UTxO set holds, by input: an input it does not hold needs no
    *   script (`getScriptsNeeded` over the UTxO, `Alonzo/UTxO.hs`)
    */
  private[ledger] def scriptsNeeded(
      body: TxBody,
      spent: Map[TxIn, TxOut]
  ): Map[Bytes, Set[Pointer]] =
    Purpose
      .all(body, spent)
      .flatMap(p => p.scriptHash.map(h => h -> ((p.tag, BigInt(p.index)))))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  /** Datum hashes a witness set may carry without a spent input asking for them: those of the
    * outputs (the collateral return too, from Babbage) and of the reference inputs (Babbage
    * onward). `getAlonzoSupplementalDataHashes` reads the outputs only
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs`); `getBabbageSupplementalDataHashes`
    * adds `allSizedOutputsTxBodyF` (the collateral return) and the resolved reference inputs
    * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/UTxO.hs`), and Conway uses it unchanged
    * (`eras/conway/impl/src/Cardano/Ledger/Conway/UTxO.hs`, `getSupplementalDataHashes`). An inline
    * datum has no hash (`dataHashTxOutL`).
    */
  private[ledger] def supplementalDatumHashes(
      pp: ProtocolParams,
      body: TxBody,
      referenced: Vector[TxOut]
  ): Set[Bytes] =
    val outs =
      if pp.protocolMajor < 7 then body.outputs
      else body.outputs ++ body.collateralReturn ++ referenced
    outs.map(_.datum).collect { case Datum.Hash(h) => h }.toSet

  private def utxow(f: UtxowFailure): PredicateFailure = PredicateFailure.utxow(f)

  /** `missingRequiredDatums` (`Alonzo/Rules/Utxow.hs:210-234`), reached from every UTXOW from
    * Alonzo: a spent Plutus output without a usable datum hash (`UnspendableUTxONoDatumHash`), a
    * required datum the witnesses lack (`MissingRequiredDatums`), and a witness datum that is
    * neither required nor allowed as supplemental (`NotAllowedSupplementalDatums`: the unallowed
    * hashes and the acceptable ones).
    *
    * @param spent
    *   the outputs of the inputs the UTxO set holds, by input
    */
  private[ledger] def datumChecks(
      pp: ProtocolParams,
      tx: Tx,
      spent: Map[TxIn, TxOut],
      referenced: Vector[TxOut],
      scripts: Map[Bytes, Script]
  ): Test[PredicateFailure] =
    val pv = pp.protocolMajor
    val body = tx.body
    val wits = tx.witnesses
    // Spent outputs locked with a Plutus script
    val plutusLocked =
      body.inputs.flatMap(in => spent.get(in).map(in -> _)).flatMap { case (in, out) =>
        out.address.kind match
          case Address.Kind.Shelley(_, Credential.Script(h), _, _)
              if scripts.get(h).exists(_.kind.isPlutus) =>
            Some((in, out.datum, scripts(h).kind))
          case _ => None
      }
    // Outputs locked by a Plutus script without a usable datum hash
    val unspendable = plutusLocked.flatMap { case (in, datum, kind) =>
      datum match
        case Datum.Hash(_)   => None
        case Datum.Inline(_) => Option.when(pv < 7)(in)
        // Plutus V3 scripts need no datum (CIP-69)
        case Datum.NoDatum => Option.when(kind != ScriptKind.PlutusV3)(in)
    }
    // Datum hashes the spent outputs ask for
    val requiredHashes = plutusLocked.collect { case (_, Datum.Hash(h), _) => h }.toSet
    val supplemental = wits.datumHashes -- requiredHashes
    val allowed = supplementalDatumHashes(pp, body, referenced)
    Test.all(
      Test.failureUnless(
        unspendable.isEmpty,
        utxow(UtxowFailure.UnspendableUTxONoDatumHash(unspendable.toSet))
      ),
      Test.failureUnless(
        requiredHashes.subsetOf(wits.datumHashes),
        utxow(
          UtxowFailure.MissingRequiredDatums(requiredHashes -- wits.datumHashes, wits.datumHashes)
        )
      ),
      Test.failureUnless(
        supplemental.subsetOf(allowed),
        utxow(
          UtxowFailure.NotAllowedSupplementalDatums(
            supplemental -- allowed,
            supplemental.intersect(allowed)
          )
        )
      )
    )

  /** A transaction of an era before Alonzo holds no Plutus fields: what its decoder would not have
    * admitted is a defect.
    */
  private[ledger] def plutusFieldsDefect(pp: ProtocolParams, tx: Tx): Option[LedgerDefect] =
    val wits = tx.witnesses
    Option.when(
      pp.protocolMajor < 5 &&
        (wits.redeemers.nonEmpty || wits.datums.nonEmpty || tx.body.scriptDataHash.nonEmpty)
    )(LedgerFailure.Undecodable(tx.body.id, TxDefect.PlutusFieldsBeforeAlonzo))

  /** `hasExactSetOfRedeemers` (`Alonzo/Rules/Utxow.hs:239-262`): the redeemers no Plutus purpose
    * needs (`ExtraRedeemers`), then the Plutus purposes with no redeemer (`MissingRedeemers`).
    *
    * @param spent
    *   the outputs of the inputs the UTxO set holds, by input
    */
  private[ledger] def redeemerChecks(
      tx: Tx,
      spent: Map[TxIn, TxOut],
      scripts: Map[Bytes, Script],
      plutusPurposes: Set[Pointer]
  ): Test[PredicateFailure] =
    val body = tx.body
    val provided = tx.witnesses.redeemers.map(r => (r.tag, r.index)).toSet
    // Plutus purposes without a redeemer, with the script each one needs
    val unredeemed = Purpose
      .all(body, spent)
      .flatMap(p =>
        p.scriptHash
          .filter(h => scripts.get(h).exists(_.kind.isPlutus))
          .filter(_ => !provided.contains((p.tag, BigInt(p.index))))
          .map(p -> _)
      )
    Test.all(
      Test.failureUnless(
        provided.subsetOf(plutusPurposes),
        utxow(UtxowFailure.ExtraRedeemers((provided -- plutusPurposes).toVector.sorted))
      ),
      Test.failureUnless(unredeemed.isEmpty, utxow(UtxowFailure.MissingRedeemers(unredeemed)))
    )

  /** `checkScriptIntegrityHash` (`Alonzo/Rules/Utxow.hs:266-283`): the script integrity hash of the
    * body is the one of the redeemers, the datums and the language views of the Plutus scripts the
    * transaction needs (`mkScriptIntegrity`, none when it has none of them).
    *
    * @param spent
    *   the outputs of the inputs the UTxO set holds, by input
    */
  private[ledger] def integrityCheck(
      pp: ProtocolParams,
      tx: Tx,
      spent: Map[TxIn, TxOut],
      scripts: Map[Bytes, Script]
  ): Test[PredicateFailure] =
    val languages = scriptsNeeded(tx.body, spent).keys.flatMap(scripts.get).map(_.kind).toSet
    Test.failureUnless(
      ScriptIntegrity.hash(tx, pp, languages) == tx.body.scriptDataHash,
      utxow(ScriptIntegrity.mismatch(tx, pp, languages))
    )

  /** `validateScriptsWellFormed` (`Babbage/Rules/Utxow.hs:254-276`), from Babbage: every Plutus
    * script in the witness set (`MalformedScriptWitnesses`) and in the outputs, the collateral
    * return included (`MalformedReferenceScripts`), must deserialise for its language and the
    * protocol version in force, which rejects a builtin the pair does not allow. Both are reported.
    * Alonzo has no such check; there an undecodable script that a transaction needs fails phase 2.
    * A decoder that cannot decide is a defect.
    */
  private[ledger] def scriptsWellFormed(
      pp: ProtocolParams,
      tx: Tx,
      env: Env
  ): Either[LedgerDefect, Test[PredicateFailure]] =
    val pv = pp.protocolMajor.toInt
    def malformed(
        failure: Set[Bytes] => UtxowFailure,
        scripts: Vector[Script]
    ): Either[LedgerDefect, Test[PredicateFailure]] =
      scripts
        .filter(_.kind.isPlutus)
        .traverse { script =>
          (Phase2.languageOf(script).toOption, script.plutus) match
            case (Some(language), Some(code)) =>
              env.decoder.validate(language, pv, code) match
                case Right(()) => Right(Option.empty[Bytes])
                case Left(xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure.Malformed(_)) =>
                  Right(Some(script.hash))
                case Left(xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure.Unavailable(why)) =>
                  Left(
                    LedgerFailure.Unavailable(
                      tx.body.id,
                      ScriptLocation.Phase1(script.hash),
                      xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported(why)
                    )
                  )
            case _ => Right(Option.empty[Bytes])
        }
        .map { hashes =>
          val bad = hashes.flatten.toSet
          Test.failureUnless(bad.isEmpty, utxow(failure(bad)))
        }
    if pv < 7 then Right(Test.pass)
    else
      for
        witnesses <- malformed(UtxowFailure.MalformedScriptWitnesses(_), tx.witnesses.scripts)
        references <- malformed(
          UtxowFailure.MalformedReferenceScripts(_),
          (tx.body.outputs ++ tx.body.collateralReturn.toVector).flatMap(_.referenceScript)
        )
      yield Test.all(witnesses, references)

  /** `validateMetadata` (`Shelley/Rules/Utxow.hs:427-443`, `Alonzo/TxAuxData.hs:369-373` at
    * f649f975): the body's hash of the auxiliary data and the data go together, and with both the
    * hash must match (`ConflictingMetadataHash`) and the auxiliary scripts be well formed
    * (`InvalidMetadata`), both reported. Auxiliary data that does not decode is a defect, and so is
    * a decoder that cannot decide.
    */
  private[ledger] def auxiliaryChecks(
      pp: ProtocolParams,
      tx: Tx,
      env: Env
  ): Either[LedgerDefect, Test[PredicateFailure]] =
    val id = tx.body.id
    for
      decoded <- tx.auxiliary
        .traverse(bytes =>
          // The bytes were read at the era's decoder version when the transaction was decoded
          // (`Tx.fromBlock`): the rule reads them again for their scripts, not their form.
          AuxiliaryScripts
            .read(bytes, pp.protocolMajor)(using ByteStrings.Chunked)
            .leftMap(error => LedgerFailure.Undecodable(id, TxDefect.AuxiliaryData(error)))
        )
      valid <- decoded.fold(Right(true): Either[LedgerDefect, Boolean]) { scripts =>
        scripts
          .traverse { script =>
            env.decoder.validate(script.language, pp.protocolMajor.toInt, script.code) match
              case Right(())                                                       => Right(true)
              case Left(xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure.Malformed(_)) => Right(false)
              case Left(xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure.Unavailable(why)) =>
                Left(
                  LedgerFailure.Unavailable(
                    id,
                    ScriptLocation.Phase1(script.hash),
                    xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported(why)
                  )
                )
          }
          .map(_.forall(identity))
      }
    yield (tx.body.auxDataHash, tx.auxiliary) match
      case (None, None)    => Test.pass
      case (Some(h), None) => Test.failureUnless(false, utxow(UtxowFailure.MissingTxMetadata(h)))
      case (None, Some(aux)) =>
        Test.failureUnless(
          false,
          utxow(UtxowFailure.MissingTxBodyMetadataHash(Blake2b.hash256.hash(aux)))
        )
      case (Some(h), Some(aux)) =>
        val computed = Blake2b.hash256.hash(aux)
        Test.all(
          Test.failureUnless(
            computed == h,
            utxow(UtxowFailure.ConflictingMetadataHash(Mismatch(h, computed)))
          ),
          Test.failureUnless(valid, utxow(UtxowFailure.InvalidMetadata))
        )

  /** The hashes a transaction's witnesses provide (`witsFromTxWitnesses`): the hashes of the vkey
    * witnesses and the address roots of the bootstrap witnesses form one set, and a Byron input's
    * `bootstrapKeyHash` is one of the needed hashes. A vkey witness whose key hashes to the root
    * therefore also spends the Byron output.
    */
  private[ledger] def providedKeyHashes(tx: Tx, vkeyHashes: Vector[Bytes]): Set[Bytes] =
    vkeyHashes.toSet ++ tx.witnesses.bootstrap.map(_.addressRoot)

  /** `validateVerifiedWits` (`Shelley/Rules/Utxow.hs:380-397`): the vkey and bootstrap witnesses
    * whose signature of the body does not verify.
    */
  private[ledger] def verifiedWits(
      tx: Tx,
      verdicts: Verdicts
  ): Either[LedgerDefect, Test[PredicateFailure]] =
    val id = tx.body.id
    val wits = tx.witnesses
    // A verifier that fails is no verdict on the transaction (`LedgerFailure.Unverifiable`), never
    // an invalid witness: the reference's `verifyWitVKey` is a Boolean, and a failure of the library
    // is an exception (see `ProviderDefect`).
    (wits.vkeys ++ wits.bootstrap.map(w => w.vkey -> w.signature))
      .traverse((vkey, signature) =>
        verdicts.witness(vkey, signature, id).map(valid => Option.unless(valid)(vkey))
      )
      .bimap(
        LedgerFailure.Unverifiable(id, _),
        invalid =>
          val keys = invalid.flatten
          Test.failureUnless(keys.isEmpty, utxow(UtxowFailure.InvalidWitnesses(keys)))
      )

  /** `validateNeededWitnesses` (`Shelley/Rules/Utxow.hs:399-412`): the key hashes that must sign
    * (`witsVKeyNeeded`) and that no witness provides. Reads of the state: the genesis delegations
    * (empty from Conway, which has no update proposals).
    */
  private[ledger] def neededWitnesses(
      genDelegs: Map[Bytes, GenDelegPair],
      tx: Tx,
      spent: Vector[TxOut],
      collateral: Collateral,
      provided: Set[Bytes]
  ): Test[PredicateFailure] =
    val body = tx.body
    val locked = spent ++ collateral.outputs
    val inputKeys = locked.flatMap(_.address.kind match
      case Address.Kind.Shelley(_, Credential.Key(h), _, _) => Some(h)
      case _                                                => None)
    val bootRoots = locked.flatMap(_.address.kind match
      case Address.Kind.Byron(root) => Some(root)
      case _                        => None)
    val withdrawalKeys = body.withdrawals
      .collect { case (a, _) =>
        a.credential
      }
      .collect { case Credential.Key(h) => h }
    val certKeys = body.certificates.flatMap {
      case Certificate.Unregister(Credential.Key(h), _)     => Vector(h)
      case Certificate.Delegate(Credential.Key(h), _, _, _) => Vector(h)
      case Certificate.RegisterPool(p)                      => p.operator +: p.owners.toVector
      case Certificate.RetirePool(p, _)                     => Vector(p)
      case Certificate.GenesisDelegate(g, _, _)             => Vector(g)
      // Conway: the deposit-carrying registration and the governance certificates.
      case Certificate.Register(Credential.Key(h), Some(_))     => Vector(h)
      case Certificate.RegisterDRep(Credential.Key(h), _, _)    => Vector(h)
      case Certificate.UnregisterDRep(Credential.Key(h), _)     => Vector(h)
      case Certificate.UpdateDRep(Credential.Key(h), _)         => Vector(h)
      case Certificate.CommitteeAuthorize(Credential.Key(h), _) => Vector(h)
      case Certificate.CommitteeResign(Credential.Key(h), _)    => Vector(h)
      case _                                                    => Vector.empty
    }
    val voterKeys = body.votes.map(_._1).collect {
      case Voter.CommitteeHot(Credential.Key(h)) => h
      case Voter.DRepVoter(Credential.Key(h))    => h
      case Voter.Pool(h)                         => h
    }
    val updateKeys = body.update.toVector.flatMap(
      _.proposals.keys.toVector.flatMap(g => genDelegs.get(g).map(_.delegate))
    )
    val required =
      (inputKeys ++ bootRoots ++ withdrawalKeys ++ certKeys ++ voterKeys ++ updateKeys ++ body.requiredSigners).toSet
    val missing = required -- provided
    Test.failureUnless(missing.isEmpty, utxow(UtxowFailure.MissingVKeyWitnesses(missing)))

  /** The native scripts that do not hold (`validateFailedNativeScripts`,
    * `Shelley/Rules/Utxow.hs:365-373`: every native script provided; from Babbage
    * `validateFailedBabbageScripts`, `Babbage/Rules/Utxow.hs:216-233`: only those needed).
    *
    * @param signers
    *   the key hashes of the vkey witnesses only: `validateTimelock` (`Allegra/Tx.hs`) and
    *   `validateMultiSig` (`Shelley/Scripts.hs`) evaluate over `addrTxWitsL` (cardano-ledger
    *   f649f975), so a bootstrap witness's address root is no signer of a script.
    */
  private[ledger] def failedNativeScripts(
      tx: Tx,
      scripts: Map[Bytes, Script],
      needed: Option[Set[Bytes]],
      signers: Set[Bytes]
  ): Test[PredicateFailure] =
    val body = tx.body
    val failing = scripts.collect {
      case (h, script)
          if needed.forall(_.contains(h)) &&
            script.native.exists(s => !s.eval(signers, body.validityStart, body.ttl)) =>
        h
    }.toSet
    Test.failureUnless(failing.isEmpty, utxow(UtxowFailure.ScriptWitnessNotValidating(failing)))

/** Reference scripts: the limits and the fee of Conway's reference-script pricing. */
object RefScripts:
  val MaxRefScriptSizePerTx: Int = 200 * 1024

  /** Maximum total size of the reference scripts of one block. */
  val MaxRefScriptSizePerBlock: Int = 1024 * 1024

  private val RefScriptStride = 25600
  private val RefScriptMultiplier = Rational(6, 5)

  /** `tierRefScriptFee`: the price per byte starts at `base` and grows by 1.2 for every 25 KiB. */
  def tierRefScriptFee(base: Rational, size: Int): BigInt =
    @annotation.tailrec
    def go(acc: Rational, price: Rational, n: Int): BigInt =
      if n < RefScriptStride then (acc + Rational(n) * price).floor
      else
        go(
          acc + Rational(RefScriptStride) * price,
          RefScriptMultiplier * price,
          n - RefScriptStride
        )
    go(Rational.zero, base, size)

  /** Total size of the reference scripts of the distinct inputs and reference inputs. */
  def refScriptsSize(utxo: Utxo, body: TxBody): Int =
    (body.inputs.toSet ++ body.referenceInputs.toSet).iterator
      .flatMap(utxo.get)
      .map(_.referenceScriptSize)
      .sum

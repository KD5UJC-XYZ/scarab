// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.GenDelegPair

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

  /** The script data hash must equal the recomputed hash, and the block's `is_valid` flag must
    * equal the outcome of evaluating every Plutus script. Reads of the state: the parameters and
    * the counters it adds to.
    */
  private[ledger] def phase2(
      pp: ProtocolParams,
      plutus: PlutusStats,
      tx: Tx,
      env: Env,
      scripts: Map[Bytes, Script],
      spent: Vector[TxOut],
      refOuts: Vector[TxOut],
      plutusPurposes: Set[Pointer]
  ): Result[PlutusStats] =
    val body = tx.body
    val id = body.id
    if pp.protocolMajor < 5 || (plutusPurposes.isEmpty && body.scriptDataHash.isEmpty) then
      Right(plutus)
    else
      val needed = scriptsNeeded(body, spent)
      val languages = needed.keys.flatMap(scripts.get).map(_.kind).toSet
      val recomputed = ScriptIntegrity.hash(tx, pp, languages)
      val stats = plutus.copy(integrityChecked = plutus.integrityChecked + 1)
      if recomputed != body.scriptDataHash then
        Left(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.utxow(
              UtxowFailure.PPViewHashesDontMatch(Mismatch(body.scriptDataHash, recomputed))
            )
          )
        )
      else if plutusPurposes.isEmpty then Right(stats)
      else
        val inputs = body.inputs.zip(spent).toMap
        val references = body.referenceInputs.zip(refOuts).toMap
        val posix = env.clock.toRight(TranslationError.NoEraHistory)
        env.preparation match
          case Some(recording) =>
            val requests =
              Phase2.collect(tx, pp, scripts, inputs, references, slot => posix.map(_(slot)))
            recording.append(
              Phase2.job(env.txIndex, tx, pp, env.slot, inputs, references, env.clock, requests)
            )
            requests.fold(errors => checkedPhase2(stats, tx, Left(errors)), _ => Right(stats))
          case None =>
            val outcome = Phase2.evaluate(
              tx,
              pp,
              scripts,
              inputs,
              references,
              slot => posix.map(_(slot)),
              env.evaluator
            )
            env.observer.foreach(
              _(Phase2.Observation(tx, pp, env.slot, inputs, references, env.clock, outcome))
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

  private def bootstrapOutputs(outs: Vector[TxOut]): Vector[(TxOut, byron.ByronAddress)] =
    outs.flatMap(out =>
      out.address.kind match
        case Address.Kind.Byron(_) =>
          byron.ByronAddress.decode(out.address.raw).toOption.map(out -> _)
        case _ => None
    )

  /** `Shelley/Rules/Utxo.hs:378-380` and `Allegra/Rules/Utxo.hs:188-190` (f649f975): output
    * networks, including bootstrap addresses, precede withdrawals and value conservation.
    */
  private[ledger] def outputNetwork(env: Env, id: Bytes, outs: Vector[TxOut]): Result[Unit] =
    val wrong = outs
      .filter(_.address.kind match
        case Address.Kind.Shelley(n, _, _, _) => n != env.network
        case _                                => false)
      .map(_.address.raw)
      .toSet ++ bootstrapOutputs(outs).collect {
      case (out, address) if address.network != env.network => out.address.raw
    }
    Either.cond(
      wrong.isEmpty,
      (),
      LedgerFailure.Rejected(
        id,
        PredicateFailure.utxo(UtxoFailure.WrongNetwork(env.network, wrong))
      )
    )

  /** Output bounds before Conway. Shelley/Allegra/Mary run their network predicate earlier in UTXO.
    * All eras check minimum value, value size (from Allegra), then bootstrap attributes
    * (`Shelley/Rules/Utxo.hs:394-397`, `Allegra/Rules/Utxo.hs:207-214`, f649f975). Alonzo and
    * Babbage then check networks (`Alonzo/Rules/Utxo.hs:543-556`, `Babbage/Rules/Utxo.hs:384-397`).
    */
  private[ledger] def outputChecks(
      pp: ProtocolParams,
      env: Env,
      id: Bytes,
      outs: Vector[TxOut]
  ): Result[Unit] =
    // Allegra and Mary test `pointwise (<) v (inject min)`: every component must be below, so a
    // value carrying assets is never too small. Alonzo negates `pointwise (>=)` instead.
    def minimumApplies(out: TxOut) =
      pp.protocolMajor <= 2 || pp.protocolMajor >= 5 || out.value.assets.isEmpty
    val small = outs.filter(o => minimumApplies(o) && o.value.coin < minUTxO(pp, o))
    val big = outs.flatMap { o =>
      maxOutputValueSize(pp).filter(max => BigInt(o.value.serializedSize) > max).map { max =>
        (o.value.serializedSize, max.toInt, o)
      }
    }
    val largeAttributes = bootstrapOutputs(outs).collect {
      case (out, address) if address.attributesSize > 64 => out
    }
    def check(ok: Boolean, f: => UtxoFailure): Result[Unit] =
      Either.cond(ok, (), LedgerFailure.Rejected(id, PredicateFailure.utxo(f)))
    for
      _ <- check(
        small.isEmpty,
        if pp.protocolMajor >= 7 then
          UtxoFailure.BabbageOutputTooSmall(small.map(out => out -> minUTxO(pp, out)))
        else UtxoFailure.OutputTooSmall(small)
      )
      _ <- check(big.isEmpty, UtxoFailure.OutputTooBig(big))
      _ <- check(largeAttributes.isEmpty, UtxoFailure.OutputBootAddrAttrsTooBig(largeAttributes))
      _ <- if pp.protocolMajor >= 5 then outputNetwork(env, id, outs) else Right(())
    yield ()

  /** The output checks of Babbage and Conway UTXO, each reporting every offending output: too small
    * (with the minimum), too big (with the size and the limit) and on the wrong network.
    */
  private[ledger] def outputsOk(
      pp: ProtocolParams,
      env: Env,
      outs: Vector[TxOut]
  ): Either[PredicateFailure, Unit] =
    val small = outs.filter(o => o.value.coin < minUTxO(pp, o)).map(o => o -> minUTxO(pp, o))
    val big = outs.flatMap { o =>
      maxOutputValueSize(pp).filter(max => BigInt(o.value.serializedSize) > max).map { max =>
        (o.value.serializedSize, max.toInt, o)
      }
    }
    val wrong = outs.filter(_.address.kind match
      case Address.Kind.Shelley(n, _, _, _) => n != env.network
      case Address.Kind.Byron(_)            => false)
    for
      _ <- Either.cond(
        small.isEmpty,
        (),
        PredicateFailure.utxo(UtxoFailure.BabbageOutputTooSmall(small))
      )
      _ <- Either.cond(big.isEmpty, (), PredicateFailure.utxo(UtxoFailure.OutputTooBig(big)))
      _ <- Either.cond(
        wrong.isEmpty,
        (),
        PredicateFailure.utxo(UtxoFailure.WrongNetwork(env.network, wrong.map(_.address.raw).toSet))
      )
    yield ()

  /** The reference stores collateral as `Set TxIn` (`Alonzo/Rules/Utxo.hs:288-324,470-481` at
    * f649f975). Deduplicate decoded inputs, preserving the transaction's original signed bytes.
    */
  private[ledger] def collateralInputs(body: TxBody): Vector[TxIn] =
    body.collateral.toSet.toVector.sorted

  /** Collateral rules (Alonzo+): only for transactions that carry redeemers. Returns the collateral
    * outputs and their ada balance after the return output.
    */
  private[ledger] def collateralChecks(
      utxo: Utxo,
      pp: ProtocolParams,
      tx: Tx
  ): Result[Collateral] =
    val body = tx.body
    val id = body.id
    def check(ok: Boolean, failure: => UtxoFailure): Result[Unit] =
      if ok then Right(())
      else Left(LedgerFailure.Rejected(id, PredicateFailure.utxo(failure)))
    if pp.protocolMajor < 5 || tx.witnesses.redeemers.isEmpty then Right(Collateral.none)
    else
      val inputs = collateralInputs(body)
      val missing = inputs.filterNot(utxo.contains).toSet
      for
        _ <- check(missing.isEmpty, UtxoFailure.BadInputs(missing))
        outs = inputs.flatMap(utxo.get)
        required = tx.witnesses.redeemers.nonEmpty
        _ <-
          if !required then Right(())
          else
            val total = outs.foldLeft(Value.zero)(_ + _.value)
            val balance = body.collateralReturn.fold(total)(r => total - r.value)
            val needed = (body.fee * pp.collateralPercentage + 99) / 100
            val scriptLocked = inputs.zip(outs).filter { case (_, o) =>
              o.address.kind match
                case Address.Kind.Shelley(_, Credential.Script(_), _, _) => true
                case _                                                   => false
            }
            for
              _ <- check(
                inputs.size <= pp.maxCollateralInputs,
                UtxoFailure.TooManyCollateralInputs(
                  Mismatch(inputs.size, pp.maxCollateralInputs.toInt)
                )
              )
              _ <- check(inputs.nonEmpty, UtxoFailure.NoCollateralInputs)
              _ <- check(scriptLocked.isEmpty, UtxoFailure.ScriptsNotPaid(scriptLocked.toMap))
              _ <- check(balance.assets.isEmpty, UtxoFailure.CollateralContainsNonAda(balance))
              _ <- check(
                balance.coin * 100 >= body.fee * pp.collateralPercentage,
                UtxoFailure.InsufficientCollateral(balance.coin, needed)
              )
              _ <- check(
                body.totalCollateral.forall(_ == balance.coin),
                UtxoFailure.IncorrectTotalCollateralField(
                  balance.coin,
                  body.totalCollateral.getOrElse(balance.coin)
                )
              )
            yield ()
      yield
        val total = outs.foldLeft(Value.zero)(_ + _.value)
        val balance = body.collateralReturn.fold(total)(r => total - r.value)
        Collateral(outs, balance.coin)

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

  /** Script hashes the transaction must provide, with the redeemer pointers that use each. */
  private[ledger] def scriptsNeeded(body: TxBody, spent: Vector[TxOut]): Map[Bytes, Set[Pointer]] =
    val byInput = body.inputs.zip(spent).toMap
    Purpose
      .all(body, byInput)
      .flatMap(p => p.scriptHash.map(h => h -> ((p.tag, BigInt(p.index)))))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  /** Alonzo UTXOW: redeemers match the Plutus purposes, datums, script data hash presence. */
  private[ledger] def alonzoChecks(
      pp: ProtocolParams,
      tx: Tx,
      spent: Vector[TxOut],
      scripts: Map[Bytes, Script],
      plutusPurposes: Set[Pointer]
  ): Result[Unit] =
    val pv = pp.protocolMajor
    val body = tx.body
    val id = body.id
    val wits = tx.witnesses
    def checkWith(ok: Boolean, failure: => UtxowFailure): Result[Unit] =
      if ok then Right(())
      else Left(LedgerFailure.Rejected(id, PredicateFailure.utxow(failure)))
    if pv < 5 then
      Either.cond(
        wits.redeemers.isEmpty && wits.datums.isEmpty && body.scriptDataHash.isEmpty,
        (),
        LedgerFailure.Undecodable(id, TxDefect.PlutusFieldsBeforeAlonzo)
      )
    else
      val provided = wits.redeemers.map(r => (r.tag, r.index)).toSet
      // Datums required by spent outputs locked with a Plutus script.
      val required = spent.flatMap { out =>
        out.address.kind match
          case Address.Kind.Shelley(_, Credential.Script(h), _, _)
              if scripts.get(h).exists(_.kind.isPlutus) =>
            Some(out.datum -> scripts(h).kind)
          case _ => None
      }
      // Plutus purposes without a redeemer, with the script each one needs
      val unredeemed = Purpose
        .all(body, body.inputs.zip(spent).toMap)
        .flatMap(p =>
          p.scriptHash
            .filter(h => scripts.get(h).exists(_.kind.isPlutus))
            .filter(_ => !provided.contains((p.tag, BigInt(p.index))))
            .map(p -> _)
        )
      for
        _ <- checkWith(unredeemed.isEmpty, UtxowFailure.MissingRedeemers(unredeemed))
        _ <- checkWith(
          provided.subsetOf(plutusPurposes),
          UtxowFailure.ExtraRedeemers((provided -- plutusPurposes).toVector.sorted)
        )
        // Datum hashes the spent outputs ask for and the witnesses do not carry
        requiredHashes = required.collect { case (Datum.Hash(h), _) => h }.toSet
        _ <- checkWith(
          requiredHashes.subsetOf(wits.datumHashes),
          UtxowFailure.MissingRequiredDatums(requiredHashes -- wits.datumHashes, wits.datumHashes)
        )
        // Outputs locked by a Plutus script without a usable datum hash
        unspendable = spent.zip(body.inputs).flatMap { case (out, in) =>
          out.address.kind match
            case Address.Kind.Shelley(_, Credential.Script(h), _, _)
                if scripts.get(h).exists(_.kind.isPlutus) =>
              out.datum match
                case Datum.Hash(_)   => None
                case Datum.Inline(_) => Option.when(pv < 7)(in)
                // Plutus V3 scripts need no datum (CIP-69)
                case Datum.NoDatum => Option.when(scripts(h).kind != ScriptKind.PlutusV3)(in)
            case _ => None
        }
        _ <- checkWith(
          unspendable.isEmpty,
          UtxowFailure.UnspendableUTxONoDatumHash(unspendable.toSet)
        )
        needsHash = wits.redeemers.nonEmpty || wits.datums.nonEmpty
        languages = scriptsNeeded(body, spent).keys.flatMap(scripts.get).map(_.kind).toSet
        _ <- checkWith(
          body.scriptDataHash.isDefined == needsHash,
          UtxowFailure.PPViewHashesDontMatch(
            Mismatch(body.scriptDataHash, ScriptIntegrity.hash(tx, pp, languages))
          )
        )
      yield ()

  /** Babbage onward: every Plutus script in the witness set and in the outputs (the collateral
    * return included) must deserialise for its language and the protocol version in force, which
    * rejects a builtin the pair does not allow (`validateScriptsWellFormed`:
    * `MalformedScriptWitnesses`, `MalformedReferenceScripts`). Alonzo has no such check; there an
    * undecodable script that a transaction needs fails phase 2.
    */
  /** Babbage onward: every Plutus script in the witness set and in the outputs (the collateral
    * return included) must deserialise for its language and the protocol version in force, which
    * rejects a builtin the pair does not allow (`validateScriptsWellFormed`:
    * `MalformedScriptWitnesses`, `MalformedReferenceScripts`). Alonzo has no such check; there an
    * undecodable script that a transaction needs fails phase 2.
    */
  private[ledger] def scriptsWellFormed(pp: ProtocolParams, tx: Tx, env: Env): Result[Unit] =
    val pv = pp.protocolMajor.toInt
    def malformed(failure: Set[Bytes] => UtxowFailure, scripts: Vector[Script]): Result[Unit] =
      val bad = scripts.filter(_.kind.isPlutus).traverse { script =>
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
      bad.flatMap { hashes =>
        val malformed = hashes.flatten.toSet
        if malformed.isEmpty then Right(())
        else Left(LedgerFailure.Rejected(tx.body.id, PredicateFailure.utxow(failure(malformed))))
      }
    if pv < 7 then Right(())
    else
      for
        _ <- malformed(UtxowFailure.MalformedScriptWitnesses(_), tx.witnesses.scripts)
        _ <- malformed(
          UtxowFailure.MalformedReferenceScripts(_),
          (tx.body.outputs ++ tx.body.collateralReturn.toVector).flatMap(_.referenceScript)
        )
      yield ()

  /** Shelley/Rules/Utxow.hs:427-443 and Alonzo/TxAuxData.hs:369-373 at f649f975. Auxiliary scripts
    * are checked even when they are not used by phase 2, for either validity flag. A backend outage
    * supplies no validation verdict.
    */
  private[ledger] def auxiliaryChecks(pp: ProtocolParams, tx: Tx, env: Env): Result[Unit] =
    for
      decoded <- tx.auxiliary
        .traverse(bytes =>
          AuxiliaryScripts
            .read(bytes, pp.protocolMajor)
            .leftMap(error => LedgerFailure.Undecodable(tx.body.id, TxDefect.AuxiliaryData(error)))
        )
      _ <- auxiliaryHash(tx)
      _ <- decoded.traverse_ { scripts =>
        scripts
          .traverse { script =>
            env.decoder.validate(script.language, pp.protocolMajor.toInt, script.code) match
              case Right(())                                                       => Right(true)
              case Left(xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure.Malformed(_)) => Right(false)
              case Left(xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure.Unavailable(why)) =>
                Left(
                  LedgerFailure.Unavailable(
                    tx.body.id,
                    ScriptLocation.Phase1(script.hash),
                    xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported(why)
                  )
                )
          }
          .flatMap { valid =>
            Either.cond(
              valid.forall(identity),
              (),
              LedgerFailure
                .Rejected(tx.body.id, PredicateFailure.utxow(UtxowFailure.InvalidMetadata))
            )
          }
      }
    yield ()

  private def auxiliaryHash(tx: Tx): Result[Unit] =
    val id = tx.body.id
    (tx.body.auxDataHash, tx.auxiliary) match
      case (None, None) => Right(())
      case (Some(h), Some(aux)) =>
        val computed = Blake2b.hash256.hash(aux)
        Either.cond(
          computed == h,
          (),
          LedgerFailure.Rejected(
            id,
            PredicateFailure.utxow(UtxowFailure.ConflictingMetadataHash(Mismatch(h, computed)))
          )
        )
      case (Some(h), None) =>
        Left(
          LedgerFailure.Rejected(id, PredicateFailure.utxow(UtxowFailure.MissingTxMetadata(h)))
        )
      case (None, Some(aux)) =>
        Left(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.utxow(
              UtxowFailure.MissingTxBodyMetadataHash(Blake2b.hash256.hash(aux))
            )
          )
        )

  /** Key hashes that must sign (`witsVKeyNeeded`) and the address roots that need a bootstrap
    * witness.
    */
  /** Key hashes that must sign (`witsVKeyNeeded`) and the address roots that need a bootstrap
    * witness. Reads of the state: the genesis delegations (empty from Conway, which has no update
    * proposals).
    */
  private[ledger] def witnessChecks(
      genDelegs: Map[Bytes, GenDelegPair],
      tx: Tx,
      spent: Vector[TxOut],
      collateral: Collateral,
      scripts: Map[Bytes, Script],
      needed: Map[Bytes, Set[Pointer]],
      vkeyHashes: Vector[Bytes],
      verdicts: xyz.kd5ujc.scarab.ledger.Verdicts
  ): Result[Unit] =
    val body = tx.body
    val id = body.id
    val wits = tx.witnesses
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
    // `witsFromTxWitnesses`: the hashes of the vkey witnesses and the address roots of the bootstrap
    // witnesses form one set, and a Byron input's `bootstrapKeyHash` is one of the needed hashes.
    // A vkey witness whose key hashes to the root therefore also spends the Byron output.
    val provided = vkeyHashes.toSet ++ wits.bootstrap.map(_.addressRoot)
    val invalidKeys = wits.vkeys.collect {
      case (vkey, signature) if !verdicts.witness(vkey, signature, id) => vkey
    } ++ wits.bootstrap.collect {
      case w if !verdicts.witness(w.vkey, w.signature, id) => w.vkey
    }
    for
      _ <- Either.cond(
        invalidKeys.isEmpty,
        (),
        LedgerFailure.Rejected(
          id,
          PredicateFailure.utxow(UtxowFailure.InvalidWitnesses(invalidKeys))
        )
      )
      missing = required -- provided
      _ <- Either.cond(
        missing.isEmpty,
        (),
        LedgerFailure.Rejected(
          id,
          PredicateFailure.utxow(UtxowFailure.MissingVKeyWitnesses(missing))
        )
      )
      // Native scripts (phase 1): the ones the transaction needs must hold.
      failing = needed.keys.toSet.filter { h =>
        scripts(h).native.exists(s => !s.eval(provided, body.validityStart, body.ttl))
      }
      _ <- Either.cond(
        failing.isEmpty,
        (),
        LedgerFailure.Rejected(
          id,
          PredicateFailure.utxow(UtxowFailure.ScriptWitnessNotValidating(failing))
        )
      )
    yield ()

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

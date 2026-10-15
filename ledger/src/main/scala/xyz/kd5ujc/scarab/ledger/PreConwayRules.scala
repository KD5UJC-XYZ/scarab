// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.annotation.unused
import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.GenDelegPair

/** UTXOW, UTXO, UTXOS and DELEGS for Shelley, Allegra, Mary, Alonzo and Babbage. Era behaviour is
  * selected by the protocol major version of the parameters in force: Allegra 3, Mary 4, Alonzo 5,
  * Babbage 7. Plutus scripts are evaluated (see [[Phase2]]) and the block's `is_valid` flag must
  * equal the verdict: a transaction flagged `true` is applied, one flagged `false` consumes its
  * collateral, and a flag that disagrees with the evaluation is a validation error.
  */
object PreConwayRules:
  import TxRules.{Env, Result}

  /** A redeemer pointer: tag 0 spend, 1 mint, 2 certificate, 3 reward, then index. */
  private[ledger] type Pointer = (Int, BigInt)

  def apply(state: LedgerState, tx: Tx, env: Env): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    val pp = state.params
    val pv = pp.protocolMajor
    val alonzo = pv >= 5
    def invalid(rule: String, detail: String) = Left(LedgerFailure.Invalid(id, rule, detail))
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else invalid(rule, detail)
    def resolve(ins: Vector[TxIn], rule: String): Result[Vector[TxOut]] =
      ins.traverse(i => state.utxo.get(i).toRight(LedgerFailure.Invalid(id, rule, i.render)))

    val wits = tx.witnesses
    val vkeyHashes: Vector[Bytes] = wits.vkeys.map(w => env.verdicts.keyHash(w._1))
    val scriptFee =
      if alonzo then
        val u = wits.totalExUnits
        (pp.priceMem * Rational(u.mem) + pp.priceSteps * Rational(u.steps)).ceil
      else BigInt(0)
    val minFee = pp.minFeeA * tx.size + pp.minFeeB + scriptFee

    // Deposits and refunds depend on the state before the transaction, not on its own order.
    val newPools = body.certificates.collect {
      case Certificate.RegisterPool(p) if !state.pools.contains(p.operator) => p.operator
    }.toSet
    val deposits = pp.keyDeposit * body.certificates.count {
      case Certificate.Register(_, _) => true
      case _                          => false
    } + pp.poolDeposit * newPools.size
    val refunds = pp.keyDeposit * body.certificates.count {
      case Certificate.Unregister(_, _) => true
      case _                            => false
    }

    for
      _ <- check(!body.hasGovernance && body.donation == 0, "UnsupportedField", "governance field")
      _ <- check(body.inputs.nonEmpty, "InputSetEmptyUTxO", "no inputs")
      spent <- resolve(body.inputs.toVector, "BadInputsUTxO")
      refOuts <- resolve(body.referenceInputs, "BadInputsUTxO")
      _ <- check(
        body.ttl.forall(env.slot < _) && body.validityStart.forall(_ <= env.slot),
        "OutsideValidityIntervalUTxO",
        s"slot ${env.slot} not in [${body.validityStart}, ${body.ttl})"
      )
      _ <- check(tx.size <= pp.maxTxSize, "MaxTxSizeUTxO", s"${tx.size} > ${pp.maxTxSize}")
      _ <- check(body.fee >= minFee, "FeeTooSmallUTxO", s"${body.fee} < $minFee")
      _ <- check(
        body.networkId.forall(_ == env.network),
        "WrongNetworkInTxBody",
        s"${body.networkId}"
      )
      _ <- (body.outputs ++ body.collateralReturn.toVector).traverse_(outputChecks(pp, env, id, _))
      _ <- check(
        !alonzo || wits.totalExUnits.fits(pp.maxTxExUnits),
        "ExUnitsTooBigUTxO",
        s"${wits.totalExUnits}"
      )
      collateral <- collateralChecks(state, tx, env)
      scripts = providedScripts(wits, spent ++ refOuts)
      needed = scriptsNeeded(body, spent)
      _ <- check(
        needed.keySet.subsetOf(scripts.keySet),
        "MissingScriptWitnessesUTXOW",
        (needed.keySet -- scripts.keySet).map(_.hex).mkString(",")
      )
      extraneous = wits.scripts.map(_.hash).toSet -- needed.keySet
      _ <- check(
        extraneous.isEmpty,
        "ExtraneousScriptWitnessesUTXOW",
        extraneous.map(_.hex).mkString(",")
      )
      plutusPurposes = needed
        .collect { case (h, ps) if scripts(h).kind.isPlutus => ps }
        .flatten
        .toSet
      _ <- alonzoChecks(state, tx, spent, refOuts, scripts, plutusPurposes)
      _ <- check(
        tx.isValid || plutusPurposes.nonEmpty,
        "ValidationTagMismatch",
        "is_valid = false without a Plutus script"
      )
      _ <- auxiliaryChecks(tx)
      _ <- witnessChecks(state, tx, spent, collateral, scripts, needed, vkeyHashes, env.verdicts)
      _ <- scriptsWellFormed(state, tx, env)
      // Value conservation holds for valid and invalid transactions alike.
      withdrawn = body.withdrawals.map(_._2).sum
      consumed = spent.foldLeft(Value.zero)(_ + _.value) + Value.coin(withdrawn + refunds) +
        Value(0, body.mint)
      produced = body.outputs.foldLeft(Value.zero)(_ + _.value) + Value.coin(body.fee + deposits)
      _ <- check(
        consumed == produced,
        "ValueNotConservedUTxO",
        s"consumed ${consumed.coin} produced ${produced.coin} assets ${consumed.assets == produced.assets}"
      )
      plutusStats <- phase2(state, tx, env, scripts, spent, refOuts, plutusPurposes)
      result <-
        if tx.isValid then
          applyValid(state, tx, env, deposits, refunds).map(_.copy(plutus = plutusStats))
        else Right(applyInvalid(state, tx, collateral).copy(plutus = plutusStats))
    yield result

  /** The script data hash must equal the recomputed hash, and the block's `is_valid` flag must
    * equal the outcome of evaluating every Plutus script.
    */
  private[ledger] def phase2(
      state: LedgerState,
      tx: Tx,
      env: Env,
      scripts: Map[Bytes, Script],
      spent: Vector[TxOut],
      refOuts: Vector[TxOut],
      plutusPurposes: Set[Pointer]
  ): Result[PlutusStats] =
    val body = tx.body
    val id = body.id
    val pp = state.params
    if pp.protocolMajor < 5 || (plutusPurposes.isEmpty && body.scriptDataHash.isEmpty) then
      Right(state.plutus)
    else
      val needed = scriptsNeeded(body, spent)
      val languages = needed.keys.flatMap(scripts.get).map(_.kind).toSet
      val recomputed = ScriptIntegrity.hash(tx, pp, languages)
      val stats = state.plutus.copy(integrityChecked = state.plutus.integrityChecked + 1)
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
        val posix = env.clock.toRight(TranslationError("no era history for slot times"))
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
        outcome.left
          .map(e => LedgerFailure.Invalid(id, "CollectErrors", e.reason))
          .flatMap {
            case Phase2.Outcome.Passed(n) =>
              if tx.isValid then
                Right(stats.copy(succeeded = stats.succeeded + 1, scripts = stats.scripts + n))
              else
                Left(
                  LedgerFailure.Rejected(
                    id,
                    PredicateFailure.utxo(
                      UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, None))
                    )
                  )
                )
            case Phase2.Outcome.Failed(n, redeemer, reason) =>
              if !tx.isValid then
                Right(stats.copy(failed = stats.failed + 1, scripts = stats.scripts + n + 1))
              else
                Left(
                  LedgerFailure.Rejected(
                    id,
                    PredicateFailure.utxo(
                      UtxoFailure.Utxos(
                        UtxosFailure.ValidationTagMismatch(
                          true,
                          Some(Vector(reason.message -> Bytes.empty))
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
    * unlike Alonzo where the ada-only entry counts 2 words); Alonzo and Babbage:
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

  private[ledger] def outputChecks(
      pp: ProtocolParams,
      env: Env,
      id: Bytes,
      out: TxOut
  ): Result[Unit] =
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Invalid(id, rule, detail))
    val min = minUTxO(pp, out)
    // Allegra and Mary test `pointwise (<) v (inject min)`: every component must be below, so a value
    // that carries assets is never too small (Shelley compares the coin, Alonzo negates `pointwise (>=)`).
    val minimumApplies = pp.protocolMajor <= 2 || pp.protocolMajor >= 5 || out.value.assets.isEmpty
    for
      _ <- check(
        !minimumApplies || out.value.coin >= min,
        "OutputTooSmallUTxO",
        s"${out.value.coin} < $min"
      )
      _ <- check(
        out.address.kind match
          case Address.Kind.Shelley(n, _, _, _) => n == env.network
          case Address.Kind.Byron(_)            => true
        ,
        "WrongNetwork",
        out.address.raw.hex
      )
      _ <- check(
        maxOutputValueSize(pp).forall(BigInt(out.value.serializedSize) <= _),
        "OutputTooBigUTxO",
        s"${out.value.serializedSize}"
      )
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

  /** Collateral rules (Alonzo+): only for transactions that carry redeemers. Returns the collateral
    * outputs and their ada balance after the return output.
    */
  private[ledger] def collateralChecks(
      state: LedgerState,
      tx: Tx,
      @unused env: Env
  ): Result[Collateral] =
    val body = tx.body
    val pp = state.params
    val id = body.id
    def check(ok: Boolean, failure: => UtxoFailure): Result[Unit] =
      if ok then Right(())
      else Left(LedgerFailure.Rejected(id, PredicateFailure.utxo(failure)))
    if pp.protocolMajor < 5 || tx.witnesses.redeemers.isEmpty then Right(Collateral.none)
    else
      val missing = body.collateral.filterNot(state.utxo.contains).toSet
      for
        _ <- check(missing.isEmpty, UtxoFailure.BadInputs(missing))
        outs = body.collateral.flatMap(state.utxo.get)
        required = tx.witnesses.redeemers.nonEmpty
        _ <-
          if !required then Right(())
          else
            val total = outs.foldLeft(Value.zero)(_ + _.value)
            val balance = body.collateralReturn.fold(total)(r => total - r.value)
            val needed = (body.fee * pp.collateralPercentage + 99) / 100
            val scriptLocked = body.collateral.zip(outs).filter { case (_, o) =>
              o.address.kind match
                case Address.Kind.Shelley(_, Credential.Script(_), _, _) => true
                case _                                                   => false
            }
            for
              _ <- check(
                body.collateral.size <= pp.maxCollateralInputs,
                UtxoFailure.TooManyCollateralInputs(
                  Mismatch(body.collateral.size, pp.maxCollateralInputs.toInt)
                )
              )
              _ <- check(body.collateral.nonEmpty, UtxoFailure.NoCollateralInputs)
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
      state: LedgerState,
      tx: Tx,
      spent: Vector[TxOut],
      @unused refOuts: Vector[TxOut],
      scripts: Map[Bytes, Script],
      plutusPurposes: Set[Pointer]
  ): Result[Unit] =
    val pv = state.params.protocolMajor
    val body = tx.body
    val id = body.id
    val wits = tx.witnesses
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Invalid(id, rule, detail))
    def checkWith(ok: Boolean, failure: => UtxowFailure): Result[Unit] =
      if ok then Right(())
      else Left(LedgerFailure.Rejected(id, PredicateFailure.utxow(failure)))
    if pv < 5 then
      check(
        wits.redeemers.isEmpty && wits.datums.isEmpty && body.scriptDataHash.isEmpty,
        "UnsupportedField",
        "Plutus fields before Alonzo"
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
            Mismatch(body.scriptDataHash, ScriptIntegrity.hash(tx, state.params, languages))
          )
        )
      yield ()

  /** Babbage onward: every Plutus script in the witness set and in the outputs (the collateral
    * return included) must deserialise for its language and the protocol version in force, which
    * rejects a builtin the pair does not allow (`validateScriptsWellFormed`:
    * `MalformedScriptWitnesses`, `MalformedReferenceScripts`). Alonzo has no such check; there an
    * undecodable script that a transaction needs fails phase 2.
    */
  private[ledger] def scriptsWellFormed(state: LedgerState, tx: Tx, env: Env): Result[Unit] =
    val pv = state.params.protocolMajor.toInt
    def malformed(rule: String, scripts: Vector[Script]): Result[Unit] =
      val bad = scripts.filter(_.kind.isPlutus).flatMap { s =>
        for
          language <- Phase2.languageOf(s).toOption.toVector
          code <- s.plutus.toVector
          reason <- env.decoder.validate(language, pv, code).left.toOption
        yield s.hash.hex -> reason
      }
      if bad.isEmpty then Right(())
      else
        val hashes = bad.flatMap(b => Bytes.fromHex(b._1).toOption).toSet
        Left(
          LedgerFailure.Rejected(
            tx.body.id,
            PredicateFailure.utxow(
              if rule == "MalformedScriptWitnesses" then
                UtxowFailure.MalformedScriptWitnesses(hashes)
              else UtxowFailure.MalformedReferenceScripts(hashes)
            )
          )
        )
    if pv < 7 then Right(())
    else
      for
        _ <- malformed("MalformedScriptWitnesses", tx.witnesses.scripts)
        _ <- malformed(
          "MalformedReferenceScripts",
          (tx.body.outputs ++ tx.body.collateralReturn.toVector).flatMap(_.referenceScript)
        )
      yield ()

  private[ledger] def auxiliaryChecks(tx: Tx): Result[Unit] =
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
  private[ledger] def witnessChecks(
      state: LedgerState,
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
      _.proposals.keys.toVector.flatMap(g => state.genDelegs.get(g).map(_.delegate))
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

  // ---------------------------------------------------------------------------------------------
  // Application

  private[ledger] def applyInvalid(
      state: LedgerState,
      tx: Tx,
      collateral: Collateral
  ): LedgerState =
    val body = tx.body
    val returned = body.collateralReturn.map(r => TxIn(body.id, body.outputs.size) -> r)
    state.copy(
      utxo = state.utxo -- body.collateral ++ returned,
      fees = state.fees + collateral.fee
    )

  private def applyValid(
      state: LedgerState,
      tx: Tx,
      env: Env,
      deposits: BigInt,
      refunds: BigInt
  ): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    def fail(rule: String, detail: String) = LedgerFailure.Invalid(id, rule, detail)
    for
      afterWithdrawals <- body.withdrawals.foldLeftM(state) { case (s, (account, amount)) =>
        for
          _ <- Either.cond(
            account.network == env.network,
            (),
            fail("WrongNetworkWithdrawal", account.credential.render)
          )
          a <- s.accounts
            .get(account.credential)
            .toRight(fail("WithdrawalsNotInRewardsDELEGS", account.credential.render))
          _ <- Either.cond(
            a.balance == amount,
            (),
            fail(
              "WithdrawalsNotInRewardsDELEGS",
              s"${account.credential.render}: $amount != ${a.balance}"
            )
          )
        yield s.copy(accounts = s.accounts.updated(account.credential, a.copy(balance = 0)))
      }
      afterUpdate <- body.update.fold(Right(afterWithdrawals): Result[LedgerState]) { u =>
        PpupRules
          .propose(afterWithdrawals, u, env.slot, env.firstSlotNextEpoch, env.stabilityWindow)
          .leftMap { case (rule, detail) => fail(rule, detail) }
      }
      afterCerts <- body.certificates.zipWithIndex.foldLeftM(afterUpdate) { case (s, (cert, ix)) =>
        PreConwayCerts(s, cert, ix, env, tx)
      }
    yield
      val created = body.outputs.zipWithIndex.map { case (out, i) => TxIn(id, i) -> out }
      afterCerts.copy(
        utxo = afterCerts.utxo -- body.inputs ++ created,
        fees = afterCerts.fees + body.fee,
        deposited = afterCerts.deposited + deposits - refunds
      )

/** DELEGS for the eras before Conway: stake key registration, delegation, pool registration and
  * retirement, genesis delegation and instantaneous reward certificates.
  */
private[ledger] object PreConwayCerts:
  import TxRules.{Env, Result}

  def apply(s: LedgerState, cert: Certificate, ix: Int, env: Env, tx: Tx): Result[LedgerState] =
    val id = tx.body.id
    val pp = s.params
    def fail(rule: String, detail: String) = LedgerFailure.Invalid(id, rule, detail)
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(fail(rule, detail))
    cert match
      case Certificate.Register(c, _) =>
        for _ <- check(!s.accounts.contains(c), "StakeKeyAlreadyRegisteredDELEG", c.render)
        yield s.copy(
          accounts = s.accounts.updated(c, Account(0, pp.keyDeposit, None, None)),
          ptrs = s.ptrs.updated(Ptr(env.slot, env.txIndex, ix), c)
        )
      case Certificate.Unregister(c, _) =>
        for
          a <- s.accounts.get(c).toRight(fail("StakeKeyNotRegisteredDELEG", c.render))
          _ <- check(a.balance == 0, "StakeKeyNonZeroAccountBalanceDELEG", s"${a.balance}")
        yield s.copy(
          accounts = s.accounts - c,
          ptrs = s.ptrs.filterNot(_._2 == c)
        )
      case Certificate.Delegate(c, Some(pool), _, _) =>
        for
          a <- s.accounts.get(c).toRight(fail("StakeDelegationImpossibleDELEG", c.render))
          _ <- check(s.pools.contains(pool), "DelegateeNotRegisteredDELEG", pool.hex)
        yield s.copy(accounts = s.accounts.updated(c, a.copy(pool = Some(pool))))
      case Certificate.RegisterPool(p) =>
        for
          _ <- check(p.cost >= pp.minPoolCost, "StakePoolCostTooLowPOOL", s"${p.cost}")
          _ <- check(p.rewardAccount.network == env.network, "WrongNetworkPOOL", p.operator.hex)
        yield
          if s.pools.contains(p.operator) then
            s.copy(
              futurePools = s.futurePools.updated(p.operator, p),
              retiring = s.retiring - p.operator
            )
          else
            // The deposit is accounted once per transaction.
            s.copy(pools = s.pools.updated(p.operator, PoolState(p, pp.poolDeposit)))
      case Certificate.RetirePool(pool, e) =>
        for
          _ <- check(s.pools.contains(pool), "StakePoolNotRegisteredOnKeyPOOL", pool.hex)
          _ <- check(
            s.epoch < e && e <= s.epoch + pp.eMax,
            "StakePoolRetirementWrongEpochPOOL",
            s"$e at ${s.epoch}"
          )
        yield s.copy(retiring = s.retiring.updated(pool, e))
      case Certificate.GenesisDelegate(g, d, v) =>
        for _ <- check(s.genDelegs.contains(g), "GenesisKeyNotInMappingDELEG", g.hex)
        yield s.copy(futureGenDelegs =
          s.futureGenDelegs :+ FutureGenDeleg(env.slot + env.stabilityWindow, g, GenDelegPair(d, v))
        )
      case Certificate.MoveRewards(pot, transfer) => moveRewards(s, pot, transfer, env, tx)
      case other =>
        Left(LedgerFailure.Unsupported(id, s"certificate $other before Conway"))

  private def moveRewards(
      s: LedgerState,
      pot: Pot,
      transfer: Either[BigInt, Map[Credential, BigInt]],
      env: Env,
      tx: Tx
  ): Result[LedgerState] =
    val id = tx.body.id
    def fail(rule: String, detail: String) = LedgerFailure.Invalid(id, rule, detail)
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(fail(rule, detail))
    val signers = tx.witnesses.vkeys.map(w => env.verdicts.keyHash(w._1)).toSet
    val genesisSigners = s.genDelegs.values.map(_.delegate).toSet.intersect(signers)
    val ir = s.mir
    val availableR = s.reserves + ir.deltaReserves
    val availableT = s.treasury + ir.deltaTreasury
    // hardforkAlonzoAllowMIRTransfer: transfers between pots and negative entries from protocol 5.
    val transfersAllowed = s.params.protocolMajor > 4
    for
      _ <- check(
        env.slot < env.firstSlotNextEpoch - env.stabilityWindow,
        "MIRCertificateTooLateinEpochDELEG",
        s"slot ${env.slot}"
      )
      _ <- check(
        genesisSigners.size >= env.updateQuorum,
        "MIRInsufficientGenesisSigsDELEG",
        s"${genesisSigners.size}"
      )
      result <- transfer match
        case Left(_) if !transfersAllowed =>
          Left(fail("MIRTransferNotCurrentlyAllowed", ""))
        case Left(coin) =>
          check(coin >= 0, "MIRNegativeTransfer", s"$coin") *> (pot match
            case Pot.Reserves =>
              check(
                coin <= availableR - ir.reserves.values.sum,
                "InsufficientForTransferDELEG",
                s"$coin"
              ).as(
                s.copy(mir =
                  ir.copy(
                    deltaReserves = ir.deltaReserves - coin,
                    deltaTreasury = ir.deltaTreasury + coin
                  )
                )
              )
            case Pot.Treasury =>
              check(
                coin <= availableT - ir.treasury.values.sum,
                "InsufficientForTransferDELEG",
                s"$coin"
              ).as(
                s.copy(mir =
                  ir.copy(
                    deltaReserves = ir.deltaReserves + coin,
                    deltaTreasury = ir.deltaTreasury - coin
                  )
                )
              ))
        case Right(amounts) =>
          val current = if pot == Pot.Reserves then ir.reserves else ir.treasury
          // Before Alonzo a certificate replaces earlier entries of the same credential and may not
          // carry negative amounts; from Alonzo it adds to them and the sum may not go negative.
          val combined =
            if transfersAllowed then
              amounts.foldLeft(current)((m, kv) =>
                m.updated(kv._1, m.getOrElse(kv._1, BigInt(0)) + kv._2)
              )
            else current ++ amounts
          val available = if pot == Pot.Reserves then availableR else availableT
          for
            _ <-
              if transfersAllowed then
                check(combined.values.forall(_ >= 0), "MIRProducesNegativeUpdate", "")
              else check(amounts.values.forall(_ >= 0), "MIRNegativesNotCurrentlyAllowed", "")
            _ <- check(
              combined.values.sum <= available,
              "InsufficientForInstantaneousRewardsDELEG",
              s"${combined.values.sum} > $available"
            )
          yield s.copy(mir =
            if pot == Pot.Reserves then ir.copy(reserves = combined)
            else ir.copy(treasury = combined)
          )
    yield result

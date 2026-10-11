// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.consensus.GenDelegPair
import lab.witness.{StrictEd25519, VerificationResult}

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
    val mary = pv >= 4
    val alonzo = pv >= 5
    val babbage = pv >= 7
    def invalid(rule: String, detail: String) = Left(LedgerFailure.Invalid(id, rule, detail))
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else invalid(rule, detail)
    def resolve(ins: Vector[TxIn], rule: String): Result[Vector[TxOut]] =
      ins.traverse(i => state.utxo.get(i).toRight(LedgerFailure.Invalid(id, rule, i.render)))

    val wits = tx.witnesses
    val vkeyHashes: Vector[Bytes] = wits.vkeys.map(w => Blake2b.hash224.hash(w._1))
    val hasRedeemers = wits.redeemers.nonEmpty
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
      _ <- witnessChecks(state, tx, spent, collateral, scripts, needed, vkeyHashes)
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
    def invalid(rule: String, detail: String) = Left(LedgerFailure.Invalid(id, rule, detail))
    if pp.protocolMajor < 5 || (plutusPurposes.isEmpty && body.scriptDataHash.isEmpty) then
      Right(state.plutus)
    else
      val needed = scriptsNeeded(body, spent)
      val languages = needed.keys.flatMap(scripts.get).map(_.kind).toSet
      val recomputed = ScriptIntegrity.hash(tx, pp, languages)
      val stats = state.plutus.copy(integrityChecked = state.plutus.integrityChecked + 1)
      if recomputed != body.scriptDataHash then
        invalid(
          "PPViewHashesDontMatch",
          s"body ${body.scriptDataHash.map(_.hex)}, recomputed ${recomputed.map(_.hex)}"
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
              else invalid("ValidationTagMismatch", "is_valid = false but every script succeeds")
            case Phase2.Outcome.Failed(n, redeemer, reason) =>
              if !tx.isValid then
                Right(stats.copy(failed = stats.failed + 1, scripts = stats.scripts + n + 1))
              else
                invalid(
                  "ValidationTagMismatch",
                  s"is_valid = true but redeemer (${redeemer.tag}, ${redeemer.index}) fails: ${reason.message}"
                )
          }

  // ---------------------------------------------------------------------------------------------
  // UTXO

  private[ledger] def minUTxO(pp: ProtocolParams, out: TxOut): BigInt =
    if pp.protocolMajor >= 7 then (160 + out.ledgerSize) * pp.coinsPerUTxOByte
    else if pp.protocolMajor >= 5 then pp.coinsPerUTxOWord * entrySize(out)
    else if pp.protocolMajor >= 4 then
      if out.value.assets.isEmpty then pp.minUTxOValue
      else pp.minUTxOValue.max((pp.minUTxOValue / 29) * entrySize(out))
    else pp.minUTxOValue

  /** `utxoEntrySizeWithoutVal` (27 words) plus the value and datum hash sizes. */
  private def entrySize(out: TxOut): BigInt =
    val value =
      if out.value.assets.isEmpty then BigInt(2)
      else
        val pairs = for
          (policy, names) <- out.value.assets.toVector
          (name, _) <- names.toVector
        yield (policy, name)
        val bytes = pairs.size * 12 + pairs.map(_._2.size).sum + out.value.assets.size * 28
        BigInt(6 + (bytes + 7) / 8)
    val datum = out.datum match
      case Datum.Hash(_) => 10
      case _             => 0
    27 + value + datum

  private[ledger] def outputChecks(
      pp: ProtocolParams,
      env: Env,
      id: Bytes,
      out: TxOut
  ): Result[Unit] =
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Invalid(id, rule, detail))
    val min = minUTxO(pp, out)
    for
      _ <- check(out.value.coin >= min, "OutputTooSmallUTxO", s"${out.value.coin} < $min")
      _ <- check(
        out.address.kind match
          case Address.Kind.Shelley(n, _, _, _) => n == env.network
          case Address.Kind.Byron(_)            => true
        ,
        "WrongNetwork",
        out.address.raw.hex
      )
      _ <- check(
        pp.protocolMajor < 5 || BigInt(out.valueSize) <= pp.maxValueSize,
        "OutputTooBigUTxO",
        s"${out.valueSize}"
      )
    yield ()

  /** Collateral rules (Alonzo+): only for transactions that carry redeemers. Returns the collateral
    * outputs and their ada balance after the return output.
    */
  private[ledger] def collateralChecks(
      state: LedgerState,
      tx: Tx,
      env: Env
  ): Result[Collateral] =
    val body = tx.body
    val pp = state.params
    val id = body.id
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Invalid(id, rule, detail))
    if pp.protocolMajor < 5 || tx.witnesses.redeemers.isEmpty then Right(Collateral.none)
    else
      for
        outs <- body.collateral.traverse(i =>
          state.utxo.get(i).toRight(LedgerFailure.Invalid(id, "BadInputsUTxO", i.render))
        )
        required = tx.witnesses.redeemers.nonEmpty
        _ <-
          if !required then Right(())
          else
            val total = outs.foldLeft(Value.zero)(_ + _.value)
            val balance = body.collateralReturn.fold(total)(r => total - r.value)
            for
              _ <- check(
                body.collateral.size <= pp.maxCollateralInputs,
                "TooManyCollateralInputs",
                s"${body.collateral.size}"
              )
              _ <- check(body.collateral.nonEmpty, "NoCollateralInputs", "")
              _ <- check(
                outs.forall(_.address.kind match
                  case Address.Kind.Shelley(_, Credential.Script(_), _, _) => false
                  case _                                                   => true),
                "ScriptsNotPaidUTxO",
                "collateral locked by a script"
              )
              _ <- check(balance.assets.isEmpty, "CollateralContainsNonADA", s"${balance.assets}")
              _ <- check(
                balance.coin * 100 >= body.fee * pp.collateralPercentage,
                "InsufficientCollateral",
                s"${balance.coin} for fee ${body.fee}"
              )
              _ <- check(
                body.totalCollateral.forall(_ == balance.coin),
                "IncorrectTotalCollateralField",
                s"${body.totalCollateral} != ${balance.coin}"
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
      refOuts: Vector[TxOut],
      scripts: Map[Bytes, Script],
      plutusPurposes: Set[Pointer]
  ): Result[Unit] =
    val pv = state.params.protocolMajor
    val body = tx.body
    val id = body.id
    val wits = tx.witnesses
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Invalid(id, rule, detail))
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
      for
        _ <- check(
          plutusPurposes.subsetOf(provided),
          "MissingRedeemers",
          (plutusPurposes -- provided).mkString(",")
        )
        _ <- check(
          provided.subsetOf(plutusPurposes),
          "ExtraRedeemers",
          (provided -- plutusPurposes).mkString(",")
        )
        _ <- required.traverse_ {
          case (Datum.Hash(h), _) =>
            check(wits.datumHashes.contains(h), "MissingRequiredDatums", h.hex)
          case (Datum.Inline(_), _) =>
            check(pv >= 7, "UnspendableUTxONoDatumHash", "inline datum before Babbage")
          // Plutus V3 scripts need no datum (CIP-69)
          case (Datum.NoDatum, kind) =>
            check(kind == ScriptKind.PlutusV3, "UnspendableUTxONoDatumHash", "no datum hash")
        }
        needsHash = wits.redeemers.nonEmpty || wits.datums.nonEmpty
        _ <- check(
          body.scriptDataHash.isDefined == needsHash,
          if needsHash then "MissingRequiredScriptIntegrityHash"
          else "UnexpectedScriptIntegrityHash",
          s"${body.scriptDataHash.map(_.hex)}"
        )
      yield ()

  private[ledger] def auxiliaryChecks(tx: Tx): Result[Unit] =
    val id = tx.body.id
    (tx.body.auxDataHash, tx.auxiliary) match
      case (None, None) => Right(())
      case (Some(h), Some(aux)) =>
        Either.cond(
          Blake2b.hash256.hash(aux) == h,
          (),
          LedgerFailure.Invalid(id, "ConflictingMetadataHash", h.hex)
        )
      case (Some(h), None) => Left(LedgerFailure.Invalid(id, "MissingTxMetadata", h.hex))
      case (None, Some(_)) =>
        Left(LedgerFailure.Invalid(id, "MissingTxBodyMetadataHash", "auxiliary data without hash"))

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
      vkeyHashes: Vector[Bytes]
  ): Result[Unit] =
    val body = tx.body
    val id = body.id
    val wits = tx.witnesses
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Invalid(id, rule, detail))
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
      (inputKeys ++ withdrawalKeys ++ certKeys ++ voterKeys ++ updateKeys ++ body.requiredSigners).toSet
    val provided = vkeyHashes.toSet
    for
      _ <- wits.vkeys.traverse_ { case (vkey, signature) =>
        StrictEd25519.verify(vkey, signature, id) match
          case Right(VerificationResult.SignatureVerified) => Right(())
          case _ => Left(LedgerFailure.Invalid(id, "InvalidWitnessesUTXOW", vkey.hex))
      }
      _ <- wits.bootstrap.traverse_ { w =>
        StrictEd25519.verify(w.vkey, w.signature, id) match
          case Right(VerificationResult.SignatureVerified) => Right(())
          case _ => Left(LedgerFailure.Invalid(id, "InvalidWitnessesUTXOW", w.vkey.hex))
      }
      missing = required -- provided
      _ <- check(missing.isEmpty, "MissingVKeyWitnessesUTXOW", missing.map(_.hex).mkString(","))
      bootProvided = wits.bootstrap.map(_.addressRoot).toSet
      missingBoot = bootRoots.toSet -- bootProvided
      _ <- check(
        missingBoot.isEmpty,
        "MissingBootstrapWitnessesUTXOW",
        missingBoot.map(_.hex).mkString(",")
      )
      // Native scripts (phase 1): the ones the transaction needs must hold.
      _ <- needed.keys.toVector.traverse_ { h =>
        scripts(h).native.fold(Right(()): Result[Unit]) { s =>
          check(
            s.eval(provided, body.validityStart, body.ttl),
            "ScriptWitnessNotValidatingUTXOW",
            h.hex
          )
        }
      }
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
          ptrs = s.ptrs.filterNot(_._2 == c),
          mir = s.mir.copy(
            reserves = s.mir.reserves - c,
            treasury = s.mir.treasury - c
          )
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
    val signers = tx.witnesses.vkeys.map(w => Blake2b.hash224.hash(w._1)).toSet
    val genesisSigners = s.genDelegs.values.map(_.delegate).toSet.intersect(signers)
    val ir = s.mir
    val availableR = s.reserves + ir.deltaReserves
    val availableT = s.treasury + ir.deltaTreasury
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
        case Left(coin) =>
          pot match
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
              )
        case Right(amounts) =>
          val current = if pot == Pot.Reserves then ir.reserves else ir.treasury
          val combined =
            if s.params.protocolMajor >= 5 then
              amounts.foldLeft(current)((m, kv) =>
                m.updated(kv._1, m.getOrElse(kv._1, BigInt(0)) + kv._2)
              )
            else current ++ amounts
          val available = if pot == Pot.Reserves then availableR else availableT
          for
            _ <- check(combined.values.forall(_ >= 0), "MIRNegativeTransfer", "")
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

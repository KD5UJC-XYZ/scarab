// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TxRules.{Env, Result}

/** The Conway LEDGER rule: withdrawals, CERTS and GOV for a phase-2 valid transaction, UTXOW
  * (scripts, datums, redeemers, witnesses, the script data hash), UTXO (fees including the
  * reference-script fee, value conservation with deposits and donations, collateral) and UTXOS
  * (phase-2 evaluation, whose verdict must equal the block's `is_valid` flag). A transaction
  * flagged invalid consumes its collateral and touches nothing else.
  */
object ConwayRules:
  /** Maximum total size of the reference scripts one transaction may use. */
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

  /** `unelectedCommitteeVoters`: the committee hot credentials among the voters of `body` that no
    * member of the current committee has authorised. From protocol version 11 the GOV rule refuses
    * such votes; before that the reference refuses them in its mempool only (`Rules/Mempool.hs`),
    * so blocks may carry them.
    */
  def unelectedCommitteeVoters(s: LedgerState, body: TxBody): Vector[Credential] =
    val electedHot =
      s.committeeState.collect {
        case (cold, CommitteeAuth.Hot(h)) if s.gov.committee.exists(_.members.contains(cold)) => h
      }.toSet
    body.votes.collect { case (Voter.CommitteeHot(h), _) if !electedHot(h) => h }

  /** `hardforkConwayDisallowDuplicatedVRFKeys` (protocol version 11 and later): the VRF key hash of
    * a pool registration may not be held by another pool, active or scheduled by a re-registration
    * (the ledger keeps this as `psVRFKeyHashes`; here it is derived from `pools` and `futurePools`,
    * which are the same set). A pool may keep its own current key. Differs from the reference only
    * for key hashes that were already shared when protocol version 11 began.
    */
  def vrfAvailable(s: LedgerState, p: PoolParams): Boolean =
    val vrf = p.vrfKeyHash
    s.pools.get(p.operator).exists(_.params.vrfKeyHash == vrf) ||
    (!s.pools.valuesIterator.exists(_.params.vrfKeyHash == vrf) &&
      !s.futurePools.valuesIterator.exists(_.vrfKeyHash == vrf))

  /** Total size of the reference scripts of the distinct inputs and reference inputs. */
  def refScriptsSize(state: LedgerState, body: TxBody): Int =
    (body.inputs.toSet ++ body.referenceInputs.toSet).iterator
      .flatMap(state.utxo.get)
      .map(_.referenceScriptSize)
      .sum

  def apply(state: LedgerState, tx: Tx, env: Env): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    val pp = state.params
    val pv = pp.protocolMajor
    def refuse(failure: PredicateFailure) = Left(LedgerFailure.Rejected(id, failure))
    def check(ok: Boolean, failure: => PredicateFailure): Result[Unit] =
      if ok then Right(()) else refuse(failure)

    val wits = tx.witnesses
    val vkeyHashes: Vector[Bytes] = wits.vkeys.map(w => env.verdicts.keyHash(w._1))
    val scriptFee =
      (pp.priceMem * Rational(wits.totalExUnits.mem) + pp.priceSteps * Rational(
        wits.totalExUnits.steps
      )).ceil
    val refSize = refScriptsSize(state, body)
    val minFee =
      pp.minFeeA * tx.size + pp.minFeeB + scriptFee +
        tierRefScriptFee(pp.minFeeRefScriptCostPerByte, refSize)
    val (deposits, refunds) = depositsAndRefunds(state, body)
    // The UTXO rule reports every input (and reference input) the set does not hold.
    val missingInputs = (body.inputs ++ body.referenceInputs).filterNot(state.utxo.contains).toSet
    lazy val spent: Vector[TxOut] = body.inputs.flatMap(state.utxo.get)
    lazy val refOuts: Vector[TxOut] = body.referenceInputs.flatMap(state.utxo.get)

    for
      // LEDGER runs CERTS and GOV before UTXOW, so their failures come first
      applied <-
        if tx.isValid then ledger(state, tx, env, refSize, deposits, refunds).map(Some(_))
        else Right(None)
      _ <- check(body.inputs.nonEmpty, PredicateFailure.utxo(UtxoFailure.InputSetEmpty))
      _ <- check(
        missingInputs.isEmpty,
        PredicateFailure.utxo(UtxoFailure.BadInputs(missingInputs))
      )
      overlap = body.inputs.toSet.intersect(body.referenceInputs.toSet)
      _ <- check(
        pv >= 11 || overlap.isEmpty,
        PredicateFailure.utxo(UtxoFailure.BabbageNonDisjointRefInputs(overlap.toVector.sorted))
      )
      _ <- check(
        body.ttl.forall(env.slot < _) && body.validityStart.forall(_ <= env.slot),
        PredicateFailure.utxo(
          UtxoFailure.OutsideValidityInterval(body.validityStart, body.ttl, env.slot)
        )
      )
      _ <- check(
        tx.size <= pp.maxTxSize,
        PredicateFailure.utxo(UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), pp.maxTxSize)))
      )
      _ <- check(
        body.fee >= minFee,
        PredicateFailure.utxo(UtxoFailure.FeeTooSmall(Mismatch(body.fee, minFee)))
      )
      _ <- check(
        body.networkId.forall(_ == env.network),
        PredicateFailure.utxo(
          UtxoFailure.WrongNetworkInTxBody(Mismatch(body.networkId.fold(0)(_.toInt), env.network))
        )
      )
      _ <- PreConwayRules
        .outputsOk(pp, env, body.outputs ++ body.collateralReturn.toVector)
        .leftMap(
          LedgerFailure.Rejected(id, _)
        )
      _ <- check(
        wits.totalExUnits.fits(pp.maxTxExUnits),
        PredicateFailure.utxo(
          UtxoFailure.ExUnitsTooBig(Mismatch(wits.totalExUnits, pp.maxTxExUnits))
        )
      )
      collateral <- PreConwayRules.collateralChecks(state, tx, env)
      scripts = PreConwayRules.providedScripts(wits, spent ++ refOuts)
      needed = PreConwayRules.scriptsNeeded(body, spent)
      referenced = (spent ++ refOuts).flatMap(_.scriptRef).map(_.hash).toSet
      neededNonRef = needed.keySet -- referenced
      received = wits.scripts.map(_.hash).toSet
      _ <- check(
        received.subsetOf(neededNonRef),
        PredicateFailure.utxow(UtxowFailure.ExtraneousScriptWitnesses(received -- neededNonRef))
      )
      _ <- check(
        neededNonRef.subsetOf(received),
        PredicateFailure.utxow(UtxowFailure.MissingScriptWitnesses(neededNonRef -- received))
      )
      _ <- check(
        needed.keySet.subsetOf(scripts.keySet),
        PredicateFailure.utxow(
          UtxowFailure.MissingScriptWitnesses(needed.keySet -- scripts.keySet)
        )
      )
      plutusPurposes = needed
        .collect { case (h, ps) if scripts(h).kind.isPlutus => ps }
        .flatten
        .toSet
      _ <- PreConwayRules.alonzoChecks(state, tx, spent, refOuts, scripts, plutusPurposes)
      _ <- check(
        tx.isValid || plutusPurposes.nonEmpty,
        PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, None)))
      )
      _ <- PreConwayRules.auxiliaryChecks(tx)
      _ <- PreConwayRules.witnessChecks(
        state,
        tx,
        spent,
        collateral,
        scripts,
        needed,
        vkeyHashes,
        env.verdicts
      )
      _ <- PreConwayRules.scriptsWellFormed(state, tx, env)
      withdrawn = body.withdrawals.map(_._2).sum
      consumed = spent.foldLeft(Value.zero)(_ + _.value) + Value.coin(withdrawn + refunds) +
        Value(0, body.mint)
      produced = body.outputs.foldLeft(Value.zero)(_ + _.value) +
        Value.coin(body.fee + deposits + body.donation)
      _ <- check(
        consumed == produced,
        PredicateFailure.utxo(UtxoFailure.ValueNotConserved(Mismatch(consumed, produced)))
      )
      plutusStats <- PreConwayRules.phase2(state, tx, env, scripts, spent, refOuts, plutusPurposes)
      result = applied match
        case Some(next) => next.copy(plutus = plutusStats)
        case None => PreConwayRules.applyInvalid(state, tx, collateral).copy(plutus = plutusStats)
    yield result

  /** The deposits a transaction pays and the refunds it collects, from the state before it. */
  def depositsAndRefunds(s: LedgerState, body: TxBody): (BigInt, BigInt) =
    val pp = s.params
    val newPools = body.certificates.collect {
      case Certificate.RegisterPool(p) if !s.pools.contains(p.operator) => p.operator
    }.toSet
    val keyDeposits = body.certificates.count {
      case Certificate.Register(_, _)             => true
      case Certificate.Delegate(_, _, _, Some(_)) => true
      case _                                      => false
    }
    val drepDeposits = body.certificates.count {
      case Certificate.RegisterDRep(_, _, _) => true
      case _                                 => false
    }
    val deposits = pp.keyDeposit * keyDeposits + pp.poolDeposit * newPools.size +
      pp.drepDeposit * drepDeposits + pp.govActionDeposit * body.proposals.size
    val (_, refunds) = body.certificates.foldLeft((Set.empty[Credential], BigInt(0))) {
      case ((regs, total), cert) =>
        cert match
          case Certificate.Register(c, _)             => (regs + c, total)
          case Certificate.Delegate(c, _, _, Some(_)) => (regs + c, total)
          case Certificate.Unregister(c, _) =>
            if regs.contains(c) then (regs - c, total + pp.keyDeposit)
            else (regs, total + s.accounts.get(c).fold(BigInt(0))(_.deposit))
          case Certificate.UnregisterDRep(_, refund) => (regs, total + refund)
          case _                                     => (regs, total)
    }
    (deposits, refunds)

  /** LEDGER for a phase-2 valid transaction: everything except phase 2. */
  private def ledger(
      state: LedgerState,
      tx: Tx,
      env: Env,
      refSize: Int,
      deposits: BigInt,
      refunds: BigInt
  ): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    def refuse(failure: PredicateFailure) = Left(LedgerFailure.Rejected(id, failure))
    def check(ok: Boolean, failure: => PredicateFailure): Result[Unit] =
      if ok then Right(()) else refuse(failure)
    // Key-hash reward accounts must be delegated to a DRep after the bootstrap phase; the check
    // looks at the state before the certificates.
    val notDelegated = body.withdrawals
      .collect {
        case (account, _) if account.credential.isInstanceOf[Credential.Key] =>
          account.credential.hash
      }
      .filterNot(h => state.accounts.get(Credential.Key(h)).exists(_.drep.isDefined))
    // withdrawalsThatDoNotDrainAccounts: accounts that do not exist (or sit on another network)
    // and accounts whose balance is not withdrawn entirely
    val missing = body.withdrawals.filter { case (a, _) =>
      a.network != env.network || !state.accounts.contains(a.credential)
    }
    val incomplete = body.withdrawals.collect {
      case (a, amount)
          if a.network == env.network && state.accounts
            .get(a.credential)
            .exists(_.balance != amount) =>
        a -> Mismatch(amount, state.accounts(a.credential).balance)
    }
    for
      _ <- check(
        body.currentTreasury.forall(_ == state.treasury),
        PredicateFailure.TreasuryValueMismatch(
          Mismatch(body.currentTreasury.getOrElse(state.treasury), state.treasury)
        )
      )
      _ <- check(
        refSize <= MaxRefScriptSizePerTx,
        PredicateFailure.TxRefScriptsSizeTooBig(Mismatch(refSize, MaxRefScriptSizePerTx))
      )
      _ <- check(
        pv(state) < 10 || notDelegated.isEmpty,
        PredicateFailure.WdrlNotDelegatedToDRep(notDelegated)
      )
      // From protocol version 11 the LEDGER rule checks the withdrawals; before, CERTS does so
      // after the certificates.
      _ <- check(
        pv(state) < 11 || missing.isEmpty,
        PredicateFailure.WithdrawalsMissingAccounts(missing.toMap)
      )
      _ <- check(
        pv(state) < 11 || incomplete.isEmpty,
        PredicateFailure.IncompleteWithdrawals(incomplete.toMap)
      )
      drained = body.withdrawals.foldLeft(state) { case (s, (account, _)) =>
        s.copy(accounts = s.accounts.updatedWith(account.credential)(_.map(_.copy(balance = 0))))
      }
      expiries = updateExpiries(drained, tx, env.epoch)
      afterCerts <- body.certificates.foldLeftM(expiries)((s, cert) =>
        ConwayCerts(s, cert, env.epoch, env.network, id)
      )
      _ <- check(
        pv(state) >= 11 || (missing.isEmpty && incomplete.isEmpty),
        PredicateFailure.Certs(
          CertsFailure.WithdrawalsNotInRewards(
            (missing.map { case (a, c) => a -> c } ++ incomplete.map { case (a, m) =>
              a -> m.supplied
            }).toMap
          )
        )
      )
      afterGov <- ConwayGov(afterCerts, tx, env)
    yield
      val created = body.outputs.zipWithIndex.map { case (out, i) => TxIn(id, i) -> out }
      afterGov.copy(
        utxo = afterGov.utxo -- body.inputs ++ created,
        fees = afterGov.fees + body.fee,
        deposited = afterGov.deposited + deposits - refunds,
        donation = afterGov.donation + body.donation
      )

  private def pv(s: LedgerState): BigInt = s.params.protocolMajor

  /** `updateDormantDRepExpiries` and `updateVotingDRepExpiries`. */
  private def updateExpiries(s: LedgerState, tx: Tx, epoch: BigInt): LedgerState =
    val body = tx.body
    val bumped =
      if body.proposals.nonEmpty && s.dormantEpochs != 0 then
        s.copy(
          dormantEpochs = 0,
          dreps = s.dreps.view.mapValues { d =>
            val actual = d.expiry + s.dormantEpochs
            if actual < epoch then d else d.copy(expiry = actual)
          }.toMap
        )
      else s
    val voters = body.votes.collect { case (Voter.DRepVoter(c), _) => c }
    voters.foldLeft(bumped) { (st, c) =>
      st.copy(dreps =
        st.dreps.updatedWith(c)(
          _.map(_.copy(expiry = epoch + st.params.drepActivity - st.dormantEpochs))
        )
      )
    }

/** CERTS: the Conway certificates (DELEG, POOL, GOVCERT). Deposits and refunds are accounted by the
  * caller from the state before the transaction.
  */
private[ledger] object ConwayCerts:
  import TxRules.Result

  def apply(
      s: LedgerState,
      cert: Certificate,
      epoch: BigInt,
      network: Int,
      id: Bytes
  ): Result[LedgerState] =
    val pp = s.params
    val bootstrap = pp.protocolMajor == 9
    def refuse(failure: PredicateFailure) = Left(LedgerFailure.Rejected(id, failure))
    def check(ok: Boolean, failure: => PredicateFailure): Result[Unit] =
      if ok then Right(()) else refuse(failure)

    /** Move `cred` from the reverse delegation set of `old` to that of `new` (both optional). */
    def redelegate(
        dreps: Map[Credential, DRepState],
        cred: Credential,
        old: Option[DRep],
        next: Option[DRep]
    ): Map[Credential, DRepState] =
      val removed = old match
        case Some(DRep.Registered(o)) if !next.contains(DRep.Registered(o)) =>
          dreps.updatedWith(o)(_.map(d => d.copy(delegs = d.delegs - cred)))
        case _ => dreps
      next match
        case Some(DRep.Registered(n)) =>
          if old.contains(DRep.Registered(n)) then removed
          else removed.updatedWith(n)(_.map(d => d.copy(delegs = d.delegs + cred)))
        case _ => removed

    def register(st: LedgerState, c: Credential, declared: Option[BigInt]): Result[LedgerState] =
      for
        _ <- check(
          !st.accounts.contains(c),
          PredicateFailure.deleg(DelegFailure.StakeKeyRegistered(c))
        )
        _ <- check(
          declared.forall(_ == pp.keyDeposit),
          if pp.protocolMajor > 10 then
            PredicateFailure.deleg(
              DelegFailure.DepositIncorrect(
                Mismatch(declared.getOrElse(pp.keyDeposit), pp.keyDeposit)
              )
            )
          else
            PredicateFailure.deleg(DelegFailure.IncorrectDeposit(declared.getOrElse(pp.keyDeposit)))
        )
      yield st.copy(accounts = st.accounts.updated(c, Account(0, pp.keyDeposit, None, None)))

    def delegate(
        st: LedgerState,
        c: Credential,
        pool: Option[Bytes],
        drep: Option[DRep],
        fresh: Boolean
    ): Result[LedgerState] =
      for
        a <- st.accounts
          .get(c)
          .toRight(
            LedgerFailure
              .Rejected(id, PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(c)))
          )
        _ <- pool.traverse_(p =>
          check(
            st.pools.contains(p),
            PredicateFailure.deleg(DelegFailure.DelegateeStakePoolNotRegistered(p))
          )
        )
        _ <- drep.traverse_ {
          case DRep.Registered(d) =>
            check(
              bootstrap || st.dreps.contains(d),
              PredicateFailure.deleg(DelegFailure.DelegateeDRepNotRegistered(d))
            )
          case _ => Right(())
        }
      yield
        // Before protocol version 10 a re-delegation does not remove the old reverse delegation.
        val dreps = drep.fold(st.dreps) { d =>
          val preserveIncorrect = pp.protocolMajor < 10
          d match
            case DRep.Registered(n) if fresh || preserveIncorrect =>
              st.dreps.updatedWith(n)(_.map(x => x.copy(delegs = x.delegs + c)))
            case _ if fresh => st.dreps
            case _          => redelegate(st.dreps, c, a.drep, Some(d))
        }
        st.copy(
          accounts = st.accounts.updated(
            c,
            a.copy(pool = pool.orElse(a.pool), drep = drep.orElse(a.drep))
          ),
          dreps = dreps
        )

    cert match
      case Certificate.Register(c, declared) => register(s, c, declared)
      case Certificate.Unregister(c, declared) =>
        for
          a <- s.accounts
            .get(c)
            .toRight(
              LedgerFailure
                .Rejected(id, PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(c)))
            )
          _ <- check(
            declared.forall(_ == a.deposit),
            if pp.protocolMajor > 10 then
              PredicateFailure.deleg(
                DelegFailure.RefundIncorrect(Mismatch(declared.getOrElse(a.deposit), a.deposit))
              )
            else
              PredicateFailure.deleg(DelegFailure.IncorrectDeposit(declared.getOrElse(a.deposit)))
          )
          _ <- check(
            a.balance == 0,
            PredicateFailure.deleg(DelegFailure.StakeKeyHasNonZeroAccountBalance(a.balance))
          )
        yield s.copy(
          accounts = s.accounts - c,
          dreps = redelegate(s.dreps, c, a.drep, None)
        )
      case Certificate.Delegate(c, pool, drep, deposit) =>
        deposit match
          case Some(d) =>
            for
              registered <- register(s, c, Some(d))
              // A fresh registration has no previous delegation to undo.
              out <- delegate(registered, c, pool, drep, fresh = true)
            yield out
          case None => delegate(s, c, pool, drep, fresh = false)
      case Certificate.RegisterPool(p) =>
        for
          _ <- check(
            p.cost >= pp.minPoolCost,
            PredicateFailure.pool(PoolFailure.StakePoolCostTooLow(Mismatch(p.cost, pp.minPoolCost)))
          )
          _ <- check(
            p.rewardAccount.network == network,
            PredicateFailure.pool(
              PoolFailure.WrongNetworkPool(Mismatch(p.rewardAccount.network, network), p.operator)
            )
          )
          _ <- check(
            pp.protocolMajor <= 10 || ConwayRules.vrfAvailable(s, p),
            PredicateFailure.pool(PoolFailure.VRFKeyHashAlreadyRegistered(p.operator, p.vrfKeyHash))
          )
        yield
          if s.pools.contains(p.operator) then
            s.copy(
              futurePools = s.futurePools.updated(p.operator, p),
              retiring = s.retiring - p.operator
            )
          else s.copy(pools = s.pools.updated(p.operator, PoolState(p, pp.poolDeposit)))
      case Certificate.RetirePool(pool, e) =>
        for
          _ <- check(
            s.pools.contains(pool),
            PredicateFailure.pool(PoolFailure.StakePoolNotRegisteredOnKey(pool))
          )
          _ <- check(
            epoch < e && e <= epoch + pp.eMax,
            PredicateFailure.pool(
              PoolFailure.StakePoolRetirementWrongEpoch(
                Mismatch(e, epoch),
                Mismatch(e, epoch + pp.eMax)
              )
            )
          )
        yield s.copy(retiring = s.retiring.updated(pool, e))
      case Certificate.RegisterDRep(c, deposit, anchor) =>
        for
          _ <- check(
            !s.dreps.contains(c),
            PredicateFailure.govCert(GovCertFailure.DRepAlreadyRegistered(c))
          )
          _ <- check(
            deposit == pp.drepDeposit,
            PredicateFailure.govCert(
              GovCertFailure.DRepIncorrectDeposit(Mismatch(deposit, pp.drepDeposit))
            )
          )
        yield
          // From protocol version 10 the expiry accounts for the dormant epochs.
          val expiry =
            if bootstrap then epoch + pp.drepActivity
            else epoch + pp.drepActivity - s.dormantEpochs
          s.copy(dreps = s.dreps.updated(c, DRepState(deposit, expiry, anchor)))
      case Certificate.UnregisterDRep(c, refund) =>
        for
          d <- s.dreps
            .get(c)
            .toRight(
              LedgerFailure
                .Rejected(id, PredicateFailure.govCert(GovCertFailure.DRepNotRegistered(c)))
            )
          _ <- check(
            refund == d.deposit,
            PredicateFailure.govCert(
              GovCertFailure.DRepIncorrectRefund(Mismatch(refund, d.deposit))
            )
          )
        yield s.copy(
          dreps = s.dreps - c,
          // The reverse index decides who loses their delegation, as in the ledger.
          accounts = d.delegs.foldLeft(s.accounts)((as, delegator) =>
            as.updatedWith(delegator)(_.map(_.copy(drep = None)))
          )
        )
      case Certificate.UpdateDRep(c, anchor) =>
        for d <- s.dreps
            .get(c)
            .toRight(
              LedgerFailure
                .Rejected(id, PredicateFailure.govCert(GovCertFailure.DRepNotRegistered(c)))
            )
        yield s.copy(dreps =
          s.dreps.updated(
            c,
            d.copy(expiry = epoch + pp.drepActivity - s.dormantEpochs, anchor = anchor)
          )
        )
      case Certificate.CommitteeAuthorize(cold, hot) =>
        committee(s, cold, CommitteeAuth.Hot(hot), id)
      case Certificate.CommitteeResign(cold, anchor) =>
        committee(s, cold, CommitteeAuth.Resigned(anchor), id)
      case Certificate.GenesisDelegate(_, _, _) | Certificate.MoveRewards(_, _) =>
        Left(LedgerFailure.Invalid(id, "DecoderError", "a pre-Conway certificate in Conway"))

  private def committee(
      s: LedgerState,
      cold: Credential,
      auth: CommitteeAuth,
      id: Bytes
  ): Result[LedgerState] =
    def refuse(failure: PredicateFailure) = Left(LedgerFailure.Rejected(id, failure))
    val resigned = s.committeeState.get(cold).exists {
      case CommitteeAuth.Resigned(_) => true
      case _                         => false
    }
    val current = s.gov.committee.exists(_.members.contains(cold))
    val future = s.gov.proposals.actions.exists { g =>
      g.action match
        case GovAction.UpdateCommittee(_, _, added, _) => added.contains(cold)
        case _                                         => false
    }
    if resigned then
      refuse(PredicateFailure.govCert(GovCertFailure.CommitteeHasPreviouslyResigned(cold)))
    else if !(current || future) then
      refuse(PredicateFailure.govCert(GovCertFailure.CommitteeIsUnknown(cold)))
    else Right(s.copy(committeeState = s.committeeState.updated(cold, auth)))

/** GOV: proposals and votes of a transaction. */
private[ledger] object ConwayGov:
  import TxRules.Result

  private def isBootstrapAction(a: GovAction): Boolean = a match
    case _: GovAction.ParameterChange | _: GovAction.HardFork | GovAction.Info => true
    case _                                                                     => false

  /** `ppuWellFormed`: no zero where zero is meaningless, and a non-empty update. */
  private def wellFormed(pp: ProtocolParams, u: ParamUpdate): Boolean =
    def value(key: Int): Option[BigInt] =
      u.fields
        .get(key)
        .flatMap(raw => xyz.kd5ujc.scarab.codec.Cbor.decode(raw).toOption)
        .flatMap(n => CborRead.uint(n, "value").toOption)
    val nonZero = Vector(2, 3, 4, 22, 23, 28, 29, 6, 30, 31) ++
      (if pp.protocolMajor == 9 then Vector.empty else Vector(17)) ++
      (if pp.protocolMajor >= 11 then Vector(8) else Vector.empty)
    u.fields.nonEmpty && nonZero.forall(k => value(k).forall(_ != 0))

  private def canFollow(prev: (BigInt, BigInt), next: (BigInt, BigInt)): Boolean =
    next == (prev._1 + 1, BigInt(0)) || next == (prev._1, prev._2 + 1)

  def apply(s: LedgerState, tx: Tx, env: TxRules.Env): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    val pp = s.params
    val epoch = env.epoch
    val bootstrap = pp.protocolMajor == 9
    def refuse(failure: PredicateFailure) = Left(LedgerFailure.Rejected(id, failure))
    def check(ok: Boolean, failure: => GovFailure): Result[Unit] =
      if ok then Right(()) else refuse(PredicateFailure.gov(failure))
    val guardrails = s.gov.constitution.script

    def addProposal(ps: Proposals, indexed: (Proposal, Int)): Result[Proposals] =
      val (proposal, idx) = indexed
      val gaid = GovActionId(id, idx)
      val action = proposal.action
      for
        _ <- check(
          !bootstrap || isBootstrapAction(action),
          GovFailure.DisallowedProposalDuringBootstrap(proposal)
        )
        _ <- action match
          case GovAction.HardFork(prev, major, minor) =>
            val reference: Option[(BigInt, BigInt)] =
              if prev == ps.roots.hardFork || major > pp.protocolMajor + 1 then
                Some(pp.protocolMajor -> pp.protocolMinor)
              else
                prev
                  .flatMap(ps.byId.get)
                  .flatMap(_.action match
                    case GovAction.HardFork(_, m, n) => Some(m -> n)
                    case _                           => None)
            check(
              reference.forall(canFollow(_, major -> minor)),
              GovFailure.ProposalCantFollow(
                prev,
                Mismatch(major -> minor, reference.getOrElse(pp.protocolMajor -> pp.protocolMinor))
              )
            )
          case _ => Right(())
        _ <- check(
          action match
            case GovAction.ParameterChange(_, u, _) => wellFormed(pp, u)
            case _                                  => true
          ,
          GovFailure.MalformedProposal(action)
        )
        _ <-
          if bootstrap then Right(())
          else
            check(
              s.accounts.contains(proposal.returnAccount.credential),
              GovFailure.ProposalReturnAccountDoesNotExist(proposal.returnAccount)
            ) *> (action match
              case GovAction.TreasuryWithdrawals(ws, _) =>
                val unknown = ws.map(_._1).filterNot(a => s.accounts.contains(a.credential))
                check(
                  unknown.isEmpty,
                  GovFailure.TreasuryWithdrawalReturnAccountsDoNotExist(unknown.distinct)
                )
              case _ => Right(()))
        _ <- check(
          proposal.deposit == pp.govActionDeposit,
          GovFailure.ProposalDepositIncorrect(Mismatch(proposal.deposit, pp.govActionDeposit))
        )
        _ <- check(
          proposal.returnAccount.network == env.network,
          GovFailure.ProposalProcedureNetworkIdMismatch(proposal.returnAccount, env.network)
        )
        _ <- action match
          case GovAction.TreasuryWithdrawals(ws, policy) =>
            check(
              ws.forall(_._1.network == env.network),
              GovFailure.TreasuryWithdrawalsNetworkIdMismatch(
                ws.map(_._1).filter(_.network != env.network).toSet,
                env.network
              )
            ) *> check(
              policy == guardrails,
              GovFailure.InvalidGuardrailsScriptHash(policy, guardrails)
            ) *>
              check(ws.map(_._2).sum != 0, GovFailure.ZeroTreasuryWithdrawals(action))
          case GovAction.UpdateCommittee(_, removed, added, _) =>
            check(
              added.keySet.intersect(removed).isEmpty,
              GovFailure.ConflictingCommitteeUpdate(added.keySet.intersect(removed))
            ) *> check(
              added.values.forall(_ > epoch),
              GovFailure.ExpirationEpochTooSmall(added.filter(_._2 <= epoch))
            )
          case GovAction.ParameterChange(_, _, policy) =>
            check(policy == guardrails, GovFailure.InvalidGuardrailsScriptHash(policy, guardrails))
          case _ => Right(())
        added <- ps
          .add(
            GovActionState(
              gaid,
              Map.empty,
              Map.empty,
              Map.empty,
              proposal,
              epoch,
              epoch + pp.govActionLifetime
            )
          )
          .toRight(
            LedgerFailure.Rejected(
              id,
              PredicateFailure.gov(GovFailure.InvalidPrevGovActionId(proposal))
            )
          )
      yield added

    val hotCredentials = s.committeeState.values.collect { case CommitteeAuth.Hot(h) => h }.toSet
    for
      _ <- check(
        pp.protocolMajor <= 10 || ConwayRules.unelectedCommitteeVoters(s, body).isEmpty,
        GovFailure.UnelectedCommitteeVoters(ConwayRules.unelectedCommitteeVoters(s, body))
      )
      proposals <- body.proposals.zipWithIndex.foldLeftM(s.gov.proposals)(addProposal)
      unknownVoters = body.votes.map(_._1).filterNot {
        case Voter.DRepVoter(c)    => s.dreps.contains(c)
        case Voter.Pool(h)         => s.pools.contains(h)
        case Voter.CommitteeHot(h) => hotCredentials(h)
      }
      _ <- check(unknownVoters.isEmpty, GovFailure.VotersDoNotExist(unknownVoters))
      votes = body.votes.flatMap { case (voter, vs) => vs.map { case (aid, v) => (voter, aid, v) } }
      _ <- votes.traverse_ { case (voter, aid, _) =>
        for
          gas <- proposals.byId
            .get(aid)
            .toRight(
              LedgerFailure.Rejected(
                id,
                PredicateFailure.gov(GovFailure.GovActionsDoNotExist(Vector(aid)))
              )
            )
          _ <- check(
            !bootstrap || (voter match
              case Voter.DRepVoter(_) => gas.action == GovAction.Info
              case _                  => isBootstrapAction(gas.action)),
            GovFailure.DisallowedVotesDuringBootstrap(Vector(voter -> aid))
          )
          _ <- check(
            epoch <= gas.expiresAfter,
            GovFailure.VotingOnExpiredGovAction(Vector(voter -> aid))
          )
          _ <- check(
            voter match
              case Voter.CommitteeHot(_) => Ratify.isCommitteeVotingAllowed(gas.action)
              case Voter.DRepVoter(_)    => Ratify.isDRepVotingAllowed(gas.action)
              case Voter.Pool(_)         => Ratify.isPoolVotingAllowed(gas.action)
            ,
            GovFailure.DisallowedVoters(Vector(voter -> aid))
          )
        yield ()
      }
    yield
      val voted = votes.foldLeft(proposals) { case (ps, (voter, aid, v)) => ps.vote(voter, v, aid) }
      val unregistered = body.certificates.collect { case Certificate.UnregisterDRep(c, _) =>
        c
      }.toSet
      val cleaned =
        if unregistered.isEmpty then voted
        else
          voted.copy(actions =
            voted.actions.map(g => g.copy(drepVotes = g.drepVotes -- unregistered))
          )
      s.copy(gov = s.gov.copy(proposals = cleaned))

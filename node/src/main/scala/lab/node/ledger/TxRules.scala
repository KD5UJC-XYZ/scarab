// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.Bytes
import lab.witness.{StrictEd25519, VerificationResult}

/** Why the ledger refused a transaction. `Unsupported` marks a valid-chain feature this node does
  * not implement yet; `Invalid` is a rule violation.
  */
enum LedgerFailure:
  case Unsupported(txId: Bytes, feature: String)
  case Invalid(txId: Bytes, rule: String, detail: String)
  case Malformed(detail: String)

  def message: String = this match
    case Unsupported(id, f) => s"tx ${id.hex.take(16)} uses unsupported $f"
    case Invalid(id, r, d)  => s"tx ${id.hex.take(16)} violates $r: $d"
    case Malformed(d)       => s"malformed transaction: $d"

/** Conway UTXOW/UTXO/CERTS rules for transactions without scripts or governance actions. */
object TxRules:
  type Result[A] = Either[LedgerFailure, A]

  final case class Env(slot: BigInt, epoch: BigInt, network: Int)

  def apply(state: LedgerState, tx: Tx, env: Env): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    def invalid(rule: String, detail: String) = Left(LedgerFailure.Invalid(id, rule, detail))
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else invalid(rule, detail)
    def unsupported(ok: Boolean, feature: String): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Unsupported(id, feature))
    val pp = state.params

    for
      _ <- unsupported(tx.isValid, "phase-2 invalid transactions")
      _ <- unsupported(!tx.witnesses.hasScripts && !body.hasScriptData, "scripts")
      _ <- unsupported(!tx.witnesses.hasBootstrap, "bootstrap witnesses")
      _ <- unsupported(!body.hasGovernance, "governance procedures")
      _ <- unsupported(body.donation == 0, "treasury donations")
      _ <- unsupported(body.mint.isEmpty, "minting")
      _ <- check(body.inputs.nonEmpty, "InputSetEmpty", "no inputs")
      spent <- body.inputs.traverse(i =>
        state.utxo.get(i).toRight(LedgerFailure.Invalid(id, "BadInputs", i.render))
      )
      _ <- body.referenceInputs.traverse_(i =>
        check(state.utxo.contains(i), "BadInputs", s"reference ${i.render}")
      )
      _ <- check(
        body.ttl.forall(env.slot < _) && body.validityStart.forall(_ <= env.slot),
        "OutsideValidityInterval",
        s"slot ${env.slot} not in [${body.validityStart}, ${body.ttl})"
      )
      _ <- check(tx.size <= pp.maxTxSize, "MaxTxSize", s"${tx.size} > ${pp.maxTxSize}")
      minFee = pp.minFeeA * tx.size + pp.minFeeB
      _ <- check(body.fee >= minFee, "FeeTooSmall", s"${body.fee} < $minFee")
      _ <- check(
        body.networkId.forall(_ == env.network),
        "WrongNetworkInTxBody",
        s"${body.networkId}"
      )
      _ <- body.outputs.traverse_(out =>
        val min = (160 + out.raw.size) * pp.coinsPerUTxOByte
        check(out.value.coin >= min, "BabbageOutputTooSmallUTxO", s"${out.value.coin} < $min") *>
          check(
            out.address.kind match
              case Address.Kind.Shelley(n, _, _, _) => n == env.network
              case Address.Kind.Byron               => true
            ,
            "WrongNetwork",
            out.address.raw.hex
          ) *>
          check(out.raw.size <= pp.maxValueSize + 64, "OutputTooBig", s"${out.raw.size}")
      )
      afterCerts <- body.certificates.foldLeftM(CertOutcome(state, 0, 0, Set.empty)) {
        (acc, cert) =>
          Certs.apply(acc, cert, env.epoch, id)
      }
      withdrawn <- body.withdrawals.foldLeftM((afterCerts.state, BigInt(0), Set.empty[Bytes])) {
        case ((s, total, keys), (account, amount)) =>
          for
            _ <- check(
              account.network == env.network,
              "WrongNetworkWithdrawal",
              account.credential.render
            )
            a <- s.accounts
              .get(account.credential)
              .toRight(
                LedgerFailure.Invalid(id, "WithdrawalNotRegistered", account.credential.render)
              )
            _ <- check(
              a.balance == amount,
              "IncorrectWithdrawal",
              s"${account.credential.render}: $amount != ${a.balance}"
            )
            _ <- check(
              pp.protocolMajor < 10 || (account.credential match
                case Credential.Key(_) => a.drep.isDefined
                case _                 => true),
              "WithdrawalNotDelegatedToDRep",
              account.credential.render
            )
            _ <- unsupported(account.credential.isInstanceOf[Credential.Key], "script withdrawals")
          yield (
            s.copy(accounts = s.accounts.updated(account.credential, a.copy(balance = 0))),
            total + amount,
            keys + account.credential.hash
          )
      }
      (afterWithdrawals, withdrawals, withdrawalKeys) = withdrawn
      consumed = spent.foldLeft(Value.zero)(_ + _.value) + Value.coin(
        withdrawals + afterCerts.refunds
      )
      produced = body.outputs.foldLeft(Value.zero)(_ + _.value) + Value.coin(
        body.fee + afterCerts.deposits
      )
      _ <- check(
        consumed == produced,
        "ValueNotConservedUTxO",
        s"consumed $consumed, produced $produced"
      )
      spendKeys <- spent.traverse(out =>
        out.address.kind match
          case Address.Kind.Shelley(_, Credential.Key(h), _, _) => Right(h)
          case Address.Kind.Shelley(_, Credential.Script(_), _, _) =>
            Left(LedgerFailure.Unsupported(id, "script inputs"))
          case Address.Kind.Byron => Left(LedgerFailure.Unsupported(id, "Byron inputs"))
      )
      required = spendKeys.toSet ++ withdrawalKeys ++ afterCerts.witnesses ++ body.requiredSigners
      provided <- tx.witnesses.vkeys.traverse { case (vkey, signature) =>
        StrictEd25519.verify(vkey, signature, id) match
          case Right(VerificationResult.SignatureVerified) => Right(Blake2b.hash224.hash(vkey))
          case _ => invalid("InvalidWitnessesUTXOW", vkey.hex)
      }
      missing = required -- provided
      _ <- check(missing.isEmpty, "MissingVKeyWitnessesUTXOW", missing.map(_.hex).mkString(","))
    yield
      val created = body.outputs.zipWithIndex.map { case (out, i) => TxIn(id, i) -> out }
      afterWithdrawals.copy(
        utxo = afterWithdrawals.utxo -- body.inputs ++ created,
        fees = afterWithdrawals.fees + body.fee
      )

  /** Running result of applying a transaction's certificates in order. */
  final case class CertOutcome(
      state: LedgerState,
      deposits: BigInt,
      refunds: BigInt,
      witnesses: Set[Bytes]
  )

private[ledger] object Certs:
  import TxRules.{CertOutcome, Result}

  def apply(acc: CertOutcome, cert: Certificate, epoch: BigInt, id: Bytes): Result[CertOutcome] =
    val s = acc.state
    val pp = s.params
    def invalid(rule: String, detail: String) = Left(LedgerFailure.Invalid(id, rule, detail))
    def check(ok: Boolean, rule: String, detail: => String): Result[Unit] =
      if ok then Right(()) else invalid(rule, detail)
    def keyOf(c: Credential): Result[Bytes] = c match
      case Credential.Key(h) => Right(h)
      case Credential.Script(_) =>
        Left(LedgerFailure.Unsupported(id, "script-credential certificates"))
    def register(c: Credential, declared: Option[BigInt]): Result[CertOutcome] =
      for
        _ <- check(!s.accounts.contains(c), "StakeKeyRegistered", c.render)
        _ <- check(declared.forall(_ == pp.keyDeposit), "IncorrectDepositDELEG", s"$declared")
        // Only the deposit-carrying Conway certificate requires a witness.
        w <- declared.traverse(_ => keyOf(c))
      yield CertOutcome(
        s.copy(
          accounts = s.accounts.updated(c, Account(0, pp.keyDeposit, None, None)),
          deposited = s.deposited + pp.keyDeposit
        ),
        acc.deposits + pp.keyDeposit,
        acc.refunds,
        acc.witnesses ++ w
      )
    def delegate(
        o: CertOutcome,
        c: Credential,
        pool: Option[Bytes],
        drep: Option[DRep]
    ): Result[CertOutcome] =
      val st = o.state
      for
        a <- st.accounts
          .get(c)
          .toRight(LedgerFailure.Invalid(id, "StakeDelegationImpossible", c.render))
        _ <- pool.traverse_(p =>
          check(st.pools.contains(p), "DelegateeStakePoolNotRegistered", p.hex)
        )
        _ <- drep.traverse_ {
          case DRep.Registered(d) =>
            check(st.dreps.contains(d), "DelegateeDRepNotRegistered", d.render)
          case _ => Right(())
        }
        w <- keyOf(c)
      yield o.copy(
        state = st.copy(accounts =
          st.accounts.updated(c, a.copy(pool = pool.orElse(a.pool), drep = drep.orElse(a.drep)))
        ),
        witnesses = o.witnesses + w
      )

    cert match
      case Certificate.Register(c, declared) => register(c, declared)
      case Certificate.Unregister(c, declared) =>
        for
          a <- s.accounts
            .get(c)
            .toRight(LedgerFailure.Invalid(id, "StakeKeyNotRegistered", c.render))
          _ <- check(a.balance == 0, "StakeKeyNonZeroAccountBalance", s"${a.balance}")
          _ <- check(declared.forall(_ == a.deposit), "RefundIncorrectDELEG", s"$declared")
          w <- keyOf(c)
        yield CertOutcome(
          s.copy(accounts = s.accounts - c, deposited = s.deposited - a.deposit),
          acc.deposits,
          acc.refunds + a.deposit,
          acc.witnesses + w
        )
      case Certificate.Delegate(c, pool, drep, deposit) =>
        deposit
          .fold(Right(acc): Result[CertOutcome])(d => register(c, Some(d)))
          .flatMap(delegate(_, c, pool, drep))
      case Certificate.RegisterPool(p) =>
        val witnesses = acc.witnesses + p.operator ++ p.owners
        for
          _ <- check(p.cost >= pp.minPoolCost, "StakePoolCostTooLow", s"${p.cost}")
          _ <- check(
            p.rewardAccount.network == s.pools.headOption
              .fold(p.rewardAccount.network)(_._2.params.rewardAccount.network),
            "WrongNetworkPOOL",
            p.operator.hex
          )
        yield
          if s.pools.contains(p.operator) then
            acc.copy(
              state = s.copy(
                futurePools = s.futurePools.updated(p.operator, p),
                retiring = s.retiring - p.operator
              ),
              witnesses = witnesses
            )
          else
            CertOutcome(
              s.copy(
                pools = s.pools.updated(p.operator, PoolState(p, pp.poolDeposit)),
                deposited = s.deposited + pp.poolDeposit
              ),
              acc.deposits + pp.poolDeposit,
              acc.refunds,
              witnesses
            )
      case Certificate.RetirePool(pool, e) =>
        for
          _ <- check(s.pools.contains(pool), "StakePoolNotRegisteredOnKey", pool.hex)
          _ <- check(epoch < e && e <= epoch + pp.eMax, "StakePoolRetirementWrongEpoch", s"$e")
        yield acc.copy(
          state = s.copy(retiring = s.retiring.updated(pool, e)),
          witnesses = acc.witnesses + pool
        )
      case Certificate.RegisterDRep(c, deposit) =>
        for
          _ <- check(!s.dreps.contains(c), "ConwayDRepAlreadyRegistered", c.render)
          _ <- check(deposit == pp.drepDeposit, "ConwayDRepIncorrectDeposit", s"$deposit")
          w <- keyOf(c)
        yield CertOutcome(
          s.copy(
            dreps =
              s.dreps.updated(c, DRepState(deposit, epoch + pp.drepActivity - s.dormantEpochs)),
            deposited = s.deposited + deposit
          ),
          acc.deposits + deposit,
          acc.refunds,
          acc.witnesses + w
        )
      case Certificate.UnregisterDRep(c, refund) =>
        for
          d <- s.dreps
            .get(c)
            .toRight(LedgerFailure.Invalid(id, "ConwayDRepNotRegistered", c.render))
          _ <- check(refund == d.deposit, "ConwayDRepIncorrectRefund", s"$refund")
          w <- keyOf(c)
        yield CertOutcome(
          s.copy(
            dreps = s.dreps - c,
            deposited = s.deposited - d.deposit,
            accounts = s.accounts.map { case (k, a) =>
              k -> (if a.drep.contains(DRep.Registered(c)) then a.copy(drep = None) else a)
            }
          ),
          acc.deposits,
          acc.refunds + d.deposit,
          acc.witnesses + w
        )
      case Certificate.UpdateDRep(c) =>
        for
          d <- s.dreps
            .get(c)
            .toRight(LedgerFailure.Invalid(id, "ConwayDRepNotRegistered", c.render))
          w <- keyOf(c)
        yield acc.copy(
          state = s.copy(dreps =
            s.dreps.updated(c, d.copy(expiry = epoch + pp.drepActivity - s.dormantEpochs))
          ),
          witnesses = acc.witnesses + w
        )
      case Certificate.CommitteeAuthorize(_, _) | Certificate.CommitteeResign(_) =>
        Left(LedgerFailure.Unsupported(id, "committee certificates"))

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Certificate,
  ConwayRules,
  LedgerFailure,
  Mismatch,
  PlutusStats,
  PreConwayRules,
  PredicateFailure,
  ProtocolParams,
  TxBody,
  TxIn,
  TxOut,
  TxRules,
  UtxoFailure,
  UtxosFailure,
  UtxowFailure,
  Value
}
import xyz.kd5ujc.scarab.ledger.state.*

// UTXOW, UTXO and UTXOS for Conway (`Conway/Rules/Utxow.hs`, `Utxo.hs`, `Utxos.hs`, with the Alonzo
// and Babbage rules they share). They run on the UTxO state (`UTxOState`): the UTxO set, the
// deposits and fees and donations, and the instant stake the tick reads (SNAP and the DRep pulser
// take it as the stake of the UTxO). The checks themselves are the node's pure predicates, the
// ones the old rules use (`PreConwayRules`), so the two sides refuse a transaction with the same
// failure; what this file adds is the state: the instant stake kept as the set changes.

/** `UtxoEnv` (`Shelley/Rules/Utxo.hs:107`): the block-level context (the slot), the parameters in
  * force, and the certificate state *before* the transaction's certificates (the refunds of
  * deregistrations are the deposits its accounts hold, which CERTS has already removed from the
  * state that follows, `Ledger.hs:410-415`).
  */
final case class UtxoEnv(ctx: TxRules.Env, pp: ProtocolParams, cert: ConwayCertState)

/** What the instant stake of the UTxO becomes as outputs are created and spent
  * (`addConwayInstantStake`, `deleteConwayInstantStake`, `Conway/State/Stake.hs:76-108`): the
  * lovelace of an output with a stake credential is added to (or taken from) the credential's
  * entry, and an entry that reaches zero is removed. An output of an address with no stake
  * credential (an enterprise address, a pointer address, a Byron address) is not in it.
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
          next <- CompactCoin.fromBigInt(
            stake.get(c).fold(BigInt(0))(_.toBigInt) + sign * out.value.coin
          )
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

/** The deposits a transaction pays and the refunds it collects, from the certificate state before
  * it (`certsTotalDepositsTxBody`, `certsTotalRefundsTxBody`, `Conway/TxCert.hs`).
  */
object Deposits:
  def of(pp: ProtocolParams, cert: ConwayCertState, body: TxBody): (BigInt, BigInt) =
    val pools = CertLens.pools.get(cert)
    val accounts = CertLens.accounts.get(cert)
    val newPools = body.certificates.collect {
      case Certificate.RegisterPool(p)
          if !Convert.keyHash[StakePool](p.operator).toOption.exists(pools.contains) =>
        p.operator
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
    val (_, refunds) =
      body.certificates.foldLeft((Set.empty[xyz.kd5ujc.scarab.ledger.Credential], BigInt(0))) {
        case ((regs, total), cert) =>
          cert match
            case Certificate.Register(c, _)             => (regs + c, total)
            case Certificate.Delegate(c, _, _, Some(_)) => (regs + c, total)
            case Certificate.Unregister(c, _) =>
              if regs.contains(c) then (regs - c, total + pp.keyDeposit)
              else
                (
                  regs,
                  total + Convert
                    .credential[Staking](c)
                    .toOption
                    .flatMap(accounts.get)
                    .fold(BigInt(0))(_.deposit.toBigInt)
                )
            case Certificate.UnregisterDRep(_, refund) => (regs, total + refund)
            case _                                     => (regs, total)
      }
    (deposits, refunds)

/** UTXOS (`Conway/Rules/Utxos.hs:187`, `utxosTransition`): the evaluation of the Plutus scripts,
  * whose verdict must equal the block's `is_valid` flag (a transaction flagged invalid must have a
  * script that fails, one flagged valid none). The state is the node's counters of what was
  * evaluated.
  */
object Utxos extends Sts[UtxoEnv, PlutusStats, StAnnTx, LedgerFailure]:
  def transition[F[_]: Monad](env: UtxoEnv, ann: StAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, PlutusStats],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val tx = ann.tx
    for
      counters <- st.get
      next <- Sts.lift[F, LedgerFailure, PlutusStats](
        PreConwayRules.phase2(
          env.pp,
          counters,
          tx,
          env.ctx,
          ann.scripts,
          ann.spent,
          ann.referenced,
          ann.plutusPurposes
        )
      )
      _ <- st.set(next)
    yield ()

/** UTXO (`Conway/Rules/Utxo.hs:239`, `conwayUtxoTransition`, with `babbageUtxoValidation`): the
  * validity of the transaction against the parameters and the UTxO set, UTXOS, and then the change
  * of the state (`updateUTxOState`): the set, the instant stake, the fees, the deposits and the
  * donation of a valid transaction, the collateral of one that failed phase 2.
  */
object Utxo extends Sts[UtxoEnv, Counted[UTxOState[Conway]], StAnnTx, LedgerFailure]:
  given Embed[PlutusStats, Counted[UTxOState[Conway]], LedgerFailure, LedgerFailure] =
    Embed(Counted.plutus[UTxOState[Conway]], identity)

  /** The inputs of the transaction exist (`InputSetEmptyUTxO`, `BadInputsUTxO`): what annotating
    * the transaction with its outputs needs, so UTXOW makes the same check first.
    */
  def resolvable(ann: StAnnTx): Either[LedgerFailure, Unit] =
    val body = ann.tx.body
    def refuse(f: UtxoFailure) = Left(LedgerFailure.Rejected(ann.id, PredicateFailure.utxo(f)))
    val missing = (body.inputs ++ body.referenceInputs).filterNot(ann.utxo.contains).toSet
    if body.inputs.isEmpty then refuse(UtxoFailure.InputSetEmpty)
    else if missing.nonEmpty then refuse(UtxoFailure.BadInputs(missing))
    else Right(())

  /** The checks of UTXO the node makes of a transaction, in the node's order, and the collateral of
    * the transaction (`PreConwayRules.Collateral`).
    */
  def validate(
      env: UtxoEnv,
      ann: StAnnTx,
      deposits: BigInt,
      refunds: BigInt
  ): Either[LedgerFailure, PreConwayRules.Collateral] =
    val tx = ann.tx
    val body = tx.body
    val id = ann.id
    val pp = env.pp
    val ctx = env.ctx
    val wits = tx.witnesses
    def refuse(f: UtxoFailure) = Left(LedgerFailure.Rejected(id, PredicateFailure.utxo(f)))
    def check(ok: Boolean, f: => UtxoFailure): Either[LedgerFailure, Unit] =
      if ok then Right(()) else refuse(f)
    val scriptFee =
      (pp.priceMem * Rational(wits.totalExUnits.mem) + pp.priceSteps * Rational(
        wits.totalExUnits.steps
      )).ceil
    val minFee =
      pp.minFeeA * tx.size + pp.minFeeB + scriptFee +
        ConwayRules.tierRefScriptFee(
          pp.minFeeRefScriptCostPerByte,
          ConwayRules.refScriptsSize(ann.utxo, body)
        )
    val overlap = body.inputs.toSet.intersect(body.referenceInputs.toSet)
    for
      _ <- check(
        pp.protocolMajor >= 11 || overlap.isEmpty,
        UtxoFailure.BabbageNonDisjointRefInputs(overlap.toVector.sorted)
      )
      _ <- check(
        body.ttl.forall(ctx.slot < _) && body.validityStart.forall(_ <= ctx.slot),
        UtxoFailure.OutsideValidityInterval(body.validityStart, body.ttl, ctx.slot)
      )
      _ <- check(
        tx.size <= pp.maxTxSize,
        UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), pp.maxTxSize))
      )
      _ <- check(body.fee >= minFee, UtxoFailure.FeeTooSmall(Mismatch(body.fee, minFee)))
      _ <- check(
        body.networkId.forall(_ == ctx.network),
        UtxoFailure.WrongNetworkInTxBody(Mismatch(body.networkId.fold(0)(_.toInt), ctx.network))
      )
      _ <- PreConwayRules
        .outputsOk(pp, ctx, body.outputs ++ body.collateralReturn.toVector)
        .leftMap(LedgerFailure.Rejected(id, _))
      _ <- check(
        wits.totalExUnits.fits(pp.maxTxExUnits),
        UtxoFailure.ExUnitsTooBig(Mismatch(wits.totalExUnits, pp.maxTxExUnits))
      )
      collateral <- PreConwayRules.collateralChecks(ann.utxo, pp, tx)
      withdrawn = body.withdrawals.map(_._2).sum
      consumed = ann.spent.foldLeft(Value.zero)(_ + _.value) + Value.coin(withdrawn + refunds) +
        Value(0, body.mint)
      produced = body.outputs.foldLeft(Value.zero)(_ + _.value) +
        Value.coin(body.fee + deposits + body.donation)
      _ <- check(consumed == produced, UtxoFailure.ValueNotConserved(Mismatch(consumed, produced)))
    yield collateral

  /** `updateUTxOState` and `updateTreasuryDonation` (`Babbage/Rules/Utxo.hs:449`,
    * `Conway/Rules/Utxo.hs:200`).
    */
  def update(
      s: UTxOState[Conway],
      ann: StAnnTx,
      collateral: PreConwayRules.Collateral,
      deposits: BigInt,
      refunds: BigInt
  ): Either[VocabError, UTxOState[Conway]] =
    val tx = ann.tx
    val body = tx.body
    val id = ann.id
    val utxo = s.utxo.utxo
    if tx.isValid then
      val created = body.outputs.zipWithIndex.map { (out, i) => TxIn(id, i) -> out }
      for
        stake <- InstantStakeOps.update(s.instantStake, body.outputs, ann.spent)
        deposited <- Coin.fromBigInt(s.deposited.value + deposits - refunds)
        fees <- Coin.fromBigInt(s.fees.value + body.fee)
        donation <- Coin.fromBigInt(s.donation.value + body.donation)
      yield s.copy(
        utxo = UtxoTables(utxo -- body.inputs ++ created),
        deposited = deposited,
        fees = fees,
        instantStake = stake,
        donation = donation
      )
    else
      val returned = body.collateralReturn.map(r => TxIn(id, body.outputs.size) -> r)
      for
        stake <- InstantStakeOps.update(
          s.instantStake,
          returned.map(_._2),
          body.collateral.flatMap(utxo.get)
        )
        fees <- Coin.fromBigInt(s.fees.value + collateral.fee)
      yield s.copy(
        utxo = UtxoTables(utxo -- body.collateral ++ returned),
        fees = fees,
        instantStake = stake
      )

  def transition[F[_]: Monad](env: UtxoEnv, ann: StAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[Conway]]],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val (deposits, refunds) = Deposits.of(env.pp, env.cert, ann.tx.body)
    for
      collateral <- Sts.lift[F, LedgerFailure, PreConwayRules.Collateral](
        validate(env, ann, deposits, refunds)
      )
      _ <- Sts.trans[F, UtxoEnv, PlutusStats, Counted[
        UTxOState[Conway]
      ], StAnnTx, LedgerFailure, LedgerFailure](
        Utxos,
        env,
        ann
      )
      s <- st.get
      next <- Refuse.vocab[F, UTxOState[Conway]](
        update(s.state, ann, collateral, deposits, refunds)
      )
      _ <- st.set(s.copy(state = next))
    yield ()

/** UTXOW (`Conway/Rules/Utxow.hs:222`, `conwayUtxowTransition`, with the Alonzo and Babbage rules
  * it extends): the scripts the transaction provides are the ones it needs, its datums and
  * redeemers match the Plutus scripts, the script integrity hash, the auxiliary data, and the
  * signatures; then UTXO.
  *
  * The two checks of UTXO that annotating the transaction depends on (inputs exist, `StAnnTx`) are
  * made first ([[Utxo.resolvable]]), as Haskell's ledger does before it hands the annotated
  * transaction to the rule.
  */
object Utxow extends Sts[UtxoEnv, Counted[UTxOState[Conway]], StAnnTx, LedgerFailure]:
  /** The checks of the witnesses and scripts, in the node's order. */
  def witnesses(env: UtxoEnv, ann: StAnnTx): Either[LedgerFailure, Unit] =
    val tx = ann.tx
    val body = tx.body
    val id = ann.id
    val pp = env.pp
    val wits = tx.witnesses
    def check(ok: Boolean, f: => UtxowFailure): Either[LedgerFailure, Unit] =
      if ok then Right(())
      else Left(LedgerFailure.Rejected(id, PredicateFailure.utxow(f)))
    def check0(ok: Boolean, f: => PredicateFailure): Either[LedgerFailure, Unit] =
      if ok then Right(()) else Left(LedgerFailure.Rejected(id, f))
    val scripts = ann.scripts
    val needed = ann.needed
    val referenced = (ann.spent ++ ann.referenced).flatMap(_.scriptRef).map(_.hash).toSet
    val neededNonRef = needed.keySet -- referenced
    val received = wits.scripts.map(_.hash).toSet
    // the outputs the collateral inputs hold, whose key witnesses the transaction needs
    val collateral = PreConwayRules.Collateral(
      if pp.protocolMajor < 5 || wits.redeemers.isEmpty then Vector.empty
      else body.collateral.flatMap(ann.utxo.get),
      BigInt(0)
    )
    for
      _ <- check(
        received.subsetOf(neededNonRef),
        UtxowFailure.ExtraneousScriptWitnesses(received -- neededNonRef)
      )
      _ <- check(
        neededNonRef.subsetOf(received),
        UtxowFailure.MissingScriptWitnesses(neededNonRef -- received)
      )
      _ <- check(
        needed.keySet.subsetOf(scripts.keySet),
        UtxowFailure.MissingScriptWitnesses(needed.keySet -- scripts.keySet)
      )
      _ <- PreConwayRules.alonzoChecks(pp, tx, ann.spent, scripts, ann.plutusPurposes)
      // a transaction flagged invalid has a Plutus script to fail (`validationTagMismatch`, which
      // Haskell's UTXOS makes; the node makes it here, where its own rules do)
      _ <- check0(
        tx.isValid || ann.plutusPurposes.nonEmpty,
        PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, None)))
      )
      _ <- PreConwayRules.auxiliaryChecks(tx)
      _ <- PreConwayRules.witnessChecks(
        Map.empty,
        tx,
        ann.spent,
        collateral,
        scripts,
        needed,
        wits.vkeys.map(w => env.ctx.verdicts.keyHash(w._1)),
        env.ctx.verdicts
      )
      _ <- PreConwayRules.scriptsWellFormed(pp, tx, env.ctx)
    yield ()

  def transition[F[_]: Monad](env: UtxoEnv, ann: StAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[Conway]]],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    for
      _ <- Sts.lift[F, LedgerFailure, Unit](Utxo.resolvable(ann))
      _ <- Sts.lift[F, LedgerFailure, Unit](witnesses(env, ann))
      _ <- Utxo.transition[F](env, ann)
    yield ()

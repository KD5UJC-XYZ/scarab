// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Certificate,
  RefScripts,
  GenDelegPair,
  LedgerFailure,
  Mismatch,
  PlutusStats,
  TxChecks,
  PredicateFailure,
  ProtocolParams,
  TxBody,
  TxIn,
  TxOut,
  TxEnv,
  UtxoFailure,
  UtxosFailure,
  UtxowFailure,
  Value
}
import xyz.kd5ujc.scarab.ledger.byron.ByronAddress
import xyz.kd5ujc.scarab.ledger.state.*

// UTXOW, UTXO and UTXOS for Conway (`Conway/Rules/Utxow.hs`, `Utxo.hs`, `Utxos.hs`, with the Alonzo
// and Babbage rules they share). They run on the UTxO state (`UTxOState`): the UTxO set, the
// deposits and fees and donations, and the instant stake the tick reads (SNAP and the DRep pulser
// take it as the stake of the UTxO). The shared pure predicates live in `TxChecks`; these rules
// sequence them and maintain the UTxO state, including the instant stake as the set changes.

/** `UtxoEnv` (`Shelley/Rules/Utxo.hs:107`): the block-level context (the slot), the parameters in
  * force, and the certificate state *before* the transaction's certificates (the refunds of
  * deregistrations are the deposits its accounts hold, which CERTS has already removed from the
  * state that follows, `Conway/Rules/Ledger.hs:410-415`). The certificate state is the era's
  * (`UtxoEnv era` holds `CertState era`): Conway's here, [[ShelleyCertState]] for the eras before.
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

/** UTXOS (`Conway/Rules/Utxos.hs:207`, `utxosTransition`, cardano-ledger `f649f975`): the
  * evaluation of the Plutus scripts, whose verdict must equal the block's `is_valid` flag (a
  * transaction flagged invalid must have a script that fails, one flagged valid none). The state is
  * the node's counters of what was evaluated.
  */
object Utxos extends JudgedSts[UtxoEnv[Any], PlutusStats, StAnnTx, PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: UtxoEnv[Any], ann: StAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, PlutusStats],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] = scripts[F](env, ann)

  /** `expectScriptsToPass` (`Babbage/Rules/Utxos.hs:132-155`) and `babbageEvalScriptsTxInvalid`
    * (`Babbage/Rules/Utxos.hs:194-220`): the contexts of the scripts are collected, and the scripts
    * are evaluated only if nothing failed so far in the judgment (`whenFailureFree`).
    */
  def scripts[F[_]: Monad](env: UtxoEnv[Any], ann: StAnnTx)(using
      st: Stateful[F, PlutusStats],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val tx = ann.tx
    for
      failing <- judge.failing
      counters <- st.get
      next <- Sts.lift[F, LedgerFailure, PlutusStats](
        TxChecks.phase2(
          env.pp,
          counters,
          tx,
          env.ctx,
          ann.scripts,
          ann.spent,
          ann.referenced,
          ann.plutusPurposes,
          evaluate = !failing
        )
      )
      _ <- st.set(next)
    yield ()

/** UTXO (`Conway/Rules/Utxo.hs:211`, `conwayUtxoTransition`, with `babbageUtxoValidation`,
  * `Babbage/Rules/Utxo.hs:325`): the validity of the transaction against the parameters and the
  * UTxO set, UTXOS, and then the change of the state (`updateUTxOState`): the set, the instant
  * stake, the fees, the deposits and the donation of a valid transaction, the collateral of one
  * that failed phase 2.
  */
object Utxo
    extends JudgedSts[UtxoEnv[ConwayCertState], Counted[
      UTxOState[Conway]
    ], StAnnTx, PredicateFailure, LedgerFailure]:
  given Embed[PlutusStats, Counted[UTxOState[Conway]], LedgerFailure, LedgerFailure] =
    Embed(Counted.plutus[UTxOState[Conway]], identity)

  /** The inputs of the transaction exist (`InputSetEmptyUTxO`, `BadInputsUTxO`): what annotating
    * the transaction with its outputs needs, so UTXOW makes the same check first. The inputs are
    * the spent, the collateral and the reference inputs (`babbageAllInputsTxBodyF`,
    * `Babbage/TxBody.hs:228`), whether or not the transaction has redeemers.
    */
  def resolvable(ann: StAnnTx): Either[LedgerFailure, Unit] =
    val body = ann.tx.body
    def refuse(f: UtxoFailure) = Left(LedgerFailure.Rejected(ann.id, PredicateFailure.utxo(f)))
    val missing =
      (body.inputs ++ body.collateral ++ body.referenceInputs).filterNot(ann.utxo.contains).toSet
    if body.inputs.isEmpty then refuse(UtxoFailure.InputSetEmpty)
    else if missing.nonEmpty then refuse(UtxoFailure.BadInputs(missing))
    else Right(())

  /** The checks of the outputs that hold a bootstrap (Byron) address: its attributes are at most 64
    * bytes (`validateOutputBootAddrAttrsTooBig`, `Babbage/Rules/Utxo.hs:392`) and its network is
    * the one of the ledger (`validateWrongNetwork`, `Babbage/Rules/Utxo.hs:397`, which takes the
    * network of a bootstrap address from its attributes, `getNetwork`). The Shelley addresses are
    * checked by [[TxChecks.outputsOk]]; an address that does not parse is not checked here.
    */
  private[ledger] def bootstrapOutputs(
      network: Int,
      outs: Vector[TxOut]
  ): Either[PredicateFailure, Unit] =
    val bootstrap = outs.flatMap(out =>
      out.address.kind match
        case Address.Kind.Byron(_) => ByronAddress.decode(out.address.raw).toOption.map(out -> _)
        case _                     => None
    )
    val big = bootstrap.collect { case (out, a) if a.attributesSize > 64 => out }
    val wrong = bootstrap.collect { case (out, a) if a.network != network => out.address.raw }
    for
      _ <- Either.cond(
        big.isEmpty,
        (),
        PredicateFailure.utxo(UtxoFailure.OutputBootAddrAttrsTooBig(big))
      )
      _ <- Either.cond(
        wrong.isEmpty,
        (),
        PredicateFailure.utxo(UtxoFailure.WrongNetwork(network, wrong.toSet))
      )
    yield ()

  /** The checks of UTXO the node makes of a transaction, in the node's order, and the collateral of
    * the transaction (`TxChecks.Collateral`).
    */
  def validate(
      env: UtxoEnv[ConwayCertState],
      ann: StAnnTx,
      deposits: BigInt,
      refunds: BigInt
  ): Either[LedgerFailure, TxChecks.Collateral] =
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
        RefScripts.tierRefScriptFee(
          pp.minFeeRefScriptCostPerByte,
          RefScripts.refScriptsSize(ann.utxo, body)
        )
    val overlap = body.inputs.toSet.intersect(body.referenceInputs.toSet)
    val wrongWithdrawals = body.withdrawals.map(_._1).filter(_.network != ctx.network).toSet
    for
      _ <- check(
        pp.protocolMajor >= 11 || overlap.isEmpty,
        UtxoFailure.BabbageNonDisjointRefInputs(overlap.toVector.sorted)
      )
      _ <- check(
        body.ttl.forall(ctx.slot < _) && body.validityStart.forall(_ <= ctx.slot),
        UtxoFailure.OutsideValidityInterval(body.validityStart, body.ttl, ctx.slot)
      )
      // f649f975: Babbage/Rules/Utxo.hs:368–375, also used by Conway/Rules/Utxo.hs:238.
      // UTXOW resolves early for its annotations; standalone UTXO still owns these predicates.
      _ <- check(body.inputs.nonEmpty, UtxoFailure.InputSetEmpty)
      _ <- check(
        tx.size <= pp.maxTxSize,
        UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), pp.maxTxSize))
      )
      _ <- check(body.fee >= minFee, UtxoFailure.FeeTooSmall(Mismatch(body.fee, minFee)))
      _ <- resolvable(ann)
      _ <- check(
        body.networkId.forall(_ == ctx.network),
        UtxoFailure.WrongNetworkInTxBody(Mismatch(body.networkId.fold(0)(_.toInt), ctx.network))
      )
      _ <- TxChecks
        .outputsOk(pp, ctx, body.outputs ++ body.collateralReturn.toVector)
        .leftMap(LedgerFailure.Rejected(id, _))
      _ <- bootstrapOutputs(ctx.network, body.outputs ++ body.collateralReturn.toVector)
        .leftMap(LedgerFailure.Rejected(id, _))
      // `validateWrongNetworkWithdrawal` (`Babbage/Rules/Utxo.hs:400`), regardless of the validity tag
      _ <- check(
        wrongWithdrawals.isEmpty,
        UtxoFailure.WrongNetworkWithdrawal(ctx.network, wrongWithdrawals)
      )
      _ <- check(
        wits.totalExUnits.fits(pp.maxTxExUnits),
        UtxoFailure.ExUnitsTooBig(Mismatch(wits.totalExUnits, pp.maxTxExUnits))
      )
      // `validateTooManyCollateralInputs` (`Babbage/Rules/Utxo.hs:412`): whether or not there are
      // redeemers, which is all `collateralChecks` looks at
      _ <- check(
        TxChecks.collateralInputs(body).size <= pp.maxCollateralInputs,
        UtxoFailure.TooManyCollateralInputs(
          Mismatch(TxChecks.collateralInputs(body).size, pp.maxCollateralInputs.toInt)
        )
      )
      collateral <- TxChecks.collateralChecks(ann.utxo, pp, tx)
      withdrawn = body.withdrawals.map(_._2).sum
      consumed = ann.spent.foldLeft(Value.zero)(_ + _.value) + Value.coin(withdrawn + refunds) +
        Value(0, body.mint)
      produced = body.outputs.foldLeft(Value.zero)(_ + _.value) +
        Value.coin(body.fee + deposits + body.donation)
      _ <- check(consumed == produced, UtxoFailure.ValueNotConserved(Mismatch(consumed, produced)))
    yield collateral

  /** `updateUTxOState` and `updateTreasuryDonation` (`Babbage/Rules/Utxo.hs:449`,
    * `Conway/Rules/Utxo.hs:201`).
    */
  def update(
      s: UTxOState[Conway],
      ann: StAnnTx,
      collateral: TxChecks.Collateral,
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
        stake <- InstantStakeOps.update(
          s.instantStake,
          body.outputs,
          body.inputs.distinct.sorted.flatMap(utxo.get)
        )
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
          collateral.outputs
        )
        fees <- Coin.fromBigInt(s.fees.value + collateral.fee)
      yield s.copy(
        utxo = UtxoTables(utxo -- body.collateral ++ returned),
        fees = fees,
        instantStake = stake
      )

  def transition[F[_]: Monad](env: UtxoEnv[ConwayCertState], ann: StAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[Conway]]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val (deposits, refunds) = Deposits.of(env.pp, env.cert, ann.tx.body)
    for
      collateral <- Sts.lift[F, LedgerFailure, TxChecks.Collateral](
        validate(env, ann, deposits, refunds)
      )
      _ <- JudgedSts.trans[F, UtxoEnv[Any], PlutusStats, Counted[
        UTxOState[Conway]
      ], StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](Utxos, env, ann)
      s <- st.get
      next <- Refuse.vocab[F, UTxOState[Conway]](
        update(s.state, ann, collateral, deposits, refunds)
      )
      _ <- st.set(s.copy(state = next))
    yield ()

/** UTXOW (`Babbage/Rules/Utxow.hs:308`, `babbageUtxowTransition`, as Conway's UTXOW runs it, with
  * the Alonzo rule it extends): the scripts the transaction provides are the ones it needs, its
  * datums and redeemers match the Plutus scripts, the script integrity hash, the auxiliary data,
  * and the signatures; then UTXO.
  *
  * The two checks of UTXO that annotating the transaction depends on (inputs exist, `StAnnTx`) are
  * made first ([[Utxo.resolvable]]), as Haskell's ledger does before it hands the annotated
  * transaction to the rule.
  */
object Utxow
    extends JudgedSts[UtxoEnv[ConwayCertState], Counted[
      UTxOState[Conway]
    ], StAnnTx, PredicateFailure, LedgerFailure]:
  /** The checks of the witnesses and scripts, in the node's order. */
  def witnesses(env: UtxoEnv[ConwayCertState], ann: StAnnTx): Either[LedgerFailure, Unit] =
    witnesses(env, Map.empty, ann)

  /** The same for any era: `genDelegs` are the genesis delegations of the certificate state before
    * the transaction (the key hashes of the genesis delegates the update proposals and genesis
    * delegation certificates need witnesses of; none from Conway, which has no such proposal).
    */
  def witnesses(
      env: UtxoEnv[Any],
      genDelegs: Map[Bytes, GenDelegPair],
      ann: StAnnTx
  ): Either[LedgerFailure, Unit] =
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
    // The outputs the collateral inputs hold, whose key witnesses the transaction needs whether or
    // not it has redeemers: `spendableInputsTxBodyF` is the inputs and the collateral inputs from
    // Alonzo on (cardano-ledger f649f975, `Alonzo/TxBody.hs` `allInputsTxBodyF` and
    // `spendableInputsTxBodyF`; `Babbage/TxBody.hs` `babbageSpendableInputsTxBodyF`; Conway uses
    // the Babbage getter), and `getShelleyWitsVKeyNeededNoGov` (`Shelley/UTxO.hs`) takes the
    // payment key of each. An earlier era has no collateral field, so its set is empty.
    val collateral = TxChecks.Collateral(body.collateral.flatMap(ann.utxo.get), BigInt(0))
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
      _ <- TxChecks.alonzoChecks(pp, tx, ann.spent, ann.referenced, scripts, ann.plutusPurposes)
      _ <- TxChecks.auxiliaryChecks(pp, tx, env.ctx)
      // a transaction flagged invalid has a Plutus script to fail (`validationTagMismatch`, which
      // Haskell's UTXOS makes; the node makes it here, where its own rules do)
      _ <- check0(
        tx.isValid || ann.plutusPurposes.nonEmpty,
        PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, None)))
      )
      _ <- TxChecks.witnessChecks(
        genDelegs,
        tx,
        ann.spent,
        collateral,
        scripts,
        needed,
        wits.vkeys.map(w => env.ctx.verdicts.keyHash(w._1)),
        env.ctx.verdicts
      )
      _ <- TxChecks.scriptsWellFormed(pp, tx, env.ctx)
    yield ()

  def transition[F[_]: Monad](env: UtxoEnv[ConwayCertState], ann: StAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[Conway]]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    for
      _ <- Sts.lift[F, LedgerFailure, Unit](Utxo.resolvable(ann))
      _ <- Sts.lift[F, LedgerFailure, Unit](witnesses(env, ann))
      _ <- JudgedSts.trans[F, UtxoEnv[ConwayCertState], Counted[UTxOState[Conway]], Counted[
        UTxOState[Conway]
      ], StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](Utxo, env, ann)
    yield ()

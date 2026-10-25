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
  PlutusStats,
  TxChecks,
  PredicateFailure,
  ProtocolParams,
  TxBody,
  TxIn,
  TxOut,
  TxEnv,
  UtxowFailure,
  Value
}
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
      next <- Refuse.recorded[F, PlutusStats](
        TxChecks.phase2(
          env.pp,
          counters,
          tx,
          env.ctx,
          ann.scripts,
          ann.spentByInput,
          ann.referencedByInput,
          ann.plutusPurposes,
          evaluate = !failing
        )
      )
      _ <- next.traverse_(st.set)
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

  /** The checks of UTXO (`babbageUtxoValidation`, [[UtxoTests.babbage]]): the minimum fee counts
    * the reference scripts (`getMinFeeTxUtxo`, `Conway/Tx.hs`), and the value consumed and produced
    * count the refunds, deposits and the donation.
    */
  def validate(
      env: UtxoEnv[ConwayCertState],
      ann: StAnnTx,
      deposits: BigInt,
      refunds: BigInt
  ): Test[PredicateFailure] =
    val tx = ann.tx
    val body = tx.body
    val pp = env.pp
    val wits = tx.witnesses
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
    val withdrawn = body.withdrawals.map(_._2).sum
    val consumed = ann.spent.foldLeft(Value.zero)(_ + _.value) +
      Value.coin(withdrawn + refunds) + Value(0, body.mint)
    val produced = body.outputs.foldLeft(Value.zero)(_ + _.value) +
      Value.coin(body.fee + deposits + body.donation)
    UtxoTests.babbage(pp, env.ctx.network, env.ctx.slot, ann, minFee, consumed, produced)

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
    val collateral = TxChecks.collateral(ann.utxo, env.pp, ann.tx)
    for
      _ <- Sts.runTest[F, PredicateFailure](validate(env, ann, deposits, refunds))
      _ <- JudgedSts.trans[F, UtxoEnv[Any], PlutusStats, Counted[
        UTxOState[Conway]
      ], StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](Utxos, env, ann)
      s <- st.get
      next <- Refuse.vocab[F, UTxOState[Conway]](
        update(s.state, ann, collateral, deposits, refunds)
      )
      _ <- st.set(s.copy(state = next))
    yield ()

/** UTXOW (`Babbage/Rules/Utxow.hs:308`, `babbageUtxowTransition`, as Conway's UTXOW runs it,
  * cardano-ledger `f649f975`, with the Alonzo and Shelley rules it extends): the scripts the
  * transaction provides are the ones it needs, its datums and redeemers match the Plutus scripts,
  * the signatures, the auxiliary data, the scripts are well formed and the script integrity hash;
  * then UTXO (`trans`). Every check records its failures and the rule goes on.
  *
  * An input the UTxO set does not hold needs nothing here (the checks read `txIns ◁ utxo`); UTXO
  * reports it.
  */
object Utxow
    extends JudgedSts[UtxoEnv[ConwayCertState], Counted[
      UTxOState[Conway]
    ], StAnnTx, PredicateFailure, LedgerFailure]:
  private def utxow(f: UtxowFailure): PredicateFailure = PredicateFailure.utxow(f)

  /** The checks of UTXOW of the era of the parameters in force, in the reference's order, each a
    * [[Test]] or the defect that leaves no verdict:
    *
    *   - Shelley to Mary (`transitionRulesUTXOW`, `Shelley/Rules/Utxow.hs:286-326`): the failing
    *     native scripts, the missing and the extraneous scripts, the signatures, the needed
    *     witnesses, the auxiliary data, the MIR quorum;
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
  def checks(
      env: UtxoEnv[Any],
      genDelegs: Map[Bytes, GenDelegPair],
      ann: StAnnTx,
      mir: Test[PredicateFailure]
  ): Vector[Either[LedgerFailure, Test[PredicateFailure]]] =
    val tx = ann.tx
    val pp = env.pp
    val pv = pp.protocolMajor
    val scripts = ann.scripts
    val needed = ann.needed.keySet
    val received = tx.witnesses.scripts.map(_.hash).toSet
    val provided =
      TxChecks.providedKeyHashes(tx, tx.witnesses.vkeys.map(w => env.ctx.verdicts.keyHash(w._1)))
    // The outputs the collateral inputs hold, whose key witnesses the transaction needs whether or
    // not it has redeemers: `spendableInputsTxBodyF` is the inputs and the collateral inputs from
    // Alonzo on (cardano-ledger f649f975, `Alonzo/TxBody.hs` `allInputsTxBodyF` and
    // `spendableInputsTxBodyF`; `Babbage/TxBody.hs` `babbageSpendableInputsTxBodyF`; Conway uses
    // the Babbage getter), and `getShelleyWitsVKeyNeededNoGov` (`Shelley/UTxO.hs`) takes the
    // payment key of each. An earlier era has no collateral field, so its set is empty.
    val collateral = TxChecks.Collateral(tx.body.collateral.flatMap(ann.utxo.get), BigInt(0))
    def test(t: Test[PredicateFailure]) = Right(t)
    val signatures = test(TxChecks.verifiedWits(tx, env.ctx.verdicts))
    val witnesses =
      test(TxChecks.neededWitnesses(genDelegs, tx, ann.spent, collateral, provided))
    val metadata = TxChecks.auxiliaryChecks(pp, tx, env.ctx)
    if pv < 7 then
      // `validateMissingScripts` (`Shelley/Rules/Utxow.hs:375-388`): the scripts provided are the
      // witnesses' before Babbage
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
      val native = test(TxChecks.failedNativeScripts(tx, scripts, None, provided))
      TxChecks.plutusFieldsDefect(pp, tx).map(Left(_)).toVector ++ (
        if pv < 5 then Vector(native, missingScripts, signatures, witnesses, metadata, test(mir))
        else
          Vector(
            native,
            missingScripts,
            test(
              TxChecks.datumChecks(pp, tx, ann.spentByInput, ann.referenced, scripts)
            ),
            test(TxChecks.redeemerChecks(tx, ann.spentByInput, scripts, ann.plutusPurposes)),
            signatures,
            witnesses,
            test(mir),
            metadata,
            test(TxChecks.integrityCheck(pp, tx, ann.spentByInput, scripts))
          )
      )
    else
      // `babbageMissingScripts` (`Babbage/Rules/Utxow.hs:192-207`): the needed scripts less the
      // reference scripts of the inputs and reference inputs are the witnesses' scripts
      val references = (ann.spent ++ ann.referenced).flatMap(_.scriptRef).map(_.hash).toSet
      val neededNonRef = needed -- references
      Vector(
        test(mir),
        test(TxChecks.failedNativeScripts(tx, scripts, Some(needed), provided)),
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
        test(TxChecks.datumChecks(pp, tx, ann.spentByInput, ann.referenced, scripts)),
        test(TxChecks.redeemerChecks(tx, ann.spentByInput, scripts, ann.plutusPurposes)),
        signatures,
        witnesses,
        metadata,
        TxChecks.scriptsWellFormed(pp, tx, env.ctx),
        test(TxChecks.integrityCheck(pp, tx, ann.spentByInput, scripts))
      )

  /** Records the checks in order: every failure of each, or the first defect raised. */
  def record[F[_]: Monad](checks: Vector[Either[LedgerFailure, Test[PredicateFailure]]])(using
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    checks.traverse_(check =>
      Sts
        .lift[F, LedgerFailure, Test[PredicateFailure]](check)
        .flatMap(Sts.runTest[F, PredicateFailure])
    )

  def transition[F[_]: Monad](env: UtxoEnv[ConwayCertState], ann: StAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[Conway]]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    for
      _ <- record[F](checks(env, Map.empty, ann, Test.pass))
      _ <- JudgedSts.trans[F, UtxoEnv[ConwayCertState], Counted[UTxOState[Conway]], Counted[
        UTxOState[Conway]
      ], StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](Utxo, env, ann)
    yield ()

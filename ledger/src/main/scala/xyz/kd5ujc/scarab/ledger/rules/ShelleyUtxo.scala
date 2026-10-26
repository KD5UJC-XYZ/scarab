// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Certificate,
  LedgerFailure,
  Mismatch,
  PpupFailure,
  UpdateChecks,
  TxChecks,
  PredicateFailure,
  ProtocolParams,
  TxBody,
  TxDefect,
  TxIn,
  TxOut,
  TxEnv,
  Update,
  UtxoFailure,
  UtxowFailure,
  Value,
  VotingPeriod
}
import xyz.kd5ujc.scarab.ledger.state.*

// UTXOW, UTXO, UTXOS and PPUP for Shelley, Allegra, Mary, Alonzo and Babbage: one rule of each,
// over the witness of the era (`Shelley/Rules/Utxow.hs`, `Utxo.hs`, `Ppup.hs`, with the rules that
// the later eras extend them with: `Allegra/Rules/Utxo.hs` (the validity interval), `Mary/Rules/
// Utxo.hs` (the multi-asset value), `Alonzo/Rules/Utxo.hs`, `Utxos.hs`, `Utxow.hs` (scripts,
// collateral, execution units, the validity flag), `Babbage/Rules/Utxo.hs`, `Utxow.hs` (reference
// inputs, the collateral return)). They run on the UTxO state (`UTxOState`): the UTxO set, the
// deposits and fees, the proposed updates, and the instant stake the tick reads, with the stake of
// the pointer addresses.
//
// The shared pure predicates live in `TxChecks`; these rules sequence them in the reference's
// order and maintain the UTxO state. The era of the rule is the evidence `PreConway[E]`:
// where the checks branch on the protocol version of the parameters in force, that is the
// reference's own (`hardfork*`).

/** `PpupEnv` (`Ppup.hs`): the block-level context of the transaction (the slot and epoch, the
  * update quorum), the parameters in force, and the genesis delegations the proposals are checked
  * against.
  */
final case class PpupEnv(
    ctx: TxEnv,
    id: Bytes,
    pp: ProtocolParams,
    genDelegs: SortedMap[KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.GenDelegPair]
)

/** PPUP (`Shelley/Rules/Ppup.hs:146`, `ppupTransitionNonEmpty`, cardano-ledger `f649f975`): the
  * update proposals of a transaction, every check recording its failure, the proposals recorded
  * whatever failed. The genesis delegates may propose, the proposed protocol version must follow
  * the one in force, and the epoch of the proposal is this one before the point of no return (two
  * stability windows before the end of the epoch) and the next after it. A proposal for this epoch
  * also makes the prediction of the parameters of the next, `votedFuturePParams` (`Ppup.hs:205`),
  * which the TICK rule solidifies and UPEC adopts.
  */
object ShelleyPpup
    extends JudgedSts[PpupEnv, ShelleyGovState, Option[Update], PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: PpupEnv, update: Option[Update])(using
      ask: Ask[F, Globals],
      st: Stateful[F, ShelleyGovState],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    update.traverse_ { u =>
      val id = env.id
      val ctx = env.ctx
      def refuse(f: PpupFailure): PredicateFailure = PredicateFailure.utxo(UtxoFailure.Update(f))
      val known = env.genDelegs.keysIterator.map(_.bytes).toSet
      val tooLate = ctx.firstSlotNextEpoch - 2 * ctx.stabilityWindow
      val current = ctx.slot < tooLate
      // `firstIllegalProtVerUpdate`: the first proposal, in the order of the genesis keys, whose
      // protocol version cannot follow the one in force (`Ppup.hs:163-168`)
      val firstIllegal = u.proposals.toVector
        .sortBy(_._1)
        .iterator
        .map((_, p) => UpdateChecks.canFollow(env.pp, p))
        .collectFirst { case Left(refusal) => refusal }
      for
        _ <- Sts.predicate[F, PredicateFailure](
          u.proposals.keys.forall(known.contains),
          refuse(PpupFailure.NonGenesisUpdate(Mismatch(u.proposals.keySet, known)))
        )
        _ <- firstIllegal.traverse_ {
          case UpdateChecks.Refusal.Failed(f) => Sts.failBecause[F, PredicateFailure](refuse(f))
          case UpdateChecks.Refusal.Unreadable =>
            (LedgerFailure.Undecodable(id, TxDefect.UnreadableProtocolVersion): LedgerFailure)
              .raise[F, Unit]
        }
        _ <- Sts.predicate[F, PredicateFailure](
          u.epoch == (if current then ctx.epoch else ctx.epoch + 1),
          refuse(
            PpupFailure.PPUpdateWrongEpoch(
              ctx.epoch,
              u.epoch,
              if current then VotingPeriod.VoteForThisEpoch else VotingPeriod.VoteForNextEpoch
            )
          )
        )
        proposed <- Refuse.vocab[F, List[(KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.ParamUpdate)]](
          u.proposals.toList.traverse((k, p) => Convert.keyHash[Genesis](k).map(_ -> p))
        )
        _ <- st.modify { g =>
          if current then
            val proposals = g.curProposals ++ proposed
            g.copy(
              curProposals = proposals,
              futurePParams = FuturePParams.PotentialPParamsUpdate(
                xyz.kd5ujc.scarab.ledger.UpdateChecks.voted(
                  env.pp,
                  proposals.iterator.map((k, p) => k.bytes -> p).toMap,
                  ctx.updateQuorum
                )
              )
            )
          else g.copy(futureProposals = g.futureProposals ++ proposed)
        }
      yield ()
    }

/** What the instant stake becomes as outputs are created and spent (`addShelleyInstantStake`,
  * `deleteShelleyInstantStake`, `applyUTxOShelleyInstantStake`, `Shelley/State/Stake.hs:108-145` at
  * f649f975): the lovelace of an output goes to the entry of the credential of a base address or of
  * the pointer of a pointer address. The arithmetic is native Word64 (`CompactForm Coin`), so a
  * deletion can wrap below zero and an addition past 2^64, and an entry that reaches zero is
  * removed. An absent entry receives the output amount for either operation, as native `Map.alter`
  * specifies. An enterprise address and a Byron address have none.
  */
object ShelleyInstantStakeOps:
  private def adjust[K](
      m: SortedMap[K, CompactCoin],
      key: K,
      amount: CompactCoin,
      sign: Int
  ): SortedMap[K, CompactCoin] =
    val next = m.get(key).fold(amount) { old =>
      val word =
        if sign > 0 then Word64.wrappingAdd(old.word, amount.word)
        else Word64.wrappingSubtract(old.word, amount.word)
      CompactCoin.fromUnsigned(word.bits)
    }
    if next.word.isZero then m.removed(key) else m.updated(key, next)

  private def step(
      is: ShelleyInstantStake,
      out: TxOut,
      sign: Int
  ): Either[VocabError, ShelleyInstantStake] =
    out.address.kind match
      case Address.Kind.Shelley(_, _, Some(credential), _) =>
        for
          c <- Convert.credential[Staking](credential)
          amount <- CompactCoin.fromBigInt(out.value.coin)
        yield is.copy(credentialStake = adjust(is.credentialStake, c, amount, sign))
      case Address.Kind.Shelley(_, _, None, Some(ptr)) =>
        CompactCoin
          .fromBigInt(out.value.coin)
          .map(amount => is.copy(ptrStake = adjust(is.ptrStake, ptr, amount, sign)))
      case _ => Right(is)

  /** The instant stake with the outputs `added` in it and the outputs `deleted` out of it. */
  def update(
      is: ShelleyInstantStake,
      added: Iterable[TxOut],
      deleted: Iterable[TxOut]
  ): Either[VocabError, ShelleyInstantStake] =
    val withAdded = added.foldLeft[Either[VocabError, ShelleyInstantStake]](Right(is))((m, out) =>
      m.flatMap(step(_, out, 1))
    )
    deleted.foldLeft(withAdded)((m, out) => m.flatMap(step(_, out, -1)))

/** The deposits a transaction pays and the refunds it collects, from the certificate state before
  * it (`shelleyTotalDepositsTxCerts`, `shelleyTotalRefundsTxCerts`, `Shelley/TxCert.hs:611-653`): a
  * key deposit for each registration, a pool deposit for each pool the state and the earlier
  * certificates do not register; a refund of the deposit the account holds for each deregistration
  * (of the key deposit, for an account the same transaction registered).
  */
object ShelleyDeposits:
  def of(pp: ProtocolParams, cert: ShelleyCertState, body: TxBody): (BigInt, BigInt) =
    val pools = ShelleyCertLens.pools.get(cert)
    val accounts = ShelleyCertLens.states.get(cert)
    val newPools = body.certificates.collect {
      case Certificate.RegisterPool(p)
          if !Convert.keyHash[StakePool](p.operator).toOption.exists(pools.contains) =>
        p.operator
    }.toSet
    val keys = body.certificates.count {
      case Certificate.Register(_, _) => true
      case _                          => false
    }
    val (_, refunds) =
      body.certificates.foldLeft((Set.empty[xyz.kd5ujc.scarab.ledger.Credential], BigInt(0))) {
        case ((regs, total), c) =>
          c match
            case Certificate.Register(k, _) => (regs + k, total)
            case Certificate.Unregister(k, _) =>
              if regs.contains(k) then (regs - k, total + pp.keyDeposit)
              else
                (
                  regs,
                  total + Convert
                    .credential[Staking](k)
                    .toOption
                    .flatMap(accounts.get)
                    .fold(BigInt(0))(_.deposit.toBigInt)
                )
            case _ => (regs, total)
      }
    (pp.keyDeposit * keys + pp.poolDeposit * newPools.size, refunds)

/** UTXOS of Alonzo and Babbage (`Alonzo/Rules/Utxos.hs:159`, `Babbage/Rules/Utxos.hs:120`,
  * cardano-ledger `f649f975`): the evaluation of the Plutus scripts ([[Utxos.scripts]]) and, for a
  * transaction whose scripts are to pass, the update proposals (`trans PPUP`): after the scripts in
  * Alonzo (`alonzoEvalScriptsTxValid`, `Alonzo/Rules/Utxos.hs:194-216`), before them in Babbage
  * (`babbageEvalScriptsTxValid`, `Babbage/Rules/Utxos.hs:157-189`).
  */
object ShelleyUtxos:
  def sts[E <: EraTag](using
      pre: PreConway[E]
  ): JudgedSts[UtxoEnv[ShelleyCertState], Counted[
    UTxOState[E]
  ], StAnnTx, PredicateFailure, LedgerFailure] =
    new JudgedSts[UtxoEnv[ShelleyCertState], Counted[
      UTxOState[E]
    ], StAnnTx, PredicateFailure, LedgerFailure]:
      def transition[F[_]: Monad](env: UtxoEnv[ShelleyCertState], ann: StAnnTx)(using
          ask: Ask[F, Globals],
          st: Stateful[F, Counted[UTxOState[E]]],
          raise: Raise[F, LedgerFailure],
          judge: Judge[F, PredicateFailure]
      ): F[Unit] =
        val scripts =
          Utxos.scripts[F](env, ann)(using
            summon[Monad[F]],
            st.focus(Counted.plutus[UTxOState[E]]),
            raise,
            judge
          )
        if !ann.tx.isValid then scripts
        else
          val ppup = ShelleyUtxo.ppup[F, E](env, ann)
          if pre.atLeast(ShelleyBasedEra.SBabbage) then ppup *> scripts else scripts *> ppup

/** UTXO (`Shelley/Rules/Utxo.hs:336`, `utxoInductive`, with the rules of the later eras,
  * cardano-ledger `f649f975`): the validity of the transaction against the parameters and the UTxO
  * set, every failure recorded ([[UtxoChecks]]), the update proposals (`trans PPUP`, Shelley to
  * Mary) or UTXOS (`trans UTXOS`, Alonzo and Babbage, which runs PPUP), and then the change of the
  * state (`updateUTxOState`): the set, the instant stake, the fees and the deposits of a valid
  * transaction, the collateral of one that failed phase 2.
  */
object ShelleyUtxo:
  /** `trans PPUP` on the governance state of the UTxO state. */
  private[rules] def ppup[F[_]: Monad, E <: EraTag](env: UtxoEnv[ShelleyCertState], ann: StAnnTx)(
      using
      pre: PreConway[E],
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[E]]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    given Embed[ShelleyGovState, Counted[UTxOState[E]], LedgerFailure, LedgerFailure] =
      Embed(Counted.state[UTxOState[E]].andThen(pre.gov), identity)
    JudgedSts.trans[F, PpupEnv, ShelleyGovState, Counted[UTxOState[E]], Option[
      Update
    ], PredicateFailure, LedgerFailure, LedgerFailure](
      ShelleyPpup,
      PpupEnv(env.ctx, ann.id, env.pp, ShelleyCertLens.genDelegs.get(env.cert)),
      ann.tx.body.update
    )

  def sts[E <: EraTag](using
      pre: PreConway[E]
  ): JudgedSts[UtxoEnv[ShelleyCertState], Counted[
    UTxOState[E]
  ], StAnnTx, PredicateFailure, LedgerFailure] =
    new JudgedSts[UtxoEnv[ShelleyCertState], Counted[
      UTxOState[E]
    ], StAnnTx, PredicateFailure, LedgerFailure]:
      def transition[F[_]: Monad](env: UtxoEnv[ShelleyCertState], ann: StAnnTx)(using
          ask: Ask[F, Globals],
          st: Stateful[F, Counted[UTxOState[E]]],
          raise: Raise[F, LedgerFailure],
          judge: Judge[F, PredicateFailure]
      ): F[Unit] =
        val (deposits, refunds) = ShelleyDeposits.of(env.pp, env.cert, ann.tx.body)
        val collateral = TxChecks.collateral(ann.utxo, env.pp, ann.tx)
        val checks = UtxoChecks.of[E](env, ann, deposits, refunds)
        for
          _ <- Sts.require[F, LedgerFailure](checks.defect)
          _ <- Sts.runTest[F, PredicateFailure](checks.beforePpup)
          _ <-
            if pre.atLeast(ShelleyBasedEra.SAlonzo) then
              JudgedSts.trans[F, UtxoEnv[ShelleyCertState], Counted[UTxOState[E]], Counted[
                UTxOState[E]
              ], StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](
                ShelleyUtxos.sts[E],
                env,
                ann
              )
            else ppup[F, E](env, ann) *> Sts.runTest[F, PredicateFailure](checks.afterPpup)
          s <- st.get
          next <- Refuse.vocab[F, UTxOState[E]](
            update[E](s.state, ann, collateral, deposits, refunds)
          )
          _ <- st.set(s.copy(state = next))
        yield ()

  /** What UTXO checks of a transaction, without PPUP between them ([[UtxoChecks]]). */
  def validate[E <: EraTag](
      env: UtxoEnv[ShelleyCertState],
      ann: StAnnTx,
      deposits: BigInt,
      refunds: BigInt
  )(using PreConway[E]): Test[PredicateFailure] =
    val checks = UtxoChecks.of[E](env, ann, deposits, refunds)
    Test.all(checks.beforePpup, checks.afterPpup)

  /** `updateUTxOState` (`Shelley/Rules/Utxo.hs:584`, `Alonzo/Rules/Utxos.hs`): a valid transaction
    * spends its inputs and creates its outputs, pays its fee and the deposits less the refunds; one
    * that failed phase 2 spends its collateral and returns what the collateral return says, and its
    * fee is the collateral's.
    */
  def update[E <: EraTag](
      s: UTxOState[E],
      ann: StAnnTx,
      collateral: TxChecks.Collateral,
      deposits: BigInt,
      refunds: BigInt
  )(using pre: PreConway[E]): Either[VocabError, UTxOState[E]] =
    val tx = ann.tx
    val body = tx.body
    val id = ann.id
    val utxo = s.utxo.utxo
    val stake = pre.instantStake.get(s)
    // `extractKeys` deletes in `TxIn` map order, whatever the order the body lists its inputs in.
    def inOrder(inputs: Vector[TxIn]): Vector[TxOut] = inputs.distinct.sorted.flatMap(utxo.get)
    if tx.isValid then
      val created = body.outputs.zipWithIndex.map { (out, i) => TxIn(id, i) -> out }
      for
        next <- ShelleyInstantStakeOps.update(stake, body.outputs, inOrder(body.inputs))
        deposited <- Coin.fromBigInt(s.deposited.value + deposits - refunds)
        fees <- Coin.fromBigInt(s.fees.value + body.fee)
      yield pre.instantStake.replace(next)(
        s.copy[E](
          utxo = UtxoTables(utxo -- body.inputs ++ created),
          deposited = deposited,
          fees = fees
        )
      )
    else
      val returned = body.collateralReturn.map(r => TxIn(id, body.outputs.size) -> r)
      for
        next <- ShelleyInstantStakeOps.update(
          stake,
          returned.map(_._2),
          inOrder(body.collateral)
        )
        fees <- Coin.fromBigInt(s.fees.value + collateral.fee)
      yield pre.instantStake.replace(next)(
        s.copy[E](utxo = UtxoTables(utxo -- body.collateral ++ returned), fees = fees)
      )

/** The checks of UTXO before Conway, in the era's order: those before PPUP and those after it
  * (Shelley to Mary run PPUP between them, `Shelley/Rules/Utxo.hs:390-392`; Alonzo and Babbage run
  * every check before UTXOS, which runs PPUP), and the defect of a transaction carrying fields its
  * era's decoder would not have admitted.
  *
  * The minimum fee is `minfee` with the script fee from Alonzo; the value consumed and produced
  * count the refunds and deposits (`consumed`, `produced`, `Shelley/UTxO.hs`).
  */
final case class UtxoChecks(
    defect: Option[LedgerFailure],
    beforePpup: Test[PredicateFailure],
    afterPpup: Test[PredicateFailure]
)

object UtxoChecks:
  def of[E <: EraTag](
      env: UtxoEnv[ShelleyCertState],
      ann: StAnnTx,
      deposits: BigInt,
      refunds: BigInt
  )(using pre: PreConway[E]): UtxoChecks =
    val tx = ann.tx
    val body = tx.body
    val pp = env.pp
    val ctx = env.ctx
    val wits = tx.witnesses
    val alonzo = pre.atLeast(ShelleyBasedEra.SAlonzo)
    val scriptFee =
      if alonzo then
        (pp.priceMem * Rational(wits.totalExUnits.mem) + pp.priceSteps * Rational(
          wits.totalExUnits.steps
        )).ceil
      else BigInt(0)
    val minFee = pp.minFeeA * tx.size + pp.minFeeB + scriptFee
    val withdrawn = body.withdrawals.map(_._2).sum
    val consumed = ann.spent.foldLeft(Value.zero)(_ + _.value) +
      Value.coin(withdrawn + refunds) + Value(0, body.mint)
    val produced = body.outputs.foldLeft(Value.zero)(_ + _.value) +
      Value.coin(body.fee + deposits)
    val defect = Option.unless(!body.hasGovernance && body.donation == 0)(
      LedgerFailure.Undecodable(ann.id, TxDefect.GovernanceFieldsBeforeConway)
    )
    if pre.atLeast(ShelleyBasedEra.SBabbage) then
      UtxoChecks(
        defect,
        UtxoTests.babbage(pp, ctx.network, ctx.slot, ann, minFee, consumed, produced),
        Test.pass
      )
    else if alonzo then
      UtxoChecks(
        defect,
        UtxoTests.alonzo(pp, ctx.network, ctx.slot, ann, minFee, consumed, produced),
        Test.pass
      )
    else
      UtxoChecks(
        defect,
        UtxoTests.shelleyBeforePpup(pp, ctx.network, ctx.slot, ann, minFee, consumed, produced),
        UtxoTests.shelleyAfterPpup(pp, tx)
      )

/** UTXOW (`Shelley/Rules/Utxow.hs:270`, `transitionRulesUTXOW`, with the Alonzo and Babbage rules
  * that extend it, cardano-ledger `f649f975`): the checks of the era ([[Utxow.checks]]), among them
  * the genesis delegates' signatures a MIR certificate needs (`validateMIRInsufficientGenesisSigs`,
  * `Shelley/Rules/Utxow.hs:449`), each recording its failures; then UTXO (`trans`).
  */
object ShelleyUtxow:
  def sts[E <: EraTag](using
      pre: PreConway[E]
  ): JudgedSts[UtxoEnv[ShelleyCertState], Counted[
    UTxOState[E]
  ], StAnnTx, PredicateFailure, LedgerFailure] =
    new JudgedSts[UtxoEnv[ShelleyCertState], Counted[
      UTxOState[E]
    ], StAnnTx, PredicateFailure, LedgerFailure]:
      def transition[F[_]: Monad](env: UtxoEnv[ShelleyCertState], ann: StAnnTx)(using
          ask: Ask[F, Globals],
          st: Stateful[F, Counted[UTxOState[E]]],
          raise: Raise[F, LedgerFailure],
          judge: Judge[F, PredicateFailure]
      ): F[Unit] =
        val genDelegs = ShelleyCertLens.genDelegs.get(env.cert)
        for
          _ <- Utxow.record[F](
            Utxow.checks(env, PreConway.genDelegs(genDelegs), ann, mirSigned(env, ann, genDelegs))
          )
          _ <- JudgedSts.trans[F, UtxoEnv[ShelleyCertState], Counted[UTxOState[E]], Counted[
            UTxOState[E]
          ], StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](
            ShelleyUtxo.sts[E],
            env,
            ann
          )
        yield ()

  /** `validateMIRInsufficientGenesisSigs` (`Shelley/Rules/Utxow.hs:449-470`): a transaction with a
    * MIR certificate is signed by at least the quorum of the genesis delegates; the refusal names
    * the delegates that did sign.
    */
  private def mirSigned(
      env: UtxoEnv[ShelleyCertState],
      ann: StAnnTx,
      genDelegs: SortedMap[KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.GenDelegPair]
  ): Test[PredicateFailure] =
    val hasMir = ann.tx.body.certificates.exists {
      case Certificate.MoveRewards(_, _) => true
      case _                             => false
    }
    val signers = ann.tx.witnesses.vkeys.map(w => env.ctx.verdicts.keyHash(w._1)).toSet
    val genesisSigners = genDelegs.values.map(_.delegate).toSet.intersect(signers)
    Test.failureUnless(
      !hasMir || genesisSigners.size >= env.ctx.updateQuorum,
      PredicateFailure.utxow(UtxowFailure.MIRInsufficientGenesisSigs(genesisSigners))
    )

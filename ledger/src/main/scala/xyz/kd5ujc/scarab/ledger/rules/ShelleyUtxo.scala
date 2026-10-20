// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Monad, Order}
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Certificate,
  LedgerFailure,
  Mismatch,
  PlutusStats,
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
import xyz.kd5ujc.scarab.ledger.state.given

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

/** PPUP (`Shelley/Rules/Ppup.hs:146`, `ppupTransitionNonEmpty`): the update proposals of a
  * transaction. The genesis delegates may propose, the proposed protocol version must follow the
  * one in force, and the epoch of the proposal is this one before the point of no return (two
  * stability windows before the end of the epoch) and the next after it. A proposal for this epoch
  * also makes the prediction of the parameters of the next, `votedFuturePParams` (`Ppup.hs:205`),
  * which the TICK rule solidifies and UPEC adopts.
  */
object ShelleyPpup extends Sts[PpupEnv, ShelleyGovState, Option[Update], LedgerFailure]:
  def transition[F[_]: Monad](env: PpupEnv, update: Option[Update])(using
      ask: Ask[F, Globals],
      st: Stateful[F, ShelleyGovState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    update.traverse_ { u =>
      val id = env.id
      val ctx = env.ctx
      def refuse(f: PpupFailure): LedgerFailure =
        LedgerFailure.Rejected(id, PredicateFailure.utxo(UtxoFailure.Update(f)))
      val known = env.genDelegs.keysIterator.map(_.bytes).toSet
      val tooLate = ctx.firstSlotNextEpoch - 2 * ctx.stabilityWindow
      val current = ctx.slot < tooLate
      for
        _ <- Sts.check[F, LedgerFailure](
          u.proposals.keys.forall(known.contains),
          refuse(PpupFailure.NonGenesisUpdate(Mismatch(u.proposals.keySet, known)))
        )
        _ <- u.proposals.values.toVector.traverse_ { p =>
          Sts.lift[F, LedgerFailure, Unit](
            UpdateChecks
              .canFollow(env.pp, p)
              .leftMap {
                case UpdateChecks.Refusal.Failed(f) => refuse(f)
                case UpdateChecks.Refusal.Unreadable =>
                  LedgerFailure.Undecodable(id, TxDefect.UnreadableProtocolVersion)
              }
          )
        }
        _ <- Sts.check[F, LedgerFailure](
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
  * `deleteShelleyInstantStake`, `Shelley/State/Stake.hs:108-146`): the lovelace of an output goes
  * to the entry of the credential of a base address or of the pointer of a pointer address, and an
  * entry that reaches zero is removed. An enterprise address and a Byron address have none.
  */
object ShelleyInstantStakeOps:
  private def adjust[K](
      m: SortedMap[K, CompactCoin],
      key: K,
      coin: BigInt
  )(using Order[K]): Either[VocabError, SortedMap[K, CompactCoin]] =
    CompactCoin.fromBigInt(m.get(key).fold(BigInt(0))(_.toBigInt) + coin).map { next =>
      if next.word.isZero then m.removed(key) else m.updated(key, next)
    }

  private def step(
      is: ShelleyInstantStake,
      out: TxOut,
      sign: Int
  ): Either[VocabError, ShelleyInstantStake] =
    out.address.kind match
      case Address.Kind.Shelley(_, _, Some(credential), _) =>
        for
          c <- Convert.credential[Staking](credential)
          next <- adjust(is.credentialStake, c, sign * out.value.coin)
        yield is.copy(credentialStake = next)
      case Address.Kind.Shelley(_, _, None, Some(ptr)) =>
        adjust(is.ptrStake, ptr, sign * out.value.coin).map(next => is.copy(ptrStake = next))
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

/** UTXO (`Shelley/Rules/Utxo.hs:336`, `utxoInductive`, with the rules of the later eras): the
  * validity of the transaction against the parameters and the UTxO set, UTXOS (the evaluation of
  * the Plutus scripts, Alonzo on), PPUP for a valid transaction, and then the change of the state
  * (`updateUTxOState`): the set, the instant stake, the fees and the deposits of a valid
  * transaction, the collateral of one that failed phase 2.
  */
object ShelleyUtxo:
  def sts[E <: EraTag](using
      pre: PreConway[E]
  ): Sts[UtxoEnv[ShelleyCertState], Counted[UTxOState[E]], StAnnTx, LedgerFailure] =
    new Sts[UtxoEnv[ShelleyCertState], Counted[UTxOState[E]], StAnnTx, LedgerFailure]:
      private given Embed[PlutusStats, Counted[UTxOState[E]], LedgerFailure, LedgerFailure] =
        Embed(Counted.plutus[UTxOState[E]], identity)

      private given Embed[ShelleyGovState, Counted[UTxOState[E]], LedgerFailure, LedgerFailure] =
        Embed(Counted.state[UTxOState[E]].andThen(pre.gov), identity)

      def transition[F[_]: Monad](env: UtxoEnv[ShelleyCertState], ann: StAnnTx)(using
          ask: Ask[F, Globals],
          st: Stateful[F, Counted[UTxOState[E]]],
          raise: Raise[F, LedgerFailure]
      ): F[Unit] =
        val (deposits, refunds) = ShelleyDeposits.of(env.pp, env.cert, ann.tx.body)
        for
          collateral <- Sts.lift[F, LedgerFailure, TxChecks.Collateral](
            validate[E](env, ann, deposits, refunds)
          )
          _ <- Sts.trans[F, UtxoEnv[Any], PlutusStats, Counted[
            UTxOState[E]
          ], StAnnTx, LedgerFailure, LedgerFailure](Utxos, env, ann)
          _ <-
            if ann.tx.isValid then
              Sts.trans[F, PpupEnv, ShelleyGovState, Counted[
                UTxOState[E]
              ], Option[Update], LedgerFailure, LedgerFailure](
                ShelleyPpup,
                PpupEnv(env.ctx, ann.id, env.pp, ShelleyCertLens.genDelegs.get(env.cert)),
                ann.tx.body.update
              )
            else Monad[F].unit
          s <- st.get
          next <- Refuse.vocab[F, UTxOState[E]](
            update[E](s.state, ann, collateral, deposits, refunds)
          )
          _ <- st.set(s.copy(state = next))
        yield ()

  /** The checks of UTXO the node makes of a transaction, in the order of the reference where it has
    * one for the era, and the collateral of the transaction.
    */
  def validate[E <: EraTag](
      env: UtxoEnv[ShelleyCertState],
      ann: StAnnTx,
      deposits: BigInt,
      refunds: BigInt
  )(using pre: PreConway[E]): Either[LedgerFailure, TxChecks.Collateral] =
    val tx = ann.tx
    val body = tx.body
    val id = ann.id
    val pp = env.pp
    val ctx = env.ctx
    val wits = tx.witnesses
    def refuse(f: UtxoFailure) = Left(LedgerFailure.Rejected(id, PredicateFailure.utxo(f)))
    def check(ok: Boolean, f: => UtxoFailure): Either[LedgerFailure, Unit] =
      if ok then Right(()) else refuse(f)
    val alonzo = pre.atLeast(ShelleyBasedEra.SAlonzo)
    val scriptFee =
      if alonzo then
        (pp.priceMem * Rational(wits.totalExUnits.mem) + pp.priceSteps * Rational(
          wits.totalExUnits.steps
        )).ceil
      else BigInt(0)
    val minFee = pp.minFeeA * tx.size + pp.minFeeB + scriptFee
    val outputs = body.outputs ++ body.collateralReturn.toVector
    for
      _ <- Either.cond(
        !body.hasGovernance && body.donation == 0,
        (),
        LedgerFailure.Undecodable(id, TxDefect.GovernanceFieldsBeforeConway)
      )
      // Shelley's time to live is the last slot the transaction is valid in (`validateTimeToLive`,
      // `Shelley/Rules/Utxo.hs:414-423`); from Allegra the validity interval's upper bound is
      // exclusive (`inInterval`)
      _ <-
        if !pre.atLeast(ShelleyBasedEra.SAllegra) then
          check(
            body.ttl.forall(ctx.slot <= _),
            UtxoFailure.ExpiredUTxO(Mismatch(body.ttl.getOrElse(ctx.slot), ctx.slot))
          )
        else
          check(
            body.ttl.forall(ctx.slot < _) && body.validityStart.forall(_ <= ctx.slot),
            UtxoFailure.OutsideValidityInterval(body.validityStart, body.ttl, ctx.slot)
          )
      _ <- check(body.fee >= minFee, UtxoFailure.FeeTooSmall(Mismatch(body.fee, minFee)))
      _ <- check(
        body.networkId.forall(_ == ctx.network),
        UtxoFailure.WrongNetworkInTxBody(Mismatch(body.networkId.fold(0)(_.toInt), ctx.network))
      )
      _ <- if !alonzo then TxChecks.outputNetwork(ctx, id, outputs) else Right(())
      wrongWithdrawals = body.withdrawals.map(_._1).filter(_.network != ctx.network).toSet
      _ <- check(
        wrongWithdrawals.isEmpty,
        UtxoFailure.WrongNetworkWithdrawal(ctx.network, wrongWithdrawals)
      )
      withdrawn = body.withdrawals.map(_._2).sum
      consumed = ann.spent.foldLeft(Value.zero)(_ + _.value) + Value.coin(withdrawn + refunds) +
        Value(0, body.mint)
      produced = body.outputs.foldLeft(Value.zero)(_ + _.value) +
        Value.coin(body.fee + deposits)
      _ <- check(consumed == produced, UtxoFailure.ValueNotConserved(Mismatch(consumed, produced)))
      _ <- TxChecks.outputChecks(pp, ctx, id, outputs)
      _ <- check(
        tx.size <= pp.maxTxSize,
        UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), pp.maxTxSize))
      )
      _ <- check(
        !alonzo || wits.totalExUnits.fits(pp.maxTxExUnits),
        UtxoFailure.ExUnitsTooBig(Mismatch(wits.totalExUnits, pp.maxTxExUnits))
      )
      // `validateTooManyCollateralInputs` (Alonzo Utxo.hs:571, Babbage Utxo.hs:412) runs
      // independently of the redeemer-dependent collateral fee checks and the validity flag.
      _ <- check(
        !alonzo || TxChecks.collateralInputs(body).size <= pp.maxCollateralInputs,
        UtxoFailure.TooManyCollateralInputs(
          Mismatch(TxChecks.collateralInputs(body).size, pp.maxCollateralInputs.toInt)
        )
      )
      collateral <- TxChecks.collateralChecks(ann.utxo, pp, tx)
    yield collateral

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
    if tx.isValid then
      val created = body.outputs.zipWithIndex.map { (out, i) => TxIn(id, i) -> out }
      for
        next <- ShelleyInstantStakeOps.update(stake, body.outputs, ann.spent)
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
          collateral.outputs
        )
        fees <- Coin.fromBigInt(s.fees.value + collateral.fee)
      yield pre.instantStake.replace(next)(
        s.copy[E](utxo = UtxoTables(utxo -- body.collateral ++ returned), fees = fees)
      )

/** UTXOW (`Shelley/Rules/Utxow.hs:270`, `transitionRulesUTXOW`, with the Alonzo and Babbage rules
  * that extend it): the scripts the transaction provides are the ones it needs, its datums and
  * redeemers match the Plutus scripts, the script integrity hash, the auxiliary data, the
  * signatures, and the genesis delegates' signatures a MIR certificate needs
  * (`validateMIRInsufficientGenesisSigs`, `Utxow.hs:449`); then UTXO.
  *
  * As for Conway, the two checks of UTXO that annotating the transaction depends on (the inputs
  * exist, `StAnnTx`) are made first ([[Utxo.resolvable]]).
  */
object ShelleyUtxow:
  def sts[E <: EraTag](using
      pre: PreConway[E]
  ): Sts[UtxoEnv[ShelleyCertState], Counted[UTxOState[E]], StAnnTx, LedgerFailure] =
    new Sts[UtxoEnv[ShelleyCertState], Counted[UTxOState[E]], StAnnTx, LedgerFailure]:
      def transition[F[_]: Monad](env: UtxoEnv[ShelleyCertState], ann: StAnnTx)(using
          ask: Ask[F, Globals],
          st: Stateful[F, Counted[UTxOState[E]]],
          raise: Raise[F, LedgerFailure]
      ): F[Unit] =
        val genDelegs = ShelleyCertLens.genDelegs.get(env.cert)
        for
          _ <- Sts.lift[F, LedgerFailure, Unit](Utxo.resolvable(ann))
          _ <- Sts.lift[F, LedgerFailure, Unit](
            Utxow.witnesses(env, PreConway.genDelegs(genDelegs), ann)
          )
          _ <- Sts.require[F, LedgerFailure](mirSigned(env, ann, genDelegs))
          _ <- ShelleyUtxo.sts[E].transition[F](env, ann)
        yield ()

  /** `validateMIRInsufficientGenesisSigs`: a transaction with a MIR certificate is signed by at
    * least the quorum of the genesis delegates; the refusal names the delegates that did sign.
    */
  private def mirSigned(
      env: UtxoEnv[ShelleyCertState],
      ann: StAnnTx,
      genDelegs: SortedMap[KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.GenDelegPair]
  ): Option[LedgerFailure] =
    val hasMir = ann.tx.body.certificates.exists {
      case Certificate.MoveRewards(_, _) => true
      case _                             => false
    }
    val signers = ann.tx.witnesses.vkeys.map(w => env.ctx.verdicts.keyHash(w._1)).toSet
    val genesisSigners = genDelegs.values.map(_.delegate).toSet.intersect(signers)
    Option.when(hasMir && genesisSigners.size < env.ctx.updateQuorum)(
      LedgerFailure.Rejected(
        ann.id,
        PredicateFailure.utxow(UtxowFailure.MIRInsufficientGenesisSigs(genesisSigners))
      )
    )

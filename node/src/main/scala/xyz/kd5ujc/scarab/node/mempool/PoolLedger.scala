// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{
  LedgerFailure,
  PlutusStats,
  PredicateFailure,
  ProtocolParams,
  Tx,
  TxEnv,
  Utxo,
  UtxoFailure,
  UtxosFailure,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{
  ConwayLedger,
  Counted,
  EraNes,
  Globals,
  Hfc,
  JudgedSts,
  Judgment,
  LedgerEnv,
  Mempool,
  PreConway,
  ShelleyLedger,
  ShelleyLedgerEnv,
  Sts,
  Tick,
  TickFailure
}
import xyz.kd5ujc.scarab.ledger.state.{
  ChainAccountState,
  Coin,
  Conway,
  EraTag,
  LedgerState,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.read.{ChainRead, ConwayView, InEra}
import xyz.kd5ujc.scarab.protocol.Era

/** The ledger the mempool validates a transaction against: the ledger state at the chain tip,
  * ticked to the slot after it by the hard-fork combinator's tick, in the era that slot is in
  * (`tickLedgerState`, `Mempool/Impl/Common.hs`; the combinator's `applyChainTickLedgerResult`,
  * ouroboros-consensus 82ecba32). The pool holds transactions of that era only: the reference's
  * `LedgerSupportsMempool (HardForkBlock xs)` applies a transaction in the era of the ticked state
  * (`HardFork/Combinator/Mempool.hs:495-536`, ouroboros-consensus 82ecba32).
  *
  * Each era applies a transaction by its own rule (`ApplyTx`, cardano-ledger f649f975): LEDGER from
  * Shelley to Babbage ([[EarlierLedger]]; `Shelley/API/Mempool.hs:325-344`, `Allegra.hs:37-47`,
  * `Mary.hs:44-53`, `Alonzo.hs:77-94`, `Babbage.hs:59-69`), MEMPOOL in Conway ([[MempoolLedger]];
  * `Conway.hs:50-59`).
  */
sealed trait PoolLedger:
  /** The era of the ledger, which every transaction the pool holds is of. */
  def era: Era

  /** The protocol parameters in force. */
  def params: ProtocolParams

  /** The handle on the UTxO set. */
  def utxo: Utxo

  /** The epoch of the ticked state. */
  def epoch: BigInt

  def withUtxo(u: Utxo): PoolLedger

  /** The transaction applied with every check (`applyTx`, `ValidateAll`). */
  def submit(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, PoolLedger]

  /** A transaction validated before, applied again without the static checks (`reapplyTx`,
    * `ValidateSuchThat (notElem lblStatic)`, `Shelley/API/Mempool.hs:472-473`, cardano-ledger
    * f649f975).
    */
  def reapply(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, PoolLedger]

  /** The transaction admitted from `source`, with the transaction as it is to be held. */
  def admit(
      tx: Tx,
      env: TxEnv,
      rules: Globals,
      source: Source
  ): Either[LedgerFailure, (PoolLedger, Tx)]

object PoolLedger:

  /** `applyShelleyBasedTx` of the era (`Shelley/Eras.hs:146-269`, ouroboros-consensus 82ecba32):
    * Shelley, Allegra and Mary apply the transaction as received (`defaultApplyShelleyBasedTx`,
    * lines 169-188); from Alonzo a transaction from a peer (`DoNotIntervene`) is applied with
    * `is_valid` true, and when exactly that flag is what the rules refuse, because a script fails,
    * again with `is_valid` false (`applyAlonzoBasedTx`, lines 190-269, `isIncorrectClaimedFlag`).
    * The reference takes a refusal that is the only element of the failure list
    * ([[LedgerFailure.Rejected.Single]]).
    */
  private[mempool] def admitting[L](era: Era, tx: Tx, source: Source)(
      submit: Tx => Either[LedgerFailure, L]
  ): Either[LedgerFailure, (L, Tx)] =
    source match
      case Source.Remote if era.ordinal >= Era.Alonzo.ordinal =>
        val claimed = tx.copy(isValid = true)
        submit(claimed) match
          case Left(
                LedgerFailure.Rejected.Single(
                  _,
                  PredicateFailure.Utxow(
                    UtxowFailure.Utxo(
                      UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(true, _))
                    )
                  )
                )
              ) =>
            val failed = tx.copy(isValid = false)
            submit(failed).map(_ -> failed)
          case other => other.map(_ -> claimed)
      case _ => submit(tx).map(_ -> tx)

  /** The pool's ledger of a new-epoch state of any era: none in Dijkstra, whose transactions this
    * build does not validate in the pool.
    */
  def of(e: EraNes): Either[ConwayView.Unavailable, PoolLedger] = e match
    case i: EraNes.InEra[x] =>
      i.era match
        case ShelleyBasedEra.SConway => Right(MempoolLedger.of(i.nes))
        case other =>
          PreConway
            .of(other)
            .toRight(ConwayView.Unavailable.InEarlier(EraNes.eraOf(other)))
            .map(pre =>
              EarlierLedger[x](pre, i.nes.es.ls, i.nes.es.chainAccount, i.nes.epoch.toBigInt)
            )

  /** The ledger of the chain whose state is `s` at its tip `tip`, ticked to the slot after the tip
    * (slot zero at Origin) by the hard-fork combinator's tick ([[Hfc.tick]]), which translates the
    * state into the era that slot is in. `transition` is the hard-fork configuration's
    * (`HardForkConfig.transition`). A tick failure is an internal failure of the ledger, distinct
    * from a chain that has no ledger the pool validates against.
    */
  def at[S: ChainRead](
      s: S,
      tip: Option[BlockRef],
      env: MempoolEnv,
      transition: Hfc.Transition
  ): Either[TickFailure, Either[ConwayView.Unavailable, PoolLedger]] =
    s.ledger(tip) match
      case Left(error) => Right(Left(ConwayView.Unavailable.Refused(error)))
      case Right(None) => Right(Left(ConwayView.Unavailable.InByron))
      case Right(Some(InEra.Of(era, read))) =>
        read.nes match
          case Left(error) => Right(Left(ConwayView.Unavailable.Refused(error)))
          case Right(nes) =>
            ticked(EraNes.InEra(era, nes), s.afterVoting, tip.map(_.slot), env, transition)

  /** [[at]] of the new-epoch state `start` of a chain whose tip is at `tipSlot` (none at Origin)
    * and has `afterVoting` blocks after the voting deadline of its epoch.
    */
  def ticked(
      start: EraNes,
      afterVoting: Long,
      tipSlot: Option[BigInt],
      env: MempoolEnv,
      transition: Hfc.Transition
  ): Either[TickFailure, Either[ConwayView.Unavailable, PoolLedger]] =
    for
      slot <- SlotNo.fromBigInt(tipSlot.fold(BigInt(0))(_ + 1)).leftMap(TickFailure.Vocab(_))
      next <- Hfc.tick(env.rules, transition)(start, afterVoting, slot)
    yield of(next)

/** The ledger of an era from Shelley to Babbage, whose transactions LEDGER applies
  * (`ruleApplyTxValidation @"LEDGER"`, `Shelley/API/Mempool.hs:230-262`, cardano-ledger f649f975):
  * the ledger state ticked to the slot after the tip, the chain account the MIR certificates draw
  * on, and the epoch.
  */
final case class EarlierLedger[E <: EraTag](
    pre: PreConway[E],
    ls: LedgerState[E],
    account: ChainAccountState,
    epoch: BigInt
) extends PoolLedger:
  def era: Era = EraNes.eraOf(pre.era)

  def params: ProtocolParams = pre.curPParams.get(ls.utxo)

  def utxo: Utxo = ls.utxo.utxo.utxo

  def withUtxo(u: Utxo): EarlierLedger[E] =
    copy(ls = ls.copy[E](utxo = ls.utxo.copy[E](utxo = UtxoTables(u))))

  def submit(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, EarlierLedger[E]] =
    judged(tx, env, rules)

  def reapply(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, EarlierLedger[E]] =
    judged(tx, env.copy(validation = TxEnv.Validation.NonStatic), rules)

  def admit(
      tx: Tx,
      env: TxEnv,
      rules: Globals,
      source: Source
  ): Either[LedgerFailure, (EarlierLedger[E], Tx)] =
    PoolLedger.admitting(era, tx, source)(submit(_, env, rules))

  private def judged(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, EarlierLedger[E]] =
    Judgment
      .run(ShelleyLedger.sts[E](using pre), Judgment.Policy.Accumulate)(
        rules,
        ShelleyLedgerEnv(env, account),
        Counted(ls, PlutusStats.zero),
        tx
      )
      .flatMap(_.leftMap(LedgerFailure.Rejected(tx.body.id, _)))
      .map(after => copy(ls = after.state))

/** The ledger a transaction is validated against: the Conway ledger state at the chain tip, the
  * treasury of the chain account (the transaction's `currentTreasuryValue` is checked against it)
  * and the epoch. The mempool is Conway's: the transaction is `[body, witnesses, is_valid,
  * auxiliary_data]`, and the rules are those of the era-indexed state ([[Ledger]]).
  */
final case class MempoolLedger(ls: LedgerState[Conway], treasury: Coin, epoch: BigInt)
    extends PoolLedger:
  def era: Era = Era.Conway

  /** The protocol parameters in force. */
  def params: xyz.kd5ujc.scarab.ledger.ProtocolParams = ls.utxo.gov.curPParams

  /** The handle on the UTxO set. */
  def utxo: Utxo = ls.utxo.utxo.utxo

  def withUtxo(u: Utxo): MempoolLedger =
    copy(ls = ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(u))))

  /** A transaction validated before, applied again: MEMPOOL and LEDGER without their static checks
    * (`reapplyTx`, ouroboros-consensus 82ecba32 `Shelley/Ledger/Mempool.hs` 325–338; cardano-ledger
    * f649f975 `Shelley/API/Mempool.hs` 472–473, whose Conway rule is MEMPOOL, `Conway.hs` 57). The
    * pool revalidates its transactions so, and so does block production (`revalidateTxsFor`,
    * `Mempool/Impl/Common.hs` 403–422): no signature is verified and no script is evaluated again
    * ([[TxEnv.Validation]]). Its refusal holds every predicate failure, in the reference's order,
    * as `applySTSNonStatic` returns them (`Shelley/API/Mempool.hs`; issue 412).
    */
  def reapply(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, MempoolLedger] =
    judged(Mempool, Judgment.Policy.Accumulate)(
      tx,
      env.copy(validation = TxEnv.Validation.NonStatic),
      rules
    )

  /** The transaction applied by the MEMPOOL rule of the ledger (`Conway/Rules/Mempool.hs`), whose
    * refusal holds every predicate failure the reference reports, in its order.
    */
  def submit(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, MempoolLedger] =
    judged(Mempool, Judgment.Policy.Accumulate)(tx, env, rules)

  private def judged(
      rule: JudgedSts[LedgerEnv, ConwayLedger, Tx, PredicateFailure, LedgerFailure],
      policy: Judgment.Policy
  )(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, MempoolLedger] =
    Judgment
      .run(rule, policy)(rules, LedgerEnv(env, treasury), Counted(ls, PlutusStats.zero), tx)
      .flatMap(_.leftMap(LedgerFailure.Rejected(tx.body.id, _)))
      .map(after => copy(ls = after.state))

  /** The transaction admitted from `source`, with the transaction as it is to be held (its
    * `is_valid` possibly changed). A local transaction is applied as received. A remote one is
    * applied with `is_valid` true; when exactly that flag is what the rules refuse, because a
    * script fails (`UtxosFailure.ValidationTagMismatch(true, _)`), it is applied again with
    * `is_valid` false and that result is the answer. This is `applyAlonzoBasedTx` with
    * `DoNotIntervene`
    * (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Eras.hs` at
    * `82ecba329d7d054340bf707d44fe6e9ac27cec40`, `isIncorrectClaimedFlag`). The reference takes a
    * refusal that is the only element of the failure list, and so does this one
    * ([[LedgerFailure.Rejected.Single]]): the scripts run only when nothing failed before them
    * (`whenFailureFree`), so a mismatch is the only failure when it is the reason of the refusal.
    */
  def admit(
      tx: Tx,
      env: TxEnv,
      rules: Globals,
      source: Source
  ): Either[LedgerFailure, (MempoolLedger, Tx)] =
    PoolLedger.admitting(era, tx, source)(submit(_, env, rules))

object MempoolLedger:
  /** The ledger of the new-epoch state at the tip. */
  def of(nes: NewEpochState[Conway]): MempoolLedger =
    MempoolLedger(nes.es.ls, nes.es.chainAccount.treasury, nes.epoch.toBigInt)

  /** Tick the complete Conway state before projecting its transaction view. This is
    * `tickLedgerState (ForgeInUnknownSlot _)` in consensus Mempool/Impl/Common.hs: the next
    * available slot, including epoch transitions, rewards, certificate state and treasury. `None`
    * denotes Origin, whose next available slot is zero. The selected state's epoch agrees with its
    * tip, so its successor crosses at most one epoch boundary. Conway is the final era: no
    * hard-fork translation precedes this tick. Reference: ouroboros-consensus
    * `82ecba329d7d054340bf707d44fe6e9ac27cec40`, `Mempool/Impl/Common.hs`, `tickLedgerState`.
    */
  def tick(
      nes: NewEpochState[Conway],
      tipSlot: Option[BigInt],
      env: MempoolEnv
  ): Either[TickFailure, MempoolLedger] =
    for
      slot <- SlotNo.fromBigInt(tipSlot.fold(BigInt(0))(_ + 1)).leftMap(TickFailure.Vocab(_))
      next <- Sts(Tick.sts[Conway])(env.rules, (), nes, slot)
    yield of(next)

  /** The next-slot transaction view of a selected chain. A tick failure is an internal ledger
    * failure, distinct from the chain being in an unsupported era.
    */
  def at[S: ChainRead](
      s: S,
      tip: Option[BlockRef],
      env: MempoolEnv
  ): Either[TickFailure, Either[ConwayView.Unavailable, MempoolLedger]] =
    ConwayView(s, tip).traverse(tick(_, tip.map(_.slot), env))

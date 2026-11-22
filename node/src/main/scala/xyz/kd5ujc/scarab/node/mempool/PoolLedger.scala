// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{
  ChainError,
  ForgeError,
  ForgeProtocolProblem,
  LedgerFailure,
  NodeError,
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
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import cats.data.NonEmptyChain
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraPParams,
  BothLevels,
  EraPParams,
  EraTx,
  EraTxBody,
  IsPhase2Valid,
  PParams,
  Proposals,
  StAnnTx,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.Tx as ETx
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayUtxosPredFailure,
  DijkstraClosure,
  DijkstraLedgerPredFailure,
  DijkstraMempoolPredFailure,
  DijkstraMempoolRun,
  DijkstraUtxoPredFailure,
  DijkstraUtxowPredFailure,
  InjectMempoolFailure
}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.ledger.era.Mismatch
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure

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
  * `Conway.hs:50-59`), and Dijkstra's MEMPOOL in the Leios build ([[DijkstraLedger]];
  * `Dijkstra.hs:87-96`, cardano-ledger 9df9f98c).
  */
sealed trait PoolLedger:
  /** The era of the ledger, which every transaction the pool holds is of. */
  def era: Era

  /** The protocol parameters in force, in the node's record. */
  def params: ProtocolParams

  /** The handle on the UTxO set. */
  def utxo: Utxo

  /** The epoch of the ticked state. */
  def epoch: BigInt

  def withUtxo(u: Utxo): PoolLedger

/** The ledger of an era from Shelley to Conway, whose transactions are the node's flat ones
  * ([[Tx]]).
  */
sealed trait FlatLedger extends PoolLedger:
  def withUtxo(u: Utxo): FlatLedger

  /** The transaction applied with every check (`applyTx`, `ValidateAll`). */
  def submit(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, FlatLedger]

  /** A transaction validated before, applied again without the static checks (`reapplyTx`,
    * `ValidateSuchThat (notElem lblStatic)`, `Shelley/API/Mempool.hs:472-473`, cardano-ledger
    * f649f975).
    */
  def reapply(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, FlatLedger]

  /** The transaction admitted from `source`, with the transaction as it is to be held. */
  def admit(
      tx: Tx,
      env: TxEnv,
      rules: Globals,
      source: Source
  ): Either[LedgerFailure, (FlatLedger, Tx)]

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

  /** Why the pool has no ledger of a chain that the node stops for: the tick to the slot after the
    * tip failed, an internal failure of the ledger; or the parameters of a Dijkstra state set an
    * endorser block the LeiosFetch codec cannot carry (`guardLeiosWireLimit`'s `error`,
    * ouroboros-consensus 2416493b `Shelley/Ledger/Mempool.hs:915-939`, [[DijkstraLedger.of]]).
    */
  enum Failure:
    case Tick(cause: TickFailure)
    case WireLimit(cause: Capacity.EndorserBlockReferencesOverWire)

    /** The node's stop for the pool's ledger of the slot `slot`: the tick's failure
      * ([[ChainError.MempoolTick]]), or the parameters the forger stops for too
      * ([[ForgeProtocolProblem.EndorserBlockReferencesOverWire]]).
      */
    def stop(slot: BigInt): NodeError = this match
      case Tick(cause) => ChainError.MempoolTick(slot, cause)
      case WireLimit(over) =>
        ForgeError.UnsupportedProtocol(
          ForgeProtocolProblem.EndorserBlockReferencesOverWire(over.parameter, over.wire)
        )

  /** The pool's ledger of a new-epoch state of any era. A Dijkstra state has one in the Leios
    * build, whose mempool takes Dijkstra transactions (`LedgerSupportsMempool (ShelleyBlock proto
    * DijkstraEra)`, ouroboros-consensus 2416493b `Shelley/Ledger/Mempool.hs:214-245`), and none in
    * the release builds, whose node never enters Dijkstra (REF-3).
    */
  def of(
      e: EraNes,
      build: ReferenceBuild
  ): Either[Failure, Either[ConwayView.Unavailable, PoolLedger]] = e match
    case i: EraNes.InEra[x] =>
      i.era match
        case ShelleyBasedEra.SConway => Right(Right(MempoolLedger.of(i.nes)))
        case ShelleyBasedEra.SDijkstra =>
          build match
            case ReferenceBuild.LeiosB004fe0c =>
              DijkstraLedger.of(i.nes).bimap(Failure.WireLimit(_), Right(_))
            case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 =>
              Right(Left(ConwayView.Unavailable.InEarlier(Era.Dijkstra)))
        case other =>
          Right(
            PreConway
              .of(other)
              .toRight(ConwayView.Unavailable.InEarlier(EraNes.eraOf(other)))
              .map(pre =>
                EarlierLedger[x](pre, i.nes.es.ls, i.nes.es.chainAccount, i.nes.epoch.toBigInt)
              )
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
  ): Either[Failure, Either[ConwayView.Unavailable, PoolLedger]] =
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
  ): Either[Failure, Either[ConwayView.Unavailable, PoolLedger]] =
    (for
      slot <- SlotNo.fromBigInt(tipSlot.fold(BigInt(0))(_ + 1)).leftMap(TickFailure.Vocab(_))
      next <- Hfc.tick(env.rules, transition)(start, afterVoting, slot)
    yield next).leftMap(Failure.Tick(_)).flatMap(of(_, env.globals.referenceBuild))

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
) extends FlatLedger:
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
    extends FlatLedger:
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

/** The state a Dijkstra transaction is judged against, and the rules that judge it
  * (`LedgerSupportsMempool (ShelleyBlock proto DijkstraEra)`, ouroboros-consensus 2416493b
  * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs:214-245`):
  * the ledger state (`mkMempoolState`, cardano-ledger 9df9f98c `Shelley/API/Mempool.hs:384-385`)
  * and the chain account of a state ticked to the slot judged at, its parameters and proposals
  * those of its governance state, the era's record. The pool's ledger holds one
  * ([[DijkstraLedger]]), and so does a forging snapshot, judged at the slot it forges in.
  */
final case class DijkstraState private (ls: LedgerState[Dijkstra], account: ChainAccountState):
  def utxo: Utxo = ls.utxo.utxo.utxo

  def withUtxo(u: Utxo): DijkstraState =
    copy(ls = ls.copy[Dijkstra](utxo = ls.utxo.copy[Dijkstra](utxo = UtxoTables(u))))

  private def unjudged(id: Bytes)(cause: DijkstraUnjudged): Rejection =
    Rejection.DijkstraUnjudged(Some(id), cause)

  private def refused(id: Bytes)(failures: NonEmptyChain[DijkstraMempoolPredFailure[Dijkstra]]) =
    Rejection.DijkstraRefused(id, failures)

  /** The state's parameters and proposals: its governance state's. */
  private def readable: DijkstraState.Readable =
    DijkstraState.Readable(ls.utxo.gov.cgsCurPParams, ls.utxo.gov.cgsProposals)

  /** The stored outputs of the transaction's inputs, read into the era. */
  private def utxoOf(tx: ETx[TxLevel.Top, Dijkstra]): Either[Rejection, UTxO[Dijkstra]] =
    DijkstraMempoolRun
      .utxoOf(ls, tx)
      .leftMap(d => unjudged(tx.id)(DijkstraUnjudged.UtxoState(d)))

  /** `txMeasurePhase1` of Dijkstra (`txMeasureAlonzo`, ouroboros-consensus 2416493b
    * `Shelley/Ledger/Mempool.hs:654-676`): the size (`sizeTxF`) at most `maxTxSize`
    * (`txInBlockSize`, `:445-458`) and the execution units (`totExUnits`, which is
    * `getTotalExUnits`, cardano-ledger 9df9f98c `Alonzo/Tx.hs:510-515`) pointwise at most
    * `maxTxExUnits`, the two accumulated, size first, as `Validation` accumulates them; each
    * failure Dijkstra's (`MaxTxSizeUTxO DijkstraEra`, `:536-546`; `ExUnitsTooBigUTxO DijkstraEra`,
    * `:718-728`). The measure's bytes are the size plus [[Capacity.PerTxOverhead]].
    */
  def phase1(t: DijkstraRead): Either[Rejection, TxMeasure] =
    val r = readable
    val size = BigInt(t.size)
    val maxTxSize = EraPParams.ppMaxTxSizeL[Dijkstra].get(r.pp).value
    val units = EraTx.dijkstra.getTotalExUnits[TxLevel.Top](t.tx)(using BothLevels.Top)
    val maxTxExUnits = AlonzoEraPParams.ppMaxTxExUnitsL[Dijkstra].get(r.pp).units
    val inject = InjectMempoolFailure.dijkstraUtxo
    val tooBig = Option.unless(size <= maxTxSize)(
      inject.injectFailure(
        DijkstraUtxoPredFailure.MaxTxSizeUTxO[Dijkstra](Mismatch(size, maxTxSize))
      )
    )
    val tooMuch = Option.unless(units.fits(maxTxExUnits))(
      inject.injectFailure(
        DijkstraUtxoPredFailure.ExUnitsTooBigUTxO[Dijkstra](Mismatch(units, maxTxExUnits))
      )
    )
    NonEmptyChain
      .fromSeq(tooBig.toList ++ tooMuch.toList)
      .map(refused(t.id))
      .toLeft(TxMeasure(t.size + Capacity.PerTxOverhead, units.mem, units.steps, 0L))

  /** `txMeasurePhase2` of Dijkstra (`txMeasureRefScripts`, `Shelley/Ledger/Mempool.hs:775-798`):
    * the reference-script bytes of the top-level transaction's spent and reference inputs
    * (`txNonDistinctRefScriptsSize`, cardano-ledger 9df9f98c `Conway/UTxO.hs:161-170`, not the
    * sub-transactions', which LEDGER counts) in the state after the held transactions, at most
    * `maxRefScriptSizePerTx` of the parameters (`ppMaxRefScriptSizePerTxG`), or Dijkstra's
    * `DijkstraTxRefScriptsSizeTooBig` (`:812-821`).
    */
  def phase2(t: DijkstraRead): Either[Rejection, Long] =
    val r = readable
    for
      u <- utxoOf(t.tx)
      size = DijkstraState.refScriptsSize(u, t.tx)
      limit = EraPParams.dijkstra.ppMaxRefScriptSizePerTxG.get(r.pp).value.toLong
      _ <- Either.cond(
        size <= limit,
        (),
        refused(t.id)(
          NonEmptyChain.one(
            DijkstraMempoolPredFailure.LedgerFailure[Dijkstra](
              DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](
                Mismatch(size, limit)
              )
            )
          )
        )
      )
    yield size

  /** MEMPOOL on `stAnnTx` with `validation` ([[DijkstraMempoolRun.mempool]]), at `mkMempoolEnv` of
    * the slot (`Shelley/API/Mempool.hs:363-377`, cardano-ledger 9df9f98c;
    * [[DijkstraClosure.mkMempoolEnv]]): the ledger after it, its proposals the governance state's.
    */
  private def judged(
      id: Bytes,
      r: DijkstraState.Readable,
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      env: DijkstraPoolEnv,
      validation: TxEnv.Validation
  ): Either[Rejection, DijkstraState] =
    DijkstraMempoolRun
      .mempool(
        env.rules,
        DijkstraClosure.mkMempoolEnv(env.slot, r.pp, account),
        stAnnTx,
        env.context(validation),
        ls,
        r.proposals,
        env.evaluator,
        env.verdicts,
        env.decoder
      )
      .leftMap(d => unjudged(id)(DijkstraUnjudged.Rules(d)))
      .flatMap(_.leftMap(refused(id)))
      .map((after, proposals) =>
        copy(ls =
          after.copy[Dijkstra](utxo =
            after.utxo.copy[Dijkstra](gov = after.utxo.gov.copy(cgsProposals = proposals))
          )
        )
      )

  /** `applyTxWithFullValidation` (`Shelley/API/Mempool.hs:406-413`, cardano-ledger 9df9f98c) of `t`
    * as it is (`defaultApplyShelleyBasedTx`, ouroboros-consensus 2416493b
    * `Shelley/Eras.hs:172-190`): annotated against the state's UTxO set with an empty cache
    * (`defaultApplyTxWithValidation`, `:267-296`), then MEMPOOL with every check; the ledger after
    * it, and the transaction as held with what it was validated with.
    */
  def applyFull(
      t: DijkstraRead,
      env: DijkstraPoolEnv
  ): Either[Rejection, (DijkstraState, DijkstraAdmitted)] =
    validatedFull(t.tx, env).map((next, validated) => (next, DijkstraAdmitted(t, validated)))

  /** [[applyFull]] of a transaction in the era's types: the ledger after it, and what it was
    * validated with.
    */
  private def validatedFull(
      tx: ETx[TxLevel.Top, Dijkstra],
      env: DijkstraPoolEnv
  ): Either[Rejection, (DijkstraState, DijkstraValidated)] =
    val r = readable
    for
      u <- utxoOf(tx)
      stAnnTx = DijkstraMempoolRun.annotate(env.clock.posix, r.pp, u, tx)
      next <- judged(tx.id, r, stAnnTx, env, TxEnv.Validation.All)
    yield (
      next,
      DijkstraValidated(stAnnTx, EraPParams.dijkstra.ppProtocolVersionL.get(r.pp), env.slot)
    )

  /** `applyTx lcfg Intervene slot tx` of a transaction of an endorser block's closure
    * (`validateEbClosure`, `LeiosVoting.hs:519-542`, ouroboros-consensus 2416493b): the transaction
    * with the validity flag the block declares, applied as it is with every check ([[applyFull]]);
    * `Intervene` leaves the flag alone, so a transaction whose flag its scripts contradict is
    * refused, not applied as a collateral-only one. The ledger after it.
    */
  def applyIntervening(
      tx: ETx[TxLevel.Top, Dijkstra],
      env: DijkstraPoolEnv
  ): Either[Rejection, DijkstraState] =
    validatedFull(tx, env).map(_._1)

  /** `reapplyTx lcfg slot (assumeValidatedClosureTx lcfg slot st tx) st` of a transaction of an
    * endorser block's closure that the node's [[xyz.kd5ujc.scarab.node.LeiosTxCache]] holds as
    * validated under the protocol version's parity of this state (`validateEbClosure`,
    * `LeiosVoting.hs:511-518`, ouroboros-consensus 2416493b): the token is made against this state
    * (`assumeValidatedClosureTx`, `Shelley/Ledger/Leios.hs:179-191`), the transaction annotated
    * against its UTxO set with an empty cache at its parameters' protocol version
    * (`unsafeMakeValidatedTx`, `Shelley/API/Mempool.hs:132-151`, cardano-ledger 9df9f98c); so
    * `reapplyValidatedTx` (`:420-440`) meets the same major version and runs MEMPOOL without the
    * static checks (`ValidateSuchThat (notElem lblStatic)`, `defaultReapplyValidatedTx`,
    * `:298-323`): no witness is checked and no script evaluated, the validity flag taken as the
    * block declares it. The ledger after it.
    */
  def reapplyIntervening(
      tx: ETx[TxLevel.Top, Dijkstra],
      env: DijkstraPoolEnv
  ): Either[Rejection, DijkstraState] =
    val r = readable
    for
      u <- utxoOf(tx)
      stAnnTx = DijkstraMempoolRun.annotate(env.clock.posix, r.pp, u, tx)
      next <- judged(tx.id, r, stAnnTx, env, TxEnv.Validation.NonStatic)
    yield next

  /** The major protocol version of the state's parameters (`leiosCurrentProtVer`,
    * `Shelley/Ledger/Leios.hs:193-194`, ouroboros-consensus 2416493b).
    */
  def protocolMajor: Int =
    EraPParams.dijkstra.ppProtocolVersionL.get(readable.pp).pvMajor.value

  /** `applyTx` of Dijkstra (`applyShelleyTx`, `Shelley/Ledger/Mempool.hs:331-366`, through
    * `applyAlonzoBasedTx`, `Shelley/Eras.hs:291-342`, ouroboros-consensus 2416493b): a local
    * transaction (`Intervene`) applied as it is; a peer's (`DoNotIntervene`) applied with its flag
    * `Phase2Valid`, and, when the only failure is UTXOS's `ValidationTagMismatch`
    * (`isIncorrectClaimedFlag` of Dijkstra, `:409-420`, whatever the flag it names), again with
    * `Phase2Invalid`, that result the answer.
    */
  def admit(
      t: DijkstraRead,
      env: DijkstraPoolEnv,
      source: Source
  ): Either[Rejection, (DijkstraState, DijkstraAdmitted)] =
    source match
      case Source.Local => applyFull(t, env)
      case Source.Remote =>
        applyFull(t.withIsValid(IsPhase2Valid.Phase2Valid), env) match
          case Left(Rejection.DijkstraRefused(_, failures))
              if DijkstraState.isIncorrectClaimedFlag(failures) =>
            applyFull(t.withIsValid(IsPhase2Valid.Phase2Invalid), env)
          case other => other

  /** `reapplyTx` of Dijkstra (`reapplyShelleyTx`, `Shelley/Ledger/Mempool.hs:368-392`), which is
    * `reapplyValidatedTx` (`Shelley/API/Mempool.hs:420-440`, cardano-ledger 9df9f98c): under the
    * major protocol version `held` was validated at, MEMPOOL without the static checks on the
    * annotation of its validation (`defaultReapplyValidatedTx`, `:298-323`); under another, with
    * every check on the transaction annotated again. The transaction stays as held.
    */
  def reapply(held: DijkstraAdmitted, env: DijkstraPoolEnv): Either[Rejection, DijkstraState] =
    val id = held.read.id
    val r = readable
    val current = EraPParams.dijkstra.ppProtocolVersionL.get(r.pp)
    if current.pvMajor == held.validated.protocolVersion.pvMajor then
      judged(id, r, held.validated.stAnnTx, env, TxEnv.Validation.NonStatic)
    else applyFull(held.read, env).map(_._1)

object DijkstraState:
  /** The state's parameters and proposals in the era's types. */
  final case class Readable(pp: PParams[Dijkstra], proposals: Proposals[Dijkstra])

  /** The state of `nes` a Dijkstra transaction is judged against (`mkMempoolState`,
    * `Shelley/API/Mempool.hs:384-385`, cardano-ledger 9df9f98c): its ledger state and chain
    * account.
    */
  def of(nes: NewEpochState[Dijkstra]): DijkstraState =
    DijkstraState(nes.es.ls, nes.es.chainAccount)

  /** `isIncorrectClaimedFlag` of Dijkstra (`Shelley/Eras.hs:409-420`, ouroboros-consensus
    * 2416493b): the only failure is UTXOS's `ValidationTagMismatch`, under LEDGER's UTXOW and UTXO,
    * whatever the flag it names.
    */
  def isIncorrectClaimedFlag(
      failures: NonEmptyChain[DijkstraMempoolPredFailure[Dijkstra]]
  ): Boolean =
    failures.length == 1 && (failures.head match
      case DijkstraMempoolPredFailure.LedgerFailure(
            DijkstraLedgerPredFailure.DijkstraUtxowFailure(
              DijkstraUtxowPredFailure.UtxoFailure(
                DijkstraUtxoPredFailure.UtxosFailure(
                  ConwayUtxosPredFailure.ValidationTagMismatch(_, _)
                )
              )
            )
          ) =>
        true
      case _ => false)

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** `txNonDistinctRefScriptsSize` of the top-level transaction (`Conway/UTxO.hs:161-170`,
    * cardano-ledger 9df9f98c), as `txMeasureRefScripts` reads it.
    */
  private[mempool] def refScriptsSize(utxo: UTxO[Dijkstra], tx: ETx[TxLevel.Top, Dijkstra]): Long =
    ConwayUTxO.txNonDistinctRefScriptsSize[Dijkstra, TxLevel.Top](utxo, tx)(using t, b)

  /** The inputs at every level whose stored outputs a held transaction's rules read
    * (`getTransactionKeySets`, `allInputsTxBodyF`, ouroboros-consensus 2416493b
    * `Shelley/Ledger/Mempool.hs:226-230`).
    */
  def readSet(txs: Iterable[DijkstraRead]): Vector[TxIn] =
    txs.iterator
      .flatMap(r => t.bodyOf.allInputs(t.body[TxLevel.Top](r.tx)))
      .toVector

/** The pool's ledger of Dijkstra: the state of the new-epoch state ticked to the slot after the tip
  * ([[DijkstraState]]), the epoch and the parameters in the node's record of that state, and
  * `reservation`, one endorser block's share of the pool (`mempoolEbReservation` of
  * `ebCapacityTxMeasure`, [[Capacity.endorserBlockReservation]]), which passed
  * `guardLeiosWireLimit` when the ledger was made ([[DijkstraLedger.of]]).
  */
final case class DijkstraLedger private (
    state: DijkstraState,
    epoch: BigInt,
    params: ProtocolParams,
    reservation: TxMeasure
) extends PoolLedger:
  def era: Era = Era.Dijkstra

  def utxo: Utxo = state.utxo

  def withUtxo(u: Utxo): DijkstraLedger = copy(state = state.withUtxo(u))

  def phase1(t: DijkstraRead): Either[Rejection, TxMeasure] = state.phase1(t)

  def phase2(t: DijkstraRead): Either[Rejection, Long] = state.phase2(t)

  def admit(
      t: DijkstraRead,
      env: DijkstraPoolEnv,
      source: Source
  ): Either[Rejection, (DijkstraLedger, DijkstraAdmitted)] =
    state.admit(t, env, source).map((next, held) => copy(state = next) -> held)

  def reapply(held: DijkstraAdmitted, env: DijkstraPoolEnv): Either[Rejection, DijkstraLedger] =
    state.reapply(held, env).map(next => copy(state = next))

object DijkstraLedger:
  /** The pool's ledger of the new-epoch state `nes`, ticked to the slot after the tip: its
    * endorser-block reservation first passes `guardLeiosWireLimit` (`Shelley/Ledger/Mempool.hs:
    * 915-939`, ouroboros-consensus 2416493b), which the reference applies where the mempool
    * computes its capacity from the state (`computeMempoolCapacity`, `Mempool/Capacity.hs:69-82`,
    * into the strict `isCapacity` of `initInternalState` and of every revalidation,
    * `Mempool/Impl/Common.hs:185, 258, 639`), so that parameters the wire cannot carry stop the
    * node when the pool first learns them.
    */
  def of(
      nes: NewEpochState[Dijkstra]
  ): Either[Capacity.EndorserBlockReferencesOverWire, DijkstraLedger] =
    val params = EraLedger.curPParams(nes.es.ls.utxo.gov)
    Capacity
      .endorserBlockReservation(params)
      .map(DijkstraLedger(DijkstraState.of(nes), nes.epoch.toBigInt, params, _))

  /** The inputs at every level whose stored outputs held transactions' rules read. */
  def readSet(txs: Iterable[DijkstraRead]): Vector[TxIn] = DijkstraState.readSet(txs)

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  Certificate,
  LedgerFailure,
  PredicateFailure,
  Purpose,
  ProtocolParams,
  Tx,
  TxEnv
}
import xyz.kd5ujc.scarab.ledger.state.*

// LEDGER, LEDGERS and BBODY for the eras before Conway (`Shelley/Rules/Ledger.hs`, `Ledgers.hs`,
// `Bbody.hs`, with `Alonzo/Rules/Ledger.hs`, `Bbody.hs`), on the ledger state counted with the
// node's phase-2 counters ([[Counted]]).

/** `LedgerEnv` (`Shelley/Rules/Ledger.hs`): the block-level context of a transaction (slot, epoch,
  * the position in the block, the evaluator and the clock, `TxEnv`) and the chain account
  * (`esChainAccountState`), which the MIR certificates draw on.
  */
final case class ShelleyLedgerEnv(ctx: TxEnv, account: ChainAccountState)

/** LEDGER (`Shelley/Rules/Ledger.hs:300`, `ledgerTransition`, and `Alonzo/Rules/Ledger.hs:111`,
  * cardano-ledger `f649f975`): for a transaction whose scripts pass (phase-2 valid; every one
  * before Alonzo) the withdrawals are checked against the accounts
  * (`testIncompleteAndMissingWithdrawals`, `Shelley/Rules/Ledger.hs:343-361`, each failure
  * recorded) and drained, and the certificates run (DELEGS); then, for every transaction, UTXOW.
  * DELEGS and UTXOW always run (`trans`). The environment of UTXOW holds the certificate state
  * *before* the transaction, which is what its refunds and the genesis delegates it checks
  * signatures of come from. A transaction that failed phase 2 only collects its collateral (UTXOW).
  */
object ShelleyLedger:
  def sts[E <: EraTag](using
      pre: PreConway[E]
  ): JudgedSts[ShelleyLedgerEnv, Counted[LedgerState[E]], Tx, PredicateFailure, LedgerFailure] =
    new JudgedSts[ShelleyLedgerEnv, Counted[LedgerState[E]], Tx, PredicateFailure, LedgerFailure]:
      private val certs: monocle.Lens[Counted[LedgerState[E]], ShelleyCertState] =
        Counted.state[LedgerState[E]].andThen(pre.cert)

      private given Embed[ShelleyCertState, Counted[LedgerState[E]], LedgerFailure, LedgerFailure] =
        Embed(certs, identity)

      private given Embed[Counted[UTxOState[E]], Counted[
        LedgerState[E]
      ], LedgerFailure, LedgerFailure] =
        Embed(Counted.utxoOfEra[E], identity)

      def transition[F[_]: Monad](env: ShelleyLedgerEnv, tx: Tx)(using
          ask: Ask[F, Globals],
          st: Stateful[F, Counted[LedgerState[E]]],
          raise: Raise[F, LedgerFailure],
          judge: Judge[F, PredicateFailure]
      ): F[Unit] =
        val ctx = env.ctx
        val id = tx.body.id
        for
          before <- st.get
          pp = pre.curPParams.get(before.state.utxo)
          certBefore = pre.cert.get(before.state)
          utxo = before.state.utxo.utxo.utxo
          _ <-
            if !tx.isValid then Monad[F].unit
            else
              for
                globals <- Sts.globals[F]
                view <- Refuse.vocab[F, Withdrawals.View](
                  Withdrawals.viewOf(
                    ShelleyCertLens.states.get(certBefore),
                    tx.body.withdrawals,
                    globals.networkId
                  )(_.balance.toBigInt)
                )
                _ <- Withdrawals.record[F](view)
                drained <- Refuse.vocab[F, ShelleyCertState](
                  Purpose
                    .sortedWithdrawals(tx.body)
                    .traverse((a, _) => Convert.credential[Staking](a.credential))
                    .map(ShelleyCertOps.drain(certBefore, _))
                )
                _ <- st.modify(certs.replace(drained))
                _ <- JudgedSts.trans[F, DelegsEnv, ShelleyCertState, Counted[
                  LedgerState[E]
                ], Vector[Certificate], PredicateFailure, LedgerFailure, LedgerFailure](
                  ShelleyDelegs,
                  DelegsEnv(ctx, pp, id, env.account),
                  tx.body.certificates
                )
              yield ()
          _ <- JudgedSts.trans[F, UtxoEnv[ShelleyCertState], Counted[
            UTxOState[E]
          ], Counted[LedgerState[E]], StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](
            ShelleyUtxow.sts[E],
            UtxoEnv(ctx, pp, certBefore),
            StAnnTx(tx, utxo)
          )
        yield ()

/** LEDGERS (`Shelley/Rules/Ledgers.hs`): the transactions of a block, in order, each by LEDGER with
  * its position in the block. The block's verdict is its first failure, so a transaction's rules
  * run to their first failure ([[Judge.raising]]), raised as the transaction's.
  */
object ShelleyLedgers:
  def sts[E <: EraTag](using
      PreConway[E]
  ): Sts[ShelleyLedgerEnv, Counted[LedgerState[E]], Vector[Tx], LedgerFailure] =
    new Sts[ShelleyLedgerEnv, Counted[LedgerState[E]], Vector[Tx], LedgerFailure]:
      def transition[F[_]: Monad](env: ShelleyLedgerEnv, txs: Vector[Tx])(using
          ask: Ask[F, Globals],
          st: Stateful[F, Counted[LedgerState[E]]],
          raise: Raise[F, LedgerFailure]
      ): F[Unit] =
        txs.zipWithIndex.traverse_ { (tx, i) =>
          ShelleyLedger
            .sts[E]
            .transition[F](env.copy(ctx = env.ctx.copy(txIndex = i)), tx)(using
              summon[Monad[F]],
              ask,
              st,
              raise,
              Judge.raising[F, PredicateFailure, LedgerFailure](
                LedgerFailure.Rejected(tx.body.id, _)
              )
            )
        }

/** `BbodyEnv` (`Shelley/Rules/Bbody.hs`): the parameters in force, the chain account, and the
  * block-level context of its transactions.
  */
final case class ShelleyBbodyEnv(ctx: TxEnv, pp: ProtocolParams, account: ChainAccountState)

/** `ShelleyBbodyState` (`Shelley/Rules/Bbody.hs`): the ledger state, and the blocks the pools have
  * made in the epoch (`nesBcur`).
  */
final case class ShelleyBbodyState[E <: EraTag](
    ledger: Counted[LedgerState[E]],
    blocksMade: BlocksMade
)

/** BBODY (`Shelley/Rules/Bbody.hs:207`, `bbodyTransition`, and `Alonzo/Rules/Bbody.hs:169`): the
  * body of a block against what its header says, the execution units of the transactions against
  * the limit of the block (Alonzo on), LEDGERS, and the block counted for its issuer unless the
  * slot is one of the overlay schedule (`incrBlocks`, `BlockBody/Internal.hs:295`: a block of the
  * overlay is made by a genesis delegate, whose count the pool performance does not use). Babbage
  * has no decentralisation parameter (`ppDG` is zero), so its slots have no overlay.
  *
  * The signal is Conway's ([[BbodySignal]]); LEDGERS runs on the ledger part of the state, its
  * failures wrapped as the block's (`BlockFailure.Tx`).
  */
object ShelleyBbody:
  /** `isOverlaySlot` (`Slot.hs:117-130`): the slot is reserved for the overlay schedule of
    * decentralisation `d`, `firstSlot` being the first slot of its epoch.
    */
  def isOverlaySlot(firstSlot: BigInt, d: Rational, slot: BigInt): Boolean =
    val s = slot - firstSlot
    (Rational(s) * d).ceil < (Rational(s + 1) * d).ceil

  def sts[E <: EraTag](using
      PreConway[E]
  ): Sts[ShelleyBbodyEnv, ShelleyBbodyState[E], BbodySignal, BlockFailure] =
    new Sts[ShelleyBbodyEnv, ShelleyBbodyState[E], BbodySignal, BlockFailure]:
      private given Embed[Counted[LedgerState[E]], ShelleyBbodyState[
        E
      ], LedgerFailure, BlockFailure] =
        Embed(
          monocle.Lens[ShelleyBbodyState[E], Counted[LedgerState[E]]](_.ledger)(l =>
            s => s.copy(ledger = l)
          ),
          BlockFailure.Tx(_)
        )

      def transition[F[_]: Monad](env: ShelleyBbodyEnv, signal: BbodySignal)(using
          ask: Ask[F, Globals],
          st: Stateful[F, ShelleyBbodyState[E]],
          raise: Raise[F, BlockFailure]
      ): F[Unit] =
        val pp = env.pp
        for
          _ <- Sts.check[F, BlockFailure](
            signal.declared == signal.actual,
            BlockFailure.BodyCommitment
          )
          _ <- Sts.require[F, BlockFailure](
            Bbody
              .exUnitsExceeded(pp, signal.txs)
              .map(BlockFailure.BlockExUnitsTooBig(_, pp.maxBlockExUnits))
          )
          globals <- Sts.globals[F]
          _ <- Sts.trans[F, ShelleyLedgerEnv, Counted[
            LedgerState[E]
          ], ShelleyBbodyState[E], Vector[Tx], LedgerFailure, BlockFailure](
            ShelleyLedgers.sts[E],
            ShelleyLedgerEnv(env.ctx, env.account),
            signal.txs
          )
          counted = !isOverlaySlot(globals.firstSlot(env.ctx.epoch), pp.effectiveD, env.ctx.slot)
          _ <- st.modify(s =>
            if !counted then s
            else
              s.copy(blocksMade =
                BlocksMade(
                  s.blocksMade.blocks.updatedWith(signal.issuer)(n =>
                    Some(Word64.fromUnsigned(n.fold(1L)(_.bits + 1)))
                  )
                )
              )
          )
        yield ()

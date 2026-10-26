// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  RefScripts,
  LedgerFailure,
  Mismatch,
  PredicateFailure,
  Proposals,
  Tx
}
import xyz.kd5ujc.scarab.ledger.state.*

/** The state the rules of a Conway transaction run on: the ledger state, with the node's phase-2
  * counters beside it ([[Counted]]).
  */
type ConwayLedger = Counted[LedgerState[Conway]]

/** LEDGER (`Conway/Rules/Ledger.hs:350`, `conwayLedgerTransitionTRC`, cardano-ledger `f649f975`): a
  * transaction applied to the ledger state. For a transaction whose scripts pass (phase-2 valid):
  * the treasury value and the reference scripts it declares and uses, the withdrawals (from
  * protocol version 10 only into accounts that delegate to a DRep; from 11 checked and drained
  * here, before that by CERTS), its certificates (CERTS), its proposals and votes (GOV); then, for
  * every transaction, UTXOW. A transaction that failed phase 2 only collects its collateral
  * (UTXOW). Every check records its failure and the rule goes on (`runTest`, `Ledger.hs:365-388`),
  * and CERTS, GOV and UTXOW always run (`trans`, `Ledger.hs:395, 410, 429`).
  *
  * The environment of UTXOW holds the certificate state *before* the transaction, which is what its
  * refunds are computed from; GOV sees the one after the certificates.
  */
object Ledger extends JudgedSts[LedgerEnv, ConwayLedger, Tx, PredicateFailure, LedgerFailure]:
  private val cert: monocle.Lens[ConwayLedger, ConwayCertState] =
    Counted.state[LedgerState[Conway]].andThen(ConwayLedgerState.cert)

  private val proposals: monocle.Lens[ConwayLedger, Proposals] =
    Counted.state[LedgerState[Conway]].andThen(ConwayLedgerState.proposals)

  given certsEmbed: Embed[ConwayCertState, ConwayLedger, LedgerFailure, LedgerFailure] =
    Embed(cert, identity)

  given govEmbed: Embed[Proposals, ConwayLedger, LedgerFailure, LedgerFailure] =
    Embed(proposals, identity)

  given utxowEmbed: Embed[Counted[UTxOState[Conway]], ConwayLedger, LedgerFailure, LedgerFailure] =
    Embed(Counted.utxoOf, identity)

  def transition[F[_]: Monad](env: LedgerEnv, tx: Tx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayLedger],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val ctx = env.ctx
    for
      before <- st.get
      gov = before.state.utxo.gov
      pp = gov.curPParams
      certBefore = before.state.cert
      utxo = before.state.utxo.utxo.utxo
      _ <-
        if !tx.isValid then Monad[F].unit
        else valid[F](env, tx, pp, gov, certBefore, utxo)
      _ <- JudgedSts.trans[F, UtxoEnv[ConwayCertState], Counted[
        UTxOState[Conway]
      ], ConwayLedger, StAnnTx, PredicateFailure, LedgerFailure, LedgerFailure](
        Utxow,
        UtxoEnv(ctx, pp, certBefore),
        StAnnTx(tx, utxo)
      )
    yield ()

  /** `validateTreasuryValue` (`Ledger.hs:440-453`). */
  private def treasuryValue(tx: Tx, treasury: BigInt): Test[PredicateFailure] =
    tx.body.currentTreasury.fold(Test.pass[PredicateFailure])(submitted =>
      Test.failureUnless(
        submitted == treasury,
        PredicateFailure.TreasuryValueMismatch(Mismatch(submitted, treasury))
      )
    )

  /** `validateRefScriptSize` (`Ledger.hs:455-472`). */
  private def refScriptSize(refSize: Int): Test[PredicateFailure] =
    Test.failureUnless(
      refSize <= RefScripts.MaxRefScriptSizePerTx,
      PredicateFailure.TxRefScriptsSizeTooBig(Mismatch(refSize, RefScripts.MaxRefScriptSizePerTx))
    )

  /** `validateWithdrawalsDelegated` (`Ledger.hs:474-488`): the key-hash reward accounts withdrawn
    * from that do not delegate to a DRep, read from the state before the certificates.
    */
  private def withdrawalsDelegated(
      accounts: scala.collection.immutable.SortedMap[Credential[Staking], ConwayAccountState],
      tx: Tx
  ): Test[PredicateFailure] =
    val notDelegated = tx.body.withdrawals
      .collect {
        case (account, _)
            if account.credential.isInstanceOf[xyz.kd5ujc.scarab.ledger.Credential.Key] =>
          account.credential.hash
      }
      .filterNot(h =>
        Convert
          .credential[Staking](xyz.kd5ujc.scarab.ledger.Credential.Key(h))
          .toOption
          .flatMap(accounts.get)
          .exists(_.drep.isDefined)
      )
    Test.failureOnNonEmpty(notDelegated)(_.isEmpty)(PredicateFailure.WdrlNotDelegatedToDRep(_))

  /** The part of LEDGER for a transaction that is phase-2 valid (`Ledger.hs:363-425`). */
  private def valid[F[_]: Monad](
      env: LedgerEnv,
      tx: Tx,
      pp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      gov: ConwayGovState,
      certBefore: ConwayCertState,
      utxo: xyz.kd5ujc.scarab.ledger.Utxo
  )(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayLedger],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val body = tx.body
    val id = body.id
    val ctx = env.ctx
    val epoch = ctx.epoch
    val certEnv = CertEnv(
      id,
      pp,
      epoch,
      gov.committee,
      gov.proposals.actions.map(g => g.id -> g).toMap
    )
    val accounts = CertLens.accounts.get(certBefore)
    for
      globals <- Sts.globals[F]
      network = globals.networkId
      _ <- Sts.runTest[F, PredicateFailure](treasuryValue(tx, env.treasury.value))
      _ <- Sts.runTest[F, PredicateFailure](refScriptSize(RefScripts.refScriptsSize(utxo, body)))
      // `hardforkConwayBootstrapPhase` (`Conway/Era.hs:257`) is protocol version 9
      _ <-
        if pp.protocolMajor < 10 then Monad[F].unit
        else Sts.runTest[F, PredicateFailure](withdrawalsDelegated(accounts, tx))
      // `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule` (`Conway/Era.hs:283`): from
      // protocol version 11 the withdrawals are checked and drained here, and the DReps' expiries
      // updated (`testIncompleteAndMissingWithdrawals`, `Shelley/Rules/Ledger.hs:343-361`); before,
      // CERTS does so
      _ <-
        if pp.protocolMajor < 11 then Monad[F].unit
        else
          for
            view <- Refuse.vocab[F, Withdrawals.View](
              Withdrawals.view(accounts, body.withdrawals, network)
            )
            _ <- Withdrawals.record[F](view)
            expired <- Refuse.vocab[F, ConwayCertState](
              Expiry.update(certBefore, tx, epoch, pp).map(Withdrawals.drain(_, tx))
            )
            _ <- st.modify(cert.replace(expired))
          yield ()
      _ <- JudgedSts.trans[F, CertsEnv, ConwayCertState, ConwayLedger, Vector[
        xyz.kd5ujc.scarab.ledger.Certificate
      ], PredicateFailure, LedgerFailure, LedgerFailure](
        Certs,
        CertsEnv(tx, certEnv),
        body.certificates
      )
      certAfter <- st.inspect(cert.get)
      _ <- JudgedSts.trans[
        F,
        GovEnv,
        Proposals,
        ConwayLedger,
        GovSignal,
        PredicateFailure,
        LedgerFailure,
        LedgerFailure
      ](
        Gov,
        GovEnv(id, epoch, pp, gov.constitution.script, certAfter, gov.committee),
        GovSignal(
          body.votes.map { case (voter, procedures) =>
            voter -> procedures.map { case (id, procedure) => id -> procedure.vote }
          },
          body.proposals,
          body.certificates
        )
      )
    yield ()

/** `LedgersEnv` (`Shelley/Rules/Ledgers.hs`): the block-level context and the treasury. */
final case class LedgersEnv(ctx: xyz.kd5ujc.scarab.ledger.TxEnv, treasury: Coin)

/** LEDGERS (`Conway/Rules/Ledgers.hs`, the Shelley rule): the transactions of a block, in order,
  * each by LEDGER with its position in the block. The block's verdict is its first failure, so a
  * transaction's rules run to their first failure ([[Judge.raising]]), raised as the transaction's.
  */
object Ledgers extends Sts[LedgersEnv, ConwayLedger, Vector[Tx], LedgerFailure]:
  def transition[F[_]: Monad](env: LedgersEnv, txs: Vector[Tx])(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayLedger],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    txs.zipWithIndex.traverse_ { (tx, i) =>
      Ledger.transition[F](LedgerEnv(env.ctx.copy(txIndex = i), env.treasury), tx)(using
        summon[Monad[F]],
        ask,
        st,
        raise,
        Judge.raising[F, PredicateFailure, LedgerFailure](LedgerFailure.Rejected(tx.body.id, _))
      )
    }

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

/** LEDGER (`Conway/Rules/Ledger.hs:350`, `conwayLedgerTransitionTRC`): a transaction applied to the
  * ledger state. For a transaction whose scripts pass (phase-2 valid): the treasury value and the
  * reference scripts it declares and uses, the withdrawals (from protocol version 10 only into
  * accounts that delegate to a DRep; from 11 checked and drained here, before that by CERTS), its
  * certificates (CERTS), its proposals and votes (GOV); then, for every transaction, UTXOW. A
  * transaction that failed phase 2 only collects its collateral (UTXOW).
  *
  * The environment of UTXOW holds the certificate state *before* the transaction, which is what its
  * refunds are computed from; GOV sees the one after the certificates.
  */
object Ledger extends Sts[LedgerEnv, ConwayLedger, Tx, LedgerFailure]:
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
      raise: Raise[F, LedgerFailure]
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
      _ <- Sts.trans[F, UtxoEnv[ConwayCertState], Counted[
        UTxOState[Conway]
      ], ConwayLedger, StAnnTx, LedgerFailure, LedgerFailure](
        Utxow,
        UtxoEnv(ctx, pp, certBefore),
        StAnnTx(tx, utxo)
      )
    yield ()

  /** The part of LEDGER for a transaction that is phase-2 valid. */
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
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val body = tx.body
    val id = body.id
    val ctx = env.ctx
    val epoch = ctx.epoch
    val treasury = env.treasury.value
    val refSize = RefScripts.refScriptsSize(utxo, body)
    val certEnv = CertEnv(
      id,
      pp,
      epoch,
      gov.committee,
      gov.proposals.actions.map(g => g.id -> g).toMap
    )
    for
      globals <- Sts.globals[F]
      network = globals.networkId
      _ <- Refuse.unless[F](
        body.currentTreasury.forall(_ == treasury),
        id,
        PredicateFailure.TreasuryValueMismatch(
          Mismatch(body.currentTreasury.getOrElse(treasury), treasury)
        )
      )
      _ <- Refuse.unless[F](
        refSize <= RefScripts.MaxRefScriptSizePerTx,
        id,
        PredicateFailure.TxRefScriptsSizeTooBig(
          Mismatch(refSize, RefScripts.MaxRefScriptSizePerTx)
        )
      )
      accounts = CertLens.accounts.get(certBefore)
      // key-hash reward accounts must be delegated to a DRep after the bootstrap phase; the check
      // looks at the state before the certificates (`validateWithdrawalsDelegated`)
      notDelegated = body.withdrawals
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
      _ <- Refuse.unless[F](
        pp.protocolMajor < 10 || notDelegated.isEmpty,
        id,
        PredicateFailure.WdrlNotDelegatedToDRep(notDelegated)
      )
      // from protocol version 11 the withdrawals are checked and drained here, and the DReps'
      // expiries updated; before, CERTS does so
      _ <-
        if pp.protocolMajor < 11 then Monad[F].unit
        else
          for
            view <- Refuse.vocab[F, Withdrawals.View](
              Withdrawals.view(accounts, body.withdrawals, network)
            )
            _ <- Refuse.unless[F](
              view.missing.isEmpty,
              id,
              PredicateFailure.WithdrawalsMissingAccounts(view.missing.toMap)
            )
            _ <- Refuse.unless[F](
              view.incomplete.isEmpty,
              id,
              PredicateFailure.IncompleteWithdrawals(view.incomplete.toMap)
            )
            expired <- Refuse.vocab[F, ConwayCertState](
              Expiry.update(certBefore, tx, epoch, pp).map(Withdrawals.drain(_, tx))
            )
            _ <- st.modify(cert.replace(expired))
          yield ()
      _ <- Sts.trans[F, CertsEnv, ConwayCertState, ConwayLedger, Vector[
        xyz.kd5ujc.scarab.ledger.Certificate
      ], LedgerFailure, LedgerFailure](
        Certs,
        CertsEnv(tx, certEnv),
        body.certificates
      )
      certAfter <- st.inspect(cert.get)
      _ <- Sts.trans[F, GovEnv, Proposals, ConwayLedger, GovSignal, LedgerFailure, LedgerFailure](
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
  * each by LEDGER with its position in the block.
  */
object Ledgers extends Sts[LedgersEnv, ConwayLedger, Vector[Tx], LedgerFailure]:
  def transition[F[_]: Monad](env: LedgersEnv, txs: Vector[Tx])(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayLedger],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    txs.zipWithIndex.traverse_ { (tx, i) =>
      Ledger.transition[F](LedgerEnv(env.ctx.copy(txIndex = i), env.treasury), tx)
    }

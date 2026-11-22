// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad, Semigroup}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, MempoolProblem, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts}
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

// MEMPOOL of Conway as the reference writes it (cardano-ledger f649f975,
// `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Mempool.hs`, the same rule at 29a7d656 and
// 9df9f98c but for the classes it asks of the failures): the rule a transaction submitted to the
// mempool is applied by, whose sub-rule is Conway's LEDGER, and whose failure and event are
// LEDGER's. Conway's `ApplyTxError` (`eras/conway/impl/src/Cardano/Ledger/Conway.hs:50-59`) holds
// its failures.

/** `newtype ApplyTxError ConwayEra = ConwayApplyTxError (NonEmpty (ConwayLedgerPredFailure
  * ConwayEra))` (`Conway.hs:51-53`, cardano-ledger f649f975): MEMPOOL's failures in the reference's
  * order, the refusal of a transaction the mempool applies. Its `Semigroup` is the list's.
  */
final case class ConwayApplyTxError(failures: NonEmptyChain[ConwayLedgerPredFailure[Conway]])

object ConwayApplyTxError:
  given CanEqual[ConwayApplyTxError, ConwayApplyTxError] = CanEqual.derived

  /** `deriving newtype Semigroup` (`Conway.hs:53`, cardano-ledger f649f975): the failures of the
    * first, then those of the second.
    */
  given Semigroup[ConwayApplyTxError] =
    (x, y) => ConwayApplyTxError(x.failures ++ y.failures)

/** MEMPOOL of Conway (`Conway/Rules/Mempool.hs`, cardano-ledger f649f975). */
object ConwayMempool:
  private val t = EraTx.conway
  private val b = EraTxBody.conway

  /** `mempoolTransition` (`Conway/Rules/Mempool.hs:91-138`, cardano-ledger f649f975), in the
    * reference's order: a transaction none of whose inputs the UTxO set holds is probably a
    * duplicate (`ConwayMempoolFailure` with the text `MempoolProblem.AllInputsSpent` renders,
    * `:112-119`), and then nothing else is checked (`whenFailureFreeDefault`, `:121`); otherwise,
    * below version 11 of the environment's parameters
    * (`hardforkConwayDisallowUnelectedCommitteeFromVoting`, `:122-123`), the votes of hot
    * credentials that no member of the elected committee has authorised are refused here
    * (`unelectedCommitteeVoters` of the governance state's committee and the certificate state's
    * committee state, `:124-135`), as GOV refuses them from 11; then Conway's LEDGER runs as a
    * sub-rule on the same environment, state and signal (`trans`, `:138`), its failures and events
    * MEMPOOL's own ([[EmbedLedgerInMempool.conway]]).
    *
    * The mempool's failure holds the node's typed cause ([[MempoolProblem]]) where the reference
    * holds its `Text`: the unelected voters as the reference's set lists them, which the cause
    * renders with the reference's prefix and `show` of the list. The proposals GOV judges are the
    * node's record in the governance state ([[ProposalsRecord]]), as LEDGER reads them.
    */
  def mempoolTransition[F[_]: Monad](
      env: LedgerEnv[Conway],
      stAnnTx: StAnnTx[TxLevel.Top, Conway],
      context: UtxoContext[Conway],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Conway]],
      judge: Judge[F, MempoolPredFailure[Conway]],
      events: Tell[F, Chain[MempoolEvent[Conway]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      govDefect: Raise[F, GovDefect],
      readError: Raise[F, ProposalsReadError],
      writeError: Raise[F, ProposalsWriteError]
  ): F[Unit] =
    mempoolTransitionAnnotating[F](
      env,
      t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top),
      Monad[F].pure(stAnnTx),
      context,
      verdicts,
      decoder
    )

  /** [[mempoolTransition]] on `tx`, whose annotation `annotated` is made only where LEDGER first
    * reads it, as the reference's is: its `AlonzoStAnnTx` is strict in the transaction alone
    * (`Alonzo/Tx.hs:556-565`, cardano-ledger f649f975), so the fields derived from the UTxO set are
    * not made for a transaction MEMPOOL refuses as a duplicate. The node makes them from the stored
    * outputs read into the era, which can fail where the reference's typed UTxO set cannot
    * ([[UtxoStateDefect]]): that defect is raised where LEDGER would read the same outputs (its own
    * reading, `ShelleyUtxo.readStored` of the same inputs, raises the same one), never before
    * MEMPOOL's own check.
    */
  def mempoolTransitionAnnotating[F[_]: Monad](
      env: LedgerEnv[Conway],
      tx: Tx[TxLevel.Top, Conway],
      annotated: F[StAnnTx[TxLevel.Top, Conway]],
      context: UtxoContext[Conway],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Conway]],
      judge: Judge[F, MempoolPredFailure[Conway]],
      events: Tell[F, Chain[MempoolEvent[Conway]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      govDefect: Raise[F, GovDefect],
      readError: Raise[F, ProposalsReadError],
      writeError: Raise[F, ProposalsWriteError]
  ): F[Unit] =
    val embed = EmbedLedgerInMempool.conway
    val top = t.bodyOf.top
    val txBody = t.body[TxLevel.Top](tx)(using top)
    val inputs = b.inputs[TxLevel.Top](txBody)(using b.top)
    val protVer = EraPParams.conway.ppProtocolVersionL.get(env.ledgerPp)
    for
      ledgerState <- state.get
      utxo = ledgerState.utxo.utxo.utxo
      _ <- Sts.predicate[F, MempoolPredFailure[Conway]](
        inputs.exists(utxo.contains),
        ConwayLedgerPredFailure.ConwayMempoolFailure[Conway](MempoolProblem.AllInputsSpent)
      )
      _ <- Sts.whenFailureFree[F, MempoolPredFailure[Conway]](
        for
          _ <-
            if ConwayEra.hardforkConwayDisallowUnelectedCommitteeFromVoting(protVer) then
              Monad[F].unit
            else
              Sts.failOnNonEmpty[F, Vector[xyz.kd5ujc.scarab.ledger.Credential], MempoolPredFailure[
                Conway
              ]](
                ConwayGov
                  .unelectedCommitteeVoters[Conway](
                    ledgerState.utxo.gov.committee,
                    EraCertState.conway.certVStateL.get(ledgerState.cert).committeeState,
                    b.votingProcedures[TxLevel.Top](txBody)(using b.top)
                  )
                  .toVector
              )(_.isEmpty)(voters =>
                ConwayLedgerPredFailure
                  .ConwayMempoolFailure[Conway](MempoolProblem.UnelectedCommittee(voters))
              )
          _ <- judge.nest(
            annotated.flatMap(stAnnTx =>
              ledger[F](env, stAnnTx, context, verdicts, decoder)(using
                judge.contramap(embed.wrapFailed),
                told(events)(embed.wrapEvent)
              )
            )
          )
        yield ()
      )
    yield ()

  /** Conway's LEDGER (`ConwayLedger.conwayLedgerTransition`) as MEMPOOL's sub-rule, with the
    * failures and events of `judge` and `events`, the embedding's.
    */
  private def ledger[F[_]: Monad](
      env: LedgerEnv[Conway],
      stAnnTx: StAnnTx[TxLevel.Top, Conway],
      context: UtxoContext[Conway],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      judge: Judge[F, LedgerPredFailure[Conway]],
      events: Tell[F, Chain[LedgerEvent[Conway]]]
  )(using
      Ask[F, Globals],
      Stateful[F, LedgerState[Conway]],
      ScriptEvaluator[F],
      Raise[F, ScriptVerdictUnavailable],
      Raise[F, UtxoStateDefect],
      Raise[F, LedgerDefect],
      Raise[F, GovDefect],
      Raise[F, ProposalsReadError],
      Raise[F, ProposalsWriteError]
  ): F[Unit] =
    given Stateful[F, Proposals[Conway]] = ProposalsRecord.stateful[F, Conway]
    ConwayLedger.conwayLedgerTransition[F, Conway](env, stAnnTx, context, verdicts, decoder)

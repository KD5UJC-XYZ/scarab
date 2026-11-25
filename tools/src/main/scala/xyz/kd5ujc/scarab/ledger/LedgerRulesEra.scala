// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import xyz.kd5ujc.scarab.ledger.era.{
  Phase2Deferral,
  Proposals,
  ProposalsReadError,
  ScriptVerdictUnavailable,
  StAnnTx,
  TxCert,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  CERTS,
  CertsEnv,
  CertsEvent,
  CertsPredFailure,
  ConwayLedger,
  ConwayRuleDefect,
  GovDefect,
  LedgerEnv,
  LedgerEvent,
  LedgerPredFailure,
  ProposalsRecord,
  UtxoContext,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Judgment, RuleT, contramap}
import xyz.kd5ujc.scarab.ledger.state.{CertState, Conway, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** The judgments of Conway's era rules the ledger-rules report runs besides MEMPOOL
  * ([[xyz.kd5ujc.scarab.ledger.era.rules.ConwayMempoolRun]]), each in one judgment with every
  * failure accumulated (`RuleT.Policy.Accumulate`) and its events discarded, as the reference's
  * `applySTSOptsEither` with `EPDiscard` runs a rule: LEDGER alone, as LEDGERS runs it in a block
  * ([[ledger]]), and CERTS on a transaction's certificates ([[certs]]).
  */
object LedgerRulesEra:
  /** A judgment's outcome: a defect, the failures in the reference's order, or the state after. */
  type Judged[D, Rec, S] = Either[D, Either[NonEmptyChain[Rec], S]]

  private object Ledgers:
    type F[A] = RuleT[LedgerPredFailure[Conway], ConwayRuleDefect, LedgerState[Conway], A]
    import RuleT.given

    given Tell[F, Chain[LedgerEvent[Conway]]] = new Tell[F, Chain[LedgerEvent[Conway]]]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Chain[LedgerEvent[Conway]]): F[Unit] = Monad[F].unit

    given Raise[F, ScriptVerdictUnavailable] =
      summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Unavailable(_))
    given Raise[F, UtxoStateDefect] =
      summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.UtxoState(_))
    given Raise[F, LedgerDefect] =
      summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Ledger(_))
    given Raise[F, GovDefect] =
      summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Gov(_))
    given Raise[F, ProposalsReadError] =
      summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.ProposalsRead(_))

    // one transaction's LEDGER: the proposals read from the node's record on every read and
    // written back on every write
    given Stateful[F, Proposals[Conway]] = ProposalsRecord.stateful[F, Conway]

    def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
      request => Monad[F].map(Monad[F].unit)(_ => evaluator.evaluate(request))

  private object Certificates:
    type F[A] = RuleT[CertsPredFailure[Conway], LedgerDefect, CertState[Conway], A]
    import RuleT.given

    given Tell[F, Chain[CertsEvent[Conway]]] = new Tell[F, Chain[CertsEvent[Conway]]]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Chain[CertsEvent[Conway]]): F[Unit] = Monad[F].unit

  /** LEDGER (`ConwayLedger.conwayLedgerTransition`, `Conway/Rules/Ledger.hs`, cardano-ledger
    * f649f975) on `stAnnTx` from `state`, one level below the root, as LEDGERS runs each
    * transaction of a block (`Shelley/Rules/Ledgers.hs:201-211`), which fixes the order its
    * failures are reported in (`Judgment.flatten`). Its proposals are the governance state's record
    * ([[ProposalsRecord]]).
    */
  def ledger(
      globals: Globals,
      env: LedgerEnv[Conway],
      stAnnTx: StAnnTx[TxLevel.Top, Conway],
      context: UtxoContext[Conway],
      state: LedgerState[Conway],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  ): Judged[ConwayRuleDefect, LedgerPredFailure[Conway], LedgerState[Conway]] =
    import Ledgers.{*, given}
    import RuleT.given
    given ScriptEvaluator[F] = lifted(evaluator)
    given Phase2Deferral[F, Conway] = Phase2Deferral.now
    Judgment
      .judge(
        summon[Judge[F, LedgerPredFailure[Conway]]].nest(
          ConwayLedger.conwayLedgerTransition[F, Conway](env, stAnnTx, context, verdicts, decoder)
        ),
        RuleT.Policy.Accumulate
      )(globals, state)
      .map(_.map(_._1))

  /** CERTS (`CERTS.conwayCertsTransition`, `Conway/Rules/Certs.hs:203-246`, cardano-ledger
    * f649f975) on `certificates` from `state`, at the root.
    */
  def certs(
      globals: Globals,
      env: CertsEnv[Conway],
      state: CertState[Conway],
      certificates: Vector[TxCert[Conway]]
  ): Judged[LedgerDefect, CertsPredFailure[Conway], CertState[Conway]] =
    import Certificates.{*, given}
    import RuleT.given
    Judgment
      .judge(
        CERTS.conwayCertsTransition[Conway, Nothing, F](env, certificates),
        RuleT.Policy.Accumulate
      )(globals, state)
      .map(_.map(_._1))

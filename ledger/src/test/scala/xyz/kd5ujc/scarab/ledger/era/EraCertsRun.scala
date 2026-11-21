// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Stateful, Tell}
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, PredicateFailure}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  CERT,
  CERTS,
  CertEnv,
  CertEvent,
  CertPredFailure,
  CertsEnv,
  CertsEvent,
  CertsPredFailure,
  ConwayCertsPredFailure,
  ConwayDelegEnv,
  ConwayGovCertEnv,
  DELEG,
  DelegPredFailure,
  GOVCERT,
  GovCertPredFailure,
  POOL,
  PoolEnv,
  PoolPredFailure,
  PoolRuleEvent
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Judgment, RuleT, focus}
import xyz.kd5ujc.scarab.ledger.state.{CertState, Conway, PState}

/** Conway's certificate rules on the era types (`CERTS.conwayCertsTransition`, and CERT, DELEG,
  * POOL and GOVCERT alone) run in one judgment, and the flat form their failures are compared in
  * with the flat rules', for the suites that compare the two (`ConwayCertsChecksSuite` in the node,
  * `CertsEpochWrapSuite` and `PoolParamsLayoutSuite`).
  */
object EraCertsRun:
  /** A judgment of the flat rules' kind: a defect, the failures in the flat form, or the state. */
  type Flat[S] = Either[LedgerDefect, Either[NonEmptyChain[PredicateFailure], S]]

  private final class Run[Rec, Ev, S]:
    type Events = Chain[Ev]
    type F[A] = RuleT[Rec, LedgerDefect, (S, Events), A]
    import RuleT.given

    given Stateful[F, S] =
      summon[Stateful[F, (S, Events)]].focus(Lens[(S, Events), S](_._1)(s => all => (s, all._2)))

    given Tell[F, Events] = new Tell[F, Events]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Events): F[Unit] =
        summon[Stateful[F, (S, Events)]].modify((s, e) => (s, e ++ events))

    /** `program` from `state`, `depth` levels below the root, every failure accumulated. */
    def run(globals: Globals, state: S, depth: Int)(
        program: F[Unit]
    ): Either[LedgerDefect, Either[NonEmptyChain[Rec], (S, Events)]] =
      val nested = (0 until depth).foldLeft(program)((fa, _) => summon[Judge[F, Rec]].nest(fa))
      Judgment
        .judge(nested, RuleT.Policy.Accumulate)(globals, (state, Chain.empty))
        .map(_.map(_._1))

  private val certsRun = Run[CertsPredFailure[Conway], CertsEvent[Conway], CertState[Conway]]
  private val poolRun = Run[PoolPredFailure[Conway], PoolRuleEvent[Conway], PState]
  private val certRun = Run[CertPredFailure[Conway], CertEvent[Conway], CertState[Conway]]
  private val delegRun = Run[DelegPredFailure[Conway], Nothing, CertState[Conway]]
  private val govCertRun = Run[GovCertPredFailure[Conway], Nothing, CertState[Conway]]

  /** A judgment's outcome: a defect, its failures in the reference's order, or the state after with
    * its events.
    */
  type Judged[Rec, S, Ev] = Either[LedgerDefect, Either[NonEmptyChain[Rec], (S, Chain[Ev])]]

  /** CERTS on `certificates` from `state`, under the build of `globals`, `depth` levels below the
    * root: a defect, its failures in the reference's order, or the state after with its events.
    */
  def certs(
      globals: Globals,
      env: CertsEnv[Conway],
      state: CertState[Conway],
      certificates: Vector[TxCert[Conway]],
      depth: Int = 0
  ): Either[
    LedgerDefect,
    Either[NonEmptyChain[CertsPredFailure[Conway]], (CertState[Conway], Chain[CertsEvent[Conway]])]
  ] =
    import certsRun.given
    import RuleT.given
    certsRun.run(globals, state, depth)(
      CERTS.conwayCertsTransition[Conway, certsRun.F](env, certificates)
    )

  /** POOL on `cert` from `state`, at the root. */
  def pool(
      globals: Globals,
      env: PoolEnv[Conway],
      state: PState,
      cert: PoolCert
  ): Either[
    LedgerDefect,
    Either[NonEmptyChain[PoolPredFailure[Conway]], (PState, Chain[PoolRuleEvent[Conway]])]
  ] =
    import poolRun.given
    import RuleT.given
    poolRun.run(globals, state, 0)(POOL.poolTransition[Conway, poolRun.F](env, cert))

  /** CERT on `cert` from `state`, at the root. */
  def cert(
      globals: Globals,
      env: CertEnv[Conway],
      state: CertState[Conway],
      cert: TxCert[Conway]
  ): Judged[CertPredFailure[Conway], CertState[Conway], CertEvent[Conway]] =
    import certRun.given
    import RuleT.given
    certRun.run(globals, state, 0)(CERT.certTransition[Conway, certRun.F](env, cert))

  /** DELEG on `cert` from `state`, at the root. */
  def deleg(
      globals: Globals,
      env: ConwayDelegEnv[Conway],
      state: CertState[Conway],
      cert: ConwayDelegCert
  ): Judged[DelegPredFailure[Conway], CertState[Conway], Nothing] =
    import delegRun.given
    import RuleT.given
    delegRun.run(globals, state, 0)(DELEG.conwayDelegTransition[Conway, delegRun.F](env, cert))

  /** GOVCERT on `cert` from `state`, at the root. */
  def govCert(
      globals: Globals,
      env: ConwayGovCertEnv[Conway],
      state: CertState[Conway],
      cert: ConwayGovCert
  ): Judged[GovCertPredFailure[Conway], CertState[Conway], Nothing] =
    import govCertRun.given
    import RuleT.given
    govCertRun.run(globals, state, 0)(
      GOVCERT.conwayGovCertTransition[Conway, govCertRun.F](env, cert)
    )

  /** A CERTS judgment in the flat rules' form: its failures put in the flat form
    * (`ConwayCertsPredFailure.toStored`), its events dropped.
    */
  def flat(
      judged: Either[
        LedgerDefect,
        Either[NonEmptyChain[
          CertsPredFailure[Conway]
        ], (CertState[Conway], Chain[CertsEvent[Conway]])]
      ]
  ): Flat[CertState[Conway]] =
    judged.map(_.left.map(_.map(ConwayCertsPredFailure.toStored)).map(_._1))

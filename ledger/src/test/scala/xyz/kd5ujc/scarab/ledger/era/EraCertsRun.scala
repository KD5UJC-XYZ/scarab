// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, PredicateFailure, TxEnv}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  CERT,
  CERTS,
  CertEnv,
  CertEvent,
  CertPredFailure,
  CertRule,
  CertsEnv,
  CertsEvent,
  CertsPredFailure,
  ConwayCertsPredFailure,
  ConwayDelegEnv,
  ConwayGovCertEnv,
  DELEG,
  DelegPredFailure,
  DijkstraPOOL,
  ENTITIES,
  EntitiesEnv,
  EntitiesRuleEvent,
  EntitiesRuleFailure,
  GOVCERT,
  GovCertPredFailure,
  POOL,
  PoolDefect,
  PoolEnv,
  PoolPredFailure,
  PoolRuleEvent
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{CertState, Conway, Dijkstra, PState}

/** Conway's certificate rules on the era types (`CERTS.conwayCertsTransition`, and CERT, DELEG,
  * POOL and GOVCERT alone) run in one judgment, and the flat form their failures are compared in
  * with the flat rules', for the suites that compare the two (`ConwayCertsChecksSuite` in the node,
  * `CertsEpochWrapSuite` and `PoolParamsLayoutSuite`); and Dijkstra's CERTS, POOL and ENTITIES
  * (`DijkstraCertsSuite`, and `DijkstraRulesOracleSuite` in the node).
  */
object EraCertsRun:
  /** A judgment of the flat rules' kind: a defect, the failures in the flat form, or the state. */
  type Flat[S] = Either[LedgerDefect, Either[NonEmptyChain[PredicateFailure], S]]

  /** What a Dijkstra judgment of the certificate rules ends with, short of a verdict: POOL's
    * defect, or one of every rule.
    */
  type DijkstraDefect = Either[PoolDefect, LedgerDefect]

  private final class Run[Rec, Ev, S, D]:
    type Events = Chain[Ev]
    type F[A] = RuleT[Rec, D, (S, Events), A]
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
    ): Either[D, Either[NonEmptyChain[Rec], (S, Events)]] =
      val nested = (0 until depth).foldLeft(program)((fa, _) => summon[Judge[F, Rec]].nest(fa))
      Judgment
        .judge(nested, RuleT.Policy.Accumulate)(globals, (state, Chain.empty))
        .map(_.map(_._1))

  private val certsRun =
    Run[CertsPredFailure[Conway], CertsEvent[Conway], CertState[Conway], LedgerDefect]
  private val poolRun = Run[PoolPredFailure[Conway], PoolRuleEvent[Conway], PState, LedgerDefect]
  private val certRun =
    Run[CertPredFailure[Conway], CertEvent[Conway], CertState[Conway], LedgerDefect]
  private val delegRun = Run[DelegPredFailure[Conway], Nothing, CertState[Conway], LedgerDefect]
  private val govCertRun =
    Run[GovCertPredFailure[Conway], Nothing, CertState[Conway], LedgerDefect]
  private val dijkstraCertsRun =
    Run[CertsPredFailure[Dijkstra], CertsEvent[Dijkstra], CertState[Dijkstra], DijkstraDefect]
  private val dijkstraPoolRun =
    Run[PoolPredFailure[Dijkstra], PoolRuleEvent[Dijkstra], PState, DijkstraDefect]
  private val entitiesRun =
    Run[
      EntitiesRuleFailure[Dijkstra],
      EntitiesRuleEvent[Dijkstra],
      CertState[Dijkstra],
      DijkstraDefect
    ]

  /** A judgment's outcome: a defect, its failures in the reference's order, or the state after with
    * its events.
    */
  type Judged[Rec, S, Ev] = Either[LedgerDefect, Either[NonEmptyChain[Rec], (S, Chain[Ev])]]

  /** A Dijkstra judgment's outcome, its defect POOL's or one of every rule ([[DijkstraDefect]]). */
  type DijkstraJudged[Rec, S, Ev] =
    Either[DijkstraDefect, Either[NonEmptyChain[Rec], (S, Chain[Ev])]]

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
      CERTS.conwayCertsTransition[Conway, Nothing, certsRun.F](env, certificates)
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

  /** Dijkstra's CERTS (Conway's transition with Dijkstra's CERT) on `certificates` from `state`,
    * under `validation`, `depth` levels below the root.
    */
  def dijkstraCerts(
      globals: Globals,
      env: CertsEnv[Dijkstra],
      state: CertState[Dijkstra],
      certificates: Vector[TxCert[Dijkstra]],
      validation: TxEnv.Validation = TxEnv.Validation.All,
      depth: Int = 0
  ): DijkstraJudged[CertsPredFailure[Dijkstra], CertState[Dijkstra], CertsEvent[Dijkstra]] =
    import dijkstraCertsRun.given
    import RuleT.given
    given CertRule[Dijkstra, PoolDefect] = CertRule.dijkstra(validation)
    given Raise[dijkstraCertsRun.F, PoolDefect] =
      summon[Raise[dijkstraCertsRun.F, DijkstraDefect]].contramap(Left(_))
    given Raise[dijkstraCertsRun.F, LedgerDefect] =
      summon[Raise[dijkstraCertsRun.F, DijkstraDefect]].contramap(Right(_))
    dijkstraCertsRun.run(globals, state, depth)(
      CERTS.conwayCertsTransition[Dijkstra, PoolDefect, dijkstraCertsRun.F](env, certificates)
    )

  /** Dijkstra's POOL on `cert` from `state`, under `validation`, at the root. */
  def dijkstraPool(
      globals: Globals,
      env: PoolEnv[Dijkstra],
      state: PState,
      cert: PoolCert,
      validation: TxEnv.Validation = TxEnv.Validation.All
  ): DijkstraJudged[PoolPredFailure[Dijkstra], PState, PoolRuleEvent[Dijkstra]] =
    import dijkstraPoolRun.given
    import RuleT.given
    given Raise[dijkstraPoolRun.F, PoolDefect] =
      summon[Raise[dijkstraPoolRun.F, DijkstraDefect]].contramap(Left(_))
    given Raise[dijkstraPoolRun.F, LedgerDefect] =
      summon[Raise[dijkstraPoolRun.F, DijkstraDefect]].contramap(Right(_))
    dijkstraPoolRun.run(globals, state, 0)(
      DijkstraPOOL.poolTransition[Dijkstra, dijkstraPoolRun.F](env, cert, validation)
    )

  /** ENTITIES on `stAnnTx` from `state`, under `validation`, `depth` levels below the root. */
  def entities(
      globals: Globals,
      env: EntitiesEnv[Dijkstra],
      state: CertState[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      validation: TxEnv.Validation = TxEnv.Validation.All,
      depth: Int = 0
  ): DijkstraJudged[
    EntitiesRuleFailure[Dijkstra],
    CertState[Dijkstra],
    EntitiesRuleEvent[Dijkstra]
  ] =
    import entitiesRun.given
    import RuleT.given
    given Raise[entitiesRun.F, PoolDefect] =
      summon[Raise[entitiesRun.F, DijkstraDefect]].contramap(Left(_))
    given Raise[entitiesRun.F, LedgerDefect] =
      summon[Raise[entitiesRun.F, DijkstraDefect]].contramap(Right(_))
    entitiesRun.run(globals, state, depth)(
      ENTITIES.dijkstraEntitiesTransition[entitiesRun.F](env, stAnnTx, validation)
    )

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Certificate,
  CertFailure,
  CertsFailure,
  GovAction,
  GovActionId,
  GovFailure,
  LedgerFailure,
  PoolFailure,
  PredicateFailure,
  Proposal,
  Proposals,
  Vote,
  Voter
}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.state.*

/** The combinators of the Dijkstra rules: a Dijkstra sub-rule, its failures wrapped, and a Conway
  * rule that Dijkstra reuses, its failures taken into Dijkstra's types.
  */
object DijkstraSts:
  /** `applySTS` of a Dijkstra rule under `policy`: a halt, or the predicate failures of the refusal
    * in the reference's order ([[Judgment.flatten]]), or the state after the transition.
    */
  def judge[Env, S, Sig, Rec](
      rule: JudgedSts[Env, S, Sig, Rec, DijkstraHalt],
      policy: Judgment.Policy
  )(
      globals: Globals,
      env: Env,
      state: S,
      signal: Sig
  ): Either[DijkstraHalt, Either[NonEmptyChain[Rec], S]] =
    Judgment
      .judge(rule.transition[RuleT.Judged[Rec, DijkstraHalt, S]](env, signal), policy)(
        globals,
        state
      )
      .map(_.map(_._1))

  /** `trans @sub` (`Extended.hs:475`) of a Dijkstra rule: `sub` on the part of the state `lens`
    * names, what it records wrapped (`wrapFailed`) as one block of the enclosing rule's failures.
    */
  private[rules] def trans[F[_]: Monad, Env, S, S0, Sig, Rec, Rec0](
      sub: JudgedSts[Env, S, Sig, Rec, DijkstraHalt],
      env: Env,
      signal: Sig,
      lens: Lens[S0, S],
      wrap: Rec => Rec0
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, S0],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, Rec0]
  ): F[Unit] =
    judge.nest(
      sub.transition[F](env, signal)(using
        summon[Monad[F]],
        ask,
        state.focus(lens),
        raise,
        judge.contramap(wrap)
      )
    )

  /** `trans @sub` of a Conway rule that Dijkstra reuses as its sub-rule: [[asConway]] as one block
    * of the enclosing rule's failures.
    */
  private[rules] def conway[F[_]: Monad, Env, S, S0, Sig, Rec](
      sub: JudgedSts[Env, S, Sig, PredicateFailure, LedgerFailure],
      env: Env,
      signal: Sig,
      lens: Lens[S0, S],
      select: PartialFunction[PredicateFailure, Rec]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, S0],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, Rec]
  ): F[Unit] =
    judge.nest(asConway[F, Env, S, S0, Sig, Rec](sub, env, signal, lens, select))

  /** A Dijkstra rule whose transition is Conway's (`transitionRules =
    * [Conway.conwayGovTransition]`, `Dijkstra/Rules/Gov.hs:220`): the Conway rule's judgment as
    * this rule's, its predicate failures taken into the Dijkstra failure by `select` (the
    * reference's `conwayToDijkstra*PredFailure`, one to one). A failure `select` has no case for
    * halts the judgment ([[DijkstraHalt.NoDijkstraCase]]), as does a defect of the Conway rule
    * ([[DijkstraHalt.Conway]]).
    */
  private[rules] def asConway[F[_]: Monad, Env, S, S0, Sig, Rec](
      sub: JudgedSts[Env, S, Sig, PredicateFailure, LedgerFailure],
      env: Env,
      signal: Sig,
      lens: Lens[S0, S],
      select: PartialFunction[PredicateFailure, Rec]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, S0],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, Rec]
  ): F[Unit] =
    val taken: Judge[F, PredicateFailure] = new Judge[F, PredicateFailure]:
      val functor: cats.Functor[F] = judge.functor
      def tell(failures: Chain[PredicateFailure]): F[Unit] =
        failures
          .traverse(f => select.lift(f).toRight(f))
          .fold(f => raise.raise[DijkstraHalt, Unit](DijkstraHalt.NoDijkstraCase(f)), judge.tell)
      def failing: F[Boolean] = judge.failing
      def nest[A](fa: F[A]): F[A] = judge.nest(fa)
    sub.transition[F](env, signal)(using
      summon[Monad[F]],
      ask,
      state.focus(lens),
      raise.contramap(DijkstraHalt.Conway(_)),
      taken
    )

  /** A Conway predicate's failures taken into a Dijkstra failure by `select`, or the halt of the
    * first it has no case for.
    */
  private[rules] def taken[Rec](select: PartialFunction[PredicateFailure, Rec])(
      test: Test[PredicateFailure]
  ): Either[DijkstraHalt, Test[Rec]] =
    test.fold(
      _.traverse(f => select.lift(f).toRight(DijkstraHalt.NoDijkstraCase(f)))
        .map(cats.data.Validated.invalid[NonEmptyChain[Rec], Unit](_)),
      _ => Right(Test.pass[Rec])
    )

  /** Records the checks in order: every failure of each, or the first halt. */
  private[rules] def record[F[_]: Monad, Rec](checks: Vector[Either[DijkstraHalt, Test[Rec]]])(using
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, Rec]
  ): F[Unit] =
    checks.traverse_(c => Sts.lift[F, DijkstraHalt, Test[Rec]](c).flatMap(Sts.runTest[F, Rec](_)))

  private[rules] def same[S]: Lens[S, S] = Lens[S, S](identity)(s => _ => s)

  private[rules] def halt[F[_], A](what: DijkstraPending)(using
      raise: Raise[F, DijkstraHalt]
  ): F[A] =
    raise.raise(DijkstraHalt.pending(what))

/** The Dijkstra certificates, votes and proposals as the Conway rules that Dijkstra reuses read
  * them. A Dijkstra certificate of tags 2 to 18 is a Conway one (`DijkstraTxCert`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxCert.hs`, cardano-ledger 9df9f98c), except a
  * pool registration, whose parameters may carry a BLS key.
  */
object DijkstraConway:
  def anchor(a: DijkstraAnchor): Anchor = Anchor(a.url, a.hash)

  def actionId(id: DijkstraGovActionId): GovActionId = GovActionId(id.txId, BigInt(id.index))

  def voter(v: DijkstraVoter): Voter = v match
    case DijkstraVoter.Committee(c) => Voter.CommitteeHot(c)
    case DijkstraVoter.DRep(c)      => Voter.DRepVoter(c)
    case DijkstraVoter.StakePool(p) => Voter.Pool(p)

  /** `Vote` (`decodeEnumBounded`): `VoteNo`, `VoteYes`, `Abstain`, as the decoder admits them. */
  def vote(v: Int): Vote = v match
    case 0 => Vote.No
    case 1 => Vote.Yes
    case _ => Vote.Abstain

  private def delegatee(d: DijkstraDelegatee) = d match
    case DijkstraDelegatee.Stake(p)        => (Some(p), None)
    case DijkstraDelegatee.Vote(r)         => (None, Some(r))
    case DijkstraDelegatee.StakeVote(p, r) => (Some(p), Some(r))

  /** The Conway certificate a Dijkstra one is, or the part of POOL this build does not run. */
  def certificate(cert: DijkstraCert): Either[DijkstraPending, Certificate] = cert match
    case DijkstraCert.Register(c, d)   => Right(Certificate.Register(c, Some(d)))
    case DijkstraCert.Unregister(c, r) => Right(Certificate.Unregister(c, Some(r)))
    case DijkstraCert.Delegate(c, d) =>
      val (pool, drep) = delegatee(d)
      Right(Certificate.Delegate(c, pool, drep, None))
    case DijkstraCert.RegisterDelegate(c, d, deposit) =>
      val (pool, drep) = delegatee(d)
      Right(Certificate.Delegate(c, pool, drep, Some(deposit)))
    case DijkstraCert.RegisterPool(_)  => Left(DijkstraPending.PoolRegistration)
    case DijkstraCert.RetirePool(p, e) => Right(Certificate.RetirePool(p, e))
    case DijkstraCert.AuthorizeCommitteeHotKey(cold, hot) =>
      Right(Certificate.CommitteeAuthorize(cold, hot))
    case DijkstraCert.ResignCommitteeColdKey(cold, a) =>
      Right(Certificate.CommitteeResign(cold, a.map(anchor)))
    case DijkstraCert.RegisterDRep(c, deposit, a) =>
      Right(Certificate.RegisterDRep(c, deposit, a.map(anchor)))
    case DijkstraCert.UnregisterDRep(c, refund) => Right(Certificate.UnregisterDRep(c, refund))
    case DijkstraCert.UpdateDRep(c, a)          => Right(Certificate.UpdateDRep(c, a.map(anchor)))

  /** The Conway governance action a Dijkstra one is, or a parameter change, which the store of
    * proposals cannot hold yet. The withdrawals of a treasury withdrawal are the reference's `Map`,
    * in its order.
    */
  def action(a: DijkstraGovAction): Either[DijkstraPending, GovAction] = a match
    case DijkstraGovAction.ParameterChange(_, _, _) =>
      Left(DijkstraPending.ParameterChangeProposal)
    case DijkstraGovAction.HardForkInitiation(prev, major, minor) =>
      Right(GovAction.HardFork(prev.map(actionId), BigInt(major), BigInt(minor)))
    case DijkstraGovAction.TreasuryWithdrawals(ws, policy) =>
      Right(GovAction.TreasuryWithdrawals(ws.toVector.sortBy(_._1), policy))
    case DijkstraGovAction.NoConfidence(prev) => Right(GovAction.NoConfidence(prev.map(actionId)))
    case DijkstraGovAction.UpdateCommittee(prev, removed, added, threshold) =>
      Right(GovAction.UpdateCommittee(prev.map(actionId), removed, added, threshold))
    case DijkstraGovAction.NewConstitution(prev, a, policy) =>
      Right(GovAction.NewConstitution(prev.map(actionId), policy, anchor(a)))
    case DijkstraGovAction.InfoAction => Right(GovAction.Info)

  def proposal(p: DijkstraProposal): Either[DijkstraPending, Proposal] =
    action(p.action).map(Proposal(p.deposit, p.returnAccount, _, anchor(p.anchor)))

/** POOL of Dijkstra (`Dijkstra/Rules/Pool.hs:199-318`, `poolTransition`, cardano-ledger 9df9f98c):
  * a retirement is Shelley's, the same checks in the same order (`Pool.hs:301-318`;
  * `Shelley/Rules/Pool.hs`), run by the Conway rule with its failures taken into
  * [[DijkstraPoolFailure]] (`Pool.hs:146-153`). A registration halts
  * ([[DijkstraPending.PoolRegistration]]): it counts VRF key hashes by occurrence and keeps the BLS
  * key (`Pool.hs:239-300`), which the node's pool state does not hold.
  */
object DijkstraPool
    extends JudgedSts[CertEnv, PState, Certificate, DijkstraPoolFailure, DijkstraHalt]:
  private val taken: PartialFunction[PredicateFailure, DijkstraPoolFailure] = {
    case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Pool(f))) =>
      f match
        case PoolFailure.StakePoolNotRegisteredOnKey(p) =>
          DijkstraPoolFailure.StakePoolNotRegisteredOnKey(p)
        case PoolFailure.StakePoolRetirementWrongEpoch(early, late) =>
          DijkstraPoolFailure.StakePoolRetirementWrongEpoch(early, late)
        case PoolFailure.StakePoolCostTooLow(m) => DijkstraPoolFailure.StakePoolCostTooLow(m)
        case PoolFailure.WrongNetworkPool(m, p) => DijkstraPoolFailure.WrongNetworkPool(m, p)
        case PoolFailure.PoolMedataHashTooBig(p, s) =>
          DijkstraPoolFailure.PoolMedataHashTooBig(p, s)
        case PoolFailure.VRFKeyHashAlreadyRegistered(p, v) =>
          DijkstraPoolFailure.VRFKeyHashAlreadyRegistered(p, v)
  }

  def transition[F[_]: Monad](env: CertEnv, cert: Certificate)(using
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, DijkstraPoolFailure]
  ): F[Unit] = cert match
    case Certificate.RetirePool(pool, epoch) =>
      Refuse
        .vocab[F, KeyHash[StakePool]](Convert.keyHash[StakePool](pool))(using
          summon,
          raise.contramap(DijkstraHalt.Conway(_))
        )
        .flatMap(p =>
          DijkstraSts.asConway[F, CertEnv, PState, PState, PoolCert, DijkstraPoolFailure](
            Pool,
            env,
            PoolCert.RetirePool(p, epoch),
            DijkstraSts.same,
            taken
          )
        )
    case _ => DijkstraSts.halt[F, Unit](DijkstraPending.PoolRegistration)

/** CERT of Dijkstra (`Dijkstra/Rules/Cert.hs:97-112`, `certTransition`, cardano-ledger 9df9f98c): a
  * certificate goes to the rule of its family. DELEG is Conway's (`Rules/Deleg.hs`, the same at
  * 9df9f98c as at f649f975 but for the name of its failure 2); GOVCERT is Conway's transition
  * (`Rules/GovCert.hs:116`) with a copy of its failure type, so [[GovCertFailure]]; POOL is
  * [[DijkstraPool]], on the pool state. The environment is Conway's `CertEnv` (`Cert.hs:98`).
  */
object DijkstraCertRule
    extends JudgedSts[CertEnv, ConwayCertState, DijkstraCert, DijkstraCertFailure, DijkstraHalt]:
  def transition[F[_]: Monad](env: CertEnv, cert: DijkstraCert)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayCertState],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, DijkstraCertFailure]
  ): F[Unit] =
    DijkstraConway
      .certificate(cert)
      .fold(
        DijkstraSts.halt[F, Unit](_),
        c =>
          Sts
            .lift[F, LedgerFailure, ConwayCert](ConwayCert.of(env.id, c))(using
              summon,
              raise.contramap(DijkstraHalt.Conway(_))
            )
            .flatMap {
              case ConwayCert.OfDeleg(d) =>
                DijkstraSts.conway[
                  F,
                  CertEnv,
                  ConwayCertState,
                  ConwayCertState,
                  DelegCert,
                  DijkstraCertFailure
                ](
                  Deleg,
                  env,
                  d,
                  DijkstraSts.same,
                  { case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Deleg(f))) =>
                    DijkstraCertFailure.Deleg(f)
                  }
                )
              case ConwayCert.OfPool(_) =>
                DijkstraSts.trans[
                  F,
                  CertEnv,
                  PState,
                  ConwayCertState,
                  Certificate,
                  DijkstraPoolFailure,
                  DijkstraCertFailure
                ](
                  DijkstraPool,
                  env,
                  c,
                  ConwayCertState.pState,
                  DijkstraCertFailure.Pool(_)
                )
              case ConwayCert.OfGov(g) =>
                DijkstraSts.conway[
                  F,
                  CertEnv,
                  ConwayCertState,
                  ConwayCertState,
                  ConwayGovCert,
                  DijkstraCertFailure
                ](
                  GovCert,
                  env,
                  g,
                  DijkstraSts.same,
                  { case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.GovCert(f))) =>
                    DijkstraCertFailure.GovCert(f)
                  }
                )
            }
      )

/** CERTS of Dijkstra, Conway's at 9df9f98c (`Conway/Rules/Certs.hs:210-224`, with
  * `Dijkstra/Rules/Certs.hs`): CERTS of all but the last certificate, then CERT of the last, each a
  * sub-rule; the empty sequence leaves the state as it is (the withdrawals are ENTITIES',
  * `Dijkstra/Rules/Entities.hs`). The recursion is a fold, each step a program over the previous
  * (ADR 0011), as [[Certs]] builds it.
  */
object DijkstraCertsRule
    extends JudgedSts[CertEnv, ConwayCertState, Vector[
      DijkstraCert
    ], DijkstraCertsFailure, DijkstraHalt]:
  def transition[F[_]: Monad](env: CertEnv, certs: Vector[DijkstraCert])(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayCertState],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, DijkstraCertsFailure]
  ): F[Unit] =
    certs.foldLeft(Monad[F].unit) { (prefix, cert) =>
      judge.nest(prefix) *>
        DijkstraSts.trans[
          F,
          CertEnv,
          ConwayCertState,
          ConwayCertState,
          DijkstraCert,
          DijkstraCertFailure,
          DijkstraCertsFailure
        ](
          DijkstraCertRule,
          env,
          cert,
          DijkstraSts.same,
          DijkstraCertsFailure(_)
        )
    }

/** The votes, proposals and certificates of a Dijkstra transaction (`GovSignal`,
  * `Conway/Rules/Gov.hs:312-316`).
  */
final case class DijkstraGovSignal(
    votes: Vector[(DijkstraVoter, Vector[(DijkstraGovActionId, DijkstraVotingProcedure)])],
    proposals: Vector[DijkstraProposal],
    certificates: Vector[DijkstraCert]
)

object DijkstraGovSignal:
  def of(body: DijkstraTxBody): DijkstraGovSignal =
    DijkstraGovSignal(body.votingProcedures, body.proposals, body.certificates)

/** GOV of Dijkstra (`Dijkstra/Rules/Gov.hs:199-220`): Conway's transition (`conwayGovTransition`,
  * the same at 9df9f98c as at f649f975), with `DijkstraGovPredFailure`, a copy of Conway's
  * ([[GovFailure]], `conwayToDijkstraGovPredFailure`, `Gov.hs:222-243`). The signal is read as
  * Conway's: the certificates only for the DReps they unregister, the one thing the rule reads of
  * them (`collectRemovals`, `Conway/Rules/Gov.hs:612-618`). A parameter-change proposal halts
  * ([[DijkstraPending.ParameterChangeProposal]]).
  */
object DijkstraGov
    extends JudgedSts[GovEnv, Proposals, DijkstraGovSignal, GovFailure, DijkstraHalt]:
  def transition[F[_]: Monad](env: GovEnv, signal: DijkstraGovSignal)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Proposals],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, GovFailure]
  ): F[Unit] =
    val votes = signal.votes.map { (voter, procedures) =>
      DijkstraConway.voter(voter) -> procedures.map((id, p) =>
        DijkstraConway.actionId(id) -> DijkstraConway.vote(p.vote)
      )
    }
    val unregistrations = signal.certificates.collect {
      case DijkstraCert.UnregisterDRep(c, refund) => Certificate.UnregisterDRep(c, refund)
    }
    signal.proposals
      .traverse(DijkstraConway.proposal)
      .fold(
        DijkstraSts.halt[F, Unit](_),
        proposals =>
          DijkstraSts.asConway[F, GovEnv, Proposals, Proposals, GovSignal, GovFailure](
            Gov,
            env,
            GovSignal(votes, proposals, unregistrations),
            DijkstraSts.same,
            { case PredicateFailure.Gov(f) => f }
          )
      )

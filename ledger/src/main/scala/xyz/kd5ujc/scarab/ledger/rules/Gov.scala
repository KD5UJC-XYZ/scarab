// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  Committee,
  GovChecks,
  GovAction,
  GovActionId,
  GovActionState,
  GovFailure,
  LedgerFailure,
  Mismatch,
  PredicateFailure,
  ProtocolParams,
  Proposal,
  Proposals,
  Ratify,
  Vote,
  Voter
}
import xyz.kd5ujc.scarab.ledger.state.*

/** `GovEnv` (`Conway/Rules/Gov.hs:149`): the transaction, the current epoch, the parameters, the
  * script of the constitution's guardrails, the certificate state *after* the transaction's
  * certificates (a proposal's return account may be one it registers), and the committee.
  */
final case class GovEnv(
    id: Bytes,
    epoch: BigInt,
    pp: ProtocolParams,
    guardrails: Option[Bytes],
    cert: ConwayCertState,
    committee: Option[Committee]
)

/** `GovSignal` (`Gov.hs:312`): the votes and the proposals of the transaction, and its certificates
  * (the DReps it unregisters lose their votes).
  */
final case class GovSignal(
    votes: Vector[(Voter, Vector[(GovActionId, Vote)])],
    proposals: Vector[Proposal],
    certificates: Vector[Certificate]
)

/** GOV (`Conway/Rules/Gov.hs:445`, `conwayGovTransition`): the proposals and the votes of a
  * transaction, on the store of proposals. The state is the store of the governance state
  * (`Proposals`), as Haskell's.
  *
  * Votes are recorded once per action, not once per vote ([[Proposals.withVotes]]): the cost of a
  * transaction is the store walked once and each vote a map insert, so epochs that carry hundreds
  * of thousands of committee votes (preprod 187 to 193, issue #94) cost no more per vote than the
  * first.
  *
  * Before protocol version 11 a vote of a hot committee key that its committee has authorised but
  * that is not in the committee that is elected is accepted
  * (`hardforkConwayDisallowUnelectedCommitteeFromVoting`, `Gov.hs:478`): the key must be *known*
  * (authorised), not elected. The authorisations are pruned at the epoch boundary (`Epoch.hs`,
  * `removeCommitteeMembers`) while the votes stay, which is what those epochs of preprod show.
  */
object Gov extends Sts[GovEnv, Proposals, GovSignal, LedgerFailure]:
  /** The committee hot credentials that the committee state has authorised
    * (`knownCommitteeMembers`, `authorizedHotCommitteeCredentials`).
    */
  private def authorised(cert: ConwayCertState): Set[Credential[HotCommittee]] =
    cert.vState.committeeState.creds.valuesIterator.collect {
      case CommitteeAuthorization.CommitteeHotCredential(h) => h
    }.toSet

  /** `unelectedCommitteeVoters` (`Gov.hs:652`): the hot credentials among the voters that no member
    * of the current committee has authorised.
    */
  def unelected(
      committee: Option[Committee],
      cert: ConwayCertState,
      voters: Iterable[Voter]
  ): Vector[node.Credential] =
    val elected: Set[Credential[HotCommittee]] =
      cert.vState.committeeState.creds.iterator.collect {
        case (cold, CommitteeAuthorization.CommitteeHotCredential(h))
            if committee.exists(_.members.contains(Convert.back(cold))) =>
          h
      }.toSet
    voters.iterator.collect {
      case Voter.CommitteeHot(h) if Convert.credential[HotCommittee](h).forall(!elected(_)) => h
    }.toVector

  def transition[F[_]: Monad](env: GovEnv, signal: GovSignal)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Proposals],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val id = env.id
    val pp = env.pp
    val bootstrap = pp.protocolMajor == 9
    def gov(f: GovFailure): PredicateFailure = PredicateFailure.gov(f)
    def accountRegistered(a: node.RewardAccount): F[Boolean] =
      Refuse
        .vocab[F, Credential[Staking]](Convert.credential[Staking](a.credential))
        .map(CertLens.accounts.get(env.cert).contains)
    for
      globals <- Sts.globals[F]
      network = globals.networkId
      // from protocol version 11 the committee voters must be elected members
      _ <-
        if pp.protocolMajor > 10 then
          val bad = unelected(env.committee, env.cert, signal.votes.map(_._1))
          Refuse.unless[F](bad.isEmpty, id, gov(GovFailure.UnelectedCommitteeVoters(bad)))
        else Monad[F].unit
      before <- st.get
      proposals <- signal.proposals.zipWithIndex.foldM(before) { (ps, indexed) =>
        addProposal[F](env, network, bootstrap, accountRegistered, ps, indexed)
      }
      // the voters must exist, and so must the actions they vote on
      votes = signal.votes.flatMap { (voter, vs) => vs.map { (aid, v) => (voter, aid, v) } }
      // GOV.hs:597-604 checks named voters even when their individual vote maps are empty.
      hot =
        if signal.votes.isEmpty then Set.empty[Credential[HotCommittee]] else authorised(env.cert)
      unknownVoters <- votesKnown[F](env, hot, signal.votes)
      _ <- Refuse.unless[F](
        unknownVoters.isEmpty,
        id,
        gov(GovFailure.VotersDoNotExist(unknownVoters))
      )
      byId = if votes.isEmpty then Map.empty[GovActionId, GovActionState] else proposals.byId
      unknownActions = votes.map(_._2).filterNot(byId.contains).distinct
      _ <- Refuse.unless[F](
        unknownActions.isEmpty,
        id,
        gov(GovFailure.GovActionsDoNotExist(unknownActions))
      )
      // each vote on the action it names (all exist now)
      cast = votes.flatMap((voter, aid, v) => byId.get(aid).map(g => (voter, aid, v, g)))
      _ <- Refuse.unless[F](
        !bootstrap || bootstrapVotesOk(cast).isEmpty,
        id,
        gov(GovFailure.DisallowedVotesDuringBootstrap(bootstrapVotesOk(cast)))
      )
      expired = cast.collect {
        case (voter, aid, _, g) if env.epoch > g.expiresAfter => voter -> aid
      }
      _ <- Refuse.unless[F](expired.isEmpty, id, gov(GovFailure.VotingOnExpiredGovAction(expired)))
      disallowed = cast.collect {
        case (voter, aid, _, g) if !votingAllowed(voter, g.action) => voter -> aid
      }
      _ <- Refuse.unless[F](disallowed.isEmpty, id, gov(GovFailure.DisallowedVoters(disallowed)))
      voted = proposals.withVotes(votes.map((voter, aid, v) => (voter, v, aid)))
      unregistered = signal.certificates.collect { case Certificate.UnregisterDRep(c, _) =>
        c
      }.toSet
      cleaned =
        if unregistered.isEmpty then voted
        else
          voted.copy(actions =
            voted.actions.map(g => g.copy(drepVotes = g.drepVotes -- unregistered))
          )
      _ <- st.set(cleaned)
    yield ()

  /** The voters that do not exist: a DRep that is not registered, a pool that is not, a hot
    * committee key that no member has authorised.
    */
  private def votesKnown[F[_]: Monad](
      env: GovEnv,
      hot: Set[Credential[HotCommittee]],
      votes: Vector[(Voter, Vector[(GovActionId, Vote)])]
  )(using Raise[F, LedgerFailure]): F[Vector[Voter]] =
    votes
      .traverse { (voter, _) =>
        val exists: Either[VocabError, Boolean] = voter match
          case Voter.DRepVoter(c) =>
            Convert.credential[DRepRole](c).map(CertLens.dreps.get(env.cert).contains)
          case Voter.Pool(h) =>
            Convert.keyHash[StakePool](h).map(CertLens.pools.get(env.cert).contains)
          case Voter.CommitteeHot(h) => Convert.credential[HotCommittee](h).map(hot.contains)
        Refuse.vocab[F, Boolean](exists).map(known => Option.unless(known)(voter))
      }
      .map(_.flatten)

  /** The votes the bootstrap phase refuses (`checkBootstrapVotes`): a DRep votes on an info action
    * only, the others on the actions of the bootstrap.
    */
  private def bootstrapVotesOk(
      cast: Vector[(Voter, GovActionId, Vote, GovActionState)]
  ): Vector[(Voter, GovActionId)] =
    cast.collect {
      case (voter, aid, _, g) if !(voter match
            case Voter.DRepVoter(_) => g.action == GovAction.Info
            case _                  => GovChecks.isBootstrapAction(g.action)
          ) =>
        voter -> aid
    }

  /** `canBeVotedOnBy` (`checkVotersAreValid`). */
  private def votingAllowed(voter: Voter, action: GovAction): Boolean = voter match
    case Voter.CommitteeHot(_) => Ratify.isCommitteeVotingAllowed(action)
    case Voter.DRepVoter(_)    => Ratify.isDRepVotingAllowed(action)
    case Voter.Pool(_)         => Ratify.isPoolVotingAllowed(action)

  /** `processProposal` (`Gov.hs:483`): the proposal at index `idx` of the transaction is checked
    * and added to the store.
    */
  private def addProposal[F[_]: Monad](
      env: GovEnv,
      network: Int,
      bootstrap: Boolean,
      registered: node.RewardAccount => F[Boolean],
      ps: Proposals,
      indexed: (Proposal, Int)
  )(using Raise[F, LedgerFailure]): F[Proposals] =
    val (proposal, idx) = indexed
    val id = env.id
    val pp = env.pp
    val action = proposal.action
    def check(ok: Boolean, f: => GovFailure): F[Unit] =
      Refuse.unless[F](ok, id, PredicateFailure.gov(f))
    for
      _ <- check(
        !bootstrap || GovChecks.isBootstrapAction(action),
        GovFailure.DisallowedProposalDuringBootstrap(proposal)
      )
      // a hard fork must be able to follow the one before it
      _ <- action match
        case GovAction.HardFork(prev, major, minor) =>
          val reference: Option[(BigInt, BigInt)] =
            if prev == ps.roots.hardFork || major > pp.protocolMajor + 1 then
              Some(pp.protocolMajor -> pp.protocolMinor)
            else
              prev
                .flatMap(ps.byId.get)
                .flatMap(_.action match
                  case GovAction.HardFork(_, m, n) => Some(m -> n)
                  case _                           => None)
          check(
            reference.forall(GovChecks.canFollow(_, major -> minor)),
            GovFailure.ProposalCantFollow(
              prev,
              Mismatch(major -> minor, reference.getOrElse(pp.protocolMajor -> pp.protocolMinor))
            )
          )
        case _ => Monad[F].unit
      _ <- check(
        action match
          case GovAction.ParameterChange(_, u, _) => GovChecks.wellFormed(pp, u)
          case _                                  => true
        ,
        GovFailure.MalformedProposal(action)
      )
      // after the bootstrap phase the accounts a proposal names must exist
      _ <-
        if bootstrap then Monad[F].unit
        else
          for
            ok <- registered(proposal.returnAccount)
            _ <- check(ok, GovFailure.ProposalReturnAccountDoesNotExist(proposal.returnAccount))
            _ <- action match
              case GovAction.TreasuryWithdrawals(ws, _) =>
                ws.map(_._1)
                  .filterA(a => registered(a).map(!_))
                  .flatMap(unknown =>
                    check(
                      unknown.isEmpty,
                      GovFailure.TreasuryWithdrawalReturnAccountsDoNotExist(unknown.distinct)
                    )
                  )
              case _ => Monad[F].unit
          yield ()
      _ <- check(
        proposal.deposit == pp.govActionDeposit,
        GovFailure.ProposalDepositIncorrect(Mismatch(proposal.deposit, pp.govActionDeposit))
      )
      _ <- check(
        proposal.returnAccount.network == network,
        GovFailure.ProposalProcedureNetworkIdMismatch(proposal.returnAccount, network)
      )
      _ <- action match
        case GovAction.TreasuryWithdrawals(ws, policy) =>
          check(
            ws.forall(_._1.network == network),
            GovFailure.TreasuryWithdrawalsNetworkIdMismatch(
              ws.map(_._1).filter(_.network != network).toSet,
              network
            )
          ) *> check(
            policy == env.guardrails,
            GovFailure.InvalidGuardrailsScriptHash(policy, env.guardrails)
          ) *> check(ws.map(_._2).sum != 0, GovFailure.ZeroTreasuryWithdrawals(action))
        case GovAction.UpdateCommittee(_, removed, added, _) =>
          check(
            added.keySet.intersect(removed).isEmpty,
            GovFailure.ConflictingCommitteeUpdate(added.keySet.intersect(removed))
          ) *> check(
            added.values.forall(_ > env.epoch),
            GovFailure.ExpirationEpochTooSmall(added.filter(_._2 <= env.epoch))
          )
        case GovAction.ParameterChange(_, _, policy) =>
          check(
            policy == env.guardrails,
            GovFailure.InvalidGuardrailsScriptHash(policy, env.guardrails)
          )
        case _ => Monad[F].unit
      added <- ps
        .add(
          GovActionState(
            GovActionId(id, idx),
            Map.empty,
            Map.empty,
            Map.empty,
            proposal,
            env.epoch,
            env.epoch + pp.govActionLifetime
          )
        )
        .fold(
          Refuse[F, Proposals](
            id,
            PredicateFailure.gov(GovFailure.InvalidPrevGovActionId(proposal))
          )
        )(_.pure[F])
    yield added

// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.{
  ConwayEmptyGovernance as G,
  ConwayRegisteredDRepCompletion as D,
  ConwayNativeLikelihood as N,
  ConwayNonMyopic as NM,
  ConwayStake as S,
  ConwayEpochBoundary as B,
  ConwayRewardPulser as P,
  ConwayRewardStart as R
}
import scala.util.control.NonFatal

/** One supplied synthetic empty-governance boundary. No general RATIFY, pool reaping, nonempty-go
  * likelihood generator, native state admission, durable import or second boundary. Constructors
  * are private; the coordinator installs this immutable component in its owned cell.
  */
private[lab] object SyntheticBoundaryState:
  val Profile = "synthetic-one-empty-governance-boundary-v1"
  final class State private[SyntheticBoundaryState] (
      val governanceInput: G.Input,
      val governanceAfter: Option[G.Applied],
      val roles: GovernanceParameterPayload.Roles,
      val globals: GovernanceGlobals.Checked,
      val poolPayloads: Map[Bytes, GovernancePoolPayload.Checked],
      val nonMyopic: NM.State,
      val frozenHistory: Option[NM.State],
      val frozenId: Option[Bytes],
      val allocationId: Option[Bytes],
      private[SyntheticBoundaryState] val origin: Bytes,
      private[SyntheticBoundaryState] val boundaryParent: Option[Bytes],
      val id: Bytes,
      val repeatedLimit: Int,
      val transitions: Int,
      val checkedLikelihood: Option[N.Generated]
  ):
    val repeated = repeatedLimit > 0
    val boundaryApplied = governanceAfter.isDefined
    val syntheticOnly = true
    val nativeConformance = false
    val durableImport = false

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[E, A](value: Either[E, A]): A =
    value.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def digest(parts: Vector[String]): Bytes = ClusterHeaderObservation.sha256(
    Bytes.fromArray((Profile + parts.map(p => s"${p.length}:$p").mkString).getBytes("UTF-8"))
  )
  private def make(
      input: G.Input,
      after: Option[G.Applied],
      roles: GovernanceParameterPayload.Roles,
      globals: GovernanceGlobals.Checked,
      pools: Map[Bytes, GovernancePoolPayload.Checked],
      nm: NM.State,
      history: Option[NM.State],
      frozen: Option[Bytes],
      allocation: Option[Bytes],
      origin: Bytes,
      parent: Option[Bytes],
      repeatedLimit: Int = 0,
      transitions: Int = 0,
      likelihood: Option[N.Generated] = None
  ): State =
    val id = digest(
      Vector(
        origin.hex,
        roles.id.hex,
        globals.id.hex,
        nm.id.hex,
        after.fold("absent")(_.id.hex),
        history.fold("absent")(_.id.hex),
        frozen.fold("absent")(_.hex),
        allocation.fold("absent")(_.hex),
        parent.fold("absent")(_.hex)
      ) ++ (if repeatedLimit == 0 then Vector.empty
            else
              Vector(
                repeatedLimit.toString,
                transitions.toString,
                likelihood.fold("absent")(g =>
                  ClusterHeaderObservation
                    .sha256(g.request.original)
                    .hex + ClusterHeaderObservation.sha256(g.response).hex
                )
              ))
    )
    new State(
      input,
      after,
      roles,
      globals,
      pools,
      nm,
      history,
      frozen,
      allocation,
      origin,
      parent,
      id,
      repeatedLimit,
      transitions,
      likelihood
    )

  /** Explicit bounded, non-durable repeated research lane; seed checks remain unchanged. */
  def enableRepeated(state: State, maxTransitions: Int): Either[String, State] = checked {
    require(
      state != null && !state.boundaryApplied && state.frozenId.isEmpty &&
        !state.repeated && maxTransitions >= 1 && maxTransitions <= 8,
      "repeated initial scope"
    )
    val prospective = get(G.applyBoundary(state.governanceInput, state.governanceInput.epoch + 1))
    get(D.complete(prospective, prospective.id, prospective.epoch))
    make(
      state.governanceInput,
      None,
      state.roles,
      state.globals,
      state.poolPayloads,
      state.nonMyopic,
      None,
      None,
      None,
      state.origin,
      None,
      maxTransitions
    )
  }

  private def likelihood(state: State, frozen: B.Frozen): Map[Bytes, NM.Likelihood] =
    if !state.repeated then get(NM.generateForFrozen(frozen, frozen.id))
    else
      val generated = state.checkedLikelihood.getOrElse(
        throw new IllegalArgumentException("checked JVM/native freeze comparison required")
      )
      require(
        generated.mode == N.Mode.CheckedJvm && !generated.nativeValuesAuthoritative &&
          generated.jvmMismatchWords == 0,
        "native authoritative values forbidden in checked JVM lane"
      )
      get(generated.forFrozen(frozen, frozen.id))

  def attachLikelihood(state: State, generated: N.Generated): Either[String, State] = checked {
    require(
      state != null && state.repeated && generated != null && state.checkedLikelihood.isEmpty,
      "new repeated freeze evidence required"
    )
    require(
      generated.mode == N.Mode.CheckedJvm && !generated.nativeValuesAuthoritative &&
        generated.jvmMismatchWords == 0,
      "checked JVM comparison required"
    )
    make(
      state.governanceInput,
      state.governanceAfter,
      state.roles,
      state.globals,
      state.poolPayloads,
      state.nonMyopic,
      state.frozenHistory,
      state.frozenId,
      state.allocationId,
      state.origin,
      state.boundaryParent,
      state.repeatedLimit,
      state.transitions,
      Some(generated)
    )
  }

  def typedGlobals(value: GovernanceGlobals.Checked): Either[String, G.SuppliedFixedGlobals] =
    checked {
      require(value != null, "checked fixed globals required")
      get(G.suppliedFixedGlobals(value.securityParameter, value.id))
    }
  private def distribution(snapshot: S.Snapshot): G.PoolDistribution =
    val domain = snapshot.distribution
    val total = if domain.isEmpty then BigInt(1) else snapshot.total
    G.PoolDistribution(
      total,
      domain.map { (p, s) =>
        p -> G.PoolShare(s.coin, get(S.fraction(s.coin, total)), s.vrf)
      }
    )
  private def checkAccounts(input: G.Input, stake: S.Context): Unit =
    require(input.accounts.keySet == stake.accounts.keySet, "complete governance account domain")
    input.accounts.foreach { (c, a) =>
      val s = stake.accounts(c)
      require(
        a.rewards == s.balance && a.deposit == s.deposit && a.pool == s.delegation,
        "governance/stake account projection mismatch"
      )
    }
    input.dreps.foreach { (c, d) =>
      require(
        d.delegators == input.accounts.collect {
          case (a, x) if x.vote.contains(G.Vote.Credential(c)) => a
        }.toSet,
        "governance DRep delegator index mismatch"
      )
    }

  def seed(
      input: G.Input,
      roles: GovernanceParameterPayload.Roles,
      pools: Map[Bytes, GovernancePoolPayload.Checked],
      globals: GovernanceGlobals.Checked,
      nonMyopic: NM.State,
      context: SequenceInput.Context,
      stake: S.State,
      pots: B.Pots,
      rewardParameters: R.Parameters,
      rewardGlobals: R.Globals
  ): Either[String, State] = checked {
    require(
      input != null && roles != null && pools != null && pools.size <= G.MaxEntries &&
        globals != null && nonMyopic != null && context != null && stake != null && pots != null &&
        rewardParameters != null && rewardGlobals != null,
      "complete composition inputs required"
    )
    require(
      input.globals == get(typedGlobals(globals)),
      "legacy/foreign governance globals unsupported"
    )
    // Validate the complete bounded governance graph before traversing/hashing caller collections.
    val validation = get(G.applyBoundary(input, input.epoch + 1))
    require(
      input.epoch == stake.epoch && input.epoch == context.epoch &&
        stake.ledgerId == context.ledger.id && stake.slot == context.ledger.slot,
      "composition seed ledger/stake epoch or identity"
    )
    get(GovernanceGlobals.checkHeader(globals, context))
    require(
      roles.previous.sha256 == globals.previousParameterSHA256 &&
        roles.current.sha256 == globals.currentParameterSHA256,
      "composition parameter roles differ from globals source binding"
    )
    require(
      rewardGlobals.id == globals.rewardGlobals.id && pots.maxSupply == globals.maxLovelaceSupply,
      "composition reward globals/supply mismatch"
    )
    get(GovernanceParameterPayload.checkRewards(roles.previous, rewardParameters))
    get(
      GovernanceParameterPayload.checkProjections(
        roles.current,
        context.ledger.environment.feeParameters,
        context.ledger.environment.minimumOutputParameters,
        roles.current.rewards
      )
    )
    require(
      roles.previous.rewards.original == roles.current.rewards.original &&
        rewardParameters.original == roles.previous.rewards.original,
      "one-boundary profile requires identical scoped reward parameters across rollover"
    )
    require(
      input.parameters.previous.original == roles.previous.original &&
        input.parameters.current.original == roles.current.original,
      "complete governance parameter role originals mismatch"
    )
    checkAccounts(input, stake.context)
    require(
      input.instantaneous == stake.instantaneous &&
        input.newMarkPoolDistribution == distribution(stake.snapshots.mark),
      "governance stake/mark projection mismatch"
    )
    require(
      pools.keySet == stake.context.pools.keySet && pools.keySet == input.stakePools.keySet,
      "complete governance pool domain"
    )
    pools.foreach { (id, payload) =>
      require(payload != null, "checked pool payload required")
      get(GovernancePoolPayload.checkProjection(payload, stake.context.pools(id)))
      require(
        input.stakePools(id).parameters.original == payload.original &&
          input.stakePools(id).deposit == payload.pool.deposit,
        "governance full pool projection"
      )
    }
    require(
      pots.treasury == input.treasury && pots.fees == context.ledger.fees &&
        pots.treasury >= 0 && pots.reserves >= 0 && pots.fees >= 0 &&
        pots.maxSupply == stake.utxo.values
          .map(_.coin)
          .sum + input.accounts.values.map(_.rewards).sum +
        pots.treasury + pots.reserves + pots.fees + input.deposits.total,
      "complete composition supply/pot accounting"
    )
    val origin = digest(
      Vector(
        validation.id.hex,
        context.id.hex,
        stake.id.hex,
        roles.id.hex,
        globals.id.hex,
        nonMyopic.id.hex
      ) ++
        pools.toVector.sortBy(_._1.hex).flatMap((k, v) => Vector(k.hex, v.sha256.hex))
    )
    make(input, None, roles, globals, pools, nonMyopic, None, None, None, origin, None)
  }

  private def bindFreeze(
      state: State,
      frozen: Option[B.Frozen],
      pulser: Option[P.State],
      history: NM.State
  ): State =
    require(
      state != null && frozen != null && pulser != null && history != null,
      "composition reward phase required"
    )
    require(frozen.isDefined == pulser.isDefined, "composition frozen/pulser phase mismatch")
    frozen match
      case None =>
        require(state.frozenId.isEmpty, "owned monetary freeze cannot disappear")
        state
      case Some(f) =>
        val p = pulser.get
        require(
          f != null && p != null && p.frozenId == f.id &&
            f.epoch == state.governanceAfter.fold(state.governanceInput.epoch)(_.epoch) &&
            f.rewardParameters.exists(_.original == state.roles.previous.rewards.original) &&
            f.rewardGlobals.exists(_.id == state.globals.rewardGlobals.id),
          "composition frozen monetary source mismatch"
        )
        val allocation = get(R.calculate(f, f.id))
        require(p.allocationId == allocation.id, "composition owned allocation mismatch")
        likelihood(state, f) // Exact captured comparison is required before publication.
        state.frozenId match
          case Some(id) =>
            require(
              id == f.id && state.allocationId.contains(
                allocation.id
              ) && state.frozenHistory.isDefined,
              "composition freeze replacement unsupported"
            )
            state
          case None =>
            make(
              state.governanceInput,
              state.governanceAfter,
              state.roles,
              state.globals,
              state.poolPayloads,
              state.nonMyopic,
              Some(history),
              Some(f.id),
              Some(allocation.id),
              state.origin,
              state.boundaryParent,
              state.repeatedLimit,
              state.transitions,
              state.checkedLikelihood
            )

  def advanceFreeze(
      state: State,
      frozen: Option[B.Frozen],
      pulser: Option[P.State]
  ): Either[String, State] = checked {
    require(state != null, "composition state required")
    bindFreeze(state, frozen, pulser, state.nonMyopic)
  }

  def atBoundary(
      state: State,
      frozen: Option[B.Frozen],
      completedPulser: Option[P.State],
      preview: B.Preview,
      completeEffect: Option[B.Complete]
  ): Either[String, State] = checked {
    require(
      state != null && (!state.boundaryApplied || state.repeated) &&
        (!state.repeated || state.transitions < state.repeatedLimit) && preview != null && frozen != null &&
        completedPulser != null && completeEffect != null,
      "only one composed boundary supported"
    )
    require(
      preview.epoch == state.governanceInput.epoch + 1 &&
        preview.before.pots.treasury == state.governanceInput.treasury,
      "composition exact successor required"
    )
    checkAccounts(state.governanceInput, preview.before.application)
    require(
      preview.before.application.pools == state.poolPayloads.map((k, v) => k -> v.pool),
      "composition boundary pool context mismatch"
    )
    val completedNM = frozen match
      case None =>
        require(
          state.frozenId.isEmpty && completedPulser.isEmpty && completeEffect.isEmpty &&
            preview.rewardApplication.isEmpty,
          "absent composition reward mismatch"
        )
        None
      case Some(f) =>
        require(
          state.frozenId.contains(f.id) && state.frozenHistory.isDefined &&
            completedPulser.isDefined && completeEffect.isDefined,
          "owned freeze/completion required"
        )
        val p = completedPulser.get
        val completion =
          p.completion.getOrElse(throw new IllegalArgumentException("incomplete monetary pulser"))
        val effect = completeEffect.get
        val allocation = get(R.calculate(f, f.id))
        require(
          p.frozenId == f.id && p.allocationId == allocation.id &&
            state.allocationId.contains(allocation.id) && completion.frozenId == f.id &&
            completion.allocationId == allocation.id && effect.frozen.id == f.id &&
            effect.completionIdentity.contains(completion.completed.id) &&
            preview.rewardIdentity == effect.id && preview.rewardApplication.isDefined &&
            preview.balances == effect.balances && preview.pots == effect.pots,
          "monetary/non-myopic completion identity mismatch"
        )
        Some(
          get(
            NM.completeFrozen(
              state.frozenHistory.get,
              state.frozenHistory.get.id,
              f,
              f.id,
              allocation,
              likelihood(state, f)
            )
          )
        )
    val nm = get(NM.applyAtBoundary(state.nonMyopic, state.nonMyopic.id, completedNM))
    require(
      preview.balances.keySet == state.governanceInput.accounts.keySet,
      "postreward account domain changed"
    )
    val accounts = state.governanceInput.accounts.map { (c, a) =>
      c -> a.copy(rewards = preview.balances(c)) // Preserve deposits, pool delegation and voting.
    }
    val input = state.governanceInput.copy(
      accounts = accounts,
      instantaneous = preview.before.stake.instantaneous,
      treasury = preview.pots.treasury,
      newMarkPoolDistribution = distribution(preview.rotation.snapshots.mark)
    )
    require(
      preview.pots.maxSupply == state.globals.maxLovelaceSupply &&
        preview.pots.maxSupply == preview.before.stake.utxo.values.map(_.coin).sum +
        accounts.values.map(_.rewards).sum + preview.pots.treasury + preview.pots.reserves +
        preview.pots.fees + input.deposits.total,
      "postreward supply accounting"
    )
    val after = get(G.applyBoundary(input, preview.epoch))
    val nextRoles =
      if state.repeated then get(GovernanceParameterPayload.rollover(state.roles, after))
      else state.roles
    val nextInput =
      if !state.repeated then input
      else
        val completed = get(D.complete(after, after.id, after.epoch))
        input.copy(
          epoch = after.epoch,
          dormant = after.dormant,
          committeeState = after.committeeState,
          parameters = after.parameters,
          oldDRep = get(D.forSource(completed, after, after.id, after.epoch)),
          donations = 0
        )
    make(
      nextInput,
      Some(after),
      nextRoles,
      state.globals,
      state.poolPayloads,
      nm,
      None,
      None,
      None,
      digest(Vector(state.origin.hex, preview.id.hex, completeEffect.fold("absent")(_.id.hex))),
      Some(state.id),
      state.repeatedLimit,
      state.transitions + 1,
      None
    )
  }

  /** A late first successor captures the pre-tick NM history, not the newly selected NM result. */
  def afterBoundaryFreeze(
      before: State,
      after: State,
      frozen: Option[B.Frozen],
      pulser: Option[P.State]
  ): Either[String, State] = checked {
    require(
      before != null && after != null && after.boundaryParent.contains(before.id),
      "composition boundary ancestry mismatch"
    )
    bindFreeze(after, frozen, pulser, before.nonMyopic)
  }

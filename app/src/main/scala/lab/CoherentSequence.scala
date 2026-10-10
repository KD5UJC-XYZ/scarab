// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Sync}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.header.{
  PraosCertificateState as Certificate,
  PraosEligibility,
  PraosNonceEvolution as Nonces
}
import lab.ledger.{
  ClusterTransition as Ledger,
  ConwayStake as Stake,
  ConwayEpochBoundary as Boundary,
  ConwayRewardStart as RewardStart,
  ConwayRewardPulser as Pulser,
  ConwayPoolReward as PoolReward,
  ConwayEmptyGovernance as Governance,
  ConwayNonMyopic as NonMyopic
}
import lab.network.ChainSync
import lab.vrf.{PraosVrfCertificate as Vrf, PraosLeaderThreshold as Leader}
import scala.util.control.NonFatal

/** A checked same-epoch tuple and at most eight retained rollback receipts. Explicit fenced
  * compaction advances a derived anchor, not consensus finality or supplied snapshot authority.
  */
object CoherentSequence:
  val ProfileId = "conway-pv9-header11-2-derived-nonce-bounded-sequence-v1"
  val MaxBlocks = 8
  enum Failure:
    case Unsupported(stage: String, feature: String)
    case Rejected(stage: String, reason: String)
    case LedgerRejected(reason: Ledger.Failure)
    case ForeignCandidate
    case StaleCandidate
    case ForeignFence
    case StaleFence
    case OutsideRetainedWindow
    case RevisionExhausted
  type Result[A] = Either[Failure, A]

  final class State private[CoherentSequence] (
      val contextId: Bytes,
      val certificates: CertificateBranch.Branch,
      val nonces: Nonces.State,
      val eligibility: Option[PraosEligibility.Checked],
      val ledger: Ledger.State,
      val compactedBlocks: BigInt = 0,
      val derivedAnchorId: Option[Bytes] = None,
      val trustedLocalPrefix: Boolean = false,
      private[CoherentSequence] val stakeBinding: Option[(Stake.Owner, Stake.State)] = None,
      private[CoherentSequence] val rewardBinding: Option[SyntheticRewards] = None,
      private[CoherentSequence] val epochBinding: Option[SyntheticEpochContext] = None,
      private[CoherentSequence] val boundaryOrigin: Option[HistoricalBoundary] = None,
      private[lab] val syntheticBoundary: Option[SyntheticBoundaryState.State] = None
  ):
    def stake: Option[Stake.State] = stakeBinding.map(_._2)
    private[lab] def supportsRestrictedImageExport: Boolean =
      rewardBinding.isEmpty && epochBinding.isEmpty && boundaryOrigin.isEmpty && syntheticBoundary.isEmpty
    def syntheticRewards: Option[SyntheticRewards] = rewardBinding
    def acquisition: BoundedChainFollower.Checkpoint = certificates.acquisition
    def revision: BigInt = ledger.revision

    /** Current branch depth from the original supplied anchor, not a lifetime event counter. */
    val depth: BigInt = compactedBlocks + acquisition.size
    val scopedAppliedTip: Option[ChainSync.Point] =
      Option.when(depth > 0)(acquisition.tip)
    val id: Bytes = digest(
      "state",
      Vector(
        contextId,
        acquisitionIdentity(acquisition),
        certificates.state.id,
        nonces.id,
        eligibility.fold(Bytes.empty)(_.contextId),
        ledger.id
      ) ++ stake.toVector.map(_.id) ++ rewardBinding.toVector.map(_.id) ++ epochBinding.toVector
        .map(_.id) ++ boundaryOrigin.toVector.map(_.id) ++ syntheticBoundary.toVector.map(
        _.id
      ) ++ derivedAnchorId.toVector
        .flatMap(id => Vector(raw(compactedBlocks.toString), id))
    )
    val fullLedgerValidated = false
    val consensusValidated = false

  final class Fence private[CoherentSequence] (
      private[CoherentSequence] val owner: AnyRef,
      private[CoherentSequence] val stateId: Bytes,
      private[CoherentSequence] val revision: BigInt
  )
  final class Snapshot private[CoherentSequence] (val state: State, val fence: Fence)
  final class Candidate private[CoherentSequence] (
      private[CoherentSequence] val owner: AnyRef,
      private[CoherentSequence] val before: State,
      private[CoherentSequence] val block: SequenceInput.Block,
      private[CoherentSequence] val certificates: CertificateBranch.Branch,
      private[CoherentSequence] val certificate: Certificate.Applied,
      private[CoherentSequence] val nonce: Nonces.Applied,
      private[CoherentSequence] val eligibility: PraosEligibility.Checked,
      private[CoherentSequence] val ledger: Ledger.BlockCandidate,
      private[CoherentSequence] val stake: Option[Stake.Candidate],
      private[CoherentSequence] val rewards: Option[SyntheticRewards],
      private[CoherentSequence] val epochBinding: Option[SyntheticEpochContext] = None,
      private[CoherentSequence] val transitionBoundary: Option[HistoricalBoundary] = None,
      private[CoherentSequence] val syntheticBoundary: Option[SyntheticBoundaryState.State] = None,
      private[CoherentSequence] val compositionPending: Boolean = false
  )
  final class Applied private[CoherentSequence] (
      val state: State,
      val ledgerObservation: Ledger.BlockApplied,
      val certificateObservation: Certificate.Applied,
      val nonceObservation: Nonces.Applied
  ):
    val profileId = ProfileId
    val fullLedgerValidated = false
    val consensusValidated = false
    val suppliedContextEligibilityVerified = true
  private final case class OwnedReceipt(
      before: State,
      afterId: Bytes,
      certificate: Certificate.Applied,
      nonce: Nonces.Applied,
      ledger: Ledger.Undo,
      position: HistoricalPosition,
      historicalAfterId: Bytes,
      boundary: Option[HistoricalBoundary]
  )
  private final case class Cell(state: State, receipts: Vector[OwnedReceipt])

  /** Historical attribution is kept separately from rebased live tuple identity. No State, Runtime
    * or receipt chain occurs inside this bounded source capsule.
    */
  private[lab] final case class HistoricalPosition(
      id: Bytes,
      revision: BigInt,
      certificates: CertificateBranch.Branch,
      compactedBlocks: BigInt,
      derivedAnchorId: Option[Bytes],
      trustedLocalPrefix: Boolean
  )
  private[lab] final class HistoricalBoundary private[CoherentSequence] (
      val preview: Boundary.Preview,
      val previousCertificates: Certificate.Context,
      val previousNonces: Nonces.Context,
      val previousLedgerEnvironment: Bytes
  ):
    val id = digest(
      "historical-boundary-v1",
      Vector(
        preview.id,
        preview.before.tupleId,
        raw(preview.before.stake.revision.toString),
        previousCertificates.id,
        previousNonces.id,
        previousLedgerEnvironment
      )
    )
  private[lab] final case class RecoveryRecord(
      original: BoundedChainFollower.Original,
      position: HistoricalPosition,
      historicalAfterId: Bytes,
      boundary: Option[HistoricalBoundary]
  )
  private[lab] final case class RecoveryImage(
      context: SequenceInput.Context,
      capacity: Int,
      states: Vector[State],
      records: Vector[RecoveryRecord]
  )
  private def historicalPosition(state: State): HistoricalPosition = HistoricalPosition(
    state.id,
    state.revision,
    state.certificates,
    state.compactedBlocks,
    state.derivedAnchorId,
    state.trustedLocalPrefix
  )

  private[lab] def recoveryBoundaries(image: RecoveryImage): Vector[HistoricalBoundary] =
    require(
      image.states.forall(_.syntheticBoundary.isEmpty),
      "one-boundary composition has no recovery model"
    )
    (image.states.flatMap(_.boundaryOrigin) ++ image.records.flatMap(_.boundary)).distinctBy(_.id)
  private[lab] def recoveryCertificateContexts(image: RecoveryImage): Vector[Certificate.Context] =
    (Vector(image.context.certificates) ++ image.states.flatMap(
      _.epochBinding.map(_.certificates)
    ) ++
      recoveryBoundaries(image).map(_.previousCertificates)).distinctBy(_.id)
  private[lab] def recoveryStateFields(state: State): Vector[Bytes] = Vector(
    state.id,
    raw(state.revision.toString),
    state.certificates.state.id,
    state.nonces.id,
    state.ledger.id,
    state.stake.get.id,
    state.syntheticRewards.get.id
  ) ++
    state.epochBinding.toVector.map(_.id) ++ state.boundaryOrigin.toVector.map(_.id)

  private def sameRecoveryContent(a: State, b: State): Boolean =
    a.contextId == b.contextId && a.certificates.state.id == b.certificates.state.id &&
      a.certificates.state.contextId == b.certificates.state.contextId && a.nonces.id == b.nonces.id &&
      a.ledger.id == b.ledger.id && a.stake.map(_.id) == b.stake.map(_.id) &&
      a.syntheticRewards.map(_.id) == b.syntheticRewards.map(_.id) &&
      a.eligibility.map(_.contextId) == b.eligibility.map(_.contextId) &&
      a.epochBinding.map(_.id) == b.epochBinding.map(_.id) && a.boundaryOrigin.map(
        _.id
      ) == b.boundaryOrigin.map(_.id)

  private def historicalState(actual: State, p: HistoricalPosition): Result[State] = protect {
    val state = new State(
      actual.contextId,
      p.certificates,
      actual.nonces,
      actual.eligibility,
      actual.ledger,
      p.compactedBlocks,
      p.derivedAnchorId,
      p.trustedLocalPrefix,
      actual.stakeBinding,
      actual.rewardBinding,
      actual.epochBinding,
      actual.boundaryOrigin,
      actual.syntheticBoundary
    )
    Either.cond(
      state.id == p.id && state.revision == p.revision && sameRecoveryContent(state, actual),
      state,
      Failure.Rejected("recovery", "historical predecessor attribution/content mismatch")
    )
  }

  /** Controller-authenticated opaque handoff only; not a raw image/identity hydration setter. */
  private[lab] def hydrateSyntheticRecovery[F[_]: Async](
      authorized: SyntheticRecoveryModel.Authorized
  ): F[Result[Runtime[F]]] =
    val image = authorized.image
    val F = Async[F]
    def validate(i: Int): Result[OwnedReceipt] = protect {
      val record = image.records(i)
      val actual = image.states(i)
      val after = image.states(i + 1)
      val verifierOwner = new Object()
      for
        before <- historicalState(actual, record.position)
        block <- SequenceInput
          .block(record.original)
          .left
          .map(e => Failure.Rejected("recovery-input", e.toString))
        pending <- record.boundary match
          case None =>
            prepare(verifierOwner, image.context, image.capacity, before, block, synthetic = true)
          case Some(boundary) =>
            for
              preview <- successor(
                verifierOwner,
                before,
                snapshot(verifierOwner, before).fence,
                block.header.hash,
                block.header.slot
              )
              _ <- Either.cond(
                preview.preview.id == boundary.preview.id && boundary.id == after.boundaryOrigin
                  .map(_.id)
                  .getOrElse(Bytes.empty),
                (),
                Failure.Rejected("recovery", "historical successor provenance mismatch")
              )
              candidate <- prepareSuccessor(
                verifierOwner,
                image.context,
                image.capacity,
                before,
                snapshot(verifierOwner, before).fence,
                preview,
                block
              )
            yield candidate
        applied <- publish(verifierOwner, image.capacity, Cell(before, Vector.empty), pending)
        (cell, result) = applied
        _ <- Either.cond(
          result.state.id == record.historicalAfterId && sameRecoveryContent(result.state, after) &&
            result.state.revision <= after.revision,
          (),
          Failure.Rejected("recovery", "retained transition or adjacent content mismatch")
        )
      yield cell.receipts.head
    }
    def cloneBoundary(
        bo: Boundary.Owner,
        so: Stake.Owner,
        b: HistoricalBoundary
    ): Result[HistoricalBoundary] =
      for
        stake <- checked("recovery-stake", Stake.reownForRecovery(so, b.preview.before.stake))
        preview <- checked(
          "recovery-boundary",
          Boundary.reownPreviewForRecovery(bo, so, b.preview, stake)
        )
      yield new HistoricalBoundary(
        preview,
        b.previousCertificates,
        b.previousNonces,
        b.previousLedgerEnvironment
      )
    def cloneState(bo: Boundary.Owner, so: Stake.Owner, s: State): Result[State] = protect {
      for
        originalStake <- s.stake.toRight(Failure.Rejected("recovery", "stake required"))
        stake <- checked("recovery-stake", Stake.reownForRecovery(so, originalStake))
        originalRewards <- s.syntheticRewards.toRight(
          Failure.Rejected("recovery", "rewards required")
        )
        frozen <- originalRewards.frozen.traverse(f =>
          checked("recovery-frozen", Boundary.reownFrozenForRecovery(bo, so, f))
        )
        pulser <- originalRewards.pulser.traverse(p =>
          frozen
            .toRight(Failure.Rejected("recovery", "pulser missing frozen"))
            .flatMap(f => checked("recovery-pulser", Pulser.reownForRecovery(p, f)))
        )
        rewards = new SyntheticRewards(
          bo,
          originalRewards.profile,
          originalRewards.pots,
          originalRewards.previousBlocks,
          originalRewards.currentBlocks,
          frozen,
          pulser,
          originalRewards.origin
        )
        origin <- s.boundaryOrigin.traverse(cloneBoundary(bo, so, _))
        next = new State(
          s.contextId,
          s.certificates,
          s.nonces,
          s.eligibility,
          s.ledger,
          s.compactedBlocks,
          s.derivedAnchorId,
          s.trustedLocalPrefix,
          Some(so -> stake),
          Some(rewards),
          s.epochBinding,
          origin
        )
        _ <- Either.cond(
          next.id == s.id,
          (),
          Failure.Rejected("recovery", "fresh-owner identity mismatch")
        )
      yield next
    }
    val preflight = protect {
      val n = image.records.size
      Either.cond(
        image.capacity >= 1 && image.capacity <= MaxBlocks && n <= image.capacity && image.states.size == n + 1 &&
          image.states.forall(s =>
            s.contextId == image.context.id && s.stake.isDefined && s.syntheticRewards.isDefined && s.syntheticBoundary.isEmpty
          ) &&
          image.states.head.acquisition.size == 0 && image.states.last.acquisition.size == n &&
          image.states.zipWithIndex.forall((s, i) =>
            s.acquisition.anchor == image.states.head.acquisition.anchor &&
              s.acquisition.originals == image.records
                .take(i)
                .map(_.original) && s.revision <= image.states.last.revision
          ),
        (),
        Failure.Rejected("recovery", "bounded anchor/retained image linkage")
      )
    }
    preflight match
      case Left(error) => F.pure(Left(error))
      case Right(_) =>
        image.records.indices.toVector.traverse(i => F.cede *> F.delay(validate(i))).flatMap {
          results =>
            results.sequence match
              case Left(error) => F.pure(Left(error))
              case Right(verified) =>
                for
                  bo <- F.delay(Boundary.owner())
                  so <- F.delay(Stake.owner())
                  states <- image.states.traverse(s => F.cede *> F.delay(cloneState(bo, so, s)))
                  boundaries <- image.records
                    .traverse(r => F.delay(r.boundary.traverse(cloneBoundary(bo, so, _))))
                  result <- (states.sequence, boundaries.sequence).mapN((_, _)) match
                    case Left(error) => F.pure(Left(error))
                    case Right((states, boundaries)) =>
                      val receipts = verified.zipWithIndex.map { (v, i) =>
                        OwnedReceipt(
                          states(i),
                          states(i + 1).id,
                          v.certificate,
                          v.nonce,
                          v.ledger,
                          image.records(i).position,
                          image.records(i).historicalAfterId,
                          boundaries(i)
                        )
                      }
                      for
                        owner <- F.delay(new Object())
                        cell <- Ref.of[F, Cell](Cell(states.last, receipts))
                      yield Right(new Runtime(image.context, image.capacity, owner, cell))
                yield result
        }

  /** Explicit assertions for a synthetic no-effect model, not extracted native state. No defaults:
    * absence is unknown and rejected. These effects are neither implemented nor carried forward.
    */
  final class SyntheticRewardProfile private[CoherentSequence] (
      val parameters: RewardStart.Parameters,
      val globals: RewardStart.Globals,
      val window: BigInt,
      val id: Bytes,
      private[CoherentSequence] val legacyNoEffects: Boolean = true
  )
  def syntheticRewardProfile(
      parameters: RewardStart.Parameters,
      globals: RewardStart.Globals,
      window: BigInt,
      poolReaping: Option[Boolean],
      refunds: Option[BigInt],
      governance: Option[Boolean],
      enactment: Option[Boolean],
      donations: Option[BigInt],
      parameterRollover: Option[Boolean],
      nonMyopic: Option[Boolean]
  ): Result[SyntheticRewardProfile] = protect {
    Either
      .cond(
        parameters != null && parameters.pool.isDefined && globals != null &&
          globals.securityParameter.isDefined && window != null && window > 0 &&
          2 * window < globals.epochLength &&
          poolReaping.contains(false) && refunds.contains(BigInt(0)) &&
          governance.contains(false) && enactment.contains(false) && donations.contains(
            BigInt(0)
          ) &&
          parameterRollover.contains(false) && nonMyopic.contains(false),
        (),
        Failure.Unsupported(
          "synthetic-rewards",
          "explicit no-effect assertions and bounded checked reward inputs required"
        )
      )
      .map(_ =>
        new SyntheticRewardProfile(
          parameters,
          globals,
          window,
          digest("synthetic-no-effects-v1", Vector(parameters.id, globals.id, raw(window.toString)))
        )
      )
  }

  /** Opaque branch-owned reward state. Frozen holds a bounded pre-tick capsule, never a State or
    * receipt chain. Only accepted block candidates can install progression in the coordinator.
    */
  final class SyntheticRewards private[CoherentSequence] (
      private[CoherentSequence] val owner: Boundary.Owner,
      val profile: SyntheticRewardProfile,
      val pots: Boundary.Pots,
      val previousBlocks: Map[Bytes, BigInt],
      val currentBlocks: Map[Bytes, BigInt],
      val frozen: Option[Boundary.Frozen],
      val pulser: Option[Pulser.State],
      val origin: Bytes
  ):
    val id: Bytes = digest(
      "owned-synthetic-rewards-v1",
      Vector(
        profile.id,
        origin,
        raw(pots.toString),
        countIdentity(previousBlocks),
        countIdentity(currentBlocks)
      ) ++
        frozen.toVector.map(_.id) ++ pulser.toVector.map(_.id)
    )
    val syntheticOnly = true
    val epochTransitionValidated = false

  final class SyntheticSuccessor private[CoherentSequence] (
      private[CoherentSequence] val owner: AnyRef,
      private[CoherentSequence] val before: State,
      val preview: Boundary.Preview,
      val completedPulser: Option[Pulser.State],
      private[CoherentSequence] val completeEffect: Option[Boundary.Complete],
      val id: Bytes
  ):
    val published = false
    val headerAndBlockChecked = false
    val epochTransitionValidated = false

  private final class SyntheticEpochContext(
      val certificates: Certificate.Context,
      val nonces: Nonces.Context,
      val stakes: Map[Bytes, Leader.Fraction]
  ):
    val id = digest(
      "synthetic-epoch-context",
      Vector(certificates.id, nonces.id) ++
        stakes.toVector
          .sortBy(_._1.hex)
          .flatMap((p, f) => Vector(p, raw(s"${f.numerator}/${f.denominator}")))
    )

  private def afterBoundaryRewards(
      current: State,
      preview: Boundary.Preview,
      selectedStake: Stake.State
  ): Result[Option[SyntheticRewards]] =
    current.rewardBinding.traverse { before =>
      for
        timing <- checked(
          "post-boundary-rupd",
          Boundary.rewardTiming(
            preview.epoch * before.profile.globals.epochLength,
            before.profile.window,
            preview.signal.slot
          )
        )
        work <-
          if timing == Boundary.Timing.TooEarly then Right((None, None))
          else
            for
              frozen <- checked(
                "post-boundary-rupd",
                Boundary.freezeAfterBoundary(
                  before.owner,
                  preview,
                  selectedStake,
                  before.profile.window,
                  before.profile.parameters,
                  before.profile.globals
                )
              )
              allocation <- checked("post-boundary-rupd", RewardStart.calculate(frozen, frozen.id))
              pools <- frozen.go.pools.keys.toVector.traverse(p =>
                checked(
                  "post-boundary-rupd",
                  PoolReward.calculate(frozen, frozen.id, allocation, allocation.id, p)
                ).map(p -> _)
              )
              pulser <- checked(
                "post-boundary-rupd",
                Pulser.start(frozen, frozen.id, allocation, allocation.id, pools.toMap)
              )
            yield (Some(frozen), Some(pulser))
      yield new SyntheticRewards(
        before.owner,
        before.profile,
        preview.pots,
        preview.previousBlocks,
        Map.empty,
        work._1,
        work._2,
        digest(
          "successor-reward-origin",
          Vector(before.origin, current.id, raw(current.revision.toString), preview.id)
        )
      )
    }

  private def prepareSuccessor(
      owner: AnyRef,
      context: SequenceInput.Context,
      capacity: Int,
      current: State,
      fence: Fence,
      preview: SyntheticSuccessor,
      block: SequenceInput.Block,
      deferComposition: Boolean = false
  ): Result[Candidate] = protect {
    for
      _ <- Either.cond(fence.owner eq owner, (), Failure.ForeignFence)
      _ <- Either.cond(
        fence.stateId == current.id && fence.revision == current.revision,
        (),
        Failure.StaleFence
      )
      _ <- Either.cond(preview.owner eq owner, (), Failure.ForeignCandidate)
      _ <- Either.cond(
        preview.before.id == current.id && preview.before.revision == current.revision,
        (),
        Failure.StaleCandidate
      )
      _ <- Either.cond(
        current.acquisition.size < capacity,
        (),
        Failure.Unsupported("window", "retained capacity reached")
      )
      _ <- Either.cond(
        preview.preview.signal.headerHash == block.header.hash && preview.preview.signal.slot == block.header.slot,
        (),
        Failure.Rejected("synthetic-successor", "preview/block identity mismatch")
      )
      oldCertificates = current.epochBinding.fold(context.certificates)(_.certificates)
      oldNonces = current.epochBinding.fold(context.nonces.context)(_.nonces)
      distribution = preview.preview.rotation.leadership.distribution
      nextCertificates <- checked(
        "successor-certificate-context",
        Certificate.Context.checkedSuccessor(
          oldCertificates,
          preview.preview.id,
          distribution.map((p, s) => p -> s.vrf)
        )
      )
      nextNonces <- checked(
        "successor-nonce-context",
        Nonces.Context.checkedSuccessor(oldNonces, nextCertificates)
      )
      stakes <- distribution.toVector
        .traverse((p, s) =>
          checked(
            "successor-stake",
            Leader.Fraction.checked(s.ratio.numerator, s.ratio.denominator)
          ).map(p -> _)
        )
        .map(_.toMap)
      certificate <- checked(
        "successor-certificate",
        Certificate.applySuccessorHeader(
          oldCertificates,
          nextCertificates,
          current.certificates.state,
          block.header.raw,
          block.header.hash
        )
      )
      certificates <- checked(
        "successor-acquisition",
        CertificateBranch.appendStep(current.certificates, block.original, certificate)
      )
      nonce <- checked(
        "successor-nonce",
        Nonces.applySuccessorHeader(oldNonces, nextNonces, current.nonces, certificate)
      )
      epochNonce = nonce.epochNonceUsed match
        case Nonces.Nonce.Neutral     => Vrf.NeutralNonce
        case Nonces.Nonce.Hash(bytes) => Vrf.Hash32.fromBytes(bytes).toOption.get
      eligibilityContext <- checked(
        "successor-eligibility-context",
        PraosEligibility.Context.checkedSuccessor(
          oldCertificates,
          nextCertificates,
          current.certificates.state,
          preview.preview.epoch,
          oldNonces.epochLength,
          epochNonce,
          context.eligibility.active,
          stakes,
          preview.preview.id
        )
      )
      eligible <- checked(
        "successor-eligibility",
        PraosEligibility.check(eligibilityContext, Vector(certificate))
      )
      _ <- Either.cond(
        !current.syntheticBoundary.exists(_.repeated) || deferComposition,
        (),
        Failure.Unsupported("repeated-boundary", "effectful checked preparation required")
      )
      plutusBinding <-
        if current.syntheticBoundary.exists(
            _.repeated
          ) && current.ledger.environment.plutus.nonEmpty
        then
          val component = current.syntheticBoundary.get
          val old = current.ledger.environment.plutus.get.parameters
          for
            parameters <- checked(
              "successor-current-parameters",
              lab.ledger.PlutusParameters.decode(
                component.roles.current.original,
                component.roles.current.sha256,
                old.modelText
              )
            )
            bound <- checked(
              "successor-plutus-binding",
              lab.ledger.PlutusSuccessorBinding.prepare(current.ledger, preview.preview, parameters)
            )
          yield Some(bound)
        else Right(None)
      pending <- ledger(plutusBinding match
        case Some(bound) =>
          Ledger.preparePlutusSuccessorBlock(
            current.ledger,
            bound,
            block.header.hash,
            block.transactionMemos,
            block.header.slot,
            Some(lab.vm.Pv9SubmissionEvaluator)
          )
        case None =>
          Ledger.prepareSyntheticSuccessorBlock(
            current.ledger,
            preview.preview.epoch,
            preview.preview.pots.fees,
            preview.preview.id,
            block.header.hash,
            block.transactionMemos,
            block.header.slot,
            Some(lab.vm.Pv9SubmissionEvaluator)
          ))
      binding <- current.stakeBinding.toRight(Failure.Rejected("successor-stake", "missing stake"))
      (stakeOwner, beforeStake) = binding
      stake <- checked(
        "successor-stake",
        plutusBinding match
          case Some(bound) =>
            Stake.preparePlutusSuccessor(
              stakeOwner,
              beforeStake,
              current.ledger,
              pending,
              preview.preview,
              bound
            )
          case None =>
            Stake.prepareSyntheticSuccessor(
              stakeOwner,
              beforeStake,
              current.ledger,
              pending,
              preview.preview
            )
      )
      selected <- checked("successor-stake", Stake.select(stakeOwner, beforeStake, stake))
      rewards <- afterBoundaryRewards(current, preview.preview, selected)
      boundary <- (if deferComposition then None else current.syntheticBoundary).traverse {
        before =>
          for
            applied <- checked(
              "synthetic-boundary",
              SyntheticBoundaryState.atBoundary(
                before,
                current.syntheticRewards.flatMap(_.frozen),
                preview.completedPulser,
                preview.preview,
                preview.completeEffect
              )
            )
            after <- checked(
              "synthetic-boundary",
              SyntheticBoundaryState.afterBoundaryFreeze(
                before,
                applied,
                rewards.flatMap(_.frozen),
                rewards.flatMap(_.pulser)
              )
            )
          yield after
      }
    yield new Candidate(
      owner,
      current,
      block,
      certificates,
      certificate,
      nonce,
      eligible,
      pending,
      Some(stake),
      rewards,
      Some(new SyntheticEpochContext(nextCertificates, nextNonces, stakes)),
      Some(
        new HistoricalBoundary(
          preview.preview,
          oldCertificates,
          oldNonces,
          current.ledger.environment.id
        )
      ),
      boundary,
      compositionPending = deferComposition
    )
  }

  private def countIdentity(counts: Map[Bytes, BigInt]): Bytes =
    digest("counts", counts.toVector.sortBy(_._1.hex).flatMap((p, n) => Vector(p, raw(n.toString))))

  private def rewardContext(current: State, rewards: SyntheticRewards): Result[Boundary.Context] =
    current.stakeBinding.toRight(Failure.Rejected("synthetic-rewards", "stake required")).flatMap {
      (owner, stake) =>
        checked(
          "synthetic-rewards",
          Boundary.context(
            rewards.owner,
            owner,
            current.id,
            stake,
            rewards.pots,
            rewards.previousBlocks,
            rewards.currentBlocks
          )
        )
    }

  private def prepareRewards(current: State, slot: BigInt): Result[Option[SyntheticRewards]] =
    current.rewardBinding.traverse { rewards =>
      for
        env <- rewardContext(current, rewards)
        timing <- checked(
          "synthetic-rewards",
          Boundary.rewardTiming(
            env.stake.epoch * env.stake.context.epochLength,
            rewards.profile.window,
            slot
          )
        )
        next <- rewards.pulser match
          case Some(p) =>
            val step =
              if timing == Boundary.Timing.ForceCompletion then Pulser.force(p, p.id, slot)
              else Pulser.pulse(p, p.id, slot)
            checked("synthetic-rewards", step).map(p => (rewards.frozen, Some(p)))
          case None if timing == Boundary.Timing.TooEarly => Right((None, None))
          case None =>
            for
              frozen <- checked(
                "synthetic-rewards",
                Boundary.freezeForAllocation(
                  rewards.owner,
                  env,
                  slot,
                  rewards.profile.window,
                  rewards.profile.parameters,
                  rewards.profile.globals
                )
              )
              allocation <- checked("synthetic-rewards", RewardStart.calculate(frozen, frozen.id))
              pools <- frozen.go.pools.keys.toVector.traverse { pool =>
                checked(
                  "synthetic-rewards",
                  PoolReward.calculate(frozen, frozen.id, allocation, allocation.id, pool)
                ).map(pool -> _)
              }
              pulser <- checked(
                "synthetic-rewards",
                Pulser.start(frozen, frozen.id, allocation, allocation.id, pools.toMap)
              )
            yield (Some(frozen), Some(pulser))
      yield new SyntheticRewards(
        rewards.owner,
        rewards.profile,
        rewards.pots,
        rewards.previousBlocks,
        rewards.currentBlocks,
        next._1,
        next._2,
        rewards.origin
      )
    }

  private def acceptRewards(
      current: State,
      proposed: Option[SyntheticRewards],
      block: Ledger.BlockApplied,
      issuer: Bytes
  ): Result[Option[SyntheticRewards]] =
    current.rewardBinding.traverse { before =>
      for
        next <- proposed.toRight(Failure.Rejected("synthetic-rewards", "missing owned progress"))
        _ <- Either.cond(
          (next.owner eq before.owner) && (next.profile eq before.profile),
          (),
          Failure.Rejected("synthetic-rewards", "foreign progress")
        )
        counts = next.currentBlocks.updated(
          issuer,
          next.currentBlocks.getOrElse(issuer, BigInt(0)) + 1
        )
        _ <- Either.cond(
          counts.size <= 4096 && counts.values.sum <= Ledger.MaxRevision,
          (),
          Failure.Unsupported("synthetic-rewards", "block count capacity")
        )
      yield new SyntheticRewards(
        next.owner,
        next.profile,
        next.pots.copy(fees = block.state.fees),
        next.previousBlocks,
        counts,
        next.frozen,
        next.pulser,
        next.origin
      )
    }

  private def successor(
      owner: AnyRef,
      current: State,
      fence: Fence,
      headerHash: Bytes,
      slot: BigInt
  ): Result[SyntheticSuccessor] = protect {
    for
      _ <- Either.cond(fence.owner eq owner, (), Failure.ForeignFence)
      _ <- Either.cond(
        fence.stateId == current.id && fence.revision == current.revision,
        (),
        Failure.StaleFence
      )
      rewards <- current.rewardBinding.toRight(
        Failure.Unsupported("synthetic-rewards", "profile not enabled")
      )
      _ <- Either.cond(
        !current.syntheticBoundary.exists(b => b.boundaryApplied && !b.repeated) &&
          !current.syntheticBoundary.exists(b => b.repeated && b.transitions >= b.repeatedLimit),
        (),
        Failure.Unsupported(
          "synthetic-boundary",
          "second boundary is outside the composition profile"
        )
      )
      env <- rewardContext(current, rewards)
      signal <- checked(
        "synthetic-successor",
        Boundary.signal(rewards.owner, env, headerHash, slot)
      )
      completed <- rewards.pulser.traverse(p =>
        checked("synthetic-successor", Pulser.completeAtBoundary(p, p.id, slot))
      )
      phase <- completed match
        case Some(p) =>
          checked(
            "synthetic-successor",
            Boundary.completeFromFrozen(rewards.owner, env, p.completion.get.completed)
          ).map(Boundary.RewardPhase.Completed(_))
        case None =>
          checked("synthetic-successor", Boundary.suppliedAbsent(rewards.owner, env, rewards.id))
            .map(Boundary.RewardPhase.Absent(_))
      preview <- checked("synthetic-successor", Boundary.preview(rewards.owner, env, signal, phase))
      effect = phase match
        case Boundary.RewardPhase.Completed(value) => Some(value)
        case _                                     => None
    yield new SyntheticSuccessor(
      owner,
      current,
      preview,
      completed,
      effect,
      digest(
        "synthetic-successor-v1",
        Vector(current.id, raw(current.revision.toString), preview.id) ++
          completed.toVector.map(_.id)
      )
    )
  }

  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def digest(domain: String, fields: Vector[Bytes]): Bytes =
    ClusterHeaderObservation.sha256(
      raw(fields.map(_.hex).mkString(ProfileId + "\n" + domain + "\n", "\n", "\n"))
    )
  private def point(p: Certificate.Point): ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.from(p.slot).toOption.get, p.hash)
  private def acquisitionIdentity(acquired: BoundedChainFollower.Checkpoint): Bytes =
    val anchor = acquired.anchor match
      case ChainSync.Point.Block(slot, hash) => raw(s"${slot.value}:${hash.hex}")
      case _                                 => raw("origin")
    digest(
      "original-acquisition-bytes",
      Vector(anchor) ++ acquired.originals.flatMap(o =>
        Vector(
          ClusterHeaderObservation.sha256(o.envelope),
          ClusterHeaderObservation.sha256(o.block)
        )
      )
    )
  private def checked[A](stage: String, result: Either[String, A]): Result[A] =
    result.left.map(Failure.Rejected(stage, _))
  private def ledger[A](result: Ledger.Checked[A]): Result[A] = result.left.map {
    case Ledger.Failure.Unsupported(reason) => Failure.Unsupported("ledger", reason)
    case other                              => Failure.LedgerRejected(other)
  }
  private def protect[A](body: => Result[A]): Result[A] =
    try body
    catch case NonFatal(e) => Left(Failure.Rejected("internal", e.getClass.getName))
  private def snapshot(owner: AnyRef, state: State): Snapshot =
    new Snapshot(state, new Fence(owner, state.id, state.revision))

  private def seed(
      context: SequenceInput.Context,
      allowDiagnostic: Boolean = false
  ): Result[State] = protect {
    for
      _ <- Either.cond(
        context != null && (!context.diagnosticOnly || allowDiagnostic),
        (),
        Failure.Rejected("seed", "diagnostic context requires private synthetic-boundary path")
      )
      _ <- Either.cond(
        context.nonces.seed.certificateStateId == context.certificateSeed.id &&
          context.nonces.seed.lastSlot == context.certificateSeed.tip.slot &&
          context.ledger.slot == context.certificateSeed.tip.slot && context.ledger.revision == 0,
        (),
        Failure.Rejected("seed", "complete supplied anchor binding required")
      )
      acquired <- checked(
        "acquisition",
        BoundedChainFollower.checked(point(context.certificateSeed.tip), Vector.empty)
      )
      certificates <- checked(
        "certificate",
        CertificateBranch.replay(context.certificates, context.certificateSeed, acquired)
      )
    yield new State(context.id, certificates, context.nonces.seed, None, context.ledger)
  }

  /** protocolDigest in the eligibility API receives derived attribution, not a literal export hash.
    */
  private def eligibilityAttribution(context: SequenceInput.Context, nonce: Nonces.Applied): Bytes =
    digest(
      "derived-eligibility-attribution-v1",
      Vector(
        context.protocolAttributionDigest,
        context.nonces.context.id,
        nonce.before.id,
        nonce.after.id,
        nonce.headerHash
      )
    )

  private def prepare(
      owner: AnyRef,
      context: SequenceInput.Context,
      maxBlocks: Int,
      current: State,
      block: SequenceInput.Block,
      synthetic: Boolean = false,
      deferComposition: Boolean = false
  ): Result[Candidate] = protect {
    val certContext =
      if synthetic then current.epochBinding.fold(context.certificates)(_.certificates)
      else context.certificates
    val nonceContext =
      if synthetic then current.epochBinding.fold(context.nonces.context)(_.nonces)
      else context.nonces.context
    val epoch = if synthetic then current.ledger.environment.epoch else context.epoch
    val stakes =
      if synthetic then current.epochBinding.fold(context.eligibility.stakes)(_.stakes)
      else context.eligibility.stakes
    for
      _ <- Either.cond(
        !current.syntheticBoundary.exists(_.repeated) || deferComposition,
        (),
        Failure.Unsupported("repeated-boundary", "effectful checked preparation required")
      )
      _ <- Either.cond(
        block.header.slot / nonceContext.epochLength == epoch,
        (),
        Failure.Unsupported("epoch", "same supplied epoch only")
      )
      _ <- Either.cond(
        current.acquisition.size < maxBlocks,
        (),
        Failure.Unsupported("window", "retained block capacity reached; explicit rollback required")
      )
      certificates <- checked(
        "certificate",
        CertificateBranch.append(certContext, current.certificates, block.original)
      )
      certificate = certificates.steps.last
      nonce <- checked(
        "nonce",
        Nonces.applyHeader(nonceContext, current.nonces, certificate)
      )
      epochNonce = nonce.epochNonceUsed match
        case Nonces.Nonce.Neutral     => Vrf.NeutralNonce
        case Nonces.Nonce.Hash(bytes) => Vrf.Hash32.fromBytes(bytes).toOption.get
      eligibilityContext <- checked(
        "eligibility-context",
        PraosEligibility.Context.checked(
          certContext,
          current.certificates.state,
          epoch,
          nonceContext.epochLength,
          epochNonce,
          context.eligibility.active,
          stakes,
          eligibilityAttribution(context, nonce)
        )
      )
      eligible <- checked(
        "eligibility",
        PraosEligibility.check(eligibilityContext, Vector(certificate))
      )
      pending <- ledger(
        Ledger.prepareBlock(
          current.ledger,
          block.header.hash,
          block.transactionMemos,
          block.header.slot,
          Some(lab.vm.Pv9SubmissionEvaluator)
        )
      )
      stake <- current.stakeBinding.traverse { (stakeOwner, state) =>
        checked("stake", Stake.prepare(stakeOwner, state, current.ledger, pending))
      }
      rewards <- prepareRewards(current, block.header.slot)
      boundary <- (if deferComposition then None else current.syntheticBoundary).traverse(b =>
        checked(
          "synthetic-boundary-freeze",
          SyntheticBoundaryState.advanceFreeze(
            b,
            rewards.flatMap(_.frozen),
            rewards.flatMap(_.pulser)
          )
        )
      )
    yield new Candidate(
      owner,
      current,
      block,
      certificates,
      certificate,
      nonce,
      eligible,
      pending,
      stake,
      rewards,
      current.epochBinding,
      syntheticBoundary = boundary,
      compositionPending = deferComposition
    )
  }
  private def bindings(current: State, c: Candidate): Boolean =
    c.certificates.acquisition.originals == (current.acquisition.originals :+ c.block.original) &&
      c.certificates.acquisition.anchor == current.acquisition.anchor &&
      c.certificate.before.id == current.certificates.state.id &&
      c.certificate.after.id == c.certificates.state.id &&
      c.nonce.before.id == current.nonces.id &&
      c.nonce.before.certificateStateId == current.certificates.state.id &&
      c.nonce.after.certificateStateId == c.certificate.after.id &&
      c.nonce.before.contextId == current.nonces.contextId && c.nonce.after.contextId == c.epochBinding
        .fold(current.nonces.contextId)(_.nonces.id) &&
      c.certificate.after.tip.hash == c.block.header.hash && c.certificate.after.tip.slot == c.block.header.slot &&
      c.nonce.headerHash == c.block.header.hash && c.nonce.after.lastSlot == c.block.header.slot &&
      c.certificate.observation.originalHeaderHash == c.block.header.hash &&
      c.ledger.headerHash == c.block.header.hash && c.ledger.slot == c.block.header.slot &&
      c.ledger.transactionMemos == c.block.transactionMemos &&
      c.eligibility.headers.map(_.headerHash) == Vector(c.block.header.hash)
  private def publish(
      owner: AnyRef,
      maxBlocks: Int,
      cell: Cell,
      candidate: Candidate
  ): Result[(Cell, Applied)] = protect {
    val current = cell.state
    if candidate.compositionPending then
      Left(Failure.Rejected("composition", "unfinished checked preparation"))
    else if !(candidate.owner eq owner) then Left(Failure.ForeignCandidate)
    else if current.id != candidate.before.id || current.revision != candidate.before.revision ||
      cell.receipts.size >= maxBlocks || !bindings(current, candidate)
    then Left(Failure.StaleCandidate)
    else
      for
        block <- ledger(Ledger.commitBlock(current.ledger, candidate.ledger))
        stake <- current.stakeBinding.traverse { (stakeOwner, before) =>
          for
            proposed <- candidate.stake.toRight(Failure.Rejected("stake", "missing candidate"))
            after <- checked("stake", Stake.select(stakeOwner, before, proposed))
            _ <- Either.cond(
              after.ledgerId == block.state.id && after.revision == block.state.revision,
              (),
              Failure.Rejected("stake", "ledger publication mismatch")
            )
          yield (stakeOwner, after)
        }
        rewards <- acceptRewards(current, candidate.rewards, block, candidate.certificate.issuer)
      yield
        val state = new State(
          current.contextId,
          candidate.certificates,
          candidate.nonce.after,
          Some(candidate.eligibility),
          block.state,
          current.compactedBlocks,
          current.derivedAnchorId,
          current.trustedLocalPrefix,
          stake,
          rewards,
          candidate.epochBinding,
          candidate.transitionBoundary.orElse(current.boundaryOrigin),
          candidate.syntheticBoundary
        )
        val owned =
          OwnedReceipt(
            current,
            state.id,
            candidate.certificate,
            candidate.nonce,
            block.undo,
            historicalPosition(current),
            state.id,
            candidate.transitionBoundary
          )
        (
          Cell(state, cell.receipts :+ owned),
          new Applied(state, block, candidate.certificate, candidate.nonce)
        )
  }

  private def restore(current: State, owned: OwnedReceipt): Result[State] = protect {
    for
      _ <- Either.cond(
        current.id == owned.afterId && current.nonces.id == owned.nonce.after.id &&
          current.certificates.state.id == owned.certificate.after.id,
        (),
        Failure.Rejected("rollback", "owned receipt does not match current tuple")
      )
      certificates <- checked(
        "certificate-undo",
        CertificateBranch.rollback(current.certificates, owned.before.acquisition.tip)
      )
      nonces <- checked("nonce-undo", Nonces.undo(current.nonces, owned.nonce))
      restoredLedger <- ledger(Ledger.undo(current.ledger, current.revision, owned.ledger))
      stake <- owned.before.stakeBinding.traverse { (stakeOwner, before) =>
        checked("stake-undo", Stake.rebindAfterUndo(stakeOwner, before, restoredLedger))
          .map(stakeOwner -> _)
      }
      restored = new State(
        current.contextId,
        certificates,
        nonces,
        owned.before.eligibility,
        restoredLedger,
        current.compactedBlocks,
        current.derivedAnchorId,
        current.trustedLocalPrefix,
        stake,
        owned.before.rewardBinding,
        owned.before.epochBinding,
        owned.before.boundaryOrigin,
        owned.before.syntheticBoundary
      )
      _ <- Either.cond(
        restored.id == owned.before.id && nonces.certificateStateId == certificates.state.id,
        (),
        Failure.Rejected("rollback", "whole tuple restoration mismatch")
      )
    yield restored
  }
  private def rollback(
      owner: AnyRef,
      cell: Cell,
      fence: Fence,
      target: ChainSync.Point
  ): Result[(Cell, Snapshot)] = protect {
    val current = cell.state
    if !(fence.owner eq owner) then Left(Failure.ForeignFence)
    else if fence.stateId != current.id || fence.revision != current.revision then
      Left(Failure.StaleFence)
    else if target == current.acquisition.tip then Right((cell, snapshot(owner, current)))
    else
      val keep =
        if target == current.acquisition.anchor then 0
        else cell.receipts.indexWhere(r => point(r.certificate.after.tip) == target) + 1
      if keep == 0 && target != current.acquisition.anchor then Left(Failure.OutsideRetainedWindow)
      else
        val undoCount = cell.receipts.size - keep
        if current.revision + undoCount > Ledger.MaxRevision then Left(Failure.RevisionExhausted)
        else
          cell.receipts
            .drop(keep)
            .reverse
            .foldLeft[Result[State]](Right(current)) { (state, receipt) =>
              state.flatMap(restore(_, receipt))
            }
            .map { state =>
              (Cell(state, cell.receipts.take(keep)), snapshot(owner, state))
            }
  }

  /** Rebind every retained receipt, without retaining a reference to discarded history. The
    * provenance digest commits to the already checked boundary tuple and its prior provenance.
    * Compaction does not change the ledger revision; changed state IDs invalidate old fences.
    */
  private def advanceAnchor(
      owner: AnyRef,
      cell: Cell,
      fence: Fence,
      through: ChainSync.Point
  ): Result[(Cell, Snapshot)] = protect {
    val current = cell.state
    if !(fence.owner eq owner) then Left(Failure.ForeignFence)
    else if fence.stateId != current.id || fence.revision != current.revision then
      Left(Failure.StaleFence)
    else if through == current.acquisition.anchor then Right((cell, snapshot(owner, current)))
    else
      val drop = cell.receipts.indexWhere(r => point(r.certificate.after.tip) == through) + 1
      if drop == 0 then Left(Failure.OutsideRetainedWindow)
      else
        val count = current.compactedBlocks + drop
        if count > Ledger.MaxRevision then Left(Failure.RevisionExhausted)
        else
          val boundary = if drop == cell.receipts.size then current else cell.receipts(drop).before
          val provenance =
            digest("derived-window-anchor-v1", Vector(boundary.id, raw(count.toString)))
          def rebase(state: State): Result[State] =
            checked(
              "certificate-anchor",
              CertificateBranch.advanceAnchor(state.certificates, through)
            )
              .map(branch =>
                new State(
                  state.contextId,
                  branch,
                  state.nonces,
                  state.eligibility,
                  state.ledger,
                  count,
                  Some(provenance),
                  state.trustedLocalPrefix,
                  state.stakeBinding,
                  state.rewardBinding,
                  state.epochBinding,
                  state.boundaryOrigin,
                  state.syntheticBoundary
                )
              )
          for
            tip <- rebase(current)
            retained <- cell.receipts.drop(drop).traverse(r => rebase(r.before).map(r -> _))
          yield
            val receipts = retained.zipWithIndex.map { case ((old, before), i) =>
              val after = retained.lift(i + 1).fold(tip)(_._2)
              OwnedReceipt(
                before,
                after.id,
                old.certificate,
                old.nonce,
                old.ledger,
                old.position,
                old.historicalAfterId,
                old.boundary
              )
            }
            (Cell(tip, receipts), snapshot(owner, tip))
  }

  /** Only a single owned-cell read creates this export capability. */
  private[lab] final class OwnedLocalExport private[CoherentSequence] (
      val context: SequenceInput.Context,
      val anchor: State,
      val current: State,
      val capacity: Int
  )

  /** Private proposed cell; only its original runtime can export/install it. */
  private[lab] final class LocalPlan private[CoherentSequence] (
      private[CoherentSequence] val owner: AnyRef,
      private[CoherentSequence] val before: Cell,
      private[CoherentSequence] val next: Cell,
      val snapshot: Snapshot
  ):
    val changed: Boolean = !(before eq next)

  final class Runtime[F[_]] private[CoherentSequence] (
      private[CoherentSequence] val context: SequenceInput.Context,
      val maxBlocks: Int,
      private[CoherentSequence] val owner: AnyRef,
      private[CoherentSequence] val cell: Ref[F, Cell]
  )(using F: Sync[F])
      extends CoherentDriver[F]:
    private def localPlan(transition: Cell => Result[Cell]): F[Result[LocalPlan]] =
      cell.get.flatMap(before =>
        F.delay(protect(transition(before)))
          .map(
            _.map(next =>
              new LocalPlan(owner, before, next, CoherentSequence.snapshot(owner, next.state))
            )
          )
      )
    private[lab] def planLocalPublish(candidate: Candidate): F[Result[LocalPlan]] =
      localPlan(before => CoherentSequence.publish(owner, maxBlocks, before, candidate).map(_._1))
    private[lab] def planLocalRollback(
        fence: Fence,
        target: ChainSync.Point
    ): F[Result[LocalPlan]] =
      localPlan(before => CoherentSequence.rollback(owner, before, fence, target).map(_._1))
    private[lab] def planLocalAnchor(fence: Fence, through: ChainSync.Point): F[Result[LocalPlan]] =
      localPlan(before => CoherentSequence.advanceAnchor(owner, before, fence, through).map(_._1))
    private[lab] def exportLocalPlan(
        plan: LocalPlan,
        storeId: Bytes,
        sessionId: Bytes,
        generation: Long
    ): F[Either[String, LocalDerivedCheckpoint.Publication]] = cell.get.flatMap { current =>
      if plan.next.state.syntheticBoundary.isDefined then
        F.pure(Left("one-boundary composition has no checkpoint codec"))
      else if (plan.owner ne owner) || (plan.before ne current) then
        F.pure(Left("foreign or stale local plan"))
      else
        F.delay {
          val anchor = plan.next.receipts.headOption.fold(plan.next.state)(_.before)
          LocalDerivedCheckpoint.encodeOwned(
            new OwnedLocalExport(context, anchor, plan.next.state, maxBlocks),
            storeId,
            sessionId,
            generation
          )
        }
    }
    private[lab] def installLocalPlan(plan: LocalPlan): F[Unit] = cell
      .modify { current =>
        if (plan.owner ne owner) || (plan.before ne current) then
          (current, Left(new IllegalStateException("foreign or stale local plan")))
        else (plan.next, Right(()))
      }
      .flatMap(F.fromEither)

    /** Pure image export; no NIO publication, controller acceptance, or durability is implied. */
    def exportLocalCheckpoint(
        storeId: Bytes,
        sessionId: Bytes,
        generation: Long
    ): F[Either[String, LocalDerivedCheckpoint.Publication]] = cell.get.flatMap { current =>
      val anchor = current.receipts.headOption.fold(current.state)(_.before)
      if current.state.syntheticBoundary.isDefined then
        F.pure(Left("one-boundary composition has no checkpoint codec"))
      else
        F.delay(
          LocalDerivedCheckpoint.encodeOwned(
            new OwnedLocalExport(context, anchor, current.state, maxBlocks),
            storeId,
            sessionId,
            generation
          )
        )
    }
    def snapshot: F[Snapshot] = cell.get.map(c => CoherentSequence.snapshot(owner, c.state))

    private[lab] def exportSyntheticRecovery(
        publicationId: Bytes
    ): F[Either[String, SyntheticRecoveryModel.Envelope]] = cell.get.map { c =>
      val states = c.receipts.map(_.before) :+ c.state
      if states.exists(_.syntheticBoundary.isDefined) then
        Left("one-boundary composition has no recovery model")
      else
        val records = c.receipts.zip(c.state.acquisition.originals).map { (r, original) =>
          RecoveryRecord(original, r.position, r.historicalAfterId, r.boundary)
        }
        SyntheticRecoveryModel.prepare(
          RecoveryImage(context, maxBlocks, states, records),
          publicationId
        )
    }

    /** Internal synthetic profile only; ordinary prepare and the CLI keep their epoch guards. */
    private[lab] def prepareSyntheticSuccessorBlock(
        fence: Fence,
        preview: SyntheticSuccessor,
        block: SequenceInput.Block
    ): F[Result[Candidate]] = cell.get.flatMap(c =>
      F.delay(prepareSuccessor(owner, context, maxBlocks, c.state, fence, preview, block))
    )
    private[lab] def prepareSyntheticBlock(
        fence: Fence,
        block: SequenceInput.Block
    ): F[Result[Candidate]] =
      cell.get.flatMap(c =>
        F.delay(protect {
          if fence.owner ne owner then Left(Failure.ForeignFence)
          else if fence.stateId != c.state.id || fence.revision != c.state.revision then
            Left(Failure.StaleFence)
          else if c.state.rewardBinding.isEmpty then
            Left(Failure.Unsupported("synthetic-rewards", "profile not enabled"))
          else CoherentSequence.prepare(owner, context, maxBlocks, c.state, block, synthetic = true)
        })
      )

    /** The oracle executes outside cell.modify. Drafts cannot be published. A stale reply is
      * discarded against the exact captured Cell, followed by the normal publication CAS.
      */
    private[lab] def prepareRepeatedBlock(
        fence: Fence,
        block: SequenceInput.Block,
        successorPreview: Option[SyntheticSuccessor],
        compare: (Boundary.Frozen, Bytes) => F[lab.ledger.ConwayNativeLikelihood.Generated]
    ): F[Result[Candidate]] = cell.get.flatMap { captured =>
      val current = captured.state
      val draft = protect {
        if fence.owner ne owner then Left(Failure.ForeignFence)
        else if fence.stateId != current.id || fence.revision != current.revision then
          Left(Failure.StaleFence)
        else if !current.syntheticBoundary.exists(_.repeated) then
          Left(Failure.Unsupported("repeated-boundary", "explicit profile required"))
        else
          successorPreview match
            case Some(preview) =>
              prepareSuccessor(
                owner,
                context,
                maxBlocks,
                current,
                fence,
                preview,
                block,
                deferComposition = true
              )
            case None =>
              CoherentSequence.prepare(
                owner,
                context,
                maxBlocks,
                current,
                block,
                synthetic = true,
                deferComposition = true
              )
      }
      draft match
        case Left(error) => F.pure(Left(error))
        case Right(pending) =>
          val before = current.syntheticBoundary.get
          val base = successorPreview match
            case None => Right(before)
            case Some(preview) =>
              checked(
                "repeated-boundary",
                SyntheticBoundaryState.atBoundary(
                  before,
                  current.syntheticRewards.flatMap(_.frozen),
                  preview.completedPulser,
                  preview.preview,
                  preview.completeEffect
                )
              )
          base match
            case Left(error) => F.pure(Left(error))
            case Right(component) =>
              val frozen = pending.rewards.flatMap(_.frozen)
              val attach: F[Result[SyntheticBoundaryState.State]] = frozen match
                case Some(f) if component.checkedLikelihood.isEmpty =>
                  compare(f, f.id).attempt.map {
                    case Left(error) =>
                      Left(Failure.Rejected("native-comparison", error.getClass.getName))
                    case Right(generated) =>
                      checked(
                        "native-comparison",
                        SyntheticBoundaryState.attachLikelihood(component, generated)
                      )
                  }
                case _ => F.pure(Right(component))
              attach.flatMap { attached =>
                F.delay(attached.flatMap { ready =>
                  val phase = successorPreview match
                    case None =>
                      SyntheticBoundaryState
                        .advanceFreeze(ready, frozen, pending.rewards.flatMap(_.pulser))
                    case Some(_) =>
                      SyntheticBoundaryState.afterBoundaryFreeze(
                        before,
                        ready,
                        frozen,
                        pending.rewards.flatMap(_.pulser)
                      )
                  checked("repeated-freeze", phase).map { completed =>
                    new Candidate(
                      owner,
                      current,
                      pending.block,
                      pending.certificates,
                      pending.certificate,
                      pending.nonce,
                      pending.eligibility,
                      pending.ledger,
                      pending.stake,
                      pending.rewards,
                      pending.epochBinding,
                      pending.transitionBoundary,
                      Some(completed)
                    )
                  }
                }).flatMap { result =>
                  cell.get
                    .map(now => if now ne captured then Left(Failure.StaleCandidate) else result)
                }
              }
    }

    /** Pure successor subrules only. No header, nonce, ledger or epoch publication path exists. */
    def prepareSyntheticSuccessor(
        fence: Fence,
        headerHash: Bytes,
        slot: BigInt
    ): F[Result[SyntheticSuccessor]] =
      cell.get.flatMap(c => F.delay(successor(owner, c.state, fence, headerHash, slot)))
    def checkSyntheticSuccessor(candidate: SyntheticSuccessor): F[Result[Unit]] = cell.get.map {
      c =>
        if candidate == null then
          Left(Failure.Rejected("synthetic-successor", "candidate required"))
        else if candidate.owner ne owner then Left(Failure.ForeignCandidate)
        else if candidate.before.id != c.state.id || candidate.before.revision != c.state.revision
        then Left(Failure.StaleCandidate)
        else Right(())
    }

    /** Explicit availability policy: rollback before `through` becomes unavailable. This is not
      * finality. Existing durable checkpoint v1 deliberately cannot serialize this state.
      */
    def advanceAnchor(fence: Fence, through: ChainSync.Point): F[Result[Snapshot]] = cell.modify {
      current =>
        CoherentSequence.advanceAnchor(owner, current, fence, through) match
          case Right((next, result)) => (next, Right(result))
          case Left(error)           => (current, Left(error))
    }
    def prepare(block: SequenceInput.Block): F[Result[Candidate]] =
      cell.get.flatMap(c =>
        F.delay(CoherentSequence.prepare(owner, context, maxBlocks, c.state, block))
      )
    def publish(candidate: Candidate): F[Result[Applied]] = cell.modify { current =>
      CoherentSequence.publish(owner, maxBlocks, current, candidate) match
        case Right((next, receipt)) => (next, Right(receipt))
        case Left(error)            => (current, Left(error))
    }
    def rollbackTo(fence: Fence, target: ChainSync.Point): F[Result[Snapshot]] = cell.modify {
      current =>
        CoherentSequence.rollback(owner, current, fence, target) match
          case Right((next, result)) => (next, Right(result))
          case Left(error)           => (current, Left(error))
    }

  enum DurablePhase:
    case Publishing, Recorded, BeforeDisk, AfterDisk, BeforeMemory, AfterMemory,
      BeforeAcknowledgement, AcknowledgementPrepared
  final case class PendingTokens(
      previous: Option[ValidatedCheckpoint.Token],
      next: ValidatedCheckpoint.Token
  )
  final case class DurableSnapshot(snapshot: Snapshot, token: ValidatedCheckpoint.Token)
  final case class Acknowledged[A](value: A, token: ValidatedCheckpoint.Token)
  final class DurableFailure(message: String) extends RuntimeException(message)
  private enum Health:
    case Active, Publishing, Poisoned, Closed
  private final case class Session(health: Health, token: Option[ValidatedCheckpoint.Token])

  /** The facade is the only mutation interface exported by durable factories. */
  final class DurableRuntime[F[_]] private[CoherentSequence] (
      runtime: Runtime[F],
      disk: NioValidatedCheckpointStore.Disk,
      session: Ref[F, Session],
      gate: cats.effect.std.Semaphore[F],
      record: PendingTokens => F[Unit],
      recorderDeadline: scala.concurrent.duration.FiniteDuration,
      observe: DurablePhase => F[Unit]
  )(using F: Async[F]):
    private[lab] val capacity: Int = runtime.maxBlocks
    import cats.effect.syntax.all.*
    private def active: F[Session] = session.get.flatMap { current =>
      F.raiseUnless(current.health == Health.Active)(
        new DurableFailure("durable runtime closed or poisoned")
      ).as(current)
    }
    private def poison: F[Unit] = session.update(_.copy(health = Health.Poisoned))
    private def encoded(
        state: State,
        id: Bytes,
        generation: Long
    ): F[(Bytes, ValidatedCheckpoint.Token)] =
      F.fromEither(
        ValidatedCheckpoint
          .encode(
            runtime.context,
            CoherentSequence.snapshot(runtime.owner, state),
            id,
            generation,
            runtime.maxBlocks
          )
          .left
          .map(new DurableFailure(_))
      )
    private def runRecorder(tokens: PendingTokens): F[Unit] = F.uncancelable { poll =>
      F.start(F.defer(record(tokens))).flatMap { fiber =>
        // Never wait for an uncooperative recorder's cancellation while holding the owner lock.
        poll(fiber.join.timeout(recorderDeadline))
          .guaranteeCase {
            case cats.effect.Outcome.Succeeded(_) => F.unit
            case _                                => F.start(fiber.cancel).void
          }
          .flatMap {
            case cats.effect.Outcome.Succeeded(result) => result
            case cats.effect.Outcome.Errored(error)    => F.raiseError(error)
            case cats.effect.Outcome.Canceled() =>
              F.raiseError(new DurableFailure("recorder canceled"))
          }
      }
    }
    private def install[A](
        previous: Option[ValidatedCheckpoint.Token],
        next: Cell,
        image: (Bytes, ValidatedCheckpoint.Token),
        result: A
    ): F[Acknowledged[A]] =
      val (raw, token) = image
      F.uncancelable { poll =>
        val publication =
          session.update(_.copy(health = Health.Publishing)) *>
            observe(DurablePhase.Publishing) *>
            // The recorder runs cancelably with a deadline BEFORE any filesystem mutation.
            poll(runRecorder(PendingTokens(previous, token))) *>
            observe(DurablePhase.Recorded) *> observe(DurablePhase.BeforeDisk) *>
            F.blocking(disk.install(previous, raw, token)) *> observe(DurablePhase.AfterDisk) *>
            observe(DurablePhase.BeforeMemory) *> runtime.cell.set(next) *>
            observe(DurablePhase.AfterMemory) *> observe(DurablePhase.BeforeAcknowledgement) *>
            F.delay(Acknowledged(result, token)).flatMap { acknowledgement =>
              observe(DurablePhase.AcknowledgementPrepared) *>
                session.set(Session(Health.Active, Some(token))).as(acknowledgement)
            }
        publication.onCancel(poison).handleErrorWith(error => poison *> F.raiseError(error))
      }
    private[CoherentSequence] def initialize: F[Unit] = gate.permit.use { _ =>
      for
        current <- runtime.cell.get
        id <- F.delay {
          val bytes = new Array[Byte](32)
          new java.security.SecureRandom().nextBytes(bytes)
          Bytes.fromArray(bytes)
        }
        image <- encoded(current.state, id, 0)
        _ <- install(None, current, image, ())
      yield ()
    }
    def snapshot: F[DurableSnapshot] = gate.permit.use { _ =>
      active.flatMap(s => runtime.snapshot.map(value => DurableSnapshot(value, s.token.get)))
    }
    def prepare(block: SequenceInput.Block): F[Result[Candidate]] = gate.permit.use { _ =>
      active *> runtime.prepare(block)
    }
    private def mutate[A](
        expected: ValidatedCheckpoint.Token
    )(transition: Cell => Result[(Cell, A)]): F[Result[Acknowledged[A]]] = gate.permit.use { _ =>
      active.flatMap { status =>
        if status.token != Some(expected) then
          F.pure(Left(Failure.Rejected("publication", "stale expected token")))
        else
          runtime.cell.get.flatMap { before =>
            F.delay(protect(transition(before))).flatMap {
              case Left(error) => F.pure(Left(error))
              case Right((next, result)) if next eq before =>
                F.pure(Right(Acknowledged(result, expected)))
              case Right((next, result)) =>
                if expected.generation == Long.MaxValue then
                  F.pure(Left(Failure.Rejected("publication", "generation exhausted")))
                else
                  encoded(next.state, expected.storeId, expected.generation + 1)
                    .flatMap(image => install(Some(expected), next, image, result))
                    .map(Right(_))
            }
          }
      }
    }
    def publish(
        candidate: Candidate,
        expected: ValidatedCheckpoint.Token
    ): F[Result[Acknowledged[Applied]]] =
      mutate(expected)(before =>
        CoherentSequence.publish(runtime.owner, runtime.maxBlocks, before, candidate)
      )
    def rollbackTo(
        fence: Fence,
        target: ChainSync.Point,
        expected: ValidatedCheckpoint.Token
    ): F[Result[Acknowledged[Snapshot]]] =
      mutate(expected)(before => CoherentSequence.rollback(runtime.owner, before, fence, target))

    /** Close shares the mutation gate; FileLock is released only after publication finishes. */
    def close: F[Unit] = F.uncancelable { _ =>
      gate.permit.use { _ =>
        session.update(_.copy(health = Health.Closed)) *> F.blocking(disk.close())
      }
    }

  private[lab] def durableResource[F[_]: Async](
      root: java.nio.file.Path,
      context: Option[SequenceInput.Context],
      expectedContext: Bytes,
      expected: Option[ValidatedCheckpoint.Token],
      capacity: Int,
      recoveryDeadline: scala.concurrent.duration.FiniteDuration,
      recorderDeadline: scala.concurrent.duration.FiniteDuration,
      record: PendingTokens => F[Unit],
      faults: NioValidatedCheckpointStore.Faults,
      observe: DurablePhase => F[Unit]
  ): cats.effect.Resource[F, DurableRuntime[F]] =
    val F = Async[F]
    def fromResult[A](value: Result[A]): F[A] =
      F.fromEither(value.left.map(e => new DurableFailure(e.toString)))
    val mode =
      if expected.isEmpty then NioValidatedCheckpointStore.Mode.Create
      else NioValidatedCheckpointStore.Mode.Resume
    cats.effect.Resource.eval(
      F.raiseWhen(context.exists(_.diagnosticOnly))(
        new DurableFailure("diagnostic context has no durable admission")
      ) *> F.raiseUnless(
        recorderDeadline.length > 0 && recorderDeadline <= scala.concurrent.duration
          .Duration(30, "seconds") && recoveryDeadline.length > 0
      )(new DurableFailure("positive recorder/recovery deadline required"))
    ) *>
      NioValidatedCheckpointStore.resource[F](root, mode, faults).flatMap { disk =>
        val build = for
          runtime <- expected match
            case None =>
              context match
                case Some(c) if c.id == expectedContext =>
                  create[F](c, capacity).flatMap(fromResult)
                case _ => F.raiseError[Runtime[F]](new DurableFailure("create context required"))
            case Some(token) =>
              F.blocking(disk.read(expectedContext, token))
                .flatMap { raw =>
                  ValidatedCheckpoint
                    .recover[F](raw, expectedContext, token, recoveryDeadline)
                    .flatMap(result => F.fromEither(result.left.map(new DurableFailure(_))))
                }
                .flatTap(_ => F.blocking(disk.discardStagingAfterRecovery()))
          status <- Ref.of[F, Session](Session(Health.Active, expected))
          gate <- cats.effect.std.Semaphore[F](1)
          facade = new DurableRuntime(
            runtime,
            disk,
            status,
            gate,
            record,
            recorderDeadline,
            observe
          )
          _ <- if expected.isEmpty then facade.initialize else F.unit
        yield facade
        cats.effect.Resource.makeFull[F, DurableRuntime[F]](poll => poll(build))(_.close)
      }

  def durableCreate[F[_]: Async](
      root: java.nio.file.Path,
      context: SequenceInput.Context,
      capacity: Int,
      recorderDeadline: scala.concurrent.duration.FiniteDuration,
      record: PendingTokens => F[Unit]
  ): cats.effect.Resource[F, DurableRuntime[F]] =
    durableResource(
      root,
      Some(context),
      context.id,
      None,
      capacity,
      recorderDeadline,
      recorderDeadline,
      record,
      NioValidatedCheckpointStore.NoFaults,
      _ => Async[F].unit
    )

  def durableResume[F[_]: Async](
      root: java.nio.file.Path,
      expectedContext: Bytes,
      expected: ValidatedCheckpoint.Token,
      recoveryDeadline: scala.concurrent.duration.FiniteDuration,
      recorderDeadline: scala.concurrent.duration.FiniteDuration,
      record: PendingTokens => F[Unit]
  ): cats.effect.Resource[F, DurableRuntime[F]] =
    durableResource(
      root,
      None,
      expectedContext,
      Some(expected),
      MaxBlocks,
      recoveryDeadline,
      recorderDeadline,
      record,
      NioValidatedCheckpointStore.NoFaults,
      _ => Async[F].unit
    )

  /** Internal full replay. No partially replayed runtime or foreign capabilities escape. */
  private[lab] def recover[F[_]: Async](
      context: SequenceInput.Context,
      maxBlocks: Int,
      revision: BigInt,
      originals: Vector[BoundedChainFollower.Original],
      expectedId: Bytes,
      between: String => F[Unit]
  ): F[Result[Runtime[F]]] =
    val F = Async[F]
    def stage[A](body: => Result[A]): F[Result[A]] =
      F.cede *> between("checked-stage") *> F.delay(protect(body))
    stage {
      for
        _ <- Either.cond(
          maxBlocks >= 1 && maxBlocks <= MaxBlocks && originals.size <= maxBlocks &&
            revision >= originals.size && revision <= Ledger.MaxRevision &&
            (revision - originals.size) % 2 == 0,
          (),
          Failure.Rejected("recovery", "bounds/revision parity")
        )
        initial <- seed(context) // Must validate the NORMAL revision-zero seed first.
        anchor <- ledger(Ledger.recoveryAnchor(initial.ledger, revision - originals.size))
      yield new State(initial.contextId, initial.certificates, initial.nonces, None, anchor)
    }.flatMap {
      case Left(error) => F.pure(Left(error))
      case Right(initial) =>
        for
          owner <- F.delay(new Object())
          cell <- Ref.of[F, Cell](Cell(initial, Vector.empty))
          runtime = new Runtime(context, maxBlocks, owner, cell)
          replayed <- originals.foldLeft(F.pure[Result[Unit]](Right(()))) { (acc, original) =>
            acc.flatMap {
              case Left(error) => F.pure(Left(error))
              case Right(_) =>
                stage(
                  SequenceInput.block(original).left.map(e => Failure.Rejected("input", e.toString))
                ).flatMap {
                  case Left(error) => F.pure(Left(error))
                  case Right(block) =>
                    (F.cede *> between("prepare") *> runtime.prepare(block)).flatMap {
                      case Left(error) => F.pure(Left(error))
                      case Right(candidate) =>
                        F.cede *> between("publish") *> runtime
                          .publish(candidate)
                          .map(_.map(_ => ()))
                    }
                }
            }
          }
          result <- replayed match
            case Left(error) => F.pure[Result[Runtime[F]]](Left(error))
            case Right(_) =>
              F.cede *> between("verify") *> runtime.snapshot.map { saved =>
                Either.cond(
                  saved.state.id == expectedId && saved.state.revision == revision,
                  runtime,
                  Failure.Rejected("recovery", "replayed tuple/revision mismatch")
                )
              }
        yield result
    }

  /** The input capability exists only after explicit external controller acceptance. No partial
    * runtime escapes. Trusted anchor hydration is followed by ordinary checked suffix replay.
    */
  private[lab] def trustedRestoreLocal[F[_]: Async](
      context: SequenceInput.Context,
      authorized: LocalDerivedCheckpoint.AuthorizedLocalImage,
      between: String => F[Unit]
  ): F[Result[Runtime[F]]] =
    val F = Async[F]
    val i = authorized.image
    def stage[A](label: String)(body: => Result[A]): F[Result[A]] =
      F.cede *> between(label) *> F.delay(protect(body))
    stage("trusted-anchor") {
      val a = i.anchor
      for
        supplied <- seed(context)
        _ <- Either.cond(
          context.id == i.contextId && i.compactedBlocks > 0 &&
            i.originals.size <= i.capacity && i.capacity >= 1 && i.capacity <= MaxBlocks &&
            i.revision <= Ledger.MaxRevision && i.compactedBlocks + i.originals.size <= i.revision &&
            (i.revision - i.compactedBlocks - i.originals.size) % 2 == 0 &&
            i.provenance.size == 32 &&
            a.certificate.tip.blockNo == context.certificateSeed.tip.blockNo + i.compactedBlocks &&
            a.certificate.tip.slot > context.certificateSeed.tip.slot &&
            a.certificate.tip.slot / context.nonces.context.epochLength == context.epoch,
          (),
          Failure.Rejected("local-anchor", "context/depth/revision/point binding")
        )
        cert <- checked(
          "local-certificate",
          Certificate.trustedRestoreLocal(context.certificates, a.certificate)
        )
        nonce <- checked(
          "local-nonce",
          Nonces.trustedRestoreLocal(context.nonces.context, cert, a.nonce)
        )
        eligible <- checked(
          "local-eligibility",
          PraosEligibility.trustedRestoreLocal(
            a.eligibility,
            cert.tip.hash,
            context.eligibility.stakes,
            context.eligibility.active
          )
        )
        restoredLedger <- ledger(
          Ledger.trustedRestoreLocal(
            context.ledger.environment,
            supplied.ledger.checkpointId,
            a.ledger,
            i.revision - i.originals.size
          )
        )
        _ <- Either.cond(
          restoredLedger.slot == cert.tip.slot,
          (),
          Failure.Rejected("local-anchor", "ledger/certificate slot mismatch")
        )
        acquisition <- checked(
          "local-acquisition",
          BoundedChainFollower.checked(point(cert.tip), Vector.empty)
        )
        branch <- checked(
          "local-certificate",
          CertificateBranch.replay(context.certificates, cert, acquisition)
        )
        anchor = new State(
          context.id,
          branch,
          nonce,
          Some(eligible),
          restoredLedger,
          i.compactedBlocks,
          Some(i.provenance),
          true
        )
        _ <- Either.cond(
          anchor.id == a.id,
          (),
          Failure.Rejected("local-anchor", "anchor ID mismatch")
        )
      yield anchor
    }.flatMap {
      case Left(error) => F.pure(Left(error))
      case Right(anchor) =>
        for
          owner <- F.delay(new Object())
          cell <- Ref.of[F, Cell](Cell(anchor, Vector.empty))
          runtime = new Runtime(context, i.capacity, owner, cell)
          replayed <- i.originals.foldLeft(F.pure[Result[Unit]](Right(()))) { (acc, original) =>
            acc.flatMap {
              case Left(error) => F.pure(Left(error))
              case Right(_) =>
                stage("input")(
                  SequenceInput.block(original).left.map(e => Failure.Rejected("input", e.toString))
                ).flatMap {
                  case Left(error) => F.pure(Left(error))
                  case Right(block) =>
                    (F.cede *> between("prepare") *> runtime.prepare(block)).flatMap {
                      case Left(error) => F.pure(Left(error))
                      case Right(candidate) =>
                        F.cede *> between("publish") *> runtime
                          .publish(candidate)
                          .map(_.map(_ => ()))
                    }
                }
            }
          }
          result <- replayed match
            case Left(error) => F.pure[Result[Runtime[F]]](Left(error))
            case Right(_) =>
              F.cede *> between("verify") *> runtime.snapshot.map { saved =>
                val state = saved.state
                Either.cond(
                  state.id == i.finalId && state.revision == i.revision &&
                    state.certificates.state.tip == i.finalPoint && state.depth == i.compactedBlocks + i.originals.size &&
                    state.derivedAnchorId.contains(i.provenance) && state.trustedLocalPrefix &&
                    state.nonces.certificateStateId == state.certificates.state.id &&
                    state.nonces.lastSlot == state.ledger.slot,
                  runtime,
                  Failure.Rejected("local-recovery", "replayed final tuple mismatch")
                )
              }
        yield result
    }

  def create[F[_]: Sync](
      context: SequenceInput.Context,
      maxBlocks: Int = MaxBlocks
  ): F[Result[Runtime[F]]] =
    Sync[F]
      .delay {
        if maxBlocks < 1 || maxBlocks > MaxBlocks then
          Left(Failure.Unsupported("window", "capacity must be 1..8"))
        else seed(context)
      }
      .flatMap {
        case Left(error) => Sync[F].pure(Left(error))
        case Right(initial) =>
          for
            owner <- Sync[F].delay(new Object())
            cell <- Ref.of[F, Cell](Cell(initial, Vector.empty))
          yield Right(new Runtime(context, maxBlocks, owner, cell))
      }

  private[lab] final class SyntheticBoundaryProfile private[CoherentSequence] (
      val roles: GovernanceParameterPayload.Roles,
      val pools: Map[Bytes, GovernancePoolPayload.Checked],
      val globals: GovernanceGlobals.Checked,
      val window: BigInt,
      private[CoherentSequence] val monetary: SyntheticRewardProfile
  )

  /** Shelley RUPD uses the source-bound randomness stabilisation window (4k/f), not the consensus
    * stability window (3k/f). A typed profile cannot override this timing scalar.
    */
  private[lab] def syntheticBoundaryProfile(
      roles: GovernanceParameterPayload.Roles,
      pools: Map[Bytes, GovernancePoolPayload.Checked],
      globals: GovernanceGlobals.Checked
  ): Result[SyntheticBoundaryProfile] = protect {
    for
      _ <- Either.cond(
        roles != null && pools != null && globals != null && pools.size <= 4096,
        (),
        Failure.Rejected("synthetic-boundary", "typed profile inputs required")
      )
      window = globals.randomnessStabilisationWindow
      _ <- Either.cond(
        window > 0 && 2 * window < globals.rewardGlobals.epochLength,
        (),
        Failure.Rejected("synthetic-boundary", "source-bound reward timing bounds required")
      )
      _ <- checked(
        "synthetic-boundary",
        GovernanceParameterPayload.checkRewards(
          roles.current,
          roles.previous.rewards
        )
      )
      monetary = new SyntheticRewardProfile(
        roles.previous.rewards,
        globals.rewardGlobals,
        window,
        digest(
          "one-boundary-composition-profile-v1",
          Vector(roles.id, globals.id, raw(window.toString))
        ),
        legacyNoEffects = false
      )
    yield new SyntheticBoundaryProfile(roles, pools, globals, window, monetary)
  }

  /** Internal one-boundary synthetic composition; no native admission or persistence support. */
  private[lab] def createWithSyntheticBoundary[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      profile: SyntheticBoundaryProfile,
      input: Governance.Input,
      nonMyopic: NonMyopic.State,
      pots: Boundary.Pots,
      previousBlocks: Map[Bytes, BigInt],
      currentBlocks: Map[Bytes, BigInt],
      absentEvidence: Bytes,
      maxBlocks: Int = MaxBlocks
  ): F[Result[Runtime[F]]] =
    if profile == null then
      Sync[F].pure(Left(Failure.Rejected("synthetic-boundary", "profile required")))
    else
      createSyntheticRewardSeed[F](
        context,
        prepared,
        profile.monetary,
        pots,
        previousBlocks,
        currentBlocks,
        absentEvidence,
        maxBlocks,
        allowDiagnostic = true
      ).flatMap {
        case Left(error) => Sync[F].pure(Left(error))
        case Right(runtime) =>
          runtime.cell.modify { cell =>
            val initial = cell.state
            val result = checked(
              "synthetic-boundary",
              SyntheticBoundaryState.seed(
                input,
                profile.roles,
                profile.pools,
                profile.globals,
                nonMyopic,
                context,
                initial.stake.get,
                pots,
                profile.monetary.parameters,
                profile.monetary.globals
              )
            ).map(component =>
              new State(
                initial.contextId,
                initial.certificates,
                initial.nonces,
                initial.eligibility,
                initial.ledger,
                stakeBinding = initial.stakeBinding,
                rewardBinding = initial.rewardBinding,
                syntheticBoundary = Some(component)
              )
            )
            result match
              case Left(error)  => (cell, Left(error))
              case Right(state) => (Cell(state, Vector.empty), Right(runtime))
          }
      }

  /** Explicit bounded repeated research composition; no durable import or CLI admission. */
  private[lab] def createWithRepeatedBoundary[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      profile: SyntheticBoundaryProfile,
      input: Governance.Input,
      nonMyopic: NonMyopic.State,
      pots: Boundary.Pots,
      previousBlocks: Map[Bytes, BigInt],
      currentBlocks: Map[Bytes, BigInt],
      absentEvidence: Bytes,
      maxTransitions: Int,
      maxBlocks: Int = MaxBlocks,
      generationMode: lab.ledger.ConwayNativeLikelihood.Mode =
        lab.ledger.ConwayNativeLikelihood.Mode.CheckedJvm
  ): F[Result[Runtime[F]]] =
    createWithSyntheticBoundary[F](
      context,
      prepared,
      profile,
      input,
      nonMyopic,
      pots,
      previousBlocks,
      currentBlocks,
      absentEvidence,
      maxBlocks
    ).flatMap {
      case Left(error) => Sync[F].pure(Left(error))
      case Right(runtime) =>
        runtime.cell.modify { cell =>
          val initial = cell.state
          checked(
            "repeated-profile",
            SyntheticBoundaryState.enableRepeated(
              initial.syntheticBoundary.get,
              maxTransitions,
              generationMode
            )
          ) match
            case Left(error) => (cell, Left(error))
            case Right(component) =>
              val next = new State(
                initial.contextId,
                initial.certificates,
                initial.nonces,
                initial.eligibility,
                initial.ledger,
                stakeBinding = initial.stakeBinding,
                rewardBinding = initial.rewardBinding,
                syntheticBoundary = Some(component)
              )
              (Cell(next, Vector.empty), Right(runtime))
        }
    }

  /** Synthetic absent reward seed is an explicit assertion, never decoded from native JSON. The
    * profile requires all omitted effects to be explicitly empty. No durability is supported.
    */
  def createWithSyntheticRewards[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      profile: SyntheticRewardProfile,
      pots: Boundary.Pots,
      previousBlocks: Map[Bytes, BigInt],
      currentBlocks: Map[Bytes, BigInt],
      absentEvidence: Bytes,
      maxBlocks: Int = MaxBlocks
  ): F[Result[Runtime[F]]] =
    if profile == null || !profile.legacyNoEffects then
      Sync[F].pure(
        Left(Failure.Unsupported("synthetic-rewards", "legacy no-effect profile required"))
      )
    else
      createSyntheticRewardSeed[F](
        context,
        prepared,
        profile,
        pots,
        previousBlocks,
        currentBlocks,
        absentEvidence,
        maxBlocks
      )

  private def createSyntheticRewardSeed[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      profile: SyntheticRewardProfile,
      pots: Boundary.Pots,
      previousBlocks: Map[Bytes, BigInt],
      currentBlocks: Map[Bytes, BigInt],
      absentEvidence: Bytes,
      maxBlocks: Int = MaxBlocks,
      allowDiagnostic: Boolean = false
  ): F[Result[Runtime[F]]] =
    createWithStakeImpl[F](context, prepared, maxBlocks, allowDiagnostic).flatMap {
      case Left(error) => Sync[F].pure(Left(error))
      case Right(runtime) =>
        runtime.cell.modify { cell =>
          val result = protect {
            val initial = cell.state
            for
              _ <- Either.cond(
                profile != null && pots != null &&
                  profile.globals.epochLength == context.nonces.context.epochLength &&
                  profile.globals.maxSupply == pots.maxSupply && pots.fees == initial.ledger.fees &&
                  profile.globals.activeSlotCoefficient.numerator == context.eligibility.active.numerator &&
                  profile.globals.activeSlotCoefficient.denominator == context.eligibility.active.denominator &&
                  ((4 * profile.globals.securityParameter.get * profile.globals.activeSlotCoefficient.denominator +
                    profile.globals.activeSlotCoefficient.numerator - 1) / profile.globals.activeSlotCoefficient.numerator) == context.nonces.context.window &&
                  absentEvidence != null && absentEvidence.size == 32,
                (),
                Failure.Rejected(
                  "synthetic-rewards",
                  "seed geometry/pots/explicit absence assertion"
                )
              )
              rewards = new SyntheticRewards(
                Boundary.owner(),
                profile,
                pots,
                previousBlocks,
                currentBlocks,
                None,
                None,
                digest("synthetic-absent-origin", Vector(initial.id, absentEvidence))
              )
              _ <- rewardContext(initial, rewards)
            yield new State(
              initial.contextId,
              initial.certificates,
              initial.nonces,
              initial.eligibility,
              initial.ledger,
              stakeBinding = initial.stakeBinding,
              rewardBinding = Some(rewards)
            )
          }
          result match
            case Left(error)  => (cell, Left(error))
            case Right(state) => (Cell(state, Vector.empty), Right(runtime))
        }
    }

  /** Opt-in atomic stake projection. Fixed registrations/reward balances, same epoch only. No
    * durable codec currently serializes this enlarged tuple.
    */
  def createWithStake[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      maxBlocks: Int = MaxBlocks
  ): F[Result[Runtime[F]]] =
    createWithStakeImpl[F](context, prepared, maxBlocks, allowDiagnostic = false)

  /** Internal same-epoch Plutus diagnostic only. No synthetic boundary or persistence capability.
    */
  private[lab] def createPlutusDiagnosticWithStake[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      maxBlocks: Int = MaxBlocks
  ): F[Result[Runtime[F]]] =
    if context == null || prepared == null || !context.diagnosticOnly ||
      context.ledger.environment.plutus.isEmpty ||
      prepared.plutusNetwork != context.ledger.environment.plutus.map(_.networkId)
    then
      Sync[F].pure(
        Left(
          Failure.Unsupported(
            "plutus-profile",
            "matching diagnostic Plutus context and stake seed required"
          )
        )
      )
    else createWithStakeImpl[F](context, prepared, maxBlocks, allowDiagnostic = true)

  /** Fresh source-validated lineage only. The enclosing restore coordinator authenticates the
    * publication and replays originals before exposing this runtime. No historical ID is installed.
    */
  private[lab] def createPlutusRecoveryWithStake[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      publicationSHA256: Bytes,
      maxBlocks: Int
  ): F[Result[Runtime[F]]] =
    val F = Sync[F]
    if publicationSHA256 == null || publicationSHA256.size != 32 then
      F.pure(
        Left(Failure.Rejected("recovery-publication", "32-byte publication identity required"))
      )
    else
      createPlutusDiagnosticWithStake[F](context, prepared, maxBlocks).flatMap {
        case Left(error) => F.pure(Left(error))
        case Right(validated) =>
          validated.snapshot.flatMap { seedSnapshot =>
            F.delay(protect {
              val initial = seedSnapshot.state
              val attribution = digest(
                "fresh-plutus-recovery-v1",
                Vector(context.id, publicationSHA256, raw(java.util.UUID.randomUUID().toString))
              )
              for
                freshLedger <- ledger(
                  Ledger.checkpoint(
                    initial.ledger.environment,
                    initial.ledger.outputMap,
                    initial.ledger.fees,
                    initial.ledger.slot,
                    attribution
                  )
                )
                stakeOwner = Stake.owner()
                stake <- checked("recovery-stake-seed", prepared.attach(stakeOwner, freshLedger))
              yield new State(
                initial.contextId,
                initial.certificates,
                initial.nonces,
                initial.eligibility,
                freshLedger,
                stakeBinding = Some(stakeOwner -> stake)
              )
            }).flatMap {
              case Left(error) => F.pure(Left(error))
              case Right(initial) =>
                for
                  owner <- F.delay(new Object())
                  cell <- Ref.of[F, Cell](Cell(initial, Vector.empty))
                yield Right(new Runtime(context, maxBlocks, owner, cell))
            }
          }
      }

  private def createWithStakeImpl[F[_]: Sync](
      context: SequenceInput.Context,
      prepared: ConwayStakeSeed.Prepared,
      maxBlocks: Int,
      allowDiagnostic: Boolean
  ): F[Result[Runtime[F]]] =
    Sync[F]
      .delay(protect {
        for
          _ <- Either.cond(
            maxBlocks >= 1 && maxBlocks <= MaxBlocks,
            (),
            Failure.Unsupported("window", "capacity must be 1..8")
          )
          initial <- seed(context, allowDiagnostic)
          _ <- Either.cond(
            prepared.context.epochLength == context.nonces.context.epochLength,
            (),
            Failure.Rejected("stake", "epoch geometry mismatch")
          )
          sourceUtxo = context.wholeUTxO
          sourceId = context.stakeSourceId
          _ <- Either.cond(
            prepared.utxo == sourceUtxo && prepared.sourceId == sourceId,
            (),
            Failure.Rejected("stake-source", "stake seed differs from pinned coordinator sources")
          )
          stakeOwner = Stake.owner()
          stake <- checked("stake-seed", prepared.attach(stakeOwner, initial.ledger))
        yield new State(
          initial.contextId,
          initial.certificates,
          initial.nonces,
          initial.eligibility,
          initial.ledger,
          stakeBinding = Some(stakeOwner -> stake)
        )
      })
      .flatMap {
        case Left(error) => Sync[F].pure(Left(error))
        case Right(initial) =>
          for
            owner <- Sync[F].delay(new Object())
            cell <- Ref.of[F, Cell](Cell(initial, Vector.empty))
          yield Right(new Runtime(context, maxBlocks, owner, cell))
      }

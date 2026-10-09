// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.header.{PraosEligibility, PraosNonceEvolution as Nonces}
import lab.vrf.PraosVrfCertificate as Vrf
import lab.ledger.ClusterTransition as Ledger
import scala.util.control.NonFatal

/** One original block/transaction in one supplied epoch. No post-state is an admission input.
  * Runtime owns one atomic tuple; pure candidates and receipts are not globally single-use.
  */
object CoherentBranch:
  val ProfileId = "conway-pv9-header11-2-derived-nonce-one-block-v2"
  enum Failure:
    case Unsupported(stage: String, feature: String)
    case Rejected(stage: String, reason: String)
    case LedgerRejected(reason: Ledger.Failure)
    case StaleCandidate
    case StaleUndo
  type Result[A] = Either[Failure, A]

  final class State private[CoherentBranch] (
      val contextId: Bytes,
      val certificates: CertificateBranch.Branch,
      val nonces: Nonces.State,
      private[CoherentBranch] val nonceContext: Nonces.Context,
      val eligibility: Option[PraosEligibility.Checked],
      val ledger: Ledger.State
  ):
    def acquisition: BoundedChainFollower.Checkpoint = certificates.acquisition
    def revision: BigInt = ledger.revision
    val id: Bytes = digest(
      Vector(
        contextId,
        certificates.state.id,
        nonces.id,
        ledger.id,
        eligibility.fold(Bytes.empty)(_.contextId)
      )
    )
    val fullLedgerValidated = false
    val consensusValidated = false

  final class Candidate private[CoherentBranch] (
      private[CoherentBranch] val before: State,
      private[CoherentBranch] val certificates: CertificateBranch.Branch,
      private[CoherentBranch] val nonceObservation: Nonces.Applied,
      private[CoherentBranch] val eligibility: PraosEligibility.Checked,
      private[CoherentBranch] val ledger: Ledger.Candidate
  )

  final class ScopedSuccess private[CoherentBranch] (
      val state: State,
      val ledgerObservation: Ledger.Applied,
      val nonceObservation: Nonces.Applied,
      private[CoherentBranch] val before: State
  ):
    val profileId = ProfileId
    val suppliedContextEligibilityVerified = true
    val fullLedgerValidated = false
    val consensusValidated = false
    val authenticatedSnapshot = false

  private def digest(fields: Vector[Bytes]): Bytes =
    ClusterHeaderObservation.sha256(
      Bytes.fromArray(fields.map(_.hex).mkString(ProfileId + "\n", "\n", "\n").getBytes("UTF-8"))
    )
  private def checked[A](stage: String, result: Either[String, A]): Result[A] =
    result.left.map(Failure.Rejected(stage, _))
  private def ledger[A](result: Ledger.Checked[A]): Result[A] = result.left.map {
    case Ledger.Failure.Unsupported(feature) => Failure.Unsupported("ledger", feature)
    case other                               => Failure.LedgerRejected(other)
  }
  private def protect[A](body: => Result[A]): Result[A] =
    try body
    catch case NonFatal(e) => Left(Failure.Rejected("context", e.getClass.getName))

  private def seed(input: BranchInput.Checked): Result[State] = protect {
    import ReferenceJson.{field, uint}
    val p = input.parameters
    val epoch = ReferenceJson.parse(input.originals("pre-ledger-state.md"))
    for
      env <- ledger(
        Ledger.environment(
          input.sourcePins("genesisSha256"),
          input.sourcePins("preParametersSha256"),
          1082026L,
          uint(field(epoch, "lastEpoch")),
          9,
          0,
          uint(field(p, "txFeePerByte")),
          uint(field(p, "txFeeFixed")),
          uint(field(p, "maxTxSize")),
          uint(field(p, "utxoCostPerByte"))
        )
      )
      initial <- ledger(
        Ledger.checkpoint(
          env,
          input.preUtxoCbor,
          input.feesBefore,
          input.certificates.seed.tip.slot,
          input.preStateAttribution
        )
      )
      acquisition <- checked(
        "acquisition",
        BoundedChainFollower.checked(input.anchor, Vector.empty)
      )
      certificates <- checked(
        "certificate",
        CertificateBranch.replay(input.certificates.context, input.certificates.seed, acquisition)
      )
      nonces <- checked(
        "nonce-seed",
        PraosNonceSnapshot.bind(
          input.certificates.context,
          input.certificates.seed,
          input.originals("transfer-genesis.md"),
          input.originals("pre-protocol-state.md"),
          input.sourcePins("preProtocolSha256")
        )
      )
    yield new State(
      // Frozen pre-context identity is independent of candidate eligibility receipts.
      digest(Vector(input.preStateAttribution, input.eligibility.context.id, nonces.context.id)),
      certificates,
      nonces.seed,
      nonces.context,
      None,
      initial
    )
  }

  /** No publication; failures leave the caller's entire state unchanged. */
  def prepare(current: State, input: BranchInput.Checked): Result[Candidate] = protect {
    for
      expected <- seed(input)
      _ <- Either.cond(
        current.contextId == expected.contextId && current.id == expected.id &&
          current.acquisition.size == 0 && current.eligibility.isEmpty,
        (),
        Failure.StaleCandidate
      )
      certificates <- checked(
        "certificate",
        CertificateBranch.replay(
          input.certificates.context,
          current.certificates.state,
          input.certificates.acquisition
        )
      )
      _ <- Either.cond(
        certificates.steps.size == 1,
        (),
        Failure.Unsupported("nonce", "one certificate step required")
      )
      nonce <- checked(
        "nonce",
        Nonces.applyHeader(current.nonceContext, current.nonces, certificates.steps.head)
      )
      epochNonce = nonce.epochNonceUsed match
        case Nonces.Nonce.Neutral     => Vrf.NeutralNonce
        case Nonces.Nonce.Hash(bytes) => Vrf.Hash32.fromBytes(bytes).toOption.get
      eligibilityContext <- checked(
        "eligibility-context",
        PraosEligibility.Context.checked(
          input.certificates.context,
          current.certificates.state,
          input.eligibility.context.epoch,
          current.nonceContext.epochLength,
          epochNonce,
          input.eligibility.context.active,
          input.eligibility.context.stakes,
          derivedEligibilityAttribution(input, current.nonceContext, nonce)
        )
      )
      eligible <- checked(
        "eligibility",
        PraosEligibility.check(eligibilityContext, certificates.steps)
      )
      pending <- ledger(Ledger.prepare(current.ledger, input.transaction, input.header.slot))
    yield new Candidate(current, certificates, nonce, eligible, pending)
  }

  /** The eligibility API calls its final argument protocolDigest. Here it is an explicit derived
    * attribution digest, not the literal SHA256 of protocol export bytes. Original pin retained.
    */
  private def derivedEligibilityAttribution(
      input: BranchInput.Checked,
      context: Nonces.Context,
      step: Nonces.Applied
  ): Bytes =
    ClusterHeaderObservation.sha256(
      Bytes.fromArray(
        Vector(
          "coherent-derived-eligibility-attribution-v1",
          ProfileId,
          input.sourcePins("preProtocolSha256").hex,
          context.id.hex,
          step.before.id.hex,
          step.after.id.hex,
          step.headerHash.hex
        ).mkString("", "\n", "\n").getBytes("UTF-8")
      )
    )

  private def receiptBindings(current: State, candidate: Candidate): Boolean =
    val nonce = candidate.nonceObservation
    candidate.certificates.steps match
      case Vector(certificate) =>
        nonce.before.id == current.nonces.id && nonce.before.contextId == current.nonceContext.id &&
        nonce.after.contextId == current.nonceContext.id &&
        nonce.before.certificateStateId == current.certificates.state.id &&
        certificate.before.id == current.certificates.state.id &&
        nonce.after.certificateStateId == certificate.after.id &&
        certificate.after.id == candidate.certificates.state.id &&
        nonce.headerHash == certificate.after.tip.hash &&
        nonce.headerHash == certificate.observation.originalHeaderHash &&
        nonce.after.lastSlot == certificate.after.tip.slot &&
        nonce.after.lastSlot == candidate.ledger.slot &&
        candidate.eligibility.headers.map(_.headerHash) == Vector(nonce.headerHash)
      case _ => false

  private def commit(current: State, candidate: Candidate): Result[ScopedSuccess] =
    if current.id != candidate.before.id || current.revision != candidate.before.revision ||
      !receiptBindings(current, candidate)
    then Left(Failure.StaleCandidate)
    else
      ledger(Ledger.commit(current.ledger, candidate.ledger)).map { applied =>
        val next = new State(
          current.contextId,
          candidate.certificates,
          candidate.nonceObservation.after,
          current.nonceContext,
          Some(candidate.eligibility),
          applied.state
        )
        new ScopedSuccess(next, applied, candidate.nonceObservation, current)
      }

  private def rollback(current: State, receipt: ScopedSuccess): Result[State] =
    if current.id != receipt.state.id || current.revision != receipt.state.revision ||
      current.nonces.id != receipt.nonceObservation.after.id ||
      current.certificates.state.id != receipt.nonceObservation.after.certificateStateId
    then Left(Failure.StaleUndo)
    else
      for
        certificates <- checked(
          "certificate-undo",
          CertificateBranch.rollback(current.certificates, receipt.before.acquisition.anchor)
        )
        nonces <- checked("nonce-undo", Nonces.undo(current.nonces, receipt.nonceObservation))
        _ <- Either.cond(
          nonces.id == receipt.before.nonces.id && nonces.certificateStateId == certificates.state.id,
          (),
          Failure.StaleUndo
        )
        restored <- ledger(
          Ledger.undo(current.ledger, current.revision, receipt.ledgerObservation.undo)
        )
      yield new State(
        receipt.before.contextId,
        certificates,
        nonces,
        current.nonceContext,
        receipt.before.eligibility,
        restored
      )

  final class Runtime[F[_]] private[CoherentBranch] (state: Ref[F, State])(using F: Sync[F]):
    def snapshot: F[State] = state.get
    def prepare(input: BranchInput.Checked): F[Result[Candidate]] =
      state.get.flatMap(s => F.delay(CoherentBranch.prepare(s, input)))
    def publish(candidate: Candidate): F[Result[ScopedSuccess]] = state.modify { current =>
      commit(current, candidate) match
        case Right(receipt) => (receipt.state, Right(receipt))
        case Left(error)    => (current, Left(error))
    }
    def rollback(receipt: ScopedSuccess): F[Result[State]] = state.modify { current =>
      CoherentBranch.rollback(current, receipt) match
        case Right(restored) => (restored, Right(restored))
        case Left(error)     => (current, Left(error))
    }

  def create[F[_]: Sync](input: BranchInput.Checked): F[Result[Runtime[F]]] =
    Sync[F].delay(seed(input)).flatMap {
      case Left(error)    => Sync[F].pure(Left(error))
      case Right(initial) => Ref.of[F, State](initial).map(ref => Right(new Runtime(ref)))
    }

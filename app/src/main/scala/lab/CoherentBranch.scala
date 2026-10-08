// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.header.PraosEligibility
import lab.ledger.ClusterTransition as Ledger
import scala.util.control.NonFatal

/** One original block/transaction in one supplied epoch. No post-state is an admission input.
  * Runtime owns one atomic tuple; pure candidates and receipts are not globally single-use.
  */
object CoherentBranch:
  val ProfileId = "conway-pv9-header11-2-supplied-epoch-one-block-v1"
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
      val eligibility: Option[PraosEligibility.Checked],
      val ledger: Ledger.State
  ):
    def acquisition: BoundedChainFollower.Checkpoint = certificates.acquisition
    def revision: BigInt = ledger.revision
    val id: Bytes = digest(
      Vector(
        contextId,
        certificates.state.id,
        ledger.id,
        eligibility.fold(Bytes.empty)(_.contextId)
      )
    )
    val fullLedgerValidated = false
    val consensusValidated = false

  final class Candidate private[CoherentBranch] (
      private[CoherentBranch] val before: State,
      private[CoherentBranch] val certificates: CertificateBranch.Branch,
      private[CoherentBranch] val eligibility: PraosEligibility.Checked,
      private[CoherentBranch] val ledger: Ledger.Candidate
  )

  final class ScopedSuccess private[CoherentBranch] (
      val state: State,
      val ledgerObservation: Ledger.Applied,
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
    yield new State(
      digest(Vector(input.preStateAttribution, input.eligibility.context.id)),
      certificates,
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
      eligible <- checked(
        "eligibility",
        PraosEligibility.check(input.eligibility.context, certificates.steps)
      )
      pending <- ledger(Ledger.prepare(current.ledger, input.transaction, input.header.slot))
    yield new Candidate(current, certificates, eligible, pending)
  }

  private def commit(current: State, candidate: Candidate): Result[ScopedSuccess] =
    if current.id != candidate.before.id || current.revision != candidate.before.revision then
      Left(Failure.StaleCandidate)
    else
      ledger(Ledger.commit(current.ledger, candidate.ledger)).map { applied =>
        val next = new State(
          current.contextId,
          candidate.certificates,
          Some(candidate.eligibility),
          applied.state
        )
        new ScopedSuccess(next, applied, current)
      }

  private def rollback(current: State, receipt: ScopedSuccess): Result[State] =
    if current.id != receipt.state.id || current.revision != receipt.state.revision then
      Left(Failure.StaleUndo)
    else
      for
        certificates <- checked(
          "certificate-undo",
          CertificateBranch.rollback(current.certificates, receipt.before.acquisition.anchor)
        )
        restored <- ledger(
          Ledger.undo(current.ledger, current.revision, receipt.ledgerObservation.undo)
        )
      yield new State(receipt.before.contextId, certificates, receipt.before.eligibility, restored)

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

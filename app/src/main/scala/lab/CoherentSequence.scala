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
import lab.ledger.ClusterTransition as Ledger
import lab.network.ChainSync
import lab.vrf.PraosVrfCertificate as Vrf
import scala.util.control.NonFatal

/** Up to eight independently checked same-epoch originals after an explicit supplied context. One
  * Ref owns both the complete tuple and every retained undo. No downloaded checkpoint loader,
  * implicit reanchor, eviction, durable publication or full-consensus claim is provided.
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
      val ledger: Ledger.State
  ):
    def acquisition: BoundedChainFollower.Checkpoint = certificates.acquisition
    def revision: BigInt = ledger.revision
    val scopedAppliedTip: Option[ChainSync.Point] =
      Option.when(acquisition.size > 0)(acquisition.tip)
    val id: Bytes = digest(
      "state",
      Vector(
        contextId,
        acquisitionIdentity(acquisition),
        certificates.state.id,
        nonces.id,
        eligibility.fold(Bytes.empty)(_.contextId),
        ledger.id
      )
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
      private[CoherentSequence] val ledger: Ledger.BlockCandidate
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
      ledger: Ledger.Undo
  )
  private final case class Cell(state: State, receipts: Vector[OwnedReceipt])

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

  private def seed(context: SequenceInput.Context): Result[State] = protect {
    for
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
        context.sourcePins("preProtocolSha256"),
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
      block: SequenceInput.Block
  ): Result[Candidate] = protect {
    for
      _ <- Either.cond(
        block.header.slot / context.nonces.context.epochLength == context.epoch,
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
        CertificateBranch.append(context.certificates, current.certificates, block.original)
      )
      certificate = certificates.steps.last
      nonce <- checked(
        "nonce",
        Nonces.applyHeader(context.nonces.context, current.nonces, certificate)
      )
      epochNonce = nonce.epochNonceUsed match
        case Nonces.Nonce.Neutral     => Vrf.NeutralNonce
        case Nonces.Nonce.Hash(bytes) => Vrf.Hash32.fromBytes(bytes).toOption.get
      eligibilityContext <- checked(
        "eligibility-context",
        PraosEligibility.Context.checked(
          context.certificates,
          current.certificates.state,
          context.epoch,
          context.nonces.context.epochLength,
          epochNonce,
          context.eligibility.active,
          context.eligibility.stakes,
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
          block.header.slot
        )
      )
    yield new Candidate(owner, current, block, certificates, certificate, nonce, eligible, pending)
  }
  private def bindings(current: State, c: Candidate): Boolean =
    c.certificates.acquisition.originals == (current.acquisition.originals :+ c.block.original) &&
      c.certificates.acquisition.anchor == current.acquisition.anchor &&
      c.certificate.before.id == current.certificates.state.id &&
      c.certificate.after.id == c.certificates.state.id &&
      c.nonce.before.id == current.nonces.id &&
      c.nonce.before.certificateStateId == current.certificates.state.id &&
      c.nonce.after.certificateStateId == c.certificate.after.id &&
      c.nonce.before.contextId == current.nonces.contextId && c.nonce.after.contextId == current.nonces.contextId &&
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
    if !(candidate.owner eq owner) then Left(Failure.ForeignCandidate)
    else if current.id != candidate.before.id || current.revision != candidate.before.revision ||
      cell.receipts.size >= maxBlocks || !bindings(current, candidate)
    then Left(Failure.StaleCandidate)
    else
      ledger(Ledger.commitBlock(current.ledger, candidate.ledger)).map { block =>
        val state = new State(
          current.contextId,
          candidate.certificates,
          candidate.nonce.after,
          Some(candidate.eligibility),
          block.state
        )
        val owned =
          OwnedReceipt(current, state.id, candidate.certificate, candidate.nonce, block.undo)
        (
          Cell(state, cell.receipts :+ owned),
          new Applied(state, block, candidate.certificate, candidate.nonce)
        )
      }
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
      restored = new State(
        current.contextId,
        certificates,
        nonces,
        owned.before.eligibility,
        restoredLedger
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

  final class Runtime[F[_]] private[CoherentSequence] (
      context: SequenceInput.Context,
      val maxBlocks: Int,
      owner: AnyRef,
      cell: Ref[F, Cell]
  )(using F: Sync[F]):
    def snapshot: F[Snapshot] = cell.get.map(c => CoherentSequence.snapshot(owner, c.state))
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

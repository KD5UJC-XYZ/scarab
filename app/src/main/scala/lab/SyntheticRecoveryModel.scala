// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.Async
import cats.syntax.all.*
import cats.effect.syntax.all.*
import java.io.{ByteArrayOutputStream, DataOutputStream}
import lab.cbor.Bytes
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Opaque bounded in-memory handoff. This is neither a byte format nor crash recovery. Controller
  * approval must compare a separately retained exact claim, never trust its digest.
  */
private[lab] object SyntheticRecoveryModel:
  val Domain = "internal-synthetic-recovery-model-v1"
  final case class Claim(
      publicationId: Bytes,
      digest: Bytes,
      contextId: Bytes,
      anchorId: Bytes,
      finalId: Bytes,
      revision: BigInt,
      measurement: SyntheticRecoveryBudget.Measurement
  )
  trait ControllerAuthority[F[_]]:
    def authorize(claim: Claim): F[Either[String, Unit]]
  final class Envelope private[SyntheticRecoveryModel] (
      private[lab] val image: CoherentSequence.RecoveryImage,
      val claim: Claim
  )
  final class Accepted private[SyntheticRecoveryModel] (
      private[SyntheticRecoveryModel] val envelope: Envelope
  )
  final class Authorized private[SyntheticRecoveryModel] (
      private[lab] val image: CoherentSequence.RecoveryImage
  )

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid recovery model"))
  private def get[A](value: Either[String, A]): A =
    value.fold(e => throw new IllegalArgumentException(e), identity)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def commitment(
      image: CoherentSequence.RecoveryImage,
      publicationId: Bytes,
      measurement: SyntheticRecoveryBudget.Measurement
  ): Bytes =
    // Framing for a bounded commitment only. No import/parser or storage representation exists.
    val buffer = new ByteArrayOutputStream()
    val out = new DataOutputStream(buffer)
    def field(b: Bytes): Unit =
      require(b != null && b.size <= 1024, "bounded commitment field")
      out.writeInt(b.size); out.write(b.toArray)
    def number(n: BigInt): Unit = field(raw(n.toString))
    def branch(b: CertificateBranch.Branch): Unit =
      field(b.initial.id); field(b.state.id); number(b.acquisition.size)
      b.acquisition.originals.foreach { o =>
        field(ClusterHeaderObservation.sha256(o.envelope));
        field(ClusterHeaderObservation.sha256(o.block))
      }
    field(raw(Domain)); field(publicationId); field(image.context.id); number(image.capacity)
    number(measurement.entries); number(measurement.payloadBytes); number(image.states.size)
    image.states.foreach { s =>
      val fields = CoherentSequence.recoveryStateFields(s)
      number(fields.size); fields.foreach(field); branch(s.certificates)
    }
    number(image.records.size)
    image.records.foreach { r =>
      field(ClusterHeaderObservation.sha256(r.original.envelope));
      field(ClusterHeaderObservation.sha256(r.original.block))
      field(r.position.id); number(r.position.revision); number(r.position.compactedBlocks)
      field(r.position.derivedAnchorId.getOrElse(Bytes.empty));
      number(if r.position.trustedLocalPrefix then 1 else 0)
      branch(r.position.certificates); field(r.historicalAfterId)
      field(r.boundary.fold(Bytes.empty)(_.id))
    }
    out.flush()
    ClusterHeaderObservation.sha256(Bytes.fromArray(buffer.toByteArray))

  def prepare(
      image: CoherentSequence.RecoveryImage,
      publicationId: Bytes
  ): Either[String, Envelope] = checked {
    require(
      image != null && publicationId != null && publicationId.size == 32 &&
        image.states.nonEmpty && image.states.size <= 9 && image.records.size <= 8 &&
        image.states.size == image.records.size + 1 && image.capacity >= 1 && image.capacity <= 8 &&
        image.records.size <= image.capacity,
      "recovery image/publication bounds"
    )
    require(
      image.states.forall(s => s != null && s.stake.isDefined && s.syntheticRewards.isDefined),
      "synthetic stake/reward image required"
    )
    val boundaries = CoherentSequence.recoveryBoundaries(image)
    val measurement = get(
      SyntheticRecoveryBudget.measure(
        image.context,
        image.states,
        image.records.map(_.position.certificates),
        boundaries.map(_.preview),
        CoherentSequence.recoveryCertificateContexts(image)
      )
    )
    val digest = commitment(image, publicationId, measurement)
    new Envelope(
      image,
      Claim(
        publicationId,
        digest,
        image.context.id,
        image.states.head.id,
        image.states.last.id,
        image.states.last.revision,
        measurement
      )
    )
  }

  def accept[F[_]: Async](
      envelope: Envelope,
      controller: ControllerAuthority[F],
      deadline: FiniteDuration = 5.seconds
  ): F[Either[String, Accepted]] =
    val F = Async[F]
    if envelope == null || controller == null || deadline <= Duration.Zero || deadline > 30.seconds
    then F.pure(Left("bounded controller/envelope required"))
    else
      F.defer(controller.authorize(envelope.claim))
        .timeoutTo(deadline, F.pure(Left("controller authorization deadline")))
        .map(_.map(_ => new Accepted(envelope)))

  def restore[F[_]: Async](
      envelope: Envelope,
      accepted: Accepted
  ): F[Either[String, CoherentSequence.Runtime[F]]] =
    val F = Async[F]
    if envelope == null || accepted == null || (accepted.envelope ne envelope) then
      F.pure(Left("missing or substituted controller-authorized envelope"))
    else
      F.delay(prepare(envelope.image, envelope.claim.publicationId)).flatMap {
        case Left(error) => F.pure(Left(error))
        case Right(rechecked) if rechecked.claim != envelope.claim =>
          F.pure(Left("recovery commitment changed"))
        case Right(_) =>
          CoherentSequence
            .hydrateSyntheticRecovery[F](new Authorized(envelope.image))
            .map(_.left.map(_.toString))
      }

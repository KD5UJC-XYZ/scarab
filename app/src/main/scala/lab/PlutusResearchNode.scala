// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.Bytes
import lab.submission.{AdmissionProfile, AdmissionStateObserver, AdmissionStateChange}

/** Reusable volatile owner/service/API lifecycle for the fixed restricted profile. Callers provide
  * a fresh checked runtime and attach a bounded observer before opening HTTP. No transaction file
  * is ingested here; the loopback endpoint is the only submission surface.
  */
private[lab] object PlutusResearchNode:
  final class Session[F[_]: Async] private[PlutusResearchNode] (
      val owner: SubmissionOwner[F],
      val service: AdaSubmissionService[F],
      val evidence: PlutusEvaluationEvidence.Store[F]
  ):
    def http(additional: Option[AdmissionStateObserver[F]] = None): Resource[F, AdaHttp.Bound] =
      val observer = new AdmissionStateObserver[F]:
        def changed(change: AdmissionStateChange): F[Unit] =
          service.changed(change) *> additional.traverse_(_.changed(change))
        def closed: F[Unit] = service.closed *> additional.traverse_(_.closed)
      Resource.eval(owner.attach(observer)) *> AdaHttp.server(AdaHttpHandler(service))

  def resource[F[_]: Async](
      freshRuntime: F[CoherentSequence.Runtime[F]],
      evidenceDirectory: Path,
      sourceJoinId: Bytes,
      initialManifestSHA256: Bytes
  ): Resource[F, Session[F]] =
    for
      evidence <- PlutusEvaluationEvidence
        .fileObserver[F](evidenceDirectory, sourceJoinId, initialManifestSHA256)
      owner <- SubmissionOwner.resource(freshRuntime, AdmissionProfile.PlutusV3)
      service <- AdaSubmissionService.resource(owner, evidence = Some(evidence))
    yield new Session(owner, service, evidence)

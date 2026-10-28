// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.network.{HandlerFailure, NetworkError, LocalTxSubmissionServer}
import xyz.kd5ujc.scarab.node.log.NodeEvent

/** A node-to-client connection that fails ends alone: the reference's local rethrow policy is
  * `rethrowPolicy <> mempty`, whose only `ShutdownNode` is a Windows `IOManagerError`.
  */
class LocalBackendOutcomeSuite extends munit.CatsEffectSuite:
  test("a storage failure under a local client ends that connection, after its resources") {
    val error = StorageError.VersionMismatch("UTxO store", "v4", "v5")
    for
      releases <- Ref.of[IO, Int](0)
      acquired = Resource
        .make(IO.pure(Left(error): Either[StorageError, LocalTxSubmissionServer.End]))(_ =>
          releases.update(_ + 1)
        )
      result <- Main.landLocalBackend(acquired.use(IO.pure))
      released <- releases.get
    yield
      assertEquals(result, InboundEnd.Storage(error))
      assertEquals(released, 1)
      assertEquals(result.message, s"storage: ${error.message}")
  }

  test("a handler defect or a network defect on a local connection closes it and nothing else") {
    val bug = new IllegalStateException("handler defect")
    for
      lines <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
      _ <- ListenerFailures.reportLocal[IO](
        "/ipc/node.socket",
        HandlerFailure.Defect(bug),
        e => lines.update(_ :+ e)
      )
      _ <- ListenerFailures.reportLocal[IO](
        "/ipc/node.socket",
        HandlerFailure.Domain(NetworkError.Defect(bug)),
        e => lines.update(_ :+ e)
      )
      seen <- lines.get
    yield assertEquals(
      seen,
      Vector(
        NodeEvent.Inbound("/ipc/node.socket", InboundEvent.Closed(InboundEnd.Defect(bug))),
        NodeEvent.Inbound(
          "/ipc/node.socket",
          InboundEvent.Closed(
            InboundEnd.Connection(xyz.kd5ujc.scarab.network.ConnectionEnd.Defect(bug))
          )
        )
      )
    )
  }

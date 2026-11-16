// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.ledger.{
  ChainError,
  ClockProblem,
  EvaluationUnavailable,
  LedgerFailure,
  ProviderDefect,
  ScriptLocation,
  StorageError,
  VerificationDefect
}
import xyz.kd5ujc.scarab.network.selection.{FailureClass, ReturnReason, Temperature, Trace}
import xyz.kd5ujc.scarab.node.assertions.{Assertions, Collecting, Made, Property}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.StorageEvent

/** The assertions of the node's wiring and of the peer governor (#1032), each checked with the
  * collecting interpreter from the typed value that triggers it: a fault the node stops for, a
  * snapshot the storage wrote, a demotion the governor traced.
  */
class NodeAssertionsSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private val point = ChainSync.Point.Block(
    ChainSync.UInt64.saturating(4200L),
    Bytes.wrap(Array.fill(32)(0xab.toByte))
  )
  private val hash = Bytes.wrap(Array.fill(32)(0xcd.toByte))

  private val unavailable = EvaluationUnavailable(
    point,
    LedgerFailure.Unavailable(
      hash,
      ScriptLocation.Phase1(hash),
      xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported(
        xyz.kd5ujc.scarab.plutus.BackendFailure.Uplc(
          xyz.kd5ujc.scarab.uplc.ledger.Unavailability.NoDenotation(
            xyz.kd5ujc.scarab.uplc.SemanticsVariant.E,
            Set(xyz.kd5ujc.scarab.uplc.DefaultFun.Bls12_381_G1_hashToGroup)
          )
        )
      )
    )
  )
  private val unverifiable = VerificationDefect(
    point,
    VerificationDefect.Site.Header,
    ProviderDefect.Ed25519(
      VerificationError.ImplementationFailure(new IllegalStateException("provider broke"))
    )
  )

  /** What `run` asserted, through the collecting interpreter. */
  private def asserted(run: Assertions[IO] ?=> IO[Unit]): IO[Vector[Made]] =
    Collecting.create.flatMap((assertions, made) => run(using assertions) *> made)

  test("a stop on a fault is asserted unreachable, but for the clock moved back too far") {
    val faults = Vector(
      Fatal.Unavailable(unavailable),
      Fatal.Storage(StorageError.Io("volatile", new java.io.IOException("disk full"))),
      Fatal.Internal(ChainError.NoIntersection),
      Fatal.Defect(new IllegalStateException("a bug in the rules")),
      Fatal.Unverifiable(unverifiable)
    )
    val clock = Fatal.Internal(ClockProblem.SystemClockMovedBack(BigInt(5010), BigInt(4980)))
    for
      stops <- asserted((faults :+ clock).traverse_(Main.stopped))
      clockOnly <- asserted(Main.stopped(clock))
    yield
      assertEquals(
        stops.map(m => (m.property, m.condition, m.details("kind"), m.details("exitCode"))),
        faults.map(f =>
          (
            Property.Unreachable.StoppedOnFault,
            false,
            Some(f.productPrefix.asJson),
            Some(Main.FaultExitCode(f).code.asJson)
          )
        )
      )
      assertEquals(clockOnly, Vector.empty[Made])
  }

  /** The reporter of the node's faults, its log entered and cancelled before it ends: what it
    * asserted, and the fault the run was handed.
    */
  private def cancelledLog(fault: Fatal): IO[(Vector[Made], Option[Fatal])] =
    for
      collected <- Collecting.create
      (assertions, made) = collected
      faulted <- Deferred[IO, Fatal]
      entered <- Deferred[IO, Unit]
      reporter <- Main.faultReporter(faulted, _ => entered.complete(()) *> IO.never)(using
        assertions
      )
      reporting <- reporter.report(fault).start
      _ <- entered.get
      _ <- reporting.cancel
      asserted <- made
      handed <- faulted.tryGet
    yield (asserted, handed)

  test("a stop is asserted and the run is told even when the fatal log is cancelled") {
    val storage = Fatal.Storage(StorageError.Io("volatile", new java.io.IOException("disk full")))
    val clock = Fatal.Internal(ClockProblem.SystemClockMovedBack(BigInt(5010), BigInt(4980)))
    for
      stop <- cancelledLog(storage)
      clockStop <- cancelledLog(clock)
    yield
      assertEquals(
        stop._1.map(m => (m.property, m.condition, m.details("kind"))),
        Vector((Property.Unreachable.StoppedOnFault, false, Some(Json.fromString("Storage"))))
      )
      assertEquals(stop._2, Some(storage))
      assertEquals(clockStop._1, Vector.empty[Made])
      assertEquals(clockStop._2, Some(clock))
  }

  // The runtime checks for cancelation at every step and yields at every second one, so the seeded
  // schedules let a cancel land anywhere in a report: before its claim, during it, in the log.
  test("a fault is never handed to the run without its assertion, wherever a cancel lands") {
    val everyStep = cats.effect.unsafe.IORuntimeConfig(1, 2)
    val storage = Fatal.Storage(StorageError.Io("volatile", new java.io.IOException("disk full")))
    (1L to 40L).toList.traverse_ { seed =>
      val program =
        for
          collected <- Collecting.create
          (assertions, made) = collected
          faulted <- Deferred[IO, Fatal]
          reporter <- Main.faultReporter(faulted, _ => IO.never)(using assertions)
          _ <- reporter.report(storage).start.flatMap(_.cancel)
          asserted <- made
          handed <- faulted.tryGet
        yield assertEquals(
          asserted.map(_.property),
          handed.as(Property.Unreachable.StoppedOnFault).toVector,
          s"seed $seed: the stop handed to the run, $handed, is asserted"
        )
      val seeded = java.util.Base64.getEncoder
        .encodeToString(seed.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      cats.effect.testkit.TestControl.executeEmbed(program, everyStep, Some(seeded))
    }
  }

  test("a rollback past half of k is counted without overflow, for any k a configuration takes") {
    assert(Follower.pastHalfK(1073741824, 1500000000))
    assert(!Follower.pastHalfK(750000000, 1500000000))
    assert(Follower.pastHalfK(Int.MaxValue, Int.MaxValue))
    assert(!Follower.pastHalfK(Int.MaxValue / 2, Int.MaxValue))
    assert(Follower.pastHalfK(3, 5))
    assert(!Follower.pastHalfK(2, 5))
    assert(!Follower.pastHalfK(0, 1))
  }

  test("a start that fails on a fault is a stop too; one that fails otherwise is not") {
    assertEquals(Main.startupFatal(unavailable), Some(Fatal.Unavailable(unavailable)))
    assertEquals(Main.startupFatal(unverifiable), Some(Fatal.Unverifiable(unverifiable)))
    assertEquals(Main.startupFatal(ChainError.NoIntersection), None)
  }

  test("a snapshot written is asserted from the storage's event, and no other event is") {
    val anchor = BlockRef(BigInt(4200), hash, BigInt(77), false)
    asserted(
      Main.snapshots.emit(StorageEvent.SnapshotDeleted("4100-abab")) *>
        Main.snapshots.emit(StorageEvent.SnapshotWritten("4200-cdcd", Some(anchor), 2048L, 15L)) *>
        Main.snapshots.emit(StorageEvent.ShutdownSnapshot(Some(anchor), false))
    ).map { made =>
      assertEquals(
        made.map(m => (m.property, m.condition, m.details("name"), m.details("slot"))),
        Vector(
          (Property.Sometimes.SnapshotWritten, true, Some("4200-cdcd".asJson), Some(4200.asJson))
        )
      )
    }
  }

  test("the governor asserts a demotion it commanded, one it did not, and a lost intersection") {
    val peer = "10.0.0.1:3001"
    val traces: Vector[Trace[String]] = Vector(
      Trace.Demote(Temperature.Hot, List(peer), local = false, 2, 3),
      Trace.DemoteDone(Temperature.Hot, peer, 2),
      Trace.DemoteAsynchronous(
        peer,
        Temperature.Warm,
        None,
        None,
        Some(ReturnReason.NoMoreIntersection),
        Some(120.seconds)
      ),
      Trace.DemoteAsynchronous(
        peer,
        Temperature.Cold,
        Some(FailureClass.Transient),
        Some("unavailable: connection closed"),
        None,
        Some(12.5.seconds)
      ),
      Trace.PromoteDone(Temperature.Hot, peer, 2, 3)
    )
    asserted(traces.traverse_(PeerGovernor.demoted[IO, String])).map { made =>
      assertEquals(
        made.map(m => (m.property, m.condition, m.details("temperature"), m.details("returned"))),
        Vector(
          (Property.Sometimes.PeerDemoted, true, Some("Hot".asJson), Some(Json.Null)),
          (
            Property.Sometimes.PeerDemotedAsynchronously,
            true,
            Some("Warm".asJson),
            Some("NoMoreIntersection".asJson)
          ),
          (
            Property.Sometimes.PeerNoLongerIntersects,
            true,
            Some("Warm".asJson),
            Some("NoMoreIntersection".asJson)
          ),
          (
            Property.Sometimes.PeerDemotedAsynchronously,
            true,
            Some("Cold".asJson),
            Some(Json.Null)
          ),
          (Property.Sometimes.PeerNoLongerIntersects, false, Some("Cold".asJson), Some(Json.Null))
        )
      )
    }
  }

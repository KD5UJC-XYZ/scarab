// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Outcome, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync
import scala.concurrent.duration.*

class PlutusRepeatedEpochFollowSuite extends munit.FunSuite:
  private val L = PlutusRepeatedEpochFollow
  private class Peer(pull: IO[BoundedChainFollower.Event]) extends BoundedChainFollower.Peer[IO]:
    def intersect(points: Vector[ChainSync.Point]) = IO.pure(points.head)
    def next = pull
    def fetch(point: ChainSync.Point): IO[Bytes] =
      IO.raiseError(new AssertionError("unexpected fetch"))
  private def owned(peer: Peer, closed: Ref[IO, Int]) =
    Resource.make(IO.pure(peer))(_ => closed.update(_ + 1))

  test("peer read timeout is a failure even during a 600 second operational session") {
    (for
      closed <- Ref.of[IO, Int](0)
      error = new java.util.concurrent.TimeoutException("peer read")
      result <- L.boundedSession(600.seconds)(
        owned(new Peer(IO.raiseError(error)), closed).use(_.next)
      )
      count <- closed.get
      _ = assertEquals(count, 1)
      _ = assertEquals(
        result.left.map(L.failureStop),
        Left(PlutusRunPolicy.FollowStop.Failed(error))
      )
    yield ()).unsafeToFuture()
  }
  test("peer release timeout remains a failure instead of successful duration completion") {
    (for
      error <- IO(new java.util.concurrent.TimeoutException("peer release"))
      resource = Resource.make(IO.unit)(_ => IO.raiseError[Unit](error))
      result <- L.boundedSession(600.seconds)(resource.use(_ => IO.unit))
      _ = assertEquals(
        result.left.map(L.failureStop),
        Left(PlutusRunPolicy.FollowStop.Failed(error))
      )
    yield ()).unsafeToFuture()
  }
  test("only the follower-owned deadline becomes Deadline and releases the stalled peer") {
    TestControl
      .executeEmbed(for
        closed <- Ref.of[IO, Int](0)
        result <- L.boundedSession(600.seconds)(owned(new Peer(IO.never), closed).use(_.next))
        count <- closed.get
        _ = assertEquals(count, 1)
        _ = assertEquals(result.left.map(L.failureStop), Left(PlutusRunPolicy.FollowStop.Deadline))
      yield ())
      .unsafeToFuture()
  }
  test("external cancellation propagates through the session and releases its peer exactly once") {
    (for
      entered <- Deferred[IO, Unit]
      closed <- Ref.of[IO, Int](0)
      fiber <- L
        .boundedSession(600.seconds)(
          owned(new Peer(entered.complete(()).void *> IO.never), closed).use(_.next)
        )
        .start
      _ <- entered.get
      _ <- fiber.cancel
      outcome <- fiber.join
      count <- closed.get
      _ = assertEquals(count, 1)
      _ = outcome match
        case Outcome.Canceled() => ()
        case other              => fail(s"cancellation transformed: $other")
    yield ()).unsafeToFuture()
  }
  test("legacy seed is refused before a repeated follower acquires a peer") {
    (for
      runtime <- EphemeralStreamingFixture.runtime
      before <- runtime.snapshot
      opened <- Ref.of[IO, Int](0)
      peer = Resource.eval(opened.update(_ + 1).as(new Peer(IO.never)))
      result <- L
        .runUntil(
          runtime,
          peer,
          before.state.certificates.state.tip,
          1000,
          EphemeralStreaming.Limits(duration = 1.second)
        )(IO.pure(false))(_ => IO.unit)
        .attempt
      count <- opened.get
      after <- runtime.snapshot
      _ = assert(result.isLeft)
      _ = assertEquals(count, 0)
      _ = assertEquals(after.state.id, before.state.id)
    yield ()).unsafeToFuture()
  }

  test("operational timeout cannot hide an error raised while cancelling the peer resource") {
    TestControl
      .executeEmbed(for
        releaseError <- IO(new java.util.concurrent.TimeoutException("cancel cleanup"))
        resource = Resource.make(IO.unit)(_ => IO.raiseError[Unit](releaseError))
        result <- L.boundedResourceSession(1.second, resource)(_ => IO.never[Unit])
        _ = result match
          case Left(error: L.ResourceFailure) => assert(error.getCause eq releaseError)
          case other                          => fail(s"cleanup failure hidden by deadline: $other")
      yield ())
      .unsafeToFuture()
  }

  sys.env.get("PLUTUS_REPEAT_BOOTSTRAP_BUNDLE").foreach { directory =>
    lazy val manifest = sys.env.getOrElse(
      "PLUTUS_REPEAT_BOOTSTRAP_MANIFEST_SHA256",
      fail("independent repeated bootstrap manifest pin required")
    )
    lazy val joined = NativeLiveBoundaryMain.initial(
      java.nio.file.Path.of(directory),
      manifest,
      lab.submission.AdmissionProfile.PlutusV3
    )
    val oracle = new NativeLikelihoodOracle.Oracle[IO]:
      def generate(frozen: lab.ledger.ConwayEpochBoundary.Frozen, id: Bytes) =
        IO.raiseError[lab.ledger.ConwayNativeLikelihood.Generated](
          new AssertionError("unexpected startup freeze")
        )
    def fresh = PlutusServiceCheckpoint
      .start(None, joined, Bytes.fromHex(manifest).toOption.get)
      .flatMap(early => RepeatedPlutusBootstrap.start(joined, early, oracle))

    test(
      "genuine repeated seed refuses a foreign fullpoint intersection without pulling or publishing"
    ) {
      (for
        startup <- fresh
        closed <- Ref.of[IO, Int](0)
        peer = new Peer(IO.raiseError(new AssertionError("unexpected pull"))):
          override def intersect(points: Vector[ChainSync.Point]) = IO.pure(ChainSync.Point.Origin)
        result <- L.runUntil(
          startup.runtime,
          owned(peer, closed),
          startup.snapshot.state.certificates.state.tip,
          1000,
          EphemeralStreaming.Limits(duration = 1.second)
        )(IO.pure(false))(_ => IO.unit)
        count <- closed.get
        _ = assertEquals(count, 1)
        _ = assert(result.stop.isInstanceOf[PlutusRunPolicy.FollowStop.Failed])
        _ = assertEquals(result.snapshot.state.id, startup.snapshot.state.id)
      yield ()).unsafeToFuture()
    }
    test(
      "genuine repeated follower treats peer timeout as failure rather than duration completion"
    ) {
      (for
        startup <- fresh
        closed <- Ref.of[IO, Int](0)
        error = new java.util.concurrent.TimeoutException("peer")
        result <- L.runUntil(
          startup.runtime,
          owned(new Peer(IO.raiseError(error)), closed),
          startup.snapshot.state.certificates.state.tip,
          1000,
          EphemeralStreaming.Limits(duration = 600.seconds)
        )(IO.pure(false))(_ => IO.unit)
        count <- closed.get
        _ = assertEquals(count, 1)
        _ = assertEquals(result.stop, PlutusRunPolicy.FollowStop.Failed(error))
        _ = assertEquals(result.snapshot.state.id, startup.snapshot.state.id)
      yield ()).unsafeToFuture()
    }
    test("genuine repeated follower cancellation releases peer and preserves selected state") {
      (for
        startup <- fresh
        entered <- Deferred[IO, Unit]
        closed <- Ref.of[IO, Int](0)
        fiber <- L
          .runUntil(
            startup.runtime,
            owned(new Peer(entered.complete(()).void *> IO.never), closed),
            startup.snapshot.state.certificates.state.tip,
            1000,
            EphemeralStreaming.Limits(duration = 600.seconds)
          )(IO.pure(false))(_ => IO.unit)
          .start
        _ <- entered.get
        _ <- fiber.cancel
        result <- fiber.join
        count <- closed.get
        after <- startup.runtime.snapshot
        _ = assertEquals(count, 1)
        _ = assertEquals(after.state.id, startup.snapshot.state.id)
        _ = result match
          case Outcome.Canceled() => ()
          case other              => fail(s"cancel became $other")
      yield ()).unsafeToFuture()
    }
  }

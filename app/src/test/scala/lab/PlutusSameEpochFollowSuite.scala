// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Outcome, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState.Point
import lab.network.ChainSync
import lab.submission.AdmissionProfile
import scala.concurrent.duration.*

/** Retained bootstrap supplies real checked runtime geometry; fake peers exercise no network. */
class PlutusSameEpochFollowSuite extends munit.FunSuite:
  private val L = PlutusSameEpochFollow
  private def get[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private def wire(point: Point): ChainSync.Point =
    ChainSync.Point.Block(get(ChainSync.UInt64.from(point.slot)), point.hash)
  private class Peer(initial: Point, pull: IO[BoundedChainFollower.Event])
      extends BoundedChainFollower.Peer[IO]:
    def intersect(points: Vector[ChainSync.Point]): IO[ChainSync.Point] = IO {
      assertEquals(points, Vector(wire(initial)))
      wire(initial)
    }
    def next: IO[BoundedChainFollower.Event] = pull
    def fetch(point: ChainSync.Point): IO[Bytes] =
      IO.raiseError(new AssertionError("unexpected block fetch"))
  private def resource(peer: BoundedChainFollower.Peer[IO], released: Ref[IO, Int]) =
    Resource.make(IO.pure(peer))(_ => released.update(_ + 1))
  private val short = EphemeralStreaming.Limits(duration = 50.millis)

  sys.env.get("PLUTUS_BOOTSTRAP_BUNDLE").foreach { directory =>
    lazy val joined = NativeLiveBoundaryMain.initial(
      Path.of(directory),
      sys.env.getOrElse(
        "PLUTUS_BOOTSTRAP_MANIFEST_SHA256",
        fail("independent bootstrap manifest pin required")
      ),
      AdmissionProfile.PlutusV3
    )
    def fresh: IO[CoherentSequence.Runtime[IO]] = IO {
      get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    }.flatMap(context =>
      CoherentSequence
        .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
        .map(get(_))
    )

    test("stalled intersection times out and releases acquired peer without publishing") {
      TestControl
        .executeEmbed(for
          runtime <- fresh
          before <- runtime.snapshot
          initial = before.state.certificates.state.tip
          released <- Ref.of[IO, Int](0)
          peer = new Peer(initial, IO.raiseError(new AssertionError("unexpected pull"))):
            override def intersect(points: Vector[ChainSync.Point]) = IO.never[ChainSync.Point]
          result <- L.runWhen(runtime, resource(peer, released), initial, 1000, short)(
            IO.pure(false)
          )(_ => IO.unit)
          closes <- released.get
          _ = assertEquals(closes, 1)
          _ = assertEquals(result.peerOpens, result.peerCloses)
          _ = assertEquals(result.reason, "deadline")
          _ = assert(!result.inclusionReached)
          _ = assertEquals(result.snapshot.state.id, before.state.id)
          _ = assertEquals(result.snapshot.state.revision, before.state.revision)
        yield ())
        .unsafeToFuture()
    }

    test("false readiness with stalled next is bounded and releases the peer") {
      TestControl
        .executeEmbed(for
          runtime <- fresh
          before <- runtime.snapshot
          initial = before.state.certificates.state.tip
          released <- Ref.of[IO, Int](0)
          peer = new Peer(initial, IO.never[BoundedChainFollower.Event])
          result <- L.runWhen(runtime, resource(peer, released), initial, 1000, short)(
            IO.pure(false)
          )(_ => IO.unit)
          closes <- released.get
          _ = assertEquals(closes, 1)
          _ = assertEquals(result.reason, "deadline")
          _ = assert(!result.inclusionReached && result.observations.isEmpty)
          _ = assertEquals(result.snapshot.state.id, before.state.id)
        yield ())
        .unsafeToFuture()
    }

    test("external cancellation during a pull propagates and finalizes once") {
      TestControl
        .executeEmbed(for
          runtime <- fresh
          before <- runtime.snapshot
          initial = before.state.certificates.state.tip
          entered <- Deferred[IO, Unit]
          released <- Ref.of[IO, Int](0)
          returned <- Ref.of[IO, Boolean](false)
          peer = new Peer(
            initial,
            entered.complete(()).void *> IO.never[BoundedChainFollower.Event]
          )
          fiber <- L
            .runWhen(
              runtime,
              resource(peer, released),
              initial,
              1000,
              EphemeralStreaming.Limits(duration = 1.second)
            )(IO.pure(false))(_ => IO.unit)
            .flatTap(_ => returned.set(true))
            .start
          _ <- entered.get
          _ <- fiber.cancel
          outcome <- fiber.join
          closes <- released.get
          synthesized <- returned.get
          after <- runtime.snapshot
          _ = outcome match
            case Outcome.Canceled() => ()
            case other              => fail(s"external cancellation became $other")
          _ = assertEquals(closes, 1)
          _ = assert(!synthesized)
          _ = assertEquals(after.state.id, before.state.id)
          _ = assertEquals(after.state.revision, before.state.revision)
        yield ())
        .unsafeToFuture()
    }

    test("foreign intersection rejects before any pull and releases the peer") {
      (for
        runtime <- fresh
        before <- runtime.snapshot
        initial = before.state.certificates.state.tip
        released <- Ref.of[IO, Int](0)
        peer = new Peer(initial, IO.raiseError(new AssertionError("unexpected pull"))):
          override def intersect(points: Vector[ChainSync.Point]) = IO.pure(ChainSync.Point.Origin)
        result <- L.runWhen(
          runtime,
          resource(peer, released),
          initial,
          1000,
          EphemeralStreaming.Limits()
        )(IO.pure(false))(_ => IO.unit)
        closes <- released.get
        _ = assertEquals(closes, 1)
        _ = assert(result.reason.contains("foreign intersection"))
        _ = assert(!result.inclusionReached && result.observations.isEmpty)
        _ = assertEquals(result.snapshot.state.id, before.state.id)
      yield ()).unsafeToFuture()
    }

    test("initial rollback confirmation is state preserving and a second rollback rejects") {
      (for
        runtime <- fresh
        before <- runtime.snapshot
        initial = before.state.certificates.state.tip
        released <- Ref.of[IO, Int](0)
        pulls <- Ref.of[IO, Int](0)
        peer = new Peer(
          initial,
          pulls.getAndUpdate(_ + 1).flatMap { index =>
            if index > 1 then IO.raiseError(new AssertionError("third rollback must not be read"))
            else
              runtime.snapshot.map { current =>
                assertEquals(current.state.id, before.state.id)
                assertEquals(current.state.revision, before.state.revision)
                BoundedChainFollower.Event.Backward(wire(initial))
              }
          }
        )
        result <- L.runWhen(
          runtime,
          resource(peer, released),
          initial,
          1000,
          EphemeralStreaming.Limits()
        )(IO.pure(false))(_ => IO.unit)
        closes <- released.get
        count <- pulls.get
        _ = assertEquals(closes, 1)
        _ = assertEquals(count, 2)
        _ = assertEquals(result.initialIntersectionConfirmations, 1)
        _ = assert(result.reason.contains("rollback unsupported"))
        _ = assert(!result.inclusionReached && result.observations.isEmpty)
        _ = assertEquals(result.snapshot.state.id, before.state.id)
      yield ()).unsafeToFuture()
    }

    test("early readiness cannot report success without a published block") {
      (for
        runtime <- fresh
        before <- runtime.snapshot
        initial = before.state.certificates.state.tip
        released <- Ref.of[IO, Int](0)
        peer = new Peer(initial, IO.raiseError(new AssertionError("early-ready must not pull")))
        result <- L.runWhen(
          runtime,
          resource(peer, released),
          initial,
          1000,
          EphemeralStreaming.Limits()
        )(IO.pure(true))(_ => IO.unit)
        closes <- released.get
        _ = assertEquals(closes, 1)
        _ = assert(!result.inclusionReached && result.observations.isEmpty)
        _ = assert(result.reason.contains("no published network observation"))
        _ = assertEquals(result.snapshot.state.id, before.state.id)
      yield ()).unsafeToFuture()
    }

    test("epoch-boundary announcement that extends tip is rejected before block fetch") {
      (for
        runtime <- fresh
        before <- runtime.snapshot
        initial = before.state.certificates.state.tip
        released <- Ref.of[IO, Int](0)
        envelope <- IO {
          // Structural announcement only. Its original signature is deliberately not reused as
          // evidence: the same-epoch guard must reject before fetching or signature validation.
          def node(value: V) = Node(value, Bytes.empty)
          def array(n: Node) = n.value match
            case V.Arr(xs) => xs
            case _         => fail("fixture header array")
          val template =
            get(Cbor.decode(SyntheticBoundaryCompositionFixture.blocks.head.header.raw))
          val outer = array(template)
          val body = array(outer.head)
            .updated(0, node(V.UInt(initial.blockNo + 1)))
            .updated(1, node(V.UInt(1000)))
            .updated(2, node(V.ByteString(initial.hash)))
          val raw = get(Cbor.encode(V.Arr(outer.updated(0, node(V.Arr(body))))))
          val wireHeader = get(
            Cbor.encode(V.Arr(Vector(node(V.UInt(6)), node(V.Tag(24, node(V.ByteString(raw)))))))
          )
          val announced = get(ReferenceCaptureCommand.header(wireHeader))
          assertEquals(announced.parent, initial.hash)
          assertEquals(announced.blockNo, initial.blockNo + 1)
          assertEquals(announced.slot, BigInt(1000))
          wireHeader
        }
        peer = new Peer(initial, IO.pure(BoundedChainFollower.Event.Forward(envelope)))
        result <- L.runWhen(
          runtime,
          resource(peer, released),
          initial,
          1000,
          EphemeralStreaming.Limits()
        )(IO.pure(false))(_ => IO.unit)
        closes <- released.get
        _ = assertEquals(closes, 1)
        _ = assert(!result.inclusionReached && result.observations.isEmpty)
        _ = assert(result.reason.contains("within initial epoch"))
        _ = assertEquals(result.snapshot.state.id, before.state.id)
      yield ()).unsafeToFuture()
    }
  }

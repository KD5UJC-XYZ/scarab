// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Deferred, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.network.ChainSync
import lab.header.PraosCertificateState.Point
import lab.submission.*
import ReferenceJson.{field, string, uint}
import scala.concurrent.duration.*

/** Offline retained original blocks with fake peer; no reference node or live transaction. */
class PlutusServiceFollowSuite extends munit.FunSuite:
  private def get[A](v: Either[?, A]): A = v.fold(e => fail(e.toString), identity)
  private def wire(p: Point): ChainSync.Point =
    ChainSync.Point.Block(get(ChainSync.UInt64.from(p.slot)), p.hash)
  sys.env.get("PLUTUS_LIVE_BUNDLE").foreach { directory =>
    val root = Path.of(directory)
    lazy val manifest =
      val raw = PlutusResearchIO.read(
        Path.of(sys.env.getOrElse("PLUTUS_LIVE_MANIFEST", fail("manifest required"))),
        65536
      )
      assertEquals(
        PlutusResearchIO.sha(raw).hex,
        sys.env.getOrElse("PLUTUS_LIVE_MANIFEST_SHA256", fail("independent pin required"))
      )
      PlutusResearchIO.obj(field(ReferenceJson.parse(raw), "inputs"))
    def original(name: String): Bytes =
      assert(!Path.of(name).isAbsolute && !name.contains("..") && !name.contains("\\"))
      val descriptor = manifest.getOrElse(name, fail("unpinned retained original"))
      val raw = PlutusResearchIO.read(root.resolve(name), 1048576)
      assertEquals(PlutusResearchIO.sha(raw), PlutusResearchIO.hash(field(descriptor, "sha256")))
      assertEquals(BigInt(raw.size), uint(field(descriptor, "bytes")))
      raw
    lazy val manifestHash = PlutusResearchIO.sha(original("initial/adapter-inputs.json"))
    lazy val joined =
      PlutusResearchIO.initial(root.resolve("initial"), manifestHash.hex, AdmissionProfile.PlutusV3)
    lazy val originals =
      manifest.keys.filter(_.matches("originals/block-[0-9]{4}\\.cbor")).toVector.sorted.map {
        name =>
          val header = original(name.stripSuffix(".cbor") + "-header.cbor")
          (get(ReferenceCaptureCommand.header(header)), header, original(name))
      }
    def fresh = IO(get(SequenceInput.fromNativeDiagnostic(joined, joined.id))).flatMap(context =>
      CoherentSequence
        .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
        .map(get(_))
    )
    def peer(index: Ref[IO, Int], tail: IO[BoundedChainFollower.Event], fetches: Ref[IO, Int]) =
      new BoundedChainFollower.Peer[IO]:
        def intersect(points: Vector[ChainSync.Point]) = IO.pure(wire(joined.point))
        def next = index
          .getAndUpdate(_ + 1)
          .flatMap(n =>
            originals.lift(n).fold(tail)(x => IO.pure(BoundedChainFollower.Event.Forward(x._2)))
          )
        def fetch(point: ChainSync.Point) = fetches.update(_ + 1) *> IO.fromOption(
          originals.find(x => wire(Point(x._1.hash, x._1.slot, x._1.blockNo)) == point).map(_._3)
        )(new IllegalArgumentException("unknown retained fullpoint"))

    test(
      "service keeps owner and HTTP resource alive after actual inclusion until explicit cancellation"
    ) {
      (for
        out <- IO.blocking(Files.createTempDirectory("plutus-service-follow-"))
        tailEntered <- Deferred[IO, Unit]
        finished <- Deferred[IO, Unit]
        included <- Ref.of[IO, Int](0)
        count <- Ref.of[IO, Int](0)
        index <- Ref.of[IO, Int](0)
        fetches <- Ref.of[IO, Int](0)
        releases <- Ref.of[IO, Int](0)
        endedOwner <- PlutusResearchNode
          .resource(fresh, out.resolve("receipts"), joined.id, manifestHash)
          .use { node =>
            val observer = new AdmissionStateObserver[IO]:
              def changed(c: AdmissionStateChange) = included.update(_ + c.included.size)
              def closed = IO.unit
            node.http(Some(observer)).use { api =>
              for
                before <- node.owner.current
                p = peer(index, tailEntered.complete(()).void *> IO.never, fetches)
                fiber <- PlutusSameEpochFollow
                  .runUntil(
                    node.owner,
                    Resource.make(IO.pure(p))(_ => releases.update(_ + 1)),
                    joined.point,
                    1000,
                    EphemeralStreaming.Limits()
                  )(IO.pure(false))(o =>
                    if o.applied.nonEmpty then count.update(_ + 1) else IO.unit
                  )
                  .guarantee(finished.complete(()).void)
                  .start
                _ <- tailEntered.get.timeout(20.seconds)
                n <- included.get
                published <- count.get
                _ = assert(n > 0, "retained actual inclusion observed")
                _ = assert(published >= 2, "repeated follower publication")
                done <- finished.tryGet
                _ = assertEquals(done, None)
                after <- node.owner.current
                pool <- node.service.snapshot
                _ = assertEquals(after.pin.ownerId, before.pin.ownerId)
                _ = assert(after.pin.generation > before.pin.generation && !pool.closed)
                _ <- IO.blocking {
                  val socket = new java.net.Socket("127.0.0.1", api.port); socket.close()
                }
                _ <- fiber.cancel
                released <- releases.get
                _ = assertEquals(released, 1)
              yield node.owner
            }
          }
        closed <- endedOwner.current.attempt
        _ = assert(closed.isLeft)
      yield ()).unsafeToFuture()
    }
    test(
      "service block budget stops at a verified publication independently of transaction identity"
    ) {
      (for
        runtime <- fresh
        index <- Ref.of[IO, Int](0)
        fetches <- Ref.of[IO, Int](0)
        count <- Ref.of[IO, Int](0)
        p = peer(index, IO.raiseError(new AssertionError("pulled beyond block budget")), fetches)
        result <- PlutusSameEpochFollow.runUntil(
          runtime,
          Resource.pure(p),
          joined.point,
          1000,
          EphemeralStreaming.Limits(maxBlocks = 1)
        )(count.get.map(_ >= 1))(o => if o.applied.nonEmpty then count.update(_ + 1) else IO.unit)
        _ = assertEquals(result.reason, "blockLimit")
        _ = assertEquals(result.observations.size, 1)
        pulls <- index.get
        _ = assertEquals(pulls, 1)
      yield ()).unsafeToFuture()
    }
    test(
      "service epoch announcement is typed refusal before fetch and leaves original state unchanged"
    ) {
      (for
        runtime <- fresh
        before <- runtime.snapshot
        fetches <- Ref.of[IO, Int](0)
        envelope <- IO {
          def node(v: V) = Node(v, Bytes.empty)
          def arr(n: Node) = n.value match
            case V.Arr(xs) => xs
            case _         => fail("header array")
          val h = arr(get(Cbor.decode(originals.head._1.raw)))
          val b = arr(h.head)
            .updated(0, node(V.UInt(joined.point.blockNo + 1)))
            .updated(1, node(V.UInt(1000)))
            .updated(2, node(V.ByteString(joined.point.hash)))
          val raw = get(Cbor.encode(V.Arr(h.updated(0, node(V.Arr(b))))))
          get(Cbor.encode(V.Arr(Vector(node(V.UInt(6)), node(V.Tag(24, node(V.ByteString(raw))))))))
        }
        p = new BoundedChainFollower.Peer[IO]:
          def intersect(points: Vector[ChainSync.Point]) = IO.pure(wire(joined.point))
          def next = IO.pure(BoundedChainFollower.Event.Forward(envelope))
          def fetch(point: ChainSync.Point) = fetches.update(_ + 1).as(Bytes.empty)
        result <- PlutusSameEpochFollow.runUntil(
          runtime,
          Resource.pure(p),
          joined.point,
          1000,
          EphemeralStreaming.Limits()
        )(IO.pure(false))(_ => IO.unit)
        n <- fetches.get
        _ = assertEquals(n, 0)
        _ = assertEquals(result.reason, "epochBoundaryRefused")
        _ = assertEquals(result.snapshot.state.id, before.state.id)
      yield ()).unsafeToFuture()
    }
  }

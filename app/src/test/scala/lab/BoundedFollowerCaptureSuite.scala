// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync
import BoundedChainFollower.*

class BoundedFollowerCaptureSuite extends munit.FunSuite:
  private def n(v: Value) = Node(v, Bytes.empty)
  private def arr(v: Value*) = Value.Arr(v.toVector.map(n))
  private def u(v: Int) = Value.UInt(BigInt(v))
  private def b(size: Int) = Value.ByteString(Bytes(Vector.fill(size)(0.toByte)))
  private def encode(v: Value) = Cbor.encode(v).toOption.get
  private val anchor =
    ChainSync.Point.Block(ChainSync.UInt64.Zero, Bytes(Vector.fill(32)(0.toByte)))
  private def point(o: Original) =
    val h = ReferenceCaptureCommand.header(o.envelope).toOption.get
    ChainSync.Point.Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
  private def block(previous: ChainSync.Point, number: Int): Original =
    val hash = previous.asInstanceOf[ChainSync.Point.Block].hash
    val components = Vector(arr(), arr(), Value.Map(Vector.empty), arr())
    val digest =
      Blake2b.hash256.hash(Bytes(components.flatMap(v => Blake2b.hash256.hash(encode(v)).value)))
    val header = arr(
      arr(
        u(number),
        u(number),
        Value.ByteString(hash),
        b(32),
        b(32),
        arr(b(64), b(80)),
        u(4),
        Value.ByteString(digest),
        arr(b(32), u(0), u(0), b(64)),
        arr(u(11), u(2))
      ),
      b(448)
    )
    Original(
      encode(arr(u(6), Value.Tag(24, n(Value.ByteString(encode(header)))))),
      encode(arr(u(7), Value.Arr((header +: components).map(n))))
    )
  private val originals = (1 to 4).foldLeft(Vector.empty[Original])((found, i) =>
    found :+ block(found.lastOption.fold(anchor)(point), i)
  )
  private val initial = checked(anchor, Vector.empty).toOption.get

  private def peers(closes: Ref[IO, Int]): Resource[IO, Peer[IO]] =
    Resource.make(Ref.of[IO, Int](0))(_ => closes.update(_ + 1)).map { cursor =>
      new Peer[IO]:
        def intersect(points: Vector[ChainSync.Point]) =
          cursor
            .set(
              if points.head == anchor then 0 else originals.indexWhere(point(_) == points.head) + 1
            )
            .as(points.head)
        def next = cursor
          .getAndUpdate(_ + 1)
          .flatMap(i =>
            originals
              .lift(i)
              .fold(IO.raiseError[Event](new RuntimeException("end")))(o =>
                IO.pure(Event.Forward(o.envelope))
              )
          )
        def fetch(p: ChainSync.Point) = IO.pure(originals.find(point(_) == p).get.block)
    }

  test("two-phase acquisition rechecks checkpoint and injects exactly one owned reconnect") {
    Ref
      .of[IO, Int](0)
      .flatMap { closes =>
        BoundedFollowerCapture.capture(initial, peers(closes)).flatMap { evidence =>
          assert(evidence.complete)
          assertEquals(evidence.first.checkpoint.originals, originals.take(2))
          assertEquals(evidence.resumed.checkpoint.originals, originals)
          assertEquals(
            evidence.offered,
            Vector.fill(2)(Vector(point(originals(1)), point(originals(0)), anchor))
          )
          assertEquals(
            evidence.selected,
            Vector(0 -> point(originals(1)), 1 -> point(originals(1)))
          )
          assert(evidence.retainedPrefix)
          assert(evidence.injected)
          val lines = BoundedFollowerCapture.records(evidence)
          assertEquals(lines.count(_.contains("acquisition-original")), 6)
          assert(lines.last.contains("\"ledgerValidated\":false"))
          closes.get.map(n => assertEquals(n, 3))
        }
      }
      .unsafeToFuture()
  }
  test("incomplete initial capture never starts resume exercise") {
    val failing = Resource.pure[IO, Peer[IO]](new Peer[IO]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(anchor)
      def next = IO.raiseError[Event](new RuntimeException("disconnected"))
      def fetch(p: ChainSync.Point) = IO.raiseError[Bytes](new AssertionError("not reached")))
    BoundedFollowerCapture
      .capture(initial, failing)
      .map { evidence =>
        assert(!evidence.complete)
        assert(!evidence.injected)
        assert(evidence.offered.isEmpty)
        assert(evidence.selected.isEmpty)
        assertEquals(evidence.first.reason, "peerFailure")
      }
      .unsafeToFuture()
  }
  test("anchor fallback is explicit in selected-point evidence and subsequent offer") {
    Ref
      .of[IO, Int](0)
      .flatMap { closes =>
        val anchored = peers(closes).map { underlying =>
          new Peer[IO]:
            def intersect(points: Vector[ChainSync.Point]) = underlying.intersect(Vector(anchor))
            def next = underlying.next
            def fetch(p: ChainSync.Point) = underlying.fetch(p)
        }
        BoundedFollowerCapture.capture(initial, anchored).map { evidence =>
          assert(evidence.complete)
          assertEquals(evidence.selected, Vector(0 -> anchor, 1 -> anchor))
          assertEquals(evidence.offered(1), Vector(anchor))
          assert(evidence.retainedPrefix) // Same bytes reacquired; selections expose the fallback.
          assertEquals(
            BoundedFollowerCapture
              .records(evidence)
              .count(_.contains("resume-intersection-selected")),
            2
          )
        }
      }
      .unsafeToFuture()
  }
  test("invalid ranges reject before allocating peers") {
    val fail = Resource.eval(IO.raiseError[Peer[IO]](new AssertionError("not reached")))
    List((0, 4), (2, 2), (2, 9))
      .traverse_ { (first, total) =>
        BoundedFollowerCapture
          .capture(initial, fail, first, total)
          .attempt
          .map(r => assert(r.swap.toOption.get.isInstanceOf[Invalid]))
      }
      .unsafeToFuture()
  }
  test("CLI accepts only bounded counts and numeric loopback endpoint arguments") {
    val base = List("3001", "1082026", "1", "00" * 32)
    assert(BoundedFollowerCapture.options(base ++ List("2", "4")).isRight)
    List(List("0", "4"), List("4", "4"), List("1", "9"), List("x", "4")).foreach(counts =>
      assert(BoundedFollowerCapture.options(base ++ counts).isLeft)
    )
    assert(
      BoundedFollowerCapture
        .options(List("example.org", "1082026", "1", "00" * 32, "2", "4"))
        .isLeft
    )
  }

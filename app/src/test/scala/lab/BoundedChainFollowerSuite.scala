// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource, Deferred}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.{ChainSync, ScriptedByteTransport}
import lab.fetcher.{Fetch, FetchSpec, Limits, Record, SegmentStore, Snapshot}
import BoundedChainFollower.*
import scala.concurrent.duration.*

class BoundedChainFollowerSuite extends munit.FunSuite:
  private def n(v: Value) = Node(v, Bytes.empty)
  private def arr(v: Value*) = Value.Arr(v.toVector.map(n))
  private def u(v: Int) = Value.UInt(BigInt(v))
  private def b(size: Int, value: Int = 0) =
    Value.ByteString(Bytes(Vector.fill(size)(value.toByte)))
  private def encode(v: Value) = Cbor.encode(v).toOption.get
  private val anchor =
    ChainSync.Point.Block(ChainSync.UInt64.Zero, Bytes(Vector.fill(32)(0.toByte)))
  private def block(previous: ChainSync.Point, slot: Int, number: Int, salt: Int = 0): Original =
    val hash = previous.asInstanceOf[ChainSync.Point.Block].hash
    val components = Vector(arr(), arr(), Value.Map(Vector.empty), arr())
    val digest =
      Blake2b.hash256.hash(Bytes(components.flatMap(v => Blake2b.hash256.hash(encode(v)).value)))
    val header = arr(
      arr(
        u(number),
        u(slot),
        Value.ByteString(hash),
        b(32, salt),
        b(32),
        arr(b(64), b(80)),
        u(4),
        Value.ByteString(digest),
        arr(b(32), u(0), u(0), b(64)),
        arr(u(11), u(2))
      ),
      b(448)
    )
    val rawHeader = encode(header)
    Original(
      encode(arr(u(6), Value.Tag(24, n(Value.ByteString(rawHeader))))),
      encode(arr(u(7), Value.Arr((header +: components).map(n))))
    )
  private def point(o: Original) =
    val h = ReferenceCaptureCommand.header(o.envelope).toOption.get
    ChainSync.Point.Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
  private val a = block(anchor, 1, 1)
  private val b2 = block(point(a), 2, 2)
  private val c = block(point(b2), 3, 3)
  private val fork = block(point(a), 4, 2, 1)
  private val forkEnd = block(point(fork), 5, 3, 1)
  private val all = Vector(a, b2, c, fork, forkEnd)
  private val empty = checked(anchor, Vector.empty).toOption.get

  private def scripted(
      events: List[Event],
      intersection: ChainSync.Point = anchor,
      fetcher: ChainSync.Point => IO[Bytes] = p => IO.pure(all.find(point(_) == p).get.block)
  ): Resource[IO, Peer[IO]] =
    Resource.eval(Ref.of[IO, List[Event]](events)).map { queue =>
      new Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point]) = IO.pure(intersection)
        def next = queue
          .modify {
            case head :: tail => (tail, Some(head))
            case Nil          => (Nil, None)
          }
          .flatMap(_.fold(IO.raiseError[Event](new RuntimeException("disconnect")))(IO.pure))
        def fetch(p: ChainSync.Point) = fetcher(p)
    }
  private def forwards(os: Original*) = os.toList.map(o => Event.Forward(o.envelope))
  private def run(
      peer: Resource[IO, Peer[IO]],
      policy: Policy = Policy(target = 3, reconnects = 0),
      initial: Checkpoint = empty
  ): IO[Outcome] = resource(initial, peer, policy).use(_.run)

  test("bounded branch preserves exact originals and exports existing source/store composition") {
    run(scripted(forwards(a, b2, c)))
      .flatMap { out =>
        assertEquals(out.reason, "targetReached")
        assertEquals(out.checkpoint.originals, Vector(a, b2, c))
        val source = out.checkpoint.source[IO]
        val limits = Limits.checked(3, 1048576, 4194304, 4194304, 16, 10).toOption.get
        val spec =
          FetchSpec.checked(source.identity.predecessor, Some(3), None, limits).toOption.get
        Ref.of[IO, Vector[Record]](Vector.empty).flatMap { records =>
          val store = new SegmentStore[IO]:
            def selectionIdentity = spec.identity
            def sourceIdentity = source.identity
            def snapshot = records.get.map(Snapshot(_, "test-only"))
            def append(block: lab.chain.CardanoBlockIndex.IndexedBlock) =
              records.update(_ :+ Record.of(block)) *> snapshot
          Fetch
            .run(spec, source, store)
            .map(result => assertEquals(result.snapshot.records.size, 3))
        }
      }
      .unsafeToFuture()
  }
  test("rollback discards abandoned branch before admitting replacement") {
    run(scripted(forwards(a, b2) ++ List(Event.Backward(point(a))) ++ forwards(fork, forkEnd)))
      .map { out =>
        assertEquals(out.reason, "targetReached")
        assertEquals(out.checkpoint.originals, Vector(a, fork, forkEnd))
        assertNotEquals(
          out.checkpoint.source[IO].identity.digest,
          checked(anchor, Vector(a, b2, c)).toOption.get.source[IO].identity.digest
        )
      }
      .unsafeToFuture()
  }
  test("disconnect reconnects with checked candidates and can intersect an older retained point") {
    Ref
      .of[IO, Int](0)
      .flatMap { opens =>
        val peers = Resource.eval(opens.getAndUpdate(_ + 1)).flatMap { i =>
          if i == 0 then scripted(forwards(a, b2))
          else
            scripted(forwards(fork, forkEnd), point(a)).map { underlying =>
              new Peer[IO]:
                def intersect(candidates: Vector[ChainSync.Point]) =
                  IO(assertEquals(candidates, Vector(point(b2), point(a), anchor))) *> underlying
                    .intersect(candidates)
                def next = underlying.next
                def fetch(p: ChainSync.Point) = underlying.fetch(p)
            }
        }
        run(peers, Policy(target = 3, reconnects = 1))
          .map(out => assertEquals(out.checkpoint.originals, Vector(a, fork, forkEnd)))
      }
      .unsafeToFuture()
  }
  test("resume from explicit checked originals fetches only successors") {
    val restored = checked(anchor, Vector(a, b2)).toOption.get
    run(scripted(forwards(c), point(b2)), initial = restored)
      .map { out =>
        assertEquals(out.checkpoint.originals, Vector(a, b2, c))
        assertEquals(out.events, 1)
      }
      .unsafeToFuture()
  }
  test("stale checkpoint, original mutations and excessive range reject") {
    assert(checked(anchor, Vector(b2)).isLeft)
    assert(checked(anchor, Vector(a, a)).isLeft)
    assert(checked(anchor, Vector(a.copy(block = b2.block))).isLeft)
    assert(checked(anchor, Vector(a.copy(envelope = b2.envelope))).isLeft)
    assert(checked(anchor, Vector.fill(9)(a)).isLeft)
    assert(checked(ChainSync.Point.Origin, Vector.empty).isLeft)
    val wrongNumber = block(point(a), 2, 7)
    assert(checked(anchor, Vector(a, wrongNumber)).isLeft)
    (0 until a.block.size by 73).foreach { index =>
      assert(checked(anchor, Vector(a.copy(block = Bytes(a.block.value.take(index))))).isLeft)
    }
  }
  test("duplicate announcement rejected without a second fetch") {
    Ref
      .of[IO, Int](0)
      .flatMap { fetched =>
        run(scripted(forwards(a, a), fetcher = _ => fetched.update(_ + 1).as(a.block))).flatMap {
          out =>
            assertEquals(out.checkpoint.originals, Vector(a))
            fetched.get.map(n => assertEquals(n, 1))
        }
      }
      .unsafeToFuture()
  }
  test("unknown rollback/intersection fails closed with retained checkpoint") {
    val unknown = point(forkEnd)
    List(
      run(scripted(forwards(a) :+ Event.Backward(unknown))),
      run(scripted(Nil, unknown), initial = checked(anchor, Vector(a)).toOption.get)
    ).traverse_(io =>
      io.map { out =>
        assertEquals(out.reason, "rollback outside retained acquisition window")
        assertEquals(out.checkpoint.originals, Vector(a))
      }
    ).unsafeToFuture()
  }
  test("incomplete fetch and mismatched block never advance checkpoint") {
    List(
      scripted(
        forwards(a),
        fetcher = _ => IO.raiseError(new RuntimeException("EOF before BatchDone"))
      ),
      scripted(forwards(a), fetcher = _ => IO.pure(b2.block))
    ).traverse_(peer => run(peer).map(out => assertEquals(out.checkpoint.size, 0))).unsafeToFuture()
  }
  test("await, rollback loops and input are bounded") {
    List(
      run(scripted(List.fill(9)(Event.Await)), Policy(target = 1, maxEvents = 3, reconnects = 0)),
      run(
        scripted(List.fill(9)(Event.Backward(anchor))),
        Policy(target = 1, maxEvents = 3, reconnects = 0)
      ),
      run(scripted(forwards(a)), Policy(target = 1, maxBytes = 1, reconnects = 0))
    ).traverse_(io => io.map(out => assertEquals(out.checkpoint.size, 0))).unsafeToFuture()
  }
  test("deadline closes owned peer and preserves completed acquisition") {
    Ref
      .of[IO, Boolean](false)
      .flatMap { closed =>
        val peer = Resource
          .make(IO.unit)(_ => closed.set(true))
          .flatMap(_ => scripted(forwards(a), fetcher = _ => IO.never))
        run(peer, Policy(target = 1, duration = 100.millis, reconnects = 0)).flatMap { out =>
          assertEquals(out.reason, "timeBudget")
          assertEquals(out.checkpoint.size, 0)
          closed.get.map(assert(_))
        }
      }
      .unsafeToFuture()
  }
  test("cancellation releases peer and leaves only checked progress") {
    (Deferred[IO, Unit], Ref.of[IO, Boolean](false)).tupled
      .flatMap { (started, closed) =>
        val peer = Resource
          .make(IO.unit)(_ => closed.set(true))
          .flatMap(_ =>
            scripted(
              forwards(a, b2),
              fetcher =
                p => if p == point(a) then IO.pure(a.block) else started.complete(()) *> IO.never
            )
          )
        resource(empty, peer, Policy(target = 3)).use { follower =>
          for
            fiber <- follower.run.start
            _ <- started.get
            _ <- fiber.cancel
            state <- follower.checkpoint
            done <- closed.get
          yield
            assertEquals(state.originals, Vector(a))
            assert(done)
        }
      }
      .unsafeToFuture()
  }

  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private def frame(protocol: Int, payload: Bytes, responder: Boolean = true): Bytes =
    val p = protocol | (if responder then 32768 else 0)
    Bytes(
      Vector(0, 0, 0, 0, p >> 8, p, payload.size >> 8, payload.size).map(_.toByte) ++ payload.value
    )
  private def wirePoint(p: ChainSync.Point): Value = p match
    case ChainSync.Point.Block(slot, hash) => arr(Value.UInt(slot.value), Value.ByteString(hash))
    case _                                 => arr()
  private def bytePeer(end: String): Resource[IO, Peer[IO]] =
    import ScriptedByteTransport.Step.*
    val proposal = Expect(frame(0, hex("8200a10e8402f500f4"), false))
    val accept = Receive(frame(0, hex("83010e8402f500f4")))
    val tip = arr(wirePoint(point(a)), u(1))
    val chain = Vector(
      proposal,
      accept,
      Expect(
        frame(
          2,
          Bytes(hex("82049f").value ++ encode(wirePoint(anchor)).value ++ hex("ff").value),
          false
        )
      ),
      Receive(frame(2, encode(arr(u(5), wirePoint(anchor), tip)))),
      Expect(frame(2, hex("8100"), false)),
      Receive(frame(2, hex("8101"))),
      Receive(frame(2, encode(arr(u(2), Cbor.decode(a.envelope).toOption.get.value, tip))))
    )
    val one = encode(arr(u(4), Value.Tag(24, n(Value.ByteString(a.block)))))
    val terminal = end match
      case "complete"  => hex("8105")
      case "duplicate" => Bytes(one.value ++ hex("8105").value)
      case "suffix"    => hex("81058105")
      case _           => Bytes.empty
    val fetch = Vector(
      proposal,
      accept,
      Expect(frame(3, encode(arr(u(0), wirePoint(point(a)), wirePoint(point(a)))), false)),
      Receive(frame(3, Bytes(hex("8102").value ++ one.value ++ terminal.value)))
    ) ++
      (if end == "complete" then Vector(Expect(frame(3, hex("8101"), false))) else Vector.empty)
    val connection = Resource.eval(Ref.of[IO, Int](0)).flatMap { opened =>
      // This resource encloses both protocol resources so each use selects the next script.
      Resource.pure[IO, Resource[IO, lab.network.ByteTransport[IO]]](
        Resource
          .eval(opened.getAndUpdate(_ + 1))
          .flatMap(i => ScriptedByteTransport.resource[IO](if i == 0 then chain else fetch))
      )
    }
    connection.flatMap(c => sessions(c, 2))

  test("real session adapters honor AwaitReply and exact requested block/BatchDone") {
    bytePeer("complete")
      .use(p => p.intersect(Vector(anchor)) *> p.next *> p.next *> p.fetch(point(a)))
      .flatMap(_ => run(bytePeer("complete"), Policy(target = 1, reconnects = 0)))
      .map { out =>
        assertEquals(out.reason, "targetReached")
        assertEquals(out.checkpoint.originals, Vector(a))
        assertEquals(out.events, 2)
      }
      .unsafeToFuture()
  }
  test("real session adapters reject EOF, extra block and buffered suffix without committing") {
    List("incomplete", "duplicate", "suffix")
      .traverse_(end =>
        run(bytePeer(end), Policy(target = 1, reconnects = 0)).map { out =>
          assertNotEquals(out.reason, "targetReached")
          assertEquals(out.checkpoint.size, 0)
        }
      )
      .unsafeToFuture()
  }

  test("all supported range sizes preserve linked original bytes") {
    (1 to 8).toList
      .traverse_ { count =>
        val originals = (1 to count).foldLeft(Vector.empty[Original]) { (found, i) =>
          found :+ block(found.lastOption.fold(anchor)(point), i * 2, i)
        }
        run(
          scripted(
            forwards(originals*),
            fetcher = p => IO.pure(originals.find(point(_) == p).get.block)
          ),
          Policy(target = count, reconnects = 0)
        ).map { out =>
          assertEquals(out.reason, "targetReached")
          assertEquals(out.checkpoint.originals, originals)
        }
      }
      .unsafeToFuture()
  }
  test("retry count bounds failed connection acquisition") {
    Ref
      .of[IO, Int](0)
      .flatMap { opened =>
        val peer = Resource.eval(
          opened.update(_ + 1) *> IO.raiseError[Peer[IO]](new RuntimeException("offline"))
        )
        run(peer, Policy(target = 1, reconnects = 2)).flatMap(out =>
          opened.get.map { n =>
            assertEquals(n, 3)
            assertEquals(out.reason, "peerFailure")
            assertEquals(out.events, 0)
          }
        )
      }
      .unsafeToFuture()
  }
  test("invalid policy rejects before opening any peer") {
    val peer = Resource.eval(IO.raiseError[Peer[IO]](new AssertionError("must not open")))
    List(
      Policy(target = 0),
      Policy(target = 9),
      Policy(maxEvents = 257),
      Policy(reconnects = 5),
      Policy(duration = 121.seconds),
      Policy(maxBytes = 0)
    ).traverse_(p =>
      resource(empty, peer, p)
        .use(_.run)
        .attempt
        .map(result => assert(result.swap.toOption.get.isInstanceOf[Invalid]))
    ).unsafeToFuture()
  }
  test("canceling a queued run does not cancel the active owner") {
    (Deferred[IO, Unit], Deferred[IO, Unit], Ref.of[IO, Int](0)).tupled
      .flatMap { (started, release, closed) =>
        val peer = Resource
          .make(IO.unit)(_ => closed.update(_ + 1))
          .flatMap(_ =>
            scripted(forwards(a), fetcher = _ => started.complete(()) *> release.get.as(a.block))
          )
        resource(empty, peer, Policy(target = 1)).use { follower =>
          for
            first <- follower.run.start
            _ <- started.get
            second <- follower.run.start
            _ <- second.cancel
            before <- closed.get
            _ <- release.complete(())
            out <- first.joinWithNever
            after <- closed.get
          yield
            assertEquals(before, 0)
            assertEquals(after, 1)
            assertEquals(out.reason, "targetReached")
        }
      }
      .unsafeToFuture()
  }

// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** Independently supplied literal SDUs; no expected wire bytes use the codec under test. */
class KeepAliveTransportSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get
  private val ping = hex("0000000000080003820000")
  private val pong = hex("0000000080080003820100")
  private val done = hex("00000000000800028102")
  private val bf = hex("00000000800300028105")
  private val cfg = BlockFetchSession.Config()
  private val policy = KeepAliveTransport.Policy()
  private final case class Peer(
      transport: ByteTransport[IO],
      incoming: Queue[IO, Bytes],
      writes: Ref[IO, Vector[Bytes]],
      closes: Ref[IO, Int],
      reading: Ref[IO, Int],
      maxReading: Ref[IO, Int]
  )
  private def peer: Resource[IO, Peer] = Resource.eval {
    for
      incoming <- Queue.bounded[IO, Bytes](32)
      writes <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      closes <- Ref.of[IO, Int](0)
      reading <- Ref.of[IO, Int](0)
      maxReading <- Ref.of[IO, Int](0)
      closed <- Deferred[IO, Unit]
    yield Peer(
      new ByteTransport[IO]:
        def read = (reading.updateAndGet(_ + 1).flatMap(n => maxReading.update(_ max n)) *>
          IO.race(closed.get, incoming.take).map(_.toOption)).guarantee(reading.update(_ - 1))
        def write(bytes: Bytes) = closed.tryGet.flatMap {
          case Some(_) => IO.raiseError(new IllegalStateException("write after close"))
          case None    => writes.update(_ :+ bytes)
        }
        def close = closes.update(_ + 1) *> closed.complete(()).void
        def isClosed = closed.tryGet.map(_.nonEmpty)
      ,
      incoming,
      writes,
      closes,
      reading,
      maxReading
    )
  }
  private def use[A](
      f: (Peer, KeepAliveTransport[IO]) => IO[A],
      p: KeepAliveTransport.Policy = policy
  ): IO[A] = peer.use { peer =>
    KeepAliveTransport
      .resource(peer.transport, cfg, p, IO.pure(KeepAlive.Cookie.Zero))
      .use(t => f(peer, t))
  }
  private def virtual[A](io: IO[A]) = TestControl.executeEmbed(io).unsafeToFuture()

  test(
    "immediate ping, routed BF remains buffered, matched response permits legal Done and one reader"
  ) {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        first <- p.writes.get
        _ = assertEquals(first, Vector(ping))
        _ <- p.incoming.offer(Bytes(bf.value ++ pong.value))
        _ <- IO.sleep(1.millis)
        _ <- t.freeze *> t.finishKeepAlive
        block <- t.blockFetch.read
        _ = assertEquals(block, Some(bf))
        _ <- t.requireBlockFetchDrained *> t.barrier
        writes <- p.writes.get
        _ = assertEquals(writes, Vector(ping, done))
        max <- p.maxReading.get
        _ = assertEquals(max, 1)
      yield ()
    })
  }

  test("fragmented reply and BF SDU retain exact bytes") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- (bf.value ++ pong.value).traverse_(b => p.incoming.offer(Bytes(Vector(b))))
        _ <- t.freeze *> t.finishKeepAlive
        result <- t.blockFetch.read
        _ = assertEquals(result, Some(bf))
        _ <- t.barrier
      yield ()
    })
  }

  test("KeepAlive continues while BF consumer pauses within queue capacity") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(Bytes(bf.value ++ pong.value))
        _ <- IO.sleep(11.seconds)
        writes <- p.writes.get
        _ = assertEquals(writes, Vector(ping, ping))
        _ <- p.incoming.offer(pong)
        _ <- t.freeze *> t.finishKeepAlive
        _ <- t.blockFetch.read *> t.barrier
      yield ()
    })
  }

  test("pending response at finish is awaited before Done, with bounded failure") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate *> t.freeze
        result <- t.finishKeepAlive.attempt
        _ = assert(result.left.toOption.exists(_.getMessage.contains("finish deadline")))
        writes <- p.writes.get
        _ = assertEquals(writes, Vector(ping))
      yield ()
    })
  }

  test("wrong cookie, duplicate, wrong direction and unknown routes fail closed") {
    virtual(
      Vector(
        "0000000080080003820101",
        "0000000080080006820100820100",
        "0000000000080003820100",
        "00000000800200028105"
      ).traverse_ { wire =>
        use { (p, t) =>
          for
            _ <- t.activate
            _ <- p.incoming.offer(hex(wire))
            result <- (IO.sleep(1.millis) *> t.freeze *> t.finishKeepAlive).attempt
            _ = assert(result.isLeft, wire)
            writes <- p.writes.get
            _ = assert(!writes.contains(done), wire)
          yield ()
        }
      }
    )
  }

  test("KA byte and frame queue caps reject without blocking the sole reader") {
    virtual(
      use(
        { (p, t) =>
          for
            _ <- t.activate
            _ <- p.incoming.offer(Bytes(pong.value ++ pong.value))
            result <- (IO.sleep(1.millis) *> t.freeze).attempt
            _ = assert(result.left.toOption.exists(_.getMessage.contains("queue full")))
          yield ()
        },
        policy.copy(keepAliveQueueFrames = 1)
      )
    )
  }

  test("BF full queue fails fast while consumer paused") {
    val small = cfg.copy(maxFrames = 1)
    virtual(peer.use { p =>
      KeepAliveTransport.resource(p.transport, small, policy, IO.pure(KeepAlive.Cookie.Zero)).use {
        t =>
          for
            _ <- t.activate
            _ <- p.incoming.offer(bf) *> IO.sleep(1.millis) *> p.incoming.offer(bf)
            result <- (IO.sleep(1.millis) *> t.freeze).attempt
            _ = assert(result.left.toOption.exists(_.getMessage.contains("queue full")))
          yield ()
      }
    })
  }

  test("BF suffix and partial mux header prevent final success") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(Bytes(pong.value ++ Vector(0.toByte)))
        _ <- t.freeze *> t.finishKeepAlive
        result <- t.barrier.attempt
        _ = assert(result.isLeft)
      yield ()
    })
  }

  test("KA response deadline is independent of BF traffic") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(bf) *> IO.sleep(11.seconds)
        result <- t.blockFetch.read.attempt
        _ = assert(result.left.toOption.exists(_.getMessage.contains("response deadline")))
      yield ()
    })
  }

  test("KA byte queue overflow is independent of frame count") {
    virtual(
      use(
        { (p, t) =>
          for
            _ <- t.activate
            _ <- p.incoming.offer(pong)
            result <- (IO.sleep(1.millis) *> t.freeze).attempt
            _ = assert(result.left.toOption.exists(_.getMessage.contains("queue full")))
          yield ()
        },
        policy.copy(keepAliveQueueBytes = 2)
      )
    )
  }

  test("handshake and both routes share actual outgoing frame and byte totals") {
    virtual(use { (p, t) =>
      val handshake = hex("00000000000000028100")
      for
        _ <- t.blockFetch.write(handshake)
        _ <- t.activate
        _ <- p.incoming.offer(pong)
        _ <- t.freeze *> t.finishKeepAlive
        metrics <- t.metrics
        _ = assertEquals(metrics.outgoingFrames, 3L)
        _ = assertEquals(metrics.outgoingWireBytes, (handshake.size + ping.size + done.size).toLong)
        _ = assertEquals(metrics.incomingWireBytes, pong.size.toLong)
        _ = assert(metrics.peakRoutedIngressBytes <= policy.routedIngressBytes(cfg))
        _ <- t.barrier
      yield ()
    })
  }

  test("outgoing frame cap includes handshake before initial KeepAlive") {
    virtual(
      use(
        { (_, t) =>
          for
            _ <- t.blockFetch.write(hex("00000000000000028100"))
            result <- t.activate.attempt
            _ = assert(result.left.toOption.exists(_.getMessage.contains("outgoing")))
          yield ()
        },
        policy.copy(maxOutgoingFrames = 1)
      )
    )
  }

  test("outgoing byte cap includes header and initial ping") {
    virtual(
      use(
        { (_, t) =>
          t.activate.attempt.map(result =>
            assert(result.left.toOption.exists(_.getMessage.contains("outgoing")))
          )
        },
        policy.copy(maxOutgoingBytes = 10)
      )
    )
  }

  test("cancellation closes physical transport once and joins reader before resource release") {
    virtual(peer.use { p =>
      for
        entered <- Deferred[IO, Unit]
        fiber <- KeepAliveTransport
          .resource(p.transport, cfg, policy, IO.pure(KeepAlive.Cookie.Zero))
          .use(t => t.activate *> entered.complete(()).void *> IO.never)
          .start
        _ <- entered.get *> fiber.cancel
        closed <- p.closes.get
        reading <- p.reading.get
        before <- p.writes.get
        _ <- IO.sleep(20.seconds)
        after <- p.writes.get
        _ = assertEquals(closed, 1)
        _ = assertEquals(reading, 0)
        _ = assertEquals(after, before)
      yield ()
    })
  }

  test("blocked admitted writer cancels and waiting producer cancellation does not poison it") {
    virtual(
      peer.use { p =>
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          first <- Ref.of[IO, Boolean](true)
          raw = new ByteTransport[IO]:
            def read = p.transport.read
            def close = p.transport.close
            def isClosed = p.transport.isClosed
            def write(bytes: Bytes) =
              first.getAndSet(false).flatMap { initial =>
                (if initial then entered.complete(()).void *> release.get
                 else IO.unit) *> p.transport.write(bytes)
              }
          _ <- KeepAliveTransport.resource(raw, cfg, policy, IO.pure(KeepAlive.Cookie.Zero)).use {
            t =>
              val frame = hex("00000000000300028101")
              for
                admitted <- t.blockFetch.write(frame).start
                _ <- entered.get
                waiting <- t.blockFetch.write(frame).start
                _ <- IO.sleep(1.millis) *> waiting.cancel
                closed <- p.transport.isClosed
                _ = assert(!closed)
                _ <- admitted.cancel
                after <- p.transport.isClosed
                _ = assert(after)
                _ <- release.complete(()).void *> IO.sleep(1.millis)
                writes <- p.writes.get
                _ = assertEquals(writes, Vector.empty)
              yield ()
          }
          reading <- p.reading.get
          _ = assertEquals(reading, 0)
        yield ()
      }
    )
  }

  test("ready KeepAlive is written after at most one competing complete SDU") {
    virtual(
      peer.use { p =>
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          first <- Ref.of[IO, Boolean](true)
          raw = new ByteTransport[IO]:
            def read = p.transport.read
            def close = p.transport.close
            def isClosed = p.transport.isClosed
            def write(bytes: Bytes) =
              first.getAndSet(false).flatMap { initial =>
                (if initial then entered.complete(()).void *> release.get
                 else IO.unit) *> p.transport.write(bytes)
              }
          _ <- KeepAliveTransport.resource(raw, cfg, policy, IO.pure(KeepAlive.Cookie.Zero)).use {
            t =>
              val frame = hex("00000000000300028101")
              for
                bfWrites <- t.blockFetch.write(Bytes(frame.value ++ frame.value)).start
                _ <- entered.get
                activate <- t.activate.start
                _ <- IO.sleep(1.millis) *> release.complete(()).void
                _ <- activate.joinWithNever *> bfWrites.joinWithNever
                writes <- p.writes.get
                _ = assertEquals(writes, Vector(frame, ping, frame))
              yield ()
          }
        yield ()
      }
    )
  }

  test("fragment progress cannot reset KeepAlive reply deadline") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(Bytes(pong.value.take(9))) *> IO.sleep(9.seconds)
        _ <- p.incoming.offer(Bytes(pong.value.slice(9, 10))) *> IO.sleep(2.seconds)
        result <- t.freeze.attempt
        _ = assert(result.left.toOption.exists(_.getMessage.contains("response deadline")))
      yield ()
    })
  }

  test("BlockFetch state deadline is not reset by continuing matched KeepAlive replies") {
    virtual(peer.use { p =>
      val times =
        BlockFetch.TimeLimits(busy = 3.seconds, streaming = 3.seconds, wholeRequest = 30.seconds)
      val config = cfg.copy(times = times)
      val point = CardanoBlockFetch.SpecificPoint
        .from(ChainSync.Point.Block(ChainSync.UInt64.from(1).toOption.get, hex("00" * 32)))
        .toOption
        .get
      KeepAliveBlockFetchSession
        .resourceWithCookies(
          p.transport,
          config,
          policy.copy(interval = 1.second),
          IO.pure(KeepAlive.Cookie.Zero)
        )
        .use { s =>
          for
            _ <- p.incoming.offer(hex("000000008000000983010e84182af500f4"))
            _ <- s.negotiate(Handshake.Data(42, initiatorOnly = true, peerSharing = false))
            _ <- s.request(CardanoBlockFetch.InclusiveRange(point, point))
            _ <- p.incoming.offer(pong) *> IO.sleep(1100.millis)
            _ <- p.incoming.offer(pong) *> IO.sleep(1100.millis)
            _ <- p.incoming.offer(pong) *> IO.sleep(1100.millis)
            result <- s.receive.attempt
            _ = assert(
              result.left.toOption.exists(_.getMessage.contains("state deadline")),
              result.toString
            )
          yield ()
        }
    })
  }

  test("rejected ingress poisons the final barrier before worker error propagation") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(pong)
        _ <- t.freeze *> t.finishKeepAlive
        rejected <- t.admit(hex("00000000800200028105")).attempt
        _ = assert(rejected.isLeft)
        result <- t.barrier.attempt
        _ = assert(result.isLeft)
      yield ()
    })
  }

  test("graceful finish budget begins at freeze and does not restart") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(pong)
        _ <- t.freeze *> IO.sleep(4.seconds) *> t.freeze
        result <- t.finishBounded(IO.sleep(2.seconds)).attempt
        _ = assert(result.left.toOption.exists(_.getMessage.contains("finish deadline")))
      yield ()
    })
  }

  test("handshake and application coalescing preserves route ownership and exact BF messages") {
    virtual(peer.use { p =>
      val point = CardanoBlockFetch.SpecificPoint
        .from(ChainSync.Point.Block(ChainSync.UInt64.from(1).toOption.get, hex("00" * 32)))
        .toOption
        .get
      KeepAliveBlockFetchSession
        .resourceWithCookies(p.transport, cfg, policy, IO.pure(KeepAlive.Cookie.Zero))
        .use { s =>
          for
            _ <- p.incoming.offer(hex("000000008000000983010e84182af500f4000000008003000481028105"))
            _ <- s.negotiate(Handshake.Data(42, initiatorOnly = true, peerSharing = false))
            _ <- s.request(CardanoBlockFetch.InclusiveRange(point, point))
            a <- s.receive
            b <- s.receive
            _ = assertEquals(a, BlockFetch.Message.StartBatch)
            _ = assertEquals(b, BlockFetch.Message.BatchDone)
            _ <- s.freeze
            _ <- p.incoming.offer(pong)
            _ <- s.finish
            writes <- p.writes.get
            _ = assertEquals(writes.takeRight(2), Vector(done, hex("00000000000300028101")))
          yield ()
        }
    })
  }

  test("whole acquisition expiry is not extended by matched KeepAlive progress") {
    virtual(peer.use { p =>
      val config = cfg.copy(times = BlockFetch.TimeLimits(wholeRequest = 2.seconds))
      KeepAliveBlockFetchSession
        .resourceWithCookies(
          p.transport,
          config,
          policy.copy(interval = 1.second),
          IO.pure(KeepAlive.Cookie.Zero)
        )
        .use { s =>
          for
            _ <- p.incoming.offer(hex("000000008000000983010e84182af500f4"))
            _ <- s.negotiate(Handshake.Data(42, initiatorOnly = true, peerSharing = false))
            _ <- p.incoming.offer(pong) *> IO.sleep(1100.millis)
            _ <- p.incoming.offer(pong) *> IO.sleep(1100.millis)
            result <- s.receive.attempt
            _ = assert(
              result.left.toOption
                .exists(_.isInstanceOf[SingleProtocolConnection.WholeDeadlineExceeded]),
              result.toString
            )
          yield ()
        }
    })
  }

  test("BF byte queue cap and routed peak remain bounded independently of frame capacity") {
    virtual(peer.use { p =>
      val config = cfg.copy(maxRawBlockBytes = 16, maxChunkBytes = 32)
      val frame = hex("0000000080030014" + "00" * 20)
      KeepAliveTransport.resource(p.transport, config, policy, IO.pure(KeepAlive.Cookie.Zero)).use {
        t =>
          for
            _ <- t.admit(frame) *> t.admit(frame)
            before <- t.metrics
            _ = assertEquals(before.routedIngressBytes, 56)
            _ = assertEquals(before.peakRoutedIngressBytes, 56)
            _ = assert(before.routedIngressBytes <= policy.bfQueueBytes(config))
            failed <- t.admit(frame).attempt
            _ = assert(
              failed.left.toOption.exists(_.getMessage.contains("BlockFetch ingress queue full"))
            )
            after <- t.metrics
            _ = assertEquals(after.incomingWireBytes, 84L)
            _ = assertEquals(after.routedIngressBytes, 56)
            _ = assertEquals(
              policy.globalIngressBytes(config),
              policy.routedIngressBytes(config) + policy
                .bfQueueBytes(config) + 2 * policy.keepAliveQueueBytes
            )
          yield ()
      }
    })
  }

  test("physical write fragmentation cannot interleave bytes within an SDU") {
    virtual(
      peer.use { p =>
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          first <- Ref.of[IO, Boolean](true)
          raw = new ByteTransport[IO]:
            def read = p.transport.read
            def close = p.transport.close
            def isClosed = p.transport.isClosed
            def write(bytes: Bytes) =
              first.getAndSet(false).flatMap { initial =>
                (if initial then entered.complete(()).void *> release.get
                 else IO.unit) *> p.transport.write(bytes)
              }
          _ <- KeepAliveTransport
            .resource(raw, cfg.copy(maxChunkBytes = 1), policy, IO.pure(KeepAlive.Cookie.Zero))
            .use { t =>
              val frame = hex("00000000000300028101")
              for
                producer <- (frame.value ++ frame.value)
                  .traverse_(b => t.blockFetch.write(Bytes(Vector(b))))
                  .start
                _ <- entered.get
                activate <- t.activate.start
                _ <- IO.sleep(1.millis) *> release.complete(()).void
                _ <- activate.joinWithNever *> producer.joinWithNever
                writes <- p.writes.get
                _ = assert(writes.forall(_.size == 1))
                _ = assertEquals(
                  Bytes(writes.flatMap(_.value)),
                  Bytes(frame.value ++ ping.value ++ frame.value)
                )
              yield ()
            }
        yield ()
      }
    )
  }

  test("reader quiescence admits an already completed raw read before final suffix check") {
    virtual(peer.use { p =>
      for
        pause <- Ref.of[IO, Boolean](false)
        readCompleted <- Deferred[IO, Unit]
        admitNow <- Deferred[IO, Unit]
        handoff = pause.get.flatMap(wait =>
          if wait then readCompleted.complete(()).void *> admitNow.get else IO.unit
        )
        _ <- KeepAliveTransport
          .resource(p.transport, cfg, policy, IO.pure(KeepAlive.Cookie.Zero), Some(handoff))
          .use { t =>
            for
              _ <- t.activate
              _ <- p.incoming.offer(pong)
              _ <- t.freeze *> t.finishKeepAlive
              _ <- pause.set(true) *> p.incoming.offer(bf)
              _ <- readCompleted.get
              finished <- Deferred[IO, Either[Throwable, Unit]]
              barrier <- t.barrier.attempt.flatTap(finished.complete).start
              _ <- IO.sleep(1.millis)
              before <- finished.tryGet
              _ = assertEquals(before, None)
              _ <- admitNow.complete(()).void
              result <- barrier.joinWithNever
              _ = assert(result.left.toOption.exists(_.getMessage.contains("suffix")))
              readers <- p.reading.get
              _ = assertEquals(readers, 0)
            yield ()
          }
      yield ()
    })
  }

  test("successful final barrier closes and joins the outstanding physical read") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(pong)
        _ <- t.freeze *> t.finishKeepAlive *> IO.sleep(1.millis)
        before <- p.reading.get
        _ = assertEquals(before, 1)
        _ <- t.barrier
        readers <- p.reading.get
        closes <- p.closes.get
        _ = assertEquals(readers, 0)
        _ = assertEquals(closes, 1)
      yield ()
    })
  }

  test("reader quiescence propagates explicit close failure and joins pending read") {
    virtual(peer.use { p =>
      val problem = new IllegalStateException("quiescence close failed")
      val raw = new ByteTransport[IO]:
        def read = p.transport.read
        def write(bytes: Bytes) = p.transport.write(bytes)
        def close = p.transport.close *> IO.raiseError[Unit](problem)
        def isClosed = p.transport.isClosed
      KeepAliveTransport.resource(raw, cfg, policy, IO.pure(KeepAlive.Cookie.Zero)).use { t =>
        for
          _ <- t.activate
          _ <- p.incoming.offer(pong)
          _ <- t.freeze *> t.finishKeepAlive
          result <- t.barrier.attempt
          _ = assert(result.left.toOption.exists(_ eq problem), result.toString)
          readers <- p.reading.get
          closes <- p.closes.get
          _ = assertEquals(readers, 0)
          _ = assertEquals(closes, 1)
        yield ()
      }
    })
  }

  test("first atomically recorded ingress error survives a concurrent writer failure") {
    virtual(use { (_, t) =>
      for
        rejected <- t.admit(hex("00000000800200028105")).attempt
        _ = assert(rejected.isLeft)
        result <- t.blockFetch.write(hex("00000000000300028101")).attempt
        _ = assert(
          result.left.toOption.exists(_.getMessage.contains("unexpected mux protocol")),
          result.toString
        )
      yield ()
    })
  }

  test(
    "absolute reply deadline includes request write delay and cannot restart after acknowledgment"
  ) {
    virtual(Vector(9.seconds, 11.seconds).traverse_ { writeDelay =>
      peer.use { p =>
        val raw = new ByteTransport[IO]:
          def read = p.transport.read
          def close = p.transport.close
          def isClosed = p.transport.isClosed
          def write(bytes: Bytes) = IO.sleep(writeDelay) *> p.transport.write(bytes)
        KeepAliveTransport.resource(raw, cfg, policy, IO.pure(KeepAlive.Cookie.Zero)).use { t =>
          for
            _ <- t.activate.attempt
            _ <- IO.sleep(2.seconds)
            result <- t.freeze.attempt
            _ = assert(
              result.left.toOption.exists(_.getMessage.contains("response deadline")),
              result.toString
            )
          yield ()
        }
      }
    })
  }

  test("a reply at absolute expiry rejects while a reply just before expiry can finish") {
    virtual(Vector(9999.millis, 10.seconds).traverse_ { delay =>
      use { (p, t) =>
        for
          _ <- t.activate
          _ <- IO.sleep(delay) *> p.incoming.offer(pong)
          result <- (t.freeze *> t.finishKeepAlive *> t.barrier).attempt
          _ =
            if delay < 10.seconds then assert(result.isRight, result.toString)
            else
              assert(
                result.left.toOption.exists(_.getMessage.contains("deadline")),
                result.toString
              )
        yield ()
      }
    })
  }

  test("final barrier checks absolute finish expiry even without an active finish timeout") {
    virtual(use { (p, t) =>
      for
        _ <- t.activate
        _ <- p.incoming.offer(pong)
        _ <- t.freeze *> t.finishKeepAlive *> IO.sleep(5.seconds)
        result <- t.barrier.attempt
        _ = assert(
          result.left.toOption.exists(_.getMessage.contains("finish deadline")),
          result.toString
        )
      yield ()
    })
  }

  test(
    "small transport chunks reserve virtual BF remainder independently of queue and next raw chunk"
  ) {
    virtual(peer.use { p =>
      val config = cfg.copy(maxRawBlockBytes = 70000, maxChunkBytes = 4096)
      val large = hex("000000008003ffff" + "00" * 65535)
      KeepAliveTransport.resource(p.transport, config, policy, IO.pure(KeepAlive.Cookie.Zero)).use {
        t =>
          def feed = large.value
            .grouped(config.maxChunkBytes)
            .toVector
            .traverse_(chunk => t.admit(Bytes(chunk)))
          for
            _ <- feed
            first <- t.blockFetch.read
            _ = assertEquals(first.map(_.size), Some(4096))
            _ <- feed
            _ <- t.admit(Bytes(large.value.take(config.maxChunkBytes)))
            metrics <- t.metrics
            _ = assertEquals(policy.virtualRemainderBytes(config), 61447)
            _ = assertEquals(metrics.routedIngressBytes, 61447 + 65543 + 4096)
            _ = assertEquals(
              policy.routedIngressBytes(config),
              policy.bfQueueBytes(
                config
              ) + policy.keepAliveQueueBytes + 65543 + config.maxChunkBytes + 61447
            )
            _ = assert(
              metrics.routedIngressBytes + config.maxChunkBytes <= policy.routedIngressBytes(config)
            )
          yield ()
      }
    })
  }

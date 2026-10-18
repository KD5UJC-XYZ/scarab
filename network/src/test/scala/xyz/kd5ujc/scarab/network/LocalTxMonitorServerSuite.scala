// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.ProtocolError
import cats.effect.{IO, Ref, Resource}
import cats.mtl.Handle
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  CardanoBlockFetch,
  ChainSync,
  Handshake,
  LocalTxMonitor,
  LocalTxSubmission
}
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Role, UInt64}
import xyz.kd5ujc.scarab.protocol.LocalTxMonitor.{Kind, Message, State}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.{Held, Snapshot, TxMeasure}
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/** The node-to-client LocalTxMonitor and local ChainSync servers against clients over an in-memory
  * bearer, with a toy mempool (a list of one-byte "transactions") and a toy chain.
  */
class LocalTxMonitorServerSuite extends FailsOnUncaught:
  private val magic = 42L
  private val capacity = TxMeasure(1000, 5000, 7000, 2048)

  private def txItem(n: Int): Bytes = Bytes(Vector(0x82, 0x06, 0x41, n & 255).map(_.toByte))
  private def idItem(n: Int): Bytes = Bytes(Vector(0x82, 0x06, 0x41, (n + 100) & 255).map(_.toByte))

  private def snapshot(slot: Int, txs: Vector[Int]): Snapshot =
    Snapshot(
      slot,
      txs.map(n => Held(txItem(n), TxMeasure(10 + n, n * 2, n * 3, n))),
      item => txs.exists(n => idItem(n) == item),
      capacity,
      (BigInt(slot), txs.map(txItem))
    )

  /** The toy mempool: its current snapshot, replaced by the test. */
  private final class Toy(val current: Ref[IO, Snapshot]):
    val source: LocalTxMonitorServer.Source[IO] = new LocalTxMonitorServer.Source[IO]:
      def acquire = current.get
      def awaitChange(seen: Snapshot): IO[Snapshot] =
        current.get.flatMap { now =>
          if now.identity != seen.identity then IO.pure(now)
          else IO.sleep(5.millis) *> awaitChange(seen)
        }

  private def toy(slot: Int, txs: Int*): IO[Toy] =
    Ref.of[IO, Snapshot](snapshot(slot, txs.toVector)).map(new Toy(_))

  /** A toy chain of blocks 1 to `n` whose bytes are `size` long. */
  private def chainSource(
      n: Int,
      size: Int
  ): Resource[IO, ChainSyncServer.Source[IO, RawNtNBlock]] =
    def block(i: Int): RawNtNBlock =
      RawNtNBlock.from(Bytes(Vector.fill(size)(i.toByte))).toOption.get
    def point(i: Int): Point = Point.Block(UInt64.saturating(i), Bytes(Vector.fill(32)(i.toByte)))
    def tip(i: Int) = ChainSync.Tip(point(i), UInt64.saturating(i))
    Resource.eval(Ref.of[IO, Int](0)).map { cursor =>
      new ChainSyncServer.Source[IO, RawNtNBlock]:
        def intersect(points: Vector[Point]) =
          points.find(p => p == Point.Origin || (1 to n).exists(point(_) == p)) match
            case Some(Point.Origin) => cursor.set(0).as(Right(Point.Origin -> tip(n)))
            case Some(p) =>
              val i = (1 to n).find(point(_) == p).get
              cursor.set(i).as(Right(p -> tip(n)))
            case None => IO.pure(Left(tip(n)))
        def poll =
          cursor.modify(c =>
            if c < n then (c + 1, Some(ChainSyncServer.Update.RollForward(block(c + 1), tip(n))))
            else (c, None)
          )
        def await = IO.never
    }

  private def sendTo[A](
      ch: MuxChannel[IO],
      encoded: Either[ProtocolError, Bytes]
  )(using Handle[IO, NetworkError]): IO[Unit] =
    IO.fromEither(encoded.left.map(e => RuntimeException(e.toString))).flatMap(ch.send)

  private def monitorSend(ch: MuxChannel[IO], state: State, m: Message)(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    sendTo(ch, LocalTxMonitor.encode(state, Role.Client, m))

  private def monitorReceive(ch: MuxChannel[IO], state: State)(using Handle[IO, NetworkError]) =
    ch.recv(LocalTxMonitor.MaxMessageBytes)(LocalTxMonitor.decodePrefix(state, Role.Server, _))

  private def connected[A](
      monitor: Option[LocalTxMonitorServer.Source[IO]],
      chain: Option[Resource[IO, ChainSyncServer.Source[IO, RawNtNBlock]]]
  )(
      body: (MuxChannel[IO], MuxChannel[IO]) => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[(A, String)] =
    Resource.eval(Bearer.inMemory[IO]()).use { (clientEnd, serverEnd) =>
      Handle
        .allow[NetworkError](
          NodeToClientServer
            .accept(
              serverEnd,
              magic,
              _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
              None,
              chainSync = chain,
              txMonitor = monitor
            )
            .map(_.message)
        )
        .rescue(e => IO.pure(e.message))
        .start
        .flatMap { server =>
          Handle
            .allow[NetworkError] {
              Mux.resource(clientEnd, Set(0, 5, 6, 9), Mux.Config()).use { mux =>
                for
                  hs <- mux.channel(0)
                  _ <- HandshakeClient.run(
                    hs,
                    Vector(16, 23).map(v => v -> Handshake.Data(magic)),
                    suite = Handshake.Suite.NodeToClient
                  )
                  monitorChannel <- mux.channel(9)
                  chainChannel <- mux.channel(5)
                  submission <- mux.channel(6)
                  out <- body(monitorChannel, chainChannel)
                  _ <- sendTo(
                    submission,
                    LocalTxSubmission.encode(
                      LocalTxSubmission.State.Idle,
                      Role.Client,
                      LocalTxSubmission.Message.Done
                    )
                  )
                  _ <- submission.flush
                yield out
              }
            }
            .rescue(e => IO.raiseError[A](RuntimeException(e.message)))
            .flatMap(out => server.cancel.as(out -> "client finished"))
        }
    }

  test("acquire, walk the transactions, ask for a member, the sizes and the measures") {
    toy(7, 1, 2, 3).flatMap { t =>
      connected(Some(t.source), None) { (ch, _) =>
        for
          _ <- monitorSend(ch, State.Idle, Message.Acquire)
          acquired <- monitorReceive(ch, State.Acquiring)
          _ <- monitorSend(ch, State.Acquired, Message.GetSizes)
          sizes <- monitorReceive(ch, State.Busy(Kind.GetSizes))
          _ <- monitorSend(ch, State.Acquired, Message.NextTx)
          first <- monitorReceive(ch, State.Busy(Kind.NextTx))
          _ <- monitorSend(ch, State.Acquired, Message.GetMeasures)
          measures <- monitorReceive(ch, State.Busy(Kind.GetMeasures))
          _ <- monitorSend(ch, State.Acquired, Message.GetSizes)
          sizesAfter <- monitorReceive(ch, State.Busy(Kind.GetSizes))
          _ <- monitorSend(ch, State.Acquired, Message.NextTx)
          second <- monitorReceive(ch, State.Busy(Kind.NextTx))
          _ <- monitorSend(ch, State.Acquired, Message.NextTx)
          third <- monitorReceive(ch, State.Busy(Kind.NextTx))
          _ <- monitorSend(ch, State.Acquired, Message.NextTx)
          end <- monitorReceive(ch, State.Busy(Kind.NextTx))
          _ <- monitorSend(ch, State.Acquired, Message.HasTx(idItem(2)))
          has <- monitorReceive(ch, State.Busy(Kind.HasTx))
          _ <- monitorSend(ch, State.Acquired, Message.HasTx(idItem(9)))
          hasNot <- monitorReceive(ch, State.Busy(Kind.HasTx))
          _ <- monitorSend(ch, State.Acquired, Message.Release)
          _ <- monitorSend(ch, State.Idle, Message.Done)
        yield (acquired, sizes, first, measures, sizesAfter, second, third, end, has, hasNot)
      }.map {
        case ((acquired, sizes, first, measures, sizesAfter, second, third, end, has, hasNot), _) =>
          assertEquals(acquired, Message.Acquired(7))
          // the sizes describe the whole snapshot, before and after NextTx
          val expected = LocalTxMonitor.Sizes(1000, 11 + 12 + 13, 3)
          assertEquals(sizes, Message.ReplyGetSizes(expected))
          assertEquals(sizesAfter, Message.ReplyGetSizes(expected))
          assertEquals(first, Message.ReplyNextTx(Some(txItem(1))))
          assertEquals(second, Message.ReplyNextTx(Some(txItem(2))))
          assertEquals(third, Message.ReplyNextTx(Some(txItem(3))))
          assertEquals(end, Message.ReplyNextTx(None))
          // the measures cover what NextTx has not handed out: transactions 2 and 3
          measures match
            case Message.ReplyGetMeasures(m) =>
              assertEquals(m.txCount, 2L)
              assertEquals(m.byName.keys.toList, LocalTxMonitorServer.MeasureNames)
              assertEquals(m.byName("transaction_bytes"), LocalTxMonitor.Measure(12 + 13, 1000))
              assertEquals(m.byName("ex_units_memory"), LocalTxMonitor.Measure(4 + 6, 5000))
              assertEquals(m.byName("ex_units_steps"), LocalTxMonitor.Measure(6 + 9, 7000))
              assertEquals(m.byName("reference_scripts_bytes"), LocalTxMonitor.Measure(5, 2048))
            case other => fail(other.toString)
          assertEquals(has, Message.ReplyHasTx(true))
          assertEquals(hasNot, Message.ReplyHasTx(false))
      }
    }
  }

  test(
    "the acquired snapshot does not change while the pool does; AwaitAcquire waits for a change"
  ) {
    toy(7, 1).flatMap { t =>
      connected(Some(t.source), None) { (ch, _) =>
        for
          _ <- monitorSend(ch, State.Idle, Message.Acquire)
          a1 <- monitorReceive(ch, State.Acquiring)
          _ <- t.current.set(snapshot(8, Vector(1, 2)))
          _ <- monitorSend(ch, State.Acquired, Message.NextTx)
          held <- monitorReceive(ch, State.Busy(Kind.NextTx))
          _ <- monitorSend(ch, State.Acquired, Message.NextTx)
          none <- monitorReceive(ch, State.Busy(Kind.NextTx))
          // the pool already differs: AwaitAcquire answers with the new snapshot
          _ <- monitorSend(ch, State.Acquired, Message.AwaitAcquire)
          a2 <- monitorReceive(ch, State.Acquiring)
          // the pool equals the snapshot just acquired: AwaitAcquire waits
          _ <- monitorSend(ch, State.Acquired, Message.AwaitAcquire)
          waiting <- monitorReceive(ch, State.Acquiring).start
          _ <- IO.sleep(60.millis)
          completedEarly <- waiting.join
            .map(_.isInstanceOf[cats.effect.kernel.Outcome.Succeeded[?, ?, ?]])
            .timeoutTo(10.millis, IO.pure(false))
          _ <- t.current.set(snapshot(9, Vector(1, 2)))
          a3 <- waiting.joinWithNever
          _ <- monitorSend(ch, State.Acquired, Message.GetSizes)
          sizes <- monitorReceive(ch, State.Busy(Kind.GetSizes))
          _ <- monitorSend(ch, State.Acquired, Message.Release)
          // after Release the client acquires afresh
          _ <- monitorSend(ch, State.Idle, Message.Acquire)
          a4 <- monitorReceive(ch, State.Acquiring)
          _ <- monitorSend(ch, State.Acquired, Message.Release)
          _ <- monitorSend(ch, State.Idle, Message.Done)
        yield (a1, held, none, a2, completedEarly, a3, sizes, a4)
      }.map { case ((a1, held, none, a2, completedEarly, a3, sizes, a4), _) =>
        assertEquals(a1, Message.Acquired(7))
        assertEquals(held, Message.ReplyNextTx(Some(txItem(1))))
        assertEquals(none, Message.ReplyNextTx(None))
        assertEquals(a2, Message.Acquired(8))
        assertEquals(completedEarly, false)
        assertEquals(a3, Message.Acquired(9))
        assertEquals(sizes, Message.ReplyGetSizes(LocalTxMonitor.Sizes(1000, 11 + 12, 2)))
        assertEquals(a4, Message.Acquired(9))
      }
    }
  }

  test("a client message in the wrong state ends the connection with a protocol error") {
    toy(7).flatMap { t =>
      Resource.eval(Bearer.inMemory[IO]()).use { (clientEnd, serverEnd) =>
        Handle
          .allow[NetworkError](
            NodeToClientServer
              .accept(
                serverEnd,
                magic,
                _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
                txMonitor = Some(t.source)
              )
              .map(_.message)
          )
          .rescue(e => IO.pure(e.message))
          .start
          .flatMap { server =>
            Handle
              .allow[NetworkError] {
                Mux.resource(clientEnd, Set(0, 9), Mux.Config()).use { mux =>
                  for
                    hs <- mux.channel(0)
                    _ <- HandshakeClient.run(
                      hs,
                      Vector(16).map(v => v -> Handshake.Data(magic)),
                      suite = Handshake.Suite.NodeToClient
                    )
                    ch <- mux.channel(9)
                    // NextTx before Acquire: the bytes [5] are not a message of Idle
                    _ <- ch.send(Bytes(Vector(0x81, 0x05).map(_.toByte)))
                    _ <- ch.flush
                    reason <- server.joinWithNever
                  yield reason
                }
              }
              .rescue(e => IO.raiseError[String](RuntimeException(e.message)))
          }
          .map(reason => assert(reason.contains("local-tx-monitor"), reason))
      }
    }
  }

  test("local ChainSync serves whole blocks larger than the node-to-node message bound") {
    val size = 150000
    connected(None, Some(chainSource(3, size))) { (_, ch) =>
      val codec = CardanoBlockFetch.payloadCodec()
      val limits = ChainSync.Limits.NodeToClient
      def send(state: ChainSync.State, m: ChainSync.Message[RawNtNBlock]): IO[Unit] =
        sendTo(ch, ChainSync.encode(state, Role.Client, m, codec, limits))
      def receive(state: ChainSync.State) =
        ch.recv(limits.maxMessageBytes)(
          ChainSync.decodePrefix(state, Role.Server, _, codec, limits)
        )
      for
        _ <- send(ChainSync.State.Idle, ChainSync.Message.FindIntersect(Vector(Point.Origin)))
        found <- receive(ChainSync.State.Intersect)
        _ <- send(ChainSync.State.Idle, ChainSync.Message.RequestNext)
        one <- receive(ChainSync.State.NextCanAwait)
        _ <- send(ChainSync.State.Idle, ChainSync.Message.RequestNext)
        two <- receive(ChainSync.State.NextCanAwait)
        _ <- send(ChainSync.State.Idle, ChainSync.Message.RequestNext)
        three <- receive(ChainSync.State.NextCanAwait)
        _ <- send(ChainSync.State.Idle, ChainSync.Message.Done)
      yield (found, List(one, two, three))
    }.map { case ((found, blocks), _) =>
      found match
        case ChainSync.Message.IntersectFound(Point.Origin, _) => ()
        case other                                             => fail(other.toString)
      assertEquals(
        blocks.collect { case ChainSync.Message.RollForward(b, _) =>
          b.bytes.size -> b.bytes.value.head
        },
        List(size -> 1.toByte, size -> 2.toByte, size -> 3.toByte)
      )
    }
  }

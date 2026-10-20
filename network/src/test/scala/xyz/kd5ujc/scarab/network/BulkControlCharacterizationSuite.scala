// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, ChainSync}

/** Transport-only opaque block payloads: no claim that these are valid ledger blocks or a real
  * historical chain. The production range codec, server, client, Mux and KeepAlive are unchanged.
  * These measurements deliberately retain the current FIFO outcome, including its refusal.
  */
class BulkControlCharacterizationSuite extends FailsOnUncaught:
  private final case class Load(name: String, blocks: Int, rawBytes: Int, bytesPerSecond: Int)
  private final case class Probe(
      result: Either[NetworkError, FiniteDuration],
      finished: FiniteDuration,
      receivedReplies: Int,
      deliveredBytes: Long
  )
  private final case class Observation(
      result: Either[NetworkError, Option[Vector[Bytes]]],
      finished: FiniteDuration,
      probe: Option[Probe],
      admitted: Option[FiniteDuration],
      arrived: Option[FiniteDuration],
      deliveredBytes: Long
  )

  private def observed(
      delegate: MuxChannel[IO],
      sent: IO[Unit] = IO.unit,
      received: IO[Unit] = IO.unit
  ): MuxChannel[IO] = new MuxChannel[IO]:
    def protocol = delegate.protocol
    def send(message: Bytes)(using Raise[IO, NetworkError]) = delegate.send(message) *> sent
    def flush = delegate.flush
    def recv[A](maxBytes: Int)(decode: Bytes => ChainSync.DecodeResult[A])(using
        Raise[IO, NetworkError]
    ): IO[A] = delegate.recv(maxBytes)(decode).flatTap(_ => received)

  private def run(load: Load, control: Boolean): IO[Observation] =
    for
      started <- IO.monotonic
      admittedBulk <- Deferred[IO, Unit]
      enteredWriter <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      admittedPing <- Deferred[IO, Unit]
      pingAdmission <- Ref.of[IO, Option[FiniteDuration]](None)
      pingArrival <- Ref.of[IO, Option[FiniteDuration]](None)
      replies <- Ref.of[IO, Int](0)
      delivered <- Ref.of[IO, Long](0L)
      toClient <- Queue.bounded[IO, Option[Chunk[Byte]]](1)
      toServer <- Queue.bounded[IO, Option[Chunk[Byte]]](1)
      clientBearer = new Bearer[IO]:
        def reads = Stream.fromQueueNoneTerminatedChunk(toClient)
        def write(bytes: Chunk[Byte]) = toServer.offer(Some(bytes))
        def close = IO.unit
      serverBearer = new Bearer[IO]:
        def reads = Stream.fromQueueNoneTerminatedChunk(toServer)
        def write(bytes: Chunk[Byte]) =
          enteredWriter.complete(()).void *> release.get *>
            bytes.toVector.grouped(4096).toVector.traverse_ { part =>
              val wait = (part.size.toLong * 1000000000L / load.bytesPerSecond).nanos
              IO.sleep(wait) *> toClient.offer(Some(Chunk.vector(part))) *>
                delivered.update(_ + part.size)
            }
        def close = IO.unit
      payload = Bytes(Vector.fill(load.rawBytes)(1.toByte))
      source = new BlockFetchServer.Blocks[IO]:
        def range(
            from: ChainSync.Point,
            to: ChainSync.Point,
            maxBlocks: Int
        ): IO[Option[Stream[IO, Bytes]]] =
          IO {
            assertEquals(from, Peers.point(1))
            assertEquals(to, Peers.point(load.blocks))
            assert(load.blocks <= maxBlocks)
            Some(
              Stream.emits(Vector.fill(load.blocks)(payload)) ++
                Stream.exec(admittedBulk.complete(()).void)
            )
          }
      outcome <- Peers.typed {
        (
          Mux.resource(clientBearer, Set(3, 8), Mux.Config(duplex = true)),
          Mux.resource(
            serverBearer,
            Set(3, 8),
            Mux.Config(role = Mux.Role.Responder, duplex = true)
          )
        ).tupled.use { (client, server) =>
          for
            fetching <- client.channel(3)
            serving <- server.channel(3)
            requesting <- server.channel(8, Mux.Role.Initiator)
            answering <- client.channel(8, Mux.Role.Responder)
            api <- BlockFetchClient(
              observed(fetching, received = replies.update(_ + 1)),
              BlockFetchClient.Settings(request = BlockFetch.RequestLimits(maxBlocks = 128))
            )
            ping <- KeepAliveClient(
              observed(
                requesting,
                sent = IO.monotonic.flatMap(now => pingAdmission.set(Some(now - started))) *>
                  admittedPing.complete(()).void
              )
            )
            observedAnswer = observed(
              answering,
              received = IO.monotonic.flatMap(now => pingArrival.set(Some(now - started)))
            )
            result <- (
              Resource.make(Peers.typed(BlockFetchServer.run(serving, source)).start)(_.cancel),
              Resource.make(Peers.typed(KeepAliveServer.run(observedAnswer)).start)(_.cancel),
              Resource.make(
                Peers.typed(api.requestRange(Peers.point(1), Peers.point(load.blocks))).start
              )(_.cancel)
            ).tupled.use { (_, _, fetchingFiber) =>
              val probe = Peers.typed(ping.ping).flatMap { result =>
                (IO.monotonic, replies.get, delivered.get).mapN { (now, count, bytes) =>
                  Probe(result, now - started, count, bytes)
                }
              }
              for
                _ <- enteredWriter.get *> admittedBulk.get
                measurement <-
                  if control then
                    Resource.make(probe.start)(_.cancel).use { pending =>
                      for
                        _ <- admittedPing.get *> release.complete(())
                        measured <- pending.joinWithNever
                        fetched <- fetchingFiber.joinWithNever
                        finished <- IO.monotonic
                      yield (fetched, finished - started, Some(measured))
                    }
                  else
                    for
                      _ <- release.complete(())
                      fetched <- fetchingFiber.joinWithNever
                      finished <- IO.monotonic
                    yield (fetched, finished - started, None)
                (fetched, finished, measured) = measurement
                admission <- pingAdmission.get
                arrival <- pingArrival.get
                bytes <- delivered.get
              yield Observation(fetched, finished, measured, admission, arrival, bytes)
            }
          yield result
        }
      }
    yield outcome.fold(error => fail(error.message), identity)

  for load <- List(
      Load("forty 60KiB messages at 32KiB/s", 40, 60 * 1024, 32 * 1024),
      Load("three historical-size 2000000-byte messages at 64KiB/s", 3, 2000000, 64 * 1024)
    )
  do
    test(s"FIFO bulk/control characterization: ${load.name}"):
      for
        bulk <- TestControl.executeEmbed(run(load, control = false))
        mixed <- TestControl.executeEmbed(run(load, control = true))
      yield
        val expected = Some(Vector.fill(load.blocks)(Bytes(Vector.fill(load.rawBytes)(1.toByte))))
        assertEquals(bulk.result, Right(expected))
        assertEquals(mixed.result, Right(expected))
        assert(bulk.finished > 60.seconds && bulk.finished < 120.seconds, bulk.toString)
        assert(mixed.finished > 60.seconds && mixed.finished < 120.seconds, mixed.toString)
        assertEquals(mixed.admitted, Some(Duration.Zero))
        val probe = mixed.probe.getOrElse(fail("control probe did not run"))
        assertEquals(probe.result, Left(NetworkError.Timeout(Protocols.KeepAlive, "server")))
        assertEquals(probe.finished, 60.seconds)
        // The same transfer is completing real replies when the control deadline expires.
        assert(probe.receivedReplies > 1 && probe.receivedReplies < load.blocks + 1, probe.toString)
        assert(
          probe.deliveredBytes > 0 && probe.deliveredBytes < mixed.deliveredBytes,
          probe.toString
        )
        assert(mixed.arrived.exists(_ > probe.finished), mixed.toString)
        println(
          s"BULK_CONTROL ${load.name}: baseline=${bulk.finished} mixed=${mixed.finished} " +
            s"blocks=${mixed.result.map(_.map(_.size))} admitted=${mixed.admitted} " +
            s"arrived=${mixed.arrived} totalBytes=${mixed.deliveredBytes} probe=$probe"
        )

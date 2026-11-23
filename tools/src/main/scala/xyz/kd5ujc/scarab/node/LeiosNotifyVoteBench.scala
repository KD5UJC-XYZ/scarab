// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, IOApp, Ref, Resource}
import cats.effect.std.{AtomicCell, Queue, Semaphore}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import java.lang.management.ManagementFactory
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.{
  Committee,
  RbHash,
  Seat,
  SeatId,
  SecretKey,
  Seed,
  VerificationKey,
  Vote,
  Weight
}
import xyz.kd5ujc.scarab.network.{
  Bearer,
  Fibers,
  LeiosNotification,
  LeiosNotifyPipelined,
  LeiosNotifyServer,
  Mux,
  NetworkError,
  Protocols
}
import xyz.kd5ujc.scarab.protocol.LeiosNotify
import xyz.kd5ujc.scarab.storage.LeiosEbNotification

/** The cost of a vote through the LeiosNotify plumbing (#1349): `votes` signed votes from the
  * node's relay server ([[LeiosNotifyRelay.server]] under [[LeiosNotifyServer.run]]) to its
  * pipelined client ([[LeiosNotifyPipelined.run]]), over an in-memory bearer, the client's handler
  * counting each vote and doing nothing else. The producer offers a vote only for a request the
  * server has counted, so the relay, which drops a vote when the peer holds no credit as the
  * reference's pump does, never drops one; and it keeps at most `window` votes ahead of the
  * handler, so that the client waits for its replies as a client whose handler checks each vote's
  * signature does. Not part of `check`: run it from the tools classpath, with
  * `-Dcats.effect.tracing.mode=none` or without; it prints the median wall and process CPU
  * microseconds per vote, and one signature check's, for scale.
  */
object LeiosNotifyVoteBench extends IOApp.Simple:
  private given LeiosNotify.AnnouncementReader = _ => Right(())

  private val os = ManagementFactory.getOperatingSystemMXBean match
    case bean: com.sun.management.OperatingSystemMXBean => Some(bean)
    case _                                              => None

  private def cpuNanos: IO[Long] = IO.delay(os.fold(0L)(_.getProcessCpuTime))

  private def bytes(n: Int, seed: Int): Bytes =
    Bytes.wrap(Array.tabulate(n)(i => (i * 31 + seed).toByte))

  /** Signed votes of 16 seats for 8 announcing blocks, and the committee of those seats. */
  private val signed: Either[Throwable, (Vector[Vote], Committee)] =
    (for
      keys <- (0 until 16).toVector.traverse(i =>
        Seed.fromBytes(bytes(32, i)).flatMap(SecretKey.generate)
      )
      hashes = (0 until 8).toVector.flatMap(i => RbHash.fromBytes(bytes(32, 100 + i)).toVector)
      votes <- (for
        (k, seat) <- keys.zipWithIndex
        h <- hashes
      yield SeatId.fromInt(seat).flatMap(Vote.sign(k, _, h))).sequence
      vks <- keys.traverse(VerificationKey.derive)
      committee <- Committee.honoured(vks.map(vk => Seat(Weight.zero, Some(vk))))
    yield (votes, committee)).leftMap(e => new IllegalStateException(e.toString))

  /** Votes `from` to `votes` of `pool` in turn, each once the server has counted a request for it
    * and the window has room for it.
    */
  private def produced(
      counted: Semaphore[IO],
      ahead: Semaphore[IO],
      acquired: Queue[IO, Vote],
      pool: Vector[Vote],
      from: Int,
      votes: Int
  ): IO[Unit] =
    (counted.acquire *> ahead.acquire *> acquired.offer(pool(from % pool.size)) >>
      produced(counted, ahead, acquired, pool, from + 1, votes)).whenA(from < votes)

  /** One round: microseconds of wall and of process CPU per vote. */
  private def round(pool: Vector[Vote], votes: Int, window: Int): IO[(Double, Double)] =
    Handle
      .allow[NetworkError] {
        Bearer.inMemory[IO](256).flatMap { (a, b) =>
          (
            Mux.resource(a, Set(Protocols.LeiosNotify), Mux.Config()),
            Mux.resource(b, Set(Protocols.LeiosNotify), Mux.Config(role = Mux.Role.Responder))
          ).tupled.use { (clientMux, serverMux) =>
            for
              ours <- clientMux.channel(Protocols.LeiosNotify)
              theirs <- serverMux.channel(Protocols.LeiosNotify)
              central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
              acquired <- Queue.unbounded[IO, Vote]
              ahead <- Semaphore[IO](window.toLong)
              counted <- Semaphore[IO](0L)
              delivered <- Ref.of[IO, Int](0)
              stop <- Deferred[IO, Unit]
              gate <- SignallingRef.of[IO, Boolean](true)
              replies = LeiosNotifyRelay.server[IO](
                central,
                Resource.eval(Queue.unbounded[IO, LeiosEbNotification]),
                Resource.pure(acquired),
                IO.pure(None),
                LeiosMinOfferLead.Default
              )
              server <- Fibers
                .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                  replies.use(r => LeiosNotifyServer.run(theirs, countingRequests(r, counted)))
                )
                .start
              handle: (LeiosNotification => Raise[IO, NetworkError] ?=> IO[Unit]) = n =>
                n match
                  case LeiosNotification.Votes(vs) =>
                    ahead.release *> delivered
                      .updateAndGet(_ + vs.size)
                      .flatMap(d => stop.complete(()).void.whenA(d >= votes))
                  case _ => IO.unit
              client <- Fibers
                .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                  LeiosNotifyPipelined.run[IO](ours, stop, gate, handle)
                )
                .start
              (began, cpu) <- (IO.monotonic, cpuNanos).tupled
              producer <- produced(counted, ahead, acquired, pool, 0, votes).start
              _ <- stop.get.timeout(120.seconds)
              (ended, cpuEnd) <- (IO.monotonic, cpuNanos).tupled
              _ <- producer.joinWithNever
              _ <- client.joinWithNever.flatMap(Fibers.land(_))
              _ <- server.joinWithNever.flatMap(Fibers.land(_))
            yield (
              (ended - began).toNanos.toDouble / votes / 1000.0,
              (cpuEnd - cpu).toDouble / votes / 1000.0
            )
          }
        }
      }
      .rescue(e => IO.raiseError(NetworkError.InternalFailure(e)))

  /** The server's replies, each request it counts released to the producer as a credit. */
  private def countingRequests(
      replies: LeiosNotifyServer.Replies[IO],
      counted: Semaphore[IO]
  ): LeiosNotifyServer.Replies[IO] =
    new LeiosNotifyServer.Replies[IO]:
      def requested: IO[LeiosNotifyServer.Requested] =
        replies.requested.flatTap {
          case LeiosNotifyServer.Requested.NotExcessive => counted.release
          case LeiosNotifyServer.Requested.Excessive(_) => IO.unit
        }
      def next: IO[LeiosNotifyServer.Reply] = replies.next
      def poll: IO[Option[LeiosNotifyServer.Reply]] = replies.poll
      def pump: IO[Unit] = replies.pump

  private def median(xs: List[Double]): Double = xs.sorted.apply(xs.size / 2)

  /** Microseconds of one signature check ([[Vote.verify]]), for scale. */
  private def verifying(pool: Vector[Vote], committee: Committee, n: Int): IO[Double] =
    IO.monotonic.flatMap(began =>
      IO.delay((0 until n).foreach(i => Vote.verify(committee, pool(i % pool.size)))) *>
        IO.monotonic.map(ended => (ended - began).toNanos.toDouble / n / 1000.0)
    )

  def run: IO[Unit] =
    IO.fromEither(signed).flatMap { (pool, committee) =>
      val votes = 20000
      val window = 64
      for
        tracing <- IO.delay(
          Option(System.getProperty("cats.effect.tracing.mode")).getOrElse("default")
        )
        _ <- round(pool, votes, window).replicateA_(2) // warm-up
        samples <- round(pool, votes, window).replicateA(7)
        _ <- verifying(pool, committee, 500) // warm-up
        verify <- verifying(pool, committee, 2000)
        _ <- IO.println(
          f"BENCH leios-notify votes, tracing $tracing, window $window: " +
            f"${median(samples.map(_._1))}%8.2f us wall, ${median(samples.map(_._2))}%8.2f us cpu a vote; " +
            f"one signature check $verify%8.2f us"
        )
      yield ()
    }

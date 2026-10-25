// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** Local generated traffic checks, not exported Haskell observations or scheduler equivalence. */
class MuxSeededTrafficSuite extends FailsOnUncaught:
  private final case class Lane(protocol: Int, role: Mux.Role)
  private final case class Sample(
      seed: Long,
      sdu: Int,
      salt: Int,
      leftOrder: Vector[Vector[Lane]],
      rightOrder: Vector[Vector[Lane]]
  )
  private final case class Flow(
      lane: Lane,
      outgoing: MuxChannel[IO],
      incoming: MuxChannel[IO],
      messages: Vector[Bytes]
  )

  private val lanes =
    for protocol <- Vector(2, 3); role <- Vector(Mux.Role.Initiator, Mux.Role.Responder)
    yield Lane(protocol, role)
  private val rounds = 5

  private def opposite(role: Mux.Role): Mux.Role = role match
    case Mux.Role.Initiator => Mux.Role.Responder
    case Mux.Role.Responder => Mux.Role.Initiator

  private def sample(seed: Long, sdu: Int): Sample =
    val permutation = Gen.listOfN(lanes.size, Gen.choose(-1000000, 1000000)).map { priorities =>
      lanes.zip(priorities).sortBy(_._2).map(_._1)
    }
    val order = Gen.listOfN(rounds, permutation).map(_.toVector)
    val generator = for
      salt <- Gen.choose(0, 255)
      left <- order
      right <- order
    yield Sample(seed, sdu, salt, left, right)
    generator(Gen.Parameters.default, Seed(seed)).getOrElse(fail(s"generation failed: $seed"))

  private def messages(value: Sample, side: Int, lane: Lane): Vector[Bytes] =
    val sizes = Vector(1, (value.sdu - 1).max(1), value.sdu, value.sdu + 1, 2 * value.sdu + 1)
    val role = if lane.role == Mux.Role.Initiator then 0 else 1
    sizes.zipWithIndex.map { (size, ordinal) =>
      Bytes(Vector.tabulate(size) { index =>
        (value.salt + side * 101 + lane.protocol * 31 + role * 43 + ordinal * 17 +
          index * 29 + (index >>> 8)).toByte
      })
    }

  /** Each pair of Mux writes is coalesced, then repartitioned independently of its SDU/message
    * boundaries. Each round submits an even number of SDUs per direction; no final half-pair is
    * withheld. Keep writes as queue elements until pairing: a byte stream may merge their chunk
    * boundaries.
    */
  private def partitioned(
      incoming: Queue[IO, Option[Chunk[Byte]]],
      outgoing: Queue[IO, Option[Chunk[Byte]]],
      groups: Ref[IO, Vector[Int]],
      sdu: Int
  ): Bearer[IO] = new Bearer[IO]:
    def reads = Stream
      .fromQueueNoneTerminated(incoming)
      .chunkN(2, allowFewer = true)
      .evalMap { writes =>
        groups.update(_ :+ writes.size).as(Chunk.concat(writes.toVector))
      }
      .flatMap { bytes =>
        val widths = Vector(1, 7, 9, sdu + 17)
        Stream
          .unfold((0, 0)) { (offset, part) =>
            if offset >= bytes.size then None
            else
              val piece = bytes.drop(offset).take(widths(part % widths.size))
              Some((piece, (offset + piece.size, part + 1)))
          }
          .flatMap(Stream.chunk)
      }
    def write(bytes: Chunk[Byte]) = outgoing.offer(Some(bytes))
    def close = outgoing.offer(None)

  private def exactly(size: Int)(bytes: Bytes): DecodeResult[Bytes] =
    if bytes.size >= size then DecodeResult.Decoded(bytes.take(size), size)
    else DecodeResult.NeedMore

  private def flows(
      from: Mux[IO],
      to: Mux[IO],
      value: Sample,
      side: Int
  )(using Peers.Net): IO[Vector[Flow]] =
    lanes.traverse { lane =>
      (from.channel(lane.protocol, lane.role), to.channel(lane.protocol, opposite(lane.role)))
        .mapN((outgoing, incoming) => Flow(lane, outgoing, incoming, messages(value, side, lane)))
    }

  private def send(
      traffic: Vector[Flow],
      order: Vector[Vector[Lane]],
      ready: Deferred[IO, Unit],
      submitted: Vector[Deferred[IO, Unit]],
      peerSubmitted: Vector[Deferred[IO, Unit]]
  )(using Peers.Net): IO[Unit] =
    ready.get *> order.zipWithIndex.traverse_ { (round, ordinal) =>
      round.traverse_ { lane =>
        val flow = traffic.find(_.lane == lane).getOrElse(fail(s"missing generated lane $lane"))
        flow.outgoing.send(flow.messages(ordinal))
      } *> submitted(ordinal).complete(()) *> peerSubmitted(ordinal).get
    }

  private def run(value: Sample)(using Peers.Net): IO[Unit] =
    val maxMessage = 2 * value.sdu + 1
    val config = Mux.Config(
      duplex = true,
      maxSduPayload = value.sdu,
      ingressLimit = _ => (maxMessage * rounds).toLong,
      egressBufferBytes = 2L * maxMessage
    )
    for
      toLeft <- Queue.bounded[IO, Option[Chunk[Byte]]](4)
      toRight <- Queue.bounded[IO, Option[Chunk[Byte]]](4)
      leftGroups <- Ref.of[IO, Vector[Int]](Vector.empty)
      rightGroups <- Ref.of[IO, Vector[Int]](Vector.empty)
      _ <- (
        Mux.resource(partitioned(toLeft, toRight, leftGroups, value.sdu), Set(2, 3), config),
        Mux.resource(
          partitioned(toRight, toLeft, rightGroups, value.sdu),
          Set(2, 3),
          config.copy(role = Mux.Role.Responder)
        )
      ).tupled.use { (left, right) =>
        for
          leftFlows <- flows(left, right, value, 0)
          rightFlows <- flows(right, left, value, 1)
          all = leftFlows ++ rightFlows
          ready <- Deferred[IO, Unit]
          readers <- Ref.of[IO, Int](0)
          leftSubmitted <- Vector.fill(rounds)(()).traverse(_ => Deferred[IO, Unit])
          rightSubmitted <- Vector.fill(rounds)(()).traverse(_ => Deferred[IO, Unit])
          receiveAll = all.parTraverse_ { flow =>
            readers.updateAndGet(_ + 1).flatMap { count =>
              ready.complete(()).void.whenA(count == all.size)
            } *> flow.messages.traverse_ { expected =>
              flow.incoming.recv(maxMessage)(exactly(expected.size)).flatMap { actual =>
                IO(assertEquals(actual, expected, s"seed ${value.seed}, lane ${flow.lane}"))
              }
            }
          }
          _ <- (
            send(leftFlows, value.leftOrder, ready, leftSubmitted, rightSubmitted),
            send(rightFlows, value.rightOrder, ready, rightSubmitted, leftSubmitted),
            receiveAll
          ).parTupled
        yield ()
      }
      observed <- (leftGroups.get, rightGroups.get).tupled
      _ <- IO {
        val framesPerLane =
          messages(value, 0, lanes.head).map(b => (b.size + value.sdu - 1) / value.sdu).sum
        val expected = Vector.fill(lanes.size * framesPerLane / 2)(2)
        assertEquals(observed, (expected, expected), "every transport group coalesced two writes")
      }
    yield ()

  // Record a finite matrix instead of relying on a randomly chosen CI seed. Sizes straddle both
  // small SDUs and the full uint16 boundary; each case changes cross-channel submission order.
  for (seed, sdu) <- Vector(
      490301L -> 1,
      490302L -> 7,
      490303L -> 256,
      490304L -> 65535,
      490305L -> 1,
      490306L -> 7,
      490307L -> 256,
      490308L -> 65535
    )
  do
    test(
      s"seed $seed preserves duplex channel FIFO across $sdu-byte SDUs and transport partitions"
    ):
      val value = sample(seed, sdu)
      assert((value.leftOrder ++ value.rightOrder).forall(_.toSet == lanes.toSet))
      assert(value.leftOrder.distinct.size > 1 && value.rightOrder.distinct.size > 1)
      TestControl
        .executeEmbed(Peers.typed(run(value)))
        .map(result => assertEquals(result, Right(())))

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{PeerError, GenesisInternalProblem}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{FailureClass, Gsm}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

class NodeGenesisHistorySuite extends FailsOnUncaught:
  import NodeGenesisHistory.*
  private val base = Path.of("fixtures/reference/genesis-historicity")
  private def field[A: Decoder](j: Json, key: String): A =
    j.hcursor.get[A](key).fold(e => fail(e.message), identity)
  private def json(s: String): Json = parse(s).fold(e => fail(e.message), identity)
  private def point(j: Json): Point =
    val slot = field[BigInt](j, "slot")
    if slot < 0 then Point.Origin
    else
      Point.Block(
        UInt64.saturating(slot),
        Bytes.fromArray(field[String](j, "hash").getBytes(StandardCharsets.UTF_8))
      )
  private def typed(body: Handle[IO, PeerError] ?=> IO[Unit]): IO[Either[PeerError, Unit]] =
    Handle
      .allow[PeerError](body.as(Right(()): Either[PeerError, Unit]))
      .rescue(e => IO.pure(Left(e)))
  private val rows =
    Files.readString(base.resolve("historicity.jsonl")).linesIterator.map(json).toVector
  test("native historicity pin, all message/state/time boundaries and payload hashes") {
    val manifest = json(Files.readString(base.resolve("manifest.json")))
    assertEquals(
      field[String](manifest, "sourceCommit"),
      "82ecba329d7d054340bf707d44fe6e9ac27cec40"
    )
    def digest(name: String) = MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(base.resolve(name)))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(digest("historicity.jsonl"), field[String](manifest, "sha256"))
    assertEquals(digest("historicity.jsonl.errors.jsonl"), field[String](manifest, "errorsSha256"))
    assertEquals(rows.size, 72)
    assertEquals(rows.size, field[Int](manifest, "cases"))
  }
  rows.zipWithIndex.foreach { (row, index) =>
    test(s"native historicity boundary $index") {
      val message = Message.valueOf(field[String](row, "message"))
      val header = HeaderTime(point(field[Json](row, "point")), field[BigInt](row, "slotTime"))
      val phase = Gsm.Phase.valueOf(field[String](row, "phase"))
      val arrival = field[BigInt](row, "arrivalTime")
      val cutoff = field[BigInt](row, "cutoff")
      val expected = field[Option[Json]](row, "failure")
        .map { failure =>
          val at = point(field[Json](failure, "point"))
          val slot = field[BigInt](failure, "slotTime")
          val time = field[BigInt](failure, "arrivalTime")
          val limit = field[BigInt](failure, "cutoff")
          assertEquals(field[String](failure, "command"), "ShutdownPeer")
          message match
            case Message.AwaitReply =>
              PeerError.GenesisProblem.HistoricalAwait(at, slot, time, limit)
            case Message.RollBackward =>
              PeerError.GenesisProblem.HistoricalRollback(at, slot, time, limit)
        }
        .toLeft(())
      for
        reads <- Ref.of[IO, Int](0)
        check = runtime(true, IO.pure(phase), reads.update(_ + 1).as(arrival), cutoff)
        observed <- typed(message match
          case Message.AwaitReply   => check.awaitReply(header)
          case Message.RollBackward => check.rollBackward(Some(header)))
        clocks <- reads.get
      yield
        assertEquals(judge(message, header, phase, arrival, cutoff), expected)
        assertEquals(observed, expected.leftMap(PeerError.Genesis(_)))
        assertEquals(clocks, field[Int](row, "clockReads"))
        assertEquals(cutoff, DefaultCutoffPicos)
    }
  }
  test("zero-depth rollback and disabled historicity avoid phase and clock effects") {
    for
      reads <- Ref.of[IO, Int](0)
      phase = reads.update(_ + 1).as(Gsm.Phase.Syncing)
      clock = reads.update(_ + 1).as(DefaultCutoffPicos * 2)
      _ <- typed(runtime(true, phase, clock).rollBackward(None))
      _ <- typed(runtime(false, phase, clock).awaitReply(HeaderTime(Point.Origin, 0)))
      count <- reads.get
    yield assertEquals(count, 0)
  }
  test("production lazy entry point skips header resolution when disabled or caught up") {
    for
      reads <- Ref.of[IO, Int](0)
      header = reads.update(_ + 1).as(HeaderTime(Point.Origin, 0))
      clock = reads.update(_ + 1).as(DefaultCutoffPicos + 1)
      disabled <- typed(
        runtime(false, IO.pure(Gsm.Phase.Syncing), clock).at(Message.AwaitReply, header)
      )
      caughtUp <- typed(
        runtime(true, IO.pure(Gsm.Phase.CaughtUp), clock).at(Message.RollBackward, header)
      )
      count <- reads.get
      active <- typed(
        runtime(true, IO.pure(Gsm.Phase.Syncing), clock).at(Message.AwaitReply, header)
      )
    yield
      assertEquals(disabled, Right(()))
      assertEquals(caughtUp, Right(()))
      assertEquals(count, 0)
      assertEquals(
        active,
        Left(
          PeerError.Genesis(
            PeerError.GenesisProblem
              .HistoricalAwait(Point.Origin, 0, DefaultCutoffPicos + 1, DefaultCutoffPicos)
          )
        )
      )
  }
  test("history checks use each caller's error capability") {
    val check = runtime(true, IO.pure(Gsm.Phase.Syncing), IO.pure(DefaultCutoffPicos + 1))
    for
      first <- typed(check.awaitReply(HeaderTime(Point.Origin, 0)))
      second <- typed(check.awaitReply(HeaderTime(Point.Origin, 0)))
    yield
      assert(first.isLeft)
      assertEquals(second, first)
  }
  test("native Genesis ChainSync failures stop the connection and remain eligible after backoff") {
    val errors = Files
      .readString(base.resolve("historicity.jsonl.errors.jsonl"))
      .linesIterator
      .map(json)
      .toVector
    val causes = Map(
      "LoPExpired" -> PeerError.GenesisProblem.LoPExpired,
      "DensityTooLow" -> PeerError.GenesisProblem.DensityTooLow,
      "InvalidJumpResponse" -> PeerError.GenesisProblem.InvalidJumpResponse(Point.Origin, None)
    )
    assertEquals(errors.size, 3)
    errors.foreach { row =>
      assertEquals(field[String](row, "outbound"), "ShutdownPeer")
      assertEquals(field[String](row, "inbound"), "ShutdownPeer")
      val problem =
        causes.getOrElse(field[String](row, "error"), fail("unknown native Genesis failure"))
      val error = PeerError.Genesis(problem)
      assertEquals(FailurePolicy.classify(error), FailureClass.Transient)
      assertEquals(FailurePolicy.disposition(SessionEnd.Failed(error)), Disposition.Backoff)
    }
    assertEquals(
      FailurePolicy.disposition(SessionEnd.of(GenesisInternalProblem.MissingDynamoJumpInfo)),
      Disposition.StopNode(Fatal.Internal(GenesisInternalProblem.MissingDynamoJumpInfo))
    )
  }

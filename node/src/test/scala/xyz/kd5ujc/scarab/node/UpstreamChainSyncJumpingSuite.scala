// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping

class UpstreamChainSyncJumpingSuite extends FunSuite:
  import ChainSyncJumping.*
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
  private def info(j: Json): JumpInfo[Unit] =
    JumpInfo(point(field[Json](j, "anchor")), field[Vector[Json]](j, "headers").map(point), ())
  private def optionalInfo(j: Json, key: String): Option[JumpInfo[Unit]] =
    field[Option[Json]](j, key).map(info)
  private def role(j: Json): Role[Unit] = field[String](j, "tag") match
    case "Dynamo" => Role.Dynamo(optionalInfo(j, "starting"), field[BigInt](j, "last"))
    case "Objector" =>
      Role.Objector(
        field[Boolean](j, "starting"),
        info(field[Json](j, "good")),
        point(field[Json](j, "bad"))
      )
    case "Disengaged" => Role.Disengaged(field[Boolean](j, "restart"))
    case "Jumper" =>
      val state = field[Json](j, "state")
      val jumper = field[String](state, "tag") match
        case "Happy" => Jumper.Happy(field[Boolean](state, "fresh"), optionalInfo(state, "good"))
        case "Looking" =>
          Jumper.Looking(info(field[Json](state, "good")), info(field[Json](state, "bad")))
        case "Found" =>
          Jumper.Found(
            field[Boolean](state, "starting"),
            info(field[Json](state, "good")),
            point(field[Json](state, "bad"))
          )
        case other => fail(s"unknown native jumper $other")
      Role.Jumping(optionalInfo(j, "pending"), jumper)
    case other => fail(s"unknown native role $other")
  private def instruction(j: Json): Instruction[Unit] = field[String](j, "tag") match
    case "RunNormally" => Instruction.RunNormally
    case "Restart"     => Instruction.Restart
    case "Wait"        => Instruction.Wait
    case "Jump" => Instruction.Jump(info(field[Json](j, "info")), field[Boolean](j, "goodPoint"))
    case other  => fail(s"unknown native instruction $other")
  private def makeInfo(n: Int): JumpInfo[Unit] = JumpInfo(
    Point.Origin,
    (1 to n).toVector.map(i =>
      Point.Block(
        UInt64.saturating(i),
        Bytes.fromArray(
          s"(testHashFromList ${Vector.fill(i)(0).mkString("[", ",", "]")})"
            .getBytes(StandardCharsets.UTF_8)
        )
      )
    ),
    ()
  )
  private val rows = Files
    .readString(Path.of("fixtures/reference/genesis-chain-sync/csj.jsonl"))
    .linesIterator
    .map(json)
    .toVector
  test("native CSJ corpus pin, complete seed set and payload hash") {
    val base = Path.of("fixtures/reference/genesis-chain-sync")
    val manifest = json(Files.readString(base.resolve("manifest.json")))
    assertEquals(
      field[String](manifest, "sourceCommit"),
      "82ecba329d7d054340bf707d44fe6e9ac27cec40"
    )
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(base.resolve("csj.jsonl")))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(digest, field[String](manifest, "sha256"))
    assertEquals(rows.size, field[Int](manifest, "traces"))
    assertEquals(rows.size, 76)
    assertEquals(
      rows.map(j => field[Vector[Json]](j, "steps").size).sum,
      field[Int](manifest, "steps")
    )
    assertEquals(
      rows.map(j => field[String](j, "label")).filter(_.startsWith("generated-")).toSet,
      (0 to 63).map(i => s"generated-$i").toSet
    )
  }
  rows.foreach { row =>
    test(s"native CSJ ${field[String](row, "label")}") {
      field[Vector[Json]](row, "steps").foldLeft(
        (State[Int, Unit](), Map.empty[Int, (JumpInfo[Unit], Boolean)])
      ) { case ((s, pending), step) =>
        val event = field[String](step, "event").split(" ").toVector
        val id = event.lift(1).fold(fail("missing native peer id"))(_.toInt)
        val (after, saved) = event.headOption match
          case Some("Register") => (register(s, id, Point.Origin, event.last == "True"), pending)
          case Some("Update")   => (update(s, id, makeInfo(event.last.toInt)), pending)
          case Some("Forward") => (onRollForward(s, id, makeInfo(event.last.toInt).tip, 4), pending)
          case Some("Backward") => (onRollback(s, id, makeInfo(event.last.toInt).tip), pending)
          case Some("Await")    => (onAwaitReply(s, id), pending)
          case Some("Remove")   => (remove(s, id), pending)
          case Some("Rotate")   => (rotate(s, id), pending)
          case Some("Next") =>
            val result = next(s, id)
            assertEquals(result.instruction, instruction(field[Json](step, "outcome")), clue(event))
            val saved = result.instruction match
              case Instruction.Jump(info, good) => pending.updated(id, info -> good)
              case _                            => pending
            (result.state, saved)
          case Some("Answer") =>
            pending.get(id) match
              case Some((info, good)) =>
                val outcome = field[Json](step, "outcome")
                assertEquals(
                  instruction(field[Json](outcome, "answered")),
                  Instruction.Jump(info, good)
                )
                assertEquals(field[Boolean](outcome, "accepted"), event.last == "True")
                val result = processJump(s, id, info, good, event.last == "True")
                (result.state, pending - id)
              case None => (s, pending)
          case other => fail(s"unknown native event $other")
        val expected = field[Vector[Json]](step, "peers").map(j =>
          Peer(
            field[Int](j, "id"),
            role(field[Json](j, "role")),
            optionalInfo(j, "info"),
            point(field[Json](j, "tip"))
          )
        )
        assertEquals(after.peers, expected, clue(event))
        assertEquals(after.error, None)
        (after, saved)
      }
    }
  }

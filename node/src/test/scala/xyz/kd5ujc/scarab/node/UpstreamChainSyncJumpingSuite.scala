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
  private def isDynamo(role: Role[Unit]): Boolean = role match
    case Role.Dynamo(_, _) => true
    case _                 => false
  private def isObjector(role: Role[Unit]): Boolean = role match
    case Role.Objector(_, _, _) => true
    case _                      => false
  private def roleOf(s: State[Int, Unit], id: Int): Option[Role[Unit]] =
    s.peers.find(_.id == id).map(_.role)
  private def holder(s: State[Int, Unit], role: Role[Unit] => Boolean): Option[Int] =
    s.peers.find(p => role(p.role)).map(_.id)

  /** What the reference traces for a step, read from the native roles before and after it alone
    * (consensus 82ecba329, Jumping.hs:842-1075): the first dynamo of a registration; a dynamo or an
    * objector that loses its role names whoever holds it after, for the reason of the step; a
    * dynamo that moves its last jump sends that jump; a jumper that becomes the objector names the
    * objector it replaced; a rotation that changes the dynamo names both.
    */
  private def expected(
      step: String,
      id: Int,
      before: State[Int, Unit],
      after: State[Int, Unit]
  ): (Option[Event[Int]], Option[RotatedDynamo[Int]]) =
    val was = roleOf(before, id)
    val now = roleOf(after, id)
    val reason = if step == "Remove" then Reason.Disconnect else Reason.Disengage
    // a rotation is traced on the Devoted BlockFetch tracer only (consensus 82ecba329, Jumping.hs:908-947)
    val event: Option[Event[Int]] =
      if step == "Rotate" then None
      else
        (was, now) match
          case (None, Some(role)) if isDynamo(role) => Some(Event.InitializedAsDynamo)
          case (Some(role), next) if isDynamo(role) && !next.exists(isDynamo) =>
            Some(Event.NoLongerDynamo(holder(after, isDynamo), reason))
          case (Some(role), next) if isObjector(role) && !next.exists(isObjector) =>
            Some(Event.NoLongerObjector(holder(after, isObjector), reason))
          case (Some(Role.Dynamo(_, last)), Some(Role.Dynamo(_, moved)))
              if step == "Forward" && (moved != last || before.peers != after.peers) =>
            before.peers.find(_.id == id).flatMap(_.info).map(i => Event.SentJumpInstruction(i.tip))
          case (Some(role), Some(next)) if !isObjector(role) && isObjector(next) =>
            Some(Event.BecomingObjector(holder(before, isObjector).filter(_ != id)))
          case _ => None
    val rotated =
      Option
        .when(step == "Rotate")((holder(before, isDynamo), holder(after, isDynamo)))
        .collect { case (Some(old), Some(fresh)) if old != fresh => RotatedDynamo(old, fresh) }
    (event, rotated)

  rows.foreach { row =>
    test(s"native CSJ ${field[String](row, "label")}") {
      field[Vector[Json]](row, "steps").foldLeft(
        (State[Int, Unit](), Map.empty[Int, (JumpInfo[Unit], Boolean)])
      ) { case ((s, pending), step) =>
        val event = field[String](step, "event").split(" ").toVector
        val id = event.lift(1).fold(fail("missing native peer id"))(_.toInt)
        def traced(step: Step[Int, Unit, Event[Int]]) =
          (step.state, pending, step.event, Option.empty[RotatedDynamo[Int]])
        val (after, saved, csj, dbf) = event.headOption match
          case Some("Register") => traced(register(s, id, Point.Origin, event.last == "True"))
          case Some("Update")   => (update(s, id, makeInfo(event.last.toInt)), pending, None, None)
          case Some("Forward")  => traced(onRollForward(s, id, makeInfo(event.last.toInt).tip, 4))
          case Some("Backward") => traced(onRollback(s, id, makeInfo(event.last.toInt).tip))
          case Some("Await")    => traced(onAwaitReply(s, id))
          case Some("Remove")   => traced(remove(s, id))
          case Some("Rotate") =>
            val rotated = rotate(s, id)
            (rotated.state, pending, None, rotated.event)
          case Some("Next") =>
            val result = next(s, id)
            assertEquals(result.instruction, instruction(field[Json](step, "outcome")), clue(event))
            val saved = result.instruction match
              case Instruction.Jump(info, good) => pending.updated(id, info -> good)
              case _                            => pending
            (result.state, saved, None, None)
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
                (result.state, pending - id, result.event, None)
              case None => (s, pending, None, None)
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
        // the step's trace is the one its native roles imply
        assertEquals(
          (csj, dbf),
          this.expected(event.head, id, s, after),
          clue(event)
        )
        (after, saved)
      }
    }
  }

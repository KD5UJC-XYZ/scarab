// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.protocol.ChainSync.UInt64

class ChainSyncPipelineDecisionSuite extends FunSuite:
  import ChainSyncPipelineDecision.*

  test("all actual native stateful decisions agree, including Word64 wrapping and hysteresis") {
    val lines =
      Files.readAllLines(Path.of("fixtures/network/chainsync-pipeline/decisions.jsonl")).asScala
    assertEquals(lines.size, 7)
    val total = lines.map { line =>
      val json = parse(line).fold(e => fail(e.message), identity).hcursor
      def field[A: io.circe.Decoder](name: String): A =
        json.get[A](name).fold(e => fail(e.message), identity)
      val window =
        Window.checked(field[Int]("low"), field[Int]("high")).fold(e => fail(e.toString), identity)
      val steps = field[List[io.circe.Json]]("steps")
      steps.foldLeft(Policy(window)) { (policy, entry) =>
        val c = entry.hcursor
        def get[A: io.circe.Decoder](name: String): A =
          c.get[A](name).fold(e => fail(e.message), identity)
        def tip(name: String): Option[UInt64] =
          get[Option[BigInt]](name).map(n => UInt64.from(n).fold(e => fail(e.message), identity))
        val (action, next) = policy
          .decide(get[Int]("outstanding"), tip("client"), tip("server"))
          .fold(e => fail(e.toString), identity)
        assertEquals(action.toString, get[String]("decision"), entry.toString)
        next
      }
      steps.size
    }.sum
    assertEquals(total, 798)
  }

  test("window and occupancy validation is typed and bounded to native Word16 marks") {
    assertEquals(Window.checked(-1, 300), Left(Error.InvalidMarks(-1, 300)))
    assertEquals(Window.checked(300, 200), Left(Error.InvalidMarks(300, 200)))
    assertEquals(Window.checked(0, 65536), Left(Error.InvalidMarks(0, 65536)))
    assertEquals(Policy().decide(-1, None, None), Left(Error.NegativeOutstanding(-1)))
  }

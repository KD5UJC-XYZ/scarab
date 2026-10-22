// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.state.{Bound, Past}
import xyz.kd5ujc.scarab.protocol.Era

class GenesisHistorySuite extends FunSuite:
  import GenesisHistory.*

  private val parameters = Map(
    Era.Byron -> Parameters(100, 20, 20),
    Era.Shelley -> Parameters(200, 60, 60),
    Era.Allegra -> Parameters(200, 60, 60),
    Era.Mary -> Parameters(200, 60, 60)
  )

  test(
    "unknown transition rounds successor of immutable tip plus safe zone to an exclusive epoch bound"
  ) {
    val current = Current(Era.Byron, Bound.zero, None)
    def at(tip: BigInt, slot: BigInt) = query(Vector.empty, current, tip, slot, parameters)
    assertEquals(at(-1, 99), Some(BigInt(20)))
    assertEquals(at(-1, 100), None)
    assertEquals(at(79, 99), Some(BigInt(20)))
    assertEquals(at(79, 100), None)
    assertEquals(at(80, 199), Some(BigInt(20)))
    assertEquals(at(80, 200), None)
  }

  test("known transition exposes exactly one future era from its own safe horizon") {
    val current = Current(Era.Byron, Bound.zero, Some(2))
    def at(slot: BigInt) = query(Vector.empty, current, 0, slot, parameters)
    assertEquals(at(199), Some(BigInt(20)))
    assertEquals(at(200), Some(BigInt(60)))
    assertEquals(at(399), Some(BigInt(60)))
    assertEquals(at(400), None)
  }

  test("past bounds and current era start come from the immutable ledger telescope") {
    val end = Bound(0, 300, 3)
    val past = Vector(Past(Bound.zero, end))
    val current = Current(Era.Shelley, end, None)
    def at(slot: BigInt) = query(past, current, 300, slot, parameters)
    assertEquals(at(299), Some(BigInt(20)))
    assertEquals(at(300), Some(BigInt(60)))
    assertEquals(at(499), Some(BigInt(60)))
    assertEquals(at(500), None)
    assertEquals(at(-1), None)
  }

  test("zero-length eras do not claim their transition slot") {
    val current = Current(Era.Shelley, Bound.zero, Some(0))
    assertEquals(query(Vector.empty, current, -1, 0, parameters), Some(BigInt(60)))
    assertEquals(query(Vector.empty, current, -1, 200, parameters), None)
  }

  test("all native immutable-ledger summary query outcomes match, including rejected horizons") {
    val rows = Files
      .readAllLines(Path.of("fixtures/reference/genesis-density/history-window.jsonl"))
      .asScala
      .toVector
    assertEquals(rows.size, 250)
    rows.zipWithIndex.foreach { (line, ordinal) =>
      val result = for
        json <- parse(line)
        c = json.hcursor
        past <- c.get[Boolean]("past")
        tip <- c.get[BigInt]("tipSlot")
        transition <- c.get[Option[BigInt]]("transitionEpoch")
        slot <- c.get[BigInt]("slot")
        expected <- c.get[Option[BigInt]]("window")
        rendering <- c.get[String]("completeOutcomeRendering")
      yield
        val end = Bound(0, 300, 3)
        val history = if past then Vector(Past(Bound.zero, end)) else Vector.empty
        val current = Current(
          if past then Era.Shelley else Era.Byron,
          if past then end else Bound.zero,
          transition
        )
        assertEquals(
          query(history, current, tip, slot, parameters),
          expected,
          s"native row $ordinal: $rendering"
        )
      result.fold(error => fail(error.toString), identity)
    }
  }

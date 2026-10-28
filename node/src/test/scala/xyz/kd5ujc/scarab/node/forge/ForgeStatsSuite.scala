// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import scala.compiletime.testing.typeChecks
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.Reports
import xyz.kd5ujc.scarab.protocol.ChainSync

class ForgeStatsSuite extends CatsEffectSuite:
  test("the counters take the loop's typed traces, never strings") {
    assert(!typeChecks("""
      import cats.effect.IO
      import xyz.kd5ujc.scarab.node.forge.ForgeStats
      ForgeStats.create[IO].flatMap(_.record(0, "noLedgerView"))
    """))
    assert(typeChecks("""
      import cats.effect.IO
      import xyz.kd5ujc.scarab.node.forge.{ForgeStats, ForgeTrace}
      ForgeStats.create[IO].flatMap(_.record(0, ForgeTrace.NodeNotLeader(BigInt(1))))
    """))
  }

  test("each trace counts once, across epochs, under the public report keys") {
    val origin = ChainSync.Point.Origin
    val range = OutsideForecastRange(None, 10, 10)
    val traces = Vector(
      0 -> ForgeTrace.StartLeadershipCheck(1),
      0 -> ForgeTrace.SlotIsImmutable(1, origin, 0),
      1 -> ForgeTrace.StartLeadershipCheck(11),
      1 -> ForgeTrace.BlockFromFuture(11, 12),
      1 -> ForgeTrace.StartLeadershipCheck(12),
      1 -> ForgeTrace.NoLedgerView(12, range),
      1 -> ForgeTrace.StartLeadershipCheck(13),
      1 -> ForgeTrace.NodeNotLeader(13),
      1 -> ForgeTrace.StartLeadershipCheck(14),
      1 -> ForgeTrace.NodeIsLeader(14),
      1 -> ForgeTrace.ForgedBlock(14, origin, Bytes(Vector(1)), 0, None),
      1 -> ForgeTrace.DidntAdoptBlock(14, Bytes(Vector(1)))
    )
    for
      stats <- ForgeStats.create[IO]
      _ <- traces.traverse_((epoch, t) => stats.record(epoch, t))
      _ <- stats.replaced(1)
      _ <- stats.failed(1)
      total <- stats.total
      first <- stats.epoch(0)
    yield
      assertEquals(first.slotsChecked, 1L)
      val report = Json.obj(Reports.forgeStats(total)*)
      def count(key: String) = report.hcursor.get[Long](key)
      assertEquals(count("slotsChecked"), Right(5L))
      assertEquals(count("leaderSlots"), Right(1L))
      assertEquals(count("notLeader"), Right(1L))
      assertEquals(count("forged"), Right(1L))
      assertEquals(count("lostSlotBattles"), Right(1L))
      assertEquals(count("replaced"), Right(1L))
      assertEquals(count("failed"), Right(1L))
      assertEquals(count("skipped"), Right(3L))
      assertEquals(
        report.hcursor.get[Map[String, Long]]("skippedByReason"),
        Right(Map("slotIsImmutable" -> 1L, "blockFromFuture" -> 1L, "noLedgerView" -> 1L))
      )
  }

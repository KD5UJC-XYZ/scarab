// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import scala.compiletime.testing.typeChecks
import xyz.kd5ujc.scarab.node.Reports

class ForgeSkipSuite extends CatsEffectSuite:
  test("skip counters reject strings and detailed events reject rendered messages") {
    assert(!typeChecks("""
      import cats.effect.IO
      import xyz.kd5ujc.scarab.node.forge.ForgeStats
      ForgeStats.create[IO].flatMap(_.skipped(0, "busy"))
    """))
    assert(!typeChecks("""
      import xyz.kd5ujc.scarab.node.forge.ForgeEvent
      ForgeEvent.Skipped(BigInt(1), "busy")
    """))
    assert(typeChecks("""
      import xyz.kd5ujc.scarab.node.forge.{ForgeEvent, ForgeSkip}
      ForgeEvent.Skipped(BigInt(1), ForgeSkip.TipTooOld(Some(BigInt(0))))
    """))
  }

  test("typed reasons merge across epochs and retain the public report keys") {
    for
      stats <- ForgeStats.create[IO]
      _ <- stats.skipped(0, ForgeSkip.Kind.Busy)
      _ <- stats.skipped(1, ForgeSkip.Kind.Busy)
      _ <- stats.skipped(1, ForgeSkip.Kind.TipTooOld)
      _ <- stats.skipped(1, ForgeSkip.Kind.BlockFromFuture)
      _ <- stats.skipped(1, ForgeSkip.Kind.SlotIsImmutable)
      _ <- stats.skipped(1, ForgeSkip.Kind.NotConnected)
      total <- stats.total
    yield
      val report = Json.obj(Reports.forgeStats(total)*)
      assertEquals(report.hcursor.get[Long]("skipped"), Right(6L))
      assertEquals(
        report.hcursor.get[Map[String, Long]]("skippedByReason"),
        Right(
          Map(
            "busy" -> 2L,
            "tipTooOld" -> 1L,
            "blockFromFuture" -> 1L,
            "slotIsImmutable" -> 1L,
            "notConnected" -> 1L
          )
        )
      )
  }

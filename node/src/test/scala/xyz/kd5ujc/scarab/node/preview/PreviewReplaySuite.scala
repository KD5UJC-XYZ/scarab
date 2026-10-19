// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.IO
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught

class PreviewReplaySuite extends FailsOnUncaught:
  override def munitIOTimeout: Duration = 2.hours

  test("original Preview blocks preserve complete state and ordered phase2 results") {
    assume(
      sys.env.contains("SCARAB_PREVIEW_CAPTURE") || sys.env.contains("SCARAB_PREVIEW_WINDOWS"),
      "requires an offline Preview capture or window manifest"
    )
    val configs = sys.env.get("SCARAB_PREVIEW_WINDOWS") match
      case None =>
        IO.pure(Vector(ReplayInputs.config(sys.env).fold(e => fail(e.toString), identity)))
      case Some(path) =>
        IO.blocking {
          val lines = Files.readAllLines(Path.of(path)).asScala.toVector
          ReplayInputs.windows(lines, sys.env).fold(e => fail(e.toString), identity)
        }
    configs.flatMap(_.traverse_(c => RecordedReplay.replay(c).flatMap(j => IO.println(j.noSpaces))))
  }

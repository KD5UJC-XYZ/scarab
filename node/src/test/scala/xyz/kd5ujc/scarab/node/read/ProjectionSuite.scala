// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import io.circe.Json
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.protocol.Era

/** The K1 projection is the same from the flat state as it was before it was written over the read
  * paths (the recorded digests, `fixtures/projection-golden`), and the same from the mirror of the
  * state.
  */
class ProjectionSuite extends FunSuite:
  private val recorded: Vector[String] =
    Files.readAllLines(ProjectionGolden.file).asScala.toVector

  private lazy val shelleyBased = ErasStates.samples.zipWithIndex.filter(_._1.era != Era.Byron)

  test("the recorded digests cover every state of the capture") {
    assertEquals(recorded.size, ErasStates.samples.size)
  }

  test("the flat state projects to the recorded text, in every Shelley-based era") {
    shelleyBased.foreach { (s, i) =>
      assertEquals(
        ProjectionGolden.line(i, s),
        recorded(i),
        s"${s.era} block ${s.block.header.blockNo}"
      )
    }
  }

  test("the mirror of the state projects to the same text as the state") {
    val different = shelleyBased.flatMap { (s, _) =>
      ProjectionDiff
        .firstDifferenceOfMirror(ProjectionGolden.ofFlat(s), ProjectionGolden.ofMirror(s))
        .map(d => s"${s.era} block ${s.block.header.blockNo}: $d")
    }
    assert(different.isEmpty, different.take(5).mkString("\n"))
  }

  test("a chain in Byron projects its consensus part") {
    val byron = ErasStates.samples.filter(_.era == Era.Byron)
    assert(byron.nonEmpty)
    byron.foreach { s =>
      val json = ProjectionGolden.ofFlat(s)
      assertEquals(json.hcursor.keys.map(_.toSet), Some(Set("point", "protocolState")))
      assertEquals(json, ProjectionGolden.ofMirror(s))
    }
  }

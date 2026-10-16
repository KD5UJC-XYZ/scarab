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

  /** The first path at which two projections differ. */
  private def firstDifference(a: Json, b: Json, path: String = ""): Option[String] =
    (a.asObject, b.asObject) match
      case (Some(x), Some(y)) =>
        (x.keys.toSet ++ y.keys.toSet).toVector.sorted.view.flatMap { k =>
          (x(k), y(k)) match
            case (Some(l), Some(r)) => firstDifference(l, r, s"$path/$k")
            case _                  => Some(s"$path/$k is in one only")
        }.headOption
      case _ => Option.when(a != b)(s"$path: ${a.noSpaces.take(200)} / ${b.noSpaces.take(200)}")

  /** The flat state keeps one set of parameter fields for every era, a mirror has zero in those the
    * era has no place for (and the reference does not have them: the comparison with it ignores the
    * keys it lacks).
    */
  /** Neither is in the mirror of a flat state: the fields of the parameters the era has no place
    * for, and the amount each credential is to be rewarded (the mirror attributes them to pools,
    * which the flat state does not know: `Gap.RewardAttribution`).
    */
  private def dropEraFields(j: Json): Json =
    val params = j.hcursor
      .downField("params")
      .withFocus(
        _.mapObject(_.remove("minUTxOValue").remove("utxoCostPerWord").remove("utxoCostPerByte"))
      )
      .top
      .getOrElse(j)
    params.hcursor
      .downField("rewardUpdate")
      .withFocus(_.mapObject(_.remove("rs")))
      .top
      .getOrElse(params)

  test("the mirror of the state projects to the same text as the state") {
    val different = shelleyBased.flatMap { (s, _) =>
      firstDifference(
        dropEraFields(ProjectionGolden.ofFlat(s)),
        dropEraFields(ProjectionGolden.ofMirror(s))
      )
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

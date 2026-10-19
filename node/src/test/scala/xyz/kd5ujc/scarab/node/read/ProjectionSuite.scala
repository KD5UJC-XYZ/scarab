// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import io.circe.Json
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.state.MirrorStep
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.PulsingRewUpdate

/** The K1 projection of the state of the era-indexed rules is the same as it was before it was
  * written over the read paths, when it was made from the flat state of the old rules (the recorded
  * digests, `fixtures/projection-golden`).
  */
class ProjectionSuite extends FunSuite:
  private val recorded: Vector[String] =
    Files.readAllLines(ProjectionGolden.file).asScala.toVector

  private lazy val shelleyBased = ErasStates.samples.zipWithIndex.filter(_._1.era != Era.Byron)

  test("the recorded digests cover every state of the capture") {
    assertEquals(recorded.size, ErasStates.samples.size)
  }

  test("the mirror projects to the recorded text, in every Shelley-based era") {
    shelleyBased.foreach { (s, i) =>
      assertEquals(
        ProjectionGolden.line(i, s),
        recorded(i),
        s"${s.era} block ${s.block.header.blockNo}"
      )
    }
  }

  test("a chain in Byron projects its consensus part") {
    val byron = ErasStates.samples.filter(_.era == Era.Byron)
    assert(byron.nonEmpty)
    byron.foreach { s =>
      val json = ProjectionGolden.ofMirror(s)
      assertEquals(json.hcursor.keys.map(_.toSet), Some(Set("point", "protocolState")))
    }
  }

  test("a native pending reward update stays absent from the completed-reward projection") {
    val pending = shelleyBased
      .map(_._1)
      .filter(s =>
        MirrorStep.nesOf(s.mirror).exists { case i: EraNes.InEra[e] =>
          i.nes.ru.exists {
            case PulsingRewUpdate.Pulsing(_, _) => true
            case _                              => false
          }
        }
      )
    assertEquals(pending.size, 19)
    pending.foreach(s =>
      assertEquals(
        ProjectionGolden.ofMirror(s).hcursor.get[Json]("rewardUpdate").toOption,
        Some(Json.Null)
      )
    )
  }

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** The node-to-client answers do not change: every ledger query on the Conway states of the eras
  * capture (and the two states built on the last one, see [[NtcGolden]]) answers the bytes recorded
  * in `fixtures/ntc-golden`.
  */
class NtcGoldenSuite extends FunSuite:
  private val recorded: Map[(String, String), String] =
    Files
      .readAllLines(NtcGolden.file)
      .asScala
      .map(_.split("\t", -1).toList)
      .collect { case state :: label :: value :: Nil => (state, label) -> value }
      .toMap

  test("the fixture covers the states and every query") {
    assertEquals(NtcGolden.subjects.size, 5)
    assert(recorded.size >= 5 * 50, recorded.size.toString)
  }

  NtcGolden.subjects.foreach { subject =>
    test(s"${subject._1}: the answers are the recorded ones") {
      NtcGolden.answers(subject).foreach { (state, label, answer) =>
        val expected = recorded.getOrElse((state, label), fail(s"$state / $label is not recorded"))
        assertEquals(NtcGolden.value(answer), expected, s"$state / $label")
      }
    }
  }

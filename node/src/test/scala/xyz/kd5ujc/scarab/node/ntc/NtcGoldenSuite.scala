// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.Files
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import scala.jdk.CollectionConverters.*

/** The node-to-client answers do not change: the historical ledger-query battery on the Conway
  * states of the eras capture (and the two states built on the last one, see [[NtcGolden]]) answers
  * the bytes recorded in `fixtures/ntc-golden`.
  */
class NtcGoldenSuite extends FunSuite:
  private def load(lines: Vector[String]): Map[(String, String), String] =
    assert(lines.nonEmpty, s"${NtcGolden.file}: empty golden corpus")
    lines.zipWithIndex.foldLeft(Map.empty[(String, String), String]) {
      case (entries, (line, index)) =>
        def invalid(detail: String): Nothing = fail(s"${NtcGolden.file}:${index + 1}: $detail")
        line.split("\t", -1).toVector match
          case Vector(state, label, value) if state.nonEmpty && label.nonEmpty =>
            val payload = value.stripPrefix("sha256:")
            val valid = payload.nonEmpty && Bytes.fromHex(payload).isRight &&
              (!value.startsWith("sha256:") || payload.length == 64)
            if !valid then invalid("expected answer hex or a complete SHA-256 digest")
            val key = state -> label
            if entries.contains(key) then invalid(s"duplicate golden key $key")
            entries.updated(key, value)
          case _ => invalid("expected nonempty state, query and value separated by two tabs")
    }

  private val recorded = load(Files.readAllLines(NtcGolden.file).asScala.toVector)
  private lazy val actual =
    NtcGolden.subjects.map(subject => subject._1 -> NtcGolden.answers(subject)).toMap

  test("the fixture covers exactly the subjects and query keys") {
    val names = NtcGolden.subjects.map(_._1)
    assertEquals(names.toSet, Set("conway-0", "conway-1", "conway-2", "rich", "rich-genesis"))
    assertEquals(names.distinct.size, names.size, "subject names must be unique")
    val keys = actual.values.toVector.flatten.map((state, query, _) => state -> query)
    assertEquals(keys.distinct.size, keys.size, "query keys must be unique within a subject")
    def inventory(rows: Map[(String, String), String]): Unit =
      assertEquals(rows.size, 265, "changing the original corpus requires explicit review")
      assertEquals(rows.keySet, keys.toSet, "missing and stale extra query rows both fail")
    inventory(recorded)
    val first = recorded.keys.headOption.getOrElse(fail("the fixture must contain query keys"))
    intercept[munit.ComparisonFailException](inventory(recorded.removed(first)))
    intercept[munit.ComparisonFailException](
      inventory(recorded.updated("conway-0" -> "stale-extra", "00"))
    )
    intercept[munit.ComparisonFailException](
      inventory(recorded.removed(first).updated("conway-0" -> "stale-extra", "00"))
    )
  }

  test("malformed, duplicate and empty golden corpora fail rather than losing rows") {
    Vector(
      Vector.empty,
      Vector("state\tquery"),
      Vector("state\tquery\t00\textra"),
      Vector("state\t\t00"),
      Vector("state\tquery\tnot-hex"),
      Vector("state\tquery\tsha256:00"),
      Vector("state\tquery\t00", "state\tquery\t01")
    ).foreach(lines => intercept[munit.FailException](load(lines)))
  }

  NtcGolden.subjects.foreach { subject =>
    test(s"${subject._1}: the answers are the recorded ones") {
      actual(subject._1).foreach { (state, label, answer) =>
        val expected = recorded.getOrElse((state, label), fail(s"$state / $label is not recorded"))
        assertEquals(NtcGolden.value(answer), expected, s"$state / $label")
      }
    }
  }

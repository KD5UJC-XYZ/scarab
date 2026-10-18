// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import munit.FunSuite

class TextFormatErrorSuite extends FunSuite:
  test("line feed refusal is a distinct format case") {
    assertEquals(TextFormat.parse("metric 1"), Left(TextFormat.Error.MissingLineFeed))
  }

  test("the line wrapper retains the source line and semantic family cause") {
    assertEquals(TextFormat.parse("metric 1\n"), Left(TextFormat.Error.Line(1, "metric 1", TextFormat.Error.MissingFamily("metric"))))
  }

  test("invalid label escapes retain the character and source position") {
    val line = "metric{label=\"bad\\t\"} 1"
    assertEquals(TextFormat.parse(s"# HELP metric text\n# TYPE metric gauge\n$line\n"), Left(TextFormat.Error.Line(3, line, TextFormat.Error.Escape('t'))))
  }

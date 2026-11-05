// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx

class ReferenceDocumentSuite extends FunSuite:
  import ReferenceDocument.*

  test("a later group chooses its own alternative during an earlier fit") {
    val first = vsep(Vector(text("a"), text("b"))).group
    val later = Doc.Alternative(text(" long"), text(" c")).group
    assertEquals((first ++ later).renderWith(Mode.Pretty, 5), "a b c")
  }

  // Original prettyprinter 1.7.2 Internal.hs layoutSmart example, lines1949-1985.
  private val nestedCalls =
    (0 until 5).foldLeft(list(Vector(text("abcdef"), text("ghijklm"))).align) { (body, _) =>
      (text("fun(") ++ lineBreak.group ++ body).hang(2) ++ text(")")
    }

  test("layoutPretty retains the upstream nested-call layout") {
    assertEquals(
      nestedCalls.renderWith(Mode.Pretty, 26),
      "fun(fun(fun(fun(fun(\n                  [ abcdef\n                  , ghijklm ])))))"
    )
  }

  test("layoutSmart examines the upstream nested-call continuation") {
    assertEquals(
      nestedCalls.renderWith(Mode.Smart, 26),
      "fun(\n  fun(\n    fun(\n      fun(\n        fun(\n          [ abcdef\n          , ghijklm ])))))"
    )
  }

  test("hard lines cannot be flattened and empty lines carry no trailing indentation") {
    val document = (text("x") ++ Doc.Line ++ Doc.Line ++ text("y")).nest(4).group
    assertEquals(document.render, "x\n\n    y")
    assertEquals((text("x") ++ Doc.Line).nest(4).render, "x\n")
  }

  test("Pretty Text newlines remain flattenable, as in the native instance") {
    assertEquals(text("hello\nworld").group.render, "hello world")
  }

  test("the unbounded layouter flattens width-limited alternatives") {
    val document = vsep(Vector(text("abc"), text("def"))).group
    assertEquals(document.renderWith(Mode.Unbounded, 1), "abc def")
  }

  test("input-controlled document depth is stack safe") {
    val document = (0 until 30000).foldLeft[Doc](Doc.Empty)((done, _) => done ++ text("x"))
    val rendered = PreprodDeepTx.onSmallStack(document.render)
    assertEquals(rendered.length, 30000)
  }

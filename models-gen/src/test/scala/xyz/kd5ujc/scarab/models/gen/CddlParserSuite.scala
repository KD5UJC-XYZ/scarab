// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.models.gen.Ast.*

class CddlParserSuite extends munit.FunSuite:
  private def parse(text: String): File =
    CddlParser.parse(text).fold(e => fail(e.message), identity)
  private def body(text: String): Ty = parse(text).rules.head.body

  test("rules, size control and bytes") {
    val r = parse("hash32 = bytes .size 32\n").rules.head
    assertEquals(r.name, "hash32")
    assertEquals(r.body, Ty.Ctrl(Ty.Name("bytes", Vector.empty), "size", Ty.Literal(Lit.IntL(32))))
  }

  test("ranges, with and without spaces, and a size range") {
    assertEquals(
      body("a = 0 .. 12"),
      Ty.Range(Ty.Literal(Lit.IntL(0)), Ty.Literal(Lit.IntL(12)), true)
    )
    assertEquals(body("a = 0..1"), Ty.Range(Ty.Literal(Lit.IntL(0)), Ty.Literal(Lit.IntL(1)), true))
    assert(body("a = bytes .size (0 .. 32)").isInstanceOf[Ty.Ctrl])
  }

  test("arrays with named entries, occurrence indicators and nested choices") {
    val Ty.Array(g) = body("x = [ a : uint, ? b : bytes, * c, 2* d, +e, 1*3 f ]"): @unchecked
    val es = g.choices.head
    assertEquals(
      es.map(_.occ),
      Vector(Occ.Once, Occ.Optional, Occ.Star, Occ(2, None), Occ.Plus, Occ(1, Some(3)))
    )
    assertEquals(es.head.key, Some(Key.Bare("a")))
  }

  test("type choice versus group choice") {
    assertEquals(body("a = b / c / d").asInstanceOf[Ty.Choice].alts.size, 3)
    val Ty.Array(g) = body("a = [ b // c ]"): @unchecked
    assertEquals(g.choices.size, 2)
    val Ty.Array(g2) = body("a = [ b / c ]"): @unchecked
    assertEquals(g2.choices.size, 1)
  }

  test("maps with integer keys, optional entries and typed keys") {
    val Ty.MapT(g) = body("m = { 0 : a, ? 3 : b, * k => v, ? \"s\" : c }"): @unchecked
    val keys = g.choices.head.map(_.key)
    assertEquals(
      keys,
      Vector(
        Some(Key.Value(Lit.IntL(0))),
        Some(Key.Value(Lit.IntL(3))),
        Some(Key.OfType(Ty.Name("k", Vector.empty), false)),
        Some(Key.Value(Lit.TextL("s")))
      )
    )
  }

  test("tags, generics, sockets, extensions and comments") {
    assertEquals(
      body("s<a> = #6.258([* a]) ; set"),
      Ty.Tag(
        6,
        Some(BigInt(258)),
        Some(Ty.Array(Group(Vector(Vector(Entry(Occ.Star, None, Ty.Name("a", Vector.empty)))))))
      )
    )
    val f = parse("$x /= 1\n$x /= 2\ng<a, b> = [a, b]\nh = g<uint, tstr>\n")
    assertEquals(
      f.rules.map(_.assign),
      Vector(Assign.TypeChoice, Assign.TypeChoice, Assign.Eq, Assign.Eq)
    )
    assertEquals(f.rules(2).params, Vector("a", "b"))
    assert(body("a = #6.24(bytes .cbor foo)").isInstanceOf[Ty.Tag])
  }

  test("a parenthesised group rule and a parenthesised type") {
    assert(body("a = (1, b)").isInstanceOf[Ty.GroupT])
    assertEquals(body("a = (b)"), Ty.Name("b", Vector.empty))
  }

  test("pragmas are collected") {
    val f = parse(";# import network.base as base\n;# include byron as byron\na = 1\n")
    assertEquals(
      f.pragmas,
      Vector(Pragma("import", "network.base", "base"), Pragma("include", "byron", "byron"))
    )
  }

  test("errors carry a position") {
    val e = CddlParser.parse("a = [\nb = c").left.toOption.get
    assert(e.line >= 1 && e.column >= 1)
  }

  private val roots =
    Vector(Paths.get("models/src/main/cddl"), Paths.get("../models/src/main/cddl"))

  test("every pinned upstream .cddl file parses") {
    val root = roots.find(Files.isDirectory(_)).getOrElse(fail("cddl directory not found"))
    val files = Files.walk(root).iterator().asScala.filter(_.toString.endsWith(".cddl")).toVector
    assert(files.size >= 20)
    files.foreach { f =>
      CddlParser.parse(Files.readString(f)) match
        case Right(parsed) => assert(parsed.rules.nonEmpty, s"$f has no rules")
        case Left(e)       => fail(s"$f: ${e.message}")
    }
  }

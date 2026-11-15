// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.warts

import org.wartremover.test.WartTestTraverser

/** Each kind of site of the wart, and what it does not count as a decision. A fixture is typed when
  * the suite compiles and never runs.
  */
class StringControlFlowSuite extends munit.FunSuite:

  private def kinds(r: WartTestTraverser.Result): List[String] =
    r.errors.map(_.stripPrefix("[wartremover:StringControlFlow] ").takeWhile(_ != ':'))

  test("equality of two strings, with a literal or not"):
    val r = WartTestTraverser(StringControlFlow) {
      val a = "x"
      val b = "y"
      (a == b, a != "z", a.equals(b), a.equalsIgnoreCase(b))
    }
    assertEquals(kinds(r), List.fill(4)("string-equality"))

  test("equality of other values, and a comparison with null, are not sites"):
    val r = WartTestTraverser(StringControlFlow) {
      val a = "x"
      (1 == 2, Option(a) == Some("y"), List(a) == Nil, a == null)
    }
    assertEquals(r.errors, Nil)

  test("predicates on a string, on String and on StringOps"):
    val r = WartTestTraverser(StringControlFlow) {
      val a = "x"
      (a.startsWith("x"), a.endsWith("y"), a.contains("z"), a.matches("x+"), a.contains('c'))
    }
    assertEquals(kinds(r), List.fill(5)("string-predicate"))

  test("regex matching: find methods and an extractor pattern"):
    val r = WartTestTraverser(StringControlFlow) {
      val a = "ab"
      val Pair = "(a)(b)".r
      val found = "a+".r.findFirstIn(a)
      val parts = a match
        case Pair(x, y) => x + y
        case _          => ""
      (found, parts)
    }
    assertEquals(kinds(r), List("regex-match", "regex-match"))

  test("a match on a string with literal patterns"):
    val r = WartTestTraverser(StringControlFlow) {
      val a = "x"
      a match
        case "x" | "y" => 1
        case _         => 2
    }
    assertEquals(kinds(r), List("string-match"))

  test("a match on a string without literal patterns is not a site"):
    val r = WartTestTraverser(StringControlFlow) {
      val a = "x"
      a match
        case s if s.length > 1 => 1
        case _                 => 2
    }
    assertEquals(r.errors, Nil)

  test("lookups by a computed key in a Map[String, _] or a Set[String], not by a literal"):
    val r = WartTestTraverser(StringControlFlow) {
      val key = "k"
      val m = Map("k" -> 1)
      val s = Set("k")
      (
        m.get(key),
        m(key),
        m.contains(key),
        s.contains(key),
        s(key),
        m.getOrElse(key, 0),
        m.get("k"),
        m.getOrElse("k", 0)
      )
    }
    assertEquals(kinds(r), List.fill(6)("string-keyed-lookup"))

  test("a site inside a lambda is reported"):
    val r = WartTestTraverser(StringControlFlow) {
      List("a", "b").filter(s => s == "a" || s.startsWith("b"))
    }
    assertEquals(kinds(r), List("string-equality", "string-predicate"))

  test("a lookup in a map with other keys is not a site"):
    val r = WartTestTraverser(StringControlFlow) {
      val m = Map(1 -> "one")
      (m.get(1), m.contains(2))
    }
    assertEquals(r.errors, Nil)

  test("each message names STR-3"):
    val r = WartTestTraverser(StringControlFlow)((a: String) => a == "x")
    assertEquals(
      r.errors.map(_.dropWhile(_ != ' ').drop(1)),
      List(s"string-equality: ${Rules.Str3}")
    )

  test("a value of an opaque type over String, inside the type's scope, is not a site"):
    val r = WartTestTraverser(StringControlFlow) {
      object Keys:
        opaque type Key = String
        def same(a: Key, b: Key): Boolean = a == b
        def find(table: Map[Key, Int], key: Key): Option[Int] = table.get(key)
      Keys
    }
    assertEquals(r.errors, Nil)

  test("the equality of a type that holds a string is not a site, other kinds in it are"):
    val r = WartTestTraverser(StringControlFlow) {
      final class Name(val text: String):
        override def equals(that: Any): Boolean = that match
          case o: Name => text == o.text
          case _       => false
        override def hashCode: Int = text.hashCode
      final case class Label(text: String)
      def sameLabel(a: Label, b: Label): Boolean = a.text == b.text
      def similar(a: Label, b: Label): Boolean = a.text.startsWith(b.text)
      def sameText(a: String, b: String): Boolean = a == b
      (
        new Name("a"),
        sameLabel(Label("a"), Label("b")),
        similar(Label("a"), Label("b")),
        sameText("a", "b")
      )
    }
    assertEquals(kinds(r), List("string-predicate", "string-equality"))

  test("a decision whose value is a String decides only what is written"):
    val r = WartTestTraverser(StringControlFlow) {
      def short(node: String): String = if node.contains('.') then node else node.take(8)
      def label(mode: String): String = mode match
        case "a" => "first"
        case _   => "other"
      def count(node: String): Int = if node.contains('.') then 1 else 8
      (short("a"), label("a"), count("a"))
    }
    assertEquals(kinds(r), List("string-predicate"))

  test("the lookup of an upsert is not a site; a lookup in another map is"):
    val r = WartTestTraverser(StringControlFlow) {
      def merge(a: Map[String, Int], b: Map[String, Int]): Map[String, Int] =
        b.foldLeft(a) { case (acc, (label, n)) =>
          acc.updated(label, acc.get(label).fold(n)(_ + n))
        }
      def cross(a: Map[String, Int], b: Map[String, Int]): Map[String, Int] =
        b.foldLeft(a) { case (acc, (label, n)) => acc.updated(label, b.get(label).fold(n)(_ + n)) }
      (merge(Map.empty, Map.empty), cross(Map.empty, Map.empty))
    }
    assertEquals(kinds(r), List("string-keyed-lookup"))

  test("a macro implementation is not a site"):
    val r = WartTestTraverser(StringControlFlow) {
      def field(label: String)(using q: scala.quoted.Quotes): Boolean =
        label == "x" && q.reflect.Symbol.noSymbol.isNoSymbol
      def runtime(label: String): Boolean = label == "x"
      (field(_: String)(using _: scala.quoted.Quotes), runtime("x"))
    }
    assertEquals(kinds(r), List("string-equality"))

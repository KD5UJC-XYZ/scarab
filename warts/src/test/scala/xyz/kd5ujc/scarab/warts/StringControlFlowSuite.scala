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

  test(
    "a choice between forms of the text it reads is not a site; another String-valued decision is"
  ):
    val r = WartTestTraverser(StringControlFlow) {
      def short(node: String): String = if node.contains('.') then node else node.take(8)
      def absolute(name: String): String = name match
        case "." => name
        case _   => name + "."
      def label(mode: String): String = mode match
        case "a" => "first"
        case _   => "other"
      def count(node: String): Int = if node.contains('.') then 1 else 8
      def quota(role: String): Int =
        val amount: String = if role == "admin" then "1000" else "1"
        amount.toInt
      (short("a"), absolute("a"), label("a"), count("a"), quota("a"))
    }
    assertEquals(kinds(r), List("string-match", "string-predicate", "string-equality"))

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

  test("in an equality definition, a comparison with a literal is a site"):
    val r = WartTestTraverser(StringControlFlow) {
      final case class Principal(role: String)
      def mayTransfer(a: Principal, b: Principal): Boolean =
        a.role == "admin" || b.role == "auditor"
      List(Principal("a")).sortWith((a, b) => a.role == "x" && b.role.nonEmpty)
      mayTransfer(Principal("a"), Principal("b"))
    }
    assertEquals(kinds(r), List.fill(3)("string-equality"))

  test("a lookup in another map that the same field names is not an upsert"):
    val r = WartTestTraverser(StringControlFlow) {
      final case class Holder(table: Map[String, Int])
      def merge(a: Holder, b: Holder, key: String): Map[String, Int] =
        a.table.updated(key, b.table.getOrElse(key, 0))
      def upsert(a: Holder, key: String): Map[String, Int] =
        a.table.updated(key, a.table.getOrElse(key, 0) + 1)
      (merge(Holder(Map.empty), Holder(Map.empty), "k"), upsert(Holder(Map.empty), "k"))
    }
    assertEquals(kinds(r), List("string-keyed-lookup"))

  test("an opaque value compared with a raw String is a site"):
    val r = WartTestTraverser(StringControlFlow) {
      object Keys:
        opaque type Key = String
        def matches(a: Key, raw: String): Boolean = a == raw
      Keys
    }
    assertEquals(kinds(r), List("string-equality"))

  test("the cats === is a site"):
    val r = WartTestTraverser(StringControlFlow) {
      import cats.syntax.all.*
      def permitted(s: String): Boolean = s === "admin"
      ((s: String) => s =!= "guest", permitted("a"))
    }
    assertEquals(kinds(r), List.fill(2)("string-equality"))

  test("an equality with Any is a site"):
    val r = WartTestTraverser(StringControlFlow)((s: String, other: Any) => s.equals(other))
    assertEquals(kinds(r), List("string-equality"))

  test("the arguments of a call to a definition kept by @SuppressWarnings are checked"):
    val r = WartTestTraverser(StringControlFlow) {
      @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.StringControlFlow"))
      def kept(b: Boolean): Boolean = b
      kept("a" == "b".reverse)
    }
    assertEquals(kinds(r), List("string-equality"))

  test("a String-valued decision between other values of the same field is a site"):
    val r = WartTestTraverser(StringControlFlow) {
      final case class Cell(text: String)
      def quota(role: Cell, privileged: Cell, regular: Cell): Int =
        (if role.text == "admin" then privileged.text else regular.text).toInt
      def grade(role: Cell, privileged: Cell, regular: Cell): Int =
        (role.text match
          case "admin" => privileged.text
          case _       => regular.text
        ).toInt
      def trimmed(role: Cell): String =
        if role.text.endsWith(".") then role.text.dropRight(1) else role.text
      (
        quota(Cell("a"), Cell("1"), Cell("2")),
        grade(Cell("a"), Cell("1"), Cell("2")),
        trimmed(Cell("a"))
      )
    }
    assertEquals(kinds(r), List("string-equality", "string-match"))

  test("the cats === follows the rule of an equality definition"):
    val r = WartTestTraverser(StringControlFlow) {
      import cats.syntax.all.*
      final case class Label(text: String)
      def sameLabel(a: Label, b: Label): Boolean = a.text === b.text
      def isMain(a: Label, b: Label): Boolean = a.text === "main" && b.text.nonEmpty
      (sameLabel(Label("a"), Label("b")), isMain(Label("a"), Label("b")))
    }
    assertEquals(kinds(r), List("string-equality"))

  test("a branch that only reads the tested String is not a form of it; an alias of its path is"):
    val r = WartTestTraverser(StringControlFlow) {
      final case class Cell(text: String)
      def quota(role: Cell, privileged: Cell, regular: Cell): Int =
        (if role.text == "admin" then (role.text, privileged.text)._2
         else (role.text, regular.text)._2).toInt
      def joined(role: Cell, other: Cell): String =
        if role.text.isEmpty || role.text == "x" then role.text + other.text else role.text
      def trimmed(role: Cell): String =
        val same: Cell = role
        if role.text.endsWith(".") then same.text.dropRight(1) else same.text
      def dotted(value: String): String = if value.endsWith(".") then value else s"$value."
      (
        quota(Cell("a"), Cell("1"), Cell("2")),
        joined(Cell("a"), Cell("b")),
        trimmed(Cell("a")),
        dotted("a")
      )
    }
    assertEquals(kinds(r), List("string-equality", "string-equality"))

  test(
    "only augmentString is the conversion of a text form; a member val alias counts as its path"
  ):
    val r = WartTestTraverser(StringControlFlow) {
      import scala.collection.StringOps
      def quota(role: String, high: String => StringOps, low: String => StringOps): Int =
        (if role == "admin" then high(role).take(4) else low(role).take(1)).toInt
      final class Label(val text: String):
        val same: String = this.text
        def trimmed: String = if this.text.endsWith(".") then this.same.dropRight(1) else this.same
        def other(that: Label): String = if this.text.endsWith(".") then that.same else this.same
      (
        quota("a", new StringOps(_), new StringOps(_)),
        Label("a").trimmed,
        Label("a").other(Label("b"))
      )
    }
    assertEquals(kinds(r), List("string-equality", "string-predicate"))

  test("renamed imports of one member from two receivers are two paths"):
    val r = WartTestTraverser(StringControlFlow) {
      final class Cell(val text: String)
      def chosen(a: Cell, b: Cell): String =
        import a.text as tested
        import b.text as other
        if tested == "admin" then other else tested
      chosen(Cell("a"), Cell("b"))
    }
    assertEquals(kinds(r), List("string-equality"))

  test("a member alias rooted at an enclosing instance is not its receiver's path"):
    val r = WartTestTraverser(StringControlFlow) {
      class Outer(val text: String):
        class Inner extends Outer("1"):
          val same: String = Outer.this.text
          def chosen: String = if this.text == "admin" then this.same else this.text
      Outer("a")
    }
    assertEquals(kinds(r), List("string-equality"))

  test("an alias inside a path counts as its path; through another receiver or Outer.this, not"):
    val r = WartTestTraverser(StringControlFlow) {
      final class Cell(val text: String)
      final class Holder(val cell: Cell):
        val same: Cell = this.cell
        def trimmed: String =
          if this.cell.text.endsWith(".") then this.same.text.dropRight(1)
          else this.same.text
        def other(that: Holder): String =
          if this.cell.text.endsWith(".") then that.same.text.dropRight(1)
          else that.same.text
      class Outer(val cell: Cell):
        class Inner extends Outer(Cell("1")):
          val same: Cell = Outer.this.cell
          def chosen: String =
            if this.cell.text.endsWith(".") then this.same.text.dropRight(1)
            else this.same.text
      (Holder(Cell("a")).trimmed, Holder(Cell("a")).other(Holder(Cell("b"))), Outer(Cell("a")))
    }
    assertEquals(kinds(r), List("string-predicate", "string-predicate"))

  test("a member alias a subclass can override is not its path; in a final class it is"):
    val r = WartTestTraverser(StringControlFlow) {
      final class Cell(val text: String)
      class Holder(val cell: Cell):
        val same: Cell = this.cell
        def chosen: String =
          if this.cell.text.endsWith(".") then this.same.text.dropRight(1)
          else this.same.text
      class Other(c: Cell, override val same: Cell) extends Holder(c)
      final class Fixed(val cell: Cell):
        val same: Cell = this.cell
        def chosen: String =
          if this.cell.text.endsWith(".") then this.same.text.dropRight(1)
          else this.same.text
      (Other(Cell("a."), Cell("xyz")).chosen, Fixed(Cell("a")).chosen)
    }
    assertEquals(kinds(r), List("string-predicate"))


# SPDX-License-Identifier: Apache-2.0
"""scala_definitions: which headers are definitions, their keys, their lines and their digests."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import scala_definitions as defs


def keys(source: str) -> list[str]:
    return [d.key for d in defs.read(source).definitions]


def digest(source: str, key: str) -> str:
    return {d.key: d.digest for d in defs.read(source).definitions}[key]


SOURCE = '''// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab
package network

import a.{
  B,
  C
}

/** A protocol. */
object KeepAlive:
  val Id: Int = 8

  enum State:
    case Client, Server, Done

    def next: State = this match
      case Client => Server
      case _      => Client

  final case class Cookie(value: Int):
    def valid: Boolean = value >= 0
    private def helper: Int = value + 1

  object Cookie:
    def from(value: Int): Cookie =
      val local = value
      def inner(x: Int): Int = x
      Cookie(inner(local))

  given Ordering[Cookie] = Ordering.by(_.value)
  given named: Ordering[State] = Ordering.by(_.ordinal)

  def apply(a: Int): Int = a
  def apply(a: Int, b: Int): Int = a + b

  extension (c: Cookie)
    def plus(n: Int): Cookie = Cookie(c.value + n)
    private def hidden: Int = 0

  private val secret: Int = 0
end KeepAlive

private def topLevel(x: Int): Int = x
'''


class DefinitionsTest(unittest.TestCase):
    def test_members_get_keys_with_hash_after_a_class_and_dot_after_an_object(self):
        self.assertEqual(keys(SOURCE), [
            'object network.KeepAlive',
            'val network.KeepAlive.Id',
            'enum network.KeepAlive.State',
            'def network.KeepAlive.State#next',
            'class network.KeepAlive.Cookie',
            'def network.KeepAlive.Cookie#valid',
            'object network.KeepAlive.Cookie',
            'def network.KeepAlive.Cookie.from',
            'given network.KeepAlive.given_Ordering_Cookie',
            'given network.KeepAlive.named',
            'def network.KeepAlive.apply',
            'def network.KeepAlive.plus',
            'def network.topLevel',
        ])

    def test_every_line_is_read(self):
        self.assertEqual(defs.read(SOURCE).unread, ())

    def test_overloads_share_one_key_and_their_lines(self):
        apply = {d.key: d for d in defs.read(SOURCE).definitions}['def network.KeepAlive.apply']
        self.assertEqual(len(apply.lines), 2)
        changed = SOURCE.replace('def apply(a: Int, b: Int): Int = a + b', 'def apply(a: Int, b: Int): Int = a - b')
        self.assertNotEqual(digest(SOURCE, 'def network.KeepAlive.apply'),
                            digest(changed, 'def network.KeepAlive.apply'))

    def test_a_comment_or_blank_line_edit_changes_no_digest(self):
        edited = SOURCE.replace('/** A protocol. */', '/** A protocol, reworded. */') \
            .replace('  val Id: Int = 8\n', '  val Id: Int = 8 // the number\n\n')
        before = {d.key: d.digest for d in defs.read(SOURCE).definitions}
        after = {d.key: d.digest for d in defs.read(edited).definitions}
        self.assertEqual(before, after)

    def test_a_member_edit_changes_only_its_own_digest(self):
        edited = SOURCE.replace('def valid: Boolean = value >= 0', 'def valid: Boolean = value > 0')
        before = {d.key: d.digest for d in defs.read(SOURCE).definitions}
        after = {d.key: d.digest for d in defs.read(edited).definitions}
        changed = sorted(k for k in before if before[k] != after[k])
        self.assertEqual(changed, ['def network.KeepAlive.Cookie#valid'])

    def test_a_private_helper_or_a_local_definition_belongs_to_its_owner(self):
        edited = SOURCE.replace('private def helper: Int = value + 1', 'private def helper: Int = value + 2')
        self.assertNotEqual(digest(SOURCE, 'class network.KeepAlive.Cookie'),
                            digest(edited, 'class network.KeepAlive.Cookie'))
        local = SOURCE.replace('def inner(x: Int): Int = x', 'def inner(x: Int): Int = x + 1')
        self.assertNotEqual(digest(SOURCE, 'def network.KeepAlive.Cookie.from'),
                            digest(local, 'def network.KeepAlive.Cookie.from'))

    def test_an_extension_header_is_part_of_its_methods(self):
        edited = SOURCE.replace('extension (c: Cookie)', 'extension (c: Cookie)(using x: Int)')
        self.assertNotEqual(digest(SOURCE, 'def network.KeepAlive.plus'), digest(edited, 'def network.KeepAlive.plus'))
        self.assertEqual(digest(SOURCE, 'object network.KeepAlive'), digest(edited, 'object network.KeepAlive'))

    def test_a_literal_keeps_its_text_and_its_line_breaks(self):
        source = 'object A:\n  val s = """x\n\n  y"""\n'
        widened = 'object A:\n  val s = """x\n\n\n  y"""\n'
        self.assertNotEqual(digest(source, 'val A.s'), digest(widened, 'val A.s'))
        self.assertEqual(defs.read(source).unread, ())

    def test_companions_and_case_classes_have_keys_of_their_own(self):
        source = 'object A:\n  case class B(x: Int)\n  object B:\n    val y = 1\n  case object C\n'
        self.assertEqual(keys(source), ['object A', 'class A.B', 'object A.B', 'val A.B.y', 'object A.C'])

    def test_a_qualified_private_or_protected_member_is_a_definition(self):
        source = 'object A:\n  private[net] def a = 1\n  protected def b = 2\n  private[this] def c = 3\n'
        self.assertEqual(keys(source), ['object A', 'def A.a', 'def A.b'])

    def test_a_definition_after_a_brace_or_a_semicolon_on_a_line_is_unread(self):
        cases = {
            'object A { def hidden = 1 }\n': (1,),
            'import a.B; def hidden = 1\n': (1,),
            'package p { object A }\n': (1,),
            'object A:\n  val x = 1; def hidden = 2\n': (2,),
            'object A:\n  extension (x: Int) { def f = 1 }\n': (2,),
        }
        for source, unread in cases.items():
            with self.subTest(source=source):
                self.assertEqual(defs.read(source).unread, unread)
        # inside a method body, braces and semicolons are the method's own code
        self.assertEqual(defs.read('object A:\n  def f = { val a = 1; def g = a; g }\n').unread, ())
        # a class header's parameters are not a body
        self.assertEqual(defs.read('final class A(val x: Int, var y: Int):\n  def f = x\n').unread, ())

    def test_a_private_only_extension_block_belongs_to_its_owner(self):
        source = 'object A:\n  extension (x: Int)\n    private def f = 1\n'
        edited = source.replace('= 1', '= 2')
        self.assertNotEqual(digest(source, 'object A'), digest(edited, 'object A'))
        header = source.replace('(x: Int)', '(x: Long)')
        self.assertNotEqual(digest(source, 'object A'), digest(header, 'object A'))
        self.assertEqual(defs.read(source).unread, ())

    def test_white_space_outside_literals_is_one_space_and_inside_them_as_written(self):
        source = 'object A:\n  val x = 1 + 2\n'
        for same in ('object A:\n  val x = 1 /* c */ + 2\n', 'object A:\n  val x   =   1 + 2   \n'):
            with self.subTest(same=same):
                self.assertEqual(digest(source, 'val A.x'), digest(same, 'val A.x'))
        self.assertNotEqual(digest('object A:\n  val s = """abc \nend"""\n', 'val A.s'),
                            digest('object A:\n  val s = """abc  \nend"""\n', 'val A.s'))
        self.assertNotEqual(digest('object A:\n  val s = "a  b"\n', 'val A.s'),
                            digest('object A:\n  val s = "a b"\n', 'val A.s'))
        self.assertNotEqual(digest('object A:\n  def f =\n    a\n    b\n', 'def A.f'),
                            digest('object A:\n  def f =\n    a\n  b\n', 'def A.f'))

    def test_given_names(self):
        self.assertEqual(defs.given_name(' Ordering[Cookie] = Ordering.by(_.value)'), 'given_Ordering_Cookie')
        self.assertEqual(defs.given_name(' ord: Ordering[Int] = ???'), 'ord')
        self.assertEqual(defs.given_name(' [A](using Eq[A]): Eq[List[A]] with'), 'given_Eq_List_A')
        self.assertEqual(defs.given_name(' rewardShow(using Era): Show[Coin] ='), 'rewardShow')

    def test_a_pattern_val_is_named_by_its_pattern(self):
        self.assertEqual(keys('object A:\n  val (a, b) = (1, 2)\n'), ['object A', 'val A.(a,b)'])

    def test_an_annotation_on_the_line_before_is_part_of_the_definition(self):
        source = 'object A:\n  @tailrec\n  def f(n: Int): Int = if n == 0 then 0 else f(n - 1)\n'
        plain = source.replace('  @tailrec\n', '')
        self.assertNotEqual(digest(source, 'def A.f'), digest(plain, 'def A.f'))
        self.assertEqual(digest(source, 'object A'), digest(plain, 'object A'))

    def test_brace_bodies_and_multi_line_signatures(self):
        source = ('object A {\n  def f(\n    a: Int,\n    b: Int\n  ): Int = {\n    a + b\n  }\n  val x = 1\n}\n')
        self.assertEqual(keys(source), ['object A', 'def A.f', 'val A.x'])
        self.assertEqual(defs.read(source).unread, ())

    def test_an_unknown_top_level_line_is_unread(self):
        self.assertEqual(defs.read('package a\nstrange thing\nobject B\n').unread, (2,))

    def test_an_enum_case_is_not_a_definition_but_a_case_class_is(self):
        source = 'enum E:\n  case A(x: Int)\n  case B\nobject O:\n  case class C(y: Int)\n'
        self.assertEqual(keys(source), ['enum E', 'object O', 'class O.C'])

    def test_members_of_a_given_with_a_body_and_of_an_anonymous_class_are_local(self):
        source = ('object A:\n  given Show[Int] with\n    def show(i: Int): String = ""\n'
                  '  val x = new Runnable:\n    def run(): Unit = ()\n')
        self.assertEqual(keys(source), ['object A', 'given A.given_Show_Int', 'val A.x'])


if __name__ == '__main__':
    unittest.main()

# SPDX-License-Identifier: Apache-2.0
"""Regression controls for the source guard, including common alias evasions."""
import importlib.util
import sys
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("error_channels", Path(__file__).parents[1] / "check_error_channels.py")
GUARD = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = GUARD
SPEC.loader.exec_module(GUARD)


class ErrorChannelGuardSuite(unittest.TestCase):
    def test_rejects_string_lefts(self):
        for source in (
            "def read: Either[String, Bytes]",
            "def read: Either[String, (State, Result)]",
            "def read: Either[String, A => B]",
            "type Result[E,A] = Either[E,A]\ndef read: Result[String,(A,B)]",
            "def read: Either[\n String, Bytes]",
            "def read: scala.util.Either[java.lang.String, Bytes]",
            "def read: Either[_root_.scala.Predef.String, Bytes]",
            "type Error = String\ndef read: Either[Error, Bytes]",
            "type First = String\ntype Second = First\ndef read: Either[Second, Bytes]",
            "type Result[E,A] = Either[E,A]\ndef read: Result[String,Bytes]",
            "type Result[A] = Either[String,A]",
            "import scala.Predef.{String as Text}\ndef read: Either[Text,Bytes]",
            "import scala.Predef.{String => Text}\ndef read: Either[Text,Bytes]",
            "import scala.util.{Either as Result}\ndef read: Result[String,Bytes]",
            "type `Problem` = String\ndef read: Either[`Problem`,Bytes]",
        ):
            with self.subTest(source=source):
                self.assertTrue(GUARD.violations(source))

    def test_accepts_closed_errors_and_ignores_prose(self):
        for source in (
            "def read: Either[DecodeError, String]",
            "def read: Either[Nothing, Bytes]",
            "// Either[String, Bytes]\ndef read: Either[Error, Bytes]",
            'val template = "Either[String, Bytes]"',
            'val template = """Either[String, Bytes]"""',
            "/* outer /* Either[String, Bytes] */ nested */",
            "type Error = DecodeError\ndef read: Either[Error,Bytes]",
            "type Loop = Loop\ndef read: Either[Loop,Bytes]",
        ):
            with self.subTest(source=source):
                self.assertFalse(GUARD.violations(source))

    def test_cross_file_aliases_and_import_spelling(self):
        definitions = """package errors
object Types:
  type Message = String
  type Nested = Message
  type Result[+E, A] = Either[E, A]
"""
        for use in (
            "import errors.Types.Message\ndef read: Either[Message, Bytes]",
            "import errors.Types.{Nested as Problem}\ndef read: Either[Problem, Bytes]",
            "import errors.Types.{Nested => Problem}\ndef read: Either[Problem, Bytes]",
            "import errors.Types.*\ndef read: Either[Nested, Bytes]",
            "import errors.Types._\ndef read: Either[Nested, Bytes]",
            "import errors.Types as T\ndef read: T.Result[T.Nested, (State, Result)]",
            "import errors.Types.{Result as Outcome, Nested as Problem}\ndef read: Outcome[Problem, A => B]",
            "def read: errors.Types.Result[errors.Types.Nested, (State, Result)]",
        ):
            with self.subTest(use=use):
                found = GUARD.project_violations({"types.scala": definitions, "use.scala": use})
                self.assertIn("use.scala", found)
                self.assertNotIn("types.scala", found)

    def test_cross_file_generic_aliases_retain_their_definition_imports(self):
        found = GUARD.project_violations({
            "base.scala": "package first\nobject Types { type Result[E,A] = Either[E,A]; type Message = String }",
            "middle.scala": "package second\nimport first.Types.{Result as Base, Message as Error}\ntype Result[A] = Base[Error,A]",
            "use.scala": "package third\nimport second.Result as Outcome\ndef read: Outcome[(A,B)]",
        })
        self.assertIn("middle.scala", found)
        self.assertIn("use.scala", found)

    def test_opaque_string_domain_values_are_not_expanded(self):
        definitions = "package domain\nobject Types { opaque type UserId = String; type Id = UserId }"
        for use in (
            "import domain.Types.UserId\ndef read: Either[UserId, Bytes]",
            "import domain.Types.{Id as Key}\ndef read: Either[Key, Bytes]",
            "def read: Either[domain.Types.Id, Bytes]",
        ):
            with self.subTest(use=use):
                self.assertFalse(GUARD.project_violations({"types.scala": definitions, "use.scala": use}))
        self.assertFalse(GUARD.violations("opaque type Error = String\ndef read: Either[Error, Bytes]"))

    def test_names_do_not_leak_between_packages_or_sibling_objects(self):
        self.assertFalse(GUARD.project_violations({
            "text.scala": "package first\ntype Error = String",
            "read.scala": "package second\nenum Error { case Missing }; def read: Either[Error, Bytes]",
        }))
        self.assertFalse(GUARD.violations("""object First:
  type Error = String
object Second:
  type Error = DecodeError
  def read: Either[Error, Bytes]
"""))

    def test_generic_parameters_shadow_aliases_and_builtin_names(self):
        for source in (
            "type Error = String\ntype Result[Error, A] = Either[Error, A]",
            "type Error = String\ndef read[Error]: Either[Error, Bytes]",
            "type Result[String, A] = Either[String, A]",
            "type Result[E <: Product, A] = Either[E, A]\ndef read: Result[DecodeError, Bytes]",
        ):
            with self.subTest(source=source):
                self.assertFalse(GUARD.violations(source))
        self.assertTrue(GUARD.violations("type Result[E <: Product, A] = Either[E,A]\ndef read: Result[String, Bytes]"))

    def test_structural_right_types_and_string_union_lefts(self):
        for source in (
            "def read: Either[String, A { type Member = Int }]",
            "type Result[E,A] = Either[E,A]\ndef read: Result[String, A { type Member = Int }]",
            "def read: Either[(String), Bytes]",
            "def read: Either[String | DomainError, Bytes]",
            "def read: Either[String & Marker, Bytes]",
        ):
            with self.subTest(source=source):
                self.assertTrue(GUARD.violations(source))
        self.assertFalse(GUARD.violations("def read: Either[DecodeError, (String, A => B)]"))

    def test_comments_literals_and_precise_source_lines(self):
        source = '''// Either[String, A]
val literal = "Either[String,A]"
/* outer /* type Error = String */ still comment */
import scala.Predef.{String /* comment */ as Message}
def read: Either[
 Message,
 (A, B)
]
'''
        self.assertEqual(GUARD.violations(source), [5])
        self.assertFalse(GUARD.violations("val quote = '\\''; val bracket = '['; def read: Either[Error, String]"))

    def test_generic_parameter_scope_ends_at_the_declaration(self):
        self.assertTrue(GUARD.violations("type Result[String,A] = Either[String,A]; def read: Either[String,Bytes]"))
        self.assertTrue(GUARD.violations("def first[String]: Either[String,A] = value; def read: Either[String,Bytes]"))
        self.assertFalse(GUARD.violations("type Error = String match { case _ => DecodeError }; def read: Either[Error,Bytes]"))

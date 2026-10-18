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

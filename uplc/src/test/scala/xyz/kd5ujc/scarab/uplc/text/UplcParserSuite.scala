// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant as Con,
  DeBruijn,
  DefaultFun,
  DefaultUni,
  PlutusData,
  Program,
  ProgramVersion,
  SmallStack,
  Term,
  TermF,
  Text,
  Word64
}

/** The corners of the textual syntax, each against the reference source it transcribes (plutus
  * 1.70.0.0, `507cab8c`) or the reference parser's own observations
  * (`fixtures/reference/plutus-text`, produced with the pinned plutus-core).
  */
final class UplcParserSuite extends ScalaCheckSuite:

  private def program(body: String, version: String = "1.1.0"): Either[ProgramError, Program] =
    UplcParser.program(s"(program $version $body)")

  private def constant(literal: String): Either[ProgramError, Con] =
    program(s"(con $literal)").flatMap { p =>
      p.term.unfix match
        case TermF.Constant(c) => Right(c)
        case other             => fail(s"not a constant: $other")
    }

  /** The code points of the string constant `literal` (with its quotes), none when it is refused.
    */
  private def string(literal: String): Option[Vector[Int]] =
    constant("string " + literal).toOption.map {
      case Con.String(t) => t.string.codePoints().toArray.toVector
      case other         => fail(s"not a string: $other")
    }

  private def refusal(result: Either[ProgramError, ?]): TextError = result match
    case Left(ProgramError.Unparsable(e)) => e
    case other                            => fail(s"expected a refusal, got $other")

  // ---- the reference parser's own observations ----

  private val observations: Vector[Json] =
    Files
      .readString(Path.of("fixtures/reference/plutus-text/observations.jsonl"))
      .linesIterator
      .map(line => parser.parse(line).fold(e => fail(e.message), identity))
      .toVector

  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)

  test("the reference parser's string literals: values and refusals (plutus-text, kind parse)") {
    val rows = observations.filter(field[String](_, "kind") == "parse")
    assertEquals(rows.size, 9)
    rows.foreach { row =>
      assertEquals(
        string(field[String](row, "literal")),
        field[Option[Vector[Int]]](row, "codePoints"),
        field[String](row, "id")
      )
    }
  }

  test("Text.pack of separate code points, written as escapes (plutus-text, kind pack)") {
    val rows = observations.filter(field[String](_, "kind") == "pack")
    assertEquals(rows.size, 12)
    rows.foreach { row =>
      val id = field[String](row, "id")
      val literal = field[Vector[Int]](row, "inputCodePoints")
        .map(n => "\\x" + Integer.toHexString(n))
        .mkString("\"", "", "\"")
      val value = constant("string " + literal) match
        case Right(Con.String(t)) => t
        case other                => fail(s"$id: $other")
      assertEquals(
        value.string.codePoints().toArray.toVector,
        field[Vector[Int]](row, "codePoints"),
        id
      )
      assertEquals(value.utf8.value.map(_ & 0xff), field[Vector[Int]](row, "utf8"), id)
      assertEquals(value.utf8Length, field[Int](row, "bytes"), id)
    }
  }

  // ---- string escapes: base's `lexChar` through megaparsec's `charLiteral` (Builtin.hs:86-87) ----

  test("single-character, control and ASCII escapes; \\SOH before its prefix \\SO") {
    assertEquals(
      string("""" \a\b\f\n\r\t\v\\\"\' """"),
      Some(Vector(32, 7, 8, 12, 10, 13, 9, 11, 92, 34, 39, 32))
    )
    assertEquals(
      string(""""\^@\^A\^Z\^[\^\\^]\^^\^_""""),
      Some(Vector(0, 1, 26, 27, 28, 29, 30, 31))
    )
    assertEquals(string(""""\NUL\SOH\SO\SI\DEL\SP\US""""), Some(Vector(0, 1, 14, 15, 127, 32, 31)))
    assertEquals(string(""""\SOH""""), Some(Vector(1)))
    assertEquals(string(""""\SO\&H""""), Some(Vector(14, 72)))
    assertEquals(string(""""\^a""""), None)
    assertEquals(string(""""\q""""), None)
  }

  test("numeric escapes: decimal, octal and hexadecimal, at most U+10FFFF, digits taken greedily") {
    assertEquals(string(""""\65\o101\x41\X""""), None)
    assertEquals(string(""""\65\o101\x41""""), Some(Vector(65, 65, 65)))
    // `lexBaseChar` takes either case of `o` and `x`
    assertEquals(string(""""\O101\X41""""), Some(Vector(65, 65)))
    assertEquals(string(""""\1114111\x10ffff""""), Some(Vector(0x10ffff, 0x10ffff)))
    assertEquals(string(""""\1114112""""), None)
    assertEquals(string(""""\x""""), None)
    assertEquals(string(""""\o8""""), None)
    assertEquals(string(""""\1234\&5""""), Some(Vector(1234, '5'.toInt)))
  }

  // Beyond the observations, these rest on the published sources (megaparsec 9.7.0's
  // `charLiteral`, base 4.18.2.1's `lexChar`): they pin the transcription, not the reference's run.
  test("\\& is consumed only after a character; a character literal reads ten code points ahead") {
    assertEquals(string(""""A\&\&B""""), Some(Vector(65, 66)))
    // `lexCharE` has no escape `\&`
    assertEquals(string(""""\&A""""), None)
    // the escape is longer than the window: its first nine digits are read, the rest are characters
    assertEquals(string(""""\00000000065""""), Some(Vector(0, '6'.toInt, '5'.toInt)))
  }

  test("raw characters are code points; surrogate escapes become U+FFFD (Builtin.hs:80-85)") {
    assertEquals(string("\"λ😀\n\t\""), Some(Vector(955, 128512, 10, 9)))
    assertEquals(string(""""X\55296Z""""), Some(Vector(88, 0xfffd, 90)))
    assertEquals(string("\"unterminated"), None)
  }

  // ---- the grammar ----

  test("keywords have no word boundary (symbol, ParserCommon.hs:184-185)") {
    assertEquals(constant("integer1"), Right(Con.Integer(1)))
    assertEquals(constant("boolTrue"), Right(Con.Bool(true)))
    assertEquals(constant("data (I1)"), Right(Con.Data(PlutusData.I(1))))
  }

  test(
    "integers: an optional sign, spaces after it, and leading zeros (conInteger, Builtin.hs:66-67)"
  ) {
    assertEquals(constant("integer - 5"), Right(Con.Integer(-5)))
    assertEquals(constant("integer +007"), Right(Con.Integer(7)))
    assertEquals(refusal(constant("integer -")).isInstanceOf[TextError.Syntax], true)
  }

  test("types are kinded applications (Type.hs:93-101, checkStar in Builtin.hs:43-46)") {
    assertEquals(constant("(integer) 5"), Right(Con.Integer(5)))
    assertEquals(
      constant("((pair integer) bool) (1, True)"),
      Right(Con.Pair(Con.Integer(1), Con.Bool(true)))
    )
    assertEquals(refusal(constant("list []")), TextError.NotOfKindStar(Kinded.ListOf))
    assertEquals(
      refusal(constant("(pair integer) (1)")),
      TextError.NotOfKindStar(Kinded.PairWith(DefaultUni.Integer))
    )
    assertEquals(
      refusal(constant("(list list) []")),
      TextError.KindMismatch(Kinded.ListOf, Kinded.ListOf)
    )
    assertEquals(refusal(constant("(integer bool) 1")).isInstanceOf[TextError.KindMismatch], true)
  }

  test("lists and arrays: sepBy, so no trailing comma; data elements without parentheses") {
    assertEquals(
      constant("(list (list integer)) [[1, 2], [], [3]]").map(_.toString.nonEmpty),
      Right(true)
    )
    assertEquals(refusal(constant("(list integer) [1, 2,]")).isInstanceOf[TextError.Syntax], true)
    assertEquals(refusal(constant("(list integer) [,]")).isInstanceOf[TextError.Syntax], true)
    assertEquals(
      constant("(array data) [I 1, B #00]").map(_.typeOf),
      Right(DefaultUni.Array(DefaultUni.Data))
    )
    assertEquals(refusal(constant("(list data) [(I 1)]")).isInstanceOf[TextError.Syntax], true)
    assertEquals(constant("unit ( )"), Right(Con.Unit))
  }

  test("an mlresult has no literal, but an empty list of them has (Builtin.hs:169-170)") {
    assertEquals(refusal(constant("bls12_381_mlresult 0x00")), TextError.MlResultLiteral)
    assertEquals(
      constant("(list bls12_381_mlresult) []").map(_.typeOf),
      Right(DefaultUni.List(DefaultUni.Bls12_381_MlResult))
    )
  }

  test("bytestrings: upper and lower case hex, an even number of digits (Builtin.hs:70-78)") {
    assertEquals(constant("bytestring #aBcD"), constant("bytestring #abcd"))
    assertEquals(constant("bytestring #"), Right(Con.ByteString(Bytes.empty)))
    assertEquals(refusal(constant("bytestring #abc")).isInstanceOf[TextError.Syntax], true)
  }

  test("comments: -- to the end of the line, nested {- -} (whitespace, ParserCommon.hs:89-90)") {
    assertEquals(
      UplcParser.program(
        "{- a {- nested -} comment -} (program -- here\n 1.0.0 (con integer 1) {--})\n"
      ),
      program("(con integer 1)", "1.0.0")
    )
    assertEquals(
      refusal(UplcParser.program("(program 1.0.0 (con integer 1)) {- open"))
        .isInstanceOf[TextError.Syntax],
      true
    )
    assertEquals(
      refusal(UplcParser.program("(program 1.0.0 (con integer 1)) x"))
        .isInstanceOf[TextError.Syntax],
      true
    )
  }

  // ---- names, uniques and de Bruijn indices ----

  private val lamIdentity: Term =
    Term.lam(Term.variable(DeBruijn.fromInt(1).getOrElse(fail("index"))))

  test("a variable is its unique: x-0 and y-0 are one variable (Name/Unique.hs:120-121)") {
    assertEquals(program("(lam x-0 y-0)").map(_.term), Right(lamIdentity))
    assertEquals(program("(lam `x` x)").map(_.term), Right(lamIdentity))
  }

  test(
    "a name without a unique gets the first fresh one, from 0 (ParserCommon.hs:235-248, Quote.hs:51)"
  ) {
    // `x` is unique 0, which the binder `y-0` binds
    assertEquals(program("(lam y-0 x)").map(_.term), Right(lamIdentity))
    assertEquals(
      program("(lam y-1 x)").map(_.term),
      Left(ProgramError.Open(FreeVariable(Name("x", 0))))
    )
  }

  // megaparsec 9.7.0's `decimal_` folds the digits into an `Int` with no range check, so it wraps.
  test("an explicit unique is read into a wrapping 64-bit Int (Lex.decimal)") {
    assertEquals(program("(lam x-18446744073709551616 y-0)").map(_.term), Right(lamIdentity))
  }

  test("shadowing, the index of an outer binder, and the first free variable") {
    val two = DeBruijn.fromInt(2).getOrElse(fail("index"))
    assertEquals(program("(lam x (lam x x))").map(_.term), Right(Term.lam(lamIdentity)))
    assertEquals(
      program("(lam x (lam y x))").map(_.term),
      Right(Term.lam(Term.lam(Term.variable(two))))
    )
    assertEquals(
      program("[(lam x a) b]").map(_.term),
      Left(ProgramError.Open(FreeVariable(Name("a", 1))))
    )
  }

  // ---- terms ----

  test("applications nest to the left; [f] is refused (some term)") {
    val f = Term.variable(DeBruijn.fromInt(1).getOrElse(fail("index")))
    assertEquals(
      program("(lam f [f f f])").map(_.term),
      Right(Term.lam(Term.app(Term.app(f, f), f)))
    )
    assertEquals(refusal(program("(lam f [f])")).isInstanceOf[TextError.Syntax], true)
  }

  test("constr and case: from version 1.1.0, a Word64 tag (UntypedPlutusCore/Parser.hs:76-93)") {
    assertEquals(
      program("(constr 18446744073709551615)").map(_.term),
      Right(Term.constr(Word64.MaxValue, Vector.empty))
    )
    assertEquals(
      refusal(program("(constr 18446744073709551616)")),
      TextError.ConstrTagTooLarge(BigInt(2).pow(64))
    )
    val v100 = ProgramVersion.V1_0_0
    assertEquals(refusal(program("(constr 0)", "1.0.0")), TextError.ConstrBeforeV110(v100))
    assertEquals(refusal(program("(case (error))", "1.0.0")), TextError.CaseBeforeV110(v100))
    assertEquals(
      program("(case (error))").map(_.term),
      Right(Term.caseOf(Term.error, Vector.empty))
    )
  }

  test("builtins by their printed names (Builtin.hs:36-63)") {
    assertEquals(
      program("(builtin addInteger)").map(_.term),
      Right(Term.builtin(DefaultFun.AddInteger))
    )
    assertEquals(refusal(program("(builtin AddInteger)")), TextError.UnknownBuiltin("AddInteger"))
    assertEquals(refusal(program("(builtin )")), TextError.UnknownBuiltin(""))
  }

  // ---- printing (PrettyConst.hs:112-121) ----

  test("a numeric escape before a digit, and \\SO before H, are followed by \\&") {
    val t = Text
      .fromCodePoints(Vector(7, 8, 9, 10, 11, 12, 13, 14, 72, 127, 128, 49, 34, 92, 955, 0))
      .getOrElse(fail("text"))
    val printed = UplcPrinter.constant(Con.String(t))
    assertEquals(printed, "string \"" + """\a\b\t\n\v\f\r\SO\&H\DEL\128\&1\"\\λ\NUL""" + "\"")
    assertEquals(constant(printed), Right(Con.String(t)))
  }

  property("any text prints to a literal that reads back as the same text") {
    val codePoint = Gen.frequency(
      8 -> Gen.choose(0, 0x2ff),
      1 -> Gen.choose(0xd800, 0xdfff),
      1 -> Gen.choose(0x300, Character.MAX_CODE_POINT)
    )
    Prop.forAll(Gen.listOf(codePoint)) { points =>
      val t = Text.fromCodePoints(points).getOrElse(fail("text"))
      assertEquals(constant(UplcPrinter.constant(Con.String(t))), Right(Con.String(t)))
    }
  }

  // ---- depth (ADR 0011) ----

  private val Deep = 100000

  private def roundTripsOnASmallStack(text: String, expected: Term): Unit =
    SmallStack.run {
      val parsed = UplcParser.program(text)
      assert(parsed.exists(_.term == expected), "the deep program reads as the expected term")
      val printed = parsed.map(UplcPrinter.program).getOrElse(fail("unreadable"))
      assert(UplcParser.program(printed) == parsed, "the deep program prints and reads back")
    }

  test(s"terms $Deep levels deep read, resolve and print on a 256 KiB stack") {
    val unit = Term.constant(Con.Unit)
    roundTripsOnASmallStack(
      "(program 1.0.0 " + "(delay " * Deep + "(con unit ())" + ")" * Deep + ")",
      (1 to Deep).foldLeft(unit)((t, _) => Term.delay(t))
    )
    val outermost = Term.variable(DeBruijn.fromInt(Deep).getOrElse(fail("index")))
    roundTripsOnASmallStack(
      "(program 1.0.0 " + (0 until Deep).map(i => s"(lam x$i ").mkString + "x0" + ")" * Deep + ")",
      (1 to Deep).foldLeft(outermost)((t, _) => Term.lam(t))
    )
    val f = Term.variable(DeBruijn.fromInt(1).getOrElse(fail("index")))
    roundTripsOnASmallStack(
      "(program 1.0.0 (lam f " + "[" * Deep + "f" + " f]" * Deep + "))",
      Term.lam((1 to Deep).foldLeft(f)((t, _) => Term.app(t, f)))
    )
  }

  test(s"data and list constants $Deep levels deep read and print on a 256 KiB stack") {
    val data =
      (1 to Deep).foldLeft(PlutusData.I(0): PlutusData)((d, _) => PlutusData.List(Vector(d)))
    roundTripsOnASmallStack(
      "(program 1.0.0 (con data (" + "List [" * Deep + "I 0" + "]" * Deep + ")))",
      Term.constant(Con.Data(data))
    )
    val depth = Deep / 20
    val (listType, list) =
      (1 to depth).foldLeft[(DefaultUni, Con)]((DefaultUni.Integer, Con.Integer(1))) {
        case ((t, c), _) => (DefaultUni.List(t), Con.List.of(t, Vector(c)).getOrElse(fail("list")))
      }
    assertEquals(list.typeOf, listType)
    roundTripsOnASmallStack(
      "(program 1.0.0 (con " + "(list " * depth + "integer" + ")" * depth + " " + "[" * depth + "1" +
        "]" * depth + "))",
      Term.constant(list)
    )
  }

// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import cats.Order
import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx

class JsonValueSuite extends FunSuite:
  private def number(c: BigInt, e: Long) = JsonValue.Number(Scientific(c, e))
  private def key(text: String): JsonKey = JsonKey(text).fold(e => fail(e.toString), identity)
  private def obj(pairs: (String, JsonValue)*) =
    JsonValue.Object(JsonObject.fromList(pairs.map((k, v) => key(k) -> v)))
  private def arr(items: JsonValue*) = JsonValue.Array(items.toVector)
  private def str(text: String) = JsonValue.String(text)
  private def bytes(text: String) = Bytes.wrap(text.getBytes("UTF-8"))
  private def lines(block: String): Vector[String] =
    block.stripMargin.linesIterator.drop(1).toVector

  // `normalizePositive` as the reference writes it: one division per trailing zero.
  @tailrec private def byOneZero(c: BigInt, e: Long): Scientific =
    if c == 0 then Scientific(0, 0)
    else
      val (q, r) = c /% 10
      if r == 0 then byOneZero(q, e + 1) else Scientific(c, e)

  // GHC 9.10.3 in the pinned reference image printed these goldens on 2026-10-27, with scientific
  // 0.3.8.1, aeson 2.2.5.0, text 2.1.3 and bytestring 0.12.2.0: `show` and `showsPrec 11` of
  // `scientific`s, `show` of Values, Chars and ByteStrings, and `compare` of each pair of
  // `cmpValues` (`<`, `=`, `>`), from the same values as the lists below.

  private val scientificShown = lines(
    """
      |1.5 ~ 1.5
      |1.0e-2 ~ 1.0e-2
      |0.0 ~ 0.0
      |0.0 ~ 0.0
      |-1.5 ~ (-1.5)
      |1.0e7 ~ 1.0e7
      |1234567.0 ~ 1234567.0
      |1.2345678e7 ~ 1.2345678e7
      |0.5 ~ 0.5
      |1.0e9223372036854775807 ~ 1.0e9223372036854775807
      |1.0e-9223372036854775808 ~ 1.0e-9223372036854775808
      |1.0e-9223372036854775808 ~ 1.0e-9223372036854775808
      |100.0 ~ 100.0
      |12.3 ~ 12.3
      |-1.0 ~ (-1.0)
      |-1.2345e-6 ~ (-1.2345e-6)
      |12.0 ~ 12.0
      |0.1 ~ 0.1
      |-1.0e9223372036854775807 ~ (-1.0e9223372036854775807)
      |9.8765e7 ~ 9.8765e7
      |4.0e-30000000995 ~ 4.0e-30000000995
      |1.2e7 ~ 1.2e7"""
  ).map(_.split(" ~ ").toList)

  private val valuesShown = lines(
    """
      |Null
      |Bool True
      |Bool False
      |Number (-1.0)
      |Number 1.5
      |Number 0.0
      |String ""
      |String "a\"b\\c'"
      |String "\SO"
      |String "\SOH"
      |String "\SO\&H"
      |String "\SOx"
      |String "\200\&1"
      |String "\200x"
      |String "\DEL1"
      |String "\NUL1"
      |String "\128512"
      |String "\t\n\r\a\b\f\v"
      |String "\233"
      |String "\65535\&9"
      |String "\NUL\SOH\STX\ETX\EOT\ENQ\ACK\a\b\t\n\v\f\r\SO\SI\DLE\DC1\DC2\DC3\DC4\NAK\SYN\ETB\CAN\EM\SUB\ESC\FS\GS\RS\US !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\DEL\128\129\130\131\132\133\134\135\136\137\138\139\140\141\142\143\144\145\146\147\148\149\150\151\152\153\154\155\156\157\158\159\160\161\162\163\164\165\166\167\168\169\170\171\172\173\174\175\176\177\178\179\180\181\182\183\184\185\186\187\188\189\190\191\192\193\194\195\196\197\198\199\200\201\202\203\204\205\206\207\208\209\210\211\212\213\214\215\216\217\218\219\220\221\222\223\224\225\226\227\228\229\230\231\232\233\234\235\236\237\238\239\240\241\242\243\244\245\246\247\248\249\250\251\252\253\254\255\256\257\258\259\260\261\262\263\264\265\266\267\268\269\270\271\272\273\274\275\276\277\278\279\280\281\282\283\284\285\286\287\288\289\290\291\292\293\294\295\296\297\298\299\300\65535\65536\128512\1114111"
      |Array []
      |Object (fromList [])
      |Array [Number (-1.0),Null,Array [Bool True]]
      |Object (fromList [("a",Number (-2.5)),("b",Null),("\65535",Object (fromList [("k",String "v")])),("\128512",Array [])])"""
  )

  // `show (chr w)` for w from 0 to 255, concatenated.
  private val charsShown =
    """'\NUL''\SOH''\STX''\ETX''\EOT''\ENQ''\ACK''\a''\b''\t''\n''\v''\f''\r''\SO''\SI''\DLE''\DC1''\DC2''\DC3''\DC4''\NAK''\SYN''\ETB''\CAN''\EM''\SUB''\ESC''\FS''\GS''\RS''\US'' ''!''"''#''$''%''&''\'''('')''*''+'',''-''.''/''0''1''2''3''4''5''6''7''8''9'':'';''<''=''>''?''@''A''B''C''D''E''F''G''H''I''J''K''L''M''N''O''P''Q''R''S''T''U''V''W''X''Y''Z''[''\\'']''^''_''`''a''b''c''d''e''f''g''h''i''j''k''l''m''n''o''p''q''r''s''t''u''v''w''x''y''z''{''|''}''~''\DEL''\128''\129''\130''\131''\132''\133''\134''\135''\136''\137''\138''\139''\140''\141''\142''\143''\144''\145''\146''\147''\148''\149''\150''\151''\152''\153''\154''\155''\156''\157''\158''\159''\160''\161''\162''\163''\164''\165''\166''\167''\168''\169''\170''\171''\172''\173''\174''\175''\176''\177''\178''\179''\180''\181''\182''\183''\184''\185''\186''\187''\188''\189''\190''\191''\192''\193''\194''\195''\196''\197''\198''\199''\200''\201''\202''\203''\204''\205''\206''\207''\208''\209''\210''\211''\212''\213''\214''\215''\216''\217''\218''\219''\220''\221''\222''\223''\224''\225''\226''\227''\228''\229''\230''\231''\232''\233''\234''\235''\236''\237''\238''\239''\240''\241''\242''\243''\244''\245''\246''\247''\248''\249''\250''\251''\252''\253''\254''\255'"""

  private val bytesShown = lines(
    """
      |"\NUL\SOH\STX\ETX\EOT\ENQ\ACK\a\b\t\n\v\f\r\SO\SI\DLE\DC1\DC2\DC3\DC4\NAK\SYN\ETB\CAN\EM\SUB\ESC\FS\GS\RS\US !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\DEL\128\129\130\131\132\133\134\135\136\137\138\139\140\141\142\143\144\145\146\147\148\149\150\151\152\153\154\155\156\157\158\159\160\161\162\163\164\165\166\167\168\169\170\171\172\173\174\175\176\177\178\179\180\181\182\183\184\185\186\187\188\189\190\191\192\193\194\195\196\197\198\199\200\201\202\203\204\205\206\207\208\209\210\211\212\213\214\215\216\217\218\219\220\221\222\223\224\225\226\227\228\229\230\231\232\233\234\235\236\237\238\239\240\241\242\243\244\245\246\247\248\249\250\251\252\253\254\255"
      |"\229\&1\SO\&H\SOI\"\\'""""
  )

  private val comparisons = lines(
    """
      |==>>><<><>><<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |==>>><<><>><<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |<<=<<<<><<<<<<<<<<>>>>>>>>>>>>>>>>>>>><<<
      |<<>==<<><<<<<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |<<>==<<><<<<<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>=<><>><<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>=><>><<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |<<<<<<<=<<<<<<<<<<>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>>>=>>>>>>>>>>>>>>>>>>>>>>>>>>>>><<<
      |<<>>><<><=><<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |<<>>><<><<=<<<><<<>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>>><>>=>>>>>>>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>>><>><==>>>>>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>>><>><==>>>>>>>>>>>>>>>>>>>>>>>><<<
      |<<><<<<><<<<<<=<<<>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>>><>><<<>=<<>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>>><>><<<>>=<>>>>>>>>>>>>>>>>>>>><<<
      |>>>>>>>><>><<<>>>=>>>>>>>>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<=<<<<<<>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<>=<<<<<>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<>>=<<<<>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<>>>=<<<>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<>>>>=<>>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<>>>>>=>>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<>>>><<=>>>>>>>>>>>>><<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<=<<<<<<<<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>=><<<<<<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<><=<<<<<<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>=><<<<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>><=<<<<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>=<<<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>>=<<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>>>=<<<<<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>>>>=<>>><<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>>>>>=>>><<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>>>><<=>><<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>>>><<<=<<<<
      |<<<<<<<<<<<<<<<<<<<<<<<<<>>>>>>>><<<>=<<<
      |>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>=<<
      |>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>=<
      |>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>="""
  )

  private val (max, min) = (Long.MaxValue, Long.MinValue)

  private val scientifics = List[(BigInt, Long)](
    (15, -1),
    (1, -2),
    (0, 5),
    (0, 0),
    (-15, -1),
    (1, 7),
    (1234567, 0),
    (12345678, 0),
    (5, -1),
    (1, max),
    (1, min),
    (10, max),
    (100, 0),
    (123, -1),
    (-1, 0),
    (-12345, -10),
    (120, -1),
    (1, -1),
    (-1, max),
    (98765, 3),
    (4, -30000000995L),
    (12, 6)
  ).map(Scientific.apply)

  private val shownValues = List(
    JsonValue.Null,
    JsonValue.Bool(true),
    JsonValue.Bool(false),
    number(-1, 0),
    number(15, -1),
    number(0, 3),
    str(""),
    str("a\"b\\c'"),
    str("\u000e"),
    str("\u0001"),
    str("\u000eH"),
    str("\u000ex"),
    str("\u00c81"),
    str("\u00c8x"),
    str("\u007f1"),
    str("\u00001"),
    str("\uD83D\uDE00"),
    str("\t\n\r\u0007\b\f\u000b"),
    str("\u00e9"),
    str("\uFFFF9"),
    str(new String(((0 to 300) ++ List(0xffff, 0x10000, 0x1f600, 0x10ffff)).toArray, 0, 305)),
    arr(),
    obj(),
    arr(number(-1, 0), JsonValue.Null, arr(JsonValue.Bool(true))),
    obj(
      "b" -> JsonValue.Null,
      "a" -> number(-25, -1),
      "\uD83D\uDE00" -> arr(),
      "\uFFFF" -> obj("k" -> str("v"))
    )
  )

  private val cmpValues = List(
    number(1, 0),
    number(10, -1),
    number(-1, 0),
    number(0, 0),
    number(0, 5),
    number(15, -1),
    number(2, 0),
    number(-2, 0),
    number(1, max),
    number(12, max),
    number(1, min),
    number(123456789, -3),
    number(1, 5),
    number(100000, 0),
    number(-12, max),
    number(99, -1),
    number(999, -2),
    number(1, 1),
    str(""),
    str("a"),
    str("ab"),
    str("b"),
    str("\uFFFF"),
    str("\uD83D\uDE00"),
    str("\u00e9"),
    obj(),
    obj("a" -> JsonValue.Null),
    obj("a" -> JsonValue.Bool(false)),
    obj("b" -> JsonValue.Null),
    obj("a" -> JsonValue.Null, "b" -> JsonValue.Null),
    obj("\uFFFF" -> JsonValue.Null),
    obj("\uD83D\uDE00" -> JsonValue.Null),
    arr(),
    arr(JsonValue.Null),
    arr(JsonValue.Null, JsonValue.Null),
    arr(JsonValue.Bool(true)),
    arr(number(1, 0)),
    arr(number(1, 0), JsonValue.Null),
    JsonValue.Bool(false),
    JsonValue.Bool(true),
    JsonValue.Null
  )

  test("Scientific equality and hashing compare normalized values, as the reference's Eq does") {
    val equal = List(
      Scientific(1, 1) -> Scientific(10, 0),
      Scientific(0, 5) -> Scientific(0, -3),
      Scientific(-100, -2) -> Scientific(-1, 0),
      Scientific(150, -2) -> Scientific(15, -1)
    )
    equal.foreach { (a, b) =>
      assertEquals(a, b)
      assertEquals(a.hashCode, b.hashCode)
      assertEquals(Order[Scientific].compare(a, b), 0)
    }
    assertNotEquals(Scientific(15, 0), Scientific(150, 0))
    assertNotEquals(Scientific(1, 0), Scientific(-1, 0))
  }

  test("normalization keeps the stored representation and agrees with one division per zero") {
    assertEquals(Scientific(150, -2).coefficient, BigInt(150))
    for
      base <- List(BigInt(1), BigInt(-7), BigInt(2).pow(70), BigInt(-12345), BigInt(10).pow(40) + 1)
      zeros <- (0 to 70) ++ List(127, 128, 129, 255, 256, 1000)
    do
      val c = base * BigInt(10).pow(zeros)
      val n = Scientific(c, -3).normalize
      val expected = if c < 0 then byOneZero(-c, -3) else byOneZero(c, -3)
      assertEquals(n.coefficient, if c < 0 then -expected.coefficient else expected.coefficient)
      assertEquals(n.base10Exponent, expected.base10Exponent)
  }

  test("the normalized exponent wraps as the reference's machine Int does") {
    val n = Scientific(100, Long.MaxValue).normalize
    assertEquals(n.coefficient, BigInt(1))
    assertEquals(n.base10Exponent, Long.MinValue + 1)
  }

  test("Scientific shows as the reference's show and showsPrec 11") {
    assertEquals(scientifics.size, scientificShown.size)
    scientifics.lazyZip(scientificShown).foreach { (s, expected) =>
      val at11 = new StringBuilder
      s.showsPrec(11, at11)
      assertEquals(List(s.toString, at11.result()), expected)
    }
  }

  test("integerLog10' is the floor of the decimal logarithm") {
    for
      digits <- 1 to 400
      n <- List(
        BigInt(10).pow(digits - 1),
        BigInt(10).pow(digits) - 1,
        BigInt(7) * BigInt(10).pow(digits - 1)
      )
    do assertEquals(Scientific.integerLog10(n), digits - 1L, n)
  }

  test("Scientific order agrees with exact decimal order where the exponents are small") {
    val random = new scala.util.Random(497)
    (1 to 5000).foreach { _ =>
      def one() = Scientific(BigInt(random.between(-100000, 100000)), random.between(-12L, 12L))
      val (a, b) = (one(), one())
      val exact = BigDecimal(a.coefficient, -a.base10Exponent.toInt)
        .compare(BigDecimal(b.coefficient, -b.base10Exponent.toInt))
      assertEquals(Integer.signum(Order[Scientific].compare(a, b)), Integer.signum(exact), (a, b))
    }
  }

  test("values show as the reference's Show Value") {
    assertEquals(shownValues.map(_.toString), valuesShown.toList)
  }

  test("characters and bytes in parse errors show as the reference's Show Char and ByteString") {
    assertEquals((0 to 255).map(HaskellShow.char).mkString, charsShown)
    val all = Bytes.wrap((0 to 255).map(_.toByte).toArray)
    val protectedEscapes =
      Bytes.wrap(Array(0xe5, 0x31, 0x0e, 0x48, 0x0e, 0x49, 0x22, 0x5c, 0x27).map(_.toByte))
    assertEquals(Vector(HaskellShow.bytes(all), HaskellShow.bytes(protectedEscapes)), bytesShown)
  }

  test("values order as the reference's derived Ord, consistent with equality") {
    val symbols = cmpValues.map { a =>
      cmpValues
        .map(b =>
          Order[JsonValue].compare(a, b).sign match
            case -1 => '<'
            case 0  => '='
            case _  => '>'
        )
        .mkString
    }
    assertEquals(symbols, comparisons.toList)
    for a <- cmpValues; b <- cmpValues do
      assertEquals(Order[JsonValue].compare(a, b) == 0, a == b, (a, b))
  }

  test("an object keeps the last pair of a key and orders keys by code point") {
    val supplementary = "\uD83D\uDE00"
    val o = JsonObject.fromList(
      List(
        key("b") -> JsonValue.Null,
        key(supplementary) -> JsonValue.Null,
        key("\uFFFF") -> JsonValue.Null
      )
        :+ (key("b") -> JsonValue.Bool(true))
    )
    // UTF-16 code units would put the surrogate pair (0xD83D) before U+FFFF.
    assertEquals(o.toVector.map(_._1.text), Vector("b", "\uFFFF", supplementary))
    assertEquals(o.get(key("b")), Some(JsonValue.Bool(true)))
  }

  test("a key refuses an unpaired surrogate, which Text cannot hold") {
    assertEquals(JsonKey("a\uD800"), Left(JsonKey.Error.UnpairedSurrogate(1)))
    assertEquals(JsonKey("\uDC00a"), Left(JsonKey.Error.UnpairedSurrogate(0)))
    assertEquals(JsonKey("\uDE00\uD83D"), Left(JsonKey.Error.UnpairedSurrogate(0)))
    assertEquals(JsonKey("\uD83D\uDE00").map(_.text), Right("\uD83D\uDE00"))
    assertEquals(JsonKey("").map(_.text), Right(""))
  }

  test("the reader refuses a lone-surrogate key as it refuses such a value, before a key exists") {
    val refused = NativeJson.DecodeError.Syntax(NativeJson.Error.InvalidUtf8Stream)
    for
      surrogate <- List("\\uD800", "\\uDC00", "\\uD800\\u0041", "\u00ed\u00a0\u0080")
      raw = surrogate.map(_.toByte).toArray
    do
      def decode(prefix: String, suffix: String) =
        NativeJson.decodeStrict(
          Bytes.wrap(prefix.getBytes("UTF-8") ++ raw ++ suffix.getBytes("UTF-8"))
        )
      assertEquals(decode("{\"", "\":0}"), Left(refused), surrogate)
      assertEquals(decode("[\"", "\"]"), Left(refused), surrogate)
  }

  test("values are equal as the derived Eq says, with numbers compared normalized") {
    val a = arr(number(1, 1), str("x"))
    assertEquals(a, arr(number(10, 0), str("x")))
    assertEquals(a.hashCode, arr(number(10, 0), str("x")).hashCode)
    assertNotEquals(a, arr(str("x"), number(10, 0)))
    val o = obj("k" -> number(5, 0), "j" -> JsonValue.Null)
    assertEquals(o, obj("j" -> JsonValue.Null, "k" -> number(50, -1)))
    assertNotEquals(o, obj("k" -> number(5, 0)))
    val pairs = List("z" -> number(1, 0), "a" -> JsonValue.Null, "m" -> number(10, -1))
    assertEquals(obj(pairs*).hashCode, obj(pairs.reverse*).hashCode)
  }

  test("parsed numbers keep their representation and compare by value") {
    def parse(text: String) =
      NativeJson.decodeStrict(bytes(text)).fold(e => fail(e.message), identity)
    parse("1.50") match
      case JsonValue.Number(n) =>
        assertEquals((n.coefficient, n.base10Exponent), (BigInt(150), -2L))
      case other => fail(s"not a number: $other")
    assertEquals(parse("1.50"), parse("15e-1"))
  }

  test("parsing stops after the value, so trailing input is reported by a separate later step") {
    assertEquals(
      NativeJson.parse(bytes("[1] x")),
      Right(NativeJson.Parsed(arr(number(1, 0)), 3))
    )
    assertEquals(NativeJson.parse(bytes("1x")).map(_.end), Right(1))
    assert(NativeJson.onlySpace(bytes("[1] \t\r\n"), 3))
    assertEquals(
      NativeJson.decodeStrict(bytes("[1] x")),
      Left(NativeJson.DecodeError.TrailingGarbage)
    )
    assertEquals(NativeJson.DecodeError.TrailingGarbage.message, "Trailing garbage")
    assertEquals(NativeJson.decodeStrict(bytes("[1] \n")), Right(arr(number(1, 0))))
  }

  test("equality, order, hashing and showing of 20,000 nested levels run on a small stack") {
    val depth = 20000
    def nested(leaf: JsonValue) = (1 to depth).foldLeft(leaf) { (v, i) =>
      if i % 2 == 0 then arr(v) else obj("k" -> v)
    }
    val (a, b, c) = (nested(number(1, 1)), nested(number(10, 0)), nested(number(2, 0)))
    val (same, different, hashes, ordered, shown) = PreprodDeepTx.onSmallStack(
      (
        a == b,
        a == c,
        a.hashCode == b.hashCode,
        (Order[JsonValue].compare(a, b).sign, Order[JsonValue].compare(a, c).sign),
        a.toString
      )
    )
    assert(same)
    assert(!different)
    assert(hashes)
    assertEquals(ordered, (0, 1))
    assertEquals(
      shown.length,
      depth / 2 * ("Array []".length + "Object (fromList [(\"k\",)])".length) + "Number 10.0".length
    )
    assert(shown.startsWith("Array [Object (fromList [(\"k\",Array ["))
  }

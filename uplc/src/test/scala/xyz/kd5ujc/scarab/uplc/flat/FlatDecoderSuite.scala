// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.*
import xyz.kd5ujc.scarab.uplc.flat.FlatWriter.*

/** The Flat decoder against the reference's decoders, plutus 1.70.0.0 (`507cab8c`): every refusal
  * at the place the reference makes it, the inputs the reference accepts that a canonical encoder
  * would not write, and deep inputs on a small stack.
  */
final class FlatDecoderSuite extends ScalaCheckSuite:
  private val v100 = natural(1) + natural(0) + natural(0)
  private val v110 = natural(1) + natural(1) + natural(0)
  private val error = bits(6, 4)

  private def decodeBits(
      bits: String,
      checks: Checks = Checks.unrestricted
  ): Either[FlatError, Program] =
    FlatDecoder.program(Bytes.wrap(padded(bits)), checks)

  private def decodeRaw(bytes: Array[Byte]): Either[FlatError, Program] =
    FlatDecoder.program(Bytes.wrap(bytes), Checks.unrestricted)

  private def term(bits: String, version: String = v110): Either[FlatError, Term] =
    decodeBits(version + bits).map(_.term)

  private def word(n: Long): Word64 = Word64.fromBits(n)
  private def variable(n: Long): Term = Term.variable(DeBruijn(word(n)))
  private def hex(s: String): Array[Byte] = Bytes.fromHex(s).fold(e => fail(e.toString), _.toArray)

  /** A constant's bits after the version of 24 bits: the term tag, the type's tags, the value. */
  private def constantBits(tags: Seq[Int], value: Int => String): String =
    val header = bits(4, 4) + tagList(tags)
    header + value(24 + header.length)

  private def constantOf(tags: Seq[Int], value: Int => String): Either[FlatError, Constant] =
    term(constantBits(tags, value)).flatMap(_.unfix match
      case TermF.Constant(c) => Right(c)
      case other             => fail(s"not a constant: $other"))

  property("a program round-trips through the reference's canonical encoding") {
    forAll(FlatGen.programs)(p =>
      assertEquals(FlatDecoder.program(FlatWriter.flat(p), Checks.unrestricted), Right(p))
    )
  }

  test("every term constructor and constant type decodes as written") {
    val text = Text.fromString("héllo 中 😀")
    val program = Program(
      ProgramVersion.V1_1_0,
      Term.lam(
        Term.app(
          Term.force(Term.delay(variable(1))),
          Term.caseOf(
            Term.constr(
              word(-1L),
              Vector(Term.constant(Constant.Integer(-(BigInt(1) << 200))), Term.error)
            ),
            Vector(
              Term.builtin(DefaultFun.ScaleValue),
              Term.constant(Constant.String(text)),
              Term.constant(Constant.ByteString(Bytes.wrap(Array.tabulate(700)(_.toByte)))),
              Term.constant(Constant.Data(PlutusData.Constr(1280, Vector(PlutusData.I(-5))))),
              Term.constant(
                Constant.Pair(
                  Constant.Unit,
                  Constant.List
                    .of(DefaultUni.Bool, Vector(Constant.Bool(true)))
                    .fold(e => fail(e.toString), identity)
                )
              )
            )
          )
        )
      )
    )
    assertEquals(FlatDecoder.program(FlatWriter.flat(program), Checks.unrestricted), Right(program))
  }

  // `postAlignedDecoder` and `strictDecoder` (`Filler.hs:77-81`, `Decoder/Run.hs:19-24`)
  test("the filler after the program: any number of zeros, its 1 on the last bit of the input") {
    val body = v100 + error
    assert(decodeRaw(packed(body + "0001")).isRight)
    assert(decodeRaw(packed(body + "0000" + "00000001")).isRight, "a filler of more than a byte")
    assertEquals(decodeRaw(packed(body + "0000")), Left(FlatError.NotEnoughSpace(32, 1)))
    assertEquals(decodeRaw(packed(body + "1000")), Left(FlatError.TooMuchSpace(29)))
    assertEquals(decodeRaw(packed(body + "0001" + "00000000")), Left(FlatError.TooMuchSpace(32)))
    assertEquals(decodeRaw(Array.empty), Left(FlatError.NotEnoughSpace(0, 8)))
  }

  // `Flat Version` (`FlatInstances.hs:177-179`): three naturals of any size
  test("version components are naturals of any width") {
    val wide = BigInt(1) + (BigInt(1) << 70)
    val decoded = decodeBits(natural(wide) + natural(0) + natural(1) + error)
    assertEquals(decoded.map(_.version), ProgramVersion.of(wide, 0, 1).toRight(fail("version")))
    assertEquals(
      decodeBits(groups(0x81, 0x80, 0x00) + natural(0) + natural(0) + error).map(_.version),
      Right(ProgramVersion.V1_0_0)
    )
  }

  // `Flat.hs:167-185`: the version gate comes before the tag of `constr` and before anything of `case`
  test("constr and case before 1.1.0 are refused before anything after their tag is read") {
    assertEquals(
      decodeRaw(packed(v100 + bits(8, 4) + "1111")),
      Left(FlatError.Refused(Availability.Refusal.ConstrBeforeVersion(ProgramVersion.V1_0_0)))
    )
    assertEquals(
      decodeRaw(packed(v100 + bits(9, 4) + "1111")),
      Left(FlatError.Refused(Availability.Refusal.CaseBeforeVersion(ProgramVersion.V1_0_0)))
    )
    assertEquals(term(bits(8, 4) + natural(3) + "0"), Right(Term.constr(word(3), Vector.empty)))
    assertEquals(term(bits(9, 4) + error + "0"), Right(Term.caseOf(Term.error, Vector.empty)))
  }

  // `dWord64` (`Strict.hs:165-184`, `lastStep` 228-239)
  test("Word64: ten groups at most, the tenth holding bit 63 alone, padding accepted") {
    def delayed(groupBytes: Int*): Either[FlatError, Term] =
      term(bits(1, 4) + bits(0, 4) + groups(groupBytes*))
    val padded10 = 0x81 +: Vector.fill(8)(0x80) :+ 0x00
    assertEquals(delayed(padded10*), Right(Term.delay(variable(1))))
    assertEquals(
      delayed((Vector.fill(9)(0x80) :+ 0x01)*),
      Right(Term.delay(variable(Long.MinValue)))
    )
    // the tenth group starts after the version (24), the two tags (8) and nine groups (72)
    assertEquals(delayed((Vector.fill(9)(0x80) :+ 0x02)*), Left(FlatError.Word64Overflow(104)))
    assertEquals(
      delayed((0x81 +: Vector.fill(9)(0x80) :+ 0x00)*),
      Left(FlatError.Word64Overflow(104))
    )
    assertEquals(
      term(bits(8, 4) + groups((Vector.fill(9)(0xff) :+ 0x01)*) + "0"),
      Right(Term.constr(word(-1L), Vector.empty))
    )
    assertEquals(
      term(bits(8, 4) + groups((Vector.fill(9)(0x80) :+ 0x03)*) + "0"),
      Left(FlatError.Word64Overflow(100))
    )
  }

  test("variable indices are read as written, 0 included: scope is not the decoder's") {
    assertEquals(term(bits(0, 4) + natural(0)), Right(variable(0)))
    assertEquals(term(bits(2, 4) + bits(0, 4) + natural(7)), Right(Term.lam(variable(7))))
  }

  // `decodeTerm`'s last case (`Flat.hs:186`)
  test("term tags 10 to 15 have no term") {
    (10 to 15).foreach(t => assertEquals(term(bits(t, 4)), Left(FlatError.UnknownTermTag(t))))
  }

  // `decode` of `DefaultFun` (`Default/Builtins.hs:2676-2784`)
  test("builtin tags 0 to 105 name the builtins; 106 to 127 none") {
    DefaultFun.values.foreach(f =>
      assertEquals(term(bits(7, 4) + bits(f.flatTag, 7)), Right(Term.builtin(f)))
    )
    assertEquals(DefaultFun.values.map(_.flatTag).toSet, (0 to 105).toSet)
    (106 to 127).foreach(t =>
      assertEquals(term(bits(7, 4) + bits(t, 7)), Left(FlatError.UnknownBuiltinTag(t)))
    )
  }

  test("an unknown builtin tag is refused before availability, an unavailable builtin after") {
    val ledger = Checks.ledger(PlutusLanguage.V3, ProtocolMajor.VanRossem)
    assertEquals(
      decodeBits(v110 + bits(7, 4) + bits(127, 7), ledger),
      Left(FlatError.UnknownBuiltinTag(127))
    )
    assertEquals(
      decodeBits(v110 + bits(7, 4) + bits(DefaultFun.MultiIndexArray.flatTag, 7), ledger),
      Left(
        FlatError.Refused(
          Availability.Refusal.BuiltinNotAvailable(
            DefaultFun.MultiIndexArray,
            PlutusLanguage.V3,
            ProtocolMajor.VanRossem
          )
        )
      )
    )
  }

  // `decodeKindedUniFlat` and `checkStar` (`FlatInstances.hs:129-135`, `Flat.hs:148-153`)
  test("a type header that is not one well-kinded type of kind * is refused before its value") {
    import TypeHeader.{Failure, Kinded}
    def header(tags: Int*): Either[FlatError, Constant] = constantOf(tags, _ => "")
    assertEquals(header(14), Left(FlatError.UndecodableType(Vector(14), Failure.UnknownTag(14))))
    assertEquals(header(), Left(FlatError.UndecodableType(Vector.empty, Failure.MissingTags)))
    assertEquals(header(7, 5), Left(FlatError.UndecodableType(Vector(7, 5), Failure.MissingTags)))
    assertEquals(
      header(0, 0),
      Left(FlatError.UndecodableType(Vector(0, 0), Failure.TrailingTags(1)))
    )
    assertEquals(
      header(7, 0, 0),
      Left(
        FlatError.UndecodableType(
          Vector(7, 0, 0),
          Failure.NotApplicable(Kinded.Star(DefaultUni.Integer), Kinded.Star(DefaultUni.Integer))
        )
      )
    )
    assertEquals(
      header(7, 5, 5),
      Left(
        FlatError.UndecodableType(
          Vector(7, 5, 5),
          Failure.NotApplicable(Kinded.ListOperator, Kinded.ListOperator)
        )
      )
    )
    assertEquals(header(5), Left(FlatError.NonStarType(Vector(5))))
    assertEquals(header(7, 6, 0), Left(FlatError.NonStarType(Vector(7, 6, 0))))
    assertEquals(header(12), Left(FlatError.NonStarType(Vector(12))))
    assertEquals(
      constantOf(Seq(7, 7, 6, 0, 4), _ => integer(-3) + "1"),
      Right(Constant.Pair(Constant.Integer(-3), Constant.Bool(true)))
    )
  }

  // `G1.hs:86-90`, `G2.hs:72-76`, `Pairing.hs:56-58`: the value's decoder fails, not the type
  test("BLS12-381 values have no Flat decoding, but an empty list of them decodes") {
    assertEquals(
      constantOf(Seq(9), _ => ""),
      Left(FlatError.NoFlatValue(DefaultUni.Bls12_381_G1_Element))
    )
    assertEquals(
      constantOf(Seq(10), _ => ""),
      Left(FlatError.NoFlatValue(DefaultUni.Bls12_381_G2_Element))
    )
    assertEquals(
      constantOf(Seq(11), _ => ""),
      Left(FlatError.NoFlatValue(DefaultUni.Bls12_381_MlResult))
    )
    assertEquals(
      constantOf(Seq(7, 5, 9), _ => "0"),
      Right(Constant.List.empty(DefaultUni.Bls12_381_G1_Element))
    )
    assertEquals(
      constantOf(Seq(7, 12, 10), _ => "0"),
      Right(Constant.List.empty(DefaultUni.Bls12_381_G2_Element).toArray)
    )
    assertEquals(
      constantOf(Seq(7, 5, 9), _ => "1"),
      Left(FlatError.NoFlatValue(DefaultUni.Bls12_381_G1_Element))
    )
  }

  // `dZigZag`/`zagZig` over `dNatural` (`Strict.hs:113-114`, `ZigZag.hs`)
  test("integers are zig-zag naturals, of any width and with padded groups") {
    def int(bits: String): Either[FlatError, Constant] = constantOf(Seq(0), _ => bits)
    assertEquals(int(natural(0)), Right(Constant.Integer(0)))
    assertEquals(int(natural(1)), Right(Constant.Integer(-1)))
    assertEquals(int(natural(2)), Right(Constant.Integer(1)))
    assertEquals(int(natural(3)), Right(Constant.Integer(-2)))
    assertEquals(int(groups(0x82, 0x80, 0x80, 0x00)), Right(Constant.Integer(1)))
    val big = (BigInt(1) << 500) + 12345
    assertEquals(int(integer(-big)), Right(Constant.Integer(-big)))
    assertEquals(int(groups((Vector.fill(15)(0x80) :+ 0x00)*)), Right(Constant.Integer(0)))
    assertEquals(int(groups(0x80)), Left(FlatError.NotEnoughSpace(24 + 4 + 6 + 8, 8)))
  }

  // `dByteString` and `getChunksInfo` (`Strict.hs:317-318`, `Prim.hs:441-459`)
  test("byte strings: a filler, a byte boundary, then chunks of any length") {
    val content = Array[Byte](1, 2, 3)
    val chunks = bits(1, 8) + bits(1, 8) + bits(2, 8) + bits(2, 8) + bits(3, 8) + bits(0, 8)
    assertEquals(
      constantOf(Seq(1), at => filler(at) + chunks),
      Right(Constant.ByteString(Bytes.wrap(content)))
    )
    // the header ends at 34: a filler of 14 bits ends on the boundary at 48
    assertEquals(
      constantOf(Seq(1), _ => "0" * 13 + "1" + bits(0, 8)),
      Right(Constant.ByteString(Bytes.empty))
    )
    // the header ends at 24 + 4 + 6 = 34; a filler of one bit ends at 35
    assertEquals(constantOf(Seq(1), _ => "1" + "00000"), Left(FlatError.Misaligned(35)))
    assertEquals(
      constantOf(Seq(1), at => filler(at) + bits(5, 8) + bits(1, 8) + bits(2, 8)),
      Left(FlatError.NotEnoughSpace(48, 40))
    )
  }

  // `dUTF8` (`Strict.hs:293-299`): `decodeUtf8'` refuses what is not UTF-8
  test("string constants must be UTF-8") {
    def string(raw: Array[Byte]): Either[FlatError, Constant] =
      constantOf(Seq(2), at => byteString(at, raw))
    assertEquals(string("é😀".getBytes("UTF-8")), Right(Constant.String(Text.fromString("é😀"))))
    assertEquals(string(Array(0xff.toByte, 0xfe.toByte)), Left(FlatError.InvalidUtf8(34)))
    assertEquals(
      string(Array(0xed.toByte, 0xa0.toByte, 0x80.toByte)),
      Left(FlatError.InvalidUtf8(34))
    )
    assertEquals(
      string(Array(0xc0.toByte, 0x80.toByte)),
      Left(FlatError.InvalidUtf8(34)),
      "an overlong NUL"
    )
  }

  // `FlatViaSerialise` (`FlatViaSerialise.hs:32-39`): the first CBOR item, what follows dropped
  test("data constants: the first CBOR item of the byte string") {
    def data(cbor: String): Either[FlatError, Constant] =
      constantOf(Seq(8), at => byteString(at, hex(cbor)))
    assertEquals(data("00"), Right(Constant.Data(PlutusData.I(0))))
    assertEquals(data("0000"), Right(Constant.Data(PlutusData.I(0))))
    assertEquals(data("00ff1c"), Right(Constant.Data(PlutusData.I(0))))
    assert(data("8200").left.exists { case FlatError.MalformedData(_) => true; case _ => false })
    assertEquals(
      data("d8024101"),
      Left(FlatError.MalformedData(PlutusData.DecodeError.BigNumberWideHead(2)))
    )
    assert(data("").isLeft)
  }

  private def valueBits(
      written: Int,
      entries: Seq[(Array[Byte], Seq[(Array[Byte], BigInt)])],
      end: Boolean = true
  ): String =
    val out = entries.foldLeft(new StringBuilder) { case (o, (currency, tokens)) =>
      o.append("1")
      o.append(byteString(written + o.length, currency))
      tokens
        .foldLeft(o) { case (o2, (token, amount)) =>
          o2.append("1")
          o2.append(byteString(written + o2.length, token)).append(integer(amount))
        }
        .append("0")
    }
    (if end then out.append("0") else out).toString

  private def value(
      entries: (Array[Byte], Seq[(Array[Byte], BigInt)])*
  ): Either[FlatError, Constant] =
    constantOf(Seq(13), at => valueBits(at, entries))

  private def k(bytes: Int*): Array[Byte] = bytes.map(_.toByte).toArray
  private def key(bytes: Int*): Value.Key = Value.Key(Bytes.wrap(k(bytes*))).getOrElse(fail("key"))

  // `Flat Value`, `K`, `Quantity` and `buildValueWith` (`Value.hs:103-108`, 137-144, 223-232, 637-694)
  test("value constants: keys and amounts checked as read, then the order of the whole") {
    import Value.Invalid
    val long = Array.fill[Byte](33)(1)
    assertEquals(
      value(long -> Seq(k(1) -> BigInt(1))),
      Left(FlatError.InvalidValue(Invalid.KeyTooLong(Bytes.wrap(long))))
    )
    // a key too long is refused although the bits after it are missing
    assertEquals(
      constantOf(Seq(13), at => "1" + byteString(at + 1, long)),
      Left(FlatError.InvalidValue(Invalid.KeyTooLong(Bytes.wrap(long))))
    )
    val tooBig = BigInt(1) << 127
    assertEquals(
      value(k(1) -> Seq(k(1) -> tooBig)),
      Left(FlatError.InvalidValue(Invalid.QuantityOutOfBounds(tooBig)))
    )
    assertEquals(
      value(k(2) -> Seq(k(1) -> BigInt(1)), k(1) -> Seq(k(1) -> BigInt(1))),
      Left(FlatError.InvalidValue(Invalid.CurrencySymbolsNotAscending(key(2), key(1))))
    )
    assertEquals(
      value(k(1) -> Seq(k(1) -> BigInt(1)), k(1) -> Seq(k(2) -> BigInt(1))),
      Left(FlatError.InvalidValue(Invalid.CurrencySymbolsNotAscending(key(1), key(1))))
    )
    assertEquals(
      value(k(1) -> Seq(k(2) -> BigInt(1), k(2) -> BigInt(1))),
      Left(FlatError.InvalidValue(Invalid.TokenNamesNotAscending(key(1), key(2), key(2))))
    )
    assertEquals(value(k(1) -> Seq()), Left(FlatError.InvalidValue(Invalid.EmptyInnerMap(key(1)))))
    assertEquals(
      value(k(1) -> Seq(k() -> BigInt(0))),
      Left(FlatError.InvalidValue(Invalid.ZeroQuantity(key(1), key())))
    )
    val max = (BigInt(1) << 127) - 1
    val expected = Value.build(
      Vector(
        key() -> Vector(
          key(1) -> Value.Quantity(-max - 1).getOrElse(fail("q")),
          key(2) -> Value.Quantity(max).getOrElse(fail("q"))
        )
      )
    )
    assertEquals(
      value(k() -> Seq(k(1) -> (-max - 1), k(2) -> max)),
      expected.map(Constant.Value(_)).left.map(e => fail(e.toString))
    )
    assertEquals(value(), Right(Constant.Value(Value.empty)))
  }

  // `decodeListWith`'s `goZero` (`Strict.hs:60-88`): an element of no bits repeats
  test("lists and arrays of elements of no bits") {
    val units = constantOf(Seq(7, 5, 3), _ => "1" * 1000 + "0")
    assertEquals(units.map { case l: Constant.List => l.items.size; case _ => -1 }, Right(1000))
    val pairs = constantOf(Seq(7, 12, 7, 7, 6, 3, 3), _ => "111" + "0")
    val pair = Constant.Pair(Constant.Unit, Constant.Unit)
    assertEquals(
      pairs,
      Constant.Array
        .of(DefaultUni.Pair(DefaultUni.Unit, DefaultUni.Unit), Vector.fill(3)(pair))
        .left
        .map(e => fail(e.toString))
    )
    assertEquals(constantOf(Seq(7, 5, 3), _ => "1" * 3), Left(FlatError.NotEnoughSpace(48, 1)))
  }

  // `compile` (`Universe.hs:166-186`): the reference builds the value of a part of no bits once
  test("a part of a type made of units is one value, shared by every value that holds it") {
    // a script of about 16 KiB: a list of 40,000 pairs of a bool and a pair of units 2,500 deep
    val depth = 2500
    val count = 40000
    val units = Vector.fill(depth)(Vector(7, 7, 6)).flatten ++ Vector.fill(depth + 1)(3)
    val tags = Vector(7, 5, 7, 7, 6, 4) ++ units
    assert(tags.size * 5 + count * 2 <= 16384 * 8)
    val decoded = SmallStack.run(constantOf(tags, _ => "11" * count + "0"))
    val elements = decoded match
      case Right(l: Constant.List) => l.items
      case other                   => fail(s"not a list: $other")
    assertEquals(elements.size, count)
    val shared = elements.collect { case Constant.Pair(_, second) => second }
    assert(shared.forall(_ eq shared(0)), "the unit part is one value")
  }

  // `maxBoundsByPV` (`Versions.hs:389-399`) through `scriptCBORDecoder` (`SerialisedScript.hs:207-234`)
  test("the ledger's bounds: 32 type tags and 1024 constr fields from protocol 11") {
    def fields(n: Int): String = bits(8, 4) + natural(0) + ("1" + error) * n + "0"
    val pv11 = Checks.ledger(PlutusLanguage.V3, ProtocolMajor.VanRossem)
    val pv10 = Checks.ledger(PlutusLanguage.V3, ProtocolMajor.Plomin)
    assert(decodeBits(v110 + fields(1024), pv11).isRight)
    assertEquals(
      decodeBits(v110 + fields(1025), pv11),
      Left(
        FlatError.Refused(
          Availability.Refusal.ConstrTooManyFields(1025, 1024, ProtocolMajor.VanRossem)
        )
      )
    )
    assert(decodeBits(v110 + fields(1025), pv10).isRight)
    def listType(levels: Int): Vector[Int] = Vector.fill(levels)(Vector(7, 5)).flatten :+ 0
    def nested(levels: Int, checks: Checks) =
      decodeBits(v110 + bits(4, 4) + tagList(listType(levels)) + "0", checks)
    assert(nested(15, pv11).isRight, "31 tags")
    val tooLarge = DefaultUni.List(
      Vector.fill(15)(()).foldLeft[DefaultUni](DefaultUni.Integer)((t, _) => DefaultUni.List(t))
    )
    assertEquals(
      nested(16, pv11),
      Left(
        FlatError.Refused(
          Availability.Refusal.ConstantTypeTooLarge(33, 32, ProtocolMajor.VanRossem)
        )
      )
    )
    assertEquals(tooLarge.size, 33)
    assert(nested(16, pv10).isRight)
    // the bound is checked on the type, before the value (here cut short) is read
    assertEquals(
      FlatDecoder
        .program(Bytes.wrap(padded(v110 + bits(4, 4) + tagList(listType(16)) + "1")), pv11),
      Left(
        FlatError.Refused(
          Availability.Refusal.ConstantTypeTooLarge(33, 32, ProtocolMajor.VanRossem)
        )
      )
    )
  }

  // ADR 0011: the depth of a term, a type and a value comes from the script
  test("deep terms, types, values and long lists decode on a small stack") {
    val n = 200000
    SmallStack.run {
      val delays = term(bits(1, 4) * n + error)
      assertEquals(
        delays.map(t =>
          Schemes.cata[TermF, Int](t)(layer => cats.Foldable[TermF].foldLeft(layer, 1)(_ + _))
        ),
        Right(n + 1)
      )
      assert(term(bits(3, 4) * n + error * (n + 1)).isRight, "applications nested in the function")
      assert(term((bits(3, 4) + error) * n + error).isRight, "applications nested in the argument")
      assert(
        term((bits(8, 4) + natural(0) + "1") * n + error + "0" * n).isRight,
        "constr in constr"
      )
      assert(term((bits(9, 4) + error + "1") * n + error + "0" * n).isRight, "case in case")
      val wide = term(bits(8, 4) + natural(0) + ("1" + error) * n + "0")
      assertEquals(
        wide.map(_.unfix match { case TermF.Constr(_, fs) => fs.size; case _ => -1 }),
        Right(n)
      )
      val levels = 20000
      val deepType = Vector.fill(levels)(Vector(7, 5)).flatten :+ 0
      val deepValue = constantOf(deepType, _ => "1" * levels + integer(1) + "0" * levels)
      val expected = (1 to levels).foldLeft[Either[Constant.ElementTypeMismatch, Constant]](
        Right(Constant.Integer(1))
      ) { (inner, _) =>
        inner.flatMap(c => Constant.List.of(c.typeOf, Vector(c)))
      }
      assertEquals(deepValue, expected.left.map(e => fail(e.toString)))
      val pairs = Vector.fill(levels)(Vector(7, 7, 6)).flatten ++ Vector.fill(levels + 1)(4)
      assert(
        constantOf(pairs, _ => "1" * (levels + 1)).isRight,
        "pairs nested in the first component"
      )
      val long = constantOf(Seq(7, 5, 0), _ => ("1" + integer(5)) * n + "0")
      assertEquals(long.map { case l: Constant.List => l.items.size; case _ => -1 }, Right(n))
    }
  }

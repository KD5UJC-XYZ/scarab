// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as CV}

/** Boundary expectations are source-derived project tests, not executed Haskell predicates. */
class FeeSizeSuite extends munit.FunSuite:
  private def ok[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private def hex(s: String): Bytes = ok(Bytes.fromHex(s))
  private val base =
    val local = Path.of("fixtures/fee-size")
    if Files.exists(local) then local else Path.of("../fixtures/fee-size")
  private def file(name: String): Bytes = Bytes.fromArray(Files.readAllBytes(base.resolve(name)))
  private def tx(i: Int = 1): Coverage.Projection = ok(
    FeeSize.decode(file(s"transfer-event-$i.cbor"))
  )
  private def params(a: BigInt = 44, b: BigInt = 155381, max: BigInt = 16384): FeeSize.Parameters =
    ok(FeeSize.Parameters.create("Conway", 9, a, b, max))
  private def context(i: Int = 1, p: FeeSize.Parameters = params()): FeeSize.Context =
    ok(FeeSize.Context.decode(p, file(s"transfer-event-$i.resolved.cbor")))
  private def check(
      t: Coverage.Projection,
      p: FeeSize.Parameters = params(),
      i: Int = 1
  ): FeeSizeResult =
    ok(FeeSize.checkTransferFeeAndSize(context(i, p), t))
  private def n(v: CV): Node = Node(v, Bytes.empty)
  private def uint(i: Int): CV = CV.UInt(i)
  private def arr(xs: CV*): CV = CV.Arr(xs.toVector.map(n))
  private def map(xs: (CV, CV)*): CV = CV.Map(xs.toVector.map((k, v) => (n(k), n(v))))
  private def encode(v: CV): Bytes = ok(Cbor.encode(v))
  private def envelope(body: Bytes, witnesses: Bytes, suffix: Bytes = hex("f5f6")): Bytes =
    Bytes(Vector(0x84.toByte) ++ body.value ++ witnesses.value ++ suffix.value)
  private def bodyFields: Vector[(Node, Node)] = ok(Cbor.decode(tx().body.bytes)).value match
    case CV.Map(xs) => xs
    case _          => fail("body")
  private def withBody(fields: Vector[(Node, Node)]): Either[FeeSizeError, Coverage.Projection] =
    FeeSize.decode(envelope(encode(CV.Map(fields)), tx().originalWitnessMap))
  private def malformed[A](e: Either[FeeSizeError, A]): Unit = e match
    case Left(FeeSizeError.Scope(CoverageError.Malformed(_))) => ()
    case other                                                => fail(s"expected malformed: $other")
  private def unsupported[A](e: Either[FeeSizeError, A]): Unit = e match
    case Left(FeeSizeError.Scope(CoverageError.TypedUnsupported(_))) => ()
    case other => fail(s"expected unsupported: $other")

  test("original archived transfers pass fee and size independently of balance rejection") {
    for (i, size, fee) <- Seq((1, 265, 167041), (2, 402, 173069)) do
      val t = tx(i)
      val c = context(i)
      assertEquals(ok(FeeSize.conwayLedgerSize(t)), BigInt(size))
      assertEquals(
        check(t, i = i),
        FeeSizeResult(FeePredicate.Satisfied(fee, fee), SizePredicate.Satisfied(size, 16384))
      )
      val balance = ok(
        Balance.check(
          "Conway",
          9,
          c.resolved.view.mapValues(_.value).toMap,
          ok(Balance.decode(t.original))
        )
      )
      assertEquals(balance.isInstanceOf[BalanceResult.PredicateSatisfied], i == 1)
      if i == 2 then assert(balance.isInstanceOf[BalanceResult.ValueNotConserved])
  }

  test("context-only synthetic fee and size boundaries include equality and simultaneous failure") {
    for i <- Seq(1, 2) do
      val t = tx(i)
      val size = ok(FeeSize.conwayLedgerSize(t))
      for delta <- Seq(-1, 0, 1) do
        val r = check(t, params(b = t.fee - 44 * size + delta), i)
        assertEquals(
          r.fee,
          if delta <= 0 then FeePredicate.Satisfied(t.fee, t.fee + delta)
          else FeePredicate.FeeTooSmall(t.fee, t.fee + delta)
        )
        val s = check(t, params(max = size + delta), i)
        assertEquals(
          s.size,
          if delta < 0 then SizePredicate.MaxTxSize(size, size + delta)
          else SizePredicate.Satisfied(size, size + delta)
        )
      val r = check(t, params(b = t.fee + 1, max = size - 1), i)
      assertEquals(r.fee, FeePredicate.FeeTooSmall(t.fee, size * 44 + t.fee + 1))
      assertEquals(r.size, SizePredicate.MaxTxSize(size, size - 1))
  }

  test("outer array widths and indefinite break never count toward ledger size") {
    val original = tx().original
    for prefix <- Seq(hex("9f"), hex("9804")) do
      val raw = Bytes(
        prefix.value ++ original.value.drop(1) ++ (if prefix == hex("9f") then hex("ff").value
                                                   else Vector.empty)
      )
      val p = ok(FeeSize.decode(raw))
      assertEquals(p.body.bytes, tx().body.bytes)
      assertEquals(p.originalWitnessMap, tx().originalWitnessMap)
      assertEquals(ok(FeeSize.conwayLedgerSize(p)), BigInt(265))
      assertEquals(raw.size, 267)
  }

  test("independently CLI-decodable memo variants retain bytes and source-derived extra fees") {
    val t = tx()
    val body = t.body.bytes
    val wit = t.originalWitnessMap
    val variants = Seq(
      (body, Bytes(hex("b801").value ++ wit.value.drop(1)), 266),
      (body, Bytes(hex("bf").value ++ wit.value.drop(1) ++ hex("ff").value), 266),
      (Bytes(hex("bf").value ++ body.value.drop(1) ++ hex("ff").value), wit, 266),
      (Bytes(body.value.dropRight(5) ++ hex("1b0000000000028c81").value), wit, 269)
    )
    for (b, w, size) <- variants do
      val p = ok(FeeSize.decode(envelope(b, w)))
      assertEquals(p.body.bytes, b)
      assertEquals(p.originalWitnessMap, w)
      assertEquals(p.fee, t.fee)
      assertEquals(ok(FeeSize.conwayLedgerSize(p)), BigInt(size))
      assertEquals(check(p).fee, FeePredicate.FeeTooSmall(167041, 44 * size + 155381))
  }

  test("parameter constructors enforce exact era, protocol and unsigned representation bounds") {
    for era <- Seq("Babbage", "conway", "") do
      assertEquals(
        FeeSize.Parameters.create(era, 9, 0, 0, 0),
        Left(FeeSizeError.UnsupportedEra(era))
      )
    for pv <- Seq(-1, 8, 10) do
      assertEquals(
        FeeSize.Parameters.create("Conway", pv, 0, 0, 0),
        Left(FeeSizeError.UnsupportedProtocol(pv))
      )
    for a <- Seq(BigInt(-1), FeeSize.MaxWord64 + 1) do
      assertEquals(
        FeeSize.Parameters.create("Conway", 9, a, 0, 0),
        Left(FeeSizeError.InvalidParameter("feePerByte", a, FeeSize.MaxWord64))
      )
      assertEquals(
        FeeSize.Parameters.create("Conway", 9, 0, a, 0),
        Left(FeeSizeError.InvalidParameter("feeFixed", a, FeeSize.MaxWord64))
      )
    for m <- Seq(BigInt(-1), FeeSize.MaxWord32 + 1) do
      assertEquals(
        FeeSize.Parameters.create("Conway", 9, 0, 0, m),
        Left(FeeSizeError.InvalidParameter("maxTxSize", m, FeeSize.MaxWord32))
      )
    params(0, 0, 0)
    params(FeeSize.MaxWord64, FeeSize.MaxWord64, FeeSize.MaxWord32)
  }

  test("zero and values beyond 2^53 and products beyond uint64 remain exact") {
    assertEquals(check(tx(), params(0, 0, 0)).fee, FeePredicate.Satisfied(167041, 0))
    for a <- Seq((BigInt(1) << 53) + 1, FeeSize.MaxWord64) do
      val r = check(tx(), params(a, FeeSize.MaxWord64))
      assertEquals(r.fee, FeePredicate.FeeTooSmall(167041, 265 * a + FeeSize.MaxWord64))
  }

  test("numeric component helper checks Word32 sum before narrowing without giant allocation") {
    assertEquals(FeeSize.componentSize(FeeSize.MaxWord32 - 3, 1), Right(FeeSize.MaxWord32))
    assertEquals(
      FeeSize.componentSize(FeeSize.MaxWord32 - 2, 1),
      Left(FeeSizeError.TransactionSizeOverflow(FeeSize.MaxWord32 + 1))
    )
    assertEquals(FeeSize.componentSize(-1, 1), Left(FeeSizeError.InvalidComponentLength(-1)))
    assertEquals(FeeSize.componentSize(1, 0), Left(FeeSizeError.InvalidComponentLength(0)))
  }

  test("missing or empty vkeys do not make fee/size depend on key coverage") {
    for w <- Seq(hex("a0"), hex("a10080"), hex("a100d9010280")) do
      val p = ok(FeeSize.decode(envelope(tx().body.bytes, w)))
      assert(p.witnesses.isEmpty)
      assert(!ok(Coverage.check("Conway", 9, context().resolved, p)).covered)
      assert(check(p).fee.isInstanceOf[FeePredicate.Satisfied])
  }

  test("unresolved inputs block fee evaluation even without reference-input body field") {
    val empty = ok(FeeSize.Context.decode(params(), hex("a0")))
    assertEquals(
      FeeSize.checkTransferFeeAndSize(empty, tx()),
      Left(FeeSizeError.Scope(CoverageError.UnknownSpendingInputs(tx().inputs)))
    )
  }

  test(
    "spending and unrelated output datum/reference script extensions reject at context construction"
  ) {
    val resolved = ok(Cbor.decode(file("transfer-event-1.resolved.cbor"))).value match
      case CV.Map(xs) => xs
      case _          => fail("resolved")
    val (key, value) = resolved.head
    val out = value.value match
      case CV.Arr(xs) => xs
      case _          => fail("output")
    for field <- Seq(2, 3, 99) do
      val mapped = map(uint(0) -> out(0).value, uint(1) -> out(1).value, uint(field) -> CV.Null)
      unsupported(FeeSize.Context.decode(params(), encode(CV.Map(Vector(key -> n(mapped))))))
      val unrelated = n(arr(CV.ByteString(Bytes(Vector.fill(32)(0.toByte))), uint(3)))
      unsupported(
        FeeSize.Context.decode(params(), encode(CV.Map(resolved :+ (unrelated -> n(mapped)))))
      )
    val extended = arr(out(0).value, out(1).value, CV.Null)
    unsupported(FeeSize.Context.decode(params(), encode(CV.Map(Vector(key -> n(extended))))))
    // Safe two-field Babbage-map form is accepted by the reused closure API.
    val plain = map(uint(0) -> out(0).value, uint(1) -> out(1).value)
    assert(FeeSize.Context.decode(params(), encode(CV.Map(Vector(key -> n(plain))))).isRight)
  }

  test("all nontransfer body and witness fields reject even if empty") {
    for field <- (3 to 30) ++ Seq(255) do
      unsupported(withBody(bodyFields :+ (n(uint(field)) -> n(arr()))))
    for field <- (1 to 10) ++ Seq(255) do
      unsupported(FeeSize.decode(envelope(tx().body.bytes, encode(map(uint(field) -> arr())))))
    unsupported(FeeSize.decode(envelope(tx().body.bytes, tx().originalWitnessMap, hex("f4f6"))))
    unsupported(FeeSize.decode(envelope(tx().body.bytes, tx().originalWitnessMap, hex("f5a0"))))
  }

  test("missing fields, duplicate inputs and duplicate semantic map keys are malformed") {
    malformed(withBody(bodyFields.dropRight(1)))
    malformed(withBody(bodyFields :+ bodyFields.head))
    val b = tx().body.bytes
    malformed(
      FeeSize.decode(
        envelope(
          Bytes(hex("a4").value ++ b.value.drop(1) ++ hex("180080").value),
          tx().originalWitnessMap
        )
      )
    )
    malformed(FeeSize.decode(envelope(b, hex("a20080180080"))))
    val input = bodyFields.head._2.value match
      case CV.Tag(tag, inner) =>
        inner.value match
          case CV.Arr(xs) => CV.Tag(tag, n(CV.Arr(xs ++ xs)))
          case _          => fail("inputs")
      case _ => fail("tag")
    malformed(withBody(bodyFields.updated(0, bodyFields.head._1 -> n(input))))
    assertEquals(
      withBody(bodyFields.updated(0, bodyFields.head._1 -> n(arr()))),
      Left(FeeSizeError.Scope(CoverageError.EmptySpendingInputs))
    )
  }

  test("duplicate vkey public keys and resolved references fail prerequisites") {
    val t = tx()
    val w = ok(Cbor.decode(t.originalWitnessMap)).value match
      case CV.Map(Vector((key, ws))) =>
        ws.value match
          case CV.Tag(tag, inner) =>
            inner.value match
              case CV.Arr(xs) => CV.Map(Vector(key -> n(CV.Tag(tag, n(CV.Arr(xs ++ xs))))))
              case _          => fail("witness array")
          case _ => fail("witness set")
      case _ => fail("witness map")
    malformed(FeeSize.decode(envelope(t.body.bytes, encode(w))))
    val resolved = ok(Cbor.decode(file("transfer-event-1.resolved.cbor"))).value match
      case CV.Map(xs) => CV.Map(xs ++ xs)
      case _          => fail("resolved")
    malformed(FeeSize.Context.decode(params(), encode(resolved)))
  }

  test("truncated, trailing, malformed envelope and resource excessive raw input reject") {
    val raw = tx().original
    for r <- Seq(
        Bytes.empty,
        Bytes(raw.value.dropRight(1)),
        Bytes(raw.value :+ 0.toByte),
        hex("83a0a0f5"),
        hex("85a0a0f5f600"),
        Bytes(Vector.fill(1048577)(0.toByte))
      )
    do malformed(FeeSize.decode(r))
    malformed(FeeSize.Context.decode(params(), hex("a000")))
  }

  test("a zero supplied fee passes exactly zero price without changing other scope") {
    val fields = bodyFields.updated(2, bodyFields(2)._1 -> n(CV.UInt(0)))
    val p = ok(withBody(fields))
    assertEquals(check(p, params(0, 0)).fee, FeePredicate.Satisfied(0, 0))
    assertEquals(check(p, params(0, 1)).fee, FeePredicate.FeeTooSmall(0, 1))
  }

  test("nonminimal duplicate output and resolved keys cannot bypass checked closure") {
    val resolved = file("transfer-event-1.resolved.cbor")
    val root = ok(Cbor.decode(resolved)).value match
      case CV.Map(xs) => xs
      case _          => fail("map")
    val key = root.head._1.original
    val output = root.head._2.value match
      case CV.Arr(xs) => xs
      case _          => fail("output")
    val duplicateOutput = Bytes(
      hex("a300").value ++ output(0).original.value ++ hex("01").value ++ output(
        1
      ).original.value ++ hex("180100").value
    )
    malformed(
      FeeSize.Context.decode(params(), Bytes(hex("a1").value ++ key.value ++ duplicateOutput.value))
    )
    val nonminimalKey = Bytes(key.value.dropRight(1) ++ hex("1800").value)
    val out = root.head._2.original
    malformed(
      FeeSize.Context.decode(
        params(),
        Bytes(hex("a2").value ++ key.value ++ out.value ++ nonminimalKey.value ++ out.value)
      )
    )
  }

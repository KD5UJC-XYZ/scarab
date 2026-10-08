// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as CValue}

/** Project-authored adversarial and deterministic metamorphic tests, not upstream oracle vectors.
  */
class BalanceAdversarialSuite extends munit.FunSuite:
  private def node(v: CValue): Node = Node(v, Bytes.empty)
  private def num(n: BigInt): CValue = if n < 0 then CValue.NInt(n) else CValue.UInt(n)
  private def arr(v: CValue*): CValue = CValue.Arr(v.toVector.map(node))
  private def dict(v: (CValue, CValue)*): CValue = CValue.Map(v.toVector.map { case (k, x) =>
    (node(k), node(x))
  })
  private def raw(v: CValue): Bytes = Cbor.encode(v).fold(fail(_), identity)
  private def repeated(n: Int, b: Int): Bytes = Bytes(Vector.fill(n)(b.toByte))
  private val policy = repeated(28, 0x81)
  private val nameA = Bytes(Vector(0x00.toByte, 0xff.toByte))
  private val nameB = Bytes(Vector(0x00.toByte, 0xfe.toByte))
  private val assetA = AssetId.create(policy, nameA).fold(e => fail(e.toString), identity)
  private val assetB = AssetId.create(policy, nameB).fold(e => fail(e.toString), identity)
  private val txid = repeated(32, 0x82)
  private val address = CValue.ByteString(Bytes(Vector(0x60.toByte) ++ Vector.fill(28)(0.toByte)))
  private def ref(i: Int): TxIn = TxIn.create(txid, BigInt(i)).fold(e => fail(e.toString), identity)
  private def input(i: Int): CValue = arr(CValue.ByteString(txid), num(i))
  private def multi(entries: (Bytes, BigInt)*): CValue =
    dict(CValue.ByteString(policy) -> dict(entries.map { case (name, q) =>
      CValue.ByteString(name) -> num(q)
    }*))
  private def amount(coin: BigInt, entries: (Bytes, BigInt)*): CValue =
    if entries.isEmpty then num(coin) else arr(num(coin), multi(entries*))
  private def output(v: CValue): CValue = arr(address, v)
  private def body(
      inputs: Seq[Int],
      outputs: Seq[CValue],
      fee: BigInt,
      mint: Option[CValue] = None
  ): CValue =
    val fields = Vector(
      num(0) -> arr(inputs.map(input)*),
      num(1) -> arr(outputs.map(output)*),
      num(2) -> num(fee)
    )
    dict((fields ++ mint.toVector.map(num(9) -> _))*)
  private def envelope(b: CValue, valid: CValue = CValue.Bool(true)): CValue =
    arr(b, dict(), valid, CValue.Null)
  private def decode(b: CValue): TransferMintBody =
    Balance.decode(raw(envelope(b))).fold(e => fail(e.toString), identity)
  private def check(b: CValue, utxo: Map[TxIn, Value]): BalanceResult =
    Balance.check("Conway", 9, utxo, decode(b)).fold(e => fail(e.toString), identity)
  private def malformed(v: CValue): Unit = Balance.decode(raw(v)) match
    case Left(LedgerError.Malformed(_)) => ()
    case other                          => fail(s"expected typed malformed error, got $other")
  private def withField(b: CValue, key: BigInt, value: CValue): CValue = b match
    case CValue.Map(xs) => CValue.Map(xs :+ (node(num(key)), node(value)))
    case _              => fail("test body must be a map")

  test("mint and burn diagnostics use positive consumption and absolute burn production") {
    val b = body(
      Seq(0),
      Seq(amount(93, nameA -> BigInt(13), nameB -> BigInt(5))),
      7,
      Some(multi(nameA -> BigInt(3), nameB -> BigInt(-4)))
    )
    val expected = Value(100, Map(assetA -> BigInt(13), assetB -> BigInt(9)))
    assertEquals(
      check(b, Map(ref(0) -> Value(100, Map(assetA -> BigInt(10), assetB -> BigInt(9))))),
      BalanceResult.PredicateSatisfied(expected, expected)
    )
  }

  test("over-burn error retains burn-side quantities and exact signed delta") {
    val b = body(Seq(0), Seq(amount(93, nameA -> BigInt(1))), 7, Some(multi(nameA -> BigInt(-10))))
    assertEquals(
      check(b, Map(ref(0) -> Value(100, Map(assetA -> BigInt(9))))),
      BalanceResult.ValueNotConserved(
        Value(100, Map(assetA -> BigInt(9))),
        Value(100, Map(assetA -> BigInt(11))),
        Value(0, Map(assetA -> BigInt(-2)))
      )
    )
  }

  test("equal aggregate token count cannot hide per-asset imbalance") {
    val b = body(Seq(0), Seq(amount(100, nameA -> BigInt(4), nameB -> BigInt(6))), 0)
    assertEquals(
      check(b, Map(ref(0) -> Value(100, Map(assetA -> BigInt(5), assetB -> BigInt(5))))),
      BalanceResult.ValueNotConserved(
        Value(100, Map(assetA -> BigInt(5), assetB -> BigInt(5))),
        Value(100, Map(assetA -> BigInt(4), assetB -> BigInt(6))),
        Value(0, Map(assetA -> BigInt(1), assetB -> BigInt(-1)))
      )
    )
  }

  test("values normalize zero assets, including arithmetic cancellation and hashes") {
    val zero = Value(0, Map(assetA -> BigInt(0), assetB -> BigInt(0)))
    assertEquals(zero, Value.zero)
    assertEquals(zero.hashCode, Value.zero.hashCode)
    val v = Value(BigInt(1) << 90, Map(assetA -> (BigInt(1) << 100)))
    assertEquals(v + -v, Value.zero)
    val b =
      decode(body(Seq(0), Seq(amount(8, nameA -> BigInt(0))), 2, Some(multi(nameB -> BigInt(0)))))
    assertEquals(b.outputs, Vector(Value(8)))
    assertEquals(b.mint, Map.empty[AssetId, BigInt])
    assertEquals(
      Balance.check("Conway", 9, Map(ref(0) -> Value(10)), b),
      Right(BalanceResult.PredicateSatisfied(Value(10), Value(10)))
    )
  }

  test("ADA quantities above 2^53 distinguish adjacent lovelaces") {
    val n = BigInt("45000000000000001")
    for delta <- Seq(-1, 1) do
      val b = body(Seq(0), Seq(amount(n - 7 + delta)), 7)
      assertEquals(
        check(b, Map(ref(0) -> Value(n))),
        BalanceResult.ValueNotConserved(Value(n), Value(n + delta), Value(-delta))
      )
  }

  test("summed coins and assets may exceed uint64 without overflow") {
    val n = (BigInt(1) << 64) - 1
    val b = body(Seq(0, 1), Seq(amount(n, nameA -> n), amount(n, nameA -> n)), 0)
    val total = Value(n * 2, Map(assetA -> (n * 2)))
    assertEquals(
      check(b, Map(ref(0) -> Value(n, Map(assetA -> n)), ref(1) -> Value(n, Map(assetA -> n)))),
      BalanceResult.PredicateSatisfied(total, total)
    )
  }

  test("generated input/output permutations and output splitting preserve all totals") {
    for seed <- 1 to 32 do
      val coin = (BigInt(1) << 54) + seed
      val fee = BigInt(seed)
      val tokens = BigInt(seed * 7)
      val utxo = Map(ref(0) -> Value(coin, Map(assetA -> tokens)), ref(1) -> Value(coin + 3))
      val expected = Value(coin * 2 + 3, Map(assetA -> tokens))
      val merged = Seq(amount(coin * 2 + 3 - fee, nameA -> tokens))
      val split = Seq(amount(coin - fee, nameA -> tokens), amount(coin + 3))
      for ins <- Seq(Seq(0, 1), Seq(1, 0)); outs <- Seq(merged, split, split.reverse) do
        assertEquals(
          check(body(ins, outs, fee), utxo),
          BalanceResult.PredicateSatisfied(expected, expected)
        )
      for drift <- Seq(-1, 1) do
        assertEquals(
          check(body(Seq(0, 1), merged, fee + drift), utxo),
          BalanceResult.ValueNotConserved(
            expected,
            Value(expected.lovelace + drift, expected.assets),
            Value(-drift)
          )
        )
  }

  test("unresolved inputs are reported together and never substituted with zero") {
    val b = decode(body(Seq(0, 1, 2), Seq(amount(0)), 0))
    assertEquals(
      Balance.check("Conway", 9, Map(ref(0) -> Value.zero), b),
      Left(LedgerError.UnresolvedInputs(Set(ref(1), ref(2))))
    )
  }

  test("negative resolved coin or token quantities fail; unrelated UTxO is ignored") {
    val b = decode(body(Seq(0), Seq(amount(0)), 0))
    for v <- Seq(Value(-1), Value(0, Map(assetA -> BigInt(-1)))) do
      assert(Balance.check("Conway", 9, Map(ref(0) -> v), b).isLeft)
    assertEquals(
      Balance.check("Conway", 9, Map(ref(0) -> Value.zero, ref(1) -> Value(-1)), b),
      Right(BalanceResult.PredicateSatisfied(Value.zero, Value.zero))
    )
  }

  test("era and protocol gates remain explicit and cannot return predicate success") {
    val b = decode(body(Seq(0), Seq(amount(10)), 0))
    val utxo = Map(ref(0) -> Value(10))
    for era <- Seq("Babbage", "Mary", "conway", "", "Unknown") do
      assertEquals(Balance.check(era, 9, utxo, b), Left(LedgerError.UnsupportedEra(era)))
    for pv <- Seq(-1, 0, 8, 10, Int.MaxValue) do
      assertEquals(Balance.check("Conway", pv, utxo, b), Left(LedgerError.UnsupportedProtocol(pv)))
  }

  test("every unmodeled body key is rejected even if its value is empty or zero") {
    val b = body(Seq(0), Seq(amount(0)), 0)
    val special = Map(
      4 -> LedgerError.UnsupportedCertificates,
      5 -> LedgerError.UnsupportedWithdrawals,
      20 -> LedgerError.UnsupportedProposals,
      22 -> LedgerError.UnsupportedDonation,
      13 -> LedgerError.UnsupportedCollateralPath,
      16 -> LedgerError.UnsupportedCollateralPath,
      17 -> LedgerError.UnsupportedCollateralPath
    )
    for key <- 0 to 30 if !Set(0, 1, 2, 9).contains(key) do
      val expected = special.getOrElse(key, LedgerError.UnsupportedBodyFields(Set(BigInt(key))))
      assertEquals(Balance.decode(raw(envelope(withField(b, key, num(0))))), Left(expected))
    assertEquals(
      Balance.decode(raw(envelope(b, CValue.Bool(false)))),
      Left(LedgerError.UnsupportedCollateralPath)
    )
    malformed(envelope(b, num(1)))
  }

  test(
    "byte lengths and unsigned input index are enforced at both construction and wire boundaries"
  ) {
    for n <- Seq(0, 27, 29, 32) do assert(AssetId.create(repeated(n, 1), Bytes.empty).isLeft)
    assert(AssetId.create(policy, Bytes.empty).isRight)
    assert(AssetId.create(policy, repeated(32, 255)).isRight)
    assert(AssetId.create(policy, repeated(33, 255)).isLeft)
    for n <- Seq(0, 31, 33) do assert(TxIn.create(repeated(n, 2), 0).isLeft)
    for i <- Seq(BigInt(-1), BigInt(65536), BigInt(1) << 80) do assert(TxIn.create(txid, i).isLeft)
    for i <- Seq(0, 65535) do assert(TxIn.create(txid, i).isRight)
    for i <- Seq(BigInt(-1), BigInt(65536)) do
      val b =
        dict(num(0) -> arr(arr(CValue.ByteString(txid), num(i))), num(1) -> arr(), num(2) -> num(0))
      malformed(envelope(b))
    for p <- Seq(repeated(27, 1), repeated(29, 1)); name <- Seq(Bytes.empty, repeated(33, 1)) do
      malformed(
        envelope(
          body(
            Seq(0),
            Seq(amount(0)),
            0,
            Some(dict(CValue.ByteString(p) -> dict(CValue.ByteString(name) -> num(1))))
          )
        )
      )
    malformed(envelope(body(Seq(0), Seq(amount(0, repeated(33, 1) -> BigInt(1))), 0)))
  }

  test("negative outputs and fees are malformed while mint accepts only signed int64") {
    malformed(envelope(body(Seq(0), Seq(amount(-1)), 0)))
    malformed(envelope(body(Seq(0), Seq(amount(0, nameA -> BigInt(-1))), 0)))
    malformed(envelope(body(Seq(0), Seq(amount(0)), -1)))
    for q <- Seq(-(BigInt(1) << 63), (BigInt(1) << 63) - 1) do
      assertEquals(
        decode(body(Seq(0), Seq(amount(0)), 0, Some(multi(nameA -> q)))).mint,
        Map(assetA -> q)
      )
    for q <- Seq(-(BigInt(1) << 63) - 1, BigInt(1) << 63) do
      malformed(envelope(body(Seq(0), Seq(amount(0)), 0, Some(multi(nameA -> q)))))
  }

  test("duplicate spending inputs and duplicate interpreted CBOR map keys are never collapsed") {
    malformed(envelope(body(Seq(0, 0), Seq(amount(0)), 0)))
    val b = body(Seq(0), Seq(amount(0)), 0)
    malformed(envelope(withField(b, 2, num(0))))
    val repeatedNames = dict(
      CValue.ByteString(policy) -> dict(
        CValue.ByteString(nameA) -> num(1),
        CValue.ByteString(nameA) -> num(2)
      )
    )
    val repeatedPolicies =
      dict(CValue.ByteString(policy) -> dict(), CValue.ByteString(policy) -> dict())
    for m <- Seq(repeatedNames, repeatedPolicies) do
      malformed(envelope(body(Seq(0), Seq(amount(0)), 0, Some(m))))
      malformed(envelope(body(Seq(0), Seq(arr(num(0), m)), 0)))
    val duplicateOutput = dict(num(0) -> address, num(1) -> num(0), num(1) -> num(0))
    malformed(
      envelope(dict(num(0) -> arr(input(0)), num(1) -> arr(duplicateOutput), num(2) -> num(0)))
    )
  }

  test("malformed or ambiguous wrappers and missing required fields fail closed") {
    val b = body(Seq(0), Seq(amount(0)), 0)
    for v <- Seq(
        b,
        arr(b),
        arr(b, dict(), CValue.Bool(true)),
        arr(b, dict(), CValue.Bool(true), CValue.Null, CValue.Null),
        CValue.Tag(24, node(CValue.ByteString(raw(envelope(b))))),
        envelope(CValue.ByteString(raw(b))),
        arr(b, arr(), CValue.Bool(true), CValue.Null)
      )
    do malformed(v)
    for key <- Seq(0, 1, 2) do
      b match
        case CValue.Map(xs) => malformed(envelope(CValue.Map(xs.filterNot(_._1.value == num(key)))))
        case _              => fail("expected test map")
    val encoded = raw(envelope(b))
    assert(Balance.decode(Bytes(encoded.value.dropRight(1))).isLeft)
    assert(Balance.decode(Bytes(encoded.value :+ 0.toByte)).isLeft)
  }

  test("input set tag 258 and address/value map outputs preserve the same projection") {
    val mappedOutput = dict(num(1) -> num(8), num(0) -> address)
    val b = dict(
      num(2) -> num(2),
      num(1) -> arr(mappedOutput),
      num(0) -> CValue.Tag(258, node(arr(input(0))))
    )
    assertEquals(
      check(b, Map(ref(0) -> Value(10))),
      BalanceResult.PredicateSatisfied(Value(10), Value(10))
    )
    val extended = withField(mappedOutput, 2, CValue.Null)
    assertEquals(
      Balance.decode(
        raw(envelope(dict(num(0) -> arr(input(0)), num(1) -> arr(extended), num(2) -> num(0))))
      ),
      Left(LedgerError.UnsupportedOutputFields(Set(BigInt(2))))
    )
    for tag <- Seq(24, 259) do
      malformed(
        envelope(
          dict(num(0) -> CValue.Tag(tag, node(arr(input(0)))), num(1) -> arr(), num(2) -> num(0))
        )
      )
  }

  test("alternate-width encodings cannot disguise duplicate body, policy, or name keys") {
    def fromHex(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
    def rejected(hex: String): Unit = Balance.decode(fromHex(hex)) match
      case Left(LedgerError.Malformed(_)) => ()
      case other => fail(s"expected malformed duplicate-key error, got $other")
    // Complete envelope; the body repeats fee as shortest 02 and wider 1802.
    rejected("84a4008001800200180200a0f5f6")
    val prefix = "84a400800180020009"
    val suffix = "a0f5f6"
    // Each byte-string pair decodes to the same bytes despite differing length widths.
    rejected(prefix + "a2581c" + policy.hex + "a059001c" + policy.hex + "a0" + suffix)
    rejected(
      prefix + "a1581c" + policy.hex + "a242" + nameA.hex + "015802" + nameA.hex + "02" + suffix
    )
    // Nonminimal encodings alone are not duplicates and remain decodable.
    assert(Balance.decode(fromHex("84a3180080180180180200a0f5f6")).isRight)
  }

  test("resolved UTxO CBOR rejects duplicate references and negative values without loss") {
    val n = BigInt("45000000000000001")
    assertEquals(
      Balance.decodeResolved(raw(dict(input(0) -> amount(n, nameA -> BigInt(7))))),
      Right(Map(ref(0) -> Value(n, Map(assetA -> BigInt(7)))))
    )
    assert(Balance.decodeResolved(raw(dict(input(0) -> num(0), input(0) -> num(1)))).isLeft)
    for v <- Seq(num(-1), amount(0, nameA -> BigInt(-1))) do
      assert(Balance.decodeResolved(raw(dict(input(0) -> v))).isLeft)
    val duplicateWidths = "a2825820" + txid.hex + "000082590020" + txid.hex + "180001"
    assert(Balance.decodeResolved(Bytes.fromHex(duplicateWidths).fold(fail(_), identity)).isLeft)
  }

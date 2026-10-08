// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as CValue}
import lab.witness.PublicKey32

/** Project-authored synthetic boundary tests. These are not Haskell ledger oracle cases. */
class CoverageSuite extends munit.FunSuite:
  private def node(v: CValue): Node = Node(v, Bytes.empty)
  private def num(n: BigInt): CValue = if n < 0 then CValue.NInt(n) else CValue.UInt(n)
  private def arr(v: CValue*): CValue = CValue.Arr(v.toVector.map(node))
  private def dict(v: (CValue, CValue)*): CValue =
    CValue.Map(v.toVector.map { case (k, x) => (node(k), node(x)) })
  private def raw(v: CValue): Bytes = Cbor.encode(v).fold(fail(_), identity)
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(fail(_), identity)
  private def repeated(n: Int, b: Int): Bytes = Bytes(Vector.fill(n)(b.toByte))
  private def bs(b: Bytes): CValue = CValue.ByteString(b)
  private def tagged(v: CValue): CValue = CValue.Tag(258, node(v))
  private val txid = repeated(32, 0x42)
  private val keyA = hex("4000cb1414760fd0995428ee2f4552c1d98d39c63a0a5bf087b5c89ccbd4bcf9")
  private val keyB = hex("856d50d5103ad04957da7135586cb0dc3a48c31aa78bbfff51f249ad67157103")
  private val keyC = repeated(32, 0x99)
  private def publicKey(b: Bytes): PublicKey32 =
    PublicKey32.create(b).fold(e => fail(e.toString), identity)
  private def hash(b: Bytes): KeyHash28 = Coverage.keyHash(publicKey(b))
  private def ref(i: Int): TxIn = TxIn.create(txid, i).fold(e => fail(e.toString), identity)
  private def input(i: Int): CValue = arr(bs(txid), num(i))
  private def address(key: Bytes, kind: Int = 6, network: Int = 0): Bytes =
    Bytes(
      Vector(((kind << 4) | network).toByte) ++ hash(key).bytes.value ++
        (if kind == 0 then hash(keyC).bytes.value else Vector.empty)
    )
  private def output(key: Bytes = keyA, mapped: Boolean = false): CValue =
    outputAt(address(key), num(10), mapped)
  private def outputAt(a: Bytes, value: CValue, mapped: Boolean = false): CValue =
    if mapped then dict(num(0) -> bs(a), num(1) -> value) else arr(bs(a), value)
  private def witness(key: Bytes, signature: Bytes = repeated(64, 0)): CValue =
    arr(bs(key), bs(signature))
  private def witnessMap(keys: Seq[Bytes]): CValue =
    dict(num(0) -> arr(keys.map(k => witness(k))*))
  private def body(
      inputs: Seq[Int] = Seq(0),
      outputs: Seq[CValue] = Seq.empty
  ): CValue =
    dict(num(0) -> arr(inputs.map(input)*), num(1) -> arr(outputs*), num(2) -> num(0))
  private def envelope(
      b: CValue,
      witnesses: CValue = dict(),
      valid: CValue = CValue.Bool(true),
      auxiliary: CValue = CValue.Null
  ): CValue = arr(b, witnesses, valid, auxiliary)
  private def projection(b: CValue, keys: Seq[Bytes] = Seq.empty): Coverage.Projection =
    Coverage.decode(raw(envelope(b, witnessMap(keys)))).fold(e => fail(e.toString), identity)
  private def resolved(entries: (Int, CValue)*): Map[TxIn, Coverage.Output] =
    Coverage
      .decodeResolved(raw(dict(entries.map { case (i, out) => input(i) -> out }*)))
      .fold(e => fail(e.toString), identity)
  private def check(
      b: CValue,
      utxo: Map[TxIn, Coverage.Output],
      keys: Seq[Bytes] = Seq.empty
  ): CoverageResult =
    Coverage
      .check("Conway", 9, utxo, projection(b, keys))
      .fold(e => fail(e.toString), identity)
  private def malformed[A](result: Either[CoverageError, A]): Unit = result match
    case Left(CoverageError.Malformed(_)) => ()
    case other                            => fail(s"expected Malformed, got $other")
  private def unsupported[A](result: Either[CoverageError, A]): Unit = result match
    case Left(CoverageError.TypedUnsupported(_)) => ()
    case other                                   => fail(s"expected TypedUnsupported, got $other")
  private def withField(b: CValue, key: Int, value: CValue): CValue = b match
    case CValue.Map(xs) => CValue.Map(xs :+ (node(num(key)), node(value)))
    case _              => fail("test body must be a map")

  test("BLAKE2b-224 hashes raw public keys to independently CLI-checked research values") {
    assertEquals(hash(keyA).bytes.hex, "88028438394946279f9ed8d66d718679b82f26b75391cb6df8107c8f")
    assertEquals(hash(keyB).bytes.hex, "6b75eafbb349012d6335c7200d3c6fbb1f06752e84ee485c0ec67ddd")
    assertEquals(hash(keyA), hash(Bytes.fromArray(keyA.toArray)))
    assertEquals(Set(hash(keyA), hash(keyA)).size, 1)
  }

  test("key-hash wrapper rejects wrong lengths and owns its bytes") {
    for size <- Seq(0, 27, 29, 32) do malformed(KeyHash28.create(repeated(size, 1)))
    val array = Array.fill[Byte](28)(1)
    val h = KeyHash28.create(Bytes.fromArray(array)).fold(e => fail(e.toString), identity)
    array(0) = 9
    val exported = h.bytes.toArray
    exported(1) = 9
    assertEquals(h.bytes, repeated(28, 1))
  }

  test("required owners come only from referenced inputs, excluding stake and recipient keys") {
    val spent = outputAt(address(keyA, kind = 0), num(10))
    val u = resolved(0 -> spent, 1 -> output(keyA), 2 -> output(keyB))
    val result = check(body(Seq(0, 1), Seq(output(keyB))), u, Seq(keyA))
    assertEquals(result.required, Set(hash(keyA)))
    assertEquals(result.provided, Set(hash(keyA)))
    assertEquals(result.missing, Set.empty[KeyHash28])
    assert(result.covered)
  }

  test("missing coverage is an exact set difference and unrelated witnesses are harmless") {
    val u = resolved(0 -> output(keyA), 1 -> output(keyB))
    val b = body(Seq(0, 1))
    val missing = check(b, u, Seq(keyB, keyC))
    assertEquals(missing.required, Set(hash(keyA), hash(keyB)))
    assertEquals(missing.provided, Set(hash(keyB), hash(keyC)))
    assertEquals(missing.missing, Set(hash(keyA)))
    assert(!missing.covered)
    assert(check(b, u, Seq(keyA, keyB, keyC)).covered)
  }

  test("empty witness map, empty vkey arrays and empty tagged vkeys parse but miss the owner") {
    val u = resolved(0 -> output())
    for ws <- Seq(dict(), dict(num(0) -> arr()), dict(num(0) -> tagged(arr()))) do
      val p = Coverage.decode(raw(envelope(body(), ws))).fold(e => fail(e.toString), identity)
      assertEquals(p.witnesses.size, 0)
      val r = Coverage.check("Conway", 9, u, p).fold(e => fail(e.toString), identity)
      assertEquals(r.missing, Set(hash(keyA)))
      assert(!r.covered)
  }

  test("coverage checks public-key membership without asserting signature validity") {
    val u = resolved(0 -> output())
    val outcomes = Seq(0, 1, 255).map { byte =>
      val ws = dict(num(0) -> arr(witness(keyA, repeated(64, byte))))
      val p = Coverage.decode(raw(envelope(body(), ws))).fold(e => fail(e.toString), identity)
      Coverage.check("Conway", 9, u, p).fold(e => fail(e.toString), identity)
    }
    outcomes.foreach { r =>
      assert(r.covered)
      assertEquals(r.provided, Set(hash(keyA)))
    }
    // A correctly sized all-zero key is structurally accepted; curve/signature checks are separate.
    assertEquals(projection(body(), Seq(repeated(32, 0))).witnesses.size, 1)
  }

  test("empty and unresolved spending inputs fail prerequisites rather than vacuous coverage") {
    assertEquals(
      Coverage.decode(raw(envelope(body(Seq.empty)))),
      Left(CoverageError.EmptySpendingInputs)
    )
    val u = resolved(0 -> output())
    assertEquals(
      Coverage.check("Conway", 9, u, projection(body(Seq(0, 1, 2)), Seq(keyA))),
      Left(CoverageError.UnknownSpendingInputs(Set(ref(1), ref(2))))
    )
    val differentId = dict(arr(bs(repeated(32, 0x43)), num(0)) -> output())
    val wrong = Coverage.decodeResolved(raw(differentId)).fold(e => fail(e.toString), identity)
    assertEquals(
      Coverage.check("Conway", 9, wrong, projection(body(), Seq(keyA))),
      Left(CoverageError.UnknownSpendingInputs(Set(ref(0))))
    )
  }

  test("only exact Conway protocol-major 9 scope is supported") {
    val p = projection(body(), Seq(keyA))
    val u = resolved(0 -> output())
    for era <- Seq("Shelley", "Babbage", "conway", "") do unsupported(Coverage.check(era, 9, u, p))
    for pv <- Seq(-1, 0, 8, 10) do unsupported(Coverage.check("Conway", pv, u, p))
  }

  test("every excluded body field is rejected even when explicitly empty") {
    for field <- (3 to 30) ++ Seq(255, 65535); value <- Seq(arr(), dict(), num(0), CValue.Null) do
      unsupported(Coverage.decode(raw(envelope(withField(body(), field, value)))))
  }

  test("every non-vkey witness field is unsupported even when empty") {
    for field <- (1 to 10) ++ Seq(255); value <- Seq(arr(), dict(), CValue.Null) do
      unsupported(Coverage.decode(raw(envelope(body(), dict(num(field) -> value)))))
  }

  test("false validity and any auxiliary payload are outside the closed profile") {
    unsupported(Coverage.decode(raw(envelope(body(), valid = CValue.Bool(false)))))
    for auxiliary <- Seq(arr(), dict(), num(0)) do
      unsupported(Coverage.decode(raw(envelope(body(), auxiliary = auxiliary))))
    malformed(Coverage.decode(raw(envelope(body(), valid = num(1)))))
  }

  test("base and enterprise addresses extract payment hashes on networks zero and one") {
    for kind <- Seq(0, 6); network <- Seq(0, 1); mapped <- Seq(false, true) do
      val a = address(keyA, kind, network)
      val out = outputAt(a, num(10), mapped)
      val decoded = resolved(0 -> out)(ref(0))
      assertEquals(decoded.address, a)
      assertEquals(decoded.paymentKey, hash(keyA))
      assertEquals(decoded.value, Value(10))
      assertEquals(decoded.original, raw(out))
      assertEquals(projection(body(outputs = Seq(out))).outputs.head.paymentKey, hash(keyA))
  }

  test("all other address header types are typed unsupported rather than guessed from length") {
    for kind <- (0 to 15).filterNot(Set(0, 6)); size <- Seq(29, 57) do
      val a = Bytes(Vector((kind << 4).toByte) ++ Vector.fill(size - 1)(0.toByte))
      unsupported(Coverage.decodeResolved(raw(dict(input(0) -> outputAt(a, num(1))))))
      unsupported(Coverage.decode(raw(envelope(body(outputs = Seq(outputAt(a, num(1))))))))
  }

  test("supported address kinds reject invalid networks, lengths and trailing bytes") {
    for kind <- Seq(0, 6); network <- 2 to 15 do
      malformed(
        Coverage.decodeResolved(
          raw(dict(input(0) -> outputAt(address(keyA, kind, network), num(1))))
        )
      )
    for kind <- Seq(0, 6); size <- Seq(1, 28, 30, 56, 58) do
      val a = Bytes(Vector((kind << 4).toByte) ++ Vector.fill(size - 1)(0.toByte))
      malformed(Coverage.decodeResolved(raw(dict(input(0) -> outputAt(a, num(1))))))
    malformed(Coverage.decodeResolved(raw(dict(input(0) -> outputAt(Bytes.empty, num(1))))))
  }

  test("duplicate body, witness, output and resolved keys and semantic set entries are malformed") {
    malformed(Coverage.decode(raw(envelope(withField(body(), 0, arr(input(0)))))))
    malformed(Coverage.decode(raw(envelope(body(), dict(num(0) -> arr(), num(0) -> arr())))))
    malformed(Coverage.decode(raw(envelope(body(Seq(0, 0))))))
    malformed(Coverage.decodeResolved(raw(dict(input(0) -> output(), input(0) -> output()))))
    val duplicateOutput = dict(num(0) -> bs(address(keyA)), num(1) -> num(1), num(1) -> num(2))
    malformed(Coverage.decodeResolved(raw(dict(input(0) -> duplicateOutput))))
    for taggedSet <- Seq(false, true) do
      val ws = arr(witness(keyA), witness(keyA, repeated(64, 1)))
      malformed(
        Coverage.decode(
          raw(envelope(body(), dict(num(0) -> (if taggedSet then tagged(ws) else ws))))
        )
      )
  }

  test("recognized set tags preserve the same input and provided-key semantics") {
    val b = dict(num(0) -> tagged(arr(input(0))), num(1) -> arr(), num(2) -> num(0))
    val ws = dict(num(0) -> tagged(arr(witness(keyA))))
    val p = Coverage.decode(raw(envelope(b, ws))).fold(e => fail(e.toString), identity)
    assertEquals(p.inputs, Set(ref(0)))
    assertEquals(p.witnesses.head.publicKey, publicKey(keyA))
    assert(Coverage.check("Conway", 9, resolved(0 -> output()), p).toOption.get.covered)
  }

  test("malformed envelope, required fields, map keys and witness structures fail closed") {
    for v <- Seq(arr(), arr(body(), dict(), CValue.Bool(true)), dict(), envelope(body(), arr())) do
      malformed(Coverage.decode(raw(v)))
    for b <- Seq(dict(), dict(num(0) -> arr(), num(1) -> arr()), withField(body(), -1, num(0))) do
      malformed(Coverage.decode(raw(envelope(b))))
    for ws <- Seq(num(0), arr(num(0)), arr(arr(bs(keyA))), arr(arr(bs(keyA), bs(repeated(63, 0)))))
    do malformed(Coverage.decode(raw(envelope(body(), dict(num(0) -> ws)))))
    for size <- Seq(0, 31, 33) do
      malformed(Coverage.decode(raw(envelope(body(), witnessMap(Seq(repeated(size, 0)))))))
    for size <- Seq(0, 63, 65) do
      malformed(
        Coverage.decode(
          raw(envelope(body(), dict(num(0) -> arr(witness(keyA, repeated(size, 0))))))
        )
      )
  }

  test("input references enforce exact txid size and unsigned uint16 index") {
    val badInputs = Seq(
      arr(bs(repeated(31, 0)), num(0)),
      arr(bs(txid), num(-1)),
      arr(bs(txid), num(65536)),
      arr(bs(txid)),
      num(0)
    )
    for in <- badInputs do
      val b = dict(num(0) -> arr(in), num(1) -> arr(), num(2) -> num(0))
      malformed(Coverage.decode(raw(envelope(b))))
      malformed(Coverage.decodeResolved(raw(dict(in -> output()))))
  }

  test("output extensions are unsupported and malformed amounts cannot hide in coverage") {
    for field <- Seq(2, 3, 99) do
      val out = dict(num(0) -> bs(address(keyA)), num(1) -> num(1), num(field) -> CValue.Null)
      unsupported(Coverage.decodeResolved(raw(dict(input(0) -> out))))
      unsupported(Coverage.decode(raw(envelope(body(outputs = Seq(out))))))
    for value <- Seq(num(-1), CValue.Null, arr(num(1)), arr(num(1), num(0))) do
      malformed(Coverage.decodeResolved(raw(dict(input(0) -> outputAt(address(keyA), value)))))
    malformed(Coverage.decodeResolved(raw(dict(input(0) -> dict(num(0) -> bs(address(keyA)))))))
  }

  test("resolved outputs retain exact large multiasset values independently of coverage") {
    val policy = repeated(28, 0x77)
    val name = hex("00ff")
    val amount = (BigInt(1) << 63) + 1
    val value = arr(num(amount), dict(bs(policy) -> dict(bs(name) -> num(amount))))
    val out = resolved(0 -> outputAt(address(keyA), value))(ref(0))
    val asset = AssetId.create(policy, name).fold(e => fail(e.toString), identity)
    assertEquals(out.value, Value(amount, Map(asset -> amount)))
  }

  test("original indefinite body bytes are retained rather than normalized before hashing") {
    val canonical = raw(body())
    val indefinite = Bytes(Vector(0xbf.toByte) ++ canonical.value.drop(1) ++ Vector(0xff.toByte))
    val tx = Bytes(Vector(0x84.toByte) ++ indefinite.value ++ hex("a0f5f6").value)
    val p = Coverage.decode(tx).fold(e => fail(e.toString), identity)
    assertEquals(p.body.bytes, indefinite)
    assertNotEquals(p.body.bytes, canonical)
    assertEquals(p.inputs, Set(ref(0)))
  }

  test("truncated and trailing CBOR and decoder resource excesses return typed malformed") {
    val tx = raw(envelope(body()))
    malformed(Coverage.decode(Bytes(tx.value.dropRight(1))))
    malformed(Coverage.decode(Bytes(tx.value :+ 0.toByte)))
    malformed(Coverage.decode(Bytes.empty))
    malformed(Coverage.decodeResolved(Bytes.empty))
    malformed(Coverage.decode(repeated(1048577, 0)))
    val deep = Bytes(Vector.fill(70)(0x81.toByte) :+ 0.toByte)
    malformed(Coverage.decode(deep))
  }

  test("unknown input and witness set tags cannot silently acquire set semantics") {
    for tag <- Seq(0, 24, 259) do
      val b =
        dict(num(0) -> CValue.Tag(tag, node(arr(input(0)))), num(1) -> arr(), num(2) -> num(0))
      malformed(Coverage.decode(raw(envelope(b))))
      val ws = dict(num(0) -> CValue.Tag(tag, node(arr(witness(keyA)))))
      malformed(Coverage.decode(raw(envelope(body(), ws))))
  }

  test("input and witness permutations preserve deterministic coverage diagnostics") {
    val u = resolved(0 -> output(keyA), 1 -> output(keyB), 2 -> output(keyA))
    val expected = check(body(Seq(0, 1, 2)), u, Seq(keyB, keyC))
    for inputs <- Seq(0, 1, 2).permutations; keys <- Seq(keyB, keyC).permutations do
      val result = check(body(inputs), u, keys)
      assertEquals(result.required, expected.required)
      assertEquals(result.provided, expected.provided)
      assertEquals(result.missing, expected.missing)
      assertEquals(result.toString, expected.toString)
  }

  test("resolved multiasset maps reject duplicate identifiers and malformed dimensions") {
    val policy = repeated(28, 0x77)
    val name = hex("00ff")
    val badAssets = Seq(
      dict(bs(policy) -> dict(bs(name) -> num(1)), bs(policy) -> dict(bs(name) -> num(2))),
      dict(bs(policy) -> dict(bs(name) -> num(1), bs(name) -> num(2))),
      dict(bs(repeated(27, 0)) -> dict(bs(name) -> num(1))),
      dict(bs(policy) -> dict(bs(repeated(33, 0)) -> num(1))),
      dict(bs(policy) -> dict(bs(name) -> num(-1)))
    )
    for assets <- badAssets do
      val out = outputAt(address(keyA), arr(num(10), assets))
      malformed(Coverage.decodeResolved(raw(dict(input(0) -> out))))
      malformed(Coverage.decode(raw(envelope(body(outputs = Seq(out))))))
  }

  test("body fee must be an unsigned integer even though coverage does not balance value") {
    for fee <- Seq(num(-1), CValue.Null, arr()) do
      val b = dict(num(0) -> arr(input(0)), num(1) -> arr(), num(2) -> fee)
      malformed(Coverage.decode(raw(envelope(b))))
    val unbalanced = body(outputs = Seq(outputAt(address(keyB), num(1000000))))
    assert(check(unbalanced, resolved(0 -> output()), Seq(keyA)).covered)
  }

  test("noncanonical encodings do not evade semantic duplicate map-key checks") {
    // Required body keys 0,1,2 followed by another key 0 encoded as 18 00.
    val canonical = raw(body())
    val duplicate = Bytes(Vector(0xa4.toByte) ++ canonical.value.drop(1) ++ hex("180080").value)
    val tx = Bytes(Vector(0x84.toByte) ++ duplicate.value ++ hex("a0f5f6").value)
    malformed(Coverage.decode(tx))
  }

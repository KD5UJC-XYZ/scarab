// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.security.{KeyPairGenerator, Signature}
import scala.compiletime.testing.typeCheckErrors
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.witness.{PublicKey32, Signature64, VKeyWitness}

class NativeScriptSuite extends munit.FunSuite:
  private def n(v: V) = Node(v, Bytes.empty)
  private def u(i: BigInt) = V.UInt(i)
  private def int(i: BigInt) = if i < 0 then V.NInt(i) else u(i)
  private def arr(v: V*) = V.Arr(v.toVector.map(n))
  private def dict(v: (V, V)*) = V.Map(v.toVector.map((k, x) => n(k) -> n(x)))
  private def raw(v: V) = Cbor.encode(v).toOption.get
  private def bs(b: Bytes) = V.ByteString(b)
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private val pairs = Vector.fill(3)(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
  private val publicKeys = pairs.map(k => Bytes.fromArray(k.getPublic.getEncoded.takeRight(32)))
  private val hashes = publicKeys.map(Blake2b.hash224.hash)
  private def body(lower: Option[BigInt], upper: Option[BigInt]): V = dict(
    (Vector(u(0) -> arr(), u(1) -> arr(), u(2) -> u(0)) ++
      lower.toVector.map(x => u(8) -> u(x)) ++ upper.toVector.map(x => u(3) -> u(x)))*
  )
  private def envelope(b: V, witnesses: V = dict()) = raw(arr(b, witnesses, V.Bool(true), V.Null))
  private def interval(lower: Option[BigInt] = None, upper: Option[BigInt] = None) =
    ValidityInterval.decode(envelope(body(lower, upper))).toOption.get
  private def signed(i: ValidityInterval.Interval, key: Int): VKeyWitness =
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pairs(key).getPrivate)
    signer.update(i.transactionId.toArray)
    VKeyWitness(
      PublicKey32.create(publicKeys(key)).toOption.get,
      Signature64.fromArray(signer.sign()).toOption.get
    )
  private def keys(i: ValidityInterval.Interval, selected: Vector[Int] = Vector.empty) =
    NativeScript.verifyKeys(i, selected.map(signed(i, _))).toOption.get
  private def sig(k: Int) = arr(u(0), bs(hashes(k)))
  private def all(xs: V*) = arr(u(1), arr(xs*))
  private def any(xs: V*) = arr(u(2), arr(xs*))
  private def threshold(m: BigInt, xs: V*) = arr(u(3), int(m), arr(xs*))
  private def eval(
      v: V,
      i: ValidityInterval.Interval = interval(),
      selected: Vector[Int] = Vector.empty
  ): Boolean =
    NativeScript
      .evaluate(NativeScript.decode(raw(v)).toOption.get, keys(i, selected), i)
      .toOption
      .get
      .satisfied
  private def witnessValue(w: VKeyWitness) = arr(bs(w.publicKey.bytes), bs(w.signature.bytes))

  test("native script hash uses domain zero plus exact bytes: independent hashlib vectors") {
    assertEquals(
      NativeScript.decode(hex("820180")).toOption.get.hash.hex,
      "d441227553a0f1a965fee7d60a0f724b368dd1bddbc208730fccebcf"
    )
    assertEquals(
      NativeScript.decode(hex("820280")).toOption.get.hash.hex,
      "52dc3d43b6d2465e96109ce75ab61abe5e9c1d8a3c9ce6ff8a3af528"
    )
    val encodings = Vector("820180", "82180180", "9f0180ff", "82019fff").map(hex)
    val scripts = encodings.map(NativeScript.decode(_).toOption.get)
    assertEquals(scripts.map(_.original), encodings)
    assertEquals(scripts.map(_.hash).distinct.size, 4)
    scripts.foreach(s =>
      assert(NativeScript.evaluate(s, keys(interval()), interval()).toOption.get.satisfied)
    )
    assertNotEquals(scripts.head.hash, Blake2b.hash224.hash(scripts.head.original))
  }
  test("signature requirement consumes verified hashes and checks original body binding") {
    val i = interval(Some(10), Some(20))
    assert(eval(sig(0), i, Vector(0)))
    assert(!eval(sig(0), i, Vector(1)))
    assert(!eval(sig(0), i))
    val script = NativeScript.decode(raw(sig(0))).toOption.get
    assert(NativeScript.evaluate(script, keys(i, Vector(0)), interval(Some(11), Some(20))).isLeft)
    assert(NativeScript.verifyKeys(interval(Some(11), Some(20)), Vector(signed(i, 0))).isLeft)
  }
  test("all any threshold empty and signed-int64 boundaries follow reference semantics") {
    assert(eval(all()))
    assert(!eval(any()))
    Vector(BigInt(Long.MinValue), BigInt(-1), BigInt(0)).foreach(m => assert(eval(threshold(m))))
    Vector(BigInt(1), BigInt(Long.MaxValue)).foreach(m => assert(!eval(threshold(m))))
    assert(eval(threshold(2, sig(0), sig(0)), selected = Vector(0)))
    assert(!eval(threshold(3, sig(0), sig(0)), selected = Vector(0)))
    assert(eval(any(sig(1), all(), sig(2))))
    assert(!eval(all(sig(0), any()), selected = Vector(0)))
  }
  test("generated key subsets and thresholds agree with counted sequence semantics") {
    val i = interval()
    val children = Vector(sig(0), sig(1), sig(0), sig(2))
    for mask <- 0 until 8 do
      val selected = (0 until 3).filter(k => (mask & (1 << k)) != 0).toVector
      val verified = keys(i, selected)
      val booleans = Vector(0, 1, 0, 2).map(selected.contains)
      for m <- -2 to 6 do
        val script = NativeScript.decode(raw(threshold(m, children*))).toOption.get
        assertEquals(
          NativeScript.evaluate(script, verified, i).toOption.get.satisfied,
          booleans.count(identity) >= m
        )
      assertEquals(
        NativeScript
          .evaluate(NativeScript.decode(raw(all(children*))).toOption.get, verified, i)
          .toOption
          .get
          .satisfied,
        booleans.forall(identity)
      )
      assertEquals(
        NativeScript
          .evaluate(NativeScript.decode(raw(any(children*))).toOption.get, verified, i)
          .toOption
          .get
          .satisfied,
        booleans.exists(identity)
      )
  }
  test("timelocks use interval endpoints inclusively and require corresponding bounds") {
    for lower <- 0 to 5; upper <- 0 to 5; lock <- 0 to 5 do
      val i = interval(Some(lower), Some(upper))
      assertEquals(eval(arr(u(4), u(lock)), i), lock <= lower)
      assertEquals(eval(arr(u(5), u(lock)), i), upper <= lock)
    assert(!eval(arr(u(4), u(0))))
    assert(!eval(arr(u(5), u(ValidityInterval.MaxSlot))))
    assert(eval(arr(u(4), u(ValidityInterval.MaxSlot)), interval(Some(ValidityInterval.MaxSlot))))
    assert(eval(arr(u(5), u(0)), interval(upper = Some(0))))
    // Even an empty/reversed interval can satisfy script predicates; UTXO inclusion is separate.
    val reversed = interval(Some(20), Some(10))
    assert(eval(all(arr(u(4), u(20)), arr(u(5), u(10))), reversed))
    assert(!ValidityInterval.atSlot(reversed, 15).toOption.get.satisfied)
  }
  test("unknown tags shapes arities hashes slots thresholds and truncated encodings reject") {
    val malformed = Vector(
      arr(u(6)),
      arr(u(0)),
      arr(u(4), u(1), u(2)),
      arr(u(1), dict()),
      arr(u(0), bs(Bytes(Vector.fill(27)(0.toByte)))),
      arr(u(0), bs(Bytes(Vector.fill(29)(0.toByte)))),
      arr(u(4), V.NInt(-1)),
      arr(u(5), V.Null),
      arr(u(4), V.Tag(2, n(bs(hex("01"))))),
      threshold(BigInt(Long.MaxValue) + 1),
      threshold(BigInt(Long.MinValue) - 1),
      arr(u(3), V.Bool(true), arr()),
      V.Tag(258, n(all())),
      arr(u(1), V.Tag(258, n(arr())))
    )
    malformed.foreach(v => assert(NativeScript.decode(raw(v)).isLeft))
    assert(NativeScript.decode(hex("82005f581c" + "00" * 28 + "ff")).isLeft)
    val encoded = raw(all(sig(0), any(), threshold(1, sig(1))))
    (0 until encoded.size).foreach(cut =>
      assert(NativeScript.decode(Bytes(encoded.value.take(cut))).isLeft)
    )
    assert(NativeScript.decode(Bytes(encoded.value :+ 0.toByte)).isLeft)
    // Unsupported children still reject underneath a logically satisfied threshold.
    assert(NativeScript.decode(raw(threshold(0, arr(u(99))))).isLeft)
  }
  test("resource bounds are explicit before evaluation and crypto") {
    val deep = (0 to NativeScript.MaxDepth).foldLeft(all())((child, _) => all(child))
    assert(NativeScript.decode(raw(deep)).isLeft)
    assert(NativeScript.decode(raw(all(Vector.fill(1024)(all())*))).isLeft)
    assert(NativeScript.decode(Bytes(Vector.fill(NativeScript.MaxBytes + 1)(0.toByte))).isLeft)
    val i = interval()
    val w = signed(i, 0)
    assert(NativeScript.verifyKeys(i, Vector.fill(NativeScript.MaxWitnesses + 1)(w)).isLeft)
  }
  test("duplicate witnesses collapse as sets but differing signatures cannot hide invalid crypto") {
    val i = interval()
    val w = signed(i, 0)
    assertEquals(NativeScript.verifyKeys(i, Vector(w, w)).toOption.get.hashes, Set(hashes(0)))
    val bad =
      VKeyWitness(w.publicKey, Signature64.create(Bytes(Vector.fill(64)(0.toByte))).toOption.get)
    assert(NativeScript.verifyKeys(i, Vector(w, bad)).isLeft)
    assert(NativeScript.verifyKeys(i, Vector(bad, w)).isLeft)
  }
  test("PV9 witness collection duplicates collapse by script hash and key membership") {
    val b = body(Some(10), Some(20))
    val i = ValidityInterval.decode(envelope(b)).toOption.get
    val w = witnessValue(signed(i, 0))
    val script = all(sig(0), arr(u(4), u(10)), arr(u(5), u(20)))
    for tag <- Vector(false, true) do
      def set(v: V): V = if tag then V.Tag(258, n(v)) else v
      val encoded = envelope(b, dict(u(0) -> set(arr(w, w)), u(1) -> set(arr(script, script))))
      val result = NativeScriptWitnesses.inspect(encoded).toOption.get
      assertEquals(result.scripts.size, 1)
      assertEquals(result.verifiedKeys.hashes, Set(hashes(0)))
      assert(result.evaluations.head.satisfied)
      assert(!result.credentialBound && !result.fullLedgerValidated)
      assertEquals(result.scripts.head.original, raw(script))
      assert(Coverage.decode(encoded).isLeft)
      assert(IntervalProjection.coverage(encoded).isLeft)
  }
  test(
    "missing collections are allowed but present empty duplicate-map or unsupported fields reject"
  ) {
    val b = body(None, None)
    assert(NativeScriptWitnesses.inspect(envelope(b)).toOption.get.scripts.isEmpty)
    Vector(
      dict(u(0) -> arr()),
      dict(u(1) -> arr()),
      dict(u(1) -> arr(all()), u(1) -> arr(all())),
      dict(u(2) -> arr()),
      dict(u(1) -> V.Tag(999, n(arr(all())))),
      dict(u(1) -> arr(Vector.fill(NativeScriptWitnesses.MaxScripts + 1)(all())*))
    ).foreach { w =>
      assert(NativeScriptWitnesses.inspect(envelope(b, w)).isLeft)
    }
    val inspection =
      NativeScriptWitnesses.inspect(envelope(b, dict(u(1) -> arr(any())))).toOption.get
    assert(!inspection.evaluations.head.satisfied)
  }
  test("checked script and verified key constructors cannot be copied or publicly manufactured") {
    assert(
      typeCheckErrors(
        "new lab.ledger.NativeScript.VerifiedKeys(lab.cbor.Bytes.empty, Set.empty)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors("new lab.ledger.NativeScript.Script(lab.cbor.Bytes.empty, null)").nonEmpty
    )
    val k = keys(interval())
    assert(!k.getClass.getMethods.exists(_.getName == "copy"))
  }
  test("inspector preserves indefinite arrays but rejects indefinite vkey/signature strings") {
    val b = body(None, None)
    val i = interval()
    val w = signed(i, 0)
    val encoded = envelope(b, dict(u(0) -> arr(witnessValue(w)), u(1) -> arr(all())))
    val wh = raw(witnessValue(w)).hex
    val indefinite = hex(encoded.hex.replace("81" + wh, "9f9f" + wh.drop(2) + "ffff"))
    assert(NativeScriptWitnesses.inspect(indefinite).isRight)
    val keyString = "5820" + w.publicKey.bytes.hex
    val sigString = "5840" + w.signature.bytes.hex
    assert(
      NativeScriptWitnesses
        .inspect(hex(encoded.hex.replace(keyString, "5f" + keyString + "ff")))
        .isLeft
    )
    assert(
      NativeScriptWitnesses
        .inspect(hex(encoded.hex.replace(sigString, "5f" + sigString + "ff")))
        .isLeft
    )
    val distinctEncoding = hex(encoded.hex.replace("0181820180", "018282018082180180"))
    val result = NativeScriptWitnesses.inspect(distinctEncoding).toOption.get
    assertEquals(result.scripts.size, 2)
    assertNotEquals(result.scripts(0).hash, result.scripts(1).hash)
  }

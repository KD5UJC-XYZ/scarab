// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import scala.compiletime.testing.typeCheckErrors

class NativeAdmissionSuite extends munit.FunSuite:
  private def n(v: V) = Node(v, Bytes.empty)
  private def u(i: BigInt) = V.UInt(i)
  private def arr(xs: V*) = V.Arr(xs.toVector.map(n))
  private def dict(xs: (V, V)*) = V.Map(xs.toVector.map((k, v) => n(k) -> n(v)))
  private def raw(v: V) = Cbor.encode(v).toOption.get
  private def bs(b: Bytes) = V.ByteString(b)
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private def cat(xs: Bytes*) = Bytes(xs.toVector.flatMap(_.value))
  private def array(xs: Vector[Bytes]) = cat(hex("9f"), Bytes(xs.flatMap(_.value)), hex("ff"))
  // Public synthetic fixture seeds only: reproducible signatures, never live keys.
  private val keys =
    (1 to 3).map(i => new Ed25519PrivateKeyParameters(Array.fill(32)(i.toByte), 0)).toVector
  private val publics = keys.map(k => Bytes.fromArray(k.generatePublicKey().getEncoded))
  private val hashes = publics.map(Blake2b.hash224.hash)
  private val digest = Bytes(Vector.fill(32)(9.toByte))
  private val ref = arr(bs(Bytes(Vector.fill(32)(2.toByte))), u(0))
  private val keyRef = arr(bs(Bytes(Vector.fill(32)(3.toByte))), u(1))
  private def sig(i: Int) = arr(u(0), bs(hashes(i)))
  private def all(xs: V*) = arr(u(1), arr(xs*))
  private def any(xs: V*) = arr(u(2), arr(xs*))
  private def threshold(k: BigInt, xs: V*) = arr(u(3), if k < 0 then V.NInt(k) else u(k), arr(xs*))
  private def start(slot: BigInt) = arr(u(4), u(slot))
  private def end(slot: BigInt) = arr(u(5), u(slot))
  private val script = raw(sig(0))
  private def scriptHash(s: Bytes) = NativeScript.decode(s).toOption.get.hash
  private def address(header: Int, hash: Bytes) = Bytes(Vector(header.toByte) ++ hash.value)
  private val recipient = address(0x60, hashes(2))
  private def out(addr: Bytes = recipient, coin: BigInt = 3800000) = arr(bs(addr), u(coin))
  private def env(a: BigInt = 44, b: BigInt = 155381, max: BigInt = 16384, cost: BigInt = 4310) =
    ClusterTransition.environment(digest, digest, 1082026, 0, 9, 0, a, b, max, cost).toOption.get
  private def view(
      s: Bytes = script,
      mixed: Boolean = false,
      shared: Boolean = false,
      slot: BigInt = 20,
      e: ClusterTransition.Environment = env(),
      onlyKey: Boolean = false,
      inputCoin: BigInt = 4000000,
      baseKey: Boolean = false
  ): ClusterTransition.State =
    val addr = if onlyKey then address(0x60, hashes(0)) else address(0x70, scriptHash(s))
    val second =
      if shared then address(0x70, scriptHash(s))
      else if baseKey then Bytes(Vector(0.toByte) ++ hashes(1).value ++ hashes(2).value)
      else address(0x60, hashes(1))
    val entries =
      if mixed || shared then
        Vector(ref -> out(addr, inputCoin / 2), keyRef -> out(second, inputCoin / 2))
      else Vector(ref -> out(addr, inputCoin))
    ClusterTransition.checkpoint(e, raw(dict(entries*)), 700000, slot, digest).toOption.get
  private def body(
      lower: Option[BigInt] = Some(20),
      upper: Option[BigInt] = Some(30),
      mixed: Boolean = false,
      coin: BigInt = 3800000,
      fee: BigInt = 200000,
      output: Option[V] = None,
      extra: Vector[(V, V)] = Vector.empty
  ): Bytes =
    raw(
      dict(
        (Vector(
          u(0) -> arr((if mixed then Vector(ref, keyRef) else Vector(ref))*),
          u(1) -> arr(output.getOrElse(out(coin = coin))),
          u(2) -> u(fee)
        ) ++
          lower.toVector.map(x => u(8) -> u(x)) ++ upper.toVector.map(x => u(3) -> u(x)) ++ extra)*
      )
    )
  private def witness(b: Bytes, i: Int): Bytes =
    val signer = new Ed25519Signer()
    signer.init(true, keys(i))
    val message = Blake2b.hash256.hash(b).toArray
    signer.update(message, 0, message.length)
    raw(arr(bs(publics(i)), bs(Bytes.fromArray(signer.generateSignature()))))
  private def tx(
      b: Bytes = body(),
      scripts: Vector[Bytes] = Vector(script),
      signers: Vector[Int] = Vector(0),
      extraKeys: Vector[Bytes] = Vector.empty,
      extraFields: Vector[(Bytes, Bytes)] = Vector.empty,
      tagged: Boolean = false,
      tail: Bytes = hex("f5f6")
  ): Bytes =
    def collection(xs: Vector[Bytes]) = if tagged then cat(hex("d90102"), array(xs)) else array(xs)
    val ws = signers.map(witness(b, _)) ++ extraKeys
    val fields = (if ws.nonEmpty then Vector(hex("00") -> collection(ws)) else Vector.empty) ++
      (if scripts.nonEmpty then Vector(hex("01") -> collection(scripts))
       else Vector.empty) ++ extraFields
    cat(
      hex("84"),
      b,
      hex("bf"),
      Bytes(fields.flatMap((k, v) => k.value ++ v.value)),
      hex("ff"),
      tail
    )
  private def check(t: Bytes = tx(), v: ClusterTransition.State = view()) =
    NativeAdmission.check(v, t)
  private def rejected(t: Bytes, v: ClusterTransition.State, error: NativeSpending.Error): Unit =
    assertEquals(
      check(t, v).left.toOption,
      Some(AdaAdmission.Failure.Ledger(ClusterTransition.Failure.Rejected(error)))
    )
  private def unsupported(t: Bytes, v: ClusterTransition.State = view()): Unit =
    check(t, v).left.toOption.get match
      case AdaAdmission.Failure.Unsupported(_)                                   => ()
      case AdaAdmission.Failure.Identity(_)                                      => ()
      case AdaAdmission.Failure.Ledger(ClusterTransition.Failure.Unsupported(_)) => ()
      case other => fail(s"expected conservative Unsupported/structural failure: $other")

  test(
    "native proof preserves exact original identity, binds pinned view, and never mutates confirmed state"
  ) {
    val v = view()
    val before = (v.outputMap, v.fees, v.slot, v.id, v.revision)
    val original = tx()
    val receipt = check(original, v).toOption.get
    assertEquals(receipt.transaction.original, original)
    assertEquals(receipt.transaction.originalBody, body())
    assertEquals(receipt.transaction.transactionId, Blake2b.hash256.hash(body()))
    assertEquals(
      (receipt.ledgerStateId, receipt.environmentId, receipt.validationSlot),
      (v.id, v.environment.id, v.slot)
    )
    assertEquals(receipt.native.requiredScripts, Set(scriptHash(script)))
    assertEquals(receipt.spent.size, 1)
    assert(receipt.credentialBound && !receipt.fullLedgerValidated)
    assertEquals((v.outputMap, v.fees, v.slot, v.id, v.revision), before)
    assert(AdaAdmission.prepare("unchanged-default", v, original).isLeft)
    assert(
      AdaAdmission
        .prepare("unchanged-default", view(onlyKey = true), tx(scripts = Vector.empty))
        .isRight
    )
    unsupported(tx(scripts = Vector.empty), view(onlyKey = true))
  }
  test("same-body signature controls reject missing, wrong and invalid alternate signatures") {
    val original = tx()
    assert(check(original).isRight)
    rejected(
      tx(signers = Vector.empty),
      view(),
      NativeSpending.Error.FailedScripts(Set(scriptHash(script)))
    )
    rejected(
      tx(signers = Vector(1)),
      view(),
      NativeSpending.Error.FailedScripts(Set(scriptHash(script)))
    )
    val invalid = raw(arr(bs(publics(0)), bs(Bytes(Vector.fill(64)(0.toByte)))))
    rejected(tx(extraKeys = Vector(invalid)), view(), NativeSpending.Error.InvalidSignature)
    assertEquals(
      check(tx(signers = Vector(0, 0))).toOption.get.native.verifiedKeys.hashes,
      Set(hashes(0))
    )
    assert(check(tx(signers = Vector(0, 1, 2))).isRight)
    assertEquals(
      check(tx(signers = Vector(0, 0))).toOption.get.transaction.transactionId,
      check(original).toOption.get.transaction.transactionId
    )
  }
  test(
    "script sets require exactly the consumed credentials; duplicate identical scripts deduplicate semantically"
  ) {
    val other = raw(all())
    rejected(
      tx(scripts = Vector.empty),
      view(),
      NativeSpending.Error.MissingScripts(Set(scriptHash(script)))
    )
    rejected(
      tx(scripts = Vector(other)),
      view(),
      NativeSpending.Error.WrongScriptHashes(Set(scriptHash(script)), Set(scriptHash(other)))
    )
    rejected(
      tx(scripts = Vector(script, other)),
      view(),
      NativeSpending.Error.ExtraneousScripts(Set(scriptHash(other)))
    )
    val duplicate = check(tx(scripts = Vector(script, script))).toOption.get
    assertEquals(duplicate.native.scriptEvaluations.size, 1)
    assert(duplicate.fee.memoBytes > check().toOption.get.fee.memoBytes)
    assert(check(tx(tagged = true)).isRight)
  }
  test("mixed key/native and shared native credentials preserve distinct obligations") {
    val b = body(mixed = true)
    val mixed = view(mixed = true)
    assert(check(tx(b, signers = Vector(0, 1)), mixed).isRight)
    rejected(tx(b), mixed, NativeSpending.Error.MissingKeys(Set(hashes(1))))
    rejected(
      tx(b, signers = Vector(1)),
      mixed,
      NativeSpending.Error.FailedScripts(Set(scriptHash(script)))
    )
    val shared = check(tx(b), view(shared = true)).toOption.get
    assertEquals(shared.native.scriptInputs.size, 2)
    assertEquals(shared.native.requiredScripts.size, 1)
    assert(shared.native.requiredKeys.isEmpty)
    assert(check(tx(b, signers = Vector(0, 1)), view(mixed = true, baseKey = true)).isRight)
  }
  test("all any and threshold positive/negative pairs use the same bodies") {
    Vector(
      (all(sig(0), sig(1)), Vector(0, 1), Vector(0)),
      (any(sig(0), sig(1)), Vector(1), Vector(2)),
      (threshold(2, sig(0), sig(1), sig(2)), Vector(0, 2), Vector(0))
    ).foreach { (term, good, bad) =>
      val s = raw(term)
      val v = view(s)
      assert(check(tx(scripts = Vector(s), signers = good), v).isRight)
      rejected(
        tx(scripts = Vector(s), signers = bad),
        v,
        NativeSpending.Error.FailedScripts(Set(scriptHash(s)))
      )
    }
  }
  test("empty and signed threshold predicates follow existing reference-Int semantics") {
    Vector(all(), threshold(0), threshold(-1), threshold(0, sig(0))).foreach { term =>
      val s = raw(term)
      assert(check(tx(scripts = Vector(s), signers = Vector.empty), view(s)).isRight)
    }
    Vector(any(), threshold(1), threshold(2, sig(0))).foreach { term =>
      val s = raw(term)
      rejected(
        tx(scripts = Vector(s)),
        view(s),
        NativeSpending.Error.FailedScripts(Set(scriptHash(s)))
      )
    }
    val duplicatedChild = raw(threshold(2, sig(0), sig(0)))
    assert(check(tx(scripts = Vector(duplicatedChild)), view(duplicatedChild)).isRight)
  }
  test(
    "timelocks check declared interval, independently of lower-inclusive upper-exclusive pinned slot"
  ) {
    val s = raw(all(start(20), end(30)))
    val original = tx(scripts = Vector(s), signers = Vector.empty)
    assert(check(original, view(s, slot = 20)).isRight)
    assert(check(original, view(s, slot = 29)).isRight)
    Vector(19, 30).foreach(slot =>
      rejected(original, view(s, slot = slot), NativeSpending.Error.OutsideValidityInterval)
    )
    Vector(body(lower = Some(19)), body(lower = None), body(upper = Some(31)), body(upper = None))
      .foreach { b =>
        rejected(
          tx(b, scripts = Vector(s), signers = Vector.empty),
          view(s),
          NativeSpending.Error.FailedScripts(Set(scriptHash(s)))
        )
      }
    Vector(body(lower = Some(20), upper = Some(20)), body(lower = Some(21), upper = Some(20)))
      .foreach { b =>
        rejected(
          tx(b, scripts = Vector(s), signers = Vector.empty),
          view(s),
          NativeSpending.Error.OutsideValidityInterval
        )
      }
    assert(check(tx(body(lower = None, upper = None))).isRight)
    val max = ValidityInterval.MaxSlot
    val edge = raw(all(start(max - 1), end(max)))
    val bounded = tx(
      body(lower = Some(max - 1), upper = Some(max)),
      scripts = Vector(edge),
      signers = Vector.empty
    )
    assert(check(bounded, view(edge, slot = max - 1)).isRight)
    rejected(bounded, view(edge, slot = max), NativeSpending.Error.OutsideValidityInterval)
  }
  test("original script and body encodings determine credentials, signatures and memo identity") {
    val sameMeaning = hex("821800" + script.hex.drop(4))
    assertNotEquals(scriptHash(sameMeaning), scriptHash(script))
    rejected(
      tx(scripts = Vector(sameMeaning)),
      view(),
      NativeSpending.Error.WrongScriptHashes(Set(scriptHash(script)), Set(scriptHash(sameMeaning)))
    )
    assert(check(tx(scripts = Vector(sameMeaning)), view(sameMeaning)).isRight)
    val b = body()
    val widened = hex(b.hex.replace("1a00030d40", "1b0000000000030d40"))
    assertNotEquals(widened, b)
    val stale = hex(tx().hex.replace(b.hex, widened.hex))
    rejected(stale, view(), NativeSpending.Error.InvalidSignature)
    val resigned = check(tx(widened)).toOption.get
    assertEquals(resigned.transaction.originalBody, widened)
    assertNotEquals(
      resigned.transaction.transactionId,
      check().toOption.get.transaction.transactionId
    )
    assertEquals(resigned.fee.memoBytes, check().toOption.get.fee.memoBytes + 4)
  }
  test("malformed and unknown witnesses are explicit conservative failures") {
    Vector(
      hex("80"),
      raw(arr(bs(publics(0)), bs(Bytes(Vector.fill(63)(0.toByte))))),
      raw(arr(bs(Bytes(Vector.fill(31)(0.toByte))), bs(Bytes(Vector.fill(64)(0.toByte)))))
    ).foreach(w => unsupported(tx(signers = Vector.empty, extraKeys = Vector(w))))
    Vector(
      raw(arr(u(6))),
      raw(arr(u(0), bs(Bytes.empty))),
      raw(arr(u(4), V.NInt(-1))),
      raw(arr(u(3), u(BigInt(Long.MaxValue) + 1), arr()))
    ).foreach(s => unsupported(tx(scripts = Vector(s))))
    Vector(2, 3, 4, 5, 6, 7, 99).foreach(k =>
      unsupported(tx(extraFields = Vector(raw(u(k)) -> raw(arr()))))
    )
    unsupported(tx(extraFields = Vector(hex("00") -> raw(arr()))))
    unsupported(tx(signers = Vector.empty, extraFields = Vector(hex("00") -> raw(arr()))))
    unsupported(tx(scripts = Vector.empty, extraFields = Vector(hex("01") -> raw(arr()))))
    unsupported(
      tx(
        scripts = Vector.empty,
        extraFields = Vector(hex("01") -> cat(hex("d90103"), array(Vector(script))))
      )
    )
    unsupported(hex("ff"))
    unsupported(cat(tx(), hex("00")))
    assert(NativeAdmission.check(view(), null).isLeft)
    assert(NativeAdmission.check(null, tx()).isLeft)
  }
  test(
    "profile closes body fields, script outputs, multiasset, datum, references, validity and auxiliary data"
  ) {
    Vector(4, 5, 6, 7, 9, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 99).foreach(k =>
      unsupported(tx(body(extra = Vector(u(k) -> u(0)))))
    )
    val excluded = Vector(
      out(address(0x70, scriptHash(script))),
      out(address(0x61, hashes(2))),
      arr(bs(recipient), arr(u(3800000), dict())),
      arr(bs(recipient), u(3800000), V.Null),
      dict(u(0) -> bs(recipient), u(1) -> u(3800000), u(3) -> V.Null)
    )
    excluded.foreach(o => unsupported(tx(body(output = Some(o)))))
    unsupported(tx(tail = hex("f4f6")))
    unsupported(tx(tail = hex("f5a0")))
    val missing = ClusterTransition.checkpoint(env(), raw(dict()), 0, 20, digest).toOption.get
    assert(check(tx(), missing).left.toOption.get match
      case AdaAdmission.Failure
            .Ledger(ClusterTransition.Failure.Rejected(NativeSpending.Error.UnresolvedInputs(_))) =>
        true
      case _ => false)
  }
  test("fee, min-output, size and conservation boundaries retain typed scoped predicates") {
    val original = tx()
    val memo = check(original).toOption.get.fee.memoBytes
    assert(check(original, view(e = env(a = 1, b = 200000 - memo, max = memo))).isRight)
    rejected(
      original,
      view(e = env(a = 1, b = 200001 - memo)),
      NativeSpending.Error.FeeTooSmall(200000, 200001)
    )
    rejected(
      original,
      view(e = env(a = 0, b = 0, max = memo - 1)),
      NativeSpending.Error.TransactionTooLarge(memo, memo - 1)
    )
    rejected(
      tx(body(coin = 3799999)),
      view(),
      NativeSpending.Error.ValueNotConserved(4000000, 3999999)
    )
    val outputBytes = raw(out()).size
    val cost = BigInt(3800000) / (160 + outputBytes)
    assert(check(original, view(e = env(cost = cost))).isRight)
    rejected(original, view(e = env(cost = cost + 1)), NativeSpending.Error.MinimumOutputFailed)
    val wider = hex(body().hex.replace("1a0039fbc0", "1b000000000039fbc0"))
    rejected(tx(wider), view(e = env(cost = cost)), NativeSpending.Error.MinimumOutputFailed)
  }
  test(
    "witness limits count before dedup; actual enclosing depth and per-script item limits apply"
  ) {
    val cheap = view(e = env(a = 0, b = 0, max = 65536))
    assert(check(tx(signers = Vector.fill(128)(0)), cheap).isRight)
    unsupported(tx(signers = Vector.fill(129)(0)), cheap)
    assert(check(tx(scripts = Vector.fill(32)(script)), cheap).isRight)
    unsupported(tx(scripts = Vector.fill(33)(script)), cheap)
    val nestedOk = (1 to 6).foldLeft(sig(0))((child, _) => all(child))
    val nestedBad = (1 to 7).foldLeft(sig(0))((child, _) => all(child))
    val ok = raw(nestedOk)
    val deep = raw(nestedBad)
    assert(NativeScript.decode(deep).isRight)
    assert(check(tx(scripts = Vector(ok)), view(ok)).isRight)
    unsupported(tx(scripts = Vector(deep)), view(deep))
    val wideOk = raw(all(Vector.fill(340)(sig(0))*))
    assert(
      check(tx(scripts = Vector(wideOk)), view(wideOk, e = env(a = 0, b = 0, max = 65536))).isRight
    )
    val wide = raw(all(Vector.fill(341)(sig(0))*))
    assert(NativeScript.decode(wide).isLeft)
    unsupported(tx(scripts = Vector(wide)))
    assert(check(Bytes(Vector.fill(65537)(0.toByte))).isLeft)
  }
  test("checked native proof cannot be manufactured or copied into a candidate") {
    assert(
      typeCheckErrors(
        "new lab.ledger.NativeAdmission.Checked(null,null,null,null,null,null,null,0)"
      ).nonEmpty
    )
    assert(typeCheckErrors("null.asInstanceOf[lab.ledger.NativeAdmission.Checked].copy()").nonEmpty)
  }

  test("128 input/output boundaries are checked before unresolved-input or balance work") {
    def replaceInputs(b: Bytes, refs: Vector[V]): Bytes =
      val fields = Cbor.decode(b).toOption.get.value.asInstanceOf[V.Map].value
      raw(V.Map(fields.map((k, v) => if k.value == u(0) then k -> n(arr(refs*)) else k -> v)))
    val refs = (0 until 129).map(i => arr(bs(digest), u(i))).toVector
    val addr = address(0x70, scriptHash(script))
    val e = env(a = 0, b = 0, max = 65536, cost = 1)
    val snapshot = raw(dict(refs.map(_ -> out(addr, 31250))*))
    val v = ClusterTransition.checkpoint(e, snapshot, 0, 20, digest).toOption.get
    assertEquals(check(tx(replaceInputs(body(), refs.take(128))), v).toOption.get.spent.size, 128)
    assert(check(tx(replaceInputs(body(), refs)), v).left.toOption.get match
      case AdaAdmission.Failure.Ledger(ClusterTransition.Failure.ResourceLimit(_)) => true
      case _                                                                       => false)
    def manyOutputs(count: Int): Bytes =
      val fields = Cbor.decode(body()).toOption.get.value.asInstanceOf[V.Map].value
      raw(
        V.Map(
          fields.map((k, v) =>
            if k.value == u(1) then k -> n(arr(Vector.fill(count)(out(coin = 1000000))*))
            else k -> v
          )
        )
      )
    val rich = view(e = e, inputCoin = 128000000 + 200000)
    assert(check(tx(manyOutputs(128)), rich).isRight)
    unsupported(tx(manyOutputs(129)), rich)
    val baseOutput = Bytes(Vector(0.toByte) ++ hashes(2).value ++ hashes(1).value)
    assert(check(tx(body(output = Some(out(baseOutput))))).isRight)
  }

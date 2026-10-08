// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.security.{KeyPairGenerator, Signature}
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.chain.CardanoBlockIndex
import scala.compiletime.testing.typeCheckErrors

class NativeSpendingSuite extends munit.FunSuite:
  import NativeSpending.Error.*
  private def n(v: V) = Node(v, Bytes.empty)
  private def u(i: BigInt) = V.UInt(i)
  private def arr(v: V*) = V.Arr(v.toVector.map(n))
  private def dict(v: (V, V)*) = V.Map(v.toVector.map((k, x) => n(k) -> n(x)))
  private def raw(v: V) = Cbor.encode(v).toOption.get
  private def bs(b: Bytes) = V.ByteString(b)
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private def cat(xs: Bytes*) = Bytes(xs.toVector.flatMap(_.value))
  private def array(xs: Vector[Bytes]) = cat(hex("9f"), Bytes(xs.flatMap(_.value)), hex("ff"))
  private val pairs = Vector.fill(3)(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
  private val publics = pairs.map(k => Bytes.fromArray(k.getPublic.getEncoded.takeRight(32)))
  private val hashes = publics.map(Blake2b.hash224.hash)
  private val anchor = Bytes(Vector.fill(32)(1.toByte))
  private val scriptRef = arr(bs(Bytes(Vector.fill(32)(2.toByte))), u(0))
  private val keyRef = arr(bs(Bytes(Vector.fill(32)(3.toByte))), u(1))
  private val untouchedRef = arr(bs(Bytes(Vector.fill(32)(4.toByte))), u(2))
  private val native = raw(
    arr(u(1), arr(arr(u(0), bs(hashes(0))), arr(u(4), u(20)), arr(u(5), u(21))))
  )
  private def scriptHash(script: Bytes) = NativeScript.decode(script).toOption.get.hash
  private def address(header: Int, hash: Bytes) = Bytes(Vector(header.toByte) ++ hash.value)
  private val recipient = address(0x60, hashes(2))
  private def output(addr: Bytes, coin: BigInt) = arr(bs(addr), u(coin))
  private def pre(
      script: Bytes = native,
      scriptOutput: Option[V] = None,
      keyAddress: Option[Bytes] = None
  ): Bytes =
    raw(
      dict(
        scriptRef -> scriptOutput.getOrElse(output(address(0x70, scriptHash(script)), 2000000)),
        keyRef -> output(keyAddress.getOrElse(address(0x60, hashes(1))), 2000000),
        untouchedRef -> output(address(0x60, hashes(2)), 9000000)
      )
    )
  private def body(
      lower: Option[BigInt] = Some(20),
      upper: Option[BigInt] = Some(21),
      extra: Vector[(V, V)] = Vector.empty
  ): Bytes =
    raw(
      dict(
        (Vector(
          u(0) -> arr(scriptRef, keyRef),
          u(1) -> arr(output(recipient, 3800000)),
          u(2) -> u(200000)
        ) ++
          lower.toVector.map(x => u(8) -> u(x)) ++ upper.toVector.map(x => u(3) -> u(x)) ++ extra)*
      )
    )
  private def witness(b: Bytes, index: Int): Bytes =
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pairs(index).getPrivate)
    signer.update(Blake2b.hash256.hash(b).toArray)
    raw(arr(bs(publics(index)), bs(Bytes.fromArray(signer.sign()))))
  private def transaction(
      b: Bytes = body(),
      scripts: Vector[Bytes] = Vector(native),
      signers: Vector[Int] = Vector(0, 1),
      badExtra: Boolean = false
  ): Bytes =
    val ws = signers.map(
      witness(b, _)
    ) ++ (if badExtra then Vector(raw(arr(bs(publics(2)), bs(Bytes(Vector.fill(64)(0.toByte))))))
          else Vector.empty)
    val fields = (if ws.nonEmpty then Vector(hex("00"), array(ws)) else Vector.empty) ++
      (if scripts.nonEmpty then Vector(hex("01"), array(scripts)) else Vector.empty)
    cat(hex("84"), b, cat(hex("bf"), Bytes(fields.flatMap(_.value)), hex("ff")), hex("f5f6"))
  private def post(tx: Bytes): Bytes = raw(
    dict(
      arr(bs(ValidityInterval.decode(tx).toOption.get.transactionId), u(0)) -> output(
        recipient,
        3800000
      ),
      untouchedRef -> output(address(0x60, hashes(2)), 9000000)
    )
  )
  private def block(parent: Bytes, slot: Int, number: Int, tx: Option[Bytes]): Bytes =
    val parts =
      tx.toVector.flatMap(t => Cbor.decode(t).toOption.get.value.asInstanceOf[V.Arr].value)
    val components = Vector(
      array(parts.headOption.toVector.map(_.original)),
      array(parts.lift(1).toVector.map(_.original)),
      hex("a0"),
      hex("80")
    )
    val hash = Blake2b.hash256.hash(Bytes(components.flatMap(b => Blake2b.hash256.hash(b).value)))
    def zero(size: Int) = bs(Bytes(Vector.fill(size)(0.toByte)))
    val header = raw(
      arr(
        arr(
          u(number),
          u(slot),
          bs(parent),
          zero(32),
          zero(32),
          arr(zero(64), zero(80)),
          u(components.map(_.size).sum),
          bs(hash),
          arr(zero(32), u(0), u(0), zero(64)),
          arr(u(11), u(2))
        ),
        zero(448)
      )
    )
    cat(hex("820785"), header, Bytes(components.flatMap(_.value)))
  private def blocks(tx: Bytes): Vector[Bytes] =
    val first = block(anchor, 20, 3, Some(tx))
    Vector(first, block(CardanoBlockIndex.inspect(first).toOption.get.headerHash, 30, 4, None))
  private def context(
      range: Vector[Bytes],
      perByte: BigInt = 44,
      fixed: BigInt = 155381,
      maxSize: BigInt = 16384
  ) =
    ClusterTransfer.Context
      .checked(
        anchor,
        anchor,
        1082026,
        anchor,
        CardanoBlockIndex.inspect(range.last).toOption.get.headerHash,
        10,
        30,
        0,
        0,
        9,
        0,
        perByte,
        fixed,
        maxSize,
        0,
        200000
      )
      .toOption
      .get
  private val minimum = MinimumOutput.Parameters.checked("Conway", 9, 0, 4310).toOption.get
  private def check(tx: Bytes = transaction(), before: Bytes = pre()) =
    NativeSpending.check(tx, before)
  private def compare(tx: Bytes = transaction(), before: Bytes = pre()) =
    val range = blocks(tx)
    ClusterNativeTransfer.compare(context(range), minimum, before, post(tx), tx, range)

  test("captured native and ordinary key inputs bind required credentials and original IDs") {
    val tx = transaction()
    val receipt = compare(tx).toOption.get
    assertEquals(receipt.admission.requiredKeys, Set(hashes(1)))
    assertEquals(receipt.admission.requiredScripts, Set(scriptHash(native)))
    assertEquals(receipt.admission.scriptInputs.size, 1)
    assertEquals(receipt.admission.verifiedKeys.hashes, Set(hashes(0), hashes(1)))
    assertEquals(receipt.transactionId, ValidityInterval.decode(tx).toOption.get.transactionId)
    assertEquals(receipt.bound.interval.slot, BigInt(20))
    assertEquals(receipt.untouchedEntries, 1)
    assert(receipt.credentialBound && receipt.admission.credentialBound)
    assert(!receipt.fullLedgerValidated && !receipt.referenceSnapshotAtomic)
    assert(Coverage.decode(tx).isLeft)
    assert(
      ClusterIntervalTransfer
        .compare(context(blocks(tx)), minimum, pre(), post(tx), tx, blocks(tx))
        .isLeft
    )
  }
  test("missing scripts wrong hashes and extraneous scripts are distinct typed failures") {
    assertEquals(
      check(transaction(scripts = Vector.empty)),
      Left(MissingScripts(Set(scriptHash(native))))
    )
    val wrong = raw(arr(u(2), arr()))
    assertEquals(
      check(transaction(scripts = Vector(wrong))),
      Left(WrongScriptHashes(Set(scriptHash(native)), Set(scriptHash(wrong))))
    )
    assertEquals(
      check(transaction(scripts = Vector(native, wrong))),
      Left(ExtraneousScripts(Set(scriptHash(wrong))))
    )
  }
  test(
    "script key and ordinary payment key obligations are separate and extra signatures all verify"
  ) {
    assertEquals(
      check(transaction(signers = Vector(1))),
      Left(FailedScripts(Set(scriptHash(native))))
    )
    assertEquals(check(transaction(signers = Vector(0))), Left(MissingKeys(Set(hashes(1)))))
    assertEquals(check(transaction(badExtra = true)), Left(InvalidSignature))
    assert(check(transaction(signers = Vector(0, 1, 2))).isRight)
  }
  test("timelocks compare transaction bounds and captured slot inclusion remains separate") {
    Vector(body(lower = Some(19)), body(upper = Some(22)), body(lower = None), body(upper = None))
      .foreach { b =>
        assertEquals(check(transaction(b)), Left(FailedScripts(Set(scriptHash(native)))))
      }
    assert(check(transaction(body(upper = Some(20)))).isRight)
    assertEquals(compare(transaction(body(upper = Some(20)))), Left(OutsideValidityInterval))
  }
  test(
    "duplicate native and vkey witnesses preserve semantic binding and grow original memo size"
  ) {
    val ordinary = transaction()
    val duplicated = transaction(scripts = Vector(native, native), signers = Vector(0, 1, 0))
    val a = check(ordinary).toOption.get
    val b = check(duplicated).toOption.get
    assertEquals(a.requiredScripts, b.requiredScripts)
    assertEquals(a.verifiedKeys.hashes, b.verifiedKeys.hashes)
    assertEquals(b.scriptEvaluations.size, 1)
    assert(b.memoSize > a.memoSize)
    assert(compare(duplicated).isRight)
  }
  test("script bytes rather than decoded meaning bind enterprise credential hashes") {
    val altered = hex("9818" + native.hex.drop(2)) // wrong arity must reject locally
    assert(NativeScript.decode(altered).isLeft)
    val sameMeaning = hex("821801" + native.hex.drop(4))
    assertNotEquals(scriptHash(sameMeaning), scriptHash(native))
    assertEquals(
      check(transaction(scripts = Vector(sameMeaning))),
      Left(WrongScriptHashes(Set(scriptHash(native)), Set(scriptHash(sameMeaning))))
    )
    assert(compare(transaction(scripts = Vector(sameMeaning)), pre(sameMeaning)).isRight)
  }
  test("body mutation cannot reuse signatures; nonminimal original body gets its own output ID") {
    val original = transaction()
    val b = body()
    val differentBody = hex(b.hex.replace("1a00030d40", "1b0000000000030d40"))
    assertNotEquals(b, differentBody)
    val staleSignatures = hex(original.hex.replace(b.hex, differentBody.hex))
    assertEquals(check(staleSignatures), Left(InvalidSignature))
    val resigned = transaction(differentBody)
    assert(compare(resigned).isRight)
    val range = blocks(resigned)
    assert(
      ClusterNativeTransfer
        .compare(context(range), minimum, pre(), post(original), resigned, range)
        .isLeft
    )
    assertNotEquals(
      ValidityInterval.decode(original).toOption.get.transactionId,
      ValidityInterval.decode(resigned).toOption.get.transactionId
    )
  }
  test("captured witness bytes must match before their semantic deduplication") {
    val original = transaction()
    val duplicate = transaction(scripts = Vector(native, native))
    val range = blocks(original)
    assert(
      ClusterNativeTransfer
        .compare(context(range), minimum, pre(), post(original), duplicate, range)
        .left
        .toOption
        .get
        .isInstanceOf[CaptureRejected]
    )
  }
  test("whole original native witness map contributes to exact fee and size boundaries") {
    val tx = transaction()
    val range = blocks(tx)
    val a = check(tx).toOption.get
    val envelope = Cbor.decode(tx).toOption.get.value.asInstanceOf[V.Arr].value
    assertEquals(a.memoSize, BigInt(2 + envelope(0).original.size + envelope(1).original.size))
    val exact = context(range, 1, 200000 - a.memoSize, a.memoSize)
    assert(ClusterNativeTransfer.compare(exact, minimum, pre(), post(tx), tx, range).isRight)
    assertEquals(
      ClusterNativeTransfer
        .compare(context(range, 1, 200001 - a.memoSize), minimum, pre(), post(tx), tx, range),
      Left(FeeTooSmall(200000, 200001))
    )
    assertEquals(
      ClusterNativeTransfer
        .compare(context(range, 0, 0, a.memoSize - 1), minimum, pre(), post(tx), tx, range),
      Left(TransactionTooLarge(a.memoSize, a.memoSize - 1))
    )
    val dup = transaction(scripts = Vector(native, native))
    val dr = blocks(dup)
    assert(
      ClusterNativeTransfer
        .compare(context(dr, 1, 200000 - a.memoSize), minimum, pre(), post(dup), dup, dr)
        .left
        .toOption
        .get
        .isInstanceOf[FeeTooSmall]
    )
  }
  test("unsupported consumed datum reference-script multiasset and address forms fail explicitly") {
    val addr = address(0x70, scriptHash(native))
    val excluded = Vector(
      arr(bs(addr), u(2000000), V.Null),
      dict(u(0) -> bs(addr), u(1) -> u(2000000), u(3) -> V.Null),
      arr(bs(addr), arr(u(2000000), dict())),
      output(address(0x71, scriptHash(native)), 2000000),
      output(Bytes(Vector(0x10.toByte) ++ scriptHash(native).value ++ hashes(2).value), 2000000)
    )
    excluded.foreach(out =>
      assert(
        check(before = pre(scriptOutput = Some(out))).left.toOption.get
          .isInstanceOf[UnsupportedInput]
      )
    )
    assert(
      check(transaction(body(extra = Vector(u(9) -> dict())))).left.toOption.get
        .isInstanceOf[UnsupportedProfile]
    )
  }
  test("base key payment credential is required but stake and destination credentials are not") {
    val base = Bytes(Vector(0.toByte) ++ hashes(1).value ++ hashes(2).value)
    assertEquals(
      check(before = pre(keyAddress = Some(base))).toOption.get.requiredKeys,
      Set(hashes(1))
    )
  }
  test("unresolved inputs and malformed duplicate snapshot references fail separately") {
    assert(check(before = raw(dict())).left.toOption.get.isInstanceOf[UnresolvedInputs])
    val out = output(address(0x70, scriptHash(native)), 2000000)
    assert(
      check(before = raw(dict(scriptRef -> out, scriptRef -> out))).left.toOption.get
        .isInstanceOf[Malformed]
    )
  }
  test("value and untouched-state mutations cannot yield a native transition receipt") {
    assert(
      compare(before =
        pre(scriptOutput = Some(output(address(0x70, scriptHash(native)), 2000001)))
      ).left.toOption.get.isInstanceOf[ValueNotConserved]
    )
    val tx = transaction()
    val range = blocks(tx)
    val mutated = hex(post(tx).hex.replace("1a00895440", "1b0000000000895440"))
    assertNotEquals(mutated, post(tx))
    assert(
      ClusterNativeTransfer
        .compare(context(range), minimum, pre(), mutated, tx, range)
        .left
        .toOption
        .get
        .isInstanceOf[StateMismatch]
    )
  }
  test(
    "credential-bound constructors are sealed and local parser rejection is not ledger invalidity"
  ) {
    assert(
      typeCheckErrors(
        "new lab.ledger.NativeSpending.Admission(null,null,null,null,null,null,0,null,null,0)"
      ).nonEmpty
    )
    assert(NativeSpending.check(hex("ff"), pre()).left.toOption.get.isInstanceOf[DecodeRejected])
  }
  test(
    "shared script credentials deduplicate requirements without demanding nonexistent key inputs"
  ) {
    val tx = transaction(signers = Vector(0))
    val before = pre(keyAddress = Some(address(0x70, scriptHash(native))))
    val receipt = compare(tx, before).toOption.get
    assertEquals(receipt.admission.scriptInputs.size, 2)
    assertEquals(receipt.admission.requiredScripts, Set(scriptHash(native)))
    assert(receipt.admission.requiredKeys.isEmpty)
    val always = raw(arr(u(1), arr()))
    val noKeys = transaction(scripts = Vector(always), signers = Vector.empty)
    val noKeyPre = pre(always, keyAddress = Some(address(0x70, scriptHash(always))))
    assert(compare(noKeys, noKeyPre).toOption.get.admission.verifiedKeys.hashes.isEmpty)
  }
  test("input/output limits reject before resolution and state comparisons") {
    def replace(key: Int, value: V): Bytes =
      val fields = Cbor.decode(body()).toOption.get.value.asInstanceOf[V.Map].value
      raw(V.Map(fields.map((k, v) => if k.value == u(key) then k -> n(value) else k -> v)))
    val inputs = (0 to 128).map(index => arr(bs(anchor), u(index))).toVector
    val outputs = Vector.fill(129)(output(recipient, 3800000))
    assert(
      check(transaction(replace(0, arr(inputs*)))).left.toOption.get.isInstanceOf[ResourceLimit]
    )
    assert(
      check(transaction(replace(1, arr(outputs*)))).left.toOption.get.isInstanceOf[ResourceLimit]
    )
  }
  test("changing a consumed credential at identical value changes authorization requirements") {
    val other = raw(arr(u(1), arr()))
    assertEquals(
      check(before = pre(other)),
      Left(WrongScriptHashes(Set(scriptHash(other)), Set(scriptHash(native))))
    )
    assertEquals(
      check(before = pre(keyAddress = Some(address(0x60, hashes(2))))),
      Left(MissingKeys(Set(hashes(2))))
    )
  }
  test("minimum output calculation measures nonminimal original output coin bytes") {
    val canonical = transaction()
    val node = Cbor.decode(canonical).toOption.get.value.asInstanceOf[V.Arr].value.head
    val outs = node.value
      .asInstanceOf[V.Map]
      .value
      .find(_._1.value == u(1))
      .get
      ._2
      .value
      .asInstanceOf[V.Arr]
      .value
    val cost = BigInt(3800000) / (160 + outs.head.original.size)
    val parameters = MinimumOutput.Parameters.checked("Conway", 9, 0, cost).toOption.get
    val cr = blocks(canonical)
    assert(
      ClusterNativeTransfer
        .compare(context(cr), parameters, pre(), post(canonical), canonical, cr)
        .isRight
    )
    val wider = hex(body().hex.replace("1a0039fbc0", "1b000000000039fbc0"))
    assertNotEquals(wider, body())
    val changed = transaction(wider)
    val br = blocks(changed)
    assertEquals(
      ClusterNativeTransfer.compare(context(br), parameters, pre(), post(changed), changed, br),
      Left(MinimumOutputFailed)
    )
  }

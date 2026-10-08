// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.security.{KeyPairGenerator, Signature}
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.chain.CardanoBlockIndex

class ValidityIntervalSuite extends munit.FunSuite:
  private def n(v: V) = Node(v, Bytes.empty)
  private def u(i: BigInt) = V.UInt(i)
  private def arr(v: V*) = V.Arr(v.toVector.map(n))
  private def dict(v: (V, V)*) = V.Map(v.toVector.map((k, x) => n(k) -> n(x)))
  private def raw(v: V) = Cbor.encode(v).toOption.get
  private def bs(b: Bytes) = V.ByteString(b)
  private def zeros(size: Int) = bs(Bytes(Vector.fill(size)(0.toByte)))
  private val digest = Bytes(Vector.fill(32)(1.toByte))
  private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val public = Bytes.fromArray(key.getPublic.getEncoded.takeRight(32))
  private val address = Bytes(Vector(0x60.toByte) ++ Blake2b.hash224.hash(public).value)
  private val input = arr(bs(digest), u(0))
  private def output(coin: Int) = arr(bs(address), u(coin))
  private def tx(bounds: Vector[(Int, V)] = Vector.empty, sign: Boolean = false): Bytes =
    val body = dict(
      (Vector(u(0) -> arr(input), u(1) -> arr(output(1800000)), u(2) -> u(200000)) ++
        bounds.map((k, v) => u(k) -> v))*
    )
    val witnesses =
      if !sign then dict()
      else
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key.getPrivate)
        signer.update(Blake2b.hash256.hash(raw(body)).toArray)
        dict(u(0) -> arr(arr(bs(public), bs(Bytes.fromArray(signer.sign())))))
    raw(arr(body, witnesses, V.Bool(true), V.Null))
  private def check(lower: Option[BigInt], upper: Option[BigInt], slot: BigInt): Boolean =
    val bounds = lower.toVector.map(v => 8 -> u(v)) ++ upper.toVector.map(v => 3 -> u(v))
    ValidityInterval.check(tx(bounds), slot).toOption.get.satisfied

  test("lower is inclusive and upper exclusive at exact boundaries") {
    assert(!check(Some(10), Some(20), 9))
    assert(check(Some(10), Some(20), 10))
    assert(check(Some(10), Some(20), 19))
    assert(!check(Some(10), Some(20), 20))
  }
  test("absent bounds remain unbounded including zero and uint64 maximum") {
    val max = ValidityInterval.MaxSlot
    assert(check(None, None, 0) && check(None, None, max))
    assert(check(Some(max), None, max))
    assert(check(None, Some(max), max - 1))
    assert(!check(None, Some(max), max))
    assert(!check(None, Some(0), 0))
  }
  test("empty and reversed intervals are decoded but never satisfied") {
    for lower <- 0 to 5; upper <- 0 to 5; slot <- 0 to 6 do
      assertEquals(check(Some(lower), Some(upper), slot), lower <= slot && slot < upper)
  }
  test("uint64 domain rejects negative, null, tagged and overflowing representations") {
    val bad = Vector(
      V.NInt(-1),
      V.Null,
      V.Bool(true),
      V.Text("20"),
      arr(u(20)),
      V.Tag(2, n(bs(Bytes.fromHex("010000000000000000").toOption.get)))
    )
    for key <- Vector(3, 8); value <- bad do
      assert(ValidityInterval.decode(tx(Vector(key -> value))).isLeft)
    assert(ValidityInterval.check(tx(), -1).isLeft)
    assert(ValidityInterval.check(tx(), ValidityInterval.MaxSlot + 1).isLeft)
  }
  test("duplicate bounds and unsupported profile fields reject before map collapse") {
    assert(ValidityInterval.decode(tx(Vector(3 -> u(20), 3 -> u(21)))).isLeft)
    assert(ValidityInterval.decode(tx(Vector(8 -> u(20), 8 -> u(20)))).isLeft)
    assert(ValidityInterval.decode(tx(Vector(9 -> dict()))).isLeft)
    val encoded = tx(Vector(3 -> u(20)))
    (0 until encoded.size by 17).foreach(i =>
      assert(ValidityInterval.decode(Bytes(encoded.value.take(i))).isLeft)
    )
  }
  test("original nonminimal bound encoding and body hash remain unchanged") {
    val encoded = tx(Vector(3 -> u(20)))
    val hex = encoded.hex
    val altered = Bytes.fromHex(hex.replace("0314a0f5f6", "031814a0f5f6")).toOption.get
    assertNotEquals(encoded, altered)
    val interval = ValidityInterval.decode(altered).toOption.get
    assertEquals(interval.originalTransaction, altered)
    val body = Cbor.decode(altered).toOption.get.value.asInstanceOf[V.Arr].value.head.original
    assertEquals(interval.originalBody, body)
    val projection = IntervalProjection.coverage(altered).toOption.get
    assertEquals(IntervalProjection.body(altered).toOption.get.bytes, body)
    assertEquals(
      projection.outputs.head.original,
      Coverage.decode(tx()).toOption.get.outputs.head.original
    )
    assertEquals(interval.transactionId, Blake2b.hash256.hash(body))
    assertNotEquals(
      interval.transactionId,
      ValidityInterval.decode(encoded).toOption.get.transactionId
    )
    assert(ValidityInterval.atSlot(interval, 19).toOption.get.satisfied)
  }

  private def block(parent: Bytes, slot: Int, number: Int, transaction: Option[Bytes]): Bytes =
    val fields = transaction.map(t => Cbor.decode(t).toOption.get.value.asInstanceOf[V.Arr].value)
    val components =
      Vector(V.Arr(fields.toVector.map(_(0))), V.Arr(fields.toVector.map(_(1))), dict(), arr())
    val originals = components.map(raw)
    val hash = Blake2b.hash256.hash(Bytes(originals.flatMap(b => Blake2b.hash256.hash(b).value)))
    val header = arr(
      arr(
        u(number),
        u(slot),
        bs(parent),
        zeros(32),
        zeros(32),
        arr(zeros(64), zeros(80)),
        u(originals.map(_.size).sum),
        bs(hash),
        arr(zeros(32), u(0), u(0), zeros(64)),
        arr(u(11), u(2))
      ),
      zeros(448)
    )
    raw(arr(u(7), V.Arr((header +: components).map(n))))
  private def blocks(transaction: Bytes): Vector[Bytes] =
    val first = block(digest, 20, 3, Some(transaction))
    val last = block(CardanoBlockIndex.inspect(first).toOption.get.headerHash, 30, 4, None)
    Vector(first, last)
  private def context(range: Vector[Bytes]) = ClusterTransfer.Context
    .checked(
      digest,
      digest,
      1082026,
      digest,
      CardanoBlockIndex.inspect(range.last).toOption.get.headerHash,
      10,
      30,
      0,
      0,
      9,
      0,
      44,
      155381,
      16384,
      0,
      200000
    )
    .toOption
    .get
  private val minimum = MinimumOutput.Parameters.checked("Conway", 9, 0, 4310).toOption.get
  private def pre = raw(dict(input -> output(2000000)))
  private def post(transaction: Bytes) =
    val id = ValidityInterval.decode(transaction).toOption.get.transactionId
    raw(dict(arr(bs(id), u(0)) -> output(1800000)))

  test(
    "explicit interval profile completes signed transfer using inclusion slot, not post-query slot"
  ) {
    val original = tx(Vector(8 -> u(20), 3 -> u(21)), sign = true)
    val range = blocks(original)
    val result = ClusterIntervalTransfer
      .compare(context(range), minimum, pre, post(original), original, range)
      .toOption
      .get
    assertEquals(result.bound.interval.slot, BigInt(20))
    assert(result.bound.interval.satisfied)
    assertEquals(
      result.transfer.transactionId,
      ValidityInterval.decode(original).toOption.get.transactionId
    )
    assertEquals(result.profileId, ClusterIntervalTransfer.ProfileId)
    assert(!result.fullLedgerValidated)
    assert(!ValidityInterval.check(original, 30).toOption.get.satisfied)
  }
  test("interval rejection precedes minimum-output and state-delta checks in new profile") {
    val original = tx(Vector(3 -> u(20)), sign = true)
    val range = blocks(original)
    assertEquals(
      ClusterIntervalTransfer
        .compare(context(range), minimum, Bytes.empty, Bytes.empty, original, range),
      Left("OutsideValidityIntervalUTxO")
    )
  }
  test("legacy decoders and transition remain closed to both interval keys") {
    val original = tx(Vector(8 -> u(20), 3 -> u(21)), sign = true)
    val range = blocks(original)
    assert(Coverage.decode(original).isLeft)
    assert(Balance.decode(original).isLeft)
    assert(MinimumOutput.check(minimum, original).isLeft)
    assert(ClusterTransfer.compare(context(range), pre, post(original), original).isLeft)
    assertEquals(RestrictedReplay.FixedResearchSlot, BigInt(3883681))
    assert(RestrictedReplay.environment(Bytes.empty).isLeft)
  }
  test(
    "missing or substituted originals, incomplete range and mutated commitments reject binding"
  ) {
    val original = tx(Vector(3 -> u(21)), sign = true)
    val range = blocks(original)
    val ctx = context(range)
    assert(ClusterIntervalTransfer.bind(ctx, tx(Vector(3 -> u(22)), sign = true), range).isLeft)
    assert(ClusterIntervalTransfer.bind(ctx, original, range.take(1)).isLeft)
    assert(ClusterIntervalTransfer.bind(ctx, original, range.reverse).isLeft)
    assert(ClusterIntervalTransfer.bind(ctx, original, Vector.empty).isLeft)
    assert(ClusterIntervalTransfer.bind(ctx, original, Vector.fill(9)(range.head)).isLeft)
    assert(
      ClusterIntervalTransfer
        .bind(ctx, original, range.updated(0, Bytes(range.head.value.dropRight(1))))
        .isLeft
    )
    assert(ClusterIntervalTransfer.bind(ctx, tx(Vector(3 -> u(21)), sign = false), range).isLeft)
    val root = Cbor.decode(range.head).toOption.get.value.asInstanceOf[V.Arr].value
    val blockFields = root(1).value.asInstanceOf[V.Arr].value
    val header = blockFields(0).value.asInstanceOf[V.Arr].value
    val headerBody = header(0).value.asInstanceOf[V.Arr].value
    val badBody = n(V.Arr(headerBody.updated(6, n(u(0)))))
    val badHeader = V.Arr(header.updated(0, badBody))
    val badFields = n(V.Arr(blockFields.updated(0, n(badHeader))))
    val badBlock = raw(V.Arr(root.updated(1, badFields)))
    assert(ClusterIntervalTransfer.bind(ctx, original, range.updated(0, badBlock)).isLeft)
  }
  test(
    "interval bytes count in fee and size while projected output and witness spans stay original"
  ) {
    val original = tx(Vector(3 -> u(21), 8 -> u(20)), sign = true)
    val projected = IntervalProjection.coverage(original).toOption.get
    val body = IntervalProjection.body(original).toOption.get
    val envelope = Cbor.decode(original).toOption.get.value.asInstanceOf[V.Arr].value
    val fields = envelope(0).value.asInstanceOf[V.Map].value
    val outputs = fields.find(_._1.value == u(1)).get._2.value.asInstanceOf[V.Arr].value
    assertEquals(projected.outputs.head.original, outputs.head.original)
    assertEquals(projected.originalWitnessMap, envelope(1).original)
    val actual = FeeSize
      .componentSize(BigInt(body.bytes.size), BigInt(projected.originalWitnessMap.size))
      .toOption
      .get
    val stripped = FeeSize.conwayLedgerSize(projected).toOption.get
    assert(actual > stripped)
    val parameters = FeeSize.Parameters.create("Conway", 9, 0, 0, actual - 1).toOption.get
    val result = IntervalProjection.feeSize(parameters, body.bytes, projected).toOption.get
    assert(result.size.isInstanceOf[SizePredicate.MaxTxSize])
    val priced =
      FeeSize.Parameters.create("Conway", 9, 1, projected.fee - actual + 1, 16384).toOption.get
    assert(
      IntervalProjection
        .feeSize(priced, body.bytes, projected)
        .toOption
        .get
        .fee
        .isInstanceOf[FeePredicate.FeeTooSmall]
    )
  }
  test("extra otherwise well-formed transaction in anchored range is outside closed comparison") {
    val original = tx(Vector(3 -> u(21)), sign = true)
    val first = blocks(original).head
    val last =
      block(CardanoBlockIndex.inspect(first).toOption.get.headerHash, 30, 4, Some(tx(sign = true)))
    val range = Vector(first, last)
    assert(ClusterIntervalTransfer.bind(context(range), original, range).isLeft)
  }
  test("omitted bounds also compose with the explicit profile") {
    val original = tx(sign = true)
    val range = blocks(original)
    assert(
      ClusterIntervalTransfer
        .compare(context(range), minimum, pre, post(original), original, range)
        .isRight
    )
    assert(ClusterTransfer.compare(context(range), pre, post(original), original).isRight)
  }

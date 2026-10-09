// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayStake as S}
import munit.FunSuite

class GovernancePoolPayloadSuite extends FunSuite:
  private def get[A](v: Either[String, A]): A = v.fold(fail(_), identity)
  private def n(v: V): Node = Node(v, Bytes(Vector.empty))
  private def a(v: V*): V = V.Arr(v.toVector.map(n))
  private def b(size: Int, value: Int): Bytes = Bytes(Vector.fill(size)(value.toByte))
  private def bs(size: Int, value: Int): V = V.ByteString(b(size, value))
  private def cred(script: Boolean, value: Int): V =
    a(V.UInt(if script then 1 else 0), bs(28, value))
  private def set(values: V*): V = V.Tag(258, n(a(values*)))
  private val fields = Vector[V](
    bs(32, 1),
    V.UInt(20),
    V.UInt(3),
    V.Tag(30, n(a(V.UInt(1), V.UInt(4)))),
    cred(false, 2),
    set(bs(28, 3), bs(28, 4)),
    a(
      a(V.UInt(0), V.UInt(3000), bs(4, 1), bs(16, 2)),
      a(V.UInt(1), V.Null, V.Text("pool.example")),
      a(V.UInt(2), V.Text("example"))
    ),
    a(a(V.Text("https://example"), bs(32, 5))),
    V.UInt(7),
    set(cred(false, 6), cred(true, 6))
  )
  private def encode(values: Vector[V] = fields): Bytes = get(Cbor.encode(a(values*)))
  private def decode(raw: Bytes): Either[String, GovernancePoolPayload.Checked] =
    GovernancePoolPayload.decode(raw, ClusterHeaderObservation.sha256(raw))
  private val expected = S.Pool(
    b(32, 1),
    20,
    3,
    S.Ratio(1, 4),
    S.Credential(false, b(28, 2)),
    Set(b(28, 3), b(28, 4)),
    Set(S.Credential(false, b(28, 6)), S.Credential(true, b(28, 6))),
    7
  )

  test("complete ten-field original and every field span are preserved") {
    val raw = encode()
    val value = get(decode(raw))
    assertEquals(value.pool, expected)
    assertEquals(value.original, raw)
    assertEquals(value.payload.original, raw)
    assertEquals(value.fieldOriginals, fields.map(v => get(Cbor.encode(v))))
    assertEquals(value.relays.size, 3)
    assertEquals(value.metadata, Some(GovernancePoolPayload.Metadata("https://example", b(32, 5))))
    assertEquals(GovernancePoolPayload.checkProjection(value, expected), Right(()))
    assert(!value.ledgerAdmitted && !value.nativeConformance)
  }
  test("relay and arbitrary native metadata ByteArray remain in the identity") {
    val original = get(decode(encode()))
    val changed = get(decode(encode(fields.updated(6, a()).updated(7, a(a(V.Text(""), bs(7, 9)))))))
    assertEquals(changed.pool, original.pool)
    assertNotEquals(changed.sha256, original.sha256)
    assertEquals(changed.metadata.get.hash.size, 7)
    assertEquals(get(decode(encode(fields.updated(7, a())))).metadata, None)
    val script = get(decode(encode(fields.updated(4, cred(true, 2)))))
    assert(script.pool.rewardAccount.script)
  }
  test("valid byte pins do not authorize a mismatched stake projection") {
    val decoded = get(decode(encode()))
    val variants = Vector(
      expected.copy(vrf = b(32, 9)),
      expected.copy(pledge = 21),
      expected.copy(cost = 4),
      expected.copy(margin = S.Ratio(1, 3)),
      expected.copy(rewardAccount = S.Credential(true, b(28, 2))),
      expected.copy(owners = Set.empty),
      expected.copy(delegators = Set.empty),
      expected.copy(deposit = 8)
    )
    variants.foreach(p => assert(GovernancePoolPayload.checkProjection(decoded, p).isLeft))
    val mutated = get(decode(encode(fields.updated(1, V.UInt(21)))))
    assert(GovernancePoolPayload.checkProjection(mutated, expected).isLeft)
  }
  test("PV9 tags, duplicate sets, credential bounds and record widths fail closed") {
    val bad = Vector(
      fields.updated(5, a(bs(28, 3))),
      fields.updated(5, set(bs(28, 3), bs(28, 3))),
      fields.updated(9, set(cred(false, 6), cred(false, 6))),
      fields.updated(4, a(V.UInt(2), bs(28, 2))),
      fields.updated(4, a(V.UInt(0), bs(27, 2))),
      fields.updated(0, bs(31, 1)),
      fields.updated(7, V.Null),
      fields.dropRight(1),
      fields.updated(1, V.Tag(2, n(bs(9, 1)))),
      fields.updated(3, V.Tag(30, n(a(V.UInt(2), V.UInt(4))))),
      fields.updated(3, V.Tag(30, n(a(V.UInt(0), V.UInt(0))))),
      fields.updated(3, V.Tag(30, n(a(V.UInt(2), V.UInt(1)))))
    )
    bad.foreach(v => assert(decode(encode(v)).isLeft))
  }
  test("relay and text bounds reject unsupported or malformed values") {
    val relays = Vector(
      a(V.UInt(3)),
      a(V.UInt(0), V.UInt(65536), V.Null, V.Null),
      a(V.UInt(0), V.Null, bs(5, 1), V.Null),
      a(V.UInt(2), V.Text("x" * 129)),
      a(V.UInt(2), V.Text("é" * 65))
    )
    relays.foreach(v => assert(decode(encode(fields.updated(6, a(v)))).isLeft))
    assert(decode(encode(fields.updated(7, a(a(V.Text("x" * 129), bs(32, 5)))))).isLeft)
  }
  test("pins, malformed originals and canonical subset are enforced") {
    val raw = encode()
    assert(GovernancePoolPayload.decode(raw, b(32, 0)).isLeft)
    assert(GovernancePoolPayload.decode(null, b(32, 0)).isLeft)
    assert(GovernancePoolPayload.decode(raw, null).isLeft)
    assert(decode(Bytes(Vector.fill(65537)(0.toByte))).isLeft)
    val indefinite = Bytes(Vector(0x9f.toByte) ++ raw.value.tail ++ Vector(0xff.toByte))
    assert(decode(indefinite).isLeft)
    assert(decode(Bytes(raw.value.dropRight(1))).isLeft)
    val wrongSetTag = fields.updated(5, V.Tag(259, n(a(bs(28, 3)))))
    assert(decode(encode(wrongSetTag)).isLeft)
  }

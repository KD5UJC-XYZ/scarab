// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value as V}

class PlutusParametersSuite extends munit.FunSuite:
  private val model =
    Bytes.fromArray(Files.readAllBytes(Path.of("vm/src/main/resources/plutus-pv9/cost-model.json")))
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def array(v: V*): V = V.Arr(v.toVector.map(n))
  private def ratio(a: BigInt, b: BigInt): V = V.Tag(30, n(array(V.UInt(a), V.UInt(b))))
  private val costs = new String(model.toArray, "UTF-8").trim
    .stripPrefix("[")
    .stripSuffix("]")
    .split(",")
    .toVector
    .map(x => BigInt(x.trim))
  private def cost(v: BigInt): V = if v < 0 then V.NInt(v) else V.UInt(v)
  private val base = Vector
    .fill[V](31)(V.UInt(0))
    .updated(0, V.UInt(44))
    .updated(1, V.UInt(155381))
    .updated(3, V.UInt(16384))
    .updated(12, array(V.UInt(9), V.UInt(0)))
    .updated(14, V.UInt(4310))
    .updated(15, V.Map(Vector(n(V.UInt(2)) -> n(V.Arr(costs.map(x => n(cost(x))))))))
    .updated(16, array(ratio(577, 10000), ratio(721, 10000000)))
    .updated(17, array(V.UInt(14000000), V.UInt(10000000000L)))
    .updated(18, array(V.UInt(62000000), V.UInt(20000000000L)))
    .updated(19, V.UInt(5000))
    .updated(20, V.UInt(150))
    .updated(21, V.UInt(3))
  private def encode(xs: Vector[V]): Bytes = Cbor.encode(V.Arr(xs.map(n))).toOption.get
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private def decode(xs: Vector[V]) =
    val raw = encode(xs)
    PlutusParameters.decode(raw, sha(raw), model)

  test("bind original parameters and exact evaluator model while projecting consumed fields") {
    val result = decode(base).fold(fail(_), identity)
    assertEquals(result.original, encode(base))
    assertEquals(result.sourceSHA256, sha(encode(base)))
    assertEquals(result.modelValues, costs)
    assertEquals(result.execution.collateralPercentage, BigInt(150))
    assertEquals(result.linear.feePerByte, BigInt(44))
    assertEquals(result.minimumOutput.coinsPerUTxOByte, BigInt(4310))
    assertEquals(result.maxValueSize, BigInt(5000))
    assert(!result.fullParameterValidity)
  }
  test("source and evaluator model mutations reject even with otherwise usable fields") {
    val raw = encode(base)
    assert(PlutusParameters.decode(raw, sha(encode(base.updated(0, V.UInt(45)))), model).isLeft)
    assert(PlutusParameters.decode(raw, sha(raw), Bytes(model.value :+ 32.toByte)).isLeft)
    val changed = V.Arr(costs.updated(0, costs.head + 1).map(x => n(cost(x))))
    assert(decode(base.updated(15, V.Map(Vector(n(V.UInt(2)) -> n(changed))))).isLeft)
  }
  test("duplicate language keys and missing V3 never become a checked model") {
    val row = n(V.UInt(2)) -> n(V.Arr(costs.map(x => n(cost(x)))))
    assert(decode(base.updated(15, V.Map(Vector(row, row)))).isLeft)
    assert(decode(base.updated(15, V.Map(Vector.empty))).isLeft)
  }
  test("version rational bounds and record shape remain explicit restrictions") {
    assert(decode(base.updated(12, array(V.UInt(11), V.UInt(0)))).isLeft)
    assert(decode(base.updated(16, array(ratio(1, 0), ratio(1, 1)))).isLeft)
    assert(decode(base.updated(16, array(ratio(2, 4), ratio(1, 1)))).isLeft)
    assert(decode(base.updated(20, V.UInt(65536))).isLeft)
    assert(decode(base.dropRight(1)).isLeft)
    assert(PlutusParameters.decode(null, null, null).isLeft)
  }

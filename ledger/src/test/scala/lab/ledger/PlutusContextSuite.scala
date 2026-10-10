// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value}
import java.nio.file.{Files, Path}

class PlutusContextSuite extends munit.FunSuite:
  private def n(v: Value) = Node(v, Bytes.empty)
  test("independent Data serializer preserves the complete pinned reference context encoding") {
    val original = Bytes.fromArray(
      Files.readAllBytes(
        Path.of("vm/src/test/resources/plutus-pv9-reference/context.cbor")
      )
    )
    val decoded = Cbor.decode(original).fold(fail(_), identity)
    assertEquals(PlutusContext.encodeData(decoded.value), Right(original))
  }
  test("Data serializer excludes non-Data forms and excessive nesting") {
    for v <- Vector(
        Value.Bool(true),
        Value.Null,
        Value.Text("not Data"),
        Value.Tag(24, n(Value.ByteString(Bytes.empty)))
      )
    do assert(PlutusContext.encodeData(v).isLeft)
    val deep = (0 until 34).foldLeft[Value](Value.UInt(0))((v, _) => Value.Arr(Vector(n(v))))
    assert(PlutusContext.encodeData(deep).isLeft)
  }

  test("uint64 amounts and explicit non-default POSIX endpoints have no synthetic cap") {
    val id = Bytes(Vector.fill(32)(1.toByte)); val credential = Bytes(Vector.fill(28)(2.toByte))
    val max = (BigInt(1) << 64) - 1
    def run(amount: BigInt, low: Option[BigInt], high: Option[BigInt]) = PlutusContext.spendData(
      id,
      id,
      0,
      credential,
      amount,
      credential,
      0,
      credential,
      amount,
      0,
      low,
      high
    )
    assert(run(max, Some(BigInt("1700000000000")), Some(BigInt("1700000000100"))).isRight)
    assert(run(max + 1, None, None).isLeft)
    assert(run(0, Some(2), Some(1)).isLeft)
    assert(run(0, Some(1), Some(1)).isRight)
  }

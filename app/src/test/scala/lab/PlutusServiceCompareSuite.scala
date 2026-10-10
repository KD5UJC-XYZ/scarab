// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.submission.SignedTransaction

class PlutusServiceCompareSuite extends munit.FunSuite:
  private def get[A](v: Either[?, A]): A = v.fold(e => fail(e.toString), identity)
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def a(v: V*): V = V.Arr(v.toVector.map(n))
  private def b(v: Bytes): V = V.ByteString(v)
  private def enc(v: V): Bytes = get(Cbor.encode(v))
  private def m(v: (Int, V)*): V = V.Map(v.toVector.map((k, v) => n(V.UInt(k)) -> n(v)))
  private def tag(v: V): V = V.Tag(258, n(v))
  private val funding = Bytes(Vector.fill(32)(1.toByte))
  private val beneficiary = Bytes(Vector.fill(28)(2.toByte))
  private val address = Bytes(Vector(0x60.toByte) ++ beneficiary.value)
  private def scriptAddress = Bytes(
    Vector(0x70.toByte) ++ lab.Blake2b.hash224.hash(Bytes(Vector(3.toByte) ++ script.value)).value
  )
  private val script = Bytes.fromArray(
    Files.readAllBytes(Path.of("vm/src/test/resources/plutus-pv9-reference/script.cbor"))
  )
  private val datum = enc(V.Tag(121, n(a(b(beneficiary), V.UInt(5000000)))))
  private val scriptOut =
    m(0 -> b(scriptAddress), 1 -> V.UInt(20000000), 2 -> a(V.UInt(1), V.Tag(24, n(b(datum)))))
  private def coin(amount: Int): V = a(b(address), V.UInt(amount))
  private def ref(id: Bytes, i: Int): V = a(b(id), V.UInt(i))
  private val witnesses = m(
    0 -> tag(a()),
    5 -> V.Map(
      Vector(n(a(V.UInt(0), V.UInt(0))) -> n(a(V.UInt(7), a(V.UInt(100000), V.UInt(30000000)))))
    ),
    7 -> tag(a(b(script)))
  )
  private def tx(i: Int): SignedTransaction = get(
    SignedTransaction.checked(
      enc(
        a(
          m(
            0 -> tag(a(ref(funding, i))),
            1 -> a(coin(19700000)),
            2 -> V.UInt(300000),
            11 -> b(Bytes(Vector.fill(32)(4.toByte))),
            13 -> tag(a(ref(funding, i + 1)))
          ),
          witnesses,
          V.Bool(true),
          V.Null
        )
      )
    )
  )
  private val txs = Vector(tx(0), tx(2))
  private val before = enc(
    V.Map(
      (0 to 4).toVector.map(i =>
        n(ref(funding, i)) -> n(if i == 0 || i == 2 then scriptOut else coin(5000000))
      )
    )
  )
  private def after(collateral: Int = 5000000): Bytes = enc(
    V.Map(
      Vector(1, 3, 4).map(i =>
        n(ref(funding, i)) -> n(coin(if i == 1 then collateral else 5000000))
      ) ++
        txs.map(t => n(ref(t.transactionId, 0)) -> n(coin(19700000)))
    )
  )
  private def check(actual: Bytes = after(), terminal: Bytes = after(), fees: BigInt = 800000) =
    PlutusServiceCompareMain.compareMaps(before, actual, terminal, txs, 200000, fees)
  test("two independent script spends preserve all collateral and unrelated outputs") {
    assertEquals(check().size, 2)
  }
  test("terminal map with altered collateral is rejected") {
    intercept[IllegalArgumentException](check(terminal = after(4999999)))
  }
  test("matching altered endpoint and terminal cannot hide collateral mutation") {
    intercept[IllegalArgumentException](check(actual = after(4999999), terminal = after(4999999)))
  }
  test("fee pot must increase by exactly both transaction fees") {
    intercept[IllegalArgumentException](check(fees = 799999))
  }
  test("duplicate transaction cannot impersonate two independent spends") {
    intercept[IllegalArgumentException](
      PlutusServiceCompareMain.compareMaps(
        before,
        after(),
        after(),
        Vector(txs.head, txs.head),
        200000,
        800000
      )
    )
  }

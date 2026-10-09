// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.cbor.{Bytes, Cbor, Node, Value}
import java.nio.file.{Files, Path}
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toCbor

class ProfileTranslatorSuite extends munit.FunSuite:
  private val root = Path.of("").toAbsolutePath.normalize()
  private def read(p: String) = Bytes.fromArray(Files.readAllBytes(root.resolve(p)))
  private val dir = "fixtures/plutus-pv9-reference/inputs/"
  private val tx = read(dir + "transaction.cbor")
  private val params = read(dir + "parameters.cbor")
  private val entries =
    Vector(0, 1).map(n => read(dir + s"input-$n.cbor") -> read(dir + s"output-$n.cbor"))
  private def run(t: Bytes = tx, p: Bytes = params, e: Vector[(Bytes, Bytes)] = entries) =
    ProfileTranslator.translate(t, p, e)
  private def node(v: Value) = Node(v, Bytes.empty)
  private def encode(v: Value) = Cbor.encode(v).fold(fail(_), identity)
  private def decoded = Cbor.decode(tx).toOption.get.value.asInstanceOf[Value.Arr].value
  private def changeWrapper(index: Int, v: Value) = encode(
    Value.Arr(decoded.updated(index, node(v)))
  )
  private def changeMap(index: Int)(f: Vector[(Node, Node)] => Vector[(Node, Node)]): Bytes =
    val fs = decoded(index).value.asInstanceOf[Value.Map].value
    changeWrapper(index, Value.Map(f(fs)))
  private def key(n: Int) = node(Value.UInt(n))
  private def reject(t: Bytes): Unit = assert(run(t).isLeft)
  test("baseline derives exact ordered Data and original reference CBOR") {
    val actual = run().fold(fail(_), identity)
    val expected = read("vm/src/test/resources/plutus-pv9-reference/context.cbor")
    assertEquals(actual, Data.fromCbor(expected.toArray))
    assertEquals(Bytes.fromArray(actual.toCbor), expected)
    assertEquals(run(e = entries.reverse), Right(actual))
  }
  test("wrapper, unknown, duplicate, required keys and integrity shape reject") {
    reject(changeWrapper(2, Value.Bool(false)))
    reject(changeWrapper(2, Value.UInt(1)))
    reject(changeWrapper(3, Value.Map(Vector.empty)))
    reject(encode(Value.Arr(decoded.take(3))))
    for position <- Vector(0, 1) do
      reject(changeMap(position)(xs => xs :+ (key(255) -> node(Value.Null))))
      reject(changeMap(position)(xs => xs :+ xs.head))
      reject(changeMap(position)(_.tail))
    reject(
      changeMap(0)(
        _.map((k, v) =>
          if k.value == Value.UInt(11) then k -> node(Value.ByteString(Bytes.empty)) else k -> v
        )
      )
    )
    reject(Bytes(tx.value :+ 0.toByte))
    reject(Bytes(Vector.fill(65537)(0.toByte)))
  }
  test("pre-state identity, parameters and collateral cannot be ignored") {
    assert(run(e = entries.take(1)).isLeft)
    assert(run(e = entries :+ entries.head).isLeft)
    assert(run(p = Bytes(params.value.updated(0, 0.toByte))).isLeft)
    val fs = decoded.head.value.asInstanceOf[Value.Map].value
    val ordinary = fs.find(_._1.value == Value.UInt(0)).get._2
    reject(
      changeMap(0)(_.map((k, v) => if k.value == Value.UInt(13) then k -> ordinary else k -> v))
    )
  }
  test("native dependencies are absent from test classpath") {
    val loader = getClass.getClassLoader
    for name <- Vector(
        "supranational/blst/P1.class",
        "scalus/crypto/NativeSecp256k1.class",
        "org/bitcoin/NativeSecp256k1.class"
      )
    do
      // Scalus may contain bridge classes; only external native implementation classes are prohibited.
      if name != "scalus/crypto/NativeSecp256k1.class" then
        assertEquals(loader.getResource(name), null)
    val cp = sys.props.getOrElse("java.class.path", "").toLowerCase
    assert(!cp.contains("blst-java") && !cp.contains("secp256k1-jni"))
  }

  test("body field allowlist and exact set/output/datum forms reject unsupported data") {
    for k <- Vector(4, 5, 6, 7, 9, 10, 12, 14, 15, 16, 17, 18, 19, 20, 21, 22) do
      reject(changeMap(0)(xs => xs :+ (key(k) -> node(Value.Null))))
    val fs = decoded.head.value.asInstanceOf[Value.Map].value
    val inputNode = fs.find(_._1.value == Value.UInt(0)).get._2
    val untagged = inputNode.value.asInstanceOf[Value.Tag].value
    reject(
      changeMap(0)(_.map((k, v) => if k.value == Value.UInt(0) then k -> untagged else k -> v))
    )
    val xs = untagged.value.asInstanceOf[Value.Arr].value
    reject(
      changeMap(0)(
        _.map((k, v) =>
          if k.value == Value.UInt(0) then k -> node(Value.Tag(258, node(Value.Arr(xs ++ xs))))
          else k -> v
        )
      )
    )
    for outputValue <- Vector(
        Value.Arr(Vector.empty),
        Value.Map(
          Vector(
            key(0) -> node(Value.Null),
            key(1) -> node(Value.UInt(1)),
            key(3) -> node(Value.Null)
          )
        )
      )
    do
      reject(
        changeMap(0)(
          _.map((k, v) =>
            if k.value == Value.UInt(1) then k -> node(Value.Arr(Vector(node(outputValue))))
            else k -> v
          )
        )
      )
    val original = Cbor.decode(entries.head._2).toOption.get.value.asInstanceOf[Value.Map].value
    val duplicate = encode(Value.Map(original :+ original.head))
    assert(run(e = entries.updated(0, entries.head._1 -> duplicate)).isLeft)
    val badDatum = encode(
      Value.Map(
        original.map((k, v) =>
          if k.value == Value.UInt(2) then
            k -> node(
              Value.Arr(
                Vector(
                  node(Value.UInt(0)),
                  node(Value.ByteString(Bytes(Vector.fill(32)(0.toByte))))
                )
              )
            )
          else k -> v
        )
      )
    )
    assert(run(e = entries.updated(0, entries.head._1 -> badDatum)).isLeft)
  }
  test("Conway intervals accept equal endpoints and reject inverted/out-of-domain bounds") {
    def bounds(low: BigInt, high: BigInt) = changeMap(0)(xs =>
      xs ++ Vector(key(8) -> node(Value.UInt(low)), key(3) -> node(Value.UInt(high)))
    )
    assert(run(bounds(0, 0)).isRight)
    assert(run(bounds(1000000, 1000000)).isRight)
    reject(bounds(10, 9))
    reject(bounds(0, 1000001))
  }

  test("malformed types, deep nesting, pointer and script mutations reject") {
    reject(Bytes.empty)
    reject(Bytes(Vector.fill(40)(0x81.toByte) :+ 0.toByte))
    val witnesses = decoded(1).value.asInstanceOf[Value.Map].value
    val script = witnesses.find(_._1.value == Value.UInt(7)).get._2
    val scriptNode =
      script.value.asInstanceOf[Value.Tag].value.value.asInstanceOf[Value.Arr].value.head
    val raw = scriptNode.value.asInstanceOf[Value.ByteString].value
    val altered = node(
      Value.Tag(
        258,
        node(Value.Arr(Vector(node(Value.ByteString(Bytes(raw.value.updated(0, 0.toByte)))))))
      )
    )
    reject(changeMap(1)(_.map((k, v) => if k.value == Value.UInt(7) then k -> altered else k -> v)))
    val redeemer =
      witnesses.find(_._1.value == Value.UInt(5)).get._2.value.asInstanceOf[Value.Map].value.head
    for pointer <- Vector(Vector(key(0), key(1)), Vector(key(1), key(0))) do
      val bad = node(Value.Map(Vector(node(Value.Arr(pointer)) -> redeemer._2)))
      reject(changeMap(1)(_.map((k, v) => if k.value == Value.UInt(5) then k -> bad else k -> v)))
  }

  test("declared budget must equal the fixed evaluated limits") {
    val ws = decoded(1).value.asInstanceOf[Value.Map].value
    val (pointer, payload) =
      ws.find(_._1.value == Value.UInt(5)).get._2.value.asInstanceOf[Value.Map].value.head
    val parts = payload.value.asInstanceOf[Value.Arr].value
    for (memory, cpu) <- Vector(
        (99999, 30000000),
        (100001, 30000000),
        (100000, 29999999),
        (100000, 30000001)
      )
    do
      val units = node(Value.Arr(Vector(node(Value.UInt(memory)), node(Value.UInt(cpu)))))
      val replacement = node(Value.Map(Vector(pointer -> node(Value.Arr(parts.updated(1, units))))))
      reject(
        changeMap(1)(_.map((k, v) => if k.value == Value.UInt(5) then k -> replacement else k -> v))
      )
  }

// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import java.nio.file.{Files, Path}

class ProfileIntegritySuite extends munit.FunSuite:
  private val root = Path.of("").toAbsolutePath.normalize()
  private def read(path: String) = Bytes.fromArray(Files.readAllBytes(root.resolve(path)))
  private def hex(s: String) = Bytes.fromHex(s).fold(fail(_), identity)
  private val dir = "fixtures/plutus-pv9-reference/inputs/"
  private val tx = read(dir + "transaction.cbor")
  private val pp = read(dir + "parameters.cbor")
  private val entries =
    Vector(0, 1).map(n => read(dir + s"input-$n.cbor") -> read(dir + s"output-$n.cbor"))
  private val model =
    Files.readString(root.resolve("vm/src/main/resources/plutus-pv9/cost-model.json"))
  private def check(
      t: Bytes = tx,
      p: Bytes = pp,
      e: Vector[(Bytes, Bytes)] = entries,
      m: String = model
  ) = ProfileIntegrity.check(t, p, e, m)
  private def node(v: Value) = Node(v, Bytes.empty)
  private def encode(v: Value) = Cbor.encode(v).fold(fail(_), identity)
  private def modify(section: Int)(f: Vector[(Node, Node)] => Vector[(Node, Node)]): Bytes =
    val parts = Cbor.decode(tx).toOption.get.value.asInstanceOf[Value.Arr].value
    val fields = parts(section).value.asInstanceOf[Value.Map].value
    encode(Value.Arr(parts.updated(section, node(Value.Map(f(fields))))))
  private def bodyCommitment(v: Option[Value]) = modify(0)(xs =>
    xs.filterNot(_._1.value == Value.UInt(11)) ++ v.map(x => node(Value.UInt(11)) -> node(x))
  )

  test("all18 original commitments match but cover only3 distinct preimages") {
    val rows = ujson
      .read(Files.readString(root.resolve("fixtures/plutus-pv9-translator/vectors.json")))(
        "vectors"
      )
      .arr
    val preimages = rows.map { row =>
      val p = row("packet")
      val es =
        p("utxo").arr.toVector.map(x => hex(x("inputCborHex").str) -> hex(x("outputCborHex").str))
      val result = check(hex(p("transactionCborHex").str), hex(p("parametersCborHex").str), es)
        .fold(fail(_), identity)
      assert(result.matches, row("name").str)
      assertEquals(result.evidence.datums, Bytes.empty)
      result.evidence.preimage
    }
    assertEquals(rows.size, 18)
    assertEquals(preimages.distinct.size, 3)
  }
  test("missing and wrong commitments mismatch; malformed values reject") {
    val baseline = check().fold(fail(_), identity)
    for value <- Vector(
        None,
        Some(Value.ByteString(Bytes(Vector.fill(32)(0.toByte)))),
        Some(
          Value.ByteString(
            Bytes(
              baseline.evidence.digest.value
                .updated(0, (baseline.evidence.digest.value.head ^ 1).toByte)
            )
          )
        )
      )
    do
      val altered = bodyCommitment(value)
      val result = check(altered).fold(fail(_), identity)
      assert(!result.matches)
      assertEquals(result.evidence, baseline.evidence)
      if value.isEmpty then assert(ProfileTranslator.translate(altered, pp, entries).isLeft)
    for value <- Vector(
        Value.Null,
        Value.ByteString(Bytes(Vector.fill(31)(0.toByte))),
        Value.UInt(0)
      )
    do assert(check(bodyCommitment(Some(value))).isLeft)
  }
  test("only pinned model and singleton V3/no witness-datum profile is admitted") {
    assert(check(m = model + " ").isLeft)
    assert(check(p = Bytes(pp.value.updated(0, 0.toByte))).isLeft)
    assert(
      check(
        modify(1)(xs =>
          xs :+ (node(Value.UInt(4)) -> node(
            Value.Tag(258, node(Value.Arr(Vector(node(Value.UInt(1))))))
          ))
        )
      ).isLeft
    )
    for absent <- Vector(5, 7) do
      assert(check(modify(1)(_.filterNot(_._1.value == Value.UInt(absent)))).isLeft)
    assert(check(modify(1)(xs => xs :+ xs.head)).isLeft)
    assert(
      check(
        modify(1)(
          _.map((k, v) =>
            if k.value == Value.UInt(5) then k -> node(Value.Arr(Vector.empty)) else k -> v
          )
        )
      ).isLeft
    )
  }
  test("language view is key2/definite251-list and preimage has no wrapper or datum placeholder") {
    val result = check().fold(fail(_), identity); val e = result.evidence
    assertEquals(e.languageView.size, 690)
    assertEquals(
      e.languageView.value.take(4),
      Vector(0xa1.toByte, 2.toByte, 0x98.toByte, 0xfb.toByte)
    )
    assertEquals(e.preimage, Bytes(e.redeemers.value ++ e.languageView.value))
    assertEquals(e.digest, Blake2b.hash256.hash(e.preimage))
    val wrongViews = Vector(
      Bytes(e.languageView.value.updated(1, 3.toByte)),
      Bytes(e.languageView.value.updated(3, 0xfa.toByte))
    )
    wrongViews.foreach(v =>
      assertNotEquals(Blake2b.hash256.hash(Bytes(e.redeemers.value ++ v.value)), e.digest)
    )
    assertNotEquals(Blake2b.hash256.hash(Bytes(Vector(0x82.toByte) ++ e.preimage.value)), e.digest)
    assertNotEquals(
      Blake2b.hash256.hash(Bytes(e.redeemers.value ++ Vector(0x80.toByte) ++ e.languageView.value)),
      e.digest
    )
  }

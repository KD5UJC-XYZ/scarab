// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}

class PlutusOutputSuite extends munit.FunSuite:
  private def get[A](x: Either[?, A]): A = x.fold(e => fail(e.toString), identity)
  private def fixture(name: String): Bytes =
    val relative = Path.of("fixtures/plutus-pv9-reference/inputs", name)
    val root = Vector(Path.of("."), Path.of("..")).find(p => Files.exists(p.resolve(relative))).get
    Bytes.fromArray(Files.readAllBytes(root.resolve(relative)))
  private def hex(s: String) = get(Bytes.fromHex(s))
  private def n(v: V) = Node(v, Bytes.empty)
  private def raw(v: V) = get(Cbor.encode(v))
  private def output = fixture("output-0.cbor")
  private def mutate(value: V => V): Bytes = raw(value(get(Cbor.decode(output)).value))
  private def mapRows(rows: Vector[(Node, Node)] => Vector[(Node, Node)]): Bytes = mutate {
    case V.Map(fs) => V.Map(rows(fs))
    case other     => other
  }
  test("authentic reference-exported output retains exact inline Data and output spans") {
    val decoded = get(PlutusOutput.decode(output, 0))
    assertEquals(decoded.original, output)
    assertEquals(decoded.coin, BigInt(5000000))
    assertEquals(decoded.kind, 7)
    val datum = decoded.datum.get
    assertEquals(datum.beneficiary, Bytes(Vector.fill(28)(0x22.toByte)))
    assertEquals(datum.minimumPayment, BigInt(2000000))
    assertEquals(datum.original, hex("d8799f581c" + "22" * 28 + "1a001e8480ff"))
    assertEquals(get(PlutusOutput.decode(decoded.original, 0)).datum.get.original, datum.original)
    assert(!decoded.fullLedgerValidated)
    assert(NativeSpending.output(get(Cbor.decode(output)), true).isLeft)
    assert(PlutusOutput.decode(output, 1).isLeft)
  }
  test(
    "reference key collateral and bounded amounts are accepted independently of synthetic five-million limit"
  ) {
    val collateral = get(PlutusOutput.decode(fixture("output-1.cbor"), 0))
    assertEquals(collateral.coin, BigInt(10000000))
    assertEquals(collateral.kind, 6)
    assertEquals(collateral.datum, None)
    val high = mapRows(
      _.map((k, v) =>
        if k.value == V.UInt(1) then k -> n(V.UInt((BigInt(1) << 64) - 1)) else k -> v
      )
    )
    assertEquals(get(PlutusOutput.decode(high, 0)).coin, (BigInt(1) << 64) - 1)
  }
  test(
    "nonminimal original encodings remain lossless across complete snapshot checkpoint/restore"
  ) {
    val wider = hex(output.hex.replace("1a001e8480", "1b00000000001e8480"))
    // Embedded BinaryData length changes from 39 to 43 as the integer widens.
    val fixed = hex(wider.hex.replace("d8185827", "d818582b"))
    assertEquals(get(PlutusOutput.decode(fixed, 0)).datum.get.minimumPayment, BigInt(2000000))
    val input = fixture("input-0.cbor")
    val snapshot = Bytes(Vector(0xbf.toByte) ++ input.value ++ fixed.value ++ Vector(0xff.toByte))
    val state = get(PlutusOutput.snapshot(snapshot, 0))
    val restored = get(PlutusOutput.snapshot(state.original, 0))
    assertEquals(restored.original, snapshot)
    assertEquals(restored.outputs.values.head.original, fixed)
    assertEquals(
      restored.outputs.values.head.datum.get.original,
      get(PlutusOutput.decode(fixed, 0)).datum.get.original
    )
    assert(ConwayStake.decodeUtxo(snapshot).isLeft)
  }
  test(
    "unsupported output features, duplicate semantic fields, datum tags and malformed Data reject"
  ) {
    val fs = get(Cbor.decode(output)).value.asInstanceOf[V.Map].value
    Vector(
      V.Map(fs :+ fs.head),
      V.Map(fs :+ (n(V.UInt(3)) -> n(V.Null))),
      V.Map(
        fs.map((k, v) =>
          if k.value == V.UInt(1) then k -> n(V.Arr(Vector(n(V.UInt(1)), n(V.Map(Vector.empty)))))
          else k -> v
        )
      ),
      V.Arr(fs.map(_._2))
    ).foreach(v => assert(PlutusOutput.decode(raw(v), 0).isLeft))
    assert(PlutusOutput.decode(hex(output.hex.replace("708b", "608b")), 0).isLeft)
    assert(PlutusOutput.decode(hex(output.hex.replace("d818", "d819")), 0).isLeft)
    assert(PlutusOutput.decode(Bytes(output.value :+ 0.toByte), 0).isLeft)
    assert(PlutusOutput.decode(null, 0).isLeft)
    assert(PlutusOutput.decode(Bytes(Vector.fill(4097)(0.toByte)), 0).isLeft)
    Vector("d87a80", "d87980", "d87982581b" + "22" * 27 + "00", "d87982581c" + "22" * 28 + "20")
      .foreach(d => assert(PlutusOutput.decodeDatum(hex(d)).isLeft))
    assert(PlutusOutput.decodeDatum(Bytes(Vector.fill(257)(0.toByte))).isLeft)
  }
  test(
    "duplicate semantic UTxO keys and truncated embedded data reject without a partial snapshot"
  ) {
    val key = fixture("input-0.cbor")
    val duplicate =
      Bytes(Vector(0xa2.toByte) ++ key.value ++ output.value ++ key.value ++ output.value)
    assert(PlutusOutput.snapshot(duplicate, 0).isLeft)
    for length <- 0 until output.size do
      assert(PlutusOutput.decode(Bytes(output.value.take(length)), 0).isLeft)
  }

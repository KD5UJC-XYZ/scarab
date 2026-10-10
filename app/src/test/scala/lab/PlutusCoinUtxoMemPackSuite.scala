// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{PlutusOutput, ConwayStake}

/** Authentic exported CBOR outputs, with source-derived synthetic native MemPack framing. These
  * tests are not native execution or live acquisition evidence.
  */
class PlutusCoinUtxoMemPackSuite extends munit.FunSuite:
  private def get[A](x: Either[?, A]): A = x.fold(e => fail(e.toString), identity)
  private def n(v: V) = Node(v, Bytes.empty)
  private def raw(v: V) = get(Cbor.encode(v))
  private def bs(b: Bytes) = n(V.ByteString(b))
  private def fixture(name: String): Bytes =
    val relative = Path.of("fixtures/plutus-pv9-reference/inputs", name)
    val root = Vector(Path.of("."), Path.of("..")).find(p => Files.exists(p.resolve(relative))).get
    Bytes.fromArray(Files.readAllBytes(root.resolve(relative)))
  private def variable(n: BigInt): Vector[Byte] =
    if n < 128 then Vector(n.toByte)
    else variable(n >> 7).map(b => (b | 128).toByte) :+ (n & 127).toByte
  private val inline = get(PlutusOutput.decode(fixture("output-0.cbor"), 0))
  private val collateral = get(PlutusOutput.decode(fixture("output-1.cbor"), 0))
  private def key(name: String): Bytes =
    val fields = get(Cbor.decode(fixture(name))).value.asInstanceOf[V.Arr].value
    val id = fields(0).value.asInstanceOf[V.ByteString].value
    val index = fields(1).value.asInstanceOf[V.UInt].value.toInt
    Bytes(id.value ++ Vector(index.toByte, (index >> 8).toByte))
  private def packed(out: PlutusOutput.Output): Bytes =
    Bytes(
      Vector((if out.datum.nonEmpty then 4 else 0).toByte, 29.toByte) ++ out.address.value ++
        Vector(0.toByte) ++ variable(out.coin) ++ out.datum.toVector.flatMap(d =>
          variable(d.original.size) ++ d.original.value
        )
    )
  private def packet(rows: Vector[(Bytes, Bytes)]): Bytes = raw(
    V.Map(rows.map((k, v) => bs(k) -> bs(v)))
  )
  private def one(v: Bytes): Bytes = packet(Vector(key("input-0.cbor") -> v))
  private def complete = packet(
    Vector(key("input-0.cbor") -> packed(inline), key("input-1.cbor") -> packed(collateral))
  )

  test("source-derived constructor 4 recovers authentic reference output and exact BinaryData") {
    val decoded = get(PlutusCoinUtxoMemPack.decode(complete, 0))
    assertEquals(decoded.originalNative, complete)
    val outputs = decoded.snapshot.outputs.values.toVector
    assertEquals(outputs.map(_.original).toSet, Set(inline.original, collateral.original))
    assertEquals(outputs.flatMap(_.datum).head.original, inline.datum.get.original)
    assertEquals(get(PlutusOutput.snapshot(decoded.utxo, 0)).original, decoded.utxo)
    assert(NativeCoinUtxoMemPack.decode(complete).isLeft)
    assert(PlutusCoinUtxoMemPack.decode(complete, 1).isLeft)
    assert(!decoded.fullLedgerValidated)
  }
  test(
    "all native truncations, invalid length encodings, trailing bytes and excluded constructors fail"
  ) {
    val original = packed(inline)
    for length <- 0 until original.size do
      assert(PlutusCoinUtxoMemPack.decode(one(Bytes(original.value.take(length))), 0).isLeft)
    val prefix = original.value.take(original.size - inline.datum.get.original.size - 1)
    val bad = Vector(
      original.value :+ 0.toByte,
      prefix ++ Vector(0x80.toByte, 39.toByte) ++ inline.datum.get.original.value,
      prefix ++ variable(257) ++ Vector.fill(257)(0.toByte),
      original.value.updated(0, 5.toByte),
      original.value.updated(0, 1.toByte),
      original.value.updated(31, 1.toByte),
      original.value.updated(1, 28.toByte)
    )
    bad.foreach(v => assert(PlutusCoinUtxoMemPack.decode(one(Bytes(v)), 0).isLeft))
    assert(
      PlutusCoinUtxoMemPack
        .decode(packet(Vector.fill(2)(key("input-0.cbor") -> original)), 0)
        .isLeft
    )
    assert(PlutusCoinUtxoMemPack.decode(null, 0).isLeft)
  }
  test("datum-aware source component preserves checkpoint bytes and every stake output original") {
    val decoded = get(PlutusCoinUtxoMemPack.decode(complete, 0))
    val utxo = decoded.utxo
    val pin = ClusterHeaderObservation.sha256(utxo)
    val seed = get(PlutusStakeSeed.decode(utxo, pin, 0, Map.empty))
    assertEquals(seed.checkpointBytes, utxo)
    assertEquals(
      seed.stakeOutputs.values.map(_.original).toSet,
      Set(inline.original, collateral.original)
    )
    assertEquals(seed.instantaneous, Map.empty[ConwayStake.Credential, BigInt])
    assert(PlutusStakeSeed.decode(utxo, Bytes(Vector.fill(32)(0.toByte)), 0, Map.empty).isLeft)
    val fake = Map(ConwayStake.Credential(false, inline.datum.get.beneficiary) -> BigInt(1))
    assert(PlutusStakeSeed.decode(utxo, pin, 0, fake).isLeft)
    assert(ConwayStake.decodeUtxo(utxo).isLeft)
  }
  test("datum does not contribute stake; base key stake sums exactly and detects overflow") {
    val credential = Bytes(Vector.fill(28)(3.toByte))
    val address = Bytes(Vector(0.toByte) ++ collateral.paymentCredential.value ++ credential.value)
    val base = n(V.Arr(Vector(bs(address), n(V.UInt(10000000)))))
    val existing = get(Cbor.decode(get(PlutusCoinUtxoMemPack.decode(complete, 0)).utxo)).value
      .asInstanceOf[V.Map]
      .value
    val utxo = raw(V.Map(existing.updated(1, existing(1)._1 -> base)))
    val exported = Map(ConwayStake.Credential(false, credential) -> BigInt(10000000))
    val seed = get(PlutusStakeSeed.decode(utxo, ClusterHeaderObservation.sha256(utxo), 0, exported))
    assertEquals(seed.instantaneous, exported)
    assertEquals(seed.snapshot.outputs.values.flatMap(_.datum).size, 1)
    val maximum = (BigInt(1) << 64) - 1
    val baseMax = n(V.Arr(Vector(bs(address), n(V.UInt(maximum)))))
    val baseOne = n(V.Arr(Vector(bs(address), n(V.UInt(1)))))
    val thirdRef = n(V.Arr(Vector(bs(Bytes(Vector.fill(32)(9.toByte))), n(V.UInt(0)))))
    val overflow =
      raw(V.Map(existing.updated(1, existing(1)._1 -> baseMax) :+ (thirdRef -> baseOne)))
    assert(
      PlutusStakeSeed
        .decode(
          overflow,
          ClusterHeaderObservation.sha256(overflow),
          0,
          Map(ConwayStake.Credential(false, credential) -> (maximum + 1))
        )
        .isLeft
    )

  }

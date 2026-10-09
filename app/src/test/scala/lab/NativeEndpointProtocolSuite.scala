// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosNonceEvolution as N

/** Generated protocol records only. No native results or captured keys. */
class NativeEndpointProtocolSuite extends munit.FunSuite:
  private def get[A](value: Either[?, A]): A =
    value.fold(e => fail(e.toString), value => value)
  private def bytes(n: Int, width: Int = 32) = Bytes(Vector.fill(width)(n.toByte))
  private def node(v: V) = Node(v, Bytes.empty)
  private def array(v: V*) = V.Arr(v.toVector.map(node))
  private val issuer = bytes(1, 28)
  private val counters = Map(issuer -> BigInt(7))
  private val fields = N.Fields(
    N.Nonce.Hash(bytes(2)),
    N.Nonce.Hash(bytes(3)),
    N.Nonce.Hash(bytes(4)),
    Some(N.Nonce.Neutral),
    N.Nonce.Hash(bytes(5)),
    N.Nonce.Hash(bytes(6))
  )
  private def nonce(n: N.Nonce): V = n match
    case N.Nonce.Neutral => array(V.UInt(0))
    case N.Nonce.Hash(b) => array(V.UInt(1), V.ByteString(b))
  private def decoded(
      f: N.Fields = fields,
      cs: Map[Bytes, BigInt] = counters,
      slot: BigInt = 1001
  ): NativePraosProtocol.Decoded =
    val raw = get(
      Cbor.encode(
        array(
          V.UInt(0),
          array(
            array(V.UInt(1), V.UInt(slot)),
            V.Map(
              cs.toVector.sortBy(_._1.hex).map((k, v) => node(V.ByteString(k)) -> node(V.UInt(v)))
            ),
            nonce(f.evolving),
            nonce(f.candidate),
            nonce(f.epoch),
            nonce(f.previousEpoch.get),
            nonce(f.lab),
            nonce(f.lastEpochBlock)
          )
        )
      )
    )
    get(NativePraosProtocol.decode(raw))
  private def check(f: N.Fields = fields, cs: Map[Bytes, BigInt] = counters, slot: BigInt = 1001) =
    NativeEndpointProtocol.compareValues(decoded(), slot, f, cs)

  test(
    "all decoded nonce values and exact counter domain compare without state identity equality"
  ) {
    assertEquals(check(), Right(()))
    val native = decoded()
    assertEquals(native.snapshot.fields, fields)
    assertEquals(native.counters, counters)
    assertEquals(native.digest, ClusterHeaderObservation.sha256(native.original))
  }
  test("every represented nonce field has an independent mismatch diagnostic") {
    val different = N.Nonce.Hash(bytes(99))
    val cases = Vector(
      "evolving" -> fields.copy(evolving = different),
      "candidate" -> fields.copy(candidate = different),
      "epoch" -> fields.copy(epoch = different),
      "previousEpoch" -> fields.copy(previousEpoch = Some(different)),
      "lab" -> fields.copy(lab = different),
      "lastEpochBlock" -> fields.copy(lastEpochBlock = different)
    )
    cases.foreach { (label, changed) =>
      assert(check(f = changed).left.exists(_.contains("protocol." + label + " mismatch")), label)
    }
  }
  test("explicit previous Neutral is not unknown or a zero hash") {
    assert(check(f = fields.copy(previousEpoch = None)).left.exists(_.contains("previousEpoch")))
    val zeroPrevious = fields.copy(previousEpoch = Some(N.Nonce.Hash(bytes(0))))
    assert(check(f = zeroPrevious).left.exists(_.contains("previousEpoch")))
    val allNeutral = N.Fields(
      N.Nonce.Neutral,
      N.Nonce.Neutral,
      N.Nonce.Neutral,
      Some(N.Nonce.Neutral),
      N.Nonce.Neutral,
      N.Nonce.Neutral
    )
    assertEquals(
      NativeEndpointProtocol.compareValues(decoded(allNeutral), 1001, allNeutral, counters),
      Right(())
    )
  }
  test("last slot and counter values are checked independently") {
    assert(check(slot = 1002).left.exists(_.contains("lastSlot")))
    assert(
      check(cs = counters.updated(issuer, 8)).left
        .exists(_.contains("protocol.counters[" + issuer.hex + "] mismatch"))
    )
  }
  test("counter missing extra and absent versus zero domains remain distinct") {
    assert(check(cs = Map.empty).left.exists(_.contains("counters domain")))
    assert(check(cs = counters.updated(bytes(9, 28), 0)).left.exists(_.contains("counters domain")))
    assert(
      NativeEndpointProtocol
        .compareValues(decoded(cs = Map.empty), 1001, fields, Map(issuer -> BigInt(0)))
        .left
        .exists(_.contains("counters domain"))
    )
  }
  test("missing comparison objects fail instead of implying equality") {
    assert(NativeEndpointProtocol.compareValues(null, 1001, fields, counters).isLeft)
    assert(NativeEndpointProtocol.compareValues(decoded(), 1001, null, counters).isLeft)
    assert(NativeEndpointProtocol.compare(null, null, null, null).isLeft)
  }

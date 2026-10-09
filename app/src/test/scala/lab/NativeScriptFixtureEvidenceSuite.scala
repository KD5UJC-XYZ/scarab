// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayStake, NativeScript, TxIn}

/** Synthetic bytes only: no key generation, reference invocation or live admission claim. */
class NativeScriptFixtureEvidenceSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
  private def hex(s: String) = get(Bytes.fromHex(s))
  private def n(v: V) = Node(v, Bytes.empty)
  private def a(xs: V*): V = V.Arr(xs.toVector.map(n))
  private def m(xs: (V, V)*): V = V.Map(xs.toVector.map((k, v) => n(k) -> n(v)))
  private def b(raw: Bytes): V = V.ByteString(raw)
  private def enc(v: V): Bytes = get(Cbor.encode(v))
  private val txid = hex("11" * 32)
  private val input = get(TxIn.create(txid, 0))
  private val scriptRaw = hex("8200581c" + "22" * 28)
  private val script = get(NativeScript.decode(scriptRaw))
  private val scriptAddress = Bytes(Vector(0x70.toByte) ++ script.hash.value)
  private val keyAddress = hex("60" + "33" * 28)
  private def variable(n: BigInt): Vector[Byte] =
    if n < 128 then Vector(n.toByte)
    else variable(n >> 7).map(x => (x | 128).toByte) :+ (n & 127).toByte
  private def mempackRow(index: Int, address: Bytes, coin: BigInt): (V, V) =
    b(Bytes(txid.value ++ Vector(index.toByte, 0.toByte))) ->
      b(Bytes(Vector(0.toByte, 29.toByte) ++ address.value ++ Vector(0.toByte) ++ variable(coin)))
  private val mempack = enc(
    m(mempackRow(0, scriptAddress, 20000000), mempackRow(1, keyAddress, 29800000))
  )
  private def whole(
      address: Bytes = scriptAddress,
      amount: BigInt = 20000000,
      mapped: Boolean = false
  ): Bytes =
    def out(addr: Bytes, coin: BigInt) =
      if mapped then m(V.UInt(0) -> b(addr), V.UInt(1) -> V.UInt(coin))
      else a(b(addr), V.UInt(coin))
    enc(
      m(
        a(b(txid), V.UInt(0)) -> out(address, amount),
        a(b(txid), V.UInt(1)) -> out(keyAddress, 29800000)
      )
    )
  private def seed(utxo: V, fees: BigInt = 200000): Bytes = enc(
    a(
      V.UInt(0),
      m(),
      m(),
      a(
        a(V.UInt(0), V.UInt(10)),
        a(a(m(), m(), m()), a(utxo, V.UInt(0), V.UInt(fees), m(), m(), V.UInt(0))),
        a(m(), m(), m(), V.UInt(0)),
        a(m(), V.UInt(0))
      ),
      a(),
      m(),
      V.Null
    )
  )
  private val originalSeed = seed(get(Cbor.decode(mempack)).value)
  private val debug = seed(m())
  private def check(
      raw: Bytes = whole(),
      scriptBytes: Bytes = scriptRaw,
      seedBytes: Bytes = originalSeed,
      debugBytes: Bytes = debug,
      pack: Bytes = mempack,
      expected: TxIn = input
  ) =
    NativeScriptFixtureEvidence.bootstrap(
      pack,
      raw,
      seedBytes,
      debugBytes,
      scriptBytes,
      expected,
      20000000
    )

  test(
    "kind-7 bootstrap survives MemPack, ConwayStake and endpoint replacement with fee-pot siblings unchanged"
  ) {
    for mapped <- Vector(false, true) do
      val raw = whole(mapped = mapped)
      val receipt = get(check(raw))
      assertEquals(receipt.scriptHash, script.hash)
      assertEquals(receipt.entries, 2)
      assertEquals(get(ConwayStake.recompute(raw)), Map.empty)
      assert(!receipt.fullLedgerValidated)
      assert(!receipt.runtimeImport)
      assertEquals(receipt.scope, "reference-only-fixture-funding")
  }
  test("script hash uses original CBOR and zero native-language prefix") {
    assertEquals(script.hash.hex, "6d88a61d44faea8672dd738057445517c8cb5fbcece60610df803b85")
    val noncanonical = hex("821800581c" + "22" * 28)
    assertNotEquals(get(NativeScript.decode(noncanonical)).hash, script.hash)
    assert(check(scriptBytes = noncanonical).isLeft)
  }
  test("whole-state mismatch, changed fee-pot sibling and wrong funded reference fail") {
    assert(check(whole(amount = 20000001)).isLeft)
    assert(check(whole(address = keyAddress)).isLeft)
    assert(check(debugBytes = seed(m(), 200001)).isLeft)
    assert(check(expected = get(TxIn.create(txid, 2))).isLeft)
    assert(check(pack = hex("a0")).isLeft)
  }
  test("mainnet kind-7 and datum-bearing outputs cannot enter the bootstrap proof") {
    val mainnet = Bytes(Vector(0x71.toByte) ++ script.hash.value)
    assert(check(whole(address = mainnet)).isLeft)
    val datum = enc(
      m(
        a(b(txid), V.UInt(0)) -> m(
          V.UInt(0) -> b(scriptAddress),
          V.UInt(1) -> V.UInt(20000000),
          V.UInt(2) -> a(V.UInt(0), b(txid))
        )
      )
    )
    assert(check(datum).isLeft)
  }
  test("exact submitted body and witness spans are independently bound to follower inclusion") {
    val original = hex("9fa1001800a10080f5f6ff")
    val evidence =
      get(NativeScriptFixtureEvidence.inclusionOriginals(original, hex("a1001800"), hex("a10080")))
    assertEquals(evidence.bytes, original.size)
    assert(
      NativeScriptFixtureEvidence.inclusionOriginals(original, hex("a10000"), hex("a10080")).isLeft
    )
    assert(
      NativeScriptFixtureEvidence.inclusionOriginals(original, hex("a1001800"), hex("a0")).isLeft
    )
    val variant = hex("84a1001800a0f5f6")
    val other = get(NativeScriptFixtureEvidence.originals(variant))
    assertEquals(evidence.transactionId, other.transactionId)
    assertNotEquals(evidence.envelopeSHA256, other.envelopeSHA256)
    assertNotEquals(evidence.witnessesSHA256, other.witnessesSHA256)
  }

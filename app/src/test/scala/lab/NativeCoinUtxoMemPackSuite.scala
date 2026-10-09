// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}

/** Synthetic codec tests, not native execution evidence. */
class NativeCoinUtxoMemPackSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def node(v: V): Node = Node(v, Bytes.empty)
  private def bs(xs: Vector[Byte]): Node = node(V.ByteString(Bytes(xs)))
  private def key(index: Int = 0): Vector[Byte] =
    Vector.tabulate(32)(_.toByte) ++ Vector(index.toByte, (index >> 8).toByte)
  private def variable(n: BigInt): Vector[Byte] =
    if n < 128 then Vector(n.toByte)
    else variable(n >> 7).map(b => (b | 128).toByte) :+ (n & 127).toByte
  private val payment = Vector.tabulate(28)(i => (i + 16).toByte)
  private val stake = Vector.tabulate(28)(i => (i + 80).toByte)
  private def enterprise(coin: BigInt = 300, header: Int = 0x60): Vector[Byte] =
    Vector(0.toByte, 29.toByte, header.toByte) ++ payment ++ Vector(0.toByte) ++ variable(coin)
  private def base(coin: BigInt, stakeTag: Int, flags: Int): Vector[Byte] =
    val first = payment.take(24).grouped(8).flatMap(_.reverse).toVector
    val last = Vector(flags.toByte, 0.toByte, 0.toByte, 0.toByte) ++ payment.drop(24).reverse
    Vector(2.toByte, stakeTag.toByte) ++ stake ++ first ++ last ++ Vector(0.toByte) ++ variable(
      coin
    )
  private def packet(rows: Vector[(Vector[Byte], Vector[Byte])]): Bytes =
    get(Cbor.encode(V.Map(rows.map((k, v) => bs(k) -> bs(v)))))
  private def one(v: Vector[Byte], index: Int = 0): Bytes = packet(Vector(key(index) -> v))
  private def expected(index: Int, address: Vector[Byte], coin: BigInt): Bytes =
    get(
      Cbor.encode(
        V.Map(
          Vector(
            node(V.Arr(Vector(bs(key().take(32)), node(V.UInt(index))))) ->
              node(V.Arr(Vector(bs(address), node(V.UInt(coin)))))
          )
        )
      )
    )

  test("enterprise address and little-endian nonzero TxIn index are preserved") {
    for header <- Vector(0x60, 0x61, 0x70, 0x71) do
      assertEquals(
        get(NativeCoinUtxoMemPack.decode(one(enterprise(300, header), 0x1234))),
        expected(0x1234, Vector(header.toByte) ++ payment, 300)
      )
  }
  test("base payment words and both credential tag conventions are preserved") {
    for stakeTag <- Vector(0, 1); flags <- 0 to 3 do
      val h = ((flags >> 1) & 1) | ((1 - (flags & 1)) << 4) | ((1 - stakeTag) << 5)
      assertEquals(
        get(NativeCoinUtxoMemPack.decode(one(base(300, stakeTag, flags)))),
        expected(0, Vector(h.toByte) ++ payment ++ stake, 300)
      )
  }
  test("coin boundary encodings retain unsigned values") {
    for coin <- Vector(BigInt(0), BigInt(127), BigInt(128), (BigInt(1) << 64) - 1) do
      assertEquals(
        get(NativeCoinUtxoMemPack.decode(one(enterprise(coin)))),
        expected(0, Vector(0x60.toByte) ++ payment, coin)
      )
  }
  test("noncanonical, overflowing, unterminated and trailing varints reject") {
    val prefix = enterprise(0).dropRight(1)
    for suffix <- Vector(
        Vector(0x80.toByte, 0.toByte),
        Vector.fill(11)(0x81.toByte),
        variable(BigInt(1) << 64),
        Vector(0x81.toByte),
        Vector(0.toByte, 0.toByte)
      )
    do assert(NativeCoinUtxoMemPack.decode(one(prefix ++ suffix)).isLeft)
  }
  test("unsupported constructors, values, address lengths and reserved bits reject") {
    val cases = Vector(
      enterprise().updated(0, 1.toByte),
      enterprise().updated(0, 3.toByte),
      enterprise().updated(0, 4.toByte),
      enterprise().updated(0, 5.toByte),
      enterprise().updated(1, 28.toByte),
      enterprise().updated(2, 0x62.toByte),
      enterprise().updated(2, 0x00.toByte),
      enterprise().updated(31, 1.toByte),
      base(1, 2, 1),
      base(1, 1, 4),
      base(1, 1, 1).updated(55, 1.toByte)
    )
    cases.foreach(v => assert(NativeCoinUtxoMemPack.decode(one(v)).isLeft))
  }
  test("duplicates, malformed keys and every truncated output reject") {
    val v = base(300, 1, 1)
    assert(NativeCoinUtxoMemPack.decode(packet(Vector(key() -> v, key() -> v))).isLeft)
    for size <- Vector(0, 33, 35) do
      assert(NativeCoinUtxoMemPack.decode(packet(Vector(Vector.fill(size)(0.toByte) -> v))).isLeft)
    for n <- 0 until v.size do assert(NativeCoinUtxoMemPack.decode(one(v.take(n))).isLeft)
  }
  test("outer map types, null input, total bytes and entry counts are bounded") {
    assert(NativeCoinUtxoMemPack.decode(null).isLeft)
    assert(NativeCoinUtxoMemPack.decode(Bytes(Vector.fill(524289)(0.toByte))).isLeft)
    assert(NativeCoinUtxoMemPack.decode(get(Cbor.encode(V.Arr(Vector.empty)))).isLeft)
    val badKey = get(Cbor.encode(V.Map(Vector(node(V.UInt(0)) -> bs(enterprise())))))
    assert(NativeCoinUtxoMemPack.decode(badKey).isLeft)
    assert(
      NativeCoinUtxoMemPack
        .decode(packet(Vector.tabulate(4097)(i => key(i) -> enterprise())))
        .isLeft
    )
    assert(
      NativeCoinUtxoMemPack
        .decode(packet(Vector.tabulate(4096)(i => key(i) -> enterprise())))
        .isRight
    )
  }
  test("full CBOR consumption and empty map") {
    val raw = one(enterprise())
    assert(NativeCoinUtxoMemPack.decode(Bytes(raw.value :+ 0.toByte)).isLeft)
    val empty = packet(Vector.empty)
    assertEquals(get(NativeCoinUtxoMemPack.decode(empty)), empty)
  }
